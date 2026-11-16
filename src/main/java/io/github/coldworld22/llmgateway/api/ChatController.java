package io.github.coldworld22.llmgateway.api;

import io.github.coldworld22.llmgateway.api.ChatDtos.ChatRequest;
import io.github.coldworld22.llmgateway.api.ChatDtos.ChatResponse;
import io.github.coldworld22.llmgateway.auth.ApiKeyAuthenticator;
import io.github.coldworld22.llmgateway.auth.ApiKeyAuthenticator.Tenant;
import io.github.coldworld22.llmgateway.limits.TenantLimits;
import io.github.coldworld22.llmgateway.service.ChatService;
import io.github.coldworld22.llmgateway.usage.UsageRecorder;
import io.github.coldworld22.llmgateway.usage.UsageRecorder.ModelUsage;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatController {

    public record UsageReport(String tenant, long tokenBudget, long tokensUsed, BigDecimal costUsd,
            List<ModelUsage> byModel) {}

    private final ApiKeyAuthenticator auth;
    private final ChatService chat;
    private final UsageRecorder usage;
    private final TenantLimits limits;

    public ChatController(ApiKeyAuthenticator auth, ChatService chat, UsageRecorder usage, TenantLimits limits) {
        this.auth = auth;
        this.chat = chat;
        this.usage = usage;
        this.limits = limits;
    }

    /** Same path and body as OpenAI, so existing SDKs work by changing only the base URL. */
    @PostMapping("/v1/chat/completions")
    public ChatResponse complete(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody ChatRequest request) {
        Tenant tenant = auth.authenticate(authorization);
        return chat.chat(tenant, request);
    }

    /** The caller's own usage this month; a tenant can't see anyone else's. */
    @GetMapping("/v1/usage")
    public UsageReport usage(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        Tenant tenant = auth.authenticate(authorization);
        List<ModelUsage> rows = usage.byModel(tenant.id(), limits.monthStart());
        BigDecimal cost = rows.stream().map(ModelUsage::costUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new UsageReport(tenant.name(), tenant.monthlyTokenBudget(), limits.tokensThisMonth(tenant), cost, rows);
    }
}
