package io.github.yopmailclient;

/**
 * YOPmail did not respond within {@link ClientOptions#operationTimeout()}. This is a website failure,
 * distinct from the {@link java.util.concurrent.TimeoutException} that the {@code waitFor...} methods
 * throw when no matching email arrives.
 */
public class YopmailTimeoutException extends YopmailException {
    public YopmailTimeoutException(String message) {
        super(message);
    }

    public YopmailTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
