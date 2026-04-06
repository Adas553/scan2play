/**
 * YouTube Auto-Pilot — automatic playback via YouTube IFrame Player API.
 *
 * Flow: polling (dashboard.js, 3s) refreshes #song-list → checkYouTubeAutoPlay()
 *       scans for accepted songs → plays via loadVideoById() → marks PLAYED on PLAYING
 *       → on ENDED resets and checks next.
 *
 * Fallback: when queue is empty and a fallback playlist/video is set, loads it via
 *           loadPlaylist(). Guest songs arriving during fallback are detected at natural
 *           track boundaries (ENDED for single videos, PLAYING for playlist auto-advance)
 *           and the player switches to the guest song — no polling watcher needed.
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
    let pendingPlaylistSetup = false;
    const markedAsPlayedIds = new Set(), skippedSongIds = new Set();

    const playerCard = document.getElementById('yt-player-card');
    let fallbackPlaylistId = playerCard ? (playerCard.getAttribute('data-fallback-playlist') || null) : null;
    let shuffleEnabled = playerCard ? playerCard.getAttribute('data-fallback-shuffle') === 'true' : true;

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

    let lastPruneTime = 0;
    function pruneStaleIds() {
        const now = Date.now();
        if (now - lastPruneTime < 60000) return;
        lastPruneTime = now;
        const tbody = document.getElementById('song-list');
        if (!tbody) return;
        const liveIds = new Set();
        tbody.querySelectorAll('tr[data-song-id]').forEach(r => liveIds.add(r.getAttribute('data-song-id')));
        markedAsPlayedIds.forEach(id => { if (!liveIds.has(id)) markedAsPlayedIds.delete(id); });
        skippedSongIds.forEach(id => { if (!liveIds.has(id)) skippedSongIds.delete(id); });
    }

    function markAsPlayed(songId) {
        const fd = new FormData();
        fd.append('id', songId);
        fetch('/dj/dashboard/play', {
            method: 'POST', headers: { [csrf.header]: csrf.token }, body: fd, redirect: 'manual'
        }).catch(e => console.error('[YT] markAsPlayed error:', e));
    }

    function findNextGuestSong() {
        const tbody = document.getElementById('song-list');
        if (!tbody) return null;
        const rows = tbody.querySelectorAll('tr[data-song-id]');
        for (let i = 0; i < rows.length; i++) {
            const songId = rows[i].getAttribute('data-song-id');
            if (!songId || markedAsPlayedIds.has(songId) || skippedSongIds.has(songId)) continue;
            if (!extractVideoId(rows[i].getAttribute('data-track-url'))) { skippedSongIds.add(songId); continue; }
            return rows[i];
        }
        return null;
    }

    function isAutoPilotOn() {
        const tbody = document.getElementById('song-list');
        return tbody && tbody.getAttribute('data-playback-mode') === 'AUTO';
    }

    // ---- Event Handlers ----

    function onPlayerStateChange(event) {
        playerState = event.data;

        // ---- BUFFERING: early detection of playlist auto-advance ----
        if (event.data === YT.PlayerState.BUFFERING
                && isFallbackMode && !fallbackIsVideo
                && guestSongPending && isAutoPilotOn()) {
            const bufIdx = player.getPlaylistIndex();
            if (bufIdx >= 0 && fallbackTrackIndex >= 0 && bufIdx !== fallbackTrackIndex) {
                const earlyGuest = findNextGuestSong();
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

            // Mark guest songs as played
            if (!isFallbackMode && currentlyPlayingSongId && !markedAsPlayedIds.has(currentlyPlayingSongId)) {
                markedAsPlayedIds.add(currentlyPlayingSongId);
                markAsPlayed(currentlyPlayingSongId);
            }

            // ---- Fallback playlist: track index + guest-song switch ----
            if (isFallbackMode && !fallbackIsVideo) {
                const plIdx = player.getPlaylistIndex();
                if (plIdx >= 0) {
                    const trackChanged = fallbackTrackIndex >= 0 && plIdx !== fallbackTrackIndex;

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
                        const nextGuest = findNextGuestSong();
                        if (nextGuest) {
                            exitFallback();
                            playGuestSong(nextGuest);
                            return;
                        }
                        guestSongPending = false;
                    }

                    fallbackTrackIndex = plIdx;
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
            if (isFallbackMode) handleFallbackEnded();
            else                handleGuestSongEnded();
        }
    }

    function handleFallbackEnded() {
        const nextGuest = isAutoPilotOn() ? findNextGuestSong() : null;

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
        if (currentlyPlayingSongId) skippedSongIds.add(currentlyPlayingSongId);
        resetPlayback();
    }

    // ---- Core Playback ----

    function playGuestSong(row) {
        const songId = row.getAttribute('data-song-id');
        const videoId = extractVideoId(row.getAttribute('data-track-url'));
        if (!videoId) return;

        isLoadingSong = true;
        currentlyPlayingSongId = songId;
        isFallbackMode = false;
        player.loadVideoById(videoId);
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
            player.loadPlaylist({ list: fallbackPlaylistId, listType: 'playlist', index: lastFallbackIndex });
            fallbackIsVideo = false;
            pendingPlaylistSetup = true;
        }
    }

    /** Main entry point — called by polling and on player ready. */
    function tryAutoPlay() {
        if (!playerReady || !player || isLoadingSong) return;
        pruneStaleIds();
        if (!isAutoPilotOn()) return;

        const isActive = playerState === YT.PlayerState.PLAYING
                      || playerState === YT.PlayerState.BUFFERING
                      || playerState === YT.PlayerState.PAUSED;

        if (isActive) {
            if (isFallbackMode) {
                const nextGuest = findNextGuestSong();
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

        const guest = findNextGuestSong();
        if (guest) {
            if (isFallbackMode) isFallbackMode = false;
            playGuestSong(guest);
        } else if (fallbackPlaylistId && !isFallbackMode) {
            startFallbackPlaylist();
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
