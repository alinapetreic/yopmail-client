package io.github.yopmailclient.example;

import io.github.yopmailclient.ClientOptions;
import io.github.yopmailclient.EmailFilter;
import io.github.yopmailclient.EmailMessage;
import io.github.yopmailclient.EmailSummary;
import io.github.yopmailclient.YopmailClient;
import java.time.Duration;

/**
 * Runnable example. It lives in the test sources so that it is compiled against the public API but
 * not shipped in the library jar.
 *
 * <p>{@code mvn test-compile exec:java "-Dexec.args=your-inbox@yopmail.com optional-subject-text"}
 */
public final class ReadInbox {
    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final int PIN_DIGITS = 6;

    private ReadInbox() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println(
                    "Usage: mvn test-compile exec:java \"-Dexec.args=your-inbox@yopmail.com [subject-text]\"");
            return;
        }
        ClientOptions options = ClientOptions.defaults()
                .withHeadless(!Boolean.getBoolean("yopmail.headed"))
                .withBrowserChannel(System.getProperty("yopmail.browser", "chrome"));
        try (YopmailClient client = YopmailClient.open(args[0], options)) {
            var messages = client.listMessages();
            System.out.println("Inbox: " + client.mailbox().address() + " (" + messages.size() + " messages)");
            for (EmailSummary message : messages) {
                System.out.printf(
                        "%s | %s | %s | %s%n",
                        message.id(), message.sender(), message.subject(), message.receivedLabel());
            }
            if (args.length > 1) {
                EmailMessage selected = client.waitForMessage(EmailFilter.subject(args[1]), WAIT);
                System.out.println("Selected message: " + selected.summary().subject());
                // Inspect the body in a debugger or in your own assertions. PINs and token URLs are not logged.
                System.out.println(
                        "Six-digit PIN present: " + selected.findPin(PIN_DIGITS).isPresent());
                System.out.println("HTTP(S) links: " + selected.links().size());
            }
        }
    }
}
