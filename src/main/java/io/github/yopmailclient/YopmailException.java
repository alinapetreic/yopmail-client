package io.github.yopmailclient;

/**
 * YOPmail could not be used: a network failure, an HTTP error, changed markup, a human-verification
 * challenge, or a message that is no longer in the inbox. A website problem is never reported as an
 * empty inbox.
 */
public class YopmailException extends RuntimeException {
    public YopmailException(String message) {
        super(message);
    }

    public YopmailException(String message, Throwable cause) {
        super(message, cause);
    }
}
