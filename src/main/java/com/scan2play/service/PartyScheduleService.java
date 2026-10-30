package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.util.PartySchedule;
import com.scan2play.util.Times;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

/**
 * The party that opens and closes by itself (V55, "Godziny imprezy"): the DJ's planned moments, the manual "Zakończ / Wznów" against
 * them, the job that applies a moment that has come, and the words the DJ and the guests read about them. Every write goes through
 * {@link PartySettingsCommandService} — the cached settings follow it. What it is now comes from the clock given, so the tests do not
 * wait.
 * <p>
 * A weekly plan (later: days and hours, for clubs) would write its next moment into the same two columns after each one; the job
 * would stay as it is.
 */
@Service
@Slf4j
public class PartyScheduleService {

    /** How many parties one run of the job opens or closes at most; the rest at the next minute. */
    static final int DUE_BATCH = 500;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("H:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM");
    private static final DateTimeFormatter FORM_TIME = DateTimeFormatter.ofPattern("HH:mm");
    /** Up to six days ahead a moment is named by its day of the week; further, by its date. */
    private static final int WEEKDAY_DAYS = 6;

    private final PartySettingsCommandService commands;
    private final MessageSource messages;
    private final Clock clock;

    @Autowired
    public PartyScheduleService(PartySettingsCommandService commands, MessageSource messages) {
        this(commands, messages, Clock.systemUTC());
    }

    /** With a clock of the test's own. */
    PartyScheduleService(PartySettingsCommandService commands, MessageSource messages, Clock clock) {
        this.commands = commands;
        this.messages = messages;
        this.clock = clock;
    }

    /**
     * Sets the party's plan (either moment may be null — none). Checked against the party as it is under its row's lock, so a
     * manual close at the same moment is taken into account.
     *
     * @throws PartySchedule.Refused why not; nothing is saved
     */
    public void plan(String partyCode, PartySchedule.Plan plan) {
        Instant now = clock.instant();
        commands.updateSettings(partyCode, s -> {
            PartySchedule.check(plan, s.isActive(), now);
            s.setNextOpenAt(plan.openAt());
            s.setNextCloseAt(plan.closeAt());
        });
        log.info("Party [{}]: planned open {} close {}", partyCode, plan.openAt(), plan.closeAt());
    }

    /** Takes one planned moment away ({@code open} true: the open); the other stays, unless it would then change nothing. */
    public void cancel(String partyCode, boolean open) {
        commands.updateSettings(partyCode, s -> {
            if (open) {
                s.setNextOpenAt(null);
            } else {
                s.setNextCloseAt(null);
            }
            settle(s);
        });
    }

    /**
     * "Wznów imprezę" / "Zakończ imprezę": the party open or closed now — and the planned moment this made pointless gone (a close
     * before the next open has happened; an open the DJ made early), the other kept.
     */
    public void setActive(String partyCode, boolean active) {
        commands.updateSettings(partyCode, s -> {
            s.setActive(active);
            settle(s);
        });
    }

    private static void settle(PartySettingsEntity s) {
        PartySchedule.Plan settled = PartySchedule.settle(new PartySchedule.Plan(s.getNextOpenAt(), s.getNextCloseAt()), s.isActive());
        s.setNextOpenAt(settled.openAt());
        s.setNextCloseAt(settled.closeAt());
    }

    /**
     * Every minute, at its start (a moment is set to the minute): the parties whose moment has come opened or closed. Safe with two
     * instances — the statement takes only rows nobody holds, and a row another run changed is no longer due.
     */
    @Scheduled(cron = "0 * * * * *", zone = Times.SCHEDULE_ZONE)
    public void applyDue() {
        List<PartySettingsCommandService.ScheduleChange> changes = commands.applyDueSchedule(clock.instant(), DUE_BATCH);
        for (PartySettingsCommandService.ScheduleChange change : changes) {
            log.info("Party [{}] {} by its plan", change.partyCode(), change.active() ? "opened" : "closed");
        }
    }

    // ---- The words ----

    /**
     * What the guest of a closed party reads in place of "DJ nie przyjmuje teraz próśb" when it opens by itself: "Prośby od 20:00"
     * (today), "Prośby od soboty, 20:00" (within six days), "Prośby od 14.11, 20:00"; null without a planned open.
     */
    public String guestOpens(PartySettingsEntity party, Locale locale) {
        Instant at = party.getNextOpenAt();
        if (at == null) {
            return null;
        }
        ZonedDateTime local = at.atZone(Times.DISPLAY_ZONE);
        long days = daysAhead(local);
        String time = CLOCK.format(local);
        if (days <= 0) {
            return messages.getMessage("party.opens.today", new Object[]{time}, locale);
        }
        if (days <= WEEKDAY_DAYS) {
            return messages.getMessage("party.opens.day." + local.getDayOfWeek().getValue(), new Object[]{time}, locale);
        }
        return messages.getMessage("party.opens.date", new Object[]{time, DAY.format(local)}, locale);
    }

    /** What the panel and the settings show of the plan, and the form's fields as they are. */
    public View view(PartySettingsEntity party, Locale locale) {
        Instant open = party.getNextOpenAt();
        Instant close = party.getNextCloseAt();
        LocalDate today = LocalDate.now(clock.withZone(Times.DISPLAY_ZONE));
        return new View(key("open", open), open == null ? null : panelText("schedule.opens", open, locale),
                key("close", close), close == null ? null : panelText("schedule.closes", close, locale),
                formDate(open), formTime(open), formDate(close), formTime(close),
                today.toString(), today.plusDays(1).toString(), open != null && (close == null || open.isBefore(close)));
    }

    /**
     * The plan as the poll says it ({@code X-Party-Schedule}): "open:1761926400,close:1761948000", the planned ones only — the panel
     * hides a line it shows that is no longer there (applied, cancelled, made pointless by a manual close).
     */
    public static String headerValue(PartySettingsEntity party) {
        String open = key("open", party.getNextOpenAt());
        String close = key("close", party.getNextCloseAt());
        return open == null ? (close == null ? "" : close) : (close == null ? open : open + "," + close);
    }

    /**
     * The plan as shown. {@code openKey} / {@code closeKey}: the moment as the poll's header names it (null: none); the texts: "Otworzy
     * się w sobotę o 20:00", "Zamknie się dziś o 3:00"; the form's fields in Polish time; today and tomorrow there for the quick
     * choices; whether the open comes first (it is listed first).
     */
    public record View(String openKey, String openText, String closeKey, String closeText,
                       String openDate, String openTime, String closeDate, String closeTime,
                       String today, String tomorrow, boolean openFirst) {
        public boolean any() {
            return openKey != null || closeKey != null;
        }
    }

    private String panelText(String kind, Instant at, Locale locale) {
        ZonedDateTime local = at.atZone(Times.DISPLAY_ZONE);
        long days = daysAhead(local);
        String time = CLOCK.format(local);
        String when;
        if (days <= 0) {
            when = messages.getMessage("schedule.when.today", new Object[]{time}, locale);
        } else if (days == 1) {
            when = messages.getMessage("schedule.when.tomorrow", new Object[]{time}, locale);
        } else if (days <= WEEKDAY_DAYS) {
            when = messages.getMessage("schedule.when.day." + local.getDayOfWeek().getValue(), new Object[]{time}, locale);
        } else {
            when = messages.getMessage("schedule.when.date", new Object[]{time, DAY.format(local)}, locale);
        }
        return messages.getMessage(kind, new Object[]{when}, locale);
    }

    /** Days from today to the moment's day, both in Polish time. */
    private long daysAhead(ZonedDateTime local) {
        return ChronoUnit.DAYS.between(LocalDate.now(clock.withZone(Times.DISPLAY_ZONE)), local.toLocalDate());
    }

    private static String key(String kind, Instant at) {
        return at == null ? null : kind + ":" + at.getEpochSecond();
    }

    private static String formDate(Instant at) {
        return at == null ? "" : at.atZone(Times.DISPLAY_ZONE).toLocalDate().toString();
    }

    private static String formTime(Instant at) {
        return at == null ? "" : FORM_TIME.format(at.atZone(Times.DISPLAY_ZONE));
    }
}
