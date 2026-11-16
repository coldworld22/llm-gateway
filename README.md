# llm-gateway

[![CI](https://github.com/coldworld22/llm-gateway/actions/workflows/ci.yml/badge.svg)](https://github.com/coldworld22/llm-gateway/actions/workflows/ci.yml)

A service that sits between internal apps and LLM providers (OpenAI, Azure OpenAI and Ollama). Apps call
one OpenAI-compatible endpoint. The gateway decides which provider answers and falls back when one is
down. It masks personal data before it leaves, caches repeat questions, enforces per-team rate limits
and monthly token budgets, and records what each team spends.

Java 21 · Spring Boot 3.3 · PostgreSQL · Redis · Flyway · Micrometer/Prometheus · Testcontainers

## Why

When several teams start using LLMs, each one ends up with its own API key, its own retry logic and no
idea what it costs. Customer data, national IDs and IBANs in particular, gets pasted into prompts and
sent to a third party. This gateway puts those concerns in one place:

| Problem | What the gateway does |
|---|---|
| One provider has an outage | Routes try providers in order; a circuit breaker skips a failing one instead of waiting for its timeout on every request |
| Personal data in prompts | National IDs, Iqama numbers, IBANs, phone numbers, cards (Luhn-checked) and emails are replaced with placeholders before the request leaves, then put back in the answer |
| Nobody knows the bill | Every request is stored with tokens, model, cost and latency; `GET /v1/usage` shows a team its month |
| One team uses everything | Per-team requests-per-minute (shared across instances through Redis) and a monthly token budget |
| Paying twice for the same answer | Deterministic requests (`temperature: 0`) are cached per team |
| Prompt injection | Common injection phrasings are rejected before any model sees them |

## Request pipeline

```
client ──► auth (API key → team)
       ──► rate limit (Redis)  ──► monthly budget (Postgres)
       ──► prompt-injection check
       ──► mask PII  ──► cache lookup ──hit──────────────┐
       ──► route: provider 1 ─fail─► provider 2 ─► ...   │
       ──► restore PII in the answer ◄───────────────────┘
       ──► record usage + metrics ──► response
```

Each step is its own class (`limits`, `safety`, `cache`, `routing`, `usage`), so each one can be tested
and replaced on its own.

## Run it locally

```bash
docker compose up --build
```

The `demo` profile creates a team called `demo-team` with the key `local-demo-key`. With a local
[Ollama](https://ollama.com) running `llama3.2`, no cloud keys are needed:

```bash
curl -s localhost:8080/v1/chat/completions \
  -H 'Authorization: Bearer local-demo-key' \
  -H 'Content-Type: application/json' \
  -d '{"model":"chat-local","messages":[{"role":"user","content":"My ID is 1012345678. What is an IBAN?"}]}'

curl -s localhost:8080/v1/usage -H 'Authorization: Bearer local-demo-key'
curl -s localhost:8080/actuator/prometheus | grep gateway_
```

To use the cloud route (`chat-default`: Azure → OpenAI → Ollama), set `AZURE_OPENAI_BASE_URL`,
`AZURE_OPENAI_API_KEY`, `AZURE_OPENAI_DEPLOYMENT` and/or `OPENAI_API_KEY` before `docker compose up`.

Existing OpenAI SDKs work unchanged; only the base URL and key change:

```python
from openai import OpenAI
client = OpenAI(base_url="http://localhost:8080/v1", api_key="local-demo-key")
client.chat.completions.create(model="chat-local", messages=[{"role": "user", "content": "hi"}])
```

## Configuration

Providers, routes and prices are all in [`application.yml`](src/main/resources/application.yml).
Adding a model or changing the fallback order is a config change:

```yaml
gateway:
  routes:
    chat-default:
      - { provider: azure,  model: gpt-4o-mini }
      - { provider: openai, model: gpt-4o-mini }
      - { provider: ollama, model: llama3.2 }
```

Clients send the route name (`chat-default`) as `model`. Clients never pick a provider, so moving
traffic from one provider to another needs no client release.

## Design decisions

**OpenAI's API shape, not a new one.** Azure OpenAI's v1 API and Ollama both accept OpenAI's
chat-completions format. One HTTP client covers all three providers, and callers can use the official
SDKs. Errors use OpenAI's error format too.

**Mask, don't block, personal data.** Rejecting every prompt with an ID number would just teach people to
work around the gateway. The model rarely needs the real value to answer, so it gets `[NATIONAL_ID_1]`,
and the caller still sees the real value in the answer. The same value gets the same placeholder across
a conversation, so the model can still refer to it. Rules run most specific first (IBAN before phone
before ID), and card numbers must pass the Luhn check, so order numbers and amounts are left alone. This
is pattern matching. It catches well-formed identifiers but isn't a replacement for a data-classification
policy.

**Circuit breaker per instance.** Without one, every request during an outage waits the full timeout on
the dead provider before falling back. The breaker opens after N consecutive failures and lets a single
trial request through after a cool-down. Its state is in memory on purpose. Each instance notices an
outage within a few requests, and the hot path doesn't need a Redis round trip.

**Rate limit in Redis, budget in Postgres.** The rate limit has to be shared across instances and checked
on every request, so it is an atomic `INCR` on a one-minute key. The budget is checked against
`usage_records`, which also serves the usage report, so the two never disagree. The request that crosses
the budget still completes, so a team can overshoot by at most one response. That is cheaper than
reserving tokens up front for a response whose length isn't known yet.

**Cache only deterministic requests, per team.** A cached answer is only correct when the caller asked for
`temperature: 0`. Keys are a SHA-256 of the team, route, settings and *masked* prompt, so personal data is
never stored in Redis, and one team can never be served another team's answer. Cache errors are treated
as misses; Redis being down never fails a request.

**Store costs, not prompts.** Each usage row stores the cost at the price that applied at the time, so a
price change doesn't rewrite history. Prompts and answers are never stored, so the usage table doesn't
become a second copy of customer data.

**API keys are hashed.** Only a SHA-256 of each key is stored. Keys are long random strings, so a fast hash
is enough (passwords would need a slow one). A team can hold several keys, so keys can be rotated without
downtime.

**Virtual threads.** Almost all of a request's time is spent waiting for the model. With
`spring.threads.virtual.enabled`, that waiting doesn't hold a platform thread, so the simple blocking code
scales without moving to a reactive stack.

## Observability

`/actuator/prometheus` exposes:

| Metric | Tags |
|---|---|
| `gateway_provider_latency_seconds` | provider, outcome |
| `gateway_provider_skipped_total` (breaker open) | provider |
| `gateway_tokens_total` | tenant, provider, type |
| `gateway_cost_usd_total` | tenant, provider |
| `gateway_cache_total` | result (hit / miss / bypass) |
| `gateway_pii_redacted_total` | tenant |
| `gateway_injection_flagged_total` | tenant |

Liveness and readiness probes are at `/actuator/health/liveness` and `/actuator/health/readiness`.

## Tests

```bash
mvn verify   # needs Docker for Testcontainers
```

The integration tests run the whole pipeline against real PostgreSQL and Redis containers. Two stub
HTTP servers stand in for the model providers, so CI needs no API keys. The tests cover the following:

- personal data never reaches the provider but comes back in the answer
- fallback to the next provider, and a 503 when all of them fail
- cache hits, and no cache sharing between teams
- token and cost recording, the usage report, and the budget and rate limits
- prompt-injection rejection, and revoked or unknown keys
- the Prometheus output

Unit tests cover the PII rules (including Luhn and false positives), the injection patterns and the
circuit breaker's state changes.

## Not done yet

- Streaming responses (`stream: true`, server-sent events)
- Embeddings endpoint
- An admin API for creating teams and keys (today it's SQL)
- Token counting before the call, to reject requests that would clearly exceed the budget

## License

MIT
