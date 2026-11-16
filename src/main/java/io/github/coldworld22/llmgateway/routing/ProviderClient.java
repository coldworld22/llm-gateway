package io.github.coldworld22.llmgateway.routing;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.coldworld22.llmgateway.config.GatewayProperties;
import io.github.coldworld22.llmgateway.config.GatewayProperties.Provider;
import java.net.http.HttpClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Talks to any OpenAI-compatible chat-completions endpoint. One client per provider is built once
 * at startup with its base URL, auth header and timeout.
 */
@Component
public class ProviderClient {

    public record Prompt(List<Map<String, String>> messages, Double temperature, Integer maxTokens) {}

    public record Completion(String content, String finishReason, int promptTokens, int completionTokens) {}

    /** Thrown for anything that should make the router try the next provider. */
    public static class ProviderFailure extends RuntimeException {
        public ProviderFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record UpstreamRequest(String model, List<Map<String, String>> messages, Double temperature,
            @JsonProperty("max_tokens") Integer maxTokens) {}

    record UpstreamMessage(String role, String content) {}

    record UpstreamChoice(UpstreamMessage message, @JsonProperty("finish_reason") String finishReason) {}

    record UpstreamUsage(@JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens) {}

    record UpstreamResponse(List<UpstreamChoice> choices, UpstreamUsage usage) {}

    private final Map<String, RestClient> clients = new HashMap<>();

    public ProviderClient(GatewayProperties properties, RestClient.Builder builder) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(properties.providerTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.providerTimeout());

        properties.providers().forEach((name, provider) -> clients.put(name, build(builder.clone(), factory, provider)));
    }

    private static RestClient build(RestClient.Builder builder, JdkClientHttpRequestFactory factory, Provider p) {
        builder.baseUrl(p.baseUrl()).requestFactory(factory);
        if (p.apiKey() != null && !p.apiKey().isBlank()) {
            String header = p.authHeader() == null ? "Authorization" : p.authHeader();
            String prefix = p.authPrefix() == null ? "" : p.authPrefix();
            builder.defaultHeader(header, prefix + p.apiKey());
        }
        return builder.build();
    }

    public Completion complete(String provider, String model, Prompt prompt) {
        RestClient client = clients.get(provider);
        if (client == null) {
            throw new IllegalStateException("Unknown provider in route: " + provider);
        }
        try {
            UpstreamResponse response = client.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new UpstreamRequest(model, prompt.messages(), prompt.temperature(), prompt.maxTokens()))
                    .retrieve()
                    .body(UpstreamResponse.class);
            if (response == null || response.choices() == null || response.choices().isEmpty()) {
                throw new ProviderFailure(provider + " returned no choices", null);
            }
            UpstreamChoice choice = response.choices().get(0);
            UpstreamUsage usage = response.usage() == null ? new UpstreamUsage(0, 0) : response.usage();
            return new Completion(choice.message().content(), choice.finishReason(),
                    usage.promptTokens(), usage.completionTokens());
        } catch (ProviderFailure e) {
            throw e;
        } catch (RuntimeException e) {
            // Timeouts, 5xx, 429 and malformed bodies all mean "try the next one".
            throw new ProviderFailure(provider + " failed: " + e.getClass().getSimpleName(), e);
        }
    }
}
