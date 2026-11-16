package io.github.coldworld22.llmgateway.safety;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Replaces personal data with placeholders before a prompt leaves for a model provider, and puts
 * the real values back into the answer. The provider only ever sees [NATIONAL_ID_1], never the ID.
 *
 * <p>Rules run in order, most specific first, so an IBAN's digits aren't also matched as a phone
 * number. Card numbers must pass the Luhn check, which keeps ordinary long numbers untouched.
 * Pattern matching catches well-formed identifiers; it is a safety net, not a guarantee.
 */
@Component
public class PiiRedactor {

    private record Rule(String label, Pattern pattern, Predicate<String> accept) {}

    private static final List<Rule> RULES = List.of(
            new Rule("IBAN", Pattern.compile("\\bSA\\d{2}[0-9A-Z]{20}\\b"), v -> true),
            new Rule("CARD", Pattern.compile("(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)"), PiiRedactor::passesLuhn),
            new Rule("PHONE", Pattern.compile("(?<![\\d+])(?:\\+966|00966|0)5\\d{8}(?!\\d)"), v -> true),
            new Rule("NATIONAL_ID", Pattern.compile("(?<!\\d)[12]\\d{9}(?!\\d)"), v -> true),
            new Rule("EMAIL", Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), v -> true));

    /** The masked texts plus the placeholder-to-value map needed to restore the answer. */
    public record Result(List<String> texts, Map<String, String> replacements) {

        public int count() {
            return replacements.size();
        }

        public String restore(String text) {
            String restored = text;
            for (Map.Entry<String, String> entry : replacements.entrySet()) {
                restored = restored.replace(entry.getKey(), entry.getValue());
            }
            return restored;
        }
    }

    /**
     * Masks several texts with one shared numbering, so the same value gets the same placeholder in
     * every message of a conversation.
     */
    public Result redact(List<String> texts) {
        Map<String, String> valueToPlaceholder = new LinkedHashMap<>();
        Map<String, Integer> counters = new LinkedHashMap<>();
        List<String> masked = new ArrayList<>();

        for (String text : texts) {
            String current = text;
            for (Rule rule : RULES) {
                Matcher matcher = rule.pattern().matcher(current);
                StringBuilder out = new StringBuilder();
                while (matcher.find()) {
                    String value = matcher.group();
                    String replacement = value;
                    if (rule.accept().test(value)) {
                        replacement = valueToPlaceholder.computeIfAbsent(value, v -> {
                            int n = counters.merge(rule.label(), 1, Integer::sum);
                            return "[" + rule.label() + "_" + n + "]";
                        });
                    }
                    matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
                }
                matcher.appendTail(out);
                current = out.toString();
            }
            masked.add(current);
        }

        Map<String, String> placeholderToValue = new LinkedHashMap<>();
        valueToPlaceholder.forEach((value, placeholder) -> placeholderToValue.put(placeholder, value));
        return new Result(masked, placeholderToValue);
    }

    static boolean passesLuhn(String candidate) {
        String digits = candidate.replaceAll("[ -]", "");
        if (digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }
}
