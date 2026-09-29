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
        long remaining = fallbackTrackRepository
                .countByPartyCodeAndPlaylistIdAndStatus(partyCode, playlistId, FallbackTrackStatus.QUEUED);
        boolean manualOrder = fallbackTrackRepository.existsByPartyCodeAndPlaylistIdAndStatusAndManualMoveTrue(
                partyCode, playlistId, FallbackTrackStatus.QUEUED);
        return new FallbackQueueView(true, settings.isFallbackShuffle(), manualOrder, remaining, tracks);
    }

    /**
     * A short string that changes whenever {@link #getUpcoming} would return something different — a track taken,
     * a move, a new order, the shuffle switch, another playlist. The dashboard windows compare it on every lease
     * report and fetch the "up next" list again when it changes, so a change made in one window shows up in the
     * others (nothing else refreshes the list of a window that does not play).
     * <p>
     * It is a hash of the whole view (at most {@value #UPCOMING_LIMIT} tracks — the same bounded read the list
     * itself does), so it is only good for "did it change"; it does not survive a restart of the application.
     */
    public String getVersion(String partyCode) {
        return versionOf(getUpcoming(partyCode));
    }

    /** The version of a view that was already read — sent along with the list itself, so that its window knows it. */
    public static String versionOf(FallbackQueueView view) {
        return Integer.toHexString(view.hashCode());
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

    private static FallbackQueueView.Track toTrack(FallbackTrackEntity entity) {
        return new FallbackQueueView.Track(entity.getId(), entity.getVideoId(), entity.getTitle());
    }
}
