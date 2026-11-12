# YOPmail Java Client

A small Java 17 + Maven library for reading YOPmail test inboxes, selecting an email and extracting a PIN or verification link.

This is an unofficial browser-based client. It uses Playwright to operate the public YOPmail web inbox; it does not rely on an invented REST API. No email sending or deletion is implemented. Verification links are extracted, never opened automatically.

## Requirements

- JDK 17 or newer.
- Maven 3.9 or newer.
- Google Chrome, used headlessly by default. Microsoft Edge and Playwright Chromium are alternatives below.
- Network access to YOPmail; Maven Central for the first dependency download.

Open this directory as a Maven project in IntelliJ IDEA, Eclipse or VS Code. A newer JDK also works: the build targets Java 17 with `--release 17`.

## Build and run

Run these from the project directory:

```powershell
mvn verify
mvn test-compile exec:java "-Dexec.args=your-test-inbox@yopmail.com"
```

The example (`ReadInbox`, in the test sources so it is not packaged into the library jar) lists message IDs, sender labels, subjects and displayed receipt times. To select a subject and wait up to 60 seconds:

```powershell
mvn test-compile exec:java '-Dexec.args=your-test-inbox@yopmail.com "Verify your account"'
```

The example reports whether a six-digit PIN was found and how many links exist. Use the Java API below to retrieve their actual values. PINs and token URLs are not printed by the example.

## List and read emails

```java
import io.github.yopmailclient.*;

try (YopmailClient client = YopmailClient.open("alex@yopmail.com")) {
    for (EmailSummary email : client.listMessages()) {
        System.out.println(email.subject());
    }

    // Use an ID returned by listMessages().
    // EmailMessage email = client.readMessage(messageId);
    // String text = email.text();
    // String html = email.html();
}
```

Both `alex` and `alex@yopmail.com` refer to the same inbox. For version 0.1, pass the canonical YOPmail inbox, rather than an alternate-domain address or an alias.

## Find an existing email and extract its PIN

```java
import io.github.yopmailclient.*;
import java.time.Duration;
import java.util.regex.Pattern;

try (YopmailClient client = YopmailClient.open("alex@yopmail.com")) {
    EmailFilter filter = EmailFilter.subject("Verify your account")
            .from("noreply")
            .containing("PIN");

    EmailMessage email = client.waitForMessage(filter, Duration.ofSeconds(60));
    String pin = email.findPin(6)
            .orElseThrow(() -> new IllegalStateException("No six-digit PIN in the selected email"));

    // Use pin in your application's test. A PIN such as "004219" stays a String.

    // When the body also contains order numbers, use the actual email template:
    String precisePin = email.findCode(Pattern.compile("(?i)PIN\\s*[:=]?\\s*(\\d{6})"))
            .orElseThrow();
}
```

All filter fields are combined with AND; matching is case-insensitive. Sender filtering uses the label exposed in YOPmail's message list, which may be a display name rather than a full email address. `findMessage(filter)` performs one scan and returns an `Optional`; `waitForMessage` polls and allows matching messages that already existed.

`findPin(digits)` refuses to guess between different matching numbers. `findCode(pattern)` uses capture group 1 when provided, otherwise the complete match. `codes(pattern)` returns all distinct candidates when you want to inspect them yourself. This is template matching, not a universal semantic PIN detector.

## Wait for a NEW verification email

For repeatable work tests, capture the inbox before triggering the action that sends mail. This prevents reuse of a code from an earlier test.

```java
import io.github.yopmailclient.*;
import java.time.Duration;
import java.util.Set;

try (YopmailClient client = YopmailClient.open("your-unique-test-inbox@yopmail.com")) {
    Set<String> before = client.snapshotMessageIds();

    // Trigger your application's send-code / sign-up action HERE.
    // Keep this client on its original thread.

    EmailMessage email = client.waitForNewMessage(
            EmailFilter.subject("Verification").from("Your App"),
            before,
            Duration.ofSeconds(90));

    String pin = email.findPin(6).orElseThrow();
    // Enter pin in your application and assert the result.
}
```

Use a unique inbox per test run, and add a run identifier to your subject/body filter when available. A baseline excludes known old IDs; it does not distinguish two concurrent tests sending identical messages to the same inbox.

## Extract a verification link

```java
import java.net.URI;

URI verificationLink = email.findLink(uri ->
        "accounts.your-company.example".equalsIgnoreCase(uri.getHost())
        && "/verify".equals(uri.getPath()))
    .orElseThrow(() -> new IllegalStateException("Verification link was not found"));
```

Replace the host/path with your application's real values. The returned links are absolute HTTP(S) anchors from the email body. Webmail navigation links, malformed URLs, `javascript:` and `mailto:` are excluded. Relative links and bare URLs written only as text are not inferred. No link is followed by this library.

## Browser and timeout options

Start from the defaults and change only what you need:

```java
ClientOptions options = ClientOptions.defaults()
        .withHeadless(true)
        .withBrowserChannel("chrome")                  // "msedge", or "" for installed Playwright Chromium
        .withOperationTimeout(Duration.ofSeconds(30))  // maximum duration of each inbox/message operation
        .withPollInterval(Duration.ofSeconds(5))       // pause between polls (minimum: 2 seconds)
        .withMaxPages(10);                             // maximum inbox pages per scan

try (YopmailClient client = YopmailClient.open("your-test-inbox", options)) {
    // ...
}
```

The values shown are the defaults. If more pages exist than `maxPages`, listing throws rather than silently dropping old messages. A scan preserves the site's newest-first order. An operation has one shared timeout across its pagination steps. Waiting also has an overall deadline and supports thread interruption. Consecutive refreshes respect YOPmail's two-second UI refresh cooldown.

To inspect the browser while running the example:

```powershell
mvn test-compile exec:java "-Dyopmail.headed=true" "-Dexec.args=your-test-inbox"
```

To use Edge:

```powershell
mvn test-compile exec:java "-Dyopmail.browser=msedge" "-Dexec.args=your-test-inbox"
```

To install Playwright Chromium explicitly and use it from Java:

```powershell
mvn exec:java "-Dexec.mainClass=com.microsoft.playwright.CLI" "-Dexec.args=install chromium"
```

```java
ClientOptions options = ClientOptions.defaults().withBrowserChannel("");
```

The client does not automatically download additional browsers on startup. Each client owns its browser; use try-with-resources. Playwright is thread-confined: create, use and close each client on the same thread.

## Tests

```powershell
# Unit tests: parsing, filtering, extraction, freshness, timeouts and error handling.
# Also fails if the sources are not formatted.
mvn verify

# Also run headless Chrome against intercepted, synthetic YOPmail pages.
# These browser tests make no external requests.
mvn verify "-Dyopmail.browserTests=true"

# Format the sources (palantir-java-format through Spotless; wildcard imports are rejected).
mvn spotless:apply
```

Browser tests cover multiple inbox pages, switching between messages without reading a stale frame, repeated reads, ids that need quoting, missing messages, empty inboxes, pagination limits, unexpected markup, challenges and HTTP 429 responses.

The fixtures are synthetic, so they prove the client agrees with itself, not with the live website. Last live check, 2026-11-11: a fresh inbox listed as empty, and a public inbox with 107 messages listed across all its pages with the first and last message read successfully. Extracting a PIN or link from your own application's email still needs a message from your own test flow.

## Use from another Maven project

Install locally:

```powershell
mvn install
```

Then add:

```xml
<dependency>
  <groupId>io.github.yopmailclient</groupId>
  <artifactId>yopmail-client</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## Project layout

- `YopmailClient`: public API and polling, including baseline-based fresh-message selection.
- `PlaywrightInbox`: browser lifecycle, refresh, pagination and message selection.
- `YopmailSite`: every URL, selector and text that depends on the website. A site change should only need edits here.
- `YopmailHtml`: inbox/message parsing.
- `EmailSummary`, `EmailMessage`: message data and extraction helpers.
- `EmailFilter`, `Mailbox`, `ClientOptions`: filtering, addresses and configuration.
- `YopmailException`, `YopmailTimeoutException`: website failures.
- `src/test`: unit tests, synthetic browser fixtures and the runnable `example/ReadInbox`.

## Errors

| Situation | Exception |
| --- | --- |
| No matching email arrived before the `waitFor...` timeout | `java.util.concurrent.TimeoutException` (checked) |
| Network failure, HTTP error, changed markup, challenge, or a message that is no longer in the inbox | `YopmailException` |
| YOPmail did not answer within `operationTimeout` | `YopmailTimeoutException`, a `YopmailException` |
| Invalid argument, such as a blank message id or a non-YOPmail address | `IllegalArgumentException` |
| Use from another thread or after `close()` | `IllegalStateException` |

Polling stops at the first `YopmailException`; a website failure is never reported as an empty inbox or as "no email yet".

## Current limits

YOPmail's website is an external dependency: markup changes, connectivity failures and access challenges can interrupt automation. The client reports these as errors and does not solve or bypass CAPTCHA or other access challenges.

The displayed receipt time is retained as text because the web UI does not provide a reliably normalized timestamp here. Attachments, sending, deletion and alternate-domain discovery are outside this version.

YOPmail inboxes have no password and messages expire after eight days. Use unique addresses for your test flows. See [YOPmail's usage explanation](https://yopmail.com/en/) and the [Playwright Java documentation](https://playwright.dev/java/docs/intro).
