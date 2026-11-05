package com.scan2play.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.Plan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Free plan's AI checks an evening ({@code guest.limit.free-ai-per-party}, 150): every guest's request that would go to Gemini is
 * counted here first, and past the limit it reaches the DJ unchecked — the path of "the AI is down" — so a party never stops, only
 * its cost does (one check ≈ $0.002–0.003). Only while the plans are enforced and for a party whose organiser has no Pro; Pro's bound
 * is the server's 300 a day for everyone ({@link GuestRequestLimiter}). Not counted: 👍, the refusals before the AI, the demo, a call that failed
 * ({@link #giveBack}).
 * <p>
 * The evening is {@link EveningSummaryService#eveningOf}'s (from 6:00 to 6:00, Polish time), so a party past midnight is one evening.
 * In memory, as the other limits: a restart starts the count again — in the DJ's favour. Yesterday's count stays two evenings, for the
 * organiser's note the next day ({@link #yesterday}).
 */
@Service
@Slf4j
public class FreeAiLimit {

    /** What an evening of a party used: requests that would have gone to the AI, and the limit (0: none applies). */
    public record Use(int asked, int limit) {

        static final Use NONE = new Use(0, 0);

        /** The requests the AI judged. */
        public int checked() {
            return limit > 0 ? Math.min(asked, limit) : asked;
        }

        /** The requests that reached the DJ unchecked because of the limit. */
        public int unchecked() {
            return limit > 0 ? Math.max(0, asked - limit) : 0;
        }

        public boolean reached() {
            return limit > 0 && asked >= limit;
        }
    }

    private static final int MAX_EVENINGS = 20_000;

    private final int limit;
    private final PlanService planService;
    private final Clock clock;
    /**
     * "party|evening" → the requests counted. Kept 54 hours from the evening's first request — its 24 hours and the next evening's
     * (yesterday's note), with a margin: one made at 6:00 still holds at 6:00 two days on. The clock's time, so a test can move it.
     */
    private final Cache<String, AtomicInteger> counts;

    @Autowired
    public FreeAiLimit(@Value("${guest.limit.free-ai-per-party:150}") int limit, PlanService planService) {
        this(limit, planService, Clock.systemUTC());
    }

    /** With a clock of its own (tests: an evening, then the next). */
    public FreeAiLimit(int limit, PlanService planService, Clock clock) {
        this.limit = limit;
        this.planService = planService;
        this.clock = clock;
        this.counts = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(54)).maximumSize(MAX_EVENINGS)
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis())).build();
    }

    /** No limit at all (tests). */
    public static FreeAiLimit off() {
        return new FreeAiLimit(0, null, Clock.systemUTC());
    }

    /** The limit for this party now: 0 when none applies (off, the plans not enforced, an organiser with Pro). */
    public int limitOf(PartySettingsEntity party) {
        if (limit <= 0 || planService == null || !planService.isEnforced() || planService.planOf(party) == Plan.PRO) {
            return 0;
        }
        return limit;
    }

    /**
     * Counts one request about to go to the AI.
     *
     * @return whether the AI may judge it; false: past the Free limit, it goes to the DJ unchecked
     */
    public boolean tryAcquire(PartySettingsEntity party) {
        int partyLimit = limitOf(party);
        if (partyLimit <= 0) {
            return true;
        }
        int asked = counts.get(key(party, today()), k -> new AtomicInteger()).incrementAndGet();
        if (asked == partyLimit + 1) {
            log.info("Party [{}]: the Free plan's {} AI checks of the evening used — the next requests go to the DJ unchecked",
                    party.getPartyCode(), partyLimit);
        }
        return asked <= partyLimit;
    }

    /**
     * Gives back a request counted by {@link #tryAcquire} that the AI never judged (it could not be asked: an outage) — the Free plan's
     * checks are the AI's answers, and a failed call costs nothing.
     */
    public void giveBack(PartySettingsEntity party) {
        if (limitOf(party) <= 0) {
            return;
        }
        AtomicInteger asked = counts.getIfPresent(key(party, today()));
        if (asked != null) {
            asked.updateAndGet(n -> Math.max(0, n - 1));
        }
    }

    /** This evening's use (the panel's line at 80 % and its bar at the limit). */
    public Use today(PartySettingsEntity party) {
        return use(party, today());
    }

    /** Yesterday evening's use (the organiser's note the next day). */
    public Use yesterday(PartySettingsEntity party) {
        return use(party, today().minusDays(1));
    }

    private Use use(PartySettingsEntity party, LocalDate evening) {
        int partyLimit = limitOf(party);
        if (partyLimit <= 0) {
            return Use.NONE;
        }
        AtomicInteger asked = counts.getIfPresent(key(party, evening));
        return new Use(asked == null ? 0 : asked.get(), partyLimit);
    }

    private LocalDate today() {
        return EveningSummaryService.eveningOf(clock.instant());
    }

    /**
     * The party by its row's id, which "Zmień kod imprezy" keeps — by its code, the change would start the evening's count again and
     * lose yesterday's; the code for a party without one (tests).
     */
    private static String key(PartySettingsEntity party, LocalDate evening) {
        return (party.getId() != null ? "#" + party.getId() : party.getPartyCode()) + "|" + evening;
    }
}
