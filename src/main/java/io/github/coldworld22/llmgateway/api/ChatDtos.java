package io.github.coldworld22.llmgateway.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** The subset of the OpenAI chat-completions format the gateway accepts and returns. */
public final class ChatDtos {

    private ChatDtos() {}

    public record Message(
            @NotBlank @Pattern(regexp = "system|user|assistant") String role,
            @NotBlank @Size(max = 100_000) String content) {}

    /**
     * @param model a route name from configuration (e.g. chat-default), not a provider's model name
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChatRequest(
            @NotBlank String model,
            @NotEmpty @Size(max = 100) List<@Valid Message> messages,
            @DecimalMin("0.0") @DecimalMax("2.0") Double temperature,
            @JsonProperty("max_tokens") @Min(1) @Max(16_384) Integer maxTokens) {}

    public record Choice(int index, Message message, @JsonProperty("finish_reason") String finishReason) {}

    public record Usage(
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens,
            @JsonProperty("total_tokens") int totalTokens) {}

    public record ChatResponse(String id, String object, long created, String model, List<Choice> choices, Usage usage) {}
}
