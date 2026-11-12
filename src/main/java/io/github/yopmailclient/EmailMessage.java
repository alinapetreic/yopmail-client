package io.github.yopmailclient;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * One email with its body, plus helpers to extract a code or a link from it.
 *
 * @param summary the inbox-list entry this body belongs to
 * @param text the body as plain text
 * @param html the body as HTML
 * @param links the distinct absolute HTTP(S) links in the body, in document order
 */
public record EmailMessage(EmailSummary summary, String text, String html, List<URI> links) {
    private static final int MAX_PIN_DIGITS = 32;

    public EmailMessage {
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(html, "html");
        links = List.copyOf(links);
    }

    /**
     * Finds every distinct match of the pattern in the body text.
     *
     * @return capture group 1 of each match when the pattern defines one, otherwise the whole match
     */
    public List<String> codes(Pattern pattern) {
        Objects.requireNonNull(pattern, "pattern");
        var matches = pattern.matcher(text);
        Set<String> result = new LinkedHashSet<>();
        while (matches.find()) {
            String value = matches.groupCount() > 0 ? matches.group(1) : matches.group();
            if (value != null && !value.isBlank()) {
                result.add(value);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Finds the single code that matches the pattern.
     *
     * @return the code, or empty if nothing matches
     * @throws IllegalStateException if several distinct codes match; the method refuses to guess
     */
    public Optional<String> findCode(Pattern pattern) {
        List<String> values = codes(pattern);
        if (values.size() > 1) {
            throw new IllegalStateException("Several codes match. Use a pattern specific to this email template.");
        }
        return values.stream().findFirst();
    }

    /**
     * Finds the single standalone number with exactly this many digits. It is returned as a string so
     * that leading zeros are preserved.
     *
     * @param digits the PIN length, between 1 and 32
     * @throws IllegalArgumentException if {@code digits} is out of range
     * @throws IllegalStateException if several distinct numbers of that length are present
     */
    public Optional<String> findPin(int digits) {
        if (digits < 1 || digits > MAX_PIN_DIGITS) {
            throw new IllegalArgumentException("digits must be between 1 and " + MAX_PIN_DIGITS + ".");
        }
        return findCode(Pattern.compile("(?<![\\p{L}\\p{N}_])[0-9]{" + digits + "}(?![\\p{L}\\p{N}_])"));
    }

    /** Returns the first link accepted by the predicate. The link is not opened, so its token stays unused. */
    public Optional<URI> findLink(Predicate<URI> predicate) {
        return links.stream()
                .filter(Objects.requireNonNull(predicate, "predicate"))
                .findFirst();
    }

    /** Shows only the id, so that PINs, links and bodies do not end up in test logs. */
    @Override
    public String toString() {
        return "EmailMessage[id=" + summary.id() + "]";
    }
}
