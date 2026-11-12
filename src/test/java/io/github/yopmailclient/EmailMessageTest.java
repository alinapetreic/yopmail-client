package io.github.yopmailclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class EmailMessageTest {
    private static EmailMessage message(String text) {
        return new EmailMessage(new EmailSummary("one", "QA", "Verify", "Today"), text, "", List.of());
    }

    @Test
    void preservesLeadingZerosAndIgnoresLongerNumbers() {
        assertEquals("004219", message("PIN: 004219. Order: 1234567").findPin(6).orElseThrow());
    }

    @Test
    void refusesToGuessBetweenDifferentPins() {
        var email = message("Order: 123456. PIN: 004219.");
        assertThrows(IllegalStateException.class, () -> email.findPin(6));
        assertEquals(
                "004219", email.findCode(Pattern.compile("PIN:\\s*(\\d{6})")).orElseThrow());
    }

    @Test
    void repeatedSamePinIsNotAmbiguous() {
        assertEquals("004219", message("004219 repeated 004219").findPin(6).orElseThrow());
    }

    @Test
    void extractsAlphanumericCodesUsingTemplatePattern() {
        assertEquals(
                "AB-09XZ",
                message("Verification code: AB-09XZ")
                        .findCode(Pattern.compile("code:\\s*([A-Z0-9-]+)"))
                        .orElseThrow());
    }

    @Test
    void returnsEmptyWhenThereIsNoCode() {
        assertTrue(message("Nothing here; abc123456def is an identifier")
                .findPin(6)
                .isEmpty());
    }

    @Test
    void toStringDoesNotDiscloseBodyOrCodes() {
        assertFalse(message("PIN 004219").toString().contains("004219"));
    }
}
