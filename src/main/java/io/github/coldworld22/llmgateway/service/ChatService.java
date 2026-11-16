package io.github.coldworld22.llmgateway.service;

import io.github.coldworld22.llmgateway.api.ChatDtos.ChatRequest;
import io.github.coldworld22.llmgateway.api.ChatDtos.ChatResponse;
import io.github.coldworld22.llmgateway.api.ChatDtos.Choice;
import io.github.coldworld22.llmgateway.api.ChatDtos.Message;
import io.github.coldworld22.llmgateway.api.ChatDtos.Usage;
import io.github.coldworld22.llmgateway.api.GatewayException;
import io.github.coldworld22.llmgateway.auth.ApiKeyAuthenticator.Tenant;
import io.github.coldworld22.llmgateway.cache.ResponseCache;
import io.github.coldworld22.llmgateway.cache.ResponseCache.Cached;
import io.github.coldworld22.llmgateway.config.GatewayProperties;
import io.github.coldworld22.llmgateway.limits.TenantLimits;
import io.github.coldworld22.llmgateway.routing.ProviderClient.Completion;
import io.github.coldworld22.llmgateway.routing.ProviderClient.Prompt;
import io.github.coldworld22.llmgateway.routing.Router;
import io.github.coldworld22.llmgateway.routing.Router.Routed;
import io.github.coldworld22.llmgateway.safety.PiiRedactor;
import io.github.coldworld22.llmgateway.safety.PromptInjectionDetector;
import io.github.coldworld22.llmgateway.usage.UsageRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The request pipeline: limits → injection check → PII masking → cache → route with fallback →
 * restore PII → record usage. Each step is its own class so it can be tested and changed alone.
 */
@Service
public class ChatService {

    private final TenantLimits limits;
    private final PromptInjectionDetector injection;
    private final PiiRedactor redactor;
    private final ResponseCache cache;
    private final Router router;
    private final UsageRecorder usage;
    private final GatewayProperties properties;
    private final MeterRegistry metrics;
    private final Clock clock;

    public ChatService(TenantLimits limits, PromptInjectionDetector injection, PiiRedactor redactor,
            ResponseCache cache, Router router, UsageRecorder usage, GatewayProperties properties,
            MeterRegistry metrics, Clock clock) {
        this.limits = limits;
        this.injection = injection;
        this.redactor = redactor;
        this.cache = cache;
        this.router = router;
        this.usage = usage;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    public ChatResponse chat(Tenant tenant, ChatRequest request) {
        long started = clock.millis();
        limits.checkRate(tenant);
        limits.checkBudget(tenant);
        checkInjection(tenant, request);

        List<String> roles = request.messages().stream().map(Message::role).toList();
        PiiRedactor.Result masked = redactor.redact(request.messages().stream().map(Message::content).toList());
        if (masked.count() > 0) {
            metrics.counter("gateway.pii.redacted", "tenant", tenant.name()).increment(masked.count());
        }

        boolean cacheable = request.temperature() != null && request.temperature() == 0.0;
        String cacheKey = cacheable
                ? cache.key(tenant.id(), request.model(), roles, masked.texts(), request.maxTokens())
                : null;

        Optional<Cached> hit = cacheable ? cache.get(cacheKey) : Optional.empty();
        metrics.counter("gateway.cache", "result", cacheable ? (hit.isPresent() ? "hit" : "miss") : "bypass").increment();

        Cached result = hit.orElseGet(() -> {
            Routed routed = router.route(request.model(), new Prompt(
                    zip(roles, masked.texts()), request.temperature(), request.maxTokens()));
            Cached fresh = new Cached(routed.provider(), routed.model(), routed.completion());
            if (cacheable) {
                cache.put(cacheKey, fresh);
            }
            return fresh;
        });

        UUID requestId = UUID.randomUUID();
        Completion completion = result.completion();
        usage.record(new UsageRecorder.Entry(requestId, tenant, request.model(), result.provider(), result.model(),
                completion, hit.isPresent(), masked.count(), clock.millis() - started));

        String answer = masked.restore(completion.content());
        return new ChatResponse(
                "chatcmpl-" + requestId,
                "chat.completion",
                clock.instant().getEpochSecond(),
                result.model(),
                List.of(new Choice(0, new Message("assistant", answer), completion.finishReason())),
                new Usage(completion.promptTokens(), completion.completionTokens(),
                        completion.promptTokens() + completion.completionTokens()));
    }

    private void checkInjection(Tenant tenant, ChatRequest request) {
        boolean suspicious = request.messages().stream()
                .filter(m -> m.role().equals("user"))
                .anyMatch(m -> injection.isSuspicious(m.content()));
        if (!suspicious) {
            return;
        }
        metrics.counter("gateway.injection.flagged", "tenant", tenant.name()).increment();
        if (properties.blockPromptInjection()) {
            throw new GatewayException(HttpStatus.BAD_REQUEST, "prompt_rejected",
                    "The request looks like a prompt-injection attempt.");
        }
    }

    private static List<Map<String, String>> zip(List<String> roles, List<String> contents) {
        return java.util.stream.IntStream.range(0, roles.size())
                .mapToObj(i -> Map.of("role", roles.get(i), "content", contents.get(i)))
                .toList();
    }
}
