package com.scan2play.service;

import com.scan2play.entity.EveningSummaryEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.LibraryMark;
import com.scan2play.model.RefusalReason;
import com.scan2play.model.RejectReason;
import com.scan2play.repository.EveningSummaryRepository;
import com.scan2play.repository.SongRequestRepository;
import com.scan2play.util.CodeGenerator;
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
import java.time.OffsetDateTime;
import java.time.YearMonth;
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

import static com.scan2play.service.DjService.DECISION_ACCEPTED;
import static com.scan2play.service.DjService.DECISION_PLAYED;
import static com.scan2play.service.DjService.DECISION_REJECTED;

/**
 * "Podsumowanie wieczoru": what the guests asked for on one evening of the party — the counts, how long the played requests waited,
 * the most wanted songs, the wanted ones that did not play, the artists of several songs, the new requests half-hour by half-hour and
 * what played, in order — for the DJ to keep, and in the hosts' version to give the client; and as CSV. The lists count a song once:
 * one asked for again after it played (out of the AI's duplicate window), after the queue was cleared or two hours after a skip is a
 * row of its own, and its rows are one {@link Song} ({@link LibraryKey}, as the library's marks). An evening runs from
 * {@link #EVENING_STARTS} to the same time the next day, Polish time, so a party that goes past midnight is one evening. Read from
 * {@code song_requests} and from what was kept of the requests already deleted ({@code evening_song}, V61: the 30 days' purge and
 * "Wyczyść historię" copy it in the statement that deletes them), so an evening lasts 13 months, without the guests' words. The DJ
 * may name an evening and give its hosts a read-only link ({@code evening_summary}).
 */
@Service
@RequiredArgsConstructor
public class EveningSummaryService {

    /** When an evening starts (and the one before ends), Polish time; the same 6 hours are in {@code findEvenings}. */
    public static final LocalTime EVENING_STARTS = LocalTime.of(6, 0);
    /** The evenings offered: a year of weekly parties (the summary keeps 13 months, SongRequestRetentionService). */
    static final int MAX_EVENINGS = 52;
    /** How long a hosts' link works after the DJ made it. */
    public static final int SHARE_DAYS = 30;
    /** The most requests one evening counts — the party's own limit is 300 a day, but a DJ can switch it off. */
    static final int MAX_REQUESTS = 2000;
    static final int TOP_SONGS = 10;
    static final int TOP_ARTISTS = 10;
    static final int TOP_REJECTED = 10;
    /** The evenings "Ostatnie wieczory" compares, the shown one among them. */
    public static final int COMPARED_EVENINGS = 8;
    /** The requests are counted by this many minutes of the evening. */
    static final int SLOT_MINUTES = 30;
    /** The tallest bar of the half-hours' chart, in the units of its viewBox (summary.html). */
    public static final int BAR_HEIGHT = 60;
    private static final int MINUTES_A_DAY = 24 * 60;

    /** First in the CSV file: Excel then reads it as UTF-8. */
    private static final char BYTE_ORDER_MARK = 0xFEFF;
    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(Times.DISPLAY_ZONE);

    /**
     * One evening the party had requests, counted in SQL as the summary counts it: the guests' votes ({@link Summary#votes()}), the
     * played requests, the ones the AI rejected, the first and the last request — the list to pick from and "Ostatnie wieczory".
     */
    public record Evening(LocalDate day, long votes, long played, long rejectedByAi, Instant first, Instant last) {

        public Evening(LocalDate day, long votes) {
            this(day, votes, 0, 0, null, null);
        }
    }

    /** The evenings of one month, for the page's list (its {@code <optgroup>}): {@code first} names the month. */
    public record Month(LocalDate first, List<Evening> evenings) {
    }

    /**
     * The requests of one half-hour of the evening, from {@code minuteOfDay} (Polish time on the clock, 0 = midnight; the night the
     * clocks go back has two half-hours of each label from 02:00 to 03:00).
     */
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

    /**
     * A song the AI rejected, its requests of the evening together (by {@link LibraryKey}): the name as first asked, how many times
     * it was asked for and why it was rejected the last time it was ({@code null}: a request of before V62).
     */
    public record RejectedSong(String name, int requests, RejectReason reason, Instant firstAsked) {
    }

    /** An artist of the songs the AI let through: how many of their songs the guests asked for, and those songs' votes. */
    public record ArtistCount(String name, int songs, int votes) {
    }

    /**
     * One song the AI let through, its requests of the evening together: the name as first asked for, the votes and the DJ's tips of
     * all of them, how often it played and how it ended at best ({@code PLAYED} when any of them played, then waiting, skipped,
     * cleared) — so a song that played and was asked for again is one line, never "wanted, not heard".
     */
    public record Song(String name, int votes, int tips, int timesPlayed, Status status, Instant firstAsked) {

        public boolean played() {
            return status == Status.PLAYED;
        }
    }

    /**
     * One evening. {@code requests}: the rows — a song the AI let through once however often it was asked for (the rest are its
     * votes), each request the AI rejected apart. The counts split them by how they ended: played, still waiting, skipped or cleared
     * by the DJ, rejected by the AI. {@code songs}: the songs the AI let through, each once ({@link Song}). {@code votes} adds up the
     * votes of the songs the AI let through (each one's first request is a vote too). {@code top}: the most wanted songs;
     * {@code missed}: the wanted songs that never played (waiting, skipped, cleared). The waits are the played requests' minutes from
     * the request to "played" — null when no request was marked as played. {@code tips}: the tips the DJ counted (V28, "💸");
     * {@code dedications}: the guests' dedications (V38), a count — their words are the DJ's alone, never in the summary.
     * {@code addedByDj}: how many of the requests the DJ added in the panel (V44) — counted in {@code requests}, but their first vote
     * is the DJ's, not a guest's, so {@code votes} leaves it out. {@code viaKiosk}: how many guests sent at the venue's kiosk (V56),
     * counted in {@code requests} too. {@code firstWanted}: the earliest request the AI let through; {@code artists}: the artists of
     * two songs or more. {@code rejectedAsPlayed} / {@code rejectedOffVibe}: the AI's rejections by why (V62; the ones of before are
     * neither); {@code rejected}: the songs it rejected that have a name (a kept one has none), the most asked first.
     */
    public record Summary(LocalDate evening, int requests, int songs, int played, int waiting, int skipped, int cleared,
                          int rejectedByAi, int votes, int tips, int dedications, Instant first, Instant last, List<Song> top,
                          List<Song> missed, List<ArtistCount> artists, List<SongRequestEntity> setlist, List<TimeSlot> slots,
                          Long averageWaitMinutes, Long longestWaitMinutes, boolean truncated, int addedByDj, int viaKiosk,
                          SongRequestEntity firstWanted, int rejectedAsPlayed, int rejectedOffVibe, List<RejectedSong> rejected) {

        /** The most requests of one half-hour. */
        public long busiestSlotRequests() {
            return slots.stream().mapToLong(TimeSlot::requests).max().orElse(0);
        }

        /** The half-hour with the most requests (the earliest of equal ones), "22:30–23:00"; null for none. */
        public String busiestSlot() {
            return busiest().map(slot -> slot.label() + "–" + new TimeSlot((slot.minuteOfDay() + SLOT_MINUTES) % MINUTES_A_DAY, 0).label())
                    .orElse(null);
        }

        /** When the busiest half-hour starts, "22:30"; null for none. */
        public String busiestSlotStart() {
            return busiest().map(TimeSlot::label).orElse(null);
        }

        /** Whether {@code slot} is the busiest half-hour (the earliest of equal ones) — its bar in the accent. */
        public boolean isBusiest(TimeSlot slot) {
            return busiest().filter(busiest -> busiest == slot).isPresent();
        }

        private Optional<TimeSlot> busiest() {
            long most = busiestSlotRequests();
            return slots.stream().filter(slot -> most > 0 && slot.requests() == most).findFirst();
        }

        /** The height of a half-hour's bar, 0 … {@value EveningSummaryService#BAR_HEIGHT} (the chart's units), by the busiest one; a quiet one 0. */
        public int barHeight(TimeSlot slot) {
            long most = busiestSlotRequests();
            return most == 0 || slot.requests() == 0 ? 0 : (int) Math.max(1, Math.round(slot.requests() * (double) BAR_HEIGHT / most));
        }

        /** The chart's viewBox: a bar of 8 and a gap of 2 units a half-hour, {@value EveningSummaryService#BAR_HEIGHT} high. */
        public String chartViewBox() {
            return "0 0 " + slots.size() * 10 + " " + BAR_HEIGHT;
        }

        /** Where a half-hour's bar starts from the chart's top: its foot on the baseline. */
        public int barY(TimeSlot slot) {
            return BAR_HEIGHT - barHeight(slot);
        }

        /** The last song played; null when none was. */
        public SongRequestEntity lastPlayed() {
            return setlist.isEmpty() ? null : setlist.getLast();
        }
    }

    private final SongRequestRepository songRequestRepository;
    private final DjLibraryService libraryService;
    private final EveningSummaryRepository eveningSummaryRepository;

    /** The party's evenings with requests, the latest first. */
    @Transactional(readOnly = true)
    public List<Evening> evenings(String partyCode) {
        return songRequestRepository.findEvenings(partyCode, MAX_EVENINGS).stream()
                .map(row -> new Evening(LocalDate.parse((String) row[0]), ((Number) row[1]).longValue(),
                        row.length > 2 ? ((Number) row[2]).longValue() : 0, row.length > 3 ? ((Number) row[3]).longValue() : 0,
                        row.length > 4 ? instant(row[4]) : null, row.length > 5 ? instant(row[5]) : null))
                .toList();
    }

    /**
     * The evening a moment belongs to: its day in Polish time, the day before when it is earlier than {@link #EVENING_STARTS} on the
     * clock — as {@code findEvenings} counts it in SQL, also the nights the clocks change (six hours of time moved it by the hour).
     */
    public static LocalDate eveningOf(Instant moment) {
        return moment.atZone(Times.DISPLAY_ZONE).toLocalDateTime().minusHours(EVENING_STARTS.getHour()).toLocalDate();
    }

    /** The evenings by month, the latest first, as {@link #evenings} gives them. */
    public static List<Month> byMonth(List<Evening> evenings) {
        Map<YearMonth, List<Evening>> months = new LinkedHashMap<>();
        evenings.forEach(evening -> months.computeIfAbsent(YearMonth.from(evening.day()), month -> new ArrayList<>()).add(evening));
        return months.entrySet().stream().map(month -> new Month(month.getKey().atDay(1), List.copyOf(month.getValue()))).toList();
    }

    /**
     * The party's requests of one evening, the first asked first, at most {@value #MAX_REQUESTS}: the ones still there and the ones
     * kept of the requests gone ({@link #fromKept}) — a request is in one of them; were it in both, the one still there counts.
     */
    @Transactional(readOnly = true)
    public List<SongRequestEntity> requests(String partyCode, LocalDate evening) {
        Instant from = evening.atTime(EVENING_STARTS).atZone(Times.DISPLAY_ZONE).toInstant();
        Instant to = evening.plusDays(1).atTime(EVENING_STARTS).atZone(Times.DISPLAY_ZONE).toInstant();
        List<SongRequestEntity> live = songRequestRepository.findRequestedBetween(partyCode, from, to, PageRequest.of(0, MAX_REQUESTS));
        List<Object[]> kept = eveningSummaryRepository.findSongsBetween(partyCode, from, to, MAX_REQUESTS);
        if (kept.isEmpty()) {
            return live;
        }
        Map<Long, SongRequestEntity> byId = new LinkedHashMap<>();
        kept.forEach(row -> {
            SongRequestEntity request = fromKept(partyCode, row);
            byId.put(request.getId(), request);
        });
        live.forEach(request -> byId.put(request.getId(), request));
        return byId.values().stream()
                .sorted(Comparator.comparing(SongRequestEntity::getRequestedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(SongRequestEntity::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(MAX_REQUESTS)
                .toList();
    }

    /**
     * A song kept of a deleted request ({@code EveningSummaryRepository.findSongsBetween}'s row) as a request the summary counts:
     * never saved — its status back as the decision and the moments {@link #statusOf} reads, no guest's words, no number.
     */
    static SongRequestEntity fromKept(String partyCode, Object[] row) {
        Status status = Status.valueOf((String) row[4]);
        Instant requestedAt = instant(row[2]);
        return SongRequestEntity.builder()
                .id(((Number) row[0]).longValue())
                .partyCode(partyCode)
                .songName((String) row[1])
                .requestedAt(requestedAt)
                .playedAt(instant(row[3]))
                .decision(switch (status) {
                    case PLAYED -> DECISION_PLAYED;
                    case WAITING -> DECISION_ACCEPTED;
                    case SKIPPED, CLEARED, REJECTED_BY_AI -> DECISION_REJECTED;
                })
                .skippedAt(status == Status.SKIPPED ? requestedAt : null)
                .clearedAt(status == Status.CLEARED ? requestedAt : null)
                .votes(((Number) row[5]).intValue())
                .tips(((Number) row[6]).intValue())
                .dedications(((Number) row[7]).intValue())
                .addedByDj(Boolean.TRUE.equals(row[8]))
                .viaKiosk(Boolean.TRUE.equals(row[9]))
                .rejectReason(row.length > 10 && row[10] != null ? RejectReason.valueOf((String) row[10]) : null)
                .build();
    }

    /** A {@code timestamptz} of a native query's row, as the driver gives it. */
    private static Instant instant(Object value) {
        return switch (value) {
            case null -> null;
            case Instant moment -> moment;
            case OffsetDateTime moment -> moment.toInstant();
            case java.sql.Timestamp moment -> moment.toInstant();
            default -> throw new IllegalArgumentException("Not a moment: " + value.getClass());
        };
    }

    /** The evening's requests refused before the AI judged a song (V63), by reason; empty when there were none. */
    @Transactional(readOnly = true)
    public Map<RefusalReason, Integer> refusals(String partyCode, LocalDate evening) {
        Map<RefusalReason, Integer> counts = new java.util.EnumMap<>(RefusalReason.class);
        eveningSummaryRepository.findRefusals(partyCode, evening)
                .forEach(row -> counts.put(RefusalReason.valueOf((String) row[0]), ((Number) row[1]).intValue()));
        return counts;
    }

    // ---- the evening's title and the hosts' link (V61) ----

    /** How the DJ named the evening and whether it has a hosts' link; empty when neither was ever given. */
    @Transactional(readOnly = true)
    public Optional<EveningSummaryEntity> evening(String partyCode, LocalDate evening) {
        return eveningSummaryRepository.findByPartyCodeAndEvening(partyCode, evening);
    }

    /** The DJ's title of the evening: one line, at most {@value EveningSummaryEntity#TITLE_MAX} characters; empty clears it. */
    @Transactional
    public void saveTitle(String partyCode, LocalDate evening, String title) {
        eveningSummaryRepository.saveTitle(partyCode, evening, tidyTitle(title), Instant.now());
    }

    static String tidyTitle(String title) {
        if (title == null) {
            return null;
        }
        String line = title.replaceAll("\\s+", " ").strip();
        if (line.isEmpty()) {
            return null;
        }
        return line.codePointCount(0, line.length()) <= EveningSummaryEntity.TITLE_MAX ? line
                : line.substring(0, line.offsetByCodePoints(0, EveningSummaryEntity.TITLE_MAX)).strip();
    }

    /** A new hosts' link of the evening, working {@value #SHARE_DAYS} days — an old one stops; its secret. */
    @Transactional
    public String share(String partyCode, LocalDate evening) {
        String token = CodeGenerator.generateSecret();
        Instant now = Instant.now();
        eveningSummaryRepository.saveShare(partyCode, evening, token, now.plus(Duration.ofDays(SHARE_DAYS)), now);
        return token;
    }

    /** The evening's hosts' link switched off. */
    @Transactional
    public void stopSharing(String partyCode, LocalDate evening) {
        eveningSummaryRepository.saveShare(partyCode, evening, null, null, Instant.now());
    }

    /** The evening a hosts' link opens, while it works; empty for an unknown, old or switched-off one. */
    @Transactional(readOnly = true)
    public Optional<EveningSummaryEntity> shared(String token) {
        if (token == null || token.isEmpty() || token.length() > 32) {
            return Optional.empty();
        }
        return eveningSummaryRepository.findByShareToken(token).filter(evening -> evening.shareWorks(Instant.now()));
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
        int dedications = 0;
        int rejectedAsPlayed = 0;
        int rejectedOffVibe = 0;
        List<SongRequestEntity> rejectedRows = new ArrayList<>();
        int addedByDj = 0;
        int viaKiosk = 0;
        List<SongRequestEntity> letThrough = new ArrayList<>();
        List<SongRequestEntity> setlist = new ArrayList<>();
        List<Long> waits = new ArrayList<>();
        Instant eveningStart = startOf(evening);
        long[] perSlot = new long[slotCount(evening)];
        Instant first = null;
        Instant last = null;
        SongRequestEntity firstWanted = null;
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
                case REJECTED_BY_AI -> {
                    rejectedByAi++;
                    rejectedRows.add(request);
                    if (request.getRejectReason() == RejectReason.DUPLICATE) {
                        rejectedAsPlayed++;
                    } else if (request.getRejectReason() == RejectReason.OFF_VIBE) {
                        rejectedOffVibe++;
                    }
                }
            }
            if (status != Status.REJECTED_BY_AI) {
                letThrough.add(request);
                // a song the DJ added (V44): its first vote is the DJ's; the guests' 👍 on it count
                votes += request.isAddedByDj() ? request.getVotes() - 1 : request.getVotes();
                tips += request.getTips();
                dedications += request.getDedications();
                if (firstWanted == null || isEarlier(request, firstWanted)) {
                    firstWanted = request;
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
                perSlot[slotOf(at, eveningStart, perSlot.length)]++;
                first = first == null || at.isBefore(first) ? at : first;
            }
            Instant end = request.getPlayedAt() != null ? request.getPlayedAt() : at;
            if (end != null) {
                last = last == null || end.isAfter(last) ? end : last;
            }
        }
        List<Song> songs = songs(letThrough);
        setlist.sort(Comparator.comparing(EveningSummaryService::playedOrRequested, Comparator.nullsLast(Comparator.naturalOrder())));
        return new Summary(evening, requests.size(), songs.size(), played, waiting, skipped, cleared, rejectedByAi, votes, tips,
                dedications, first, last, firstOf(songs, TOP_SONGS),
                firstOf(songs.stream().filter(song -> !song.played()).toList(), TOP_SONGS), artists(songs), List.copyOf(setlist),
                slots(perSlot, eveningStart),
                waits.isEmpty() ? null : Math.round(waits.stream().mapToLong(Long::longValue).average().orElse(0)),
                waits.isEmpty() ? null : waits.stream().mapToLong(Long::longValue).max().orElse(0),
                requests.size() >= MAX_REQUESTS, addedByDj, viaKiosk, firstWanted, rejectedAsPlayed, rejectedOffVibe,
                rejectedSongs(rejectedRows));
    }

    /**
     * The songs the AI rejected, each once (by {@link LibraryKey}), with how many times they were asked for and the last reason; the
     * ones without a name (kept of a deleted request: the name was the guest's) left out — the most asked first, at most
     * {@value #TOP_REJECTED}.
     */
    static List<RejectedSong> rejectedSongs(List<SongRequestEntity> rejected) {
        Map<String, RejectedSong> byKey = new LinkedHashMap<>();
        rejected.stream()
                .filter(request -> request.getSongName() != null && !request.getSongName().isBlank())
                .sorted(Comparator.comparing(SongRequestEntity::getRequestedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(request -> byKey.merge(songKey(request.getSongName()),
                        new RejectedSong(request.getSongName(), 1, request.getRejectReason(), request.getRequestedAt()),
                        (had, again) -> new RejectedSong(had.name(), had.requests() + 1,
                                again.reason() != null ? again.reason() : had.reason(), had.firstAsked())));
        return byKey.values().stream()
                .sorted(Comparator.comparingInt(RejectedSong::requests).reversed()
                        .thenComparing(RejectedSong::firstAsked, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(TOP_REJECTED)
                .toList();
    }

    /** The evenings "Ostatnie wieczory" compares: up to {@value #COMPARED_EVENINGS} from the list, the shown one among them. */
    public static List<Evening> compared(List<Evening> evenings, LocalDate shown) {
        int at = 0;
        for (int i = 0; i < evenings.size(); i++) {
            if (evenings.get(i).day().equals(shown)) {
                at = i;
            }
        }
        int from = Math.max(0, Math.min(at - COMPARED_EVENINGS / 2, evenings.size() - COMPARED_EVENINGS));
        return List.copyOf(evenings.subList(from, Math.min(evenings.size(), from + COMPARED_EVENINGS)));
    }

    /**
     * The songs the AI let through, each once — the rows of one {@link LibraryKey} together (a name without one: by its own
     * {@link SongNames#comparable}) — the most votes first, then the earlier asked.
     */
    static List<Song> songs(List<SongRequestEntity> letThrough) {
        Map<String, Song> byKey = new LinkedHashMap<>();
        letThrough.stream()
                .sorted(Comparator.comparing(SongRequestEntity::getRequestedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(request -> {
                    Status status = statusOf(request);
                    Song one = new Song(request.getSongName(), request.getVotes(), request.getTips(), status == Status.PLAYED ? 1 : 0,
                            status, request.getRequestedAt());
                    byKey.merge(songKey(request.getSongName()), one, (had, again) -> new Song(had.name(), had.votes() + again.votes(),
                            had.tips() + again.tips(), had.timesPlayed() + again.timesPlayed(), better(had.status(), again.status()),
                            had.firstAsked()));
                });
        return byKey.values().stream()
                .sorted(Comparator.comparingInt(Song::votes).reversed()
                        .thenComparing(Song::firstAsked, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private static String songKey(String songName) {
        LibraryKey key = LibraryKey.of(songName);
        return key.isEmpty() ? "=" + SongNames.comparable(songName == null ? "" : songName) : key.title() + "|" + key.artists();
    }

    /** How a song ended at best over its requests: played, then waiting (it may still play), then skipped, then cleared. */
    private static Status better(Status one, Status other) {
        return rank(one) <= rank(other) ? one : other;
    }

    private static int rank(Status status) {
        return switch (status) {
            case PLAYED -> 0;
            case WAITING -> 1;
            case SKIPPED -> 2;
            case CLEARED -> 3;
            case REJECTED_BY_AI -> 4;
        };
    }

    private static boolean isEarlier(SongRequestEntity one, SongRequestEntity other) {
        return one.getRequestedAt() != null && (other.getRequestedAt() == null || one.getRequestedAt().isBefore(other.getRequestedAt()));
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

    /** The artist of "Artist - Title" (tidied as new names are: an older one may still have "–" or "—"); null without one. */
    private static String artistOf(String songName) {
        String name = SongNames.tidy(songName == null ? "" : songName);
        int dash = name.indexOf(" - ");
        return dash <= 0 ? null : name.substring(0, dash).strip();
    }

    /** The title of "Artist - Title"; the whole name without an artist. */
    private static String titleOf(String songName) {
        String name = SongNames.tidy(songName == null ? "" : songName);
        int dash = name.indexOf(" - ");
        return dash <= 0 ? name : name.substring(dash + 3).strip();
    }

    /** When an evening starts: {@link #EVENING_STARTS} on its day, Polish time. */
    private static Instant startOf(LocalDate evening) {
        return evening.atTime(EVENING_STARTS).atZone(Times.DISPLAY_ZONE).toInstant();
    }

    /** The evening's half-hours: 48, or 46 / 50 the nights the clocks change (the evening runs from 6:00 to 6:00 on the clock). */
    private static int slotCount(LocalDate evening) {
        return (int) (Duration.between(startOf(evening), startOf(evening.plusDays(1))).toMinutes() / SLOT_MINUTES);
    }

    /**
     * The half-hour of the evening a moment falls in, counted in time from its start — so the hour the clocks go back is two
     * half-hours of its own, not one counted twice; a moment outside the evening (a list not of it) at its edge.
     */
    private static int slotOf(Instant moment, Instant eveningStart, int count) {
        long slot = Math.floorDiv(Duration.between(eveningStart, moment).toMinutes(), SLOT_MINUTES);
        return (int) Math.clamp(slot, 0, count - 1);
    }

    /**
     * The artists of two songs or more: the songs the AI let through grouped by the artist of "Artist - Title" (the AI's name; case
     * and accents ignored, the first spelling kept), the most votes first, then the most songs. A song without " - " has no artist;
     * an artist of one song says no more than the song itself in the top list.
     */
    static List<ArtistCount> artists(List<Song> songs) {
        Map<String, ArtistCount> byArtist = new LinkedHashMap<>();
        for (Song song : songs) {
            String artist = artistOf(song.name());
            if (artist == null) {
                continue;
            }
            byArtist.merge(SongNames.comparable(artist), new ArtistCount(artist, 1, song.votes()),
                    (had, one) -> new ArtistCount(had.name(), had.songs() + 1, had.votes() + one.votes()));
        }
        return byArtist.values().stream()
                .filter(artist -> artist.songs() >= 2)
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
    private static List<TimeSlot> slots(long[] perSlot, Instant eveningStart) {
        int firstSlot = -1;
        int lastSlot = -1;
        for (int slot = 0; slot < perSlot.length; slot++) {
            if (perSlot[slot] > 0) {
                firstSlot = firstSlot < 0 ? slot : firstSlot;
                lastSlot = slot;
            }
        }
        List<TimeSlot> slots = new ArrayList<>();
        for (int slot = firstSlot; firstSlot >= 0 && slot <= lastSlot; slot++) {
            LocalTime start = eveningStart.plus(Duration.ofMinutes((long) slot * SLOT_MINUTES)).atZone(Times.DISPLAY_ZONE).toLocalTime();
            slots.add(new TimeSlot(start.getHour() * 60 + start.getMinute(), perSlot[slot]));
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
     * reads without asking), the texts of the DJ's language. A row a request; after the columns of before (a sheet made from them
     * keeps working) how long a played one waited, its artist and title apart, the count of its dedications (never their words) and
     * why the AI rejected it (V62). Every value a guest or the AI wrote is quoted, and one that a
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
                    request.isViaKiosk() ? cell(messages.getMessage("summary.csv.kiosk.yes", null, locale)) : "",
                    waitedMinutes(request),
                    cell(artistOf(request.getSongName())),
                    cell(titleOf(request.getSongName())),
                    String.valueOf(request.getDedications()),
                    request.getRejectReason() == null || statusOf(request) != Status.REJECTED_BY_AI ? ""
                            : cell(messages.getMessage("summary.csv.reject." + request.getRejectReason().name(), null, locale))));
            if (!marks.isEmpty()) {
                LibraryMark mark = marks.getOrDefault(request.getId(), LibraryMark.MISSING);
                csv.append(';').append(cell(messages.getMessage("summary.csv.library." + mark.name(), null, locale)));
            }
            csv.append("\r\n");
        }
        return csv.toString();
    }

    /** A played request's minutes from the request to "played"; empty for any other. */
    private static String waitedMinutes(SongRequestEntity request) {
        if (statusOf(request) != Status.PLAYED || request.getRequestedAt() == null || request.getPlayedAt() == null
                || request.getPlayedAt().isBefore(request.getRequestedAt())) {
            return "";
        }
        return String.valueOf(Duration.between(request.getRequestedAt(), request.getPlayedAt()).toMinutes());
    }

    /** The songs of "Nie masz w bibliotece", one a line — what "Kopiuj listę" copies, for a shop's search or a note. */
    public static String copyList(List<LibraryGap> gaps) {
        return String.join("\n", gaps.stream().map(LibraryGap::name).toList());
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
