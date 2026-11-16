package io.github.coldworld22.llmgateway.auth;

import io.github.coldworld22.llmgateway.api.GatewayException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Resolves the caller's tenant from its gateway API key. Only a SHA-256 hash of each key is stored,
 * so a leaked database doesn't leak working keys. Keys are long random strings, so a fast hash is
 * enough here; passwords would need a slow one.
 */
@Component
public class ApiKeyAuthenticator {

    public record Tenant(UUID id, String name, long monthlyTokenBudget, int requestsPerMinute) {}

    private final JdbcClient jdbc;

    public ApiKeyAuthenticator(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Tenant authenticate(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw unauthorized();
        }
        String key = authorizationHeader.substring("Bearer ".length()).trim();
        return jdbc.sql("""
                        select t.id, t.name, t.monthly_token_budget, t.requests_per_minute
                        from api_keys k join tenants t on t.id = k.tenant_id
                        where k.key_hash = :hash and k.revoked_at is null
                        """)
                .param("hash", sha256(key))
                .query((rs, n) -> new Tenant(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getLong("monthly_token_budget"), rs.getInt("requests_per_minute")))
                .optional()
                .orElseThrow(ApiKeyAuthenticator::unauthorized);
    }

    public static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static GatewayException unauthorized() {
        return new GatewayException(HttpStatus.UNAUTHORIZED, "authentication_error", "Invalid or missing API key.");
    }
}
