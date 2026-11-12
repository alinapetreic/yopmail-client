package io.github.yopmailclient;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

/** Parses the HTML of the inbox and message frames. Selectors come from {@link YopmailSite}. */
final class YopmailHtml {
    private YopmailHtml() {}

    static List<EmailSummary> summaries(String html) {
        var document = Jsoup.parse(html);
        List<EmailSummary> result = new ArrayList<>();
        String day = "";
        for (Element element : document.select(YopmailSite.DAY_HEADER + ", " + YopmailSite.MESSAGE_ROW)) {
            if (element.is(YopmailSite.DAY_HEADER)) {
                day = element.text();
                continue;
            }
            Element sender = element.selectFirst(YopmailSite.ROW_SENDER);
            Element subject = element.selectFirst(YopmailSite.ROW_SUBJECT);
            if (sender == null || subject == null || element.id().isBlank()) {
                throw new YopmailException("YOPmail message-list markup changed: sender, subject or id is missing.");
            }
            Element time = element.selectFirst(YopmailSite.ROW_TIME);
            String received = (day + " " + (time == null ? "" : time.text())).strip();
            result.add(new EmailSummary(element.id(), sender.text(), subject.text(), received));
        }
        return List.copyOf(result);
    }

    static EmailMessage message(EmailSummary summary, String frameHtml) {
        Element body = Jsoup.parse(frameHtml).selectFirst(YopmailSite.MAIL_BODY);
        if (body == null) {
            throw new YopmailException("YOPmail message body was not loaded or its markup changed.");
        }
        Set<URI> links = new LinkedHashSet<>();
        for (Element anchor : body.select("a[href]")) {
            try {
                URI uri = URI.create(anchor.attr("href").strip());
                if (uri.getHost() != null && isHttp(uri.getScheme())) {
                    links.add(uri);
                }
            } catch (IllegalArgumentException ignored) {
                // Malformed links are not useful verification URLs.
            }
        }
        return new EmailMessage(summary, body.text(), body.html(), List.copyOf(links));
    }

    private static boolean isHttp(String scheme) {
        return "https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme);
    }
}
