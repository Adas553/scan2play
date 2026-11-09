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

import java.sql.Date;
import java.sql.Time;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The owner's daily report by e-mail: yesterday's traffic and its funnel beside the week's average ({@link SiteCounter}), the
 * organisers new in the last {@value #NEW_DAYS} days and whether they have had a party yet, every party of yesterday's evening with its
 * numbers, and the health of the AI, the e-mails and the log ({@link LogTally}). Sent to {@code scan2play.admin-email} when the
 * owner's {@link Schedule} says (V66; every day at 9:10 by default; a failed send tried again for 3 hours), and by hand from
 * {@code /dj/stats/report}. No address or no Brevo key: nothing.
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
    public record Report(String subject, String text, String emailText, View view) {
    }

    /**
     * The report for the eye (the HTML e-mail, {@code templates/email/admin-report_pl.html}, and the owner's page): every number
     * worked out and every word written here, so the two templates only lay it out. {@code organisers} and {@code parties} carry the
     * addresses and the titles; the e-mail's template leaves them out.
     */
    public record View(String day, String dayShort, List<Tile> tiles, List<Bar> visits, List<Step> funnel, List<NewOrganiser> organisers,
                       List<PartyLine> parties, boolean moreParties, int totalRequests, int totalPlayed, List<Check> checks,
                       List<LogTally.Line> logLines, String logSince, String pageUrl) {
    }

    /** A number of yesterday beside the week's average: "up", "down" or "flat", and by how much ("+30 %"; empty with no average). */
    public record Tile(String label, long value, String average, String trend, String change) {
    }

    /** One day of the visits' chart: its label, its count and its length (0–100, of the week's busiest day). */
    public record Bar(String day, long value, int percent) {
    }

    /** One step of yesterday's funnel: its count and its share of the first step's. */
    public record Step(String label, long value, String share) {
    }

    /** One line of the health: ok or not, and what it says. */
    public record Check(String label, boolean ok, String detail) {
    }

    /** One party of the evening, as the report lists it. */
    public record PartyLine(String partyCode, String ownerEmail, String plan, String title, int guestRequests, int played,
                     int rejectedByAi, int refusedBeforeAi, int viaKiosk, int tips, int addedByDj) {
    }

    /** One new organiser: when, the party, the address and name, the plan, the guests' requests since. */
    public record NewOrganiser(Instant createdAt, String partyCode, String email, String name, String plan, long guestRequests) {
    }

    /** What the report says of the AI and the e-mails. */
    public record Health(boolean aiDown, boolean emailsOn, Mailer.Status emails, LogTally.Tally log) {
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

    /**
     * When the report goes (V66, the owner's page): on or off, the time of day in Polish time and the days of the week. The report is
     * always of the day before.
     */
    public record Schedule(boolean on, LocalTime time, Set<DayOfWeek> days) {

        public Schedule {
            days = Set.copyOf(days);
        }

        /** The days as the column keeps them: Monday 1, Tuesday 2, … Sunday 64. */
        int daysBits() {
            return days.stream().mapToInt(day -> 1 << (day.getValue() - 1)).sum();
        }

        static Set<DayOfWeek> days(int bits) {
            Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
            for (DayOfWeek day : DayOfWeek.values()) {
                if ((bits & (1 << (day.getValue() - 1))) != 0) {
                    days.add(day);
                }
            }
            return days;
        }

        /**
         * Whether a report is due at {@code now} (Polish time): on, a day of the schedule, from its time until 3 hours after it (a
         * failed send is tried again on the next ticks), never past midnight.
         */
        boolean isDue(LocalDateTime now) {
            if (!on || !days.contains(now.getDayOfWeek()) || now.toLocalTime().isBefore(time)) {
                return false;
            }
            LocalTime until = time.plusHours(RETRY_HOURS);
            return until.isBefore(time) || now.toLocalTime().isBefore(until);
        }
    }

    /** How long after its time a report that did not go is tried again, every {@value #TICK_MINUTES} minutes. */
    static final int RETRY_HOURS = 3;
    static final int TICK_MINUTES = 5;

    /** The schedule as the owner set it; every day at 9:10 until then. */
    public Schedule schedule() {
        List<Schedule> rows = jdbc.query("SELECT admin_report_on, admin_report_time, admin_report_days FROM email_status WHERE id = 1",
                (row, n) -> new Schedule(row.getBoolean(1), row.getTime(2).toLocalTime(), Schedule.days(row.getInt(3))));
        return rows.isEmpty() ? new Schedule(true, LocalTime.of(9, 10), EnumSet.allOf(DayOfWeek.class)) : rows.getFirst();
    }

    /** Keeps the owner's schedule (minutes only: the seconds are dropped). */
    public void saveSchedule(Schedule schedule) {
        jdbc.update("UPDATE email_status SET admin_report_on = ?, admin_report_time = ?, admin_report_days = ? WHERE id = 1",
                schedule.on(), Time.valueOf(schedule.time().withSecond(0).withNano(0)), schedule.daysBits());
    }

    /** Every {@value #TICK_MINUTES} minutes: the report, when the schedule says it is due and it has not gone today. */
    @Scheduled(cron = "0 */" + TICK_MINUTES + " * * * *", zone = Times.SCHEDULE_ZONE)
    public void sendDue() {
        if (canSend() && schedule().isDue(LocalDateTime.now(clock.withZone(Times.DISPLAY_ZONE)))) {
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
                // the claim and the log's counts back: the next tick sends them
                logTally.giveBack(tally);
                jdbc.update("UPDATE email_status SET admin_report_day = NULL WHERE id = 1 AND admin_report_day = ?",
                        Date.valueOf(today));
                log.warn("The owner's report of {} not sent; tried again in {} minutes", today, TICK_MINUTES);
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

    /** The HTML e-mail (its text part: the report's text without the addresses). */
    private boolean send(Report report) {
        String html = mailer.render("admin-report", PL, Map.of("v", report.view()));
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
        View view = view(yesterday, days, organisers, parties, codes.size() > MAX_PARTIES, health,
                mailer.siteUrl() + "/dj/stats/report");
        return new Report(subject, text, emailText, view);
    }

    // ---- the report for the eye ----

    private static final DateTimeFormatter BAR_DAY = DateTimeFormatter.ofPattern("EEE d.MM", PL);

    public static View view(LocalDate yesterday, List<SiteCounter.Day> days, List<NewOrganiser> organisers, List<PartyLine> parties,
                     boolean moreParties, Health health, String pageUrl) {
        long visits = visits(days, yesterday);
        double visitsWeek = average(days, yesterday, Event.LANDING_PL) + average(days, yesterday, Event.LANDING_EN);
        List<Tile> tiles = List.of(
                tile("Wejścia", visits, visitsWeek),
                tile("„Zacznij”", count(days, yesterday, Event.START), average(days, yesterday, Event.START)),
                tile("Nowi DJ-e", count(days, yesterday, Event.NEW_DJ), average(days, yesterday, Event.NEW_DJ)),
                tile("Prośby gości", count(days, yesterday, Event.GUEST_REQUEST), average(days, yesterday, Event.GUEST_REQUEST)));

        List<Long> week = new ArrayList<>();
        for (int back = AVERAGE_DAYS - 1; back >= 0; back--) {
            week.add(visits(days, yesterday.minusDays(back)));
        }
        long busiest = week.stream().mapToLong(Long::longValue).max().orElse(0);
        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < week.size(); i++) {
            LocalDate day = yesterday.minusDays(AVERAGE_DAYS - 1L - i);
            long value = week.get(i);
            // a day with any visit gets a sliver, so it does not read as none
            int percent = busiest == 0 ? 0 : (int) Math.max(value > 0 ? 2 : 0, Math.round(100.0 * value / busiest));
            bars.add(new Bar(day.format(BAR_DAY), value, percent));
        }

        long start = count(days, yesterday, Event.START);
        long login = count(days, yesterday, Event.LOGIN);
        long newDj = count(days, yesterday, Event.NEW_DJ);
        List<Step> funnel = List.of(new Step("Wejścia", visits, visits > 0 ? "100 %" : "—"),
                new Step("„Zacznij”", start, percent(start, visits)), new Step("Logowania", login, percent(login, visits)),
                new Step("Nowi DJ-e", newDj, percent(newDj, visits)));

        Mailer.Status emails = health.emails();
        boolean emailFailed = emails.lastFailed() != null && (emails.lastSent() == null || emails.lastFailed().isAfter(emails.lastSent()));
        LogTally.Tally log = health.log();
        List<Check> checks = List.of(
                new Check("AI (Gemini)", !health.aiDown(), health.aiDown() ? "nie odpowiada — prośby idą do DJ-a bez oceny" : "odpowiada"),
                new Check("E-maile", health.emailsOn() && !emailFailed, !health.emailsOn() ? "brak klucza (BREVO_API_KEY)"
                        : emailFailed ? "ostatni błąd " + Times.display(emails.lastFailed()) + ": " + emails.lastFailure() : "działają"),
                new Check("Log", log.errors() == 0, "ERROR " + log.errors() + " · WARN " + log.warnings()));

        int requests = parties.stream().mapToInt(PartyLine::guestRequests).sum();
        int played = parties.stream().mapToInt(PartyLine::played).sum();
        return new View(yesterday.format(DAY), yesterday.format(SHORT_DAY), tiles, bars, funnel, organisers, parties, moreParties,
                requests, played, checks, log.lines(), Times.display(log.since()), pageUrl);
    }

    static Tile tile(String label, long value, double average) {
        String averageText = String.format(PL, "śr. %.1f", average);
        if (average <= 0) {
            return new Tile(label, value, averageText, value > 0 ? "up" : "flat", "");
        }
        long change = Math.round(100 * (value - average) / average);
        String trend = change > 5 ? "up" : change < -5 ? "down" : "flat";
        return new Tile(label, value, averageText, trend, (change > 0 ? "+" : "") + change + " %");
    }

    private static long visits(List<SiteCounter.Day> days, LocalDate day) {
        return count(days, day, Event.LANDING_PL) + count(days, day, Event.LANDING_EN);
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
