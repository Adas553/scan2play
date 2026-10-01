package com.scan2play.service;

import com.scan2play.entity.FallbackTrackEntity;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.FallbackQueueView;
import com.scan2play.model.FallbackTrackStatus;
import com.scan2play.model.MoveDirection;
import com.scan2play.repository.FallbackTrackRepository;
import com.scan2play.util.YouTubeUrls;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

import static com.scan2play.repository.FallbackTrackRepository.UPCOMING_ORDER;

/**
 * The fallback playlist as the DJ dashboard sees it: what plays next, and after that, for the rest of the round —
 * and the DJ's moves within that order.
 * <p>
 * The list is in the same order {@link FallbackTrackCommandService#takeNextTrack} hands tracks out in, so what the
 * DJ sees is what will play (after any waiting guest song). Listing changes nothing; {@link #moveTrack} does.
 */
@Service
@RequiredArgsConstructor
public class FallbackQueueService {

    /**
     * How many upcoming tracks the dashboard lists: the whole round. A playlist is imported with at most
     * {@value YouTubePlaylistClient#MAX_TRACKS} tracks, so this is never truncated; the dashboard scrolls the list.
     */
    static final int UPCOMING_LIMIT = YouTubePlaylistClient.MAX_TRACKS;

    private final PartySettingsQueryService partySettingsQueryService;
    private final FallbackTrackRepository fallbackTrackRepository;
    private final FallbackTrackCommandService fallbackTrackCommandService;

    @Transactional(readOnly = true)
    public FallbackQueueView getUpcoming(String partyCode) {
        PartySettingsEntity settings = partySettingsQueryService.getSettings(partyCode);
        String playlistId = YouTubeUrls.extractPlaylistId(settings.getFallbackPlaylistUrl());
        if (playlistId == null) {
            return FallbackQueueView.noPlaylist(settings.isFallbackShuffle());
        }

        List<FallbackQueueView.Track> tracks = fallbackTrackRepository
                .findByPartyCodeAndPlaylistIdAndStatus(partyCode, playlistId, FallbackTrackStatus.QUEUED,
                        PageRequest.of(0, UPCOMING_LIMIT, UPCOMING_ORDER))
                .stream()
                .map(FallbackQueueService::toTrack)
                .toList();
        // The list is the whole round (the limit is the most an import holds, and nothing adds to a round), so its size is
        // everything still queued — no count of its own (review item 1.9).
        long remaining = tracks.size();
        boolean manualOrder = fallbackTrackRepository.existsByPartyCodeAndPlaylistIdAndStatusAndManualMoveTrue(
                partyCode, playlistId, FallbackTrackStatus.QUEUED);
        long skipped = fallbackTrackRepository
                .countByPartyCodeAndPlaylistIdAndStatus(partyCode, playlistId, FallbackTrackStatus.SKIPPED);
        boolean singleVideo = playlistId.startsWith(YouTubeUrls.SINGLE_VIDEO_PREFIX);   // a video link, not a playlist
        return new FallbackQueueView(true, settings.isFallbackShuffle(), manualOrder, remaining, skipped, singleVideo, tracks);
    }

    /**
     * A short string that changes whenever {@link #getUpcoming} would return something different — a track taken,
     * a move, a new order, the shuffle switch, another playlist. The dashboard windows compare it on every lease
     * report and fetch the "up next" list again when it changes, so a change made in one window shows up in the
     * others (nothing else refreshes the list of a window that does not play).
     * <p>
     * It is asked for on every lease report (every 3 s from every window), so it reads no track: the database hashes the
     * queue ({@link FallbackTrackRepository#queueFingerprint}) and this adds the playlist and the shuffle setting. Only
     * good for "did it change". The titles are not in it: a track's title never changes (a new import is new rows).
     */
    public String getVersion(String partyCode) {
        PartySettingsEntity settings = partySettingsQueryService.getSettings(partyCode);
        String playlistId = YouTubeUrls.extractPlaylistId(settings.getFallbackPlaylistUrl());
        String queue = playlistId == null ? "" : fallbackTrackRepository.queueFingerprint(partyCode, playlistId,
                FallbackTrackStatus.QUEUED.name(), FallbackTrackStatus.SKIPPED.name());
        return Integer.toHexString(Objects.hash(playlistId, settings.isFallbackShuffle(), queue));
    }

    /**
     * The DJ moves a track of the queue.
     *
     * @return true if the track was queued in the party's current playlist (it was moved, or was already at that
     *         end); false if it cannot be moved — unknown, not this party's, or already taken by the player
     */
    public boolean moveTrack(String partyCode, Long trackId, MoveDirection direction) {
        PartySettingsEntity settings = partySettingsQueryService.getSettings(partyCode);
        String playlistId = YouTubeUrls.extractPlaylistId(settings.getFallbackPlaylistUrl());
        if (playlistId == null) {
            return false;
        }
        return fallbackTrackCommandService.moveTrack(partyCode, playlistId, trackId, direction);
    }

    /**
     * The DJ drags a track to a new place in the queue: in front of {@code beforeTrackId}, or to the end when that is
     * {@code null}.
     *
     * @return true if it was placed (or was already there); false if it cannot be — a track is no longer queued in the
     *         party's current playlist
     */
    public boolean placeTrack(String partyCode, Long trackId, Long beforeTrackId) {
        PartySettingsEntity settings = partySettingsQueryService.getSettings(partyCode);
        String playlistId = YouTubeUrls.extractPlaylistId(settings.getFallbackPlaylistUrl());
        if (playlistId == null) {
            return false;
        }
        return fallbackTrackCommandService.placeTrack(partyCode, playlistId, trackId, beforeTrackId);
    }

    /**
     * The DJ skips a track of the queue for this round: it comes back in the playlist's next round.
     *
     * @return true if the track was queued in the party's current playlist and is skipped now; false if it cannot be
     *         skipped — unknown, not this party's, or already taken by the player
     */
    public boolean skipTrack(String partyCode, Long trackId) {
        PartySettingsEntity settings = partySettingsQueryService.getSettings(partyCode);
        String playlistId = YouTubeUrls.extractPlaylistId(settings.getFallbackPlaylistUrl());
        if (playlistId == null) {
            return false;
        }
        return fallbackTrackCommandService.skipTrack(partyCode, playlistId, trackId, settings.isFallbackShuffle());
    }

    private static FallbackQueueView.Track toTrack(FallbackTrackEntity entity) {
        return new FallbackQueueView.Track(entity.getId(), entity.getVideoId(), entity.getTitle());
    }
}
