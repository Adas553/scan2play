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

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
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

    /** The organisers' plans by their Google subject (AppConfig): read once per {@code CACHE_TTL}, emptied when a plan is written. */
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
        Plan cached = cache.get(ownerId, Plan.class);
        if (cached == null) {
            cached = planRepository.findById(ownerId).map(row -> row.planAt(Instant.now())).orElse(Plan.FREE);
            cache.put(ownerId, cached);
        }
        return cached;
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
     * only once — a DJ with a row of {@code dj_plan} already gets none. The caller's transaction (the party's creation).
     */
    public void startTrial(String ownerId) {
        if (!enforced || trialDays == 0 || planRepository.existsById(ownerId)) {
            return;
        }
        Instant now = Instant.now();
        planRepository.save(DjPlanEntity.builder().ownerId(ownerId).plan(Plan.PRO).source(PlanSource.TRIAL)
                .validUntil(now.plus(Duration.ofDays(trialDays))).updatedAt(now).build());
        cache().evict(ownerId);
        log.info("DJ {}: a trial of Pro for {} days", ownerId, trialDays);
    }

    /** The organiser's plan read again on the next request (their row changed). */
    public void forget(String ownerId) {
        cache().evict(ownerId);
    }

    private Cache cache() {
        return Objects.requireNonNull(cacheManager.getCache(CACHE), CACHE);
    }
}
