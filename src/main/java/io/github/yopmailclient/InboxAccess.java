package io.github.yopmailclient;

import java.time.Duration;
import java.util.List;

/** The inbox operations {@link YopmailClient} needs. Implementations report every failure as a {@link YopmailException}. */
interface InboxAccess extends AutoCloseable {
    /** Refreshes the inbox and returns all messages, newest first. */
    List<EmailSummary> listMessages(Duration timeout);

    /** Opens one listed message. Fails if the id is not in the inbox. */
    EmailMessage readMessage(String id, Duration timeout);

    @Override
    void close();
}
