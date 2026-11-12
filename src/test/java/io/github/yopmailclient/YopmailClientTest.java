package io.github.yopmailclient;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class YopmailClientTest {
    static final EmailSummary OLD = new EmailSummary("old", "QA", "Verify", "Yesterday");
    static final EmailSummary NEW = new EmailSummary("new", "QA", "Verify", "Today");

    static class FakeInbox implements InboxAccess {
        List<EmailSummary> summaries = List.of(NEW, OLD);
        List<String> reads = new ArrayList<>();
        boolean closed;

        @Override
        public List<EmailSummary> listMessages(Duration timeout) {
            return summaries;
        }

        @Override
        public EmailMessage readMessage(String id, Duration timeout) {
            reads.add(id);
            EmailSummary summary = summaries.stream()
                    .filter(item -> item.id().equals(id))
                    .findFirst()
                    .orElseThrow();
            return new EmailMessage(summary, "Your PIN: " + (id.equals("old") ? "111111" : "004219"), "", List.of());
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static YopmailClient client(InboxAccess access) {
        return new YopmailClient(Mailbox.parse("qa-test"), ClientOptions.defaults(), access);
    }

    @Test
    void freshMessageSelectionExcludesTheBaseline() throws Exception {
        FakeInbox inbox = new FakeInbox();
        try (var client = client(inbox)) {
            inbox.summaries = List.of(OLD);
            Set<String> before = client.snapshotMessageIds();
            inbox.summaries = List.of(NEW, OLD);
            var selected =
                    client.waitForNewMessage(EmailFilter.subject("verify").from("qa"), before, Duration.ofSeconds(1));
            assertEquals("004219", selected.findPin(6).orElseThrow());
            assertEquals(List.of("new"), inbox.reads);
        }
        assertTrue(inbox.closed);
    }

    @Test
    void usesBodyFilterAndDoesNotReadUnrelatedSubjects() {
        FakeInbox inbox = new FakeInbox();
        try (var client = client(inbox)) {
            assertEquals(
                    "old",
                    client.findMessage(EmailFilter.subject("verify").containing("111111"))
                            .orElseThrow()
                            .summary()
                            .id());
            inbox.reads.clear();
            assertTrue(client.findMessage(EmailFilter.subject("newsletter")).isEmpty());
            assertTrue(inbox.reads.isEmpty());
        }
    }

    @Test
    void waitingForAbsentEmailHonorsShortTimeout() {
        FakeInbox inbox = new FakeInbox();
        inbox.summaries = List.of();
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            try (var client = client(inbox)) {
                assertThrows(
                        TimeoutException.class, () -> client.waitForMessage(EmailFilter.any(), Duration.ofMillis(25)));
            }
        });
    }

    @Test
    void networkFailureIsNotReportedAsEmptyInbox() {
        FakeInbox inbox = new FakeInbox() {
            @Override
            public List<EmailSummary> listMessages(Duration timeout) {
                throw new YopmailException("Network failed");
            }
        };
        try (var client = client(inbox)) {
            assertThrows(YopmailException.class, () -> client.waitForMessage(EmailFilter.any(), Duration.ofSeconds(1)));
        }
    }

    @Test
    void operationCutShortByTheWaitDeadlineIsAWaitTimeout() {
        FakeInbox inbox = new FakeInbox() {
            @Override
            public List<EmailSummary> listMessages(Duration timeout) {
                // Use up the whole budget, as a browser operation capped by the wait deadline would.
                try {
                    Thread.sleep(timeout.toMillis() + 5);
                } catch (InterruptedException error) {
                    throw new AssertionError(error);
                }
                throw new YopmailTimeoutException("YOPmail operation timed out.");
            }
        };
        try (var client = client(inbox)) {
            var error = assertThrows(
                    TimeoutException.class, () -> client.waitForMessage(EmailFilter.any(), Duration.ofMillis(50)));
            assertInstanceOf(YopmailTimeoutException.class, error.getCause());
        }
    }

    @Test
    void unresponsiveWebsiteDuringAWaitIsNotAWaitTimeout() {
        FakeInbox inbox = new FakeInbox() {
            @Override
            public List<EmailSummary> listMessages(Duration timeout) {
                throw new YopmailTimeoutException("YOPmail operation timed out.");
            }
        };
        try (var client = client(inbox)) {
            assertThrows(
                    YopmailTimeoutException.class,
                    () -> client.waitForMessage(EmailFilter.any(), Duration.ofSeconds(5)));
        }
    }

    @Test
    void interruptionAndUseAfterCloseAreExplicit() {
        var client = client(new FakeInbox());
        Thread.currentThread().interrupt();
        try {
            assertThrows(
                    InterruptedException.class, () -> client.waitForMessage(EmailFilter.any(), Duration.ofSeconds(1)));
        } finally {
            Thread.interrupted();
            client.close();
        }
        assertThrows(IllegalStateException.class, client::listMessages);
        assertDoesNotThrow(client::close);
    }

    @Test
    void rejectsBlankMessageIdsBeforeTouchingTheInbox() {
        FakeInbox inbox = new FakeInbox();
        try (var client = client(inbox)) {
            assertThrows(IllegalArgumentException.class, () -> client.readMessage(" "));
            assertThrows(IllegalArgumentException.class, () -> client.readMessage(null));
            assertTrue(inbox.reads.isEmpty());
        }
    }

    @Test
    void normalizesMailboxAndRejectsNonYopmailAddresses() {
        assertEquals(
                "qa-test@yopmail.com", Mailbox.parse(" QA-Test@YOPMAIL.COM ").address());
        assertThrows(IllegalArgumentException.class, () -> Mailbox.parse("qa@gmail.com"));
        assertThrows(IllegalArgumentException.class, () -> Mailbox.parse("../other"));
        assertThrows(IllegalArgumentException.class, () -> Mailbox.parse("one@two@yopmail.com"));
    }
}
