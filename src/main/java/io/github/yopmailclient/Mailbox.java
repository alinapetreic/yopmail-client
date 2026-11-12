package io.github.yopmailclient;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A canonical yopmail.com inbox. Alternate domains and aliases are deliberately not guessed.
 *
 * @param name the part before {@code @yopmail.com}, in lower case
 */
public record Mailbox(String name) {
    private static final String DOMAIN_SUFFIX = "@yopmail.com";
    private static final Pattern VALID_NAME = Pattern.compile("[a-z0-9][a-z0-9._+-]{0,63}");

    /**
     * Normalizes and validates the name.
     *
     * @throws IllegalArgumentException if the name is not 1-64 letters, digits, dots, underscores, + or -
     */
    public Mailbox {
        Objects.requireNonNull(name, "mailbox");
        name = name.strip().toLowerCase(Locale.ROOT);
        if (!VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Use a mailbox name of 1-64 letters, numbers, dots, underscores, + or -.");
        }
    }

    /**
     * Accepts a bare mailbox name or a full {@code @yopmail.com} address.
     *
     * @throws IllegalArgumentException if the address uses another domain or an invalid name
     */
    public static Mailbox parse(String address) {
        Objects.requireNonNull(address, "address");
        String value = address.strip().toLowerCase(Locale.ROOT);
        if (value.contains("@")) {
            if (!value.endsWith(DOMAIN_SUFFIX)) {
                throw new IllegalArgumentException("Supply the canonical @yopmail.com address or its mailbox name.");
            }
            value = value.substring(0, value.length() - DOMAIN_SUFFIX.length());
        }
        return new Mailbox(value);
    }

    /** The full address, for example {@code alex@yopmail.com}. */
    public String address() {
        return name + DOMAIN_SUFFIX;
    }
}
