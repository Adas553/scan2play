package com.scan2play.service;

import com.scan2play.service.EmailSender.Email;
import com.scan2play.service.EmailSender.Result;
import com.scan2play.util.Texts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * What the automatic e-mails share: the language of an organiser's e-mails, the page of an e-mail made from its template
 * ({@code templates/email/*}), its send through {@link EmailSender} — whatever goes wrong, never an exception to the caller —, the
 * last send that worked and the last one that did not ({@code email_status}, V64, for {@code /dj/stats}), and one thread that sends
 * off the request's ({@link #later}).
 */
@Component
@Slf4j
public class Mailer {

    /** One thread: a handful of e-mails a day; a full queue drops the task — the nightly job sends what was dropped. */
    private static final int QUEUE = 100;

    /** The last send that worked and the last one that did not, with why; any of them null when there was none. */
    public record Status(Instant lastSent, Instant lastFailed, String lastFailure) {
    }

    private final EmailSender sender;
    private final ITemplateEngine templates;
    private final JdbcTemplate jdbc;
    private final Executor executor;
    private final String siteUrl;

    @Autowired
    public Mailer(EmailSender sender, ITemplateEngine templates, JdbcTemplate jdbc, @Value("${scan2play.site-url}") String siteUrl) {
        this(sender, templates, jdbc, siteUrl, new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(QUEUE),
                Thread.ofPlatform().name("email-", 1).daemon().factory()));
    }

    /** For the tests: an executor of their own (one that runs at once). */
    Mailer(EmailSender sender, ITemplateEngine templates, JdbcTemplate jdbc, String siteUrl, Executor executor) {
        this.sender = sender;
        this.templates = templates;
        this.jdbc = jdbc;
        this.executor = executor;
        this.siteUrl = Texts.withoutTrailingSlash(siteUrl);
    }

    /** The site's address without the slash at its end: the e-mails' links are whole addresses. */
    public String siteUrl() {
        return siteUrl;
    }

    /** The e-mails' language of an organiser: the panel's language they last opened it in ({@code email_language}); none — Polish. */
    public static Locale localeOf(String emailLanguage) {
        return "en".equals(emailLanguage) ? Locale.ENGLISH : Locale.forLanguageTag("pl");
    }

    /** The panel's language as kept for the e-mails: "en" or "pl" (any other language reads the English pages, as the site). */
    public static String languageOf(Locale locale) {
        return locale != null && "pl".equals(locale.getLanguage()) ? "pl" : "en";
    }

    /** "Nie wysyłaj mi tego": the one-click address of an organiser's secret, in their language. */
    public String stopUrl(String emailToken, Locale locale) {
        return emailToken == null ? null : siteUrl + "/email/stop/" + emailToken + "?lang=" + languageOf(locale);
    }

    /** The page of an e-mail: {@code templates/email/<name>_<pl|en>.html} with {@code variables}. */
    public String render(String name, Locale locale, Map<String, Object> variables) {
        Context context = new Context(locale, variables);
        context.setVariable("siteUrl", siteUrl);
        return templates.process("email/" + name + "_" + languageOf(locale), context);
    }

    /**
     * Sends it now, on the caller's thread, and keeps how it went; true when the service took it — or may have (no answer in time:
     * the caller keeps its claim, the same e-mail twice is worse than one lost). Never throws: a send is never a reason for a page, a
     * login or a job to fail.
     */
    public boolean send(Email email) {
        Result result;
        try {
            result = sender.send(email);
        } catch (RuntimeException e) {
            log.warn("E-mail not sent: {}", e.toString());
            result = Result.FAILED;
        }
        record(result);
        return result == Result.SENT || result == Result.UNKNOWN;
    }

    /** Runs {@code task} on the e-mails' thread; a full queue drops it (logged) — the caller's nightly job tries again. */
    public void later(Runnable task) {
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    log.warn("E-mail task failed: {}", e.toString());
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("E-mail task dropped (the queue is full); the nightly job sends it");
        }
    }

    private void record(Result result) {
        try {
            Timestamp now = Timestamp.from(Instant.now());
            switch (result) {
                case SENT -> jdbc.update("UPDATE email_status SET last_sent_at = ? WHERE id = 1", now);
                case KEY_REFUSED -> jdbc.update("UPDATE email_status SET last_failed_at = ?, last_failure = ? WHERE id = 1", now,
                        "the key was refused (401/403): a new BREVO_API_KEY is needed");
                case FAILED -> jdbc.update("UPDATE email_status SET last_failed_at = ?, last_failure = ? WHERE id = 1", now,
                        "the send failed (the network or Brevo); tried again later");
                case UNKNOWN -> jdbc.update("UPDATE email_status SET last_failed_at = ?, last_failure = ? WHERE id = 1", now,
                        "Brevo did not answer in time: maybe sent, not tried again (see Brevo's log)");
                case OFF -> {
                    // no key on this server: nothing to keep
                }
            }
        } catch (RuntimeException e) {
            log.warn("E-mail status not kept: {}", e.toString());
        }
    }

    /** How the e-mails went, for {@code /dj/stats}. */
    public Status status() {
        List<Status> rows = jdbc.query("SELECT last_sent_at, last_failed_at, last_failure FROM email_status WHERE id = 1",
                (row, n) -> new Status(instant(row.getTimestamp(1)), instant(row.getTimestamp(2)), row.getString(3)));
        return rows.isEmpty() ? new Status(null, null, null) : rows.getFirst();
    }

    /** Whether this server has a key to send with. */
    public boolean isOn() {
        return !(sender instanceof BrevoEmailSender brevo) || brevo.isOn();
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /**
     * The words of an e-mail's page for its text part: the links as "words (address)", a paragraph, a heading, a line of a list or a
     * break as a new line, the tags and the page's head gone, the entities of the templates read back.
     */
    static String plainText(String html) {
        String text = html.replaceAll("(?is)<(head|style)[^>]*>.*?</\\1>", "")
                .replaceAll("(?is)<a\\s[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>", "$2 ($1)")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|h1|h2|h3|li|tr|div)>", "\n")
                .replaceAll("(?i)<li[^>]*>", "• ")
                .replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&");
        return text.lines().map(String::strip).reduce("", (all, line) -> line.isEmpty() && (all.isEmpty() || all.endsWith("\n\n"))
                ? all : all + line + "\n").strip() + "\n";
    }
}
