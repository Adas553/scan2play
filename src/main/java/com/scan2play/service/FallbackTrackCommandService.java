package com.scan2play.service;

import com.scan2play.entity.FallbackPlayEntity;
import com.scan2play.entity.FallbackTrackEntity;
import com.scan2play.model.FallbackTrackStatus;
import com.scan2play.model.MoveDirection;
import com.scan2play.model.PlaylistTrack;
import com.scan2play.repository.FallbackPlayRepository;
import com.scan2play.repository.FallbackTrackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static com.scan2play.model.FallbackTrackStatus.CANCELLED;
import static com.scan2play.model.FallbackTrackStatus.PLAYED;
import static com.scan2play.model.FallbackTrackStatus.QUEUED;
import static com.scan2play.model.FallbackTrackStatus.SKIPPED;
import static com.scan2play.repository.FallbackTrackRepository.UPCOMING_ORDER;

/**
 * Database writes for the server-side fallback playlist. Kept separate from
 * {@link FallbackPlaylistService} so the (slow) YouTube API calls happen <em>outside</em> any
 * transaction — a DB connection is only held for the short swap below.
 * <p>
 * <b>Order:</b> every queued track has a fixed {@code playOrder}; the next track is the queued one with the lowest
 * value, so the DJ can be shown what comes next and reorder it ({@link #moveTrack}). The order is set in three
 * places — when the playlist is imported, when it starts a new round, and when the DJ switches shuffle on or off —
 * and is either the playlist order or a random order (shuffle); the DJ's moves apply to the current round only.
 * <p>
 * <b>Concurrency:</b> every method that changes the queue first takes a per-party advisory lock (transaction scoped),
 * so a move, a re-order, an import and the player taking a track never interleave — statements that update many
 * rows at once would otherwise lock the same rows in different orders and deadlock (seen against PostgreSQL).
 * <p>
 * <b>The play log:</b> the queue forgets what has played (a new round puts the played tracks back in the queue), so
 * every hand-out is also written to {@code fallback_play} — {@link #takeNextTrack} does it inside the same transaction
 * and under the same lock. The log is what the DJ history and "previous track" read.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FallbackTrackCommandService {

    /**
     * Upper bound of attempts when concurrent callers keep grabbing the track we picked. Everyone goes for the same
     * head of the queue, so a caller loses at most once per other caller: 10 covers a handful of open dashboards.
     */
    static final int MAX_TAKE_ATTEMPTS = 10;

    /** Added to the order of the tracks before the anchor when the playlist order is rotated; above any position (max 500). */
    static final int ROTATION = 10_000;

    private static final int NO_ANCHOR = -1;

    /** More than a playlist can hold (an import reads at most 500 tracks) — the whole queue is always loaded. */
    private static final int MAX_QUEUE = 1000;

    private final FallbackTrackRepository fallbackTrackRepository;
    private final FallbackPlayRepository fallbackPlayRepository;

    /**
     * Serialises everything that changes a party's queue (see {@link FallbackTrackRepository#lockQueue}). The lock
     * lives as long as the calling transaction, so this must be the first thing a queue-changing method does.
     */
    private void lockQueue(String partyCode) {
        fallbackTrackRepository.lockQueue(queueLockKey(partyCode));
    }

    /** The advisory-lock key of a party's queue (the prefix keeps it apart from other uses of the same lock space). */
    static long queueLockKey(String partyCode) {
        return ("fallback-queue:" + partyCode).hashCode();
    }

    /**
     * Replaces the party's fallback playlist: still-{@link FallbackTrackStatus#QUEUED} tracks are flipped to
     * CANCELLED (soft invalidation — PLAYED rows stay as history) and the new tracks are inserted as QUEUED,
     * all in one transaction. The new tracks play in playlist order, or in a random order when {@code shuffle}.
     *
     * @param playlistId source of the tracks (playlist ID or {@code V:<videoId>})
     * @param tracks     the videos in playlist order
     * @return number of tracks inserted
     */
    @Transactional
    public int replaceTracks(String partyCode, String playlistId, List<PlaylistTrack> tracks, boolean shuffle) {
        lockQueue(partyCode);
        int cancelled = fallbackTrackRepository.updateStatus(partyCode, QUEUED, CANCELLED);

        LocalDateTime fetchedAt = LocalDateTime.now();
        List<FallbackTrackEntity> entities = IntStream.range(0, tracks.size())
                .mapToObj(i -> FallbackTrackEntity.builder()
                        .partyCode(partyCode)
                        .playlistId(playlistId)
                        .videoId(tracks.get(i).videoId())
                        .title(tracks.get(i).title())
                        .playlistPosition(i)
                        .playOrder(i)
                        .status(QUEUED)
                        .fetchedAt(fetchedAt)
                        .build())
                .toList();
        fallbackTrackRepository.saveAll(entities);
        if (shuffle) {
            fallbackTrackRepository.shuffle(partyCode, playlistId, QUEUED.name());
        }

        log.info("Party [{}]: fallback playlist {} imported — {} track(s) queued ({}), {} previous track(s) cancelled",
                partyCode, playlistId, entities.size(), shuffle ? "shuffled" : "playlist order", cancelled);
        return entities.size();
    }

    /** The DJ cleared the fallback playlist: nothing queued stays queued. */
    @Transactional
    public void cancelQueuedTracks(String partyCode) {
        lockQueue(partyCode);
        int cancelled = fallbackTrackRepository.updateStatus(partyCode, QUEUED, CANCELLED);
        log.info("Party [{}]: fallback playlist cleared — {} queued track(s) cancelled", partyCode, cancelled);
    }

    /**
     * The DJ switched shuffle on or off: re-orders the tracks that are still queued.
     * <ul>
     *     <li><b>on</b> — a fresh random order;</li>
     *     <li><b>off</b> — playlist order <em>continuing after the track that was handed out last</em>, so the music
     *         carries on from where it is instead of jumping back to the start of the playlist (tracks already
     *         played in this round are not queued, so they do not come back).</li>
     * </ul>
     * The track that is playing is not queued and is not affected.
     */
    @Transactional
    public void applyShuffleSetting(String partyCode, String playlistId, boolean shuffle) {
        lockQueue(partyCode);
        int reordered;
        if (shuffle) {
            reordered = fallbackTrackRepository.shuffle(partyCode, playlistId, QUEUED.name());
        } else {
            int anchor = fallbackTrackRepository
                    .findFirstByPartyCodeAndPlaylistIdAndStatusOrderByPlayedAtDesc(partyCode, playlistId, PLAYED)
                    .map(FallbackTrackEntity::getPlaylistPosition)
                    .orElse(NO_ANCHOR);
            reordered = fallbackTrackRepository.orderByPlaylistPosition(partyCode, playlistId, QUEUED, anchor, ROTATION);
        }
        log.info("Party [{}]: fallback shuffle {} — {} queued track(s) re-ordered",
                partyCode, shuffle ? "on" : "off", reordered);
    }

    /**
     * The DJ moves a track of the queue: one place up or down, or to the front ("play next"). The moved tracks are
     * flagged, so the dashboard can say the order was changed by hand; a new order (import, shuffle, a new round,
     * the shuffle switch) clears the flags again.
     * <p>
     * Only a track that is still queued in the party's <em>current</em> playlist can be moved — never a track of
     * another party, of an old playlist, or one the player has already taken.
     *
     * @return true if the track is queued in this playlist (it was moved, or it already was at that end of the
     *         queue); false if it cannot be moved
     */
    @Transactional
    public boolean moveTrack(String partyCode, String playlistId, Long trackId, MoveDirection direction) {
        lockQueue(partyCode);
        if (!isQueuedIn(partyCode, playlistId, trackId)) {
            return false;
        }

        if (direction == MoveDirection.TOP) {
            List<FallbackTrackEntity> first = fallbackTrackRepository.findByPartyCodeAndPlaylistIdAndStatus(
                    partyCode, playlistId, QUEUED, PageRequest.of(0, 1, UPCOMING_ORDER));
            if (!first.isEmpty() && trackId.equals(first.get(0).getId())) {
                return true; // already next: nothing to change, nothing to flag
            }
            return fallbackTrackRepository.moveToFront(trackId, QUEUED) == 1;
        }

        // Neighbours are swapped by exchanging their play orders, which needs every order to be unique.
        fallbackTrackRepository.renumberQueued(partyCode, playlistId, QUEUED.name());
        List<FallbackTrackEntity> queue = fallbackTrackRepository.findByPartyCodeAndPlaylistIdAndStatus(
                partyCode, playlistId, QUEUED, PageRequest.of(0, MAX_QUEUE, UPCOMING_ORDER));
        int index = indexOf(queue, trackId);
        if (index < 0) {
            return false; // the player took it in the meantime
        }
        int neighbour = direction == MoveDirection.UP ? index - 1 : index + 1;
        if (neighbour < 0 || neighbour >= queue.size()) {
            return true; // already at that end of the queue
        }
        FallbackTrackEntity moved = queue.get(index);
        FallbackTrackEntity other = queue.get(neighbour);
        int movedOrder = moved.getPlayOrder();
        fallbackTrackRepository.setPlayOrderByHand(moved.getId(), other.getPlayOrder(), QUEUED);
        fallbackTrackRepository.setPlayOrderByHand(other.getId(), movedOrder, QUEUED);
        return true;
    }

    /**
     * The DJ drags a track to a new place: right in front of {@code beforeTrackId}, or to the end of the queue when that
     * is {@code null}. The dragged track is flagged as moved by hand; the tracks it passes only shift by one.
     * <p>
     * Referring to the track it goes in front of (not to a position) keeps this correct even if the queue changed while
     * the DJ was dragging — for example the player took a track meanwhile.
     *
     * @return true if both tracks are queued in the party's current playlist (the track was moved, or it already was at
     *         that place); false if it cannot be moved — the player took one of them, or one belongs to another party
     *         or to an old playlist
     */
    @Transactional
    public boolean placeTrack(String partyCode, String playlistId, Long trackId, Long beforeTrackId) {
        lockQueue(partyCode);
        if (!isQueuedIn(partyCode, playlistId, trackId)) {
            return false;
        }
        if (trackId.equals(beforeTrackId)) {
            return true; // dropped on itself
        }
        if (beforeTrackId != null && !isQueuedIn(partyCode, playlistId, beforeTrackId)) {
            return false;
        }

        // After renumbering the play orders are exactly the positions 0..n-1, so a move is "shift the tracks in between".
        fallbackTrackRepository.renumberQueued(partyCode, playlistId, QUEUED.name());
        List<FallbackTrackEntity> queue = fallbackTrackRepository.findByPartyCodeAndPlaylistIdAndStatus(
                partyCode, playlistId, QUEUED, PageRequest.of(0, MAX_QUEUE, UPCOMING_ORDER));
        int from = indexOf(queue, trackId);
        int before = beforeTrackId == null ? queue.size() : indexOf(queue, beforeTrackId);
        if (from < 0 || before < 0) {
            return false; // the player took one of them in the meantime
        }
        int to = before > from ? before - 1 : before; // the place the track ends up at
        if (to == from) {
            return true; // already exactly there
        }
        if (to > from) {
            fallbackTrackRepository.shiftPlayOrder(partyCode, playlistId, QUEUED, from + 1, to, -1);
        } else {
            fallbackTrackRepository.shiftPlayOrder(partyCode, playlistId, QUEUED, to, from - 1, 1);
        }
        fallbackTrackRepository.setPlayOrderByHand(trackId, to, QUEUED);
        return true;
    }

    /**
     * The DJ skips a track of the queue for this round: it is not queued any more, so it does not play now, and it comes back
     * when the playlist starts its next round (with the tracks that played). Nothing is written to the play log — the track
     * was never handed out.
     * <p>
     * When that was the last queued track the round is over: the next one starts at once, as when the player takes the last
     * track — so there is always a "next" to show — and the skipped track comes back at the end of it, not at the front.
     * <p>
     * Only a track that is still queued in the party's <em>current</em> playlist can be skipped — never a track of another
     * party, of an old playlist, or one the player has already taken.
     *
     * @param shuffle whether the next round is shuffled (only matters when the skip ends the round)
     * @return true if the track was queued in this playlist and is skipped now; false if it cannot be skipped
     */
    @Transactional
    public boolean skipTrack(String partyCode, String playlistId, Long trackId, boolean shuffle) {
        lockQueue(partyCode);
        if (!isQueuedIn(partyCode, playlistId, trackId)) {
            return false;
        }
        if (fallbackTrackRepository.markSkipped(trackId, QUEUED, SKIPPED) != 1) {
            return false;
        }
        if (fallbackTrackRepository.countByPartyCodeAndPlaylistIdAndStatus(partyCode, playlistId, QUEUED) == 0
                && startNewRound(partyCode, playlistId, shuffle, trackId) && !shuffle) {
            // a shuffled round keeps the track out of first place by itself; in playlist order it could open the round again
            keepOutOfFirstPlace(partyCode, playlistId, trackId);
        }
        log.info("Party [{}]: track {} of fallback playlist {} skipped for this round", partyCode, trackId, playlistId);
        return true;
    }

    /** Whether the track exists, belongs to the party and is still queued in the party's <em>current</em> playlist. */
    private boolean isQueuedIn(String partyCode, String playlistId, Long trackId) {
        return fallbackTrackRepository.findByIdAndPartyCode(trackId, partyCode)
                .filter(t -> t.getStatus() == QUEUED && playlistId.equals(t.getPlaylistId()))
                .isPresent();
    }

    private static int indexOf(List<FallbackTrackEntity> queue, Long trackId) {
        for (int i = 0; i < queue.size(); i++) {
            if (trackId.equals(queue.get(i).getId())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Hands out the next background track of the party's <em>current</em> playlist, marks it PLAYED and writes it to the
     * play log.
     * <ul>
     *     <li>It is the queued track with the lowest {@code playOrder} — the one the DJ is shown as "next".</li>
     *     <li>When that was the last queued track, the playlist starts a new round at once (played tracks go back
     *         into the queue, in playlist order or freshly shuffled), so there is always a "next" to show. A
     *         shuffled round never opens with the track that has just been handed out.</li>
     *     <li>The track is claimed with a single conditional UPDATE, so two concurrent callers never get
     *         the same one.</li>
     *     <li>The claim and the log row are one transaction: a track is never handed out without being in the history,
     *         and never in the history without having been handed out. The row is a snapshot (video, title, when it was
     *         fetched), so it survives the next round, a replaced playlist and the purge of the track.</li>
     * </ul>
     * Only tracks of {@code playlistId} are considered: after the DJ switches playlists, tracks of an old
     * playlist must never play — even if the import of the new one has not succeeded yet.
     *
     * @param shuffle whether the next round is shuffled (the order of the current round is already fixed)
     * @return the log row of this hand-out — its id is what the client knows the track by ({@code B:<id>}), so two
     *         plays of the same video (in two rounds) are two different entries — or empty if the playlist has no
     *         tracks to play (never imported / all cancelled)
     */
    @Transactional
    public Optional<FallbackPlayEntity> takeNextTrack(String partyCode, String playlistId, boolean shuffle) {
        lockQueue(partyCode);
        boolean newRoundStarted = false;
        for (int attempt = 0; attempt < MAX_TAKE_ATTEMPTS; attempt++) {
            List<FallbackTrackEntity> next = fallbackTrackRepository.findByPartyCodeAndPlaylistIdAndStatus(
                    partyCode, playlistId, QUEUED, PageRequest.of(0, 1, UPCOMING_ORDER));
            if (next.isEmpty()) {
                // Normally the queue is refilled as soon as its last track is handed out (below); this covers
                // a queue that is empty for another reason, e.g. it was emptied before this code existed.
                if (newRoundStarted || !startNewRound(partyCode, playlistId, shuffle, null)) {
                    return Optional.empty();
                }
                newRoundStarted = true;
                continue;
            }

            FallbackTrackEntity track = next.get(0);
            LocalDateTime now = LocalDateTime.now();
            int claimed = fallbackTrackRepository.claimQueuedTrack(track.getId(), QUEUED, PLAYED, now);
            if (claimed == 1) {
                FallbackPlayEntity play = fallbackPlayRepository.save(FallbackPlayEntity.builder()
                        .partyCode(partyCode)
                        .videoId(track.getVideoId())
                        .title(track.getTitle())
                        .fetchedAt(track.getFetchedAt())
                        .playedAt(now)
                        .build());
                if (fallbackTrackRepository.countByPartyCodeAndPlaylistIdAndStatus(partyCode, playlistId, QUEUED) == 0) {
                    startNewRound(partyCode, playlistId, shuffle, track.getId());
                }
                return Optional.of(play);
            }
            // lost the race for this track — pick again
        }
        return Optional.empty();
    }

    /**
     * Loops the playlist: re-queues the played tracks of the party's newest import — provided that import is
     * of this playlist (a superseded import is never revived, see the repository query) — and gives them a new
     * order.
     *
     * @param justPlayedId the track that was just handed out (and is playing now), or {@code null}
     * @return false if there was nothing to re-queue
     */
    private boolean startNewRound(String partyCode, String playlistId, boolean shuffle, Long justPlayedId) {
        int requeued = fallbackTrackRepository.requeuePlayedTracks(partyCode, playlistId, PLAYED, QUEUED);
        if (requeued == 0) {
            return false;
        }
        if (shuffle) {
            fallbackTrackRepository.shuffle(partyCode, playlistId, QUEUED.name());
            if (justPlayedId != null && requeued > 1) {
                keepOutOfFirstPlace(partyCode, playlistId, justPlayedId);
            }
        } else {
            fallbackTrackRepository.orderByPlaylistPosition(partyCode, playlistId, QUEUED, NO_ANCHOR, ROTATION);
        }
        log.debug("Party [{}]: fallback playlist {} finished — {} track(s) back in the queue ({})",
                partyCode, playlistId, requeued, shuffle ? "shuffled" : "playlist order");
        return true;
    }

    /** A new round must not open with the track that is still playing: if the shuffle put it first, it goes last. */
    private void keepOutOfFirstPlace(String partyCode, String playlistId, Long trackId) {
        List<FallbackTrackEntity> first = fallbackTrackRepository.findByPartyCodeAndPlaylistIdAndStatus(
                partyCode, playlistId, QUEUED, PageRequest.of(0, 1, UPCOMING_ORDER));
        if (!first.isEmpty() && trackId.equals(first.get(0).getId())) {
            fallbackTrackRepository.moveToEnd(trackId, QUEUED);
        }
    }

    /**
     * Deletes tracks — and the play log rows that copied their data — fetched more than
     * {@value FallbackTrackEntity#MAX_AGE_DAYS} days ago: YouTube API data must not be retained longer. Runs daily
     * at 04:30.
     */
    @Scheduled(cron = "0 30 4 * * *")
    @Transactional
    public void purgeStaleTracks() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(FallbackTrackEntity.MAX_AGE_DAYS);
        int deleted = fallbackTrackRepository.deleteFetchedBefore(cutoff);
        int deletedPlays = fallbackPlayRepository.deleteFetchedBefore(cutoff);
        if (deleted > 0 || deletedPlays > 0) {
            log.info("Fallback track cleanup: deleted {} track(s) and {} play log row(s) fetched more than {} days ago",
                    deleted, deletedPlays, FallbackTrackEntity.MAX_AGE_DAYS);
        }
    }
}
