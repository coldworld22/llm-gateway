package io.github.coldworld22.llmgateway.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything about providers, routes and policies lives in configuration, so adding a model or
 * changing the fallback order is a config change, not a code change.
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
        Map<String, Provider> providers,
        Map<String, List<Target>> routes,
        Map<String, Price> pricing,
        Duration providerTimeout,
        Duration cacheTtl,
        Breaker breaker,
        boolean blockPromptInjection) {

    /**
     * An OpenAI-compatible endpoint: OpenAI, Azure OpenAI (v1 API) and Ollama all accept the same
     * chat-completions request.
     *
     * @param baseUrl    e.g. https://api.openai.com/v1
     * @param apiKey     empty for providers that need none (a local Ollama)
     * @param authHeader Authorization for OpenAI, api-key for Azure
     * @param authPrefix "Bearer " for OpenAI, empty for Azure
     */
    public record Provider(String baseUrl, String apiKey, String authHeader, String authPrefix) {}

    /** One step in a route: which provider, and the model name that provider knows. */
    public record Target(String provider, String model) {}

    /** USD per one million tokens. */
    public record Price(double inputPerMillion, double outputPerMillion) {}

    /**
     * @param failureThreshold consecutive failures before a provider is skipped
     * @param openDuration     how long it is skipped before one trial request is let through
     */
    public record Breaker(int failureThreshold, Duration openDuration) {}
}
