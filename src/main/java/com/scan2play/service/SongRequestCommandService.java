package com.scan2play.service;

import com.scan2play.entity.SongRequestEntity;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.repository.SongRequestRepository;
import com.scan2play.util.CodeGenerator;
import com.scan2play.util.SongNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.scan2play.service.DjService.DECISION_ACCEPTED;

/**
 * Saves a guest's request — or, when the same song already waits in the queue, counts the guest's vote on it instead of a second
 * row: the DJ sees how many guests want a song ("×3"), and the queue does not fill with repeats.
 * <p>
 * <b>Concurrency:</b> the party's requests are saved under a transaction-scoped advisory lock, so two guests who ask for the same
 * song at the same moment make one row with two votes ({@code SongRequestVotesIT}), and two new songs never get the same number.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SongRequestCommandService {

    /**
     * What happened to a request: a new row, a vote on a waiting song, or nothing — the guest's own song already waits, or the DJ
     * skipped this song lately.
     */
    public enum Outcome { NEW, VOTE, ALREADY_YOURS, SKIPPED_BY_DJ }

    /**
     * @param request the row the request is in now: the new one, or the waiting song it was counted on (with its votes), or the
     *                request the DJ skipped ({@link Outcome#SKIPPED_BY_DJ}; nothing was saved)
     */
    public record Saved(Outcome outcome, SongRequestEntity request) {
    }

    /** The upper 32 bits of every lock key of guest requests: "S2PR". */
    private static final long REQUESTS_LOCK_SPACE = 0x5332_5052L << 32;

    /**
     * How long a song the DJ skipped stays out of the queue, from the skip (the owner, 2026-10-05: 12 hours was too long — a skip
     * by mistake can also be undone, "Cofnij" / "↩ Przywróć"). A DJ keeps one party (one QR code) for every event, so it never
     * lasts beyond the evening.
     */
    static final Duration SKIP_REMEMBERED = Duration.ofHours(2);

    /** The most of the DJ's skips a request is compared with (a bounded read; two hours have far fewer). */
    private static final int SKIPS_READ = 100;

    private final SongRequestRepository songRequestRepository;
    private final PartySettingsRepository partySettingsRepository;
    private final DjService djService;

    /**
     * Saves {@code request}, unless the same song already waits in the party's queue: then the waiting song gets one vote more
     * (and keeps its name, its link, its verdict and the words of the guest who asked first). A guest's own waiting song is not
     * counted again ({@link Outcome#ALREADY_YOURS}). That holds for a request the AI rejected too: the AI does not judge a song the
     * same way every time, and the party took this one already — the caller tells the guest the verdict the song was taken with.
     * A rejected request for a song that does not wait is a row of its own — the history shows it.
     * <p>
     * A song the DJ skipped ("Pomiń" — usually "I do not have it") within {@link #SKIP_REMEMBERED} is not saved again: it would
     * come back to the queue with every guest who asks for it ({@link Outcome#SKIPPED_BY_DJ}). Clearing the whole queue is not
     * such a decision about one song, so a cleared song may be asked for again.
     *
     * @param request     the request as the AI judged it, not saved yet
     * <p>
     * Nothing is saved for a party that is not there any more: the AI judges a request outside any transaction, and the organiser
     * may delete the account meanwhile — the guest's words would stay 30 days after "Usuń konto". The deletion holds this lock until
     * it commits (review 2026-10-14 W9, {@code AccountDeletionIT}), so the question asked under it has the final answer.
     *
     * @param guestsOwnIds the guest's earlier requests at this party (their session), to tell their own song
     * @throws IllegalArgumentException when the party is gone (as {@code PartySettingsQueryService.getSettings} for an unknown code)
     */
    @Transactional
    public Saved saveOrVote(SongRequestEntity request, Set<Long> guestsOwnIds) {
        songRequestRepository.lockRequests(lockKey(request.getPartyCode()));
        if (!partySettingsRepository.existsByPartyCode(request.getPartyCode())) {
            throw new IllegalArgumentException("Party not found: " + request.getPartyCode());
        }
        Optional<SongRequestEntity> waiting = sameSongWaiting(request);
        if (waiting.isPresent()) {
            SongRequestEntity song = waiting.get();
            if (guestsOwnIds.contains(song.getId())) {
                return new Saved(Outcome.ALREADY_YOURS, song);
            }
            if (songRequestRepository.addVote(song.getId()) > 0) {
                song.setVotes(song.getVotes() + 1);
                djService.refreshQueue(request.getPartyCode());
                log.info("Party [{}]: one more vote for '{}' ({} now)", request.getPartyCode(), song.getSongName(), song.getVotes());
                return new Saved(Outcome.VOTE, song);
            }
            // The DJ played or skipped it since it was read (that does not take this lock): as if it had not been waiting
        }
        Optional<SongRequestEntity> skipped = sameSongSkippedByTheDj(request);
        if (skipped.isPresent()) {
            log.info("Party [{}]: '{}' was skipped by the DJ lately — not back in the queue", request.getPartyCode(),
                    request.getSongName());
            return new Saved(Outcome.SKIPPED_BY_DJ, skipped.get());
        }
        if (DECISION_ACCEPTED.equals(request.getDecision())) {
            // a song in the queue gets the party's next number ("#27", V28) — under this lock, so two at once never share one
            Integer number = songRequestRepository.nextRequestNumber(request.getPartyCode());
            request.setRequestNumber(number != null ? number : songRequestRepository.maxRequestNumber(request.getPartyCode()) + 1);
            // the guests' list shows the new song with its next read, not up to 3 s later (review 2026-10-14 K1)
            djService.refreshQueue(request.getPartyCode());
        }
        return new Saved(Outcome.NEW, songRequestRepository.save(request));
    }

    /** The song of the request, if the DJ skipped it within {@link #SKIP_REMEMBERED}: the same name ({@link SongNames#same}). */
    private Optional<SongRequestEntity> sameSongSkippedByTheDj(SongRequestEntity request) {
        List<SongRequestEntity> skipped = songRequestRepository.findSkippedByTheDj(request.getPartyCode(),
                Instant.now().minus(SKIP_REMEMBERED), PageRequest.of(0, SKIPS_READ));
        return skipped.stream()
                .filter(song -> SongNames.same(song.getSongName(), request.getSongName()))
                .findFirst();
    }

    /** The waiting song of the party that is the same as the request: the same name ({@link SongNames#same}). */
    private Optional<SongRequestEntity> sameSongWaiting(SongRequestEntity request) {
        List<SongRequestEntity> waiting = songRequestRepository.findTop300ByPartyCodeAndDecisionInOrderByRequestedAtAsc(
                request.getPartyCode(), List.of(DECISION_ACCEPTED));
        return waiting.stream()
                .filter(song -> SongNames.same(song.getSongName(), request.getSongName()))
                .findFirst();
    }

    /**
     * The advisory-lock key of a party's guest requests: "S2PR" and the code itself — each of its five characters by its place in
     * {@link CodeGenerator#CHARACTERS} (1–36, six bits: 30 bits in all), so two parties never share a key. The code's 32-bit hash
     * gave "AOXXX" and "B0XXX" one key, and two parties took turns for nothing (review 2026-10-14). A code of another shape (none
     * is made) gets its hash, with the top bit of the half set: apart from every packed code.
     */
    static long lockKey(String partyCode) {
        long packed = 0;
        boolean madeByTheApp = partyCode != null && !partyCode.isEmpty() && partyCode.length() <= CodeGenerator.CODE_LENGTH;
        for (int i = 0; madeByTheApp && i < partyCode.length(); i++) {
            int place = CodeGenerator.CHARACTERS.indexOf(partyCode.charAt(i));
            madeByTheApp = place >= 0;
            packed = (packed << 6) | (place + 1);
        }
        long half = madeByTheApp ? packed : 0x8000_0000L | (Objects.hashCode(partyCode) & 0x7FFF_FFFFL);
        return REQUESTS_LOCK_SPACE | half;
    }
}
