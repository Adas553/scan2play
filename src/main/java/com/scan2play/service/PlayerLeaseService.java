package com.scan2play.service;

import com.scan2play.model.PlayerCommand;
import com.scan2play.model.PlayerLeaseMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Decides which dashboard window plays the music of a party, and carries the DJ's commands to it.
 * <p>
 * Every open YouTube dashboard has its own player, and each one asks the server what to play next while
 * Auto-Pilot is on — a second window (the DJ peeking from a phone) would take tracks off the queue that the
 * first one never plays. So a party has one <b>lease</b>: the window that holds it plays, the others only show
 * the queue. A window reports in every few seconds (renewing the lease); a lease that has not been renewed for
 * {@link #LEASE_TTL} is free, e.g. because the tab was closed or the computer went to sleep.
 * <p>
 * The DJ can give the window that plays a command (skip to the next track) from any window — the phone as a
 * remote control of the computer. The command waits here until the window that plays collects it with its next
 * report, so it arrives within one report interval. It never outlives the lease it was given for: it is dropped
 * whenever the lease changes hands or is given up (a command meant for one window must not fire in another, or
 * long after the DJ pressed the button). Only one command waits per party: pressing "next" twice within one
 * interval skips once.
 * <p>
 * The state is in memory, like the rest of the app's caches (single instance): a restart frees every lease, and
 * the window that plays takes it back with its next report.
 */
@Service
@Slf4j
public class PlayerLeaseService {

    /** How long a lease lives without being renewed — a few missed reports of the 3 s interval the dashboard uses. */
    static final Duration LEASE_TTL = Duration.ofSeconds(10);

    /** Above this many parties the expired leases and the commands left behind are swept, so the maps cannot grow without bound. */
    private static final int SWEEP_THRESHOLD = 1000;

    /** A window's id is a random string it makes up itself (a UUID in practice); nothing else is accepted. */
    private static final Pattern DEVICE_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    /** @param playing what the holder last said about its player: making sound ({@code true}) or paused ({@code false}); null = never said */
    private record Lease(String deviceId, Instant lastSeen, Boolean playing) {
    }

    /**
     * The outcome of a report: whether the reporting window plays, whether nobody holds the lease, the command that
     * was waiting for the window that plays (only ever set when {@code holder} is true), and — for every window —
     * whether the holder's player is making sound or is paused ({@code null} when nobody holds the lease or the holder
     * has not said), so that a window that does not play can show "pause" or "resume".
     */
    public record Status(boolean holder, boolean free, PlayerCommand command, Boolean playing) {
    }

    private final ConcurrentHashMap<String, Lease> leases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PlayerCommand> commands = new ConcurrentHashMap<>();
    private final Clock clock;

    public PlayerLeaseService() {
        this(Clock.systemUTC());
    }

    PlayerLeaseService(Clock clock) {
        this.clock = clock;
    }

    public static boolean isValidDeviceId(String deviceId) {
        return deviceId != null && DEVICE_ID.matcher(deviceId).matches();
    }

    /** A report that says nothing about the player: what a window that only watches sends. */
    public Status report(String partyCode, String deviceId, PlayerLeaseMode mode) {
        return report(partyCode, deviceId, mode, null);
    }

    /**
     * A window reports in. See {@link PlayerLeaseMode} for what each mode may do; a window that already holds the
     * lease always keeps it. The window that holds it also collects the command waiting for it, if any, and tells
     * whether its player makes sound; the report of a window that does not hold the lease never changes that.
     *
     * @param playing whether the reporting window's player is making sound ({@code null}: it says nothing)
     */
    public Status report(String partyCode, String deviceId, PlayerLeaseMode mode, Boolean playing) {
        Instant now = clock.instant();
        Lease result = leases.compute(partyCode, (code, current) -> {
            boolean live = isLive(current, now);
            if (live && current.deviceId().equals(deviceId)) {
                return new Lease(deviceId, now, playing != null ? playing : current.playing());
            }
            if (mode == PlayerLeaseMode.TAKE_OVER || (!live && mode == PlayerLeaseMode.CLAIM)) {
                log.info("Party {}: player lease {} by window {}", partyCode, live ? "taken over" : "claimed", deviceId);
                commands.remove(code); // meant for the window that played before
                return new Lease(deviceId, now, playing);
            }
            return live ? current : null;
        });
        if (leases.size() > SWEEP_THRESHOLD) {
            leases.values().removeIf(lease -> !isLive(lease, now));
            commands.keySet().removeIf(code -> !isLive(leases.get(code), now));
        }
        boolean holder = result != null && result.deviceId().equals(deviceId);
        return new Status(holder, result == null, holder ? commands.remove(partyCode) : null,
                result != null ? result.playing() : null);
    }

    /**
     * The DJ gives the window that plays a command, from any window.
     *
     * @return false when no window holds a live lease — nobody plays, so the command would wait for nobody
     */
    public boolean sendCommand(String partyCode, PlayerCommand command) {
        Instant now = clock.instant();
        if (!isLive(leases.get(partyCode), now)) {
            return false;
        }
        commands.put(partyCode, command);
        return true;
    }

    /**
     * May this window ask for the next track? Yes unless another window holds a live lease; a window that sent no
     * id counts as another one. With nobody holding the lease anyone may ask.
     */
    public boolean mayPlay(String partyCode, String deviceId) {
        Lease lease = leases.get(partyCode);
        return !isLive(lease, clock.instant()) || lease.deviceId().equals(deviceId);
    }

    /**
     * A window asks for the next track (review item 2.4): it may when it holds the lease — and when nobody does it takes the lease
     * in the same step, so two windows that ask before their first report (right after a restart of the server) cannot both get a
     * track. Unlike {@link #report} it leaves the waiting command alone: that is for the holder's next report. A window without a
     * valid id cannot hold a lease; it may ask only while nobody plays, as before.
     */
    public boolean claimToPlay(String partyCode, String deviceId) {
        if (!isValidDeviceId(deviceId)) {
            return mayPlay(partyCode, deviceId);
        }
        Instant now = clock.instant();
        Lease result = leases.compute(partyCode, (code, current) -> {
            if (isLive(current, now)) {
                return current.deviceId().equals(deviceId) ? new Lease(deviceId, now, current.playing()) : current;
            }
            log.info("Party {}: player lease claimed by window {} asking for a track", partyCode, deviceId);
            commands.remove(code);
            return new Lease(deviceId, now, null);
        });
        return result.deviceId().equals(deviceId);
    }

    /** The window is going away: give the lease up at once instead of after the timeout. Ignored unless it holds it. */
    public void release(String partyCode, String deviceId) {
        leases.computeIfPresent(partyCode, (code, current) -> {
            if (!current.deviceId().equals(deviceId)) {
                return current;
            }
            commands.remove(code);
            return null;
        });
    }

    private boolean isLive(Lease lease, Instant now) {
        return lease != null && Duration.between(lease.lastSeen(), now).compareTo(LEASE_TTL) <= 0;
    }
}
