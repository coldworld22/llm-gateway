package io.github.coldworld22.llmgateway.routing;

import io.github.coldworld22.llmgateway.api.GatewayException;
import io.github.coldworld22.llmgateway.config.GatewayProperties;
import io.github.coldworld22.llmgateway.config.GatewayProperties.Target;
import io.github.coldworld22.llmgateway.routing.ProviderClient.Completion;
import io.github.coldworld22.llmgateway.routing.ProviderClient.Prompt;
import io.github.coldworld22.llmgateway.routing.ProviderClient.ProviderFailure;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Tries a route's targets in order, skipping providers whose breaker is open. */
@Component
public class Router {

    private static final Logger log = LoggerFactory.getLogger(Router.class);

    public record Routed(String provider, String model, Completion completion, int attempts) {}

    private final GatewayProperties properties;
    private final ProviderClient client;
    private final CircuitBreakers breakers;
    private final MeterRegistry metrics;

    public Router(GatewayProperties properties, ProviderClient client, CircuitBreakers breakers, MeterRegistry metrics) {
        this.properties = properties;
        this.client = client;
        this.breakers = breakers;
        this.metrics = metrics;
    }

    public Routed route(String routeName, Prompt prompt) {
        List<Target> targets = properties.routes().get(routeName);
        if (targets == null) {
            throw new GatewayException(HttpStatus.NOT_FOUND, "model_not_found", "Unknown model: " + routeName);
        }

        int attempts = 0;
        for (Target target : targets) {
            if (!breakers.allows(target.provider())) {
                metrics.counter("gateway.provider.skipped", "provider", target.provider()).increment();
                continue;
            }
            attempts++;
            Timer.Sample sample = Timer.start(metrics);
            try {
                Completion completion = client.complete(target.provider(), target.model(), prompt);
                breakers.recordSuccess(target.provider());
                sample.stop(metrics.timer("gateway.provider.latency", "provider", target.provider(), "outcome", "success"));
                return new Routed(target.provider(), target.model(), completion, attempts);
            } catch (ProviderFailure e) {
                breakers.recordFailure(target.provider());
                sample.stop(metrics.timer("gateway.provider.latency", "provider", target.provider(), "outcome", "failure"));
                log.warn("Route {} falling back after {}: {}", routeName, target.provider(), e.getMessage());
            }
        }
        throw new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, "providers_unavailable",
                "No provider for " + routeName + " is available right now.");
    }
}
