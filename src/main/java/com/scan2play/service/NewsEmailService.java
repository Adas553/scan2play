package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.PanelNews;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.service.EmailSender.Email;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The e-mail of what is new ({@link PanelNews}, V80) — only to the organisers who switched "Nowości mailem" on (the privacy policy:
 * never without that consent), in their panel's language, with "Nie wysyłaj mi nowości" and {@code List-Unsubscribe}. Sent by hand
 * by the site's owner ({@code /dj/stats/news}), never by a schedule: a news goes once someone has looked at it. Only to whom it is
 * news: an organiser who opened their panel, or said yes, while it was there has it already ({@code markNewsKnown}) — the e-mail is
 * for those who have not logged in since.
 * <p>
 * "Once a news" is the claim of the organiser's row ({@code news_sent}, one {@code UPDATE}): two sends at once send it once, and a
 * second send gives it nobody twice. A failed send gives the claim back: the next send tries it again.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NewsEmailService {

    /** The most e-mails one send gives Brevo: its free plan takes 300 a day, the owner's daily report and the welcomes among them. */
    public static final int MAX_PER_SEND = 200;

    /** How many said yes, and how many of them have had the news now. */
    public record Counts(long yes, long had) {

        public long due() {
            return yes - had;
        }

        /** How many one send gives: the due ones, at most {@value #MAX_PER_SEND} — the rest wait for the next send. */
        public long nextSend() {
            return Math.min(due(), MAX_PER_SEND);
        }
    }

    private final PartySettingsQueryService partySettingsQueryService;
    private final PartySettingsCommandService partySettingsCommandService;
    private final PartySettingsRepository partySettingsRepository;
    private final Mailer mailer;
    private final AtomicBoolean sending = new AtomicBoolean();

    public Counts counts() {
        List<Object[]> rows = partySettingsRepository.newsCounts(PanelNews.NUMBER);
        Object[] row = rows.isEmpty() ? new Object[]{0L, 0L} : rows.getFirst();
        return new Counts(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
    }

    /** Whether a send is on its way now (the page says so, and a second click starts none). */
    public boolean isSending() {
        return sending.get();
    }

    /**
     * The news to every organiser it is due to, at most {@value #MAX_PER_SEND}, on the e-mails' thread; false when there is nothing to
     * send with (no key), a send is on its way already, or the e-mails' queue is full — then nothing is marked as on its way, or the
     * mark would stay until a restart and no send could start.
     */
    public boolean sendLater() {
        if (!mailer.isOn() || !sending.compareAndSet(false, true)) {
            return false;
        }
        boolean queued = mailer.later(() -> {
            try {
                sendDue();
            } finally {
                sending.set(false);
            }
        });
        if (!queued) {
            sending.set(false);
        }
        return queued;
    }

    /** Sends the news now to whom it is due; how many went. */
    int sendDue() {
        int sent = 0;
        for (String partyCode : partySettingsRepository.newsDue(PanelNews.NUMBER, MAX_PER_SEND)) {
            if (send(partyCode)) {
                sent++;
            }
        }
        log.info("News {} e-mailed: {}", PanelNews.NUMBER, sent);
        return sent;
    }

    /** One organiser's news e-mail, if it is due; true when this call sent it. */
    boolean send(String partyCode) {
        if (!partySettingsCommandService.claimNews(partyCode, PanelNews.NUMBER)) {
            return false;
        }
        boolean sent;
        try {
            PartySettingsEntity settings = partySettingsQueryService.getSettings(partyCode);
            sent = settings.getOwnerEmail() != null && mailer.send(email(settings, settings.getOwnerEmail()));
        } catch (RuntimeException e) {
            log.warn("Party [{}]: the news e-mail failed ({})", partyCode, e.toString());
            sent = false;
        }
        if (!sent) {
            partySettingsCommandService.releaseNews(partyCode, PanelNews.NUMBER);
        }
        return sent;
    }

    /** The news as an organiser would get it, to {@code address} — the owner's try before the send; true when Brevo took it. */
    public boolean sendTry(String address, String language) {
        if (address == null || address.isBlank()) {
            return false;
        }
        PartySettingsEntity sample = PartySettingsEntity.builder().ownerName(null).emailLanguage(language).build();
        return mailer.send(email(sample, address.strip()));
    }

    /** The e-mail's words in {@code language} ("pl" / "en") as its text part says them, for the owner's look before the send. */
    public String previewText(String language) {
        return Mailer.plainText(page(Mailer.localeOf(language), null, null));
    }

    Email email(PartySettingsEntity settings, String to) {
        Locale locale = Mailer.localeOf(settings.getEmailLanguage());
        String stopUrl = mailer.newsStopUrl(settings.getEmailToken(), locale);
        String html = page(locale, WelcomeEmailService.firstName(settings.getOwnerName()), stopUrl);
        return new Email(to, subject(locale), html, Mailer.plainText(html), stopUrl);
    }

    static String subject(Locale locale) {
        return "en".equals(Mailer.languageOf(locale))
                ? "Scan2Play — new: played by itself, a question for the guests, the party plan"
                : "Scan2Play — nowości: Zagrane samo, Pytanie do gości, Plan imprezy";
    }

    private String page(Locale locale, String firstName, String stopUrl) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("firstName", firstName);
        variables.put("stopUrl", stopUrl);
        return mailer.render("news", locale, variables);
    }
}
