package com.scan2play.service;

import com.scan2play.entity.EveningSummaryEntity;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.repository.EveningSummaryRepository;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.service.EmailSender.Email;
import com.scan2play.service.SiteCounter.Event;
import com.scan2play.util.Texts;
import com.scan2play.util.Times;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.sql.Date;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The owner's daily report by e-mail: yesterday's traffic and its funnel beside the week's average ({@link SiteCounter}), the
 * organisers new in the last {@value #NEW_DAYS} days and whether they have had a party yet, every party of yesterday's evening with its
 * numbers, and the health of the AI, the e-mails and the log ({@link LogTally}). Sent to {@code scan2play.admin-email} at 9:10 Polish
 * time (at 11:10 again when the first send failed), and by hand from {@code /dj/stats/report}. No address or no Brevo key: nothing.
 * <p>
 * "Once a day" is the claim of {@code email_status.admin_report_day} (V65), one conditional UPDATE: two runs at once — or the old and
 * the new server during a deploy — send it once. A failed send gives the claim back.
 */
@Service
@Slf4j
public class AdminReportService {

    /** How far back an organiser is new, and how many the report lists. */
    static final int NEW_DAYS = 7;
    static final int NEW_MAX = 50;
    /** The most parties of one evening the report lists (the rest are counted). */
    static final int MAX_PARTIES = 50;
    /** The days of the average beside yesterday. */
    static final int AVERAGE_DAYS = 7;

    private static final Locale PL = Locale.forLanguageTag("pl");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d.MM.yyyy", PL);
    private static final DateTimeFormatter SHORT_DAY = DateTimeFormatter.ofPattern("d.MM");
    private static final DateTimeFormatter SHORT_MOMENT = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(Times.DISPLAY_ZONE);

    /**
     * The report: its subject, its text with the organisers' addresses and names and the evenings' titles (the owner's page) and the
     * e-mail's text without them — the privacy policy gives Brevo an organiser's address, and an evening's title, only to write to
     * that organiser, so the e-mail names a party by its code and the page says whose it is.
     */
    public record Report(String subject, String text, String emailText) {
    }

    /** One party of the evening, as the report lists it. */
    record PartyLine(String partyCode, String ownerEmail, String plan, String title, int guestRequests, int played,
                     int rejectedByAi, int refusedBeforeAi, int viaKiosk, int tips, int addedByDj) {
    }

    /** One new organiser: when, the party, the address and name, the plan, the guests' requests since. */
    record NewOrganiser(Instant createdAt, String partyCode, String email, String name, String plan, long guestRequests) {
    }

    /** What the report says of the AI and the e-mails. */
    record Health(boolean aiDown, boolean emailsOn, Mailer.Status emails, LogTally.Tally log) {
    }

    private final SiteCounter siteCounter;
    private final PartySettingsRepository partySettingsRepository;
    private final EveningSummaryRepository eveningSummaryRepository;
    private final EveningSummaryService eveningSummaryService;
    private final PlanService planService;
    private final Mailer mailer;
    private final AiHealthMonitor aiHealthMonitor;
    private final LogTally logTally;
    private final JdbcTemplate jdbc;
    private final String address;
    private final Clock clock;

    @Autowired
    public AdminReportService(SiteCounter siteCounter, PartySettingsRepository partySettingsRepository,
                              EveningSummaryRepository eveningSummaryRepository, EveningSummaryService eveningSummaryService,
                              PlanService planService, Mailer mailer, AiHealthMonitor aiHealthMonitor, LogTally logTally,
                              JdbcTemplate jdbc, @Value("${scan2play.admin-email:}") String address) {
        this(siteCounter, partySettingsRepository, eveningSummaryRepository, eveningSummaryService, planService, mailer, aiHealthMonitor,
                logTally, jdbc, address, Clock.systemUTC());
    }

    AdminReportService(SiteCounter siteCounter, PartySettingsRepository partySettingsRepository,
                       EveningSummaryRepository eveningSummaryRepository, EveningSummaryService eveningSummaryService,
                       PlanService planService, Mailer mailer, AiHealthMonitor aiHealthMonitor, LogTally logTally, JdbcTemplate jdbc,
                       String address, Clock clock) {
        this.siteCounter = siteCounter;
        this.partySettingsRepository = partySettingsRepository;
        this.eveningSummaryRepository = eveningSummaryRepository;
        this.eveningSummaryService = eveningSummaryService;
        this.planService = planService;
        this.mailer = mailer;
        this.aiHealthMonitor = aiHealthMonitor;
        this.logTally = logTally;
        this.jdbc = jdbc;
        this.address = address == null ? "" : address.strip();
        this.clock = clock;
    }

    /** The address the report goes to; empty when none is set. */
    public String address() {
        return address;
    }

    /** Whether a report can be sent: an address and a Brevo key. */
    public boolean canSend() {
        return !address.isEmpty() && mailer.isOn();
    }

    /** At 9:10 yesterday's report; at 11:10 again, if the first did not go. */
    @Scheduled(cron = "0 10 9,11 * * *", zone = Times.SCHEDULE_ZONE)
    public void sendDaily() {
        if (canSend()) {
            sendOnce(today());
        }
    }

    /** Today's report, if no run has sent it yet; true when this call sent it. */
    boolean sendOnce(LocalDate today) {
        if (jdbc.update("UPDATE email_status SET admin_report_day = ? WHERE id = 1"
                + " AND (admin_report_day IS NULL OR admin_report_day < ?)", Date.valueOf(today), Date.valueOf(today)) != 1) {
            return false;
        }
        LogTally.Tally tally = logTally.take();
        boolean sent = false;
        try {
            sent = send(build(clock.instant(), tally));
        } catch (RuntimeException e) {
            log.warn("The owner's report of {} not built: {}", today, e.toString());
        } finally {
            if (!sent) {
                // the claim and the log's counts back: the 11:10 run sends them
                logTally.giveBack(tally);
                jdbc.update("UPDATE email_status SET admin_report_day = NULL WHERE id = 1 AND admin_report_day = ?",
                        Date.valueOf(today));
                log.warn("The owner's report of {} not sent; tried again at 11:10", today);
            }
        }
        return sent;
    }

    /**
     * Sends the report now (the owner's button). The log's counts are kept: the morning's report still has the night's errors. True
     * when Brevo took it.
     */
    public boolean sendNow() {
        return canSend() && send(build(clock.instant(), logTally.peek()));
    }

    /** The report as it would go now, the log's counts kept (the page's preview). */
    public Report preview() {
        return build(clock.instant(), logTally.peek());
    }

    private boolean send(Report report) {
        String html = "<pre style=\"font-family:Menlo,Consolas,monospace;font-size:13px;line-height:1.45;white-space:pre-wrap;"
                + "margin:0;color:#1a1a1a;\">" + HtmlUtils.htmlEscape(report.emailText()) + "</pre>";
        return mailer.send(new Email(address, report.subject(), html, report.emailText(), null));
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(Times.DISPLAY_ZONE));
    }

    /** The report of the day before {@code now} (Polish time); the parties of that day's evening (6:00 to 6:00). */
    Report build(Instant now, LogTally.Tally tally) {
        LocalDate yesterday = now.atZone(Times.DISPLAY_ZONE).toLocalDate().minusDays(1);
        List<SiteCounter.Day> days = siteCounter.lastDays(AVERAGE_DAYS + 1);
        List<NewOrganiser> organisers = newOrganisers(now);
        // the demo's two parties may be among the rows: asked for, then left out, before the limit says whether there are more
        List<String> codes = eveningSummaryRepository.busyParties(EveningSummaryService.startOf(yesterday),
                EveningSummaryService.startOf(yesterday.plusDays(1)), 1, MAX_PARTIES + 3).stream()
                .filter(code -> !DemoParty.isDemo(code)).toList();
        List<PartyLine> parties = new ArrayList<>();
        for (String code : codes.stream().limit(MAX_PARTIES).toList()) {
            partyLine(code, yesterday).ifPresent(parties::add);
        }
        Health health = new Health(aiHealthMonitor.isAiDown(), mailer.isOn(), mailer.status(), tally);
        String text = render(yesterday, days, organisers, parties, codes.size() > MAX_PARTIES, health, true);
        String emailText = render(yesterday, days, organisers, parties, codes.size() > MAX_PARTIES, health, false)
                + "\nAdresy i imiona DJ-ów: " + mailer.siteUrl() + "/dj/stats/report\n";
        long visits = count(days, yesterday, Event.LANDING_PL) + count(days, yesterday, Event.LANDING_EN);
        String subject = "Scan2Play — raport za " + yesterday.format(SHORT_DAY) + ": " + visits + " "
                + Texts.polishPlural(visits, "wejście", "wejścia", "wejść") + ", " + parties.size() + " "
                + Texts.polishPlural(parties.size(), "impreza", "imprezy", "imprez")
                + (tally.errors() > 0 ? ", " + tally.errors() + " ERROR" : "");
        return new Report(subject, text, emailText);
    }

    private List<NewOrganiser> newOrganisers(Instant now) {
        Instant since = now.minus(Duration.ofDays(NEW_DAYS));
        List<PartySettingsEntity> found = partySettingsRepository.newOrganisers(since, Limit.of(NEW_MAX));
        Map<String, Long> requests = new HashMap<>();
        if (!found.isEmpty()) {
            eveningSummaryRepository.guestRequestsSince(found.stream().map(PartySettingsEntity::getPartyCode).toList(), since)
                    .forEach(row -> requests.put((String) row[0], ((Number) row[1]).longValue()));
        }
        return found.stream().map(party -> new NewOrganiser(party.getCreatedAt(), party.getPartyCode(), party.getOwnerEmail(),
                party.getOwnerName(), planService.planOf(party).name(), requests.getOrDefault(party.getPartyCode(), 0L))).toList();
    }

    private java.util.Optional<PartyLine> partyLine(String code, LocalDate evening) {
        return partySettingsRepository.findByPartyCode(code).map(party -> {
            EveningSummaryService.Summary summary = eveningSummaryService.summarize(code, evening);
            int refused = eveningSummaryService.refusals(code, evening).values().stream().mapToInt(Integer::intValue).sum();
            String title = eveningSummaryService.evening(code, evening).map(EveningSummaryEntity::getTitle).orElse(null);
            return new PartyLine(code, party.getOwnerEmail(), planService.planOf(party).name(), title,
                    summary.requests() - summary.addedByDj(), summary.played(), summary.rejectedByAi(), refused, summary.viaKiosk(),
                    summary.tips(), summary.addedByDj());
        });
    }

    // ---- the text ----

    static String render(LocalDate yesterday, List<SiteCounter.Day> days, List<NewOrganiser> organisers, List<PartyLine> parties,
                         boolean moreParties, Health health, boolean contacts) {
        StringBuilder text = new StringBuilder();
        text.append("Scan2Play — raport za ").append(yesterday.format(DAY)).append("\n\n");
        renderTraffic(text, yesterday, days);
        renderOrganisers(text, organisers, contacts);
        renderParties(text, yesterday, parties, moreParties, contacts);
        renderHealth(text, health);
        return text.toString();
    }

    private static final List<Map.Entry<Event, String>> TRAFFIC = List.of(
            Map.entry(Event.LANDING_PL, "Strona główna PL"), Map.entry(Event.LANDING_EN, "Strona główna EN"),
            Map.entry(Event.START, "„Zacznij”"), Map.entry(Event.LOGIN, "Logowania"), Map.entry(Event.NEW_DJ, "Nowi DJ-e"),
            Map.entry(Event.GUEST_REQUEST, "Prośby gości"), Map.entry(Event.DEMO, "Demo"));

    static void renderTraffic(StringBuilder text, LocalDate yesterday, List<SiteCounter.Day> days) {
        text.append("RUCH (ludzie, bez robotów) — wczoraj · średnio ").append(AVERAGE_DAYS).append(" dni\n");
        for (Map.Entry<Event, String> row : TRAFFIC) {
            text.append(String.format(PL, "%-18s %5d · %6.1f%n", row.getValue(), count(days, yesterday, row.getKey()),
                    average(days, yesterday, row.getKey())));
        }
        long landing = count(days, yesterday, Event.LANDING_PL) + count(days, yesterday, Event.LANDING_EN);
        double landingWeek = average(days, yesterday, Event.LANDING_PL) + average(days, yesterday, Event.LANDING_EN);
        text.append(String.format(PL, "%-18s %5s · %6s%n", "„Zacznij” / wejścia", percent(count(days, yesterday, Event.START), landing),
                percent(average(days, yesterday, Event.START), landingWeek)));
        text.append('\n');
    }

    static void renderOrganisers(StringBuilder text, List<NewOrganiser> organisers, boolean contacts) {
        text.append("NOWI DJ-E — ostatnie ").append(NEW_DAYS).append(" dni (").append(organisers.size()).append(")\n");
        for (NewOrganiser organiser : organisers) {
            text.append(SHORT_MOMENT.format(organiser.createdAt())).append("  ").append(organiser.partyCode()).append("  ")
                    .append(organiser.plan()).append("  próśb gości: ").append(organiser.guestRequests()).append('\n');
            if (!contacts) {
                continue;
            }
            text.append("  ").append(organiser.email());
            if (organiser.name() != null && !organiser.name().isBlank()) {
                text.append(" · ").append(organiser.name());
            }
            text.append('\n');
        }
        if (organisers.isEmpty()) {
            text.append("(nikt nowy)\n");
        }
        text.append('\n');
    }

    static void renderParties(StringBuilder text, LocalDate evening, List<PartyLine> parties, boolean more, boolean contacts) {
        text.append("IMPREZY WCZORAJ — wieczór ").append(evening.format(SHORT_DAY)).append(", 6:00–6:00 (").append(parties.size())
                .append(more ? "+" : "").append(")\n");
        int requests = 0;
        int played = 0;
        for (PartyLine party : parties) {
            requests += party.guestRequests();
            played += party.played();
            text.append(party.partyCode()).append("  ").append(party.plan());
            if (contacts) {
                text.append("  ").append(party.ownerEmail() == null ? "(bez adresu)" : party.ownerEmail());
            }
            if (contacts && party.title() != null && !party.title().isBlank()) {
                text.append(" · „").append(party.title()).append('”');
            }
            text.append('\n');
            text.append("  prośby ").append(party.guestRequests()).append(" · zagrane ").append(party.played())
                    .append(" · AI odrzuciło ").append(party.rejectedByAi()).append(" · przed AI ").append(party.refusedBeforeAi());
            if (party.viaKiosk() > 0) {
                text.append(" · kiosk ").append(party.viaKiosk());
            }
            if (party.tips() > 0) {
                text.append(" · napiwki ").append(party.tips());
            }
            if (party.addedByDj() > 0) {
                text.append(" · dodane przez DJ-a ").append(party.addedByDj());
            }
            text.append('\n');
        }
        if (parties.isEmpty()) {
            text.append("(żadnej)\n");
        } else {
            text.append("Razem: prośby ").append(requests).append(" · zagrane ").append(played)
                    .append(more ? " (pokazane pierwsze " + MAX_PARTIES + ")" : "").append('\n');
        }
        text.append('\n');
    }

    static void renderHealth(StringBuilder text, Health health) {
        text.append("ZDROWIE\n");
        text.append("AI (Gemini): ").append(health.aiDown() ? "NIE ODPOWIADA — prośby idą do DJ-a bez oceny" : "odpowiada").append('\n');
        Mailer.Status emails = health.emails();
        text.append("E-maile: ").append(health.emailsOn() ? "klucz ustawiony" : "BRAK KLUCZA (BREVO_API_KEY)");
        if (emails.lastFailed() != null && (emails.lastSent() == null || emails.lastFailed().isAfter(emails.lastSent()))) {
            text.append(" · OSTATNI BŁĄD ").append(Times.display(emails.lastFailed())).append(": ").append(emails.lastFailure());
        }
        text.append('\n');
        LogTally.Tally log = health.log();
        text.append("Log od ").append(Times.display(log.since())).append(": ERROR ").append(log.errors()).append(" · WARN ")
                .append(log.warnings()).append('\n');
        for (LogTally.Line line : log.lines()) {
            text.append(String.format("  %3d× %s%n", line.count(), line.text()));
        }
    }

    static long count(List<SiteCounter.Day> days, LocalDate day, Event event) {
        return days.stream().filter(d -> d.day().equals(day)).mapToLong(d -> d.counts().getOrDefault(event, 0)).sum();
    }

    /** The average of the {@value #AVERAGE_DAYS} days up to {@code yesterday}; a day without a row counts as 0. */
    static double average(List<SiteCounter.Day> days, LocalDate yesterday, Event event) {
        LocalDate first = yesterday.minusDays(AVERAGE_DAYS - 1L);
        return days.stream().filter(d -> !d.day().isBefore(first) && !d.day().isAfter(yesterday))
                .mapToLong(d -> d.counts().getOrDefault(event, 0)).sum() / (double) AVERAGE_DAYS;
    }

    static String percent(double part, double whole) {
        return whole <= 0 ? "—" : Math.round(100 * part / whole) + " %";
    }
}
