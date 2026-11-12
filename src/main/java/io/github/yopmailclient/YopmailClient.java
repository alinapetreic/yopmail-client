package io.github.yopmailclient;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Reads one YOPmail inbox through one browser. Create, use and close an instance on the same thread;
 * every method throws {@link IllegalStateException} when called from another thread or after
 * {@link #close()}.
 *
 * <p>Failures follow one rule. A problem with the website (network, HTTP error, changed markup,
 * human-verification challenge, missing message) is a {@link YopmailException}; when YOPmail simply
 * does not answer within {@link ClientOptions#operationTimeout()} it is the subclass
 * {@link YopmailTimeoutException}. Only the {@code waitFor...} methods throw the checked
 * {@link TimeoutException}, and only because no matching email arrived in time.
 */
public final class YopmailClient implements AutoCloseable {
    private static final Duration MAX_WAIT = Duration.ofDays(1);
    private static final String WAIT_TIMED_OUT = "Timed out waiting for a matching email.";

    private final Mailbox mailbox;
    private final ClientOptions options;
    private final InboxAccess inbox;
    private final Thread owner = Thread.currentThread();
    private boolean closed;

    YopmailClient(Mailbox mailbox, ClientOptions options, InboxAccess inbox) {
        this.mailbox = mailbox;
        this.options = options;
        this.inbox = inbox;
    }

    /**
     * Opens an inbox with {@link ClientOptions#defaults()}.
     *
     * @param address a mailbox name such as {@code alex}, or {@code alex@yopmail.com}
     * @throws IllegalArgumentException if the address is not a canonical yopmail.com inbox
     * @throws YopmailException if the browser cannot be started or the inbox cannot be opened
     */
    public static YopmailClient open(String address) {
        return open(address, ClientOptions.defaults());
    }

    /**
     * Starts a browser and opens an inbox.
     *
     * @param address a mailbox name such as {@code alex}, or {@code alex@yopmail.com}
     * @param options browser, timeout and paging settings
     * @throws IllegalArgumentException if the address is not a canonical yopmail.com inbox
     * @throws YopmailException if the browser cannot be started or the inbox cannot be opened
     */
    public static YopmailClient open(String address, ClientOptions options) {
        Mailbox mailbox = Mailbox.parse(address);
        Objects.requireNonNull(options, "options");
        return new YopmailClient(mailbox, options, new PlaywrightInbox(mailbox, options));
    }

    /** The inbox this client reads. */
    public Mailbox mailbox() {
        return mailbox;
    }

    /**
     * Refreshes the inbox and lists every message, newest first.
     *
     * @return an immutable list; empty only when YOPmail confirms the inbox is empty
     * @throws YopmailException if the inbox cannot be read, or holds more pages than
     *     {@link ClientOptions#maxPages()} (the result is never silently truncated)
     */
    public List<EmailSummary> listMessages() {
        checkOpen();
        return List.copyOf(inbox.listMessages(options.operationTimeout()));
    }

    /**
     * Reads the body of one message.
     *
     * @param id an id returned by {@link #listMessages()}
     * @throws IllegalArgumentException if the id is null or blank
     * @throws YopmailException if the message is no longer in the inbox or cannot be read
     */
    public EmailMessage readMessage(String id) {
        checkOpen();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Message id is required.");
        }
        return inbox.readMessage(id, options.operationTimeout());
    }

    /**
     * Returns the ids currently in the inbox. Capture this BEFORE triggering the action that sends the
     * verification email, then pass it to {@link #waitForNewMessage}.
     *
     * @throws YopmailException if the inbox cannot be read
     */
    public Set<String> snapshotMessageIds() {
        return listMessages().stream().map(EmailSummary::id).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Scans the inbox once and returns the newest message that matches.
     *
     * @return the match, or empty if no message in the inbox matches right now
     * @throws YopmailException if the inbox or a candidate message cannot be read
     */
    public Optional<EmailMessage> findMessage(EmailFilter filter) {
        checkOpen();
        Objects.requireNonNull(filter, "filter");
        for (EmailSummary summary : listMessages()) {
            if (filter.matches(summary)) {
                EmailMessage message = readMessage(summary.id());
                if (filter.matches(message)) {
                    return Optional.of(message);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Polls until a message matches. Messages that already exist are eligible; prefer
     * {@link #waitForNewMessage} for a PIN generated by a test action.
     *
     * @param timeout how long to wait; positive and at most one day
     * @throws TimeoutException if no matching message arrived within the timeout
     * @throws InterruptedException if the calling thread is interrupted while waiting
     * @throws YopmailException if the inbox cannot be read; polling stops at the first failure
     */
    public EmailMessage waitForMessage(EmailFilter filter, Duration timeout)
            throws TimeoutException, InterruptedException {
        return waitForNewMessage(filter, Set.of(), timeout);
    }

    /**
     * Polls until a message matches that is not in {@code previousIds}.
     *
     * @param previousIds ids to ignore, normally the result of {@link #snapshotMessageIds()}
     * @param timeout how long to wait; positive and at most one day
     * @throws TimeoutException if no matching new message arrived within the timeout
     * @throws InterruptedException if the calling thread is interrupted while waiting
     * @throws YopmailException if the inbox cannot be read; polling stops at the first failure
     */
    public EmailMessage waitForNewMessage(EmailFilter filter, Set<String> previousIds, Duration timeout)
            throws TimeoutException, InterruptedException {
        checkOpen();
        Objects.requireNonNull(filter, "filter");
        Set<String> ignored = new HashSet<>(Objects.requireNonNull(previousIds, "previousIds"));
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(MAX_WAIT) > 0) {
            throw new IllegalArgumentException("timeout must be positive and at most one day.");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Waiting for email was interrupted.");
            }
            try {
                for (EmailSummary summary : inbox.listMessages(timeLeft(deadline))) {
                    if (ignored.contains(summary.id()) || !filter.matches(summary)) {
                        continue;
                    }
                    EmailMessage message = inbox.readMessage(summary.id(), timeLeft(deadline));
                    if (filter.matches(message)) {
                        return message;
                    }
                    // Message bodies are immutable: don't fetch a rejected body on every poll.
                    ignored.add(summary.id());
                }
            } catch (YopmailTimeoutException error) {
                if (System.nanoTime() < deadline) {
                    throw error;
                }
                // The operation was cut short by the wait deadline, not by an unresponsive website.
                TimeoutException timedOut = new TimeoutException(WAIT_TIMED_OUT);
                timedOut.initCause(error);
                throw timedOut;
            }
            long sleepNanos = Math.min(options.pollInterval().toNanos(), deadline - System.nanoTime());
            if (sleepNanos > 0) {
                TimeUnit.NANOSECONDS.sleep(sleepNanos);
            }
        }
        throw new TimeoutException("No matching email arrived within " + timeout + ".");
    }

    /** Closes the browser. Closing twice is harmless. */
    @Override
    public void close() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Close the client on its creating thread.");
        }
        if (!closed) {
            closed = true;
            inbox.close();
        }
    }

    /** The time one operation may take: what is left of the wait, capped by the operation timeout. */
    private Duration timeLeft(long deadline) throws TimeoutException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) {
            throw new TimeoutException(WAIT_TIMED_OUT);
        }
        return Duration.ofNanos(Math.min(nanos, options.operationTimeout().toNanos()));
    }

    private void checkOpen() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Use the client on its creating thread.");
        }
        if (closed) {
            throw new IllegalStateException("Client is closed.");
        }
    }
}
