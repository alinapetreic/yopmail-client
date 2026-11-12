package io.github.yopmailclient;

import java.util.Objects;

/**
 * One entry of the inbox list.
 *
 * @param id YOPmail's identifier for the message, for use with {@link YopmailClient#readMessage}
 * @param sender the sender label shown in the list, which may be a display name
 * @param subject the subject line
 * @param receivedLabel the date and time as displayed by the site, not a timezone-normalized timestamp
 */
public record EmailSummary(String id, String sender, String subject, String receivedLabel) {
    public EmailSummary {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(receivedLabel, "receivedLabel");
    }
}
