package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.service.EmailSender.Email;
import com.scan2play.util.AfterCommit;
import com.scan2play.util.Times;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The welcome e-mail to a new organiser (V64; the text of {@code docs/welcome-email.md}): once an account, in the panel's language,
 * no marketing. Asked for when the organiser opens the panel ({@link #sendLater}) and sent after that transaction commits, on the
 * e-mails' own thread — the login and the page never wait for Brevo, and a failed send never fails them. "Once" is the claim
 * ({@code welcome_sent_at}, one UPDATE): of two logins at once, or a login and the nightly job, one sends. A failed send gives the
 * claim back; {@link #sendDue} tries again every night for the accounts of the last {@value #RETRY_DAYS} days.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WelcomeEmailService {

    /** How long after the account was made the nightly job still sends a welcome that failed: after that it is not a welcome. */
    static final int RETRY_DAYS = 7;
    /** The most welcomes one night sends. */
    static final int RETRY_MAX = 50;

    private final PartySettingsQueryService partySettingsQueryService;
    private final PartySettingsCommandService partySettingsCommandService;
    private final PartySettingsRepository partySettingsRepository;
    private final Mailer mailer;

    /**
     * The welcome of this party, if it has not gone: after the caller's transaction commits (the organiser's address is saved by
     * then), on the e-mails' thread. Cheap when it went already — the cached settings say so.
     */
    public void sendLater(PartySettingsEntity settings) {
        if (settings.getWelcomeSentAt() != null || !mailer.isOn()) {
            return;   // sent; or no key on this server (a developer's machine): nothing to claim and give back at every panel
        }
        String partyCode = settings.getPartyCode();
        AfterCommit.run(() -> mailer.later(() -> send(partyCode)));
    }

    /** Sends the welcome now unless it went; true when this call sent it. */
    public boolean send(String partyCode) {
        if (!partySettingsCommandService.claimWelcome(partyCode, Instant.now())) {
            return false;
        }
        boolean sent;
        try {
            PartySettingsEntity settings = partySettingsQueryService.getSettings(partyCode);
            sent = settings.getOwnerEmail() != null && mailer.send(email(settings));
        } catch (RuntimeException e) {
            log.warn("Party [{}]: the welcome e-mail failed ({})", partyCode, e.toString());
            sent = false;
        }
        if (sent) {
            log.info("Party [{}]: the welcome e-mail sent", partyCode);
        } else {
            partySettingsCommandService.releaseWelcome(partyCode);
        }
        return sent;
    }

    /** Every night: the welcomes of the new accounts that failed (or were dropped), at most {@value #RETRY_MAX}. */
    @Scheduled(cron = "0 35 4 * * *", zone = Times.SCHEDULE_ZONE)
    public void sendDue() {
        if (!mailer.isOn()) {
            return;
        }
        Instant since = Instant.now().minus(Duration.ofDays(RETRY_DAYS));
        int sent = 0;
        for (String partyCode : partySettingsRepository.welcomesDue(since, RETRY_MAX)) {
            if (send(partyCode)) {
                sent++;
            }
        }
        if (sent > 0) {
            log.info("Welcome e-mails sent by the nightly job: {}", sent);
        }
    }

    Email email(PartySettingsEntity settings) {
        Locale locale = Mailer.localeOf(settings.getEmailLanguage());
        Map<String, Object> variables = new HashMap<>();
        variables.put("firstName", firstName(settings.getOwnerName()));
        String stopUrl = mailer.stopUrl(settings.getEmailToken(), locale);
        variables.put("stopUrl", stopUrl);
        String html = mailer.render("welcome", locale, variables);
        String subject = "en".equals(Mailer.languageOf(locale)) ? "Scan2Play — thanks for signing up" : "Scan2Play — dzięki za założenie konta";
        return new Email(settings.getOwnerEmail(), subject, html, Mailer.plainText(html), stopUrl);
    }

    /** "Ola" of "Ola Kowalska" (the name from Google); null without one. */
    static String firstName(String ownerName) {
        if (ownerName == null || ownerName.isBlank()) {
            return null;
        }
        return ownerName.strip().split("\\s+")[0];
    }
}
