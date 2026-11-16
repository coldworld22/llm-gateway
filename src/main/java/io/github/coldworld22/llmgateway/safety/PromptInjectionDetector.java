package io.github.coldworld22.llmgateway.safety;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Flags the most common prompt-injection phrasings in user messages. It's a cheap first filter
 * that catches copy-pasted attacks, not a defence on its own: the real protection is that models
 * behind this gateway get no tools or data the caller isn't already allowed to see.
 */
@Component
public class PromptInjectionDetector {

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("ignore\\s+(all\\s+|any\\s+)?(the\\s+)?(previous|prior|above|earlier)\\s+(instructions|prompts|rules)"),
            Pattern.compile("disregard\\s+(all\\s+|the\\s+|your\\s+)?(previous|prior|system)\\s+(instructions|prompt|rules)"),
            Pattern.compile("(reveal|print|show|repeat)\\s+(me\\s+)?(your|the)\\s+(system|hidden|initial)\\s+(prompt|instructions)"),
            Pattern.compile("you\\s+are\\s+now\\s+(in\\s+)?(developer|dan|jailbreak|god)\\s*mode"),
            Pattern.compile("pretend\\s+(that\\s+)?you\\s+(have|are)\\s+no\\s+(rules|restrictions|guidelines)"));

    public boolean isSuspicious(String text) {
        String normalized = text.toLowerCase().replaceAll("\\s+", " ");
        return PATTERNS.stream().anyMatch(p -> p.matcher(normalized).find());
    }
}
