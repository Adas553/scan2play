package com.scan2play.service;

import com.scan2play.entity.DjPlanEntity;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.CommentStyle;
import com.scan2play.model.Plan;
import com.scan2play.model.PlanFeature;
import com.scan2play.model.PlanSource;
import com.scan2play.repository.DjPlanRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The one place that decides what a party may do by its organiser's plan (V35, the owner 2026-10-14: the app stays free, some
 * features may one day be "Pro"). As {@code DjSessionHelper.require} for the staff's permissions: {@link #require} on the server for
 * every endpoint of a Pro feature, {@link #asPlanned} for what the guests and the AI get, {@link #view} for what the pages show.
 * <p>
 * <b>{@code scan2play.plans.enforce=false}</b> (the start): every party is {@link Plan#PRO} — no row is read, no page says "Pro";
 * nothing changes for anyone. Enforced: the organiser's plan — Pro for ever when their Google subject is in
 * {@code scan2play.plans.pro-owners}, else their row of {@code dj_plan} while it is valid, else {@link Plan#FREE}. The staff work an
 * organiser's party with the organiser's plan (the party's {@code ownerId} decides, never the person). Leaving Pro deletes nothing:
 * the hosts' lists, the profiles, the tip link, the comment style and the limits stay saved and only stop working until Pro is back.
 */
@Service
@Slf4j
public class PlanService {

    /**
     * The organisers' plans by their Google subject (AppConfig): read once per {@code CACHE_TTL}, emptied when a plan is written. What
     * is kept is the row's plan with its end ({@link Read}), so a plan ends when its row says, not up to {@code CACHE_TTL} later.
     */
    public static final String CACHE = "accountPlan";
    public static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final DjPlanRepository planRepository;
    private final CacheManager cacheManager;
    private final boolean enforced;
    private final Set<String> proOwners;
    private final int trialDays;

    public PlanService(DjPlanRepository planRepository, CacheManager cacheManager,
                       @Value("${scan2play.plans.enforce:false}") boolean enforced,
                       @Value("${scan2play.plans.pro-owners:}") String proOwners,
                       @Value("${scan2play.plans.trial-days:0}") int trialDays) {
        this.planRepository = planRepository;
        this.cacheManager = cacheManager;
        this.enforced = enforced;
        this.proOwners = Arrays.stream(proOwners.split(",")).map(String::strip).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        this.trialDays = Math.max(0, trialDays);
        log.info("Plans {}", enforced ? "enforced (" + this.proOwners.size() + " Pro for ever, trial " + this.trialDays + " days)"
                : "not enforced: every party has every feature");
    }

    /**
     * What a page shows of the plans: {@link #allows} a feature, and whether to offer it as "Pro" ({@link #pro}) — only to the
     * organiser (the staff cannot buy it: theirs stays hidden, 6.7) and only while the plans are enforced.
     */
    public record View(Plan plan, boolean offered) {

        /** For the templates: {@code plan.allows('HOST_LISTS')}. */
        public boolean allows(String feature) {
            return plan.includes(PlanFeature.valueOf(feature));
        }

        /** The feature shown with "PRO" in place of its form: the organiser's party has no Pro. */
        public boolean pro(String feature) {
            return offered && !allows(feature);
        }

        public int staffPlaces() {
            return plan.staffPlaces();
        }

        /** The places of the Pro plan: what the organiser without it is told of ("Do 10 osób w planie Pro"). */
        public int proStaffPlaces() {
            return Plan.PRO.staffPlaces();
        }

        /** The staff's places shown as "PRO" to the organiser: their plan has fewer than Pro. */
        public boolean proStaff() {
            return offered && plan.staffPlaces() < Plan.PRO.staffPlaces();
        }
    }

    /** A plan as its row says it: the plan and its end (null: none) — the same rule as {@link DjPlanEntity#planAt}. */
    record Read(Plan plan, Instant until) {

        static final Read NONE = new Read(Plan.FREE, null);

        Plan at(Instant now) {
            return until == null || until.isAfter(now) ? plan : Plan.FREE;
        }
    }

    public boolean isEnforced() {
        return enforced;
    }

    /** The organiser's plan: {@link Plan#PRO} while the plans are not enforced (and then no row is read). */
    public Plan planOf(String ownerId) {
        if (!enforced) {
            return Plan.PRO;
        }
        if (ownerId == null) {
            return Plan.FREE;
        }
        if (proOwners.contains(ownerId)) {
            return Plan.PRO;
        }
        Cache cache = cache();
        Read cached = cache.get(ownerId, Read.class);
        if (cached == null) {
            cached = planRepository.findById(ownerId).map(row -> new Read(row.getPlan(), row.getValidUntil())).orElse(Read.NONE);
            cache.put(ownerId, cached);
        }
        return cached.at(Instant.now());
    }

    /** The plan the party works with: its organiser's. */
    public Plan planOf(PartySettingsEntity party) {
        return enforced ? planOf(party.getOwnerId()) : Plan.PRO;
    }

    public boolean allows(PartySettingsEntity party, PlanFeature feature) {
        return planOf(party).includes(feature);
    }

    /** The party, when its organiser's plan has the feature; else a 403 ({@link PlanRequiredException}). */
    public PartySettingsEntity require(PartySettingsEntity party, PlanFeature feature) {
        if (!allows(party, feature)) {
            log.info("Party [{}]: {} needs the Pro plan", party.getPartyCode(), feature);
            throw new PlanRequiredException(feature);
        }
        return party;
    }

    /** The most people on the party's staff (with the invitations by e-mail waiting): 10 with Pro, 1 without. */
    public int staffPlaces(PartySettingsEntity party) {
        return planOf(party).staffPlaces();
    }

    /** What the pages show for this party; {@code organiser}: the person looking owns it. */
    public View view(PartySettingsEntity party, boolean organiser) {
        return new View(planOf(party), enforced && organiser);
    }

    /**
     * The party as its plan lets it work — for the guests, the AI and the queue's marks, never for saving: a copy without what the
     * plan does not have (the hosts' lists and link, the profiles, the tip link) and with the defaults for what it does not let the
     * DJ change (the comment style "Klasyczne", the guests' limits of a new party). With every feature, the party as it is.
     */
    public PartySettingsEntity asPlanned(PartySettingsEntity party) {
        Plan plan = planOf(party);
        if (plan == Plan.PRO) {
            return party;
        }
        PartySettingsEntity planned = party.toBuilder().build();
        if (!plan.includes(PlanFeature.HOST_LISTS)) {
            planned.setHostBlocked(null);
            planned.setHostWanted(null);
            planned.setHostToken(null);
        }
        if (!plan.includes(PlanFeature.PROFILES)) {
            planned.setInstagramUrl(null);
            planned.setFacebookUrl(null);
            planned.setTiktokUrl(null);
        }
        if (!plan.includes(PlanFeature.TIPS)) {
            planned.setTipUrl(null);
        }
        if (!plan.includes(PlanFeature.COMMENT_STYLES)) {
            planned.setCommentStyle(CommentStyle.CLASSIC);
        }
        if (!plan.includes(PlanFeature.GUEST_LIMITS)) {
            planned.setRequestLimit(PartySettingsEntity.DEFAULT_REQUEST_LIMIT);
            planned.setCooldownMinutes(PartySettingsEntity.DEFAULT_COOLDOWN_MINUTES);
            planned.setDuplicateCheckWindow(PartySettingsEntity.DEFAULT_DUPLICATE_CHECK_WINDOW);
        }
        return planned;
    }

    /**
     * A new DJ's trial of Pro ({@code scan2play.plans.trial-days}), when their party is made: only while the plans are enforced and
     * only once per Google account — a DJ with a row of {@code dj_plan} gets none, and neither does one who had a trial and deleted
     * the account since ({@code trial_used}, V36: only the account's {@link #trialKey} outlives it — the owner, 2026-10-15). The
     * caller's transaction (the party's creation); the cached plan goes after its commit — before it, a request reading the plan
     * meanwhile found no row and kept Free for {@code CACHE_TTL} (the review of 2026-10-14).
     */
    public void startTrial(String ownerId) {
        if (!enforced || trialDays == 0 || planRepository.existsById(ownerId)) {
            return;
        }
        String key = trialKey(ownerId);
        if (planRepository.trialUsed(key)) {
            log.info("DJ {}: had a trial of Pro before (an account deleted since) — none again", ownerId);
            return;
        }
        Instant now = Instant.now();
        planRepository.save(DjPlanEntity.builder().ownerId(ownerId).plan(Plan.PRO).source(PlanSource.TRIAL)
                .validUntil(now.plus(Duration.ofDays(trialDays))).updatedAt(now).build());
        planRepository.markTrialUsed(key);
        afterCommit(() -> cache().evict(ownerId));
        log.info("DJ {}: a trial of Pro for {} days", ownerId, trialDays);
    }

    /**
     * The key a Google account is remembered by in {@code trial_used} (V36): the SHA-256 of its Google subject, in lower-case hex —
     * PostgreSQL's {@code encode(sha256(convert_to(owner_id, 'UTF8')), 'hex')}, which V36 used for the trials before it.
     */
    public static String trialKey(String ownerId) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ownerId.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is in every JVM", e);
        }
    }

    /** After the commit of the caller's transaction; at once without one (a unit test). */
    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /** The organiser's plan read again on the next request (their row changed). */
    public void forget(String ownerId) {
        cache().evict(ownerId);
    }

    private Cache cache() {
        return Objects.requireNonNull(cacheManager.getCache(CACHE), CACHE);
    }
}
