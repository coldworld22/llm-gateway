package io.github.coldworld22.llmgateway.limits;

import io.github.coldworld22.llmgateway.api.GatewayException;
import io.github.coldworld22.llmgateway.auth.ApiKeyAuthenticator.Tenant;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Per-tenant request rate (shared across instances through Redis) and monthly token budget. */
@Component
public class TenantLimits {

    private final StringRedisTemplate redis;
    private final JdbcClient jdbc;
    private final Clock clock;

    public TenantLimits(StringRedisTemplate redis, JdbcClient jdbc, Clock clock) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Fixed one-minute window: INCR is atomic, so every instance counts against the same number. */
    public void checkRate(Tenant tenant) {
        long minute = clock.millis() / 60_000;
        String key = "ratelimit:" + tenant.id() + ":" + minute;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            redis.expire(key, Duration.ofMinutes(2));
        }
        if (count != null && count > tenant.requestsPerMinute()) {
            throw new GatewayException(HttpStatus.TOO_MANY_REQUESTS, "rate_limit_exceeded",
                    "Rate limit of " + tenant.requestsPerMinute() + " requests per minute reached.");
        }
    }

    /**
     * Stops new requests once this month's tokens reach the budget. The request that crosses the
     * line still completes, so a tenant can overshoot by at most one response.
     */
    public void checkBudget(Tenant tenant) {
        long used = tokensThisMonth(tenant);
        if (used >= tenant.monthlyTokenBudget()) {
            throw new GatewayException(HttpStatus.TOO_MANY_REQUESTS, "budget_exceeded",
                    "Monthly token budget of " + tenant.monthlyTokenBudget() + " is used up.");
        }
    }

    public long tokensThisMonth(Tenant tenant) {
        return jdbc.sql("""
                        select coalesce(sum(prompt_tokens + completion_tokens), 0)
                        from usage_records where tenant_id = :tenant and created_at >= :since
                        """)
                .param("tenant", tenant.id())
                .param("since", java.sql.Timestamp.from(monthStart()))
                .query(Long.class)
                .single();
    }

    public Instant monthStart() {
        return YearMonth.now(clock.withZone(ZoneOffset.UTC)).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    }
}
