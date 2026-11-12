package io.github.yopmailclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class YopmailHtmlTest {
    static String fixture(String name) {
        try (var input = YopmailHtmlTest.class.getResourceAsStream("/" + name)) {
            if (input == null) {
                throw new IllegalArgumentException("Missing fixture: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    @Test
    void readsInboxOrderAndDecodedHeaders() {
        var summaries = YopmailHtml.summaries(fixture("inbox-page-1.html"));
        assertEquals(2, summaries.size());
        assertEquals("newest", summaries.get(0).id());
        assertEquals("QA <noreply@example.test>", summaries.get(0).sender());
        assertEquals("Today 10:42", summaries.get(0).receivedLabel());
        assertEquals("older", summaries.get(1).id());
    }

    @Test
    void extractsOnlyTheEmailBodyAndSafeAbsoluteLinks() {
        var summary = YopmailHtml.summaries(fixture("inbox-page-1.html")).get(0);
        var email = YopmailHtml.message(summary, fixture("message.html"));
        assertEquals("004219", email.findPin(6).orElseThrow());
        assertFalse(email.text().contains("Delete"));
        assertEquals(1, email.links().size());
        assertEquals("token=test-token&source=email", email.links().get(0).getQuery());
        assertTrue(email.findLink(uri -> "accounts.example.test".equals(uri.getHost()))
                .isPresent());
        assertTrue(email.findLink(uri -> "elsewhere.example.test".equals(uri.getHost()))
                .isEmpty());
    }

    @Test
    void missingBodyAndChangedRowsFailExplicitly() {
        assertThrows(YopmailException.class, () -> YopmailHtml.summaries("<div class='m' id='one'>Changed</div>"));
        assertThrows(
                YopmailException.class,
                () -> YopmailHtml.message(new EmailSummary("one", "", "", ""), "<h1>Loading...</h1>"));
    }

    @Test
    void rowSelectorQuotesIdsInsteadOfTrustingThem() {
        assertEquals(".m[id=\"e_ZwNj==\"]", YopmailSite.messageRow("e_ZwNj=="));
        assertEquals(".m[id=\"a\\\"b\\\\c\"]", YopmailSite.messageRow("a\"b\\c"));
    }
}
