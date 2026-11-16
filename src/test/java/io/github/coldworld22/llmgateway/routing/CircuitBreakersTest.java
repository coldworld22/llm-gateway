package io.github.coldworld22.llmgateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.coldworld22.llmgateway.config.GatewayProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class CircuitBreakersTest {

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-16T10:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final CircuitBreakers breakers = new CircuitBreakers(new GatewayProperties(
            null, null, null, null, null, new GatewayProperties.Breaker(3, Duration.ofSeconds(30)), true), clock);

    @Test
    void opensAfterThresholdAndLetsOneTrialThroughLater() {
        breakers.recordFailure("openai");
        breakers.recordFailure("openai");
        assertThat(breakers.allows("openai")).isTrue();

        breakers.recordFailure("openai");
        assertThat(breakers.allows("openai")).isFalse();

        clock.now = clock.now.plusSeconds(31);
        assertThat(breakers.allows("openai")).as("trial request").isTrue();
        assertThat(breakers.allows("openai")).as("only one trial at a time").isFalse();

        breakers.recordSuccess("openai");
        assertThat(breakers.allows("openai")).isTrue();
        assertThat(breakers.isOpen("openai")).isFalse();
    }

    @Test
    void failedTrialReopensTheBreaker() {
        for (int i = 0; i < 3; i++) {
            breakers.recordFailure("azure");
        }
        clock.now = clock.now.plusSeconds(31);
        assertThat(breakers.allows("azure")).isTrue();

        breakers.recordFailure("azure");

        assertThat(breakers.allows("azure")).isFalse();
    }

    @Test
    void successResetsTheFailureCount() {
        breakers.recordFailure("ollama");
        breakers.recordFailure("ollama");
        breakers.recordSuccess("ollama");
        breakers.recordFailure("ollama");

        assertThat(breakers.allows("ollama")).isTrue();
    }
}
