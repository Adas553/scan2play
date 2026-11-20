package com.scan2play.service;

import com.scan2play.util.CodeGenerator;
import com.scan2play.util.SongList;
import com.scan2play.util.SongNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
 * is at most {@value #MAX_MOMENTS} rows.
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

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("H:mm");

    /** One moment: its name, its time (none: "when it comes"), the song and the note (each null for none). */
    public record Moment(String label, LocalTime time, String song, String note) {

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
    }

    /** The event a hosts' link opens: its id and its party. */
    public record HostEvent(long id, String partyCode) {
    }

    /** The moments as a form sent them, tidied; and how many rows over {@value #MAX_MOMENTS} were left out. */
    public record Moments(List<Moment> kept, int dropped) {
    }

    private final JdbcTemplate jdbc;

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
     * Under the event row's lock, so two saves at once leave one plan, not a mix of both.
     *
     * @param by {@link #BY_DJ} or {@link #BY_HOSTS}
     */
    @Transactional
    public void save(long eventId, List<Moment> moments, String blocked, String wanted, String by) {
        jdbc.queryForObject("SELECT id FROM wish_event WHERE id = ? FOR UPDATE", Long.class, eventId);
        write(eventId, moments, blocked, wanted, by);
    }

    /**
     * The hosts' save on their link: the event whose hosts' link {@code token} is, asked again under its row's lock — a link the DJ
     * made new meanwhile writes nothing. False when the link opens nothing (any more).
     */
    @Transactional
    public boolean saveByHosts(String token, List<Moment> moments, String blocked, String wanted) {
        if (!wellFormed(token)) {
            return false;
        }
        List<Long> event = jdbc.queryForList("SELECT id FROM wish_event WHERE host_token = ? FOR UPDATE", Long.class, token);
        if (event.isEmpty()) {
            return false;
        }
        write(event.getFirst(), moments, blocked, wanted, BY_HOSTS);
        return true;
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
        jdbc.update("UPDATE wish_event SET plan_blocked = ?, plan_wanted = ?, plan_saved_at = now(), plan_saved_by = ? WHERE id = ?",
                SongList.tidy(blocked), SongList.tidy(wanted), by, eventId);
        log.info("Wishes event {}: plan saved by {} ({} moments)", eventId, by, kept.size());
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
     * out (the page's spare rows); a row with a song or a note and no name gets {@code unnamed}; a time that is not "17:30" (or
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
            if (label == null && song == null && note == null) {
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
        if (line.length() > max) {
            line = line.substring(0, max).strip();
        }
        return line.isEmpty() ? null : line;
    }
}
