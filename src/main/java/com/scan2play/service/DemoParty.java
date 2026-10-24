package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.SongRequestDedicationEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.VibeType;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.repository.SongRequestDedicationRepository;
import com.scan2play.repository.SongRequestRepository;
import com.scan2play.util.AfterCommit;
import com.scan2play.util.Times;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import static com.scan2play.service.DjService.DECISION_ACCEPTED;
import static com.scan2play.service.DjService.DECISION_PLAYED;
import static com.scan2play.service.DjService.DECISION_REJECTED;

/**
 * The demo (the owner, 2026-10-24: people who saw a post wanted to look before giving a Google account): two parties of its own, in
 * the visitor's language — {@value #CODE} in Polish, {@value #CODE_EN} in English for everyone else (the owner, 2026-10-24) —, each
 * with its own examples, vibe note and comments, owned by no one (their owners are no Google accounts, so no DJ's page ever opens
 * them). A dozen example requests each: waiting ones with votes, dedications, a "⚠ Sprawdź", a song the hosts want, and a history.
 * {@code /demo} shows the panel of the visitor's language to anyone, only to look at ({@code DemoController}); its guest page works,
 * but the AI is not asked ({@code SongEvaluationService.demoAnswer}) — the demo costs no Gemini call and is no free AI for anyone.
 * What a visitor sends is theirs alone: saved unchecked, so the other visitors' lists and the TV screen never show it, and the demo
 * panel shows each visitor only the examples and their own. {@link #reset} puts the examples back at every start and every night
 * (04:55 Polish time, after the queues' cleanup), drops the visitors' requests and starts the party's daily limit again.
 * <p>
 * The codes have four characters: {@code CodeGenerator} makes five, so no DJ's party is ever given one of them.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DemoParty {

    /** The Polish demo's code (four characters: no real party has one). */
    public static final String CODE = "DEMO";

    /** The English demo's code: everyone whose language is not Polish. */
    public static final String CODE_EN = "DEME";

    private final PartySettingsRepository partySettingsRepository;
    private final SongRequestRepository songRequestRepository;
    private final SongRequestDedicationRepository dedicationRepository;
    private final CacheManager cacheManager;
    private final PlatformTransactionManager transactionManager;
    private final GuestRequestLimiter guestRequestLimiter;

    /** Whether the code is a demo's. */
    public static boolean isDemo(String partyCode) {
        return CODE.equals(partyCode) || CODE_EN.equals(partyCode);
    }

    /** The demo of the visitor's language: Polish for Polish, English for any other. */
    public static String codeFor(Locale locale) {
        return locale != null && "pl".equals(locale.getLanguage()) ? CODE : CODE_EN;
    }

    /**
     * One example request: the song, the guest's words, the decision, the votes, the AI's comment, how many minutes ago, a dedication
     * (or null), whether the DJ skipped it; a played one played that many minutes ago too, a skip skipped then; {@code tips} 💸.
     */
    record Example(String song, String guestText, String decision, int votes, String comment, int minutesAgo, String dedication,
                   boolean skippedByTheDj, int tips) {
    }

    /**
     * One demo: its code, its owner (no Google account; unique, as every party's owner), what the DJ wrote for the vibe, the hosts'
     * "must play" (a song of the queue gets its ⭐), its examples, the oldest first (the numbers go in this order, as at a party).
     */
    record Demo(String code, String owner, String vibeNote, String hostsWant, List<Example> examples) {
    }

    static final Demo POLISH = new Demo(CODE, "scan2play-demo", "Wesele 30+, przeboje do tańca, bez metalu", "Wilki - Baśka", List.of(
            new Example("Zenek Martyniuk - Przez twe oczy zielone", "przez twe oczy zielone", DECISION_PLAYED, 4,
                    "Hit wesel — parkiet pełny w 3 sekundy.", 95, null, false, 0),
            new Example("Smash Mouth - All Star", "all star shrek", DECISION_PLAYED, 2, "Bajkowy klasyk, wszyscy znają refren.", 80, null,
                    false, 0),
            new Example("Slayer - Raining Blood", "slayer raining blood", DECISION_REJECTED, 1,
                    "Dziś raczej nie — sala czeka na przeboje do tańca, nie na thrash metal.", 70, null, false, 0),
            new Example("Wilki - Baśka", "wilki baska", DECISION_ACCEPTED, 5, "Klasyk, który podniesie każdego z krzesła!", 60,
                    "dla Ani i Tomka — 10 lat razem!", false, 0),
            new Example("ABBA - Dancing Queen", "dancing queen abba", DECISION_ACCEPTED, 4, "Disco nigdy się nie starzeje!", 52, null,
                    false, 1),
            new Example("Ich Troje - Powiedz", "ich troje powiedz", DECISION_REJECTED, 1, "Mocny wybór na drugą część wieczoru.", 48,
                    null, true, 0),
            new Example("Dawid Podsiadło - Małomiasteczkowy", "malomiasteczkowy", DECISION_ACCEPTED, 3,
                    "Świetny wybór na rozgrzanie parkietu.", 40, null, false, 0),
            new Example("Queen - Don't Stop Me Now", "dont stop me now", DECISION_ACCEPTED, 2, "Energia na sto procent!", 30, null, false,
                    0),
            new Example("Daft Punk - One More Time", "ta piosenka co leci w reklamie", DECISION_ACCEPTED, 1,
                    "Jeśli o tę chodziło — idealna do tańca.", 18, null, false, 0),
            new Example("sanah - Szampan", "szampan sanah", DECISION_ACCEPTED, 1, "Musujący wybór na toast!", 8, "dla babci Zosi 🎉",
                    false, 0)));

    static final Demo ENGLISH = new Demo(CODE_EN, "scan2play-demo-en", "Wedding, 30+ crowd, dance hits, no metal",
            "Whitney Houston - I Wanna Dance with Somebody", List.of(
            new Example("Bon Jovi - Livin' on a Prayer", "livin on a prayer", DECISION_PLAYED, 4,
                    "A wedding anthem — the whole room sings along.", 95, null, false, 0),
            new Example("Smash Mouth - All Star", "all star shrek", DECISION_PLAYED, 2, "A cartoon classic, everyone knows the chorus.", 80,
                    null, false, 0),
            new Example("Slayer - Raining Blood", "slayer raining blood", DECISION_REJECTED, 1,
                    "Not tonight — the room wants dance hits, not thrash metal.", 70, null, false, 0),
            new Example("Whitney Houston - I Wanna Dance with Somebody", "i wanna dance with somebody", DECISION_ACCEPTED, 5,
                    "Pure joy on the dance floor!", 60, "for Anna & Tom — 10 years together!", false, 0),
            new Example("ABBA - Dancing Queen", "dancing queen abba", DECISION_ACCEPTED, 4, "Disco never gets old!", 52, null, false, 1),
            new Example("Celine Dion - My Heart Will Go On", "titanic song", DECISION_REJECTED, 1, "A strong pick for later in the night.",
                    48, null, true, 0),
            new Example("Mark Ronson - Uptown Funk", "uptown funk", DECISION_ACCEPTED, 3, "Great pick to warm up the floor.", 40, null,
                    false, 0),
            new Example("Queen - Don't Stop Me Now", "dont stop me now", DECISION_ACCEPTED, 2, "Energy at a hundred percent!", 30, null,
                    false, 0),
            new Example("Daft Punk - One More Time", "that song from the ad", DECISION_ACCEPTED, 1,
                    "If that's the one you meant — perfect for dancing.", 18, null, false, 0),
            new Example("Dua Lipa - Levitating", "levitating dua lipa", DECISION_ACCEPTED, 1, "A sparkling pick for a toast!", 8,
                    "for grandma Rose 🎉", false, 0)));

    /** Both demos. */
    static final List<Demo> DEMOS = List.of(POLISH, ENGLISH);

    @EventListener(ApplicationReadyEvent.class)
    public void atStart() {
        reset();
    }

    @Scheduled(cron = "0 55 4 * * *", zone = Times.SCHEDULE_ZONE)
    public void everyNight() {
        reset();
    }

    /**
     * Both demo parties as they are shown: made when missing, their settings put back, every request of them (the visitors' too)
     * deleted and the examples saved again, timed from now. Under the party's requests' lock, as anything that shapes a queue. One
     * transaction per party (a template, not {@code @Transactional}: the start and the night call it from this class, past Spring's
     * proxy).
     */
    public void reset() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        for (Demo demo : DEMOS) {
            transaction.executeWithoutResult(status -> putBack(demo));
            // the party's daily limit is one for all its visitors: a script that spent it would block the demo until a restart
            guestRequestLimiter.resetParty(demo.code());
        }
    }

    private void putBack(Demo demo) {
        PartySettingsEntity party = partySettingsRepository.findByPartyCode(demo.code()).orElseGet(() -> PartySettingsEntity.builder()
                .partyCode(demo.code()).ownerId(demo.owner()).build());
        party.setActive(true);
        party.setGlobalVibe(VibeType.ANY);
        party.setDjName("DJ Demo");
        party.setVibeNote(demo.vibeNote());
        party.setDedicationsOn(true);
        party.setHostWanted(demo.hostsWant());
        partySettingsRepository.save(party);

        songRequestRepository.lockRequests(SongRequestCommandService.lockKey(demo.code()));
        songRequestRepository.deleteByPartyCode(demo.code());
        Instant now = Instant.now();
        int number = 0;
        for (Example example : demo.examples()) {
            Instant at = now.minus(Duration.ofMinutes(example.minutesAgo()));
            boolean numbered = !DECISION_REJECTED.equals(example.decision()) || example.skippedByTheDj();
            SongRequestEntity request = songRequestRepository.save(SongRequestEntity.builder()
                    .partyCode(demo.code()).songName(example.song()).guestText(example.guestText()).decision(example.decision())
                    .votes(example.votes()).djComment(example.comment()).style(VibeType.ANY.name()).requestedAt(at)
                    .requestNumber(numbered ? ++number : null)
                    .playedAt(DECISION_PLAYED.equals(example.decision()) ? at.plus(Duration.ofMinutes(5)) : null)
                    .skippedAt(example.skippedByTheDj() ? at.plus(Duration.ofMinutes(3)) : null)
                    .tips(example.tips())
                    .dedications(example.dedication() == null ? 0 : 1)
                    .build());
            if (example.dedication() != null) {
                dedicationRepository.save(SongRequestDedicationEntity.builder().songRequestId(request.getId())
                        .text(example.dedication()).checked(true).createdAt(at).build());
            }
        }
        songRequestRepository.resetRequestCounter(demo.code());
        AfterCommit.run(() -> {
            evict("partySettings", demo.code());
            evict(DjService.QUEUE_CACHE, demo.code());
        });
        log.info("Demo party {}: {} example request(s) put back", demo.code(), demo.examples().size());
    }

    private void evict(String cacheName, String code) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.evict(code);
        }
    }
}
