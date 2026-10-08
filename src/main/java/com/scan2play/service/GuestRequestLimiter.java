package com.scan2play.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Server-side limits on guest song requests that do not depend on the session cookie.
 * <p>
 * The per-guest limit set by the DJ lives in the session ({@link GuestSessionService}), and a request without the cookie gets
 * a new session and so no limit at all. Every request costs a Gemini call. Two limits here, both counted <b>before</b> the
 * evaluation and atomically:
 * <ul>
 *     <li><b>client IP + party</b> — {@code guest.limit.per-ip-party} requests per {@code guest.limit.per-ip-window-minutes}.
 *         Loose on purpose: the guests on a venue's Wi-Fi share one public address.</li>
 *     <li><b>party</b> — {@code guest.limit.per-party-daily} requests per 24 hours, whatever the address, so one party (or a
 *         script that fakes addresses) cannot spend more than its own share.</li>
 * </ul>
 * Each limit is a fixed window that starts with the first request after the previous one ended. A limit of 0 or less is off.
 * <p>
 * The guests' 👍 on the songs of the list ({@link GuestVoteService}) have a limit of their own, per client IP + party
 * ({@code guest.limit.votes-per-ip-party} per the same window): they cost no AI call, so a vote never uses up a request.
 * The counts are in memory, like the sessions (a single instance, Section 13 of {@code PROJECT_CONTEXT.md}).
 * <p>
 * The client address is {@link HttpServletRequest#getRemoteAddr()} — with {@code server.forward-headers-strategy=FRAMEWORK}
 * the first entry of {@code X-Forwarded-For}, which a client can fake — unless {@code guest.client-ip-header} names a header
 * set by a proxy that overwrites it (e.g. {@code CF-Connecting-IP} behind Cloudflare).
 */
@Service
@Slf4j
public class GuestRequestLimiter {

    /** Which of the two limits refused a request, and how long until it lets the next one through. */
    public record Refusal(Scope scope, long waitSeconds) {
    }

    public enum Scope { CLIENT, PARTY }

    private static final Duration PARTY_WINDOW = Duration.ofHours(24);

    private record Window(Instant start, int count) {
    }

    private final int perClientLimit;
    private final int perClientVoteLimit;
    private final Duration clientWindow;
    private final int perPartyLimit;
    private final String clientIpHeader;
    private final Clock clock;

    private final Cache<String, Window> clientWindows;
    private final Cache<String, Window> voteWindows;
    private final Cache<String, Window> partyWindows;

    @Autowired
    public GuestRequestLimiter(@Value("${guest.limit.per-ip-party:30}") int perClientLimit,
                               @Value("${guest.limit.per-ip-window-minutes:10}") int clientWindowMinutes,
                               @Value("${guest.limit.per-party-daily:300}") int perPartyLimit,
                               @Value("${guest.client-ip-header:}") String clientIpHeader,
                               @Value("${guest.limit.votes-per-ip-party:300}") int perClientVoteLimit) {
        this(perClientLimit, clientWindowMinutes, perPartyLimit, clientIpHeader, perClientVoteLimit, Clock.systemUTC());
    }

    /** With the votes' default limit (tests and the page renders). */
    public GuestRequestLimiter(int perClientLimit, int clientWindowMinutes, int perPartyLimit, String clientIpHeader) {
        this(perClientLimit, clientWindowMinutes, perPartyLimit, clientIpHeader, 300, Clock.systemUTC());
    }

    GuestRequestLimiter(int perClientLimit, int clientWindowMinutes, int perPartyLimit, String clientIpHeader, Clock clock) {
        this(perClientLimit, clientWindowMinutes, perPartyLimit, clientIpHeader, 300, clock);
    }

    GuestRequestLimiter(int perClientLimit, int clientWindowMinutes, int perPartyLimit, String clientIpHeader,
                        int perClientVoteLimit, Clock clock) {
        this.perClientLimit = perClientLimit;
        this.perClientVoteLimit = perClientVoteLimit;
        this.clientWindow = Duration.ofMinutes(Math.max(1, clientWindowMinutes));
        this.perPartyLimit = perPartyLimit;
        this.clientIpHeader = clientIpHeader == null ? "" : clientIpHeader.strip();
        this.clock = clock;
        // Bounded, so a flood of fake addresses cannot grow the maps without end; an entry is dropped one window after its last
        // request at the latest (the window's own start decides whether it is still open).
        this.clientWindows = Caffeine.newBuilder().expireAfterWrite(clientWindow).maximumSize(100_000).build();
        this.voteWindows = Caffeine.newBuilder().expireAfterWrite(clientWindow).maximumSize(100_000).build();
        this.partyWindows = Caffeine.newBuilder().expireAfterWrite(PARTY_WINDOW).maximumSize(10_000).build();
    }

    /**
     * The address a request came from: the configured proxy header if it is present, the remote address otherwise.
     * Read it on the request thread — not inside the {@code Callable} of an async request.
     */
    public String clientIp(HttpServletRequest request) {
        if (!clientIpHeader.isEmpty()) {
            String value = request.getHeader(clientIpHeader);
            if (value != null && !value.isBlank()) {
                // CF-Connecting-IP holds one address; take the first one in case a header holds a list
                return value.split(",", 2)[0].strip();
            }
        }
        return request.getRemoteAddr();
    }

    /**
     * Counts one request against both limits.
     *
     * @return empty if the request may go on (it has been counted), otherwise the limit that refused it
     */
    public Optional<Refusal> tryAcquire(String clientIp, String partyCode) {
        Optional<Long> clientWait = acquire(clientWindows, clientIp + "|" + partyCode, perClientLimit, clientWindow);
        if (clientWait.isPresent()) {
            return Optional.of(new Refusal(Scope.CLIENT, clientWait.get()));
        }
        return acquire(partyWindows, partyCode, perPartyLimit, PARTY_WINDOW)
                .map(wait -> new Refusal(Scope.PARTY, wait));
    }

    /**
     * Counts one guest's 👍 (or its taking back) against the votes' limit of the client address at the party.
     *
     * @return empty if the vote may go on (it has been counted), otherwise the seconds to wait
     */
    public Optional<Long> tryAcquireVote(String clientIp, String partyCode) {
        return acquire(voteWindows, clientIp + "|" + partyCode, perClientVoteLimit, clientWindow);
    }

    // ---- What the DJ's dashboard shows (reads only) ----

    /** Requests per client address and party per window; 0 or less = off. */
    public int perClientLimit() {
        return perClientLimit;
    }

    public long clientWindowMinutes() {
        return clientWindow.toMinutes();
    }

    /** Requests per party per 24 hours; 0 or less = off. */
    public int perPartyLimit() {
        return perPartyLimit;
    }

    /** How many requests the party has used of its 24-hour window (0 when no window is open). */
    public int partyRequestsUsed(String partyCode) {
        Window window = partyWindows.getIfPresent(partyCode);
        if (window == null || !clock.instant().isBefore(window.start().plus(PARTY_WINDOW))) {
            return 0;
        }
        return perPartyLimit > 0 ? Math.min(window.count(), perPartyLimit) : window.count();
    }

    /**
     * The most requests one client address has used of its open window at this party — the network closest to its limit (at a
     * party that is usually the venue's Wi-Fi, which all its guests share). 0 when no window is open. Scans the address windows of
     * every party — one per address that asked in the last window, a few hundred at a busy time, at most the cache's bound — on
     * every queue poll of a dashboard (every 3 s).
     */
    public int busiestClientRequestsUsed(String partyCode) {
        String suffix = "|" + partyCode;
        Instant now = clock.instant();
        int busiest = 0;
        for (Map.Entry<String, Window> entry : clientWindows.asMap().entrySet()) {
            Window window = entry.getValue();
            if (entry.getKey().endsWith(suffix) && now.isBefore(window.start().plus(clientWindow))) {
                busiest = Math.max(busiest, window.count());
            }
        }
        return perClientLimit > 0 ? Math.min(busiest, perClientLimit) : busiest;
    }

    /** Whether the party's 24-hour limit is spent: its guests are refused until the window ends. */
    public boolean isPartyLimitReached(String partyCode) {
        return perPartyLimit > 0 && partyRequestsUsed(partyCode) >= perPartyLimit;
    }

    /** Takes one request from the key's window in one atomic step; returns the seconds to wait if the window is full. */
    private Optional<Long> acquire(Cache<String, Window> windows, String key, int limit, Duration length) {
        if (limit <= 0) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        long[] waitSeconds = {-1};
        windows.asMap().compute(key, (k, window) -> {
            if (window == null || !now.isBefore(window.start().plus(length))) {
                return new Window(now, 1);
            }
            if (window.count() >= limit) {
                waitSeconds[0] = Math.max(1, Duration.between(now, window.start().plus(length)).toSeconds());
                if (window.count() == limit) {
                    log.warn("Guest request limit of {} per {} reached for '{}'", limit, length, loggable(k));
                    return new Window(window.start(), limit + 1); // log once per window
                }
                return window;
            }
            return new Window(window.start(), window.count() + 1);
        });
        return waitSeconds[0] < 0 ? Optional.empty() : Optional.of(waitSeconds[0]);
    }

    /** The party code of a key, without the client address (not logged). */
    private static String loggable(String key) {
        int separator = key.indexOf('|');
        return separator < 0 ? key : "one address @ " + key.substring(separator + 1);
    }
}
