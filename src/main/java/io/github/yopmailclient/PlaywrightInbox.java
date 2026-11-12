package io.github.yopmailclient;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.WaitUntilState;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Drives the YOPmail web inbox in one browser page. Not thread-safe. */
final class PlaywrightInbox implements InboxAccess {
    private static final String BROWSER_LOCALE = "en-US";
    private static final int VIEWPORT_WIDTH = 1280;
    private static final int VIEWPORT_HEIGHT = 900;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int HTTP_FIRST_ERROR_STATUS = 400;
    private static final double NANOS_PER_MILLI = 1_000_000.0;

    /** A message from the last listing and the inbox page it was found on. */
    private record Listed(EmailSummary summary, int page) {}

    private final ClientOptions options;
    private final Page page;
    private final Runnable cleanup;
    private final Map<String, Listed> listed = new LinkedHashMap<>();
    private int currentPage = 1;
    private long lastRefreshNanos;

    PlaywrightInbox(Mailbox mailbox, ClientOptions options) {
        this.options = options;
        Playwright playwright = createPlaywright();
        Browser browser = null;
        try {
            var launch = new BrowserType.LaunchOptions().setHeadless(options.headless());
            if (!options.browserChannel().isBlank()) {
                launch.setChannel(options.browserChannel());
            }
            browser = playwright.chromium().launch(launch);
            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setLocale(BROWSER_LOCALE)
                    .setViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT));
            page = context.newPage();
            Browser ownedBrowser = browser;
            cleanup = () -> closeAll(ownedBrowser, playwright);
            openInbox(mailbox, deadline(options.operationTimeout()));
        } catch (RuntimeException error) {
            closeAll(browser, playwright);
            String message = "Could not open YOPmail. Check Chrome installation, network access, "
                    + "cookie consent and any human-verification challenge. " + error.getMessage();
            throw isTimeout(error) ? new YopmailTimeoutException(message, error) : new YopmailException(message, error);
        }
    }

    /** Allows deterministic browser tests against intercepted synthetic pages. */
    PlaywrightInbox(Page page, ClientOptions options) {
        this.page = page;
        this.options = options;
        this.cleanup = page::close;
    }

    @Override
    public List<EmailSummary> listMessages(Duration timeout) {
        long deadline = deadline(timeout);
        try {
            checkChallenge();
            awaitRefreshCooldown(deadline);
            refresh(deadline);
            return isEmpty() ? List.of() : collectPages(deadline);
        } catch (PlaywrightException error) {
            throw failure("Could not read the inbox", error);
        }
    }

    @Override
    public EmailMessage readMessage(String id, Duration timeout) {
        long deadline = deadline(timeout);
        try {
            if (!listed.containsKey(id)) {
                listMessages(durationLeft(deadline));
            }
            Listed target = listed.get(id);
            if (target == null) {
                throw new YopmailException("Message is not present in this inbox: " + id);
            }
            goToPage(target.page(), deadline);
            checkChallenge();
            openMessage(id, deadline);
            String mailHtml = frame(YopmailSite.MAIL_FRAME_NAME).content();
            return YopmailHtml.message(target.summary(), mailHtml);
        } catch (PlaywrightException error) {
            throw failure("Could not read the selected message; it may have expired or been removed", error);
        }
    }

    @Override
    public void close() {
        cleanup.run();
    }

    static Playwright createPlaywright() {
        // Chrome/Edge are already installed by the user. Bundled Chromium is an explicit setup step.
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        return Playwright.create(new Playwright.CreateOptions().setEnv(environment));
    }

    private void openInbox(Mailbox mailbox, long deadline) {
        page.navigate(
                YopmailSite.HOME_URL,
                new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(millisLeft(deadline)));
        dismissConsent();
        page.locator(YopmailSite.LOGIN_INPUT)
                .fill(mailbox.name(), new Locator.FillOptions().setTimeout(millisLeft(deadline)));
        page.locator(YopmailSite.OPEN_INBOX_BUTTON).click(new Locator.ClickOptions().setTimeout(millisLeft(deadline)));
        awaitInbox(deadline);
    }

    private void dismissConsent() {
        Locator necessaryOnly = page.locator(YopmailSite.NECESSARY_COOKIES_BUTTON);
        if (necessaryOnly.count() > 0 && necessaryOnly.first().isVisible()) {
            necessaryOnly.first().click();
        }
    }

    private void awaitRefreshCooldown(long deadline) {
        if (lastRefreshNanos == 0) {
            return;
        }
        long waitNanos = YopmailSite.REFRESH_COOLDOWN.toNanos() - (System.nanoTime() - lastRefreshNanos);
        if (waitNanos <= 0) {
            return;
        }
        double pauseMillis = waitNanos / NANOS_PER_MILLI;
        double leftMillis = millisLeft(deadline);
        if (pauseMillis >= leftMillis) {
            page.waitForTimeout(leftMillis);
            throw new YopmailTimeoutException("Timed out before YOPmail could be refreshed.");
        }
        page.waitForTimeout(pauseMillis);
    }

    private void refresh(long deadline) {
        lastRefreshNanos = System.nanoTime();
        clickAndAwaitInbox(YopmailSite.REFRESH_BUTTON, deadline);
        currentPage = 1;
        listed.clear();
    }

    /** Reads the current page and every following one. Throws rather than dropping pages beyond maxPages. */
    private List<EmailSummary> collectPages(long deadline) {
        readCurrentPage();
        while (page.locator(YopmailSite.NEXT_PAGE).isVisible()) {
            if (currentPage == options.maxPages()) {
                throw new YopmailException("Inbox exceeds maxPages=" + options.maxPages()
                        + ". Increase ClientOptions.maxPages; results have not been silently truncated.");
            }
            clickAndAwaitInbox(YopmailSite.NEXT_PAGE_BUTTON, deadline);
            currentPage++;
            readCurrentPage();
        }
        return listed.values().stream().map(Listed::summary).toList();
    }

    private void readCurrentPage() {
        String inboxHtml = frame(YopmailSite.INBOX_FRAME_NAME).content();
        List<EmailSummary> rows = YopmailHtml.summaries(inboxHtml);
        if (rows.isEmpty()) {
            throw new YopmailException("The inbox loaded without recognizable message rows.");
        }
        int sizeBefore = listed.size();
        for (EmailSummary row : rows) {
            listed.putIfAbsent(row.id(), new Listed(row, currentPage));
        }
        if (listed.size() == sizeBefore) {
            throw new YopmailException("Inbox pagination repeated a page.");
        }
    }

    private void goToPage(int target, long deadline) {
        while (currentPage != target) {
            boolean forward = currentPage < target;
            clickAndAwaitInbox(forward ? YopmailSite.NEXT_PAGE_BUTTON : YopmailSite.PREVIOUS_PAGE_BUTTON, deadline);
            currentPage += forward ? 1 : -1;
        }
    }

    private void openMessage(String id, long deadline) {
        Locator row = frame(YopmailSite.INBOX_FRAME_NAME).locator(YopmailSite.messageRow(id));
        Locator button = row.locator(YopmailSite.ROW_BUTTON);
        Locator clickable = button.count() > 0 ? button.first() : row;
        clickable.click(new Locator.ClickOptions().setTimeout(millisLeft(deadline)));
        page.waitForFunction(
                YopmailSite.MESSAGE_READY,
                YopmailSite.messageReadyArgs(id),
                new Page.WaitForFunctionOptions().setTimeout(millisLeft(deadline)));
        checkChallenge();
    }

    /** Clicks a control that reloads the inbox frame, then waits for the reloaded list. */
    private void clickAndAwaitInbox(String selector, long deadline) {
        // Wait for an actual inbox response before accepting the old DOM as the new page.
        Response response = page.waitForResponse(
                candidate -> isInboxUrl(candidate.url()),
                new Page.WaitForResponseOptions().setTimeout(millisLeft(deadline)),
                () -> page.locator(selector).click(new Locator.ClickOptions().setTimeout(millisLeft(deadline))));
        if (response.status() == HTTP_TOO_MANY_REQUESTS) {
            throw new YopmailException("YOPmail rate-limited the request. Stop and retry later.");
        }
        if (response.status() >= HTTP_FIRST_ERROR_STATUS) {
            throw new YopmailException("YOPmail inbox returned HTTP " + response.status() + ".");
        }
        awaitInbox(deadline);
    }

    private void awaitInbox(long deadline) {
        page.waitForFunction(
                YopmailSite.INBOX_READY,
                YopmailSite.INBOX_READY_ARGS,
                new Page.WaitForFunctionOptions().setTimeout(millisLeft(deadline)));
        checkChallenge();
    }

    private boolean isEmpty() {
        Locator status = page.locator(YopmailSite.STATUS_MESSAGE);
        if (status.count() == 0 || !status.innerText().strip().equals(YopmailSite.EMPTY_INBOX_TEXT)) {
            return false;
        }
        String count = page.locator(YopmailSite.MAIL_COUNT).innerText().strip();
        return YopmailSite.EMPTY_MAIL_COUNT.matcher(count).matches();
    }

    private void checkChallenge() {
        String title = page.title().toLowerCase(Locale.ROOT);
        boolean blocked = YopmailSite.BLOCKED_TITLES.stream().anyMatch(title::contains);
        if (blocked || page.locator(YopmailSite.CHALLENGE).isVisible()) {
            throw new YopmailException("YOPmail requires human verification or has blocked this session. "
                    + "This client does not solve or bypass challenges.");
        }
    }

    private Frame frame(String name) {
        Frame frame = page.frame(name);
        if (frame == null) {
            throw new YopmailException("The YOPmail frame '" + name + "' is missing.");
        }
        return frame;
    }

    /** Translates a browser failure, preferring a visible challenge as the explanation. */
    private YopmailException failure(String description, PlaywrightException error) {
        try {
            checkChallenge();
        } catch (YopmailException challenge) {
            challenge.addSuppressed(error);
            return challenge;
        } catch (PlaywrightException pageUnusable) {
            // The page cannot even be inspected, so the original failure is the best explanation.
            error.addSuppressed(pageUnusable);
        }
        String hint = " Check the connection and whether YOPmail changed its page layout.";
        return isTimeout(error)
                ? new YopmailTimeoutException(description + ": YOPmail did not respond in time." + hint, error)
                : new YopmailException(description + "." + hint, error);
    }

    private static boolean isTimeout(RuntimeException error) {
        return error instanceof TimeoutError || error instanceof YopmailTimeoutException;
    }

    private static boolean isInboxUrl(String url) {
        try {
            URI uri = URI.create(url);
            return YopmailSite.HOST.equalsIgnoreCase(uri.getHost())
                    && uri.getPath().endsWith(YopmailSite.INBOX_PATH_SUFFIX);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static void closeAll(Browser browser, Playwright playwright) {
        try {
            if (browser != null) {
                browser.close();
            }
        } finally {
            playwright.close();
        }
    }

    private static long deadline(Duration timeout) {
        return System.nanoTime() + timeout.toNanos();
    }

    private static double millisLeft(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) {
            throw new YopmailTimeoutException("YOPmail operation timed out.");
        }
        return Math.max(1.0, nanos / NANOS_PER_MILLI);
    }

    private static Duration durationLeft(long deadline) {
        return Duration.ofMillis((long) millisLeft(deadline));
    }
}
