package com.scan2play.service;

import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.LibraryMark;
import com.scan2play.repository.SongRequestRepository;
import com.scan2play.util.LibraryKey;
import com.scan2play.util.SongNames;
import com.scan2play.util.Times;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.scan2play.service.DjService.DECISION_PLAYED;
import static com.scan2play.service.DjService.DECISION_REJECTED;

/**
 * "Podsumowanie wieczoru": what the guests asked for on one evening of the party — the counts, how long the played requests waited,
 * the most wanted songs, the wanted ones that did not play, the most wanted artists, the requests half-hour by half-hour and what
 * played, in order — for the DJ to keep, print or show the client, and as CSV. An evening runs from
 * {@link #EVENING_STARTS} to the same time the next day, Polish time, so a party that goes past midnight is one evening. Read from
 * {@code song_requests}, so it lasts as long as they do (30 days) and is gone with "Wyczyść historię".
 */
@Service
@RequiredArgsConstructor
public class EveningSummaryService {

    /** When an evening starts (and the one before ends), Polish time; the same 6 hours are in {@code findEvenings}. */
    public static final LocalTime EVENING_STARTS = LocalTime.of(6, 0);
    /** The evenings offered: the retention keeps 30 days. */
    static final int MAX_EVENINGS = 31;
    /** The most requests one evening counts — the party's own limit is 300 a day, but a DJ can switch it off. */
    static final int MAX_REQUESTS = 2000;
    static final int TOP_SONGS = 10;
    static final int TOP_ARTISTS = 10;
    /** The requests are counted by this many minutes of the evening. */
    static final int SLOT_MINUTES = 30;
    private static final int MINUTES_A_DAY = 24 * 60;

    /** First in the CSV file: Excel then reads it as UTF-8. */
    private static final char BYTE_ORDER_MARK = 0xFEFF;
    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(Times.DISPLAY_ZONE);

    /** One evening the party had requests, and how many. */
    public record Evening(LocalDate day, long requests) {
    }

    /** The requests of one half-hour of the evening, from {@code minuteOfDay} (Polish time, 0 = midnight). */
    public record TimeSlot(int minuteOfDay, long requests) {

        /** "22:30". */
        public String label() {
            return "%02d:%02d".formatted(minuteOfDay / 60, minuteOfDay % 60);
        }
    }

    /**
     * A song the AI let through that the DJ's library (V42) has no song of ({@code MISSING}) or only one of its title by another artist
     * ({@code SIMILAR}): its name as first asked for, the votes and the requests of the evening — what to get before the next one.
     */
    public record LibraryGap(String name, int votes, int requests, LibraryMark mark) {
    }

    /** An artist of the songs the AI let through: how many of their songs the guests asked for, and those songs' votes. */
    public record ArtistCount(String name, int songs, int votes) {
    }

    /**
     * One evening. The counts split the requests by how they ended: played, still waiting, skipped or cleared by the DJ, rejected
     * by the AI. {@code votes} adds up the votes of the songs the AI let through (each one's first request is a vote too).
     * {@code missed}: the wanted songs that did not play (waiting, skipped, cleared). The waits are the played requests' minutes
     * from the request to "played" — null when no request was marked as played. {@code tips}: the tips the DJ counted (V28, "💸").
     * {@code addedByDj}: how many of the requests the DJ added in the panel (V44) — counted in {@code requests}, but their first vote
     * is the DJ's, not a guest's, so {@code votes} leaves it out. {@code viaKiosk}: how many guests sent at the venue's kiosk (V56),
     * counted in {@code requests} too.
     */
    public record Summary(LocalDate evening, int requests, int played, int waiting, int skipped, int cleared, int rejectedByAi,
                          int votes, int tips, Instant first, Instant last, List<SongRequestEntity> top, List<SongRequestEntity> missed,
                          List<ArtistCount> artists, List<SongRequestEntity> setlist, List<TimeSlot> slots,
                          Long averageWaitMinutes, Long longestWaitMinutes, boolean truncated, int addedByDj,
                          int viaKiosk) {

        /** How the request ended, "WAITING" — the page's text of it is {@code summary.csv.status.<this>}. */
        public String status(SongRequestEntity request) {
            return statusOf(request).name();
        }

        /** The most requests of one half-hour. */
        public long busiestSlotRequests() {
            return slots.stream().mapToLong(TimeSlot::requests).max().orElse(0);
        }

        /** The half-hour with the most requests (the earliest of equal ones), "22:30–23:00"; null for none. */
        public String busiestSlot() {
            long most = busiestSlotRequests();
            return slots.stream().filter(slot -> most > 0 && slot.requests() == most).findFirst()
                    .map(slot -> slot.label() + "–" + new TimeSlot((slot.minuteOfDay() + SLOT_MINUTES) % MINUTES_A_DAY, 0).label())
                    .orElse(null);
        }
    }

    private final SongRequestRepository songRequestRepository;
    private final DjLibraryService libraryService;

    /** The party's evenings with requests, the latest first. */
    @Transactional(readOnly = true)
    public List<Evening> evenings(String partyCode) {
        return songRequestRepository.findEvenings(partyCode, MAX_EVENINGS).stream()
                .map(row -> new Evening(LocalDate.parse((String) row[0]), ((Number) row[1]).longValue()))
                .toList();
    }

    /**
     * The evening a moment belongs to: its day in Polish time, the day before when it is earlier than {@link #EVENING_STARTS} on the
     * clock — as {@code findEvenings} counts it in SQL, also the nights the clocks change (six hours of time moved it by the hour).
     */
    public static LocalDate eveningOf(Instant moment) {
        return moment.atZone(Times.DISPLAY_ZONE).toLocalDateTime().minusHours(EVENING_STARTS.getHour()).toLocalDate();
    }

    /** The party's requests of one evening, the first asked first, at most {@value #MAX_REQUESTS}. */
    @Transactional(readOnly = true)
    public List<SongRequestEntity> requests(String partyCode, LocalDate evening) {
        Instant from = evening.atTime(EVENING_STARTS).atZone(Times.DISPLAY_ZONE).toInstant();
        Instant to = evening.plusDays(1).atTime(EVENING_STARTS).atZone(Times.DISPLAY_ZONE).toInstant();
        return songRequestRepository.findRequestedBetween(partyCode, from, to, PageRequest.of(0, MAX_REQUESTS));
    }

    @Transactional(readOnly = true)
    public Summary summarize(String partyCode, LocalDate evening) {
        return summarize(evening, requests(partyCode, evening));
    }

    public static Summary summarize(LocalDate evening, List<SongRequestEntity> requests) {
        int played = 0;
        int waiting = 0;
        int skipped = 0;
        int cleared = 0;
        int rejectedByAi = 0;
        int votes = 0;
        int tips = 0;
        int addedByDj = 0;
        int viaKiosk = 0;
        List<SongRequestEntity> letThrough = new ArrayList<>();
        List<SongRequestEntity> missed = new ArrayList<>();
        List<SongRequestEntity> setlist = new ArrayList<>();
        List<Long> waits = new ArrayList<>();
        long[] perSlot = new long[MINUTES_A_DAY / SLOT_MINUTES];
        Instant first = null;
        Instant last = null;
        for (SongRequestEntity request : requests) {
            Status status = statusOf(request);
            switch (status) {
                case PLAYED -> {
                    played++;
                    setlist.add(request);
                    if (request.getRequestedAt() != null && request.getPlayedAt() != null
                            && !request.getPlayedAt().isBefore(request.getRequestedAt())) {
                        waits.add(Duration.between(request.getRequestedAt(), request.getPlayedAt()).toMinutes());
                    }
                }
                case WAITING -> waiting++;
                case SKIPPED -> skipped++;
                case CLEARED -> cleared++;
                case REJECTED_BY_AI -> rejectedByAi++;
            }
            if (status != Status.REJECTED_BY_AI) {
                letThrough.add(request);
                // a song the DJ added (V44): its first vote is the DJ's; the guests' 👍 on it count
                votes += request.isAddedByDj() ? request.getVotes() - 1 : request.getVotes();
                tips += request.getTips();
                if (status != Status.PLAYED) {
                    missed.add(request);
                }
            }
            if (request.isAddedByDj()) {
                addedByDj++;
            }
            if (request.isViaKiosk()) {
                viaKiosk++;
            }
            Instant at = request.getRequestedAt();
            if (at != null) {
                perSlot[slotOf(at)]++;
                first = first == null || at.isBefore(first) ? at : first;
            }
            Instant end = request.getPlayedAt() != null ? request.getPlayedAt() : at;
            if (end != null) {
                last = last == null || end.isAfter(last) ? end : last;
            }
        }
        Comparator<SongRequestEntity> mostWanted = Comparator.comparingInt(SongRequestEntity::getVotes).reversed()
                .thenComparing(SongRequestEntity::getRequestedAt, Comparator.nullsLast(Comparator.naturalOrder()));
        letThrough.sort(mostWanted);
        missed.sort(mostWanted);
        setlist.sort(Comparator.comparing(EveningSummaryService::playedOrRequested, Comparator.nullsLast(Comparator.naturalOrder())));
        return new Summary(evening, requests.size(), played, waiting, skipped, cleared, rejectedByAi, votes, tips, first, last,
                firstOf(letThrough, TOP_SONGS), firstOf(missed, TOP_SONGS), artists(letThrough), List.copyOf(setlist),
                slots(perSlot, first),
                waits.isEmpty() ? null : Math.round(waits.stream().mapToLong(Long::longValue).average().orElse(0)),
                waits.isEmpty() ? null : waits.stream().mapToLong(Long::longValue).max().orElse(0),
                requests.size() >= MAX_REQUESTS, addedByDj, viaKiosk);
    }

    /**
     * The evening's songs not in the party's library ({@link #libraryGaps(List, Map)}), by the library as it is now; empty when the
     * party has none. One lookup for all the evening's titles.
     */
    @Transactional(readOnly = true)
    public Optional<List<LibraryGap>> libraryGaps(String partyCode, List<SongRequestEntity> requests) {
        if (libraryService.info(partyCode).isEmpty()) {
            return Optional.empty();
        }
        Set<LibraryKey> keys = new HashSet<>();
        requests.stream().filter(request -> statusOf(request) != Status.REJECTED_BY_AI)
                .forEach(request -> keys.add(LibraryKey.of(request.getSongName())));
        return Optional.of(libraryGaps(requests, libraryService.marks(partyCode, keys)));
    }

    /** Each request's mark by the party's library as it is now, by its id — the CSV's column; none when the party has no library. */
    @Transactional(readOnly = true)
    public Map<Long, LibraryMark> libraryMarks(String partyCode, List<SongRequestEntity> requests) {
        if (libraryService.info(partyCode).isEmpty()) {
            return Map.of();
        }
        Map<Long, LibraryKey> keys = new LinkedHashMap<>();
        requests.forEach(request -> keys.put(request.getId(), LibraryKey.of(request.getSongName())));
        Map<LibraryKey, LibraryMark> marks = libraryService.marks(partyCode, new HashSet<>(keys.values()));
        Map<Long, LibraryMark> byId = new LinkedHashMap<>();
        keys.forEach((id, key) -> byId.put(id, marks.getOrDefault(key, LibraryMark.MISSING)));
        return byId;
    }

    /**
     * The songs the AI let through that {@code marks} says the library has not ("✗") or has only by another artist ("?"), each once
     * — the requests of one song, by its {@link LibraryKey}, are one line with their votes added — the most wanted first.
     */
    static List<LibraryGap> libraryGaps(List<SongRequestEntity> requests, Map<LibraryKey, LibraryMark> marks) {
        Map<LibraryKey, LibraryGap> gaps = new LinkedHashMap<>();
        for (SongRequestEntity request : requests) {
            LibraryKey key = LibraryKey.of(request.getSongName());
            LibraryMark mark = marks.get(key);
            if (statusOf(request) == Status.REJECTED_BY_AI || key.isEmpty() || mark == null || mark == LibraryMark.HAVE) {
                continue;
            }
            gaps.merge(key, new LibraryGap(request.getSongName(), request.getVotes(), 1, mark),
                    (had, one) -> new LibraryGap(had.name(), had.votes() + one.votes(), had.requests() + 1, had.mark()));
        }
        return gaps.values().stream()
                .sorted(Comparator.comparingInt(LibraryGap::votes).reversed().thenComparing(Comparator.comparingInt(LibraryGap::requests).reversed()))
                .toList();
    }

    private static <T> List<T> firstOf(List<T> list, int count) {
        return List.copyOf(list.subList(0, Math.min(count, list.size())));
    }

    /** The half-hour of the day a moment falls in, Polish time: 0 = 00:00–00:30, 45 = 22:30–23:00. */
    private static int slotOf(Instant moment) {
        LocalTime time = moment.atZone(Times.DISPLAY_ZONE).toLocalTime();
        return (time.getHour() * 60 + time.getMinute()) / SLOT_MINUTES;
    }

    /**
     * The most wanted artists: the songs the AI let through grouped by the artist of "Artist - Title" (the AI's name; case and
     * accents ignored, the first spelling kept), the most votes first, then the most songs. A song without " - " has no artist.
     */
    static List<ArtistCount> artists(List<SongRequestEntity> letThrough) {
        Map<String, ArtistCount> byArtist = new LinkedHashMap<>();
        for (SongRequestEntity song : letThrough) {
            // tidied as new names are: an older one may still have "–" or "—" between the artist and the title
            String name = SongNames.tidy(song.getSongName());
            int dash = name.indexOf(" - ");
            if (dash <= 0) {
                continue;
            }
            String artist = name.substring(0, dash).strip();
            byArtist.merge(SongNames.comparable(artist), new ArtistCount(artist, 1, song.getVotes()),
                    (had, one) -> new ArtistCount(had.name(), had.songs() + 1, had.votes() + one.votes()));
        }
        return byArtist.values().stream()
                .sorted(Comparator.comparingInt(ArtistCount::votes).reversed()
                        .thenComparing(Comparator.comparingInt(ArtistCount::songs).reversed())
                        .thenComparing(ArtistCount::name, String.CASE_INSENSITIVE_ORDER))
                .limit(TOP_ARTISTS)
                .toList();
    }

    /**
     * Every half-hour from the first request's to the last request's, in the evening's order (18:00, 18:30 … 23:30, 00:00 …), the
     * quiet ones too — the bars of the page show when the guests asked.
     */
    private static List<TimeSlot> slots(long[] perSlot, Instant first) {
        if (first == null) {
            return List.of();
        }
        int count = perSlot.length;
        int start = EVENING_STARTS.getHour() * 60 / SLOT_MINUTES;
        int firstPosition = Math.floorMod(slotOf(first) - start, count);
        int lastPosition = firstPosition;
        for (int position = 0; position < count; position++) {
            if (perSlot[(position + start) % count] > 0) {
                lastPosition = Math.max(lastPosition, position);
            }
        }
        List<TimeSlot> slots = new ArrayList<>();
        for (int position = firstPosition; position <= lastPosition; position++) {
            int slot = (position + start) % count;
            slots.add(new TimeSlot(slot * SLOT_MINUTES, perSlot[slot]));
        }
        return slots;
    }

    private static Instant playedOrRequested(SongRequestEntity request) {
        return request.getPlayedAt() != null ? request.getPlayedAt() : request.getRequestedAt();
    }

    /** How a request ended — the summary's counts and the CSV's status column. */
    public enum Status {
        PLAYED, WAITING, SKIPPED, CLEARED, REJECTED_BY_AI
    }

    public static Status statusOf(SongRequestEntity request) {
        if (DECISION_PLAYED.equals(request.getDecision())) {
            return Status.PLAYED;
        }
        if (DECISION_REJECTED.equals(request.getDecision())) {
            if (request.getSkippedAt() != null) {
                return Status.SKIPPED;
            }
            return request.getClearedAt() != null ? Status.CLEARED : Status.REJECTED_BY_AI;
        }
        return Status.WAITING;
    }

    /**
     * The evening's requests as CSV for a spreadsheet: a byte order mark and ";" between the columns (what Excel set to Polish
     * reads without asking), the texts of the DJ's language. Every value a guest or the AI wrote is quoted, and one that a
     * spreadsheet would take for a formula ({@code = + - @}, a tab) starts with an apostrophe — a guest's "=HYPERLINK(…)" stays
     * text.
     */
    public static String toCsv(List<SongRequestEntity> requests, MessageSource messages, Locale locale) {
        return toCsv(requests, Map.of(), messages, locale);
    }

    /** The same with a column of how each request stands against the DJ's library (V42) — none when {@code marks} is empty. */
    public static String toCsv(List<SongRequestEntity> requests, Map<Long, LibraryMark> marks, MessageSource messages, Locale locale) {
        StringBuilder csv = new StringBuilder().append(BYTE_ORDER_MARK)
                .append(messages.getMessage("summary.csv.header", null, locale))
                .append(marks.isEmpty() ? "" : ";" + messages.getMessage("summary.csv.library", null, locale)).append("\r\n");
        for (SongRequestEntity request : requests) {
            String status = messages.getMessage("summary.csv.status." + statusOf(request).name(), null, locale);
            csv.append(String.join(";",
                    request.getRequestNumber() == null ? "" : String.valueOf(request.getRequestNumber()),
                    cell(request.getRequestedAt() == null ? "" : CSV_TIME.format(request.getRequestedAt())),
                    cell(request.getPlayedAt() == null ? "" : CSV_TIME.format(request.getPlayedAt())),
                    cell(request.getSongName()),
                    cell(request.getGuestText()),
                    String.valueOf(request.getVotes()),
                    String.valueOf(request.getTips()),
                    cell(status),
                    cell(request.getDjComment()),
                    request.isAddedByDj() ? cell(messages.getMessage("summary.csv.added_by_dj.yes", null, locale)) : "",
                    request.isViaKiosk() ? cell(messages.getMessage("summary.csv.kiosk.yes", null, locale)) : ""));
            if (!marks.isEmpty()) {
                LibraryMark mark = marks.getOrDefault(request.getId(), LibraryMark.MISSING);
                csv.append(';').append(cell(messages.getMessage("summary.csv.library." + mark.name(), null, locale)));
            }
            csv.append("\r\n");
        }
        return csv.toString();
    }

    static String cell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String text = value.replace("\r", " ").replace("\n", " ");
        if ("=+-@\t".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }
}
