package io.github.coldworld22.llmgateway.usage;

import io.github.coldworld22.llmgateway.auth.ApiKeyAuthenticator.Tenant;
import io.github.coldworld22.llmgateway.config.GatewayProperties;
import io.github.coldworld22.llmgateway.config.GatewayProperties.Price;
import io.github.coldworld22.llmgateway.routing.ProviderClient.Completion;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * One row per request: who, which provider and model, tokens, cost and latency. Cost is stored
 * per row at the price that applied then, so a later price change doesn't rewrite history.
 * Prompts and answers are never stored.
 */
@Component
public class UsageRecorder {

    public record Entry(UUID requestId, Tenant tenant, String route, String provider, String model,
            Completion completion, boolean cacheHit, int piiRedacted, long latencyMs) {}

    public record ModelUsage(String provider, String model, long requests, long promptTokens,
            long completionTokens, BigDecimal costUsd) {}

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final JdbcClient jdbc;
    private final GatewayProperties properties;
    private final MeterRegistry metrics;

    public UsageRecorder(JdbcClient jdbc, GatewayProperties properties, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.metrics = metrics;
    }

    public void record(Entry e) {
        // A cache hit costs nothing upstream, so it is logged with zero tokens and zero cost.
        int prompt = e.cacheHit() ? 0 : e.completion().promptTokens();
        int completion = e.cacheHit() ? 0 : e.completion().completionTokens();
        BigDecimal cost = cost(e.model(), prompt, completion);

        jdbc.sql("""
                        insert into usage_records (id, tenant_id, route, provider, model, prompt_tokens,
                            completion_tokens, cost_usd, cache_hit, pii_redacted, latency_ms)
                        values (:id, :tenant, :route, :provider, :model, :prompt, :completion,
                            :cost, :cacheHit, :pii, :latency)
                        """)
                .param("id", e.requestId())
                .param("tenant", e.tenant().id())
                .param("route", e.route())
                .param("provider", e.provider())
                .param("model", e.model())
                .param("prompt", prompt)
                .param("completion", completion)
                .param("cost", cost)
                .param("cacheHit", e.cacheHit())
                .param("pii", e.piiRedacted())
                .param("latency", e.latencyMs())
                .update();

        String tenant = e.tenant().name();
        metrics.counter("gateway.tokens", "tenant", tenant, "provider", e.provider(), "type", "prompt").increment(prompt);
        metrics.counter("gateway.tokens", "tenant", tenant, "provider", e.provider(), "type", "completion").increment(completion);
        metrics.counter("gateway.cost.usd", "tenant", tenant, "provider", e.provider()).increment(cost.doubleValue());
    }

    BigDecimal cost(String model, int promptTokens, int completionTokens) {
        Price price = properties.pricing() == null ? null : properties.pricing().get(model);
        if (price == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(price.inputPerMillion()).multiply(BigDecimal.valueOf(promptTokens))
                .add(BigDecimal.valueOf(price.outputPerMillion()).multiply(BigDecimal.valueOf(completionTokens)))
                .divide(MILLION, 6, RoundingMode.HALF_UP);
    }

    public List<ModelUsage> byModel(UUID tenantId, Instant since) {
        return jdbc.sql("""
                        select provider, model, count(*) as requests, sum(prompt_tokens) as prompt_tokens,
                               sum(completion_tokens) as completion_tokens, sum(cost_usd) as cost_usd
                        from usage_records
                        where tenant_id = :tenant and created_at >= :since
                        group by provider, model
                        order by cost_usd desc
                        """)
                .param("tenant", tenantId)
                .param("since", Timestamp.from(since))
                .query((rs, n) -> new ModelUsage(rs.getString("provider"), rs.getString("model"),
                        rs.getLong("requests"), rs.getLong("prompt_tokens"), rs.getLong("completion_tokens"),
                        rs.getBigDecimal("cost_usd")))
                .list();
    }
}
