package com.scan2play.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.scan2play.util.CodeGenerator;
import com.scan2play.util.SongList;
import com.scan2play.util.SongNames;
import com.scan2play.util.Times;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * "Plan imprezy" (V77, Pro, docs/ideas.md item 17): the plan of one wishes' event ({@link WishService.Event}) — the evening's
 * moments in their order, each with a time, a song and a note, and the event's own "don't play" / "must play" — written by the DJ on
 * the event's page and by the hosts, without an account, on the event's link {@code /h/e/{token}}. Last save wins, as the party's
 * hosts' lists; the pages say who saved last and when.
 * <p>
 * The event's lists are not the party's: a DJ plans two weddings on two Saturdays at once, and the party's lists are what the AI and
 * the queue use tonight. "Użyj na imprezie" copies the event's lists over the party's ({@code DjEventPlanController.useLists}).
 * <p>
 * Plain SQL, as {@link WishService}: a save is one transaction under the event row's lock — the moments replaced whole — and a plan
 * is at most {@value #MAX_MOMENTS} rows. A save names the version it was made from ({@link Plan#version}): a plan saved by someone
 * else since that page was opened is not overwritten ({@link Saved#CHANGED}) — a plan is written slowly, by the DJ and the hosts at
 * once, and a whole-plan save would otherwise drop the other one's moments without a word.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventPlanService {

    /** Moments of one evening: a wedding's plan is 10–15, with room. */
    public static final int MAX_MOMENTS = 30;
    /** As the columns of {@code event_plan_item}. */
    public static final int LABEL_MAX = 60;
    public static final int SONG_MAX = 150;
    public static final int NOTE_MAX = 300;
    /** Who saved last: the column {@code wish_event.plan_saved_by}. */
    public static final String BY_DJ = "dj";
    public static final String BY_HOSTS = "hosts";
    /** The templates offered while a plan is empty: the keys of {@code plan.template.<key>} (the moments' names, "|" between). */
    public static final List<String> TEMPLATES = List.of("wedding", "eighteen", "birthday", "company");

    /**
     * Saves on one hosts' link in {@value #HOST_SAVE_WINDOW_MINUTES} minutes: a couple saves a few times an evening; a link that leaked
     * (a family chat) must not rewrite the plan in a loop — each save is a delete, up to 30 inserts and a log line.
     */
    static final int HOST_SAVES_PER_WINDOW = 30;
    static final int HOST_SAVE_WINDOW_MINUTES = 10;
    /** How long the panel's "tonight" is reused: the queue asks for it every 3 s, a save forgets it at once. */
    static final Duration TONIGHT_KEPT = Duration.ofSeconds(30);

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("H:mm");

    /** What a save did: saved; nothing, the plan was saved by someone else since; nothing, the link opens nothing; nothing, too many. */
    public enum Saved { SAVED, CHANGED, GONE, TOO_MANY }

    /**
     * What a refused save had in its form: the page shows it again instead of the plan, so nothing typed is lost (a flash attribute,
     * so serialisable — the session is kept in the database).
     */
    public record Draft(List<Moment> moments, String blocked, String wanted) implements Serializable {
    }

    /** One moment: its name, its time (none: "when it comes"), the song and the note (each null for none). */
    public record Moment(String label, LocalTime time, String song, String note) implements Serializable {

        /** The time as the form and the print show it, "17:30"; "" for none. */
        public String timeText() {
            return time == null ? "" : time.format(DateTimeFormatter.ofPattern("HH:mm"));
        }
    }

    /** The plan of an event: its moments, its two lists, and the last save — when and by whom (null for never). */
    public record Plan(List<Moment> moments, String blocked, String wanted, Instant savedAt, String savedBy) {

        public boolean isEmpty() {
            return moments.isEmpty() && (blocked == null || blocked.isBlank()) && (wanted == null || wanted.isBlank());
        }

        /** The version a form sends back with a save: the last save's moment in microseconds (the column's precision); "" for never. */
        public String version() {
            return versionOf(savedAt);
        }
    }

    static String versionOf(Instant savedAt) {
        return savedAt == null ? "" : String.valueOf(savedAt.getEpochSecond() * 1_000_000L + savedAt.getNano() / 1_000);
    }

    /** The event a hosts' link opens: its id and its party. */
    public record HostEvent(long id, String partyCode) {
    }

    /** The moments as a form sent them, tidied; and how many rows over {@value #MAX_MOMENTS} were left out. */
    public record Moments(List<Moment> kept, int dropped) {
    }

    private final JdbcTemplate jdbc;
    /** Saves by hosts' link, a window from its first save (in memory: one instance, Section 13; a restart forgets them). */
    private final Cache<String, AtomicInteger> hostSaves = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(HOST_SAVE_WINDOW_MINUTES)).maximumSize(10_000).build();
    /** Tonight's plan by party, with the moment it was chosen: see {@link #TONIGHT_KEPT}. */
    private final Cache<String, Kept> tonights = Caffeine.newBuilder().expireAfterWrite(TONIGHT_KEPT).maximumSize(10_000).build();

    /** Tonight's plan as it was chosen at {@code at} (the choice depends on the hour: {@link #choose}). */
    private record Kept(Instant at, Optional<Tonight> tonight) {

        boolean holdsFor(Instant now) {
            return !now.isBefore(at) && now.isBefore(at.plus(TONIGHT_KEPT))
                    && EveningSummaryService.eveningOf(now).equals(EveningSummaryService.eveningOf(at));
        }
    }

    /** The event's plan; an event without one has no moments, no lists, never saved. */
    public Plan plan(long eventId) {
        List<Moment> moments = jdbc.query("SELECT label, at_time, song, note FROM event_plan_item WHERE event_id = ? ORDER BY position"
                + " LIMIT ?", (row, n) -> {
                    Time time = row.getTime(2);
                    return new Moment(row.getString(1), time == null ? null : time.toLocalTime(), row.getString(3), row.getString(4));
                }, eventId, MAX_MOMENTS);
        return jdbc.query("SELECT plan_blocked, plan_wanted, plan_saved_at, plan_saved_by FROM wish_event WHERE id = ?", (row, n) -> {
            Timestamp saved = row.getTimestamp(3);
            return new Plan(moments, row.getString(1), row.getString(2), saved == null ? null : saved.toInstant(), row.getString(4));
        }, eventId).stream().findFirst().orElse(new Plan(moments, null, null, null, null));
    }

    /**
     * Saves the whole plan: the moments replace the ones there were, the lists are tidied as the party's ({@link SongList#tidy}).
     * Under the event row's lock, so two saves at once leave one plan, not a mix of both; and only over the version the form was made
     * from ({@code version}, {@link Plan#version}; null: no check) — else nothing is written ({@link Saved#CHANGED}).
     *
     * @param by {@link #BY_DJ} or {@link #BY_HOSTS}
     */
    @Transactional
    public Saved save(long eventId, List<Moment> moments, String blocked, String wanted, String by, String version) {
        List<Instant> saved = jdbc.query("SELECT plan_saved_at FROM wish_event WHERE id = ? FOR UPDATE", (row, n) -> {
            Timestamp at = row.getTimestamp(1);
            return at == null ? Instant.EPOCH : at.toInstant();
        }, eventId);
        if (saved.isEmpty()) {
            return Saved.GONE;
        }
        if (changedSince(saved.getFirst(), version)) {
            return Saved.CHANGED;
        }
        write(eventId, moments, blocked, wanted, by);
        return Saved.SAVED;
    }

    /**
     * {@link #save} without a version: whatever was saved meanwhile is overwritten. Transactional itself: a call to the other
     * {@code save} from here does not pass Spring's proxy, so without it the row's lock would end before the moments are written.
     */
    @Transactional
    public Saved save(long eventId, List<Moment> moments, String blocked, String wanted, String by) {
        return save(eventId, moments, blocked, wanted, by, null);
    }

    /**
     * The hosts' save on their link: the event whose hosts' link {@code token} is, asked again under its row's lock — a link the DJ
     * made new meanwhile writes nothing ({@link Saved#GONE}); at most {@value #HOST_SAVES_PER_WINDOW} saves a link in
     * {@value #HOST_SAVE_WINDOW_MINUTES} minutes ({@link Saved#TOO_MANY}).
     */
    @Transactional
    public Saved saveByHosts(String token, List<Moment> moments, String blocked, String wanted, String version) {
        if (!wellFormed(token)) {
            return Saved.GONE;
        }
        if (hostSaves.get(token, key -> new AtomicInteger()).incrementAndGet() > HOST_SAVES_PER_WINDOW) {
            return Saved.TOO_MANY;
        }
        List<Object[]> event = jdbc.query("SELECT id, plan_saved_at FROM wish_event WHERE host_token = ? FOR UPDATE",
                (row, n) -> new Object[] {row.getLong(1), row.getTimestamp(2)}, token);
        if (event.isEmpty()) {
            return Saved.GONE;
        }
        Timestamp at = (Timestamp) event.getFirst()[1];
        if (changedSince(at == null ? Instant.EPOCH : at.toInstant(), version)) {
            return Saved.CHANGED;
        }
        write((Long) event.getFirst()[0], moments, blocked, wanted, BY_HOSTS);
        return Saved.SAVED;
    }

    /** Whether the plan was saved after the version a form was made from (EPOCH: never saved); no version, no check. */
    private static boolean changedSince(Instant saved, String version) {
        if (version == null) {
            return false;
        }
        return !versionOf(Instant.EPOCH.equals(saved) ? null : saved).equals(version.strip());
    }

    private void write(long eventId, List<Moment> moments, String blocked, String wanted, String by) {
        jdbc.update("DELETE FROM event_plan_item WHERE event_id = ?", eventId);
        List<Object[]> rows = new ArrayList<>();
        List<Moment> kept = moments.size() > MAX_MOMENTS ? moments.subList(0, MAX_MOMENTS) : moments;
        for (int position = 0; position < kept.size(); position++) {
            Moment moment = kept.get(position);
            rows.add(new Object[] {eventId, position, moment.label(), moment.time() == null ? null : Time.valueOf(moment.time()),
                    moment.song(), moment.note()});
        }
        if (!rows.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO event_plan_item (event_id, position, label, at_time, song, note) VALUES (?, ?, ?, ?, ?, ?)", rows);
        }
        jdbc.queryForList("UPDATE wish_event SET plan_blocked = ?, plan_wanted = ?, plan_saved_at = now(), plan_saved_by = ? WHERE id = ?"
                + " RETURNING party_code", String.class, SongList.tidy(blocked), SongList.tidy(wanted), by, eventId)
                .forEach(tonights::invalidate);   // the panel's card and its "📋 z planu" at once, not in 30 s
        log.info("Wishes event {}: plan saved by {} ({} moments)", eventId, by, kept.size());
    }

    /**
     * "Wyczyść plan" (the DJ's page): the moments, both lists and the last save gone — the plan as never written, the templates offered
     * again; the hosts' link stays (it has its own "Wyłącz link"). A page opened before saves nothing over it (its version is not "").
     * False for an event that is not there.
     */
    @Transactional
    public boolean clear(long eventId) {
        if (jdbc.queryForList("SELECT id FROM wish_event WHERE id = ? FOR UPDATE", Long.class, eventId).isEmpty()) {
            return false;
        }
        jdbc.update("DELETE FROM event_plan_item WHERE event_id = ?", eventId);
        jdbc.queryForList("UPDATE wish_event SET plan_blocked = NULL, plan_wanted = NULL, plan_saved_at = NULL, plan_saved_by = NULL"
                + " WHERE id = ? RETURNING party_code", String.class, eventId).forEach(tonights::invalidate);
        log.info("Wishes event {}: plan cleared", eventId);
        return true;
    }

    /**
     * A template's moments for an empty plan: their names only, the times and songs left to the people. Nothing when the plan has
     * moments already (two people choosing a template at once make one plan, not two) — false then.
     */
    @Transactional
    public boolean startFrom(long eventId, List<String> labels, String by) {
        jdbc.queryForObject("SELECT id FROM wish_event WHERE id = ? FOR UPDATE", Long.class, eventId);
        Integer moments = jdbc.queryForObject("SELECT count(*) FROM event_plan_item WHERE event_id = ?", Integer.class, eventId);
        if (moments != null && moments > 0) {
            return false;
        }
        Plan plan = plan(eventId);
        write(eventId, labels.stream().map(label -> new Moment(tidy(label, LABEL_MAX), null, null, null))
                .filter(moment -> moment.label() != null).toList(), plan.blocked(), plan.wanted(), by);
        return true;
    }

    // ---------------------------------------------------------------- on the night (variant B)

    /**
     * The plan the panel shows tonight: its event, the evening's date, the moments, and the event's two lists (tidied) — the panel
     * says when they are not the party's yet.
     */
    public record Tonight(long eventId, String name, LocalDate evening, List<Moment> moments, String blocked, String wanted) {

        /**
         * A moment's time as a moment of this evening, in milliseconds (the panel counts down to it with nothing but the device's
         * {@code Date.now()}): on the evening's day, or the next day's before 6:00 — the evening runs until 6:00 (oczepiny at 0:30
         * come after the cake at 23:00) — in Polish time, whatever the device's zone. Null for a moment without a time.
         */
        public Long at(Moment moment) {
            if (moment.time() == null) {
                return null;
            }
            LocalDate day = moment.time().isBefore(EveningSummaryService.EVENING_STARTS) ? evening.plusDays(1) : evening;
            return day.atTime(moment.time()).atZone(Times.DISPLAY_ZONE).toInstant().toEpochMilli();
        }

        /** A moment as the panel's ticks know it: its name and time — not its place, which moves when the plan changes. */
        public String key(Moment moment) {
            return moment.label() + "|" + moment.timeText();
        }

        /** The songs of the plan, a song a line; null for none. */
        String songs() {
            List<String> songs = moments.stream().map(Moment::song).filter(Objects::nonNull).toList();
            return songs.isEmpty() ? null : String.join("\n", songs);
        }

        /** The first moment with a time, as {@link #at}; null when none has one. */
        Long start() {
            return moments.stream().map(this::at).filter(Objects::nonNull).findFirst().orElse(null);
        }
    }

    /** At most this many of one evening's events are weighed against each other (a DJ plays two, three in a day at most). */
    static final int MAX_EVENTS_A_DAY = 5;

    /**
     * The plan of the party's event whose date is this evening ({@link EveningSummaryService#eveningOf}: until 6:00 the night still
     * belongs to the day before), when it has moments. Two events with a plan on one day (a christening at 14:00, a wedding at 19:00):
     * {@link #choose}. Kept {@link #TONIGHT_KEPT} — the queue's poll asks every 3 s; a save forgets it.
     */
    public Optional<Tonight> tonight(String partyCode, Instant now) {
        LocalDate evening = EveningSummaryService.eveningOf(now);
        Kept kept = tonights.getIfPresent(partyCode);
        if (kept != null && kept.holdsFor(now)) {
            return kept.tonight();
        }
        List<Tonight> events = jdbc.query("SELECT id, name, plan_blocked, plan_wanted FROM wish_event WHERE party_code = ? AND event_date = ?"
                        + " AND EXISTS (SELECT 1 FROM event_plan_item WHERE event_id = wish_event.id) ORDER BY id LIMIT ?",
                (row, n) -> new Tonight(row.getLong(1), row.getString(2), evening, List.of(), SongList.tidy(row.getString(3)),
                        SongList.tidy(row.getString(4))), partyCode, Date.valueOf(evening), MAX_EVENTS_A_DAY);
        Optional<Tonight> tonight = choose(events.stream().map(event -> new Tonight(event.eventId(), event.name(), evening,
                plan(event.eventId()).moments(), event.blocked(), event.wanted())).toList(), now);
        tonights.put(partyCode, new Kept(now, tonight));
        return tonight;
    }

    /**
     * Which of the evening's plans is tonight's: the one started last (its first time at most an hour from now), else the one starting
     * first; plans without any time count only when no plan has one — then the first made.
     */
    static Optional<Tonight> choose(List<Tonight> plans, Instant now) {
        long soon = now.plus(Duration.ofHours(1)).toEpochMilli();
        Optional<Tonight> started = plans.stream().filter(plan -> plan.start() != null && plan.start() <= soon)
                .max(Comparator.comparingLong(Tonight::start));
        if (started.isPresent()) {
            return started;
        }
        Optional<Tonight> next = plans.stream().filter(plan -> plan.start() != null).min(Comparator.comparingLong(Tonight::start));
        return next.isPresent() ? next : plans.stream().findFirst();
    }

    /** The songs of tonight's plan as one text, a song a line (null for none) — the queue marks a request for one "📋 z planu" and
     * its ETag carries the text's hash. From {@link #tonight}, so kept as it is. */
    public String tonightSongs(String partyCode, Instant now) {
        return tonight(partyCode, now).map(Tonight::songs).orElse(null);
    }

    /**
     * How many moments each of the party's events' plans has (events without a plan left out) — the list of events says which have a
     * plan without opening each. One query; a party keeps at most {@link WishService#MAX_EVENTS} events.
     */
    public Map<Long, Integer> momentCounts(String partyCode) {
        Map<Long, Integer> counts = new HashMap<>();
        jdbc.query("SELECT e.id, count(*) FROM wish_event e JOIN event_plan_item i ON i.event_id = e.id WHERE e.party_code = ?"
                + " GROUP BY e.id LIMIT ?", (java.sql.ResultSet row) -> {
                    counts.put(row.getLong(1), row.getInt(2));
                }, partyCode, WishService.MAX_EVENTS);
        return counts;
    }

    // ---------------------------------------------------------------- the hosts' link of an event

    /** The event whose hosts' link this is; none for an unknown or malformed secret. */
    public Optional<HostEvent> byHostToken(String token) {
        if (!wellFormed(token)) {
            return Optional.empty();
        }
        return jdbc.query("SELECT id, party_code FROM wish_event WHERE host_token = ?", (row, n) -> new HostEvent(row.getLong(1),
                row.getString(2)), token).stream().findFirst();
    }

    /** The event's hosts' link's secret; none while the DJ has made none. */
    public Optional<String> hostToken(String partyCode, long eventId) {
        return jdbc.queryForList("SELECT host_token FROM wish_event WHERE party_code = ? AND id = ? AND host_token IS NOT NULL",
                String.class, partyCode, eventId).stream().findFirst();
    }

    /** "Utwórz link" / "Nowy link": a new secret — the old link opens nothing now. False for another party's event. */
    public boolean newHostLink(String partyCode, long eventId) {
        return jdbc.update("UPDATE wish_event SET host_token = ? WHERE party_code = ? AND id = ?", CodeGenerator.generateSecret(),
                partyCode, eventId) == 1;
    }

    /** "Wyłącz link": no hosts' link; the plan stays. */
    public boolean removeHostLink(String partyCode, long eventId) {
        return jdbc.update("UPDATE wish_event SET host_token = NULL WHERE party_code = ? AND id = ?", partyCode, eventId) == 1;
    }

    private static boolean wellFormed(String token) {
        return token != null && !token.isEmpty() && token.length() <= 32;
    }

    // ---------------------------------------------------------------- the form's rows

    /**
     * The moments as a form sent them — four columns of the same length, in the order on the page. A row with nothing in it is left
     * out (the page's spare rows); a row with a time, a song or a note and no name gets {@code unnamed}; a time that is not "17:30" (or
     * "7:30") is none. At most {@value #MAX_MOMENTS}: the rest counted, so the page can say so.
     */
    public static Moments moments(String[] labels, String[] times, String[] songs, String[] notes, String unnamed) {
        List<Moment> kept = new ArrayList<>();
        int dropped = 0;
        int rows = labels == null ? 0 : labels.length;
        for (int i = 0; i < rows; i++) {
            String label = tidy(labels[i], LABEL_MAX);
            LocalTime time = time(at(times, i));
            String song = SongNames.tidy(at(songs, i)).isEmpty() ? null : tidy(SongNames.tidy(at(songs, i)), SONG_MAX);
            String note = tidy(at(notes, i), NOTE_MAX);
            if (label == null && time == null && song == null && note == null) {
                continue;
            }
            if (kept.size() >= MAX_MOMENTS) {
                dropped++;
                continue;
            }
            kept.add(new Moment(label == null ? unnamed : label, time, song, note));
        }
        return new Moments(kept, dropped);
    }

    private static String at(String[] values, int i) {
        return values != null && i < values.length ? values[i] : null;
    }

    /** "17:30", "7:30" or "17.30" as a time; anything else none. */
    static LocalTime time(String typed) {
        if (typed == null || typed.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(typed.strip().replace('.', ':'), CLOCK);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** One line, the spaces as one, at most {@code max}; null for nothing. */
    static String tidy(String typed, int max) {
        if (typed == null) {
            return null;
        }
        String line = typed.replaceAll("\\p{Cc}+", " ").replaceAll("\\s+", " ").strip();
        if (line.codePointCount(0, line.length()) > max) {
            line = line.substring(0, line.offsetByCodePoints(0, max)).strip();   // not half of an emoji
        }
        return line.isEmpty() ? null : line;
    }
}
