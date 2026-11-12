package io.github.yopmailclient;

import java.util.Locale;
import java.util.Objects;

/**
 * Selects messages by sender, subject and body text. Every non-empty field must be contained in the
 * message; matching ignores case and surrounding whitespace. An empty field matches everything.
 *
 * @param senderContains text in the sender label shown in the inbox list, which may be a display name
 * @param subjectContains text in the subject
 * @param bodyContains text in the plain-text body
 */
public record EmailFilter(String senderContains, String subjectContains, String bodyContains) {
    /**
     * Normalizes the fields for case-insensitive matching.
     *
     * @throws NullPointerException if a field is null; use an empty string to match everything
     */
    public EmailFilter {
        senderContains = normalize(senderContains);
        subjectContains = normalize(subjectContains);
        bodyContains = normalize(bodyContains);
    }

    /** A filter that matches every message. */
    public static EmailFilter any() {
        return new EmailFilter("", "", "");
    }

    /** A filter on the subject; refine it with {@link #from} and {@link #containing}. */
    public static EmailFilter subject(String subject) {
        return new EmailFilter("", subject, "");
    }

    /** Returns a copy that also requires this text in the sender label. */
    public EmailFilter from(String sender) {
        return new EmailFilter(sender, subjectContains, bodyContains);
    }

    /** Returns a copy that also requires this text in the body. */
    public EmailFilter containing(String body) {
        return new EmailFilter(senderContains, subjectContains, body);
    }

    /** Checks sender and subject only; the body is not part of a summary. */
    public boolean matches(EmailSummary summary) {
        return normalize(summary.sender()).contains(senderContains)
                && normalize(summary.subject()).contains(subjectContains);
    }

    /** Checks sender, subject and body. */
    public boolean matches(EmailMessage message) {
        return matches(message.summary()) && normalize(message.text()).contains(bodyContains);
    }

    private static String normalize(String value) {
        return Objects.requireNonNull(value, "filter text").strip().toLowerCase(Locale.ROOT);
    }
}
