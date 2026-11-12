package io.github.yopmailclient;

import static io.github.yopmailclient.YopmailHtmlTest.fixture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** All requests are intercepted. These tests never contact YOPmail or send email. */
@EnabledIfSystemProperty(named = "yopmail.browserTests", matches = "true")
class PlaywrightInboxTest {
    private static final String INBOX_REQUESTS = "**/en/inbox?**";
    private static final String HTML = "text/html; charset=utf-8";

    static Playwright playwright;
    static Browser browser;
    BrowserContext context;
    Page page;

    @BeforeAll
    static void startBrowser() {
        playwright = PlaywrightInbox.createPlaywright();
        var launch = new BrowserType.LaunchOptions().setHeadless(true);
        String channel = System.getProperty("yopmail.browser", "chrome");
        if (!channel.isBlank()) {
            launch.setChannel(channel);
        }
        browser = playwright.chromium().launch(launch);
    }

    @AfterAll
    static void stopBrowser() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    @BeforeEach
    void openFixture() {
        context = browser.newContext();
        context.route("**/*", route -> {
            String url = route.request().url();
            if (url.contains("/en/inbox?")) {
                respond(route, fixture(url.contains("p=2") ? "inbox-page-2.html" : "inbox-page-1.html"));
            } else if (url.contains("/en/mail?")) {
                String pin = url.contains("mprevious") ? "111111" : "004219";
                respond(route, fixture("message.html").replace("004219", pin));
            } else if (url.endsWith("/en/wm")) {
                respond(route, fixture("webmail.html"));
            } else {
                route.abort();
            }
        });
        page = context.newPage();
        page.navigate("https://yopmail.com/en/wm");
    }

    @AfterEach
    void closeFixture() {
        context.close();
    }

    @Test
    void listsAllPagesAndReadsTheSelectedMessageRatherThanStaleFrame() {
        try (var inbox = new PlaywrightInbox(page, ClientOptions.defaults())) {
            var list = inbox.listMessages(Duration.ofSeconds(10));
            assertEquals(
                    List.of("newest", "older", "previous"),
                    list.stream().map(EmailSummary::id).toList());
            assertEquals("111111", read(inbox, "previous"));
            assertEquals("004219", read(inbox, "newest"));
            assertEquals("004219", read(inbox, "newest"));
        }
    }

    @Test
    void readsAMessageWhoseIdNeedsQuotingInASelector() {
        String id = "e_ZwNj.ZGNl:ZQNm==";
        page.route(
                INBOX_REQUESTS,
                route -> respond(route, fixture("inbox-page-2.html").replace("previous", id)));
        try (var inbox = new PlaywrightInbox(page, ClientOptions.defaults())) {
            assertEquals(
                    List.of(id),
                    inbox.listMessages(Duration.ofSeconds(5)).stream()
                            .map(EmailSummary::id)
                            .toList());
            assertEquals("004219", read(inbox, id));
        }
    }

    @Test
    void messageMissingFromTheInboxIsAWebsiteFailure() {
        try (var inbox = new PlaywrightInbox(page, ClientOptions.defaults())) {
            var error = assertThrows(YopmailException.class, () -> read(inbox, "expired"));
            assertTrue(error.getMessage().contains("not present"));
        }
    }

    @Test
    void handlesConfirmedEmptyInbox() {
        page.route(
                INBOX_REQUESTS,
                route -> respond(route, "<html><body><script>parent.emptyInbox()</script></body></html>"));
        try (var inbox = new PlaywrightInbox(page, ClientOptions.defaults())) {
            assertTrue(inbox.listMessages(Duration.ofSeconds(5)).isEmpty());
        }
    }

    @Test
    void refusesToSilentlyTruncatePagination() {
        ClientOptions onePage = ClientOptions.defaults()
                .withOperationTimeout(Duration.ofSeconds(5))
                .withMaxPages(1);
        try (var inbox = new PlaywrightInbox(page, onePage)) {
            var error = assertThrows(YopmailException.class, () -> inbox.listMessages(Duration.ofSeconds(5)));
            assertTrue(error.getMessage().contains("maxPages"));
        }
    }

    @Test
    void challengeAndRateLimitingAreNotEmptyInboxes() {
        page.evaluate("document.getElementById('r_parent').style.display = 'block'");
        try (var inbox = new PlaywrightInbox(page, ClientOptions.defaults())) {
            var challenge = assertThrows(YopmailException.class, () -> inbox.listMessages(Duration.ofSeconds(5)));
            assertTrue(challenge.getMessage().contains("human verification"));
            page.evaluate("document.getElementById('r_parent').style.display = 'none'");
            page.route(
                    INBOX_REQUESTS,
                    route -> route.fulfill(
                            new Route.FulfillOptions().setStatus(429).setBody("Too many requests")));
            var rateLimit = assertThrows(YopmailException.class, () -> inbox.listMessages(Duration.ofSeconds(5)));
            assertTrue(rateLimit.getMessage().contains("rate-limited"));
        }
    }

    @Test
    void unexpectedHtmlTimesOutAsAWebsiteFailure() {
        page.route(INBOX_REQUESTS, route -> respond(route, "<h1>Unexpected response</h1>"));
        try (var inbox = new PlaywrightInbox(page, ClientOptions.defaults())) {
            var error = assertThrows(YopmailException.class, () -> inbox.listMessages(Duration.ofMillis(500)));
            assertInstanceOf(YopmailTimeoutException.class, error);
        }
    }

    private static String read(PlaywrightInbox inbox, String id) {
        return inbox.readMessage(id, Duration.ofSeconds(5)).findPin(6).orElseThrow();
    }

    private static void respond(Route route, String html) {
        route.fulfill(new Route.FulfillOptions().setContentType(HTML).setBody(html));
    }
}
