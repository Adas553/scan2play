package com.scan2play.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.PartyStaffEntity;
import com.scan2play.model.PlanFeature;
import com.scan2play.model.StaffPermission;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * "Zagrane samo" watched (docs/ideas.md item 21, V81, V82): whether each paired computer's program sees songs — the panel's line per
 * computer, which is also the DJ's test before the party ("puść dowolny utwór") — and a push to the organiser's and the staff's
 * devices when the program stopped seeing them during the party, after the minutes the DJ chose ("Ostrzeż po": 10, 20 or 30, or
 * never).
 * <p>
 * "Silent during the party" is one rule for the card and the push, about the party's <em>lead</em> computer — the one whose program
 * sent the newest song in the last {@value #SET_HOURS} hours:
 * <ul>
 *     <li>the party open and the minutes chosen (not "never");</li>
 *     <li>a set had started: the lead's run of songs ({@code play_run_from}, V82) lasted at least {@code RUN_MIN} — one song played to
 *     test the program before the party is no set that stopped;</li>
 *     <li>its newest song older than the minutes chosen;</li>
 *     <li>a guest's request since that song (more than {@code AFTER_LAST_SONG} after it, in the last {@value #GUESTS_MINUTES} minutes):
 *     when the program stops seeing songs the music goes on and so do the guests; at the end of the set they stop — the requests of
 *     its last minutes are no sign of a fault.</li>
 * </ul>
 * The other computers (a spare laptop tested and left aside) are said in grey. One push a silence: the next only after a new song and
 * another silence.
 */
@Slf4j
@Service
public class DeckWatchService {

    /** What "Ostrzeż po" offers; 0 = never. The default is the column's (V81). */
    public static final Set<Integer> SILENCE_CHOICES = Set.of(0, 10, 20, 30);
    static final int DEFAULT_SILENCE_MINUTES = 20;
    static final int SET_HOURS = 12;
    static final int GUESTS_MINUTES = 60;
    /** A run of songs this long is a set (three songs or so); a test of one song, or two, is not. */
    static final Duration RUN_MIN = Duration.ofMinutes(10);
    /** A request this soon after the last song still belongs to the set's end: the guest asked while it played. */
    static final Duration AFTER_LAST_SONG = Duration.ofMinutes(2);
    /** The program calls once a minute even with nothing to send: three missed calls are a program gone. */
    static final Duration OFFLINE_AFTER = Duration.ofMinutes(3);
    /** The most parties one look warns: the newest silences first, those not warned yet. */
    private static final int MAX_PER_LOOK = 100;

    /** A computer's line in the panel. */
    public enum State {
        /** Paired, no song seen yet tonight: the DJ's test — play any song for a minute. */
        WAITING,
        /** Songs seen, the last one within the minutes chosen. */
        OK,
        /** The last song is older than the minutes chosen. */
        SILENT,
        /** The program has not called for {@code OFFLINE_AFTER}: closed, the laptop asleep, no internet. */
        OFFLINE
    }

    /**
     * A computer as the panel's card shows it: {@code warn} when its state is worth the DJ's eye now (the lead computer SILENT or
     * OFFLINE during the party, see the class); otherwise the same state is said in grey ("ostatnio 23:10").
     */
    public record Computer(long id, String name, Instant createdAt, Instant lastSeenAt, Instant lastPlayAt, String program,
                           State state, boolean warn) {
    }

    /** The card's content: the computers and the minutes chosen. */
    public record Watch(List<Computer> computers, int silenceMinutes) {
    }

    private final JdbcTemplate jdbc;
    private final DjDeviceService devices;
    private final PartySettingsQueryService parties;
    private final PartyStaffService staff;
    private final PlanService plans;
    private final PushNotificationService push;
    private final Clock clock;
    /** A party → the last song its warning was about: one push a silence (a restart may repeat one). */
    private final Cache<String, Instant> warned = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(SET_HOURS + 1))
            .maximumSize(10_000).build();

    @Autowired
    public DeckWatchService(JdbcTemplate jdbc, DjDeviceService devices, PartySettingsQueryService parties, PartyStaffService staff,
                            PlanService plans, PushNotificationService push) {
        this(jdbc, devices, parties, staff, plans, push, Clock.systemUTC());
    }

    DeckWatchService(JdbcTemplate jdbc, DjDeviceService devices, PartySettingsQueryService parties, PartyStaffService staff,
                     PlanService plans, PushNotificationService push, Clock clock) {
        this.jdbc = jdbc;
        this.devices = devices;
        this.parties = parties;
        this.staff = staff;
        this.plans = plans;
        this.push = push;
        this.clock = clock;
    }

    /** The party's computers with their lines, and the minutes chosen. */
    public Watch watch(PartySettingsEntity party) {
        Instant now = clock.instant();
        int minutes = silenceMinutes(party.getPartyCode());
        List<DjDeviceService.Device> paired = devices.devices(party.getPartyCode());
        Optional<DjDeviceService.Device> lead = lead(paired, now);
        boolean live = minutes > 0 && party.isActive() && lead.filter(DeckWatchService::setPlayed).isPresent()
                && guestsSince(party.getPartyCode(), guestsAfter(lead.get().lastPlayAt(), now));
        return new Watch(paired.stream().map(device -> {
            State state = state(device, minutes, now);
            boolean warn = live && device.id() == lead.get().id() && (state == State.SILENT || state == State.OFFLINE);
            return new Computer(device.id(), device.name(), device.createdAt(), device.lastSeenAt(), device.lastPlayAt(),
                    device.program(), state, warn);
        }).toList(), minutes);
    }

    /** A computer's state now (see {@link State}); {@code minutes} 0: never SILENT. */
    static State state(DjDeviceService.Device device, int minutes, Instant now) {
        if (device.heardAt().isBefore(now.minus(OFFLINE_AFTER))) {
            return State.OFFLINE;
        }
        if (!setStarted(device.lastPlayAt(), now)) {
            return State.WAITING;
        }
        if (minutes > 0 && device.lastPlayAt().isBefore(now.minus(Duration.ofMinutes(minutes)))) {
            return State.SILENT;
        }
        return State.OK;
    }

    /** The computer that sent the party's newest song of the last {@value #SET_HOURS} hours: the one the set is played on. */
    static Optional<DjDeviceService.Device> lead(List<DjDeviceService.Device> paired, Instant now) {
        return paired.stream().filter(device -> setStarted(device.lastPlayAt(), now))
                .max(Comparator.comparing(DjDeviceService.Device::lastPlayAt).thenComparing(DjDeviceService.Device::id));
    }

    /** Whether the computer's newest song ends a run of songs long enough to be a set, not a test of the program. */
    static boolean setPlayed(DjDeviceService.Device device) {
        return device.playRunFrom() != null && device.lastPlayAt() != null
                && Duration.between(device.playRunFrom(), device.lastPlayAt()).compareTo(RUN_MIN) >= 0;
    }

    private static boolean setStarted(Instant lastPlayAt, Instant now) {
        return lastPlayAt != null && lastPlayAt.isAfter(now.minus(Duration.ofHours(SET_HOURS)));
    }

    /** Requests after this count as the guests still there (see the class). */
    private static Instant guestsAfter(Instant lastPlayAt, Instant now) {
        Instant afterSong = lastPlayAt.plus(AFTER_LAST_SONG);
        Instant recent = now.minus(Duration.ofMinutes(GUESTS_MINUTES));
        return afterSong.isAfter(recent) ? afterSong : recent;
    }

    private boolean guestsSince(String partyCode, Instant after) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM song_requests WHERE party_code = ?"
                + " AND requested_at > ?)", Boolean.class, partyCode, Timestamp.from(after)));
    }

    /** "Ostrzeż po": the minutes chosen for the party (0: never). */
    public int silenceMinutes(String partyCode) {
        List<Integer> found = jdbc.queryForList("SELECT deck_silence_minutes FROM party_settings WHERE party_code = ?", Integer.class,
                partyCode);
        return found.isEmpty() ? DEFAULT_SILENCE_MINUTES : found.getFirst();
    }

    /** "Ostrzeż po" changed; false for a value not offered. */
    public boolean setSilenceMinutes(String partyCode, int minutes) {
        if (!SILENCE_CHOICES.contains(minutes)) {
            return false;
        }
        int before = silenceMinutes(partyCode);
        jdbc.update("UPDATE party_settings SET deck_silence_minutes = ? WHERE party_code = ?", minutes, partyCode);
        // more minutes: a silence warned about may be warned again when it reaches them. Fewer, or "never" and back: the silence
        // warned about already was the DJ's news — not a second push about it at the next minute
        if (before > 0 && minutes > before) {
            warned.invalidate(partyCode);
        }
        return true;
    }

    /** A party whose program fell silent: its organiser, the lead computer's newest song and last call, the minutes chosen. */
    record Silent(String partyCode, String ownerId, Instant lastPlayAt, Instant lastSeenAt, int minutes) {
    }

    /**
     * The parties silent now (see the class), at most {@value #MAX_PER_LOOK}, the newest silences first. One read: the few computers
     * that saw a song in the last {@value #SET_HOURS} hours (V81's partial index), each party's lead one, the party open and watched,
     * and a guest's request since the lead's newest song (the queue's index by party).
     */
    List<Silent> silentNow(Instant now) {
        return jdbc.query("WITH lead AS (SELECT DISTINCT ON (party_code) party_code, last_play_at, play_run_from,"
                        + " COALESCE(last_seen_at, created_at) AS heard_at FROM dj_device WHERE last_play_at > ?"
                        + " ORDER BY party_code, last_play_at DESC, id DESC)"
                        + " SELECT l.party_code, p.owner_id, l.last_play_at, l.heard_at, p.deck_silence_minutes"
                        + " FROM lead l JOIN party_settings p ON p.party_code = l.party_code"
                        + " WHERE p.active AND p.deck_silence_minutes > 0"
                        + " AND l.last_play_at < ?::timestamptz - p.deck_silence_minutes * interval '1 minute'"
                        + " AND l.last_play_at - l.play_run_from >= interval '" + RUN_MIN.toMinutes() + " minutes'"
                        + " AND EXISTS (SELECT 1 FROM song_requests r WHERE r.party_code = l.party_code"
                        + " AND r.requested_at > GREATEST(?::timestamptz, l.last_play_at + interval '" + AFTER_LAST_SONG.toMinutes()
                        + " minutes'))"
                        + " ORDER BY l.last_play_at DESC LIMIT " + MAX_PER_LOOK,
                (row, n) -> new Silent(row.getString(1), row.getString(2), row.getTimestamp(3).toInstant(),
                        row.getTimestamp(4).toInstant(), row.getInt(5)),
                Timestamp.from(now.minus(Duration.ofHours(SET_HOURS))), Timestamp.from(now),
                Timestamp.from(now.minus(Duration.ofMinutes(GUESTS_MINUTES))));
    }

    /**
     * Every minute: the parties silent now told once (with Pro — the program's own plan) — the organiser and the staff who may work
     * the queue (they see the card, and get the notifications of new requests too).
     */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    public void look() {
        Instant now = clock.instant();
        List<Silent> silent;
        try {
            silent = silentNow(now);
        } catch (RuntimeException e) {
            log.warn("The deck watch's look failed: {}", e.toString());
            return;
        }
        for (Silent party : silent) {
            if (party.lastPlayAt().equals(warned.getIfPresent(party.partyCode()))) {
                continue;
            }
            warned.put(party.partyCode(), party.lastPlayAt());
            List<String> people;
            try {
                if (!plans.allows(parties.getSettings(party.partyCode()), PlanFeature.DECK)) {
                    continue;
                }
                people = recipients(party);
            } catch (RuntimeException e) {
                continue;   // the party gone meanwhile
            }
            boolean offline = party.lastSeenAt().isBefore(now.minus(OFFLINE_AFTER));
            // the silence as it is: more than the minutes chosen after a restart, a choice changed or guests who came back later
            long silentFor = Duration.between(party.lastPlayAt(), now).toMinutes();
            for (String person : people) {
                push.notifyDeckSilent(person, party.partyCode(), silentFor, offline);
            }
            log.info("Party {}: the DJ's program saw no song for {} min{} — {} warned", party.partyCode(), silentFor,
                    offline ? " (and has not called)" : "", people.size());
        }
    }

    private List<String> recipients(Silent party) {
        List<String> people = new ArrayList<>();
        people.add(party.ownerId());
        staff.staffOf(party.partyCode()).stream().filter(member -> member.getPermissions().contains(StaffPermission.QUEUE))
                .map(PartyStaffEntity::getMemberId).filter(id -> !people.contains(id)).forEach(people::add);
        return people;
    }
}
