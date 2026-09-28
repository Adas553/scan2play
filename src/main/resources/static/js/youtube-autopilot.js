/**
 * YouTube Auto-Pilot — automatic playback via YouTube IFrame Player API.
 *
 * Flow: on ENDED / at natural track boundaries, asks the backend "what's next?"
 *       (GET /dj/dashboard/next-guest-track) instead of scanning the queue table's DOM.
 *       The server is now the single source of truth for song ordering/eligibility
 *       (oldest accepted request with a resolvable YouTube video ID) — see
 *       PROJECT_CONTEXT.md Section 14. The client stays "dumb": it plays whatever video
 *       ID it's given via loadVideoById(), and still confirms playback itself via the
 *       existing POST /dj/dashboard/play once the video actually reaches PLAYING.
 *
 * Fallback: when the queue is empty and a fallback playlist/video is set, loads it via
 *           loadPlaylist(). This part is still client-side (Phase 2 in the roadmap moves
 *           it server-side too) — YouTube iterates/shuffles the playlist natively here,
 *           at zero YouTube Data API quota cost. Guest songs arriving during fallback are
 *           detected at natural track boundaries (ENDED for single videos, PLAYING for
 *           playlist auto-advance) and the player switches over — no polling watcher.
 *
 * Exposes: checkYouTubeAutoPlay, playInEmbeddedPlayer, updateFallbackSource,
 *          updateFallbackShuffle, stopFallback
 */
(function () {
    'use strict';

    // ---- State ----
    let player = null, playerReady = false, playerState = -1;
    let currentlyPlayingSongId = null, isLoadingSong = false;
    let isFallbackMode = false, fallbackIsVideo = false;
    let guestSongPending = false;
    let lastFallbackIndex = 0, fallbackTrackIndex = -1;
    // Video ID of the fallback track that is currently playing (see fallbackTrackChanged()).
    let fallbackVideoId = null;
    let pendingPlaylistSetup = false;
    let tryAutoPlayInFlight = false;
    // Guards against calling markAsPlayed() again if PLAYING re-fires for the same video
    // (e.g. a brief buffering stall, or the DJ manually pausing/resuming) without a new
    // song having been loaded in between.
    let lastMarkedPlayedId = null;
    // Songs the YouTube player itself errored on (video removed/private/region-blocked).
    // Session-only — passed to the server so it skips them too, otherwise it would keep
    // handing back the same broken "next" track forever (it still looks valid: accepted,
    // with a resolvable video ID — the player is what failed, not the URL shape).
    const erroredSongIds = new Set();

    // Bumped on every player state change; an async lookup checks it after
    // awaiting to detect that a newer event has already moved playback on
    // (e.g. the track ended naturally while we were still asking the server
    // about an early/BUFFERING-triggered guest-song switch) and bails out
    // instead of acting on a now-stale answer.
    let stateVersion = 0;

    const playerCard = document.getElementById('yt-player-card');
    let fallbackPlaylistId = playerCard ? (playerCard.getAttribute('data-fallback-playlist') || null) : null;
    let shuffleEnabled = playerCard ? playerCard.getAttribute('data-fallback-shuffle') === 'true' : true;

    const partyCodeEl = document.getElementById('partyCode');
    const partyCodeValue = partyCodeEl ? partyCodeEl.value : null;

    const csrf = {
        token:  document.querySelector('meta[name="_csrf"]').getAttribute('content'),
        header: document.querySelector('meta[name="_csrf_header"]').getAttribute('content')
    };

    // ---- YouTube IFrame API ----
    const tag = document.createElement('script');
    tag.src = 'https://www.youtube.com/iframe_api';
    document.head.appendChild(tag);

    window.onYouTubeIframeAPIReady = function () {
        player = new YT.Player('yt-player', {
            playerVars: { autoplay: 0, controls: 1, rel: 0 },
            events: {
                onReady:       function () { playerReady = true; tryAutoPlay(); },
                onStateChange: onPlayerStateChange,
                onError:       onPlayerError
            }
        });
    };

    // ---- Helpers ----

    function extractVideoId(url) {
        if (!url) return null;
        const m = url.match(/[?&]v=([A-Za-z0-9_-]{11})/);
        return m ? m[1] : null;
    }

    /** Video ID currently loaded in the player, or null while a new video is still loading. */
    function currentVideoId() {
        const data = player && player.getVideoData ? player.getVideoData() : null;
        return (data && data.video_id) || null;
    }

    /**
     * True when the fallback playlist has moved on to a different track.
     * getPlaylistIndex() alone is not reliable: with shuffle on it can change for the SAME video
     * (e.g. after the DJ seeks, once the shuffled order has been applied). Treating that as a
     * track change switched to the waiting guest song mid-track. A real change also changes the
     * video ID — or leaves it momentarily empty while the next video loads, which is what
     * UNSTARTED/BUFFERING report at a natural track boundary.
     */
    function fallbackTrackChanged(plIdx) {
        return fallbackTrackIndex >= 0
            && plIdx !== fallbackTrackIndex
            && (fallbackVideoId === null || currentVideoId() !== fallbackVideoId);
    }

    function resetPlayback() {
        isFallbackMode = false;
        fallbackIsVideo = false;
        guestSongPending = false;
        currentlyPlayingSongId = null;
        isLoadingSong = false;
        pendingPlaylistSetup = false;
    }

    /** Saves resume position from cached index (no API call), resets flags. */
    function exitFallback() {
        if (!fallbackIsVideo && fallbackTrackIndex >= 0) {
            lastFallbackIndex = fallbackTrackIndex + 1;
        }
        isFallbackMode = false;
        fallbackIsVideo = false;
        guestSongPending = false;
        currentlyPlayingSongId = null;
        isLoadingSong = false;
    }

    function markAsPlayed(songId) {
        const fd = new FormData();
        fd.append('id', songId);
        fetch('/dj/dashboard/play', {
            method: 'POST', headers: { [csrf.header]: csrf.token }, body: fd, redirect: 'manual'
        }).catch(e => console.error('[YT] markAsPlayed error:', e));
    }

    /**
     * Asks the backend for the next playable guest track. Read-only (see
     * DjService.findNextPlayableGuestTrack) — the client still confirms playback itself,
     * so calling this repeatedly (e.g. every few seconds while idle in fallback mode) is
     * safe. Returns null on "nothing waiting", on a network error, or when the server's
     * answer is the song we're already loading/playing (guards against two overlapping
     * lookups both trying to hand back the same not-yet-confirmed track).
     */
    async function fetchNextGuestTrack() {
        if (!partyCodeValue) return null;
        try {
            let url = '/dj/dashboard/next-guest-track?partyCode=' + encodeURIComponent(partyCodeValue);
            if (erroredSongIds.size > 0) {
                url += '&exclude=' + Array.from(erroredSongIds).join(',');
            }
            const response = await fetch(url, { headers: { [csrf.header]: csrf.token } });
            if (response.status === 204 || !response.ok) return null;
            const track = await response.json();
            if (!track || !track.videoId || track.songId === currentlyPlayingSongId) return null;
            return track;
        } catch (e) {
            console.error('[YT] fetchNextGuestTrack error:', e);
            return null;
        }
    }

    function isAutoPilotOn() {
        const tbody = document.getElementById('song-list');
        return tbody && tbody.getAttribute('data-playback-mode') === 'AUTO';
    }

    // ---- Event Handlers ----

    async function onPlayerStateChange(event) {
        playerState = event.data;
        const myVersion = ++stateVersion;

        // ---- BUFFERING: early detection of playlist auto-advance ----
        if (event.data === YT.PlayerState.BUFFERING
                && isFallbackMode && !fallbackIsVideo
                && guestSongPending && isAutoPilotOn()) {
            const bufIdx = player.getPlaylistIndex();
            if (bufIdx >= 0 && fallbackTrackChanged(bufIdx)) {
                const earlyGuest = await fetchNextGuestTrack();
                if (myVersion !== stateVersion) return; // superseded by a newer event
                if (earlyGuest) {
                    exitFallback();
                    playGuestSong(earlyGuest);
                    return;
                }
                guestSongPending = false;
            }
        }

        if (event.data === YT.PlayerState.PLAYING) {
            isLoadingSong = false;

            // Mark guest songs as played (once per song, see lastMarkedPlayedId above)
            if (!isFallbackMode && currentlyPlayingSongId && currentlyPlayingSongId !== lastMarkedPlayedId) {
                lastMarkedPlayedId = currentlyPlayingSongId;
                markAsPlayed(currentlyPlayingSongId);
            }

            // ---- Fallback playlist: track index + guest-song switch ----
            if (isFallbackMode && !fallbackIsVideo) {
                const plIdx = player.getPlaylistIndex();
                if (plIdx >= 0) {
                    const trackChanged = fallbackTrackChanged(plIdx);

                    // Auto-Pilot OFF: just track position, let playlist play
                    if (!isAutoPilotOn() && trackChanged) {
                        lastFallbackIndex = plIdx;
                    }

                    // Guest song pending + natural track boundary → switch now.
                    // The new playlist track just started — loadVideoById() overrides
                    // it immediately. No stopVideo/pauseVideo needed: YouTube tears
                    // down a barely-initialized video (fast) instead of a fully
                    // loaded one (heavy).
                    if (isAutoPilotOn() && guestSongPending && trackChanged) {
                        const nextGuest = await fetchNextGuestTrack();
                        if (myVersion !== stateVersion) return; // superseded by a newer event
                        if (nextGuest) {
                            exitFallback();
                            playGuestSong(nextGuest);
                            return;
                        }
                        guestSongPending = false;
                    }

                    fallbackTrackIndex = plIdx;
                    fallbackVideoId = currentVideoId() || fallbackVideoId;
                }
            }

            // Apply shuffle + loop on first PLAYING after loadPlaylist()
            if (pendingPlaylistSetup && isFallbackMode && !fallbackIsVideo) {
                pendingPlaylistSetup = false;
                player.setShuffle(shuffleEnabled);
                player.setLoop(true);
            }
        }

        if (event.data === YT.PlayerState.ENDED) {
            if (isFallbackMode) await handleFallbackEnded(myVersion);
            else                 handleGuestSongEnded();
        }
    }

    async function handleFallbackEnded(myVersion) {
        const nextGuest = isAutoPilotOn() ? await fetchNextGuestTrack() : null;
        if (myVersion !== stateVersion) return; // superseded by a newer event

        if (nextGuest) {
            exitFallback();
            playGuestSong(nextGuest);
        } else if (!isAutoPilotOn()) {
            player.stopVideo();
            playerState = -1;
            isFallbackMode = false;
            fallbackIsVideo = false;
            guestSongPending = false;
        } else if (fallbackIsVideo) {
            player.seekTo(0);
            player.playVideo();
        }
        // else: playlist auto-advances to next track
    }

    function handleGuestSongEnded() {
        if (isLoadingSong) return; // stale ENDED during transition
        currentlyPlayingSongId = null;
        isLoadingSong = false;
        tryAutoPlay();
    }

    function onPlayerError(event) {
        console.error('[YT] Player error ' + event.data + ' for ID=' + currentlyPlayingSongId);
        if (currentlyPlayingSongId) erroredSongIds.add(currentlyPlayingSongId);
        resetPlayback();
    }

    // ---- Core Playback ----

    /** @param track {{songId: number, videoId: string}} */
    function playGuestSong(track) {
        if (!track || !track.videoId) return;

        isLoadingSong = true;
        currentlyPlayingSongId = track.songId;
        isFallbackMode = false;
        player.loadVideoById(track.videoId);
    }

    function startFallbackPlaylist() {
        if (!fallbackPlaylistId) return;
        isFallbackMode = true;
        guestSongPending = false;
        currentlyPlayingSongId = null;
        isLoadingSong = false;

        if (fallbackPlaylistId.startsWith('V:')) {
            player.loadVideoById({ videoId: fallbackPlaylistId.substring(2) });
            fallbackIsVideo = true;
            pendingPlaylistSetup = false;
        } else {
            // Forget the previous playlist session's track, so the first PLAYING after this
            // load is not compared against a stale index/video from before the guest song.
            fallbackTrackIndex = -1;
            fallbackVideoId = null;
            player.loadPlaylist({ list: fallbackPlaylistId, listType: 'playlist', index: lastFallbackIndex });
            fallbackIsVideo = false;
            pendingPlaylistSetup = true;
        }
    }

    /** Main entry point — called by polling and on player ready. */
    async function tryAutoPlay() {
        if (!playerReady || !player || isLoadingSong || tryAutoPlayInFlight) return;
        if (!isAutoPilotOn()) return;
        tryAutoPlayInFlight = true;

        try {
            const isActive = playerState === YT.PlayerState.PLAYING
                          || playerState === YT.PlayerState.BUFFERING
                          || playerState === YT.PlayerState.PAUSED;

            if (isActive) {
                if (isFallbackMode) {
                    const nextGuest = await fetchNextGuestTrack();
                    if (!isFallbackMode) return; // a player-state event already moved us on
                    if (nextGuest) {
                        if (!guestSongPending) guestSongPending = true;
                        // Paused fallback → switch immediately (no track boundary needed)
                        if (playerState === YT.PlayerState.PAUSED) {
                            exitFallback();
                            playGuestSong(nextGuest);
                        }
                        // Playing/buffering → guestSongPending flag is set;
                        // PLAYING handler switches at the next track boundary.
                    }
                    return;
                }
                if (currentlyPlayingSongId) return; // guest song playing

                // Orphaned playback — pause it, fall through to find next song
                player.pauseVideo();
                isFallbackMode = false;
            }

            const guest = await fetchNextGuestTrack();
            if (isLoadingSong || currentlyPlayingSongId) return; // superseded meanwhile
            if (guest) {
                if (isFallbackMode) isFallbackMode = false;
                playGuestSong(guest);
            } else if (fallbackPlaylistId && !isFallbackMode) {
                startFallbackPlaylist();
            }
        } finally {
            tryAutoPlayInFlight = false;
        }
    }

    // ---- Public API ----

    window.checkYouTubeAutoPlay = tryAutoPlay;

    window.updateFallbackSource = function (extractedId) {
        const newId = extractedId || null;
        if (isFallbackMode && player) { player.stopVideo(); playerState = -1; }
        resetPlayback();
        lastFallbackIndex = 0;
        fallbackPlaylistId = newId;
        if (playerCard) {
            if (newId) playerCard.setAttribute('data-fallback-playlist', newId);
            else       playerCard.removeAttribute('data-fallback-playlist');
        }
        if (newId) tryAutoPlay();
    };

    window.stopFallback = function () {
        if (isFallbackMode && player) { player.stopVideo(); playerState = -1; }
        resetPlayback();
        fallbackPlaylistId = null;
        lastFallbackIndex = 0;
        if (playerCard) playerCard.removeAttribute('data-fallback-playlist');
    };

    window.updateFallbackShuffle = function (enabled) {
        shuffleEnabled = !!enabled;
        if (playerCard) playerCard.setAttribute('data-fallback-shuffle', String(shuffleEnabled));
        if (isFallbackMode && !fallbackIsVideo && player) player.setShuffle(shuffleEnabled);
    };

    window.playInEmbeddedPlayer = function (trackUrl) {
        if (!playerReady || !player) return false;
        const videoId = extractVideoId(trackUrl);
        if (!videoId) return false;
        if (isFallbackMode && !fallbackIsVideo && fallbackTrackIndex >= 0) lastFallbackIndex = fallbackTrackIndex + 1;
        resetPlayback();
        player.loadVideoById(videoId);
        return true;
    };

})();

// ---- Click handler: ▶ YOUTUBE links play in embedded player ----
document.addEventListener('click', function (e) {
    const link = e.target.closest('a.play-link');
    if (!link) return;
    const url = link.getAttribute('data-track-url');
    if (!url || url.indexOf('youtube.com') < 0) return;
    if (typeof window.playInEmbeddedPlayer === 'function' && window.playInEmbeddedPlayer(url)) {
        e.preventDefault();
    }
});
