package io.github.yopmailclient;

import java.time.Duration;
import java.util.Objects;

/**
 * Browser, timeout and paging settings. Start from {@link #defaults()} and change individual values
 * with the {@code with...} methods.
 *
 * @param headless whether the browser runs without a window
 * @param browserChannel {@code "chrome"}, {@code "msedge"}, or empty for Playwright's installed Chromium
 * @param operationTimeout the longest one inbox or message operation may take
 * @param pollInterval the pause between polls while waiting for a message; at least 2 seconds
 * @param maxPages the most inbox pages one listing may span; between 1 and 100
 */
public record ClientOptions(
        boolean headless, String browserChannel, Duration operationTimeout, Duration pollInterval, int maxPages) {
    private static final Duration MIN_POLL_INTERVAL = Duration.ofSeconds(2);
    private static final int MAX_PAGES_LIMIT = 100;

    /**
     * Validates the settings.
     *
     * @throws IllegalArgumentException if a duration or the page count is out of range
     */
    public ClientOptions {
        Objects.requireNonNull(browserChannel, "browserChannel");
        requireAtLeastOneMilli(operationTimeout, "operationTimeout");
        requireAtLeastOneMilli(pollInterval, "pollInterval");
        if (pollInterval.compareTo(MIN_POLL_INTERVAL) < 0) {
            throw new IllegalArgumentException(
                    "pollInterval must be at least " + MIN_POLL_INTERVAL.toSeconds() + " seconds.");
        }
        if (maxPages < 1 || maxPages > MAX_PAGES_LIMIT) {
            throw new IllegalArgumentException("maxPages must be between 1 and " + MAX_PAGES_LIMIT + ".");
        }
    }

    /** Headless Chrome, 30-second operations, 5-second polling and at most 10 inbox pages. */
    public static ClientOptions defaults() {
        return new ClientOptions(true, "chrome", Duration.ofSeconds(30), Duration.ofSeconds(5), 10);
    }

    public ClientOptions withHeadless(boolean value) {
        return new ClientOptions(value, browserChannel, operationTimeout, pollInterval, maxPages);
    }

    public ClientOptions withBrowserChannel(String value) {
        return new ClientOptions(headless, value, operationTimeout, pollInterval, maxPages);
    }

    public ClientOptions withOperationTimeout(Duration value) {
        return new ClientOptions(headless, browserChannel, value, pollInterval, maxPages);
    }

    public ClientOptions withPollInterval(Duration value) {
        return new ClientOptions(headless, browserChannel, operationTimeout, value, maxPages);
    }

    public ClientOptions withMaxPages(int value) {
        return new ClientOptions(headless, browserChannel, operationTimeout, pollInterval, value);
    }

    private static void requireAtLeastOneMilli(Duration duration, String field) {
        Objects.requireNonNull(duration, field);
        if (duration.toMillis() < 1) {
            throw new IllegalArgumentException(field + " must be at least 1 millisecond.");
        }
    }
}
