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

import static com.scan2play.service.DjService.DECISION_ACCEPTED;
import static com.scan2play.service.DjService.DECISION_PLAYED;
import static com.scan2play.service.DjService.DECISION_REJECTED;

/**
 * The demo (the owner, 2026-10-24: people who saw a post wanted to look before giving a Google account): a party of its own, code
 * {@value #CODE}, owned by no one ({@value #OWNER} is no Google account, so no DJ's page ever opens it), with a dozen example requests —
 * waiting ones with votes, dedications, a "⚠ Sprawdź", a song the hosts want, and a history. {@code /demo} shows its panel to anyone,
 * only to look at ({@code DemoController}); its guest page {@code /p/DEMO} works, but the AI is not asked: {@link #answer} gives a
 * ready verdict — the demo costs no Gemini call and is no free AI for anyone. What a visitor sends is theirs alone: saved unchecked,
 * so the other visitors' lists and the TV screen never show it, and the demo panel shows each visitor only the examples and their
 * own. {@link #reset} puts the examples back at every start and every night (04:55 Polish time, after the queues' cleanup), and drops
 * the visitors' requests.
 * <p>
 * The code has four characters: {@code CodeGenerator} makes five, so no DJ's party is ever given it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DemoParty {

    /** The demo party's code (four characters: no real party has one). */
    public static final String CODE = "DEMO";

    /** Its owner: no Google account has this id, so only {@code /demo} shows its panel. */
    static final String OWNER = "scan2play-demo";

    private final PartySettingsRepository partySettingsRepository;
    private final SongRequestRepository songRequestRepository;
    private final SongRequestDedicationRepository dedicationRepository;
    private final CacheManager cacheManager;
    private final PlatformTransactionManager transactionManager;

    /** Whether the code is the demo's. */
    public static boolean isDemo(String partyCode) {
        return CODE.equals(partyCode);
    }

    /**
     * One example request: the song, the guest's words, the decision, the votes, the AI's comment, how many minutes ago, a dedication
     * (or null); a played one played that many minutes ago too, a skip skipped then.
     */
    record Example(String song, String guestText, String decision, int votes, String comment, int minutesAgo, String dedication,
                   boolean skippedByTheDj) {
    }

    /** The examples, the oldest first (the numbers go in this order, as at a party). */
    static final List<Example> EXAMPLES = List.of(
            new Example("Zenek Martyniuk - Przez twe oczy zielone", "przez twe oczy zielone", DECISION_PLAYED, 4,
                    "Hit wesel — parkiet pełny w 3 sekundy.", 95, null, false),
            new Example("Smash Mouth - All Star", "all star shrek", DECISION_PLAYED, 2, "Bajkowy klasyk, wszyscy znają refren.", 80, null,
                    false),
            new Example("Slayer - Raining Blood", "slayer raining blood", DECISION_REJECTED, 1,
                    "Dziś raczej nie — sala czeka na przeboje do tańca, nie na thrash metal.", 70, null, false),
            new Example("Wilki - Baśka", "wilki baska", DECISION_ACCEPTED, 5, "Klasyk, który podniesie każdego z krzesła!", 60,
                    "dla Ani i Tomka — 10 lat razem!", false),
            new Example("ABBA - Dancing Queen", "dancing queen abba", DECISION_ACCEPTED, 4, "Disco nigdy się nie starzeje!", 52, null, false),
            new Example("Ich Troje - Powiedz", "ich troje powiedz", DECISION_REJECTED, 1, "Mocny wybór na drugą część wieczoru.", 48, null,
                    true),
            new Example("Dawid Podsiadło - Małomiasteczkowy", "malomiasteczkowy", DECISION_ACCEPTED, 3,
                    "Świetny wybór na rozgrzanie parkietu.", 40, null, false),
            new Example("Queen - Don't Stop Me Now", "dont stop me now", DECISION_ACCEPTED, 2, "Energia na sto procent!", 30, null, false),
            new Example("Daft Punk - One More Time", "ta piosenka co leci w reklamie", DECISION_ACCEPTED, 1,
                    "Jeśli o tę chodziło — idealna do tańca.", 18, null, false),
            new Example("sanah - Szampan", "szampan sanah", DECISION_ACCEPTED, 1, "Musujący wybór na toast!", 8, "dla babci Zosi 🎉",
                    false));

    /** The hosts' "koniecznie zagrać" list of the demo: a song of the queue gets its ⭐. */
    static final String HOSTS_WANT = "Wilki - Baśka";

    @EventListener(ApplicationReadyEvent.class)
    public void atStart() {
        reset();
    }

    @Scheduled(cron = "0 55 4 * * *", zone = Times.SCHEDULE_ZONE)
    public void everyNight() {
        reset();
    }

    /**
     * The demo party as it is shown: made when missing, its settings put back, every request of it (the visitors' too) deleted and
     * the examples saved again, timed from now. Under the party's requests' lock, as anything that shapes a queue. One transaction of
     * its own (a template, not {@code @Transactional}: the start and the night call it from this class, past Spring's proxy).
     */
    public void reset() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> putBack());
    }

    private void putBack() {
        PartySettingsEntity party = partySettingsRepository.findByPartyCode(CODE).orElseGet(() -> PartySettingsEntity.builder()
                .partyCode(CODE).ownerId(OWNER).build());
        party.setActive(true);
        party.setGlobalVibe(VibeType.ANY);
        party.setDjName("DJ Demo");
        party.setVibeNote("Wesele 30+, przeboje do tańca, bez metalu");
        party.setDedicationsOn(true);
        party.setHostWanted(HOSTS_WANT);
        partySettingsRepository.save(party);

        songRequestRepository.lockRequests(SongRequestCommandService.lockKey(CODE));
        songRequestRepository.deleteByPartyCode(CODE);
        Instant now = Instant.now();
        int number = 0;
        for (Example example : EXAMPLES) {
            Instant at = now.minus(Duration.ofMinutes(example.minutesAgo()));
            boolean numbered = !DECISION_REJECTED.equals(example.decision()) || example.skippedByTheDj();
            SongRequestEntity request = songRequestRepository.save(SongRequestEntity.builder()
                    .partyCode(CODE).songName(example.song()).guestText(example.guestText()).decision(example.decision())
                    .votes(example.votes()).djComment(example.comment()).style(VibeType.ANY.name()).requestedAt(at)
                    .requestNumber(numbered ? ++number : null)
                    .playedAt(DECISION_PLAYED.equals(example.decision()) ? at.plus(Duration.ofMinutes(5)) : null)
                    .skippedAt(example.skippedByTheDj() ? at.plus(Duration.ofMinutes(3)) : null)
                    .tips(example.song().startsWith("ABBA") ? 1 : 0)
                    .dedications(example.dedication() == null ? 0 : 1)
                    .build());
            if (example.dedication() != null) {
                dedicationRepository.save(SongRequestDedicationEntity.builder().songRequestId(request.getId())
                        .text(example.dedication()).checked(true).createdAt(at).build());
            }
        }
        songRequestRepository.resetRequestCounter(CODE);
        AfterCommit.run(() -> {
            evict("partySettings");
            evict(DjService.QUEUE_CACHE);
        });
        log.info("Demo party {}: {} example request(s) put back", CODE, EXAMPLES.size());
    }

    private void evict(String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.evict(CODE);
        }
    }
}
