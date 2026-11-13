package com.scan2play.service;

import com.scan2play.util.CodeGenerator;
import com.scan2play.util.LibraryKey;
import com.scan2play.util.SongNames;
import com.scan2play.util.Times;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * "Życzenia przed imprezą" (V68, Pro): before an event the guests open its link ({@code /w/{token}}) and write the songs they want;
 * the DJ sees them counted and moves the ones to play to "⭐ Koniecznie zagrać". A party has one code for every event, so the wishes
 * belong to an {@link Event} of their own — a DJ collects for two weddings on two Saturdays at once. Nothing of the guest is kept:
 * the song as typed and how many wrote it.
 * <p>
 * Plain SQL: a wish is one statement that counts — the event's ceiling raised under the row's lock, then the song's row made or
 * counted ({@code ON CONFLICT}) — and the lists are read whole and bounded; nothing an entity would add.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WishService {

    /** Events open at once on one party (not closed, their date today or later): a busy month of weekends, with room. */
    public static final int MAX_OPEN_EVENTS = 5;
    /** Wishes of one event: a ball of 300 guests writing five each, with room; a link that leaked to the internet stops there. */
    public static final int MAX_WISHES = 2_000;
    /** The event's name as the guests read it ("Wesele Ani i Kuby"), as the column {@code wish_event.name}. */
    public static final int NAME_MAX = 80;
    /** A song as a guest typed it, as the column {@code guest_wish.song_name}: the party page's field is 150 too. */
    public static final int SONG_MAX = 150;
    /** How far ahead an event may be: a wedding is booked a year or more ahead, invitations go two or three months before. */
    public static final int MAX_DAYS_AHEAD = 2 * 366;
    /** The wishes are kept this many days after the event's date — the privacy policy says so — and then deleted with it. */
    public static final int KEPT_DAYS_AFTER = 7;
    /** The songs the DJ's page lists of one event, the most wished first: the rest are in its CSV. */
    public static final int LIST_MAX = 500;
    /**
     * Events a party keeps at once — open, closed or past (a week after their date): every one of them is on the DJ's page, where it
     * can be closed or deleted, and a party's rows stay bounded however often events are closed and made again.
     */
    public static final int MAX_EVENTS = 20;

    /** One event: its guests' link's secret, its name and date, whether the DJ closed it, how many wishes it took. */
    public record Event(long id, String partyCode, String token, String name, LocalDate date, boolean closed, int wishCount,
                        Instant createdAt) {

        /** Whether the guests may write now: not closed by the DJ, and its day has not passed (Polish time). */
        public boolean open(LocalDate today) {
            return !closed && !date.isBefore(today);
        }

        /** Whether it took as many wishes as one event may. */
        public boolean full() {
            return wishCount >= MAX_WISHES;
        }
    }

    /**
     * One song of an event: as the first guest wrote it, how many wrote it, and whether it was written as "Artist - Title" — one
     * without the dash may be a line of the lyrics or a title alone, so the DJ's page lists it apart, to check.
     */
    public record Wish(String songName, int wishes, boolean named) {

        /** The song's key in the DJ's library: what "✓ Masz" / "✗ Nie masz" is looked up by. */
        public LibraryKey libraryKey() {
            return LibraryKey.of(songName);
        }
    }

    /** What became of "Wznów zbieranie". */
    public enum Reopened { REOPENED, NOT_FOUND, TOO_MANY }

    /** What became of a guest's wish. */
    public enum Added { ADDED, CLOSED, FULL, EMPTY }

    private static final RowMapper<Event> EVENT = (row, n) -> new Event(row.getLong("id"), row.getString("party_code"),
            row.getString("token"), row.getString("name"), row.getDate("event_date").toLocalDate(), row.getBoolean("closed"),
            row.getInt("wish_count"), row.getTimestamp("created_at").toInstant());

    private static final String EVENT_COLUMNS = "id, party_code, token, name, event_date, closed, wish_count, created_at";

    private final JdbcTemplate jdbc;

    /** Today in Poland: an event's day ends at midnight there, whatever the server's clock. */
    public static LocalDate today() {
        return LocalDate.now(Times.DISPLAY_ZONE);
    }

    /**
     * The party's events for its DJ's page: the ones whose date has not passed first, the nearest first, then the past ones still
     * kept (a week after their date), the latest first.
     */
    public List<Event> eventsOf(String partyCode) {
        return jdbc.query("SELECT " + EVENT_COLUMNS + " FROM wish_event WHERE party_code = ?"
                + " ORDER BY (event_date < ?), CASE WHEN event_date < ? THEN NULL ELSE event_date END, event_date DESC, id LIMIT ?",
                EVENT, partyCode, Date.valueOf(today()), Date.valueOf(today()), MAX_EVENTS);
    }

    /** The party's event of that id; another party's is none. */
    public Optional<Event> event(String partyCode, long id) {
        return jdbc.query("SELECT " + EVENT_COLUMNS + " FROM wish_event WHERE party_code = ? AND id = ?", EVENT, partyCode, id)
                .stream().findFirst();
    }

    /** The event whose guests' link this is. */
    public Optional<Event> byToken(String token) {
        if (token == null || token.isEmpty() || token.length() > 32) {
            return Optional.empty();
        }
        return jdbc.query("SELECT " + EVENT_COLUMNS + " FROM wish_event WHERE token = ?", EVENT, token).stream().findFirst();
    }

    /** How many of the party's events are open now (their day not passed, not closed). */
    public int openEvents(String partyCode) {
        Integer open = jdbc.queryForObject("SELECT count(*) FROM wish_event WHERE party_code = ? AND NOT closed AND event_date >= ?",
                Integer.class, partyCode, Date.valueOf(today()));
        return open == null ? 0 : open;
    }

    /**
     * A new event with its guests' link — none when the party has {@value #MAX_OPEN_EVENTS} open already, or keeps
     * {@value #MAX_EVENTS} events. Counted under the party row's lock: two "Utwórz" at once make one too many no more.
     *
     * @param name a name {@link #tidyName} kept
     * @param date today or later, within {@value #MAX_DAYS_AHEAD} days
     */
    @Transactional
    public Optional<Event> create(String partyCode, String name, LocalDate date) {
        lockParty(partyCode);
        if (openEvents(partyCode) >= MAX_OPEN_EVENTS || events(partyCode) >= MAX_EVENTS) {
            return Optional.empty();
        }
        Long id = jdbc.queryForObject("INSERT INTO wish_event (party_code, token, name, event_date) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, partyCode, CodeGenerator.generateSecret(), name, Date.valueOf(date));
        log.info("Party [{}]: wishes event {} made for {}", partyCode, id, date);
        return event(partyCode, id);
    }

    /** "Zakończ zbieranie": the guests' link says the collecting has ended. */
    public boolean close(String partyCode, long id) {
        return jdbc.update("UPDATE wish_event SET closed = true WHERE party_code = ? AND id = ?", partyCode, id) == 1;
    }

    /**
     * "Wznów zbieranie": the guests' link takes wishes again — unless that makes more than {@value #MAX_OPEN_EVENTS} open, counted
     * under the party row's lock as {@link #create} counts them (else closing and reopening would pass the limit). An event whose day
     * has passed is reopened without counting: it takes nothing anyway.
     */
    @Transactional
    public Reopened reopen(String partyCode, long id) {
        lockParty(partyCode);
        Optional<Event> event = event(partyCode, id);
        if (event.isEmpty()) {
            return Reopened.NOT_FOUND;
        }
        if (event.get().closed() && !event.get().date().isBefore(today()) && openEvents(partyCode) >= MAX_OPEN_EVENTS) {
            return Reopened.TOO_MANY;
        }
        jdbc.update("UPDATE wish_event SET closed = false WHERE party_code = ? AND id = ?", partyCode, id);
        return Reopened.REOPENED;
    }

    /** The party's row locked for this transaction: what counts its events and then adds to them is one at a time. */
    private void lockParty(String partyCode) {
        jdbc.queryForObject("SELECT party_code FROM party_settings WHERE party_code = ? FOR UPDATE", String.class, partyCode);
    }

    /** How many events the party keeps, open, closed or past. */
    public int events(String partyCode) {
        Integer events = jdbc.queryForObject("SELECT count(*) FROM wish_event WHERE party_code = ?", Integer.class, partyCode);
        return events == null ? 0 : events;
    }

    /** "Nowy link": a new secret — the old link (on a photo of an invitation online, say) opens nothing now. The wishes stay. */
    public boolean newLink(String partyCode, long id) {
        return jdbc.update("UPDATE wish_event SET token = ? WHERE party_code = ? AND id = ?", CodeGenerator.generateSecret(), partyCode,
                id) == 1;
    }

    /** The event and its wishes, at once. */
    public boolean delete(String partyCode, long id) {
        return jdbc.update("DELETE FROM wish_event WHERE party_code = ? AND id = ?", partyCode, id) == 1;
    }

    /**
     * A guest's wish at the event of {@code token}: the event's count raised — only while it is open and below
     * {@value #MAX_WISHES}, in the one statement, so guests writing at once never pass it — and the song's row made or counted.
     */
    @Transactional
    public Added add(String token, String typed) {
        String song = tidySong(typed);
        if (song.isEmpty()) {
            return Added.EMPTY;
        }
        List<Long> taken = jdbc.queryForList("UPDATE wish_event SET wish_count = wish_count + 1 WHERE token = ? AND NOT closed"
                + " AND event_date >= ? AND wish_count < ? RETURNING id", Long.class, token, Date.valueOf(today()), MAX_WISHES);
        if (taken.isEmpty()) {
            return byToken(token).filter(event -> event.open(today())).isPresent() ? Added.FULL : Added.CLOSED;
        }
        jdbc.update("INSERT INTO guest_wish (event_id, wish_key, song_name) VALUES (?, ?, ?) ON CONFLICT (event_id, wish_key)"
                + " DO UPDATE SET wishes = guest_wish.wishes + 1, last_at = now()", taken.get(0), keyOf(song), song);
        return Added.ADDED;
    }

    /** The event's songs, the most wished first (the earliest first among equal ones), at most {@value #LIST_MAX}. */
    public List<Wish> wishes(long eventId) {
        return wishes(eventId, LIST_MAX);
    }

    /** {@link #wishes} up to {@code limit} — the CSV takes every one ({@value #MAX_WISHES} at most). */
    public List<Wish> wishes(long eventId, int limit) {
        return jdbc.query("SELECT song_name, wishes, wish_key FROM guest_wish WHERE event_id = ? ORDER BY wishes DESC, first_at, wish_key"
                + " LIMIT ?", (row, n) -> new Wish(row.getString(1), row.getInt(2), !row.getString(3).startsWith(UNNAMED)),
                eventId, limit);
    }

    /** How many songs the event's wishes are. */
    public int songs(long eventId) {
        Integer songs = jdbc.queryForObject("SELECT count(*) FROM guest_wish WHERE event_id = ?", Integer.class, eventId);
        return songs == null ? 0 : songs;
    }

    /**
     * Deletes the events {@value #KEPT_DAYS_AFTER} days after their date, and any made more than {@value #MAX_DAYS_AHEAD} days ago
     * (a date moved on cannot keep them for ever), their wishes with them — what the privacy policy promises. Daily at 04:50 Polish
     * time, after the requests' purge.
     */
    @Scheduled(cron = "0 50 4 * * *", zone = Times.SCHEDULE_ZONE)
    public void purge() {
        int deleted = purgeBefore(today().minusDays(KEPT_DAYS_AFTER), Instant.now().minus(MAX_DAYS_AHEAD, ChronoUnit.DAYS));
        // a line also for none: the log shows the purge ran
        log.info("Wishes purge: {} event(s) deleted with their wishes", deleted);
    }

    /** {@link #purge}: the events dated before {@code dateBefore} or made before {@code madeBefore}. */
    int purgeBefore(LocalDate dateBefore, Instant madeBefore) {
        return jdbc.update("DELETE FROM wish_event WHERE event_date < ? OR created_at < ?", Date.valueOf(dateBefore),
                java.sql.Timestamp.from(madeBefore));
    }

    /**
     * The event's wishes as a CSV file for the DJ (Excel opens it: ";" between the columns, a byte order mark), every song — the
     * page lists {@value #LIST_MAX}: the song, how many wrote it, and the library's mark when the party has one.
     */
    public static String toCsv(List<Wish> wishes, java.util.Map<LibraryKey, com.scan2play.model.LibraryMark> marks,
                               org.springframework.context.MessageSource messages, java.util.Locale locale) {
        StringBuilder csv = new StringBuilder().append(EveningSummaryService.BYTE_ORDER_MARK)
                .append(messages.getMessage("wishes.csv.header", null, locale))
                .append(marks.isEmpty() ? "" : ";" + messages.getMessage("summary.csv.library", null, locale)).append("\r\n");
        for (Wish wish : wishes) {
            csv.append(EveningSummaryService.cell(wish.songName())).append(';').append(wish.wishes());
            if (!marks.isEmpty()) {
                com.scan2play.model.LibraryMark mark = marks.get(wish.libraryKey());
                csv.append(';').append(mark == null ? "" : EveningSummaryService.cell(
                        messages.getMessage("summary.csv.library." + mark.name(), null, locale)));
            }
            csv.append("\r\n");
        }
        return csv.toString();
    }

    // ---------------------------------------------------------------- the texts as they are kept

    /** A wish's key apart from the songs named "Artist - Title": its words only. */
    static final String UNNAMED = "?";

    /**
     * The song's key: "Artist - Title" by {@link LibraryKey} (the version and "feat." left out, the artists sorted — the same song
     * typed two ways is one row); a line without the dash by its words alone, apart ({@value #UNNAMED} before it).
     */
    static String keyOf(String song) {
        if (song.contains(" - ")) {
            LibraryKey key = LibraryKey.of(song);
            if (!key.isEmpty()) {
                return key.title() + "|" + key.artists();
            }
        }
        String words = SongNames.comparable(song);
        return UNNAMED + (words.length() > LibraryKey.MAX ? words.substring(0, LibraryKey.MAX) : words);
    }

    /** The song as kept: one line, every dash "-" ({@link SongNames#tidy}), at most {@value #SONG_MAX}; "" for nothing to keep. */
    public static String tidySong(String typed) {
        String song = SongNames.tidy(typed);
        if (song.length() > SONG_MAX) {
            song = song.substring(0, SONG_MAX).strip();
        }
        return SongNames.comparable(song).isEmpty() ? "" : song;
    }

    /** An event's name as kept: one line, at most {@value #NAME_MAX}; null for none. */
    public static String tidyName(String typed) {
        if (typed == null) {
            return null;
        }
        String name = typed.replaceAll("\\p{Cc}+", " ").replaceAll("\\s+", " ").strip();
        if (name.length() > NAME_MAX) {
            name = name.substring(0, NAME_MAX).strip();
        }
        return name.isEmpty() ? null : name;
    }
}
