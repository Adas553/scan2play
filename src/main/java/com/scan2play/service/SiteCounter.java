package com.scan2play.service;

import com.scan2play.util.Times;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * How many people come to the site and how far they get, per day (V40; the owner, 2026-10-24: after the first posts on Facebook the
 * only answer was Railway's HTTP log, kept 7 days): the landing page seen, "Zacznij" clicked, a login done, a new DJ, a guest's
 * request. Numbers only — no address, no browser, no account is kept; a robot is told apart by its browser's name and not counted
 * ({@link #isPerson}). The day is Polish time. A count that fails (the database away) is a line in the log, never an error of the
 * page that counted it. The owner reads it at {@code /dj/stats}.
 */
@Service
@Slf4j
public class SiteCounter {

    /** What is counted. The name is the row's {@code event} (at most 20 characters). */
    public enum Event {
        /** The landing page, by its language: a visitor not logged in. */
        LANDING_PL, LANDING_EN,
        /** "Zacznij" — on to Google's login. */
        START,
        /** A login done (Google let the person back). */
        LOGIN,
        /** A DJ's first login: their party made. */
        NEW_DJ,
        /** A guest's request sent to a party (before the limits and the AI). */
        GUEST_REQUEST,
        /** The demo's panel seen ({@code /demo}). */
        DEMO
    }

    /** One day's counts, every event there (0 when none). */
    public record Day(LocalDate day, Map<Event, Integer> counts) {
    }

    /** The browsers of robots: search engines, link previews (Facebook's for a post), scanners, scripts. */
    private static final Pattern NOT_A_PERSON = Pattern.compile(
            "bot|crawl|spider|slurp|facebookexternalhit|meta-externalagent|preview|curl|wget|python|java/|go-http|okhttp|"
                    + "headless|scan|checker|monitor|lighthouse", Pattern.CASE_INSENSITIVE);

    private static final String COUNT = "INSERT INTO site_counter (day, event, count) VALUES (?, ?, 1) "
            + "ON CONFLICT (day, event) DO UPDATE SET count = site_counter.count + 1";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public SiteCounter(JdbcTemplate jdbc) {
        this(jdbc, Clock.system(Times.DISPLAY_ZONE));
    }

    /** With a clock of the test's own. */
    SiteCounter(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** One more today. */
    public void count(Event event) {
        try {
            jdbc.update(COUNT, Date.valueOf(LocalDate.now(clock)), event.name());
        } catch (DataAccessException e) {
            log.warn("Site counter: {} not counted — {}", event, e.getMessage());
        }
    }

    /** One more today, when the browser is a person's ({@link #isPerson}). */
    public void countVisit(Event event, String userAgent) {
        if (isPerson(userAgent)) {
            count(event);
        }
    }

    /** A browser that names itself (every person's does) and is no robot's. */
    static boolean isPerson(String userAgent) {
        return userAgent != null && !userAgent.isBlank() && !NOT_A_PERSON.matcher(userAgent).find();
    }

    /** The last {@code days} days, today first; a day without a count is left out. Bounded: at most {@code days} × the events. */
    public List<Day> lastDays(int days) {
        LocalDate today = LocalDate.now(clock);
        LocalDate since = today.minusDays(days - 1L);
        TreeMap<LocalDate, Map<Event, Integer>> byDay = new TreeMap<>();
        jdbc.query("SELECT day, event, count FROM site_counter WHERE day BETWEEN ? AND ?", rows -> {
            Event event;
            try {
                event = Event.valueOf(rows.getString("event"));
            } catch (IllegalArgumentException gone) {
                return;   // an event no longer counted
            }
            byDay.computeIfAbsent(rows.getDate("day").toLocalDate(), day -> new EnumMap<>(Event.class))
                    .put(event, rows.getInt("count"));
        }, Date.valueOf(since), Date.valueOf(today));
        return byDay.descendingMap().entrySet().stream().map(day -> {
            Map<Event, Integer> counts = new EnumMap<>(Event.class);
            for (Event event : Event.values()) {
                counts.put(event, day.getValue().getOrDefault(event, 0));
            }
            return new Day(day.getKey(), counts);
        }).toList();
    }
}
