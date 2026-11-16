package io.github.coldworld22.llmgateway.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.coldworld22.llmgateway.auth.ApiKeyAuthenticator;
import io.github.coldworld22.llmgateway.config.GatewayProperties;
import io.github.coldworld22.llmgateway.routing.ProviderClient.Completion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Caches answers to identical deterministic requests (temperature 0) so the same question isn't
 * paid for twice. Keys include the tenant, so one tenant can never read another's answers, and
 * are built from the already-masked prompt, so no personal data is stored in Redis.
 */
@Component
public class ResponseCache {

    private static final Logger log = LoggerFactory.getLogger(ResponseCache.class);

    public record Cached(String provider, String model, Completion completion) {}

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final GatewayProperties properties;

    public ResponseCache(StringRedisTemplate redis, ObjectMapper json, GatewayProperties properties) {
        this.redis = redis;
        this.json = json;
        this.properties = properties;
    }

    public String key(UUID tenant, String route, List<String> roles, List<String> maskedContents, Integer maxTokens) {
        String material = tenant + "|" + route + "|" + maxTokens + "|" + roles + "|" + String.join("\u0000", maskedContents);
        return "cache:" + ApiKeyAuthenticator.sha256(material);
    }

    public Optional<Cached> get(String key) {
        try {
            String value = redis.opsForValue().get(key);
            return value == null ? Optional.empty() : Optional.of(json.readValue(value, Cached.class));
        } catch (Exception e) {
            // A cache problem must never fail the request; it just means a miss.
            log.warn("Cache read failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void put(String key, Cached value) {
        try {
            redis.opsForValue().set(key, json.writeValueAsString(value), properties.cacheTtl());
        } catch (Exception e) {
            log.warn("Cache write failed: {}", e.getMessage());
        }
    }
}
