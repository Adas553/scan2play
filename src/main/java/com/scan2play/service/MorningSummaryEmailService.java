package com.scan2play.service;

import com.scan2play.entity.EveningSummaryEntity;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.PlanFeature;
import com.scan2play.repository.EveningSummaryRepository;
import com.scan2play.service.EmailSender.Email;
import com.scan2play.util.Texts;
import com.scan2play.util.Times;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The morning e-mail of the evening summary (V64): the evening's title and day, three numbers, the top 3 songs and a button to
 * "Podsumowanie wieczoru" — the next morning at 10:00 Polish time, once an evening, and only when it says something: the Pro plan
 * ({@link PlanFeature#SUMMARY}), at least {@value #MIN_REQUESTS} guests' requests that evening, the organiser's switch
 * "Poranne podsumowanie" on and an address to send to. A DJ of weekends gets one or two a week, none in a break.
 * <p>
 * "Once" is the claim of the evening's row ({@code evening_summary.morning_sent_at}, one upsert): two runs at once send it once. A
 * failed send gives the claim back, and the run at noon tries again.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MorningSummaryEmailService {

    /** The fewest guests' requests of an evening worth an e-mail. */
    static final int MIN_REQUESTS = 5;
    /** The most parties one run looks at. */
    static final int MAX_PARTIES = 500;
    /** The songs the e-mail lists. */
    static final int TOP = 3;

    private final EveningSummaryRepository eveningSummaryRepository;
    private final EveningSummaryService eveningSummaryService;
    private final PartySettingsQueryService partySettingsQueryService;
    private final PlanService planService;
    private final Mailer mailer;

    /** At 10:00 the evening before's e-mails; at noon the ones that failed at 10. */
    @Scheduled(cron = "0 0 10,12 * * *", zone = Times.SCHEDULE_ZONE)
    public void sendMorningSummaries() {
        if (mailer.isOn()) {
            send(Instant.now());
        }
    }

    /** The e-mails of the evening before {@code now}'s; how many went. */
    int send(Instant now) {
        LocalDate evening = EveningSummaryService.eveningOf(now).minusDays(1);
        Instant from = EveningSummaryService.startOf(evening);
        Instant to = EveningSummaryService.startOf(evening.plusDays(1));
        int sent = 0;
        for (String partyCode : eveningSummaryRepository.busyParties(from, to, MIN_REQUESTS, MAX_PARTIES)) {
            if (sendOne(partyCode, evening, now)) {
                sent++;
            }
        }
        if (sent > 0) {
            log.info("Morning summaries of {} sent: {}", evening, sent);
        }
        return sent;
    }

    /** One party's e-mail of the evening, if it is due and has not gone; true when this call sent it. */
    boolean sendOne(String partyCode, LocalDate evening, Instant now) {
        PartySettingsEntity settings;
        try {
            settings = partySettingsQueryService.getSettings(partyCode);
        } catch (IllegalArgumentException gone) {
            return false;
        }
        if (settings.getOwnerEmail() == null || !settings.isMorningSummary() || !planService.allows(settings, PlanFeature.SUMMARY)) {
            return false;
        }
        if (eveningSummaryRepository.claimMorning(partyCode, evening, now) != 1) {
            return false;
        }
        boolean sent;
        try {
            sent = mailer.send(email(settings, evening));
        } catch (RuntimeException e) {
            log.warn("Party [{}]: the morning summary of {} failed ({})", partyCode, evening, e.toString());
            sent = false;
        }
        if (!sent) {
            eveningSummaryRepository.releaseMorning(partyCode, evening);
        }
        return sent;
    }

    Email email(PartySettingsEntity settings, LocalDate evening) {
        String partyCode = settings.getPartyCode();
        Locale locale = Mailer.localeOf(settings.getEmailLanguage());
        boolean english = "en".equals(Mailer.languageOf(locale));
        EveningSummaryService.Summary summary = eveningSummaryService.summarize(partyCode, evening);
        String title = eveningSummaryService.evening(partyCode, evening).map(EveningSummaryEntity::getTitle).orElse(null);
        String day = evening.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", locale));
        List<EveningSummaryService.Song> top = summary.top().stream().limit(TOP).toList();
        String stopUrl = mailer.stopUrl(settings.getEmailToken(), locale);

        // the guests' requests, as the threshold of MIN_REQUESTS counts them: the DJ's own additions are no guest's
        int requests = summary.requests() - summary.addedByDj();
        Map<String, Object> variables = new HashMap<>();
        variables.put("title", title);
        variables.put("day", day);
        variables.put("requests", requests);
        variables.put("played", summary.played());
        variables.put("votes", summary.votes());
        variables.put("labels", labels(requests, summary.played(), summary.votes(), english));
        variables.put("top", top);
        variables.put("summaryUrl", mailer.siteUrl() + "/dj/summary?evening=" + evening + "&party=" + partyCode);
        variables.put("stopUrl", stopUrl);
        String html = mailer.render("morning", locale, variables);
        String date = evening.format(DateTimeFormatter.ofPattern("d.MM"));
        String subject = title != null
                ? title + " — " + (english ? "the evening's summary, " : "podsumowanie wieczoru, ") + date
                : (english ? "The evening's summary — " : "Podsumowanie wieczoru — ")
                        + evening.format(DateTimeFormatter.ofPattern("EEEE d.MM", locale));
        return new Email(settings.getOwnerEmail(), subject, html, Mailer.plainText(html), stopUrl);
    }

    /**
     * The words under the three numbers, each by its number — "5 próśb gości", "1 zagrana", "23 głosy gości" (a label under a number
     * reads with it); in English the singular of 1.
     */
    static List<String> labels(long requests, long played, long votes, boolean english) {
        if (english) {
            return List.of(requests == 1 ? "guest's request" : "guests' requests", "played", votes == 1 ? "guest's vote" : "guests' votes");
        }
        return List.of(Texts.polishPlural(requests, "prośba gościa", "prośby gości", "próśb gości"),
                Texts.polishPlural(played, "zagrana", "zagrane", "zagranych"),
                Texts.polishPlural(votes, "głos gościa", "głosy gości", "głosów gości"));
    }
}
