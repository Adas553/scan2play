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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    /** The upper 32 bits of every queue's lock key: "S2PQ", apart from any other use of the advisory-lock space. */
    private static final long QUEUE_LOCK_SPACE = 0x5332_5051L << 32;
    private static final int PARTY_CODE_LENGTH = 5;

    /**
     * The advisory-lock key of a party's queue. A party code (5 characters of [A-Z0-9], {@code CodeGenerator}) read as a number in
     * base 36 is below 36⁵ ≈ 60 million, so it fits the lower 32 bits whole: two parties never share a key (review item 1.7 —
     * the 32-bit {@code hashCode} used before could make two parties wait for each other). Any other string falls back to its hash.
     */
    static long queueLockKey(String partyCode) {
        if (partyCode != null && partyCode.length() == PARTY_CODE_LENGTH && partyCode.chars().allMatch(
                c -> (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))) {
            return QUEUE_LOCK_SPACE | Long.parseLong(partyCode, 36);
        }
        return QUEUE_LOCK_SPACE | (("fallback-queue:" + partyCode).hashCode() & 0xFFFF_FFFFL);
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

        int inserted = fallbackTrackRepository.insertTracks(partyCode, playlistId,
                tracks.stream().map(PlaylistTrack::videoId).toArray(String[]::new),
                tracks.stream().map(PlaylistTrack::title).toArray(String[]::new),
                QUEUED.name(), Instant.now());
        if (shuffle) {
            fallbackTrackRepository.shuffle(partyCode, playlistId, QUEUED.name());
        }

        log.info("Party [{}]: fallback playlist {} imported — {} track(s) queued ({}), {} previous track(s) cancelled",
                partyCode, playlistId, inserted, shuffle ? "shuffled" : "playlist order", cancelled);
        return inserted;
    }

    /**
     * Brings the party's current import of the playlist up to date with a fresh fetch <em>without</em> starting a new round
     * (review item 2.5 — a re-import cancelled the queue and put every track back, in the middle of a party): a video that is
     * still in the playlist keeps its row — its status, its place in the queue, the DJ's moves — and gets the new title,
     * playlist position and fetch time; a new video joins the end of the queue; a video gone from the playlist is cancelled.
     * Every row of the import then has the same fetch time again, which is what the next round looks for
     * ({@link FallbackTrackRepository#requeuePlayedTracks}), and the 30-day purge starts counting anew.
     * <p>
     * When the party's newest import is not of this playlist (nothing to keep), the tracks are imported as by
     * {@link #replaceTracks}.
     *
     * @param tracks the videos in playlist order, as fetched just now
     * @return number of tracks the playlist has now
     */
    @Transactional
    public int refreshTracks(String partyCode, String playlistId, List<PlaylistTrack> tracks, boolean shuffle) {
        lockQueue(partyCode);
        List<FallbackTrackEntity> current = fallbackTrackRepository.findNewestImport(
                partyCode, playlistId, CANCELLED, PageRequest.of(0, MAX_QUEUE));
        if (current.isEmpty()) {
            return replaceTracks(partyCode, playlistId, tracks, shuffle);
        }

        // The same video may be in a playlist twice: each fetched occurrence takes the next row of that video.
        Map<String, Deque<FallbackTrackEntity>> rowsByVideo = new HashMap<>();
        for (FallbackTrackEntity row : current) {
            rowsByVideo.computeIfAbsent(row.getVideoId(), v -> new ArrayDeque<>()).add(row);
        }
        List<Long> keptIds = new ArrayList<>();
        List<Integer> keptPositions = new ArrayList<>();
        List<String> keptTitles = new ArrayList<>();
        List<Integer> newPositions = new ArrayList<>();
        List<PlaylistTrack> newTracks = new ArrayList<>();
        for (int position = 0; position < tracks.size(); position++) {
            PlaylistTrack track = tracks.get(position);
            Deque<FallbackTrackEntity> rows = rowsByVideo.get(track.videoId());
            FallbackTrackEntity row = rows == null ? null : rows.poll();
            if (row != null) {
                keptIds.add(row.getId());
                keptPositions.add(position);
                keptTitles.add(track.title());
            } else {
                newPositions.add(position);
                newTracks.add(track);
            }
        }
        List<Long> goneIds = rowsByVideo.values().stream().flatMap(Deque::stream).map(FallbackTrackEntity::getId).toList();

        Instant now = Instant.now();
        fallbackTrackRepository.refreshTracks(keptIds.toArray(Long[]::new), keptPositions.toArray(Integer[]::new),
                keptTitles.toArray(String[]::new), now);
        if (!newTracks.isEmpty()) {
            fallbackTrackRepository.appendTracks(partyCode, playlistId,
                    newTracks.stream().map(PlaylistTrack::videoId).toArray(String[]::new),
                    newTracks.stream().map(PlaylistTrack::title).toArray(String[]::new),
                    newPositions.toArray(Integer[]::new), QUEUED.name(), now);
        }
        if (!goneIds.isEmpty()) {
            fallbackTrackRepository.updateStatusOf(goneIds, CANCELLED);
        }

        log.info("Party [{}]: fallback playlist {} refreshed in place — {} track(s) kept, {} added, {} gone from the playlist",
                partyCode, playlistId, keptIds.size(), newTracks.size(), goneIds.size());
        return tracks.size();
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
     *     <li>Two concurrent callers never get the same track: the queue's advisory lock makes them take turns (the
     *         second one reads the queue after the first one's commit), and the claim is a conditional UPDATE.</li>
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
        Optional<FallbackTrackEntity> next = firstQueued(partyCode, playlistId);
        if (next.isEmpty()) {
            // Normally the queue is refilled as soon as its last track is handed out (below); this covers
            // a queue that is empty for another reason, e.g. it was emptied before this code existed.
            if (!startNewRound(partyCode, playlistId, shuffle, null)) {
                return Optional.empty();
            }
            next = firstQueued(partyCode, playlistId);
            if (next.isEmpty()) {
                return Optional.empty();
            }
        }

        FallbackTrackEntity track = next.get();
        Instant now = Instant.now();
        // Under the queue's lock nobody can take the track between the read above and this update, so there is no race to
        // retry (review item 1.6); the condition on the status stays as a safety net for a change made without the lock.
        if (fallbackTrackRepository.claimQueuedTrack(track.getId(), QUEUED, PLAYED, now) != 1) {
            log.warn("Party [{}]: the next track {} was no longer queued — the queue changed without its lock?",
                    partyCode, track.getId());
            return Optional.empty();
        }
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

    /** The queued track that plays next, if any. */
    private Optional<FallbackTrackEntity> firstQueued(String partyCode, String playlistId) {
        return fallbackTrackRepository.findByPartyCodeAndPlaylistIdAndStatus(
                partyCode, playlistId, QUEUED, PageRequest.of(0, 1, UPCOMING_ORDER)).stream().findFirst();
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
        Instant cutoff = Instant.now().minus(FallbackTrackEntity.MAX_AGE_DAYS, ChronoUnit.DAYS);
        int deleted = fallbackTrackRepository.deleteFetchedBefore(cutoff);
        int deletedPlays = fallbackPlayRepository.deleteFetchedBefore(cutoff);
        if (deleted > 0 || deletedPlays > 0) {
            log.info("Fallback track cleanup: deleted {} track(s) and {} play log row(s) fetched more than {} days ago",
                    deleted, deletedPlays, FallbackTrackEntity.MAX_AGE_DAYS);
        }
    }
}
