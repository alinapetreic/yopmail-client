package io.github.yopmailclient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Everything that depends on YOPmail's URLs, markup and wording. When the website changes, this is
 * the only file that should need editing.
 */
final class YopmailSite {
    static final String HOST = "yopmail.com";
    static final String HOME_URL = "https://" + HOST + "/en/";
    static final String INBOX_PATH_SUFFIX = "/inbox";

    // Home page.
    static final String LOGIN_INPUT = "#login";
    static final String OPEN_INBOX_BUTTON = "#refreshbut button";
    static final String NECESSARY_COOKIES_BUTTON = "[onclick*='fnecesary']";

    // Webmail page.
    static final String REFRESH_BUTTON = "#refresh";
    static final String NEXT_PAGE = "#next";
    static final String NEXT_PAGE_BUTTON = NEXT_PAGE + " button";
    static final String PREVIOUS_PAGE_BUTTON = "#previous button";
    static final String STATUS_MESSAGE = "#message";
    static final String MAIL_COUNT = "#nbmail";
    static final String CHALLENGE = "#r_parent";
    static final String INBOX_FRAME_NAME = "ifinbox";
    static final String INBOX_FRAME = "#" + INBOX_FRAME_NAME;
    static final String MAIL_FRAME_NAME = "ifmail";
    static final String MAIL_FRAME = "#" + MAIL_FRAME_NAME;
    static final String EMPTY_INBOX_TEXT = "This inbox is empty";
    static final Pattern EMPTY_MAIL_COUNT = Pattern.compile("0\\s+mails?");
    /** Lower-case fragments of page titles shown when the session is blocked. */
    static final List<String> BLOCKED_TITLES = List.of("just a moment", "access denied");
    /** YOPmail ignores a second refresh click within two seconds. */
    static final Duration REFRESH_COOLDOWN = Duration.ofMillis(2100);

    // Inbox frame.
    static final String DAY_HEADER = ".mday";
    static final String ROW_CLASS = ".m";
    static final String MESSAGE_ROW = ROW_CLASS + "[id]";
    static final String ROW_BUTTON = ".lm";
    static final String ROW_SENDER = ".lmf";
    static final String ROW_SUBJECT = ".lms";
    static final String ROW_TIME = ".lmh";

    // Message frame. Its URL carries the row id with this prefix.
    static final String MAIL_BODY = "#mail";
    static final String MAIL_ID_PREFIX = "m";

    /** Browser-side predicate: the inbox list finished loading, or a challenge is showing. */
    static final String INBOX_READY = """
            site => {
              const visible = e => !!e && !!(e.offsetWidth || e.offsetHeight || e.getClientRects().length);
              if (visible(document.querySelector(site.challenge))) return true;
              const refresh = document.querySelector(site.refresh);
              if (!refresh || refresh.hasAttribute('loading')) return false;
              const status = document.querySelector(site.status)?.textContent?.trim();
              if (status === site.emptyText) return true;
              const frame = document.querySelector(site.inboxFrame);
              try {
                return frame?.getAttribute('state') === 'full'
                  && frame.contentDocument?.readyState === 'complete'
                  && frame.contentDocument?.querySelector(site.row) !== null;
              } catch (_) { return false; }
            }
            """;

    static final Map<String, String> INBOX_READY_ARGS = Map.of(
            "challenge", CHALLENGE,
            "refresh", REFRESH_BUTTON,
            "status", STATUS_MESSAGE,
            "emptyText", EMPTY_INBOX_TEXT,
            "inboxFrame", INBOX_FRAME,
            "row", MESSAGE_ROW);

    /** Browser-side predicate: the message frame shows the requested mail, or a challenge is showing. */
    static final String MESSAGE_READY = """
            site => {
              const challenge = document.querySelector(site.challenge);
              if (challenge && (challenge.offsetWidth || challenge.offsetHeight)) return true;
              try {
                const frame = document.querySelector(site.mailFrame);
                const doc = frame?.contentDocument;
                return new URL(frame.contentWindow.location.href).searchParams.get('id') === site.mailId
                  && doc?.readyState === 'complete' && doc.querySelector(site.mailBody) !== null;
              } catch (_) { return false; }
            }
            """;

    private YopmailSite() {}

    static Map<String, String> messageReadyArgs(String id) {
        String mailId = MAIL_ID_PREFIX + id;
        return Map.of("challenge", CHALLENGE, "mailFrame", MAIL_FRAME, "mailBody", MAIL_BODY, "mailId", mailId);
    }

    /** Selector for one inbox row. Ids come from the site, so they are quoted rather than trusted. */
    static String messageRow(String id) {
        String quoted = id.replace("\\", "\\\\").replace("\"", "\\\"");
        return ROW_CLASS + "[id=\"" + quoted + "\"]";
    }
}
