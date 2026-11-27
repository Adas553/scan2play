package com.scan2play.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.PlanFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * "Zagrane samo" watched (docs/ideas.md item 21, V81): whether each paired computer's program sees songs — the panel's line per
 * computer, which is also the DJ's test before the party ("puść dowolny utwór") — and a push to the organiser's devices when the
 * program stopped seeing them during the party, after the minutes the DJ chose ("Ostrzeż po": 10, 20 or 30, or never).
 * <p>
 * "During the party" is: the party open, a song seen in the last {@value #SET_HOURS} hours (the set has started — no warning before
 * the DJ played anything) and a guest's request in the last {@value #GUESTS_MINUTES} minutes (the guests are still there: the end of
 * the set at a party left open is not a failure). One push a silence: the next only after a new song and another silence.
 */
@Slf4j
@Service
public class DeckWatchService {

    /** What "Ostrzeż po" offers; 0 = never. The default is the column's (V81). */
    public static final Set<Integer> SILENCE_CHOICES = Set.of(0, 10, 20, 30);
    static final int DEFAULT_SILENCE_MINUTES = 20;
    static final int SET_HOURS = 12;
    static final int GUESTS_MINUTES = 60;
    /** The program calls once a minute even with nothing to send: three missed calls are a program gone. */
    static final Duration OFFLINE_AFTER = Duration.ofMinutes(3);
    /** The most parties one look warns: the rest at the next minute. */
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
     * A computer as the panel's card shows it: {@code warn} when its state is worth the DJ's eye now (SILENT or OFFLINE during the
     * party); otherwise the same state is said in grey ("ostatnio 23:10").
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
    private final PlanService plans;
    private final PushNotificationService push;
    private final Clock clock;
    /** A party → the last song its warning was about: one push a silence. */
    private final Cache<String, Instant> warned = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(SET_HOURS + 1))
            .maximumSize(10_000).build();

    @Autowired
    public DeckWatchService(JdbcTemplate jdbc, DjDeviceService devices, PartySettingsQueryService parties, PlanService plans,
                            PushNotificationService push) {
        this(jdbc, devices, parties, plans, push, Clock.systemUTC());
    }

    DeckWatchService(JdbcTemplate jdbc, DjDeviceService devices, PartySettingsQueryService parties, PlanService plans,
                     PushNotificationService push, Clock clock) {
        this.jdbc = jdbc;
        this.devices = devices;
        this.parties = parties;
        this.plans = plans;
        this.push = push;
        this.clock = clock;
    }

    /** The party's computers with their lines, and the minutes chosen. */
    public Watch watch(PartySettingsEntity party) {
        Instant now = clock.instant();
        int minutes = silenceMinutes(party.getPartyCode());
        List<DjDeviceService.Device> paired = devices.devices(party.getPartyCode());
        boolean live = party.isActive() && paired.stream().anyMatch(device -> setStarted(device.lastPlayAt(), now))
                && guestsRecently(party.getPartyCode(), now);
        return new Watch(paired.stream().map(device -> {
            State state = state(device, minutes, now);
            return new Computer(device.id(), device.name(), device.createdAt(), device.lastSeenAt(), device.lastPlayAt(),
                    device.program(), state, live && (state == State.SILENT || state == State.OFFLINE));
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

    private static boolean setStarted(Instant lastPlayAt, Instant now) {
        return lastPlayAt != null && lastPlayAt.isAfter(now.minus(Duration.ofHours(SET_HOURS)));
    }

    private boolean guestsRecently(String partyCode, Instant now) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM song_requests WHERE party_code = ?"
                + " AND requested_at > ?)", Boolean.class, partyCode, Timestamp.from(now.minus(Duration.ofMinutes(GUESTS_MINUTES)))));
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
        jdbc.update("UPDATE party_settings SET deck_silence_minutes = ? WHERE party_code = ?", minutes, partyCode);
        // a new choice starts a new watch: a warning about this silence may come again at the new minute
        warned.invalidate(partyCode);
        return true;
    }

    /** A party whose program fell silent: its organiser, its newest song, its last call, the minutes chosen. */
    record Silent(String partyCode, String ownerId, Instant lastPlayAt, Instant lastSeenAt, int minutes) {
    }

    /**
     * The parties silent now (see the class), at most {@value #MAX_PER_LOOK}. One read: the few computers that saw a song in the last
     * {@value #SET_HOURS} hours (V81's partial index), their parties open and watched, newest song older than their minutes, a guest's
     * request within {@value #GUESTS_MINUTES} minutes (the queue's index by party).
     */
    List<Silent> silentNow(Instant now) {
        return jdbc.query("SELECT d.party_code, p.owner_id, MAX(d.last_play_at), MAX(COALESCE(d.last_seen_at, d.created_at)),"
                        + " p.deck_silence_minutes"
                        + " FROM dj_device d JOIN party_settings p ON p.party_code = d.party_code"
                        + " WHERE d.last_play_at > ? AND p.active AND p.deck_silence_minutes > 0"
                        + " GROUP BY d.party_code, p.owner_id, p.deck_silence_minutes"
                        + " HAVING MAX(d.last_play_at) < ?::timestamptz - p.deck_silence_minutes * interval '1 minute'"
                        + " AND EXISTS (SELECT 1 FROM song_requests r WHERE r.party_code = d.party_code AND r.requested_at > ?)"
                        + " ORDER BY d.party_code LIMIT " + MAX_PER_LOOK,
                (row, n) -> new Silent(row.getString(1), row.getString(2), row.getTimestamp(3).toInstant(),
                        row.getTimestamp(4).toInstant(), row.getInt(5)),
                Timestamp.from(now.minus(Duration.ofHours(SET_HOURS))), Timestamp.from(now),
                Timestamp.from(now.minus(Duration.ofMinutes(GUESTS_MINUTES))));
    }

    /** Every minute: the organisers of the parties silent now told once (with Pro — the program's own plan). */
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
            try {
                if (!plans.allows(parties.getSettings(party.partyCode()), PlanFeature.DECK)) {
                    continue;
                }
            } catch (RuntimeException e) {
                continue;   // the party gone meanwhile
            }
            boolean offline = party.lastSeenAt().isBefore(now.minus(OFFLINE_AFTER));
            push.notifyDeckSilent(party.ownerId(), party.partyCode(), party.minutes(), offline);
            log.info("Party {}: the DJ's program saw no song for {} min{} — the organiser warned", party.partyCode(), party.minutes(),
                    offline ? " (and has not called)" : "");
        }
    }
}
