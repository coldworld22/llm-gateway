package io.github.coldworld22.llmgateway.safety;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PiiRedactorTest {

    private final PiiRedactor redactor = new PiiRedactor();

    @Test
    void masksSaudiIdentifiersAndRestoresThem() {
        String text = "My ID is 1012345678, IBAN SA0380000000608010167519, call 0551234567 or mail a.b@example.com";

        PiiRedactor.Result result = redactor.redact(List.of(text));

        String masked = result.texts().get(0);
        assertThat(masked)
                .contains("[NATIONAL_ID_1]", "[IBAN_1]", "[PHONE_1]", "[EMAIL_1]")
                .doesNotContain("1012345678", "SA0380000000608010167519", "0551234567", "a.b@example.com");
        assertThat(result.restore(masked)).isEqualTo(text);
    }

    @Test
    void sameValueGetsSamePlaceholderAcrossMessages() {
        PiiRedactor.Result result = redactor.redact(List.of("ID 2123456789", "again: 2123456789", "other 1098765432"));

        assertThat(result.texts()).containsExactly("ID [NATIONAL_ID_1]", "again: [NATIONAL_ID_1]", "other [NATIONAL_ID_2]");
        assertThat(result.count()).isEqualTo(2);
    }

    @Test
    void masksInternationalPhoneFormat() {
        assertThat(redactor.redact(List.of("+966551234567")).texts()).containsExactly("[PHONE_1]");
    }

    @Test
    void masksOnlyCardNumbersThatPassLuhn() {
        PiiRedactor.Result result = redactor.redact(List.of("card 4111 1111 1111 1111, order 4111111111111112"));

        assertThat(result.texts().get(0)).isEqualTo("card [CARD_1], order 4111111111111112");
    }

    @Test
    void leavesOrdinaryNumbersAlone() {
        String text = "Order 12345 shipped on 2026-11-16, total 349.99 SAR, 3000000000 is not an ID";

        assertThat(redactor.redact(List.of(text)).texts()).containsExactly(text);
    }
}
