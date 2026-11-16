package io.github.coldworld22.llmgateway.routing;

import io.github.coldworld22.llmgateway.config.GatewayProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * A small per-provider circuit breaker. After N consecutive failures a provider is skipped for a
 * while instead of making every request wait for its timeout first; when that time is up, one
 * request is let through to test it (half-open).
 *
 * <p>State is per instance on purpose: each instance learns about an outage within a few requests,
 * and keeping it local avoids a Redis round trip on the hot path.
 */
@Component
public class CircuitBreakers {

    private static final class State {
        int consecutiveFailures;
        Instant openUntil;
        boolean trialInFlight;
    }

    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final GatewayProperties.Breaker config;
    private final Clock clock;

    public CircuitBreakers(GatewayProperties properties, Clock clock) {
        this.config = properties.breaker();
        this.clock = clock;
    }

    public boolean allows(String provider) {
        State state = states.computeIfAbsent(provider, p -> new State());
        synchronized (state) {
            if (state.openUntil == null) {
                return true;
            }
            if (clock.instant().isBefore(state.openUntil) || state.trialInFlight) {
                return false;
            }
            state.trialInFlight = true;
            return true;
        }
    }

    public void recordSuccess(String provider) {
        State state = states.computeIfAbsent(provider, p -> new State());
        synchronized (state) {
            state.consecutiveFailures = 0;
            state.openUntil = null;
            state.trialInFlight = false;
        }
    }

    public void recordFailure(String provider) {
        State state = states.computeIfAbsent(provider, p -> new State());
        synchronized (state) {
            state.trialInFlight = false;
            state.consecutiveFailures++;
            if (state.consecutiveFailures >= config.failureThreshold()) {
                state.openUntil = clock.instant().plus(config.openDuration());
            }
        }
    }

    public boolean isOpen(String provider) {
        State state = states.get(provider);
        return state != null && state.openUntil != null;
    }
}
