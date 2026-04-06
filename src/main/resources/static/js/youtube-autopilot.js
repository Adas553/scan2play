/**
 * YouTube Auto-Pilot — automatic playback via YouTube IFrame Player API.
 *
 * State Machine:
 *   IDLE → (find accepted song) → LOADING → PLAYING → ENDED → IDLE
 *        → (queue empty + fallback set) → FALLBACK_PLAYING → ENDED →
 *           → (guest song arrived?) → LOADING (guest) ...
 *           → (still empty?)       → continue fallback playlist
 *
 * How it works:
 *   1. Polling (dashboard.js) refreshes the <tbody id="song-list"> every 3s
 *   2. After each refresh, checkYouTubeAutoPlay() is called
 *   3. Auto-Pilot scans the table for the oldest accepted song with a valid video URL
 *   4. Loads the video in the embedded player via loadVideoById()
 *   5. On PLAYING state, marks the song as PLAYED via fetch POST
 *   6. On ENDED state, resets and waits for the next poll cycle
 *
 * Fallback Playlist (background music):
 *   - When the guest queue is empty and a fallback playlist ID is configured,
 *     Auto-Pilot loads the playlist via player.loadPlaylist()
 *   - If a fallback song is playing and a guest request arrives,
 *     the player waits for the current track to naturally end (within 1.5s),
 *     then switches to the guest queue. A 500ms polling interval checks
 *     getCurrentTime() vs getDuration() to reliably detect track endings
 *     (YouTube playlists don't fire ENDED between tracks).
 *   - Playlist position is saved on exit and resumed on re-entry,
 *     so the DJ doesn't hear the same first song every time.
 *   - When the guest queue empties again, fallback resumes from where it left off.
 *   - Loop is always enabled on fallback playlists. Shuffle is configurable
 *     by the DJ via a toggle on the dashboard. Both are applied via
 *     setShuffle() / setLoop(true) on first PLAYING event after loadPlaylist().
 *
 * Quota optimization:
 *   Video URLs are resolved server-side via YouTube Data API v3 with 24h caching.
 *   Each unique song costs 100 quota units only once per day.
 *
 * Dependencies (DOM):
 *   - <div id="yt-player">            YouTube IFrame container
 *   - <div id="yt-player-card">       data-fallback-playlist, data-fallback-shuffle attributes
 *   - <meta name="_csrf">             CSRF token
 *   - <meta name="_csrf_header">      CSRF header name
 *   - <tbody id="song-list">          with data-playback-mode attribute
 *   - Each <tr> has: data-song-id, data-song-name, data-track-url
 *
 * Exposes (global):
 *   - window.checkYouTubeAutoPlay      — called by polling after table refresh
 *   - window.playInEmbeddedPlayer(url) — called by ▶ YOUTUBE link click handler
 *   - window.updateFallbackSource(id)  — called after AJAX save with server-extracted ID
 *   - window.updateFallbackShuffle(b)  — called after DJ toggles the shuffle checkbox
 *   - window.stopFallback()            — called by the Stop button on dashboard
 */
(function() {
    'use strict';

    // ---- State ----
    let player = null;
    let playerReady = false;
    let playerState = -1;                // YT.PlayerState.UNSTARTED
    let currentlyPlayingSongId = null;
    let isLoadingSong = false;
    let isFallbackMode = false;          // true when playing from the fallback playlist
    let fallbackIsVideo = false;         // true when fallback is a single video (loop mode)
    let guestSongPending = false;        // true when a guest song arrived during fallback playback
    let guestSongCheckInterval = null;   // 500ms interval for detecting fallback track end
    let lastFallbackIndex = 0;           // resume position in fallback playlist
    let fallbackTrackIndex = -1;         // current playlist index — for detecting auto-advance
    let pendingPlaylistSetup = false;    // true after loadPlaylist() — apply shuffle+loop on first PLAYING event
    const markedAsPlayedIds = new Set();
    const skippedSongIds   = new Set();  // Songs without a valid video URL

    // ---- Fallback Playlist ID & Shuffle (mutable — updated via window.updateFallbackSource / toggleShuffle) ----
    const playerCard = document.getElementById('yt-player-card');
    let fallbackPlaylistId = playerCard ? (playerCard.getAttribute('data-fallback-playlist') || null) : null;
    let shuffleEnabled = playerCard ? (playerCard.getAttribute('data-fallback-shuffle') === 'true') : true;

    // ---- CSRF (reuse from meta tags) ----
    const csrf = {
        token:  document.querySelector('meta[name="_csrf"]').getAttribute('content'),
        header: document.querySelector('meta[name="_csrf_header"]').getAttribute('content')
    };

    // ---- Load YouTube IFrame API ----
    const tag = document.createElement('script');
    tag.src = 'https://www.youtube.com/iframe_api';
    document.head.appendChild(tag);

    window.onYouTubeIframeAPIReady = function() {
        player = new YT.Player('yt-player', {
            playerVars: { autoplay: 0, controls: 1, rel: 0 },
            events: {
                onReady:       function() { playerReady = true; console.log('[YT Auto-Pilot] Player ready'); tryAutoPlay(); },
                onStateChange: onPlayerStateChange,
                onError:       onPlayerError
            }
        });
    };

    // ---- Helpers ----

    /**
     * Extracts the 11-char video ID from a YouTube watch URL.
     * Returns null for search URLs, null values, or invalid formats.
     */
    function extractVideoId(url) {
        if (!url) return null;
        const m = url.match(/[?&]v=([A-Za-z0-9_-]{11})/);
        return m ? m[1] : null;
    }

    /**
     * Removes stale IDs from markedAsPlayedIds and skippedSongIds that are
     * no longer present in the queue table. Prevents unbounded Set growth
     * during long party sessions (8+ hours). Called periodically from tryAutoPlay.
     */
    let lastPruneTime = 0;
    function pruneStaleIds() {
        const now = Date.now();
        if (now - lastPruneTime < 60000) return; // at most once per minute
        lastPruneTime = now;

        const tbody = document.getElementById('song-list');
        if (!tbody) return;
        const currentIds = new Set();
        const rows = tbody.querySelectorAll('tr[data-song-id]');
        for (let i = 0; i < rows.length; i++) {
            currentIds.add(rows[i].getAttribute('data-song-id'));
        }
        for (const id of markedAsPlayedIds) {
            if (!currentIds.has(id)) markedAsPlayedIds.delete(id);
        }
        for (const id of skippedSongIds) {
            if (!currentIds.has(id)) skippedSongIds.delete(id);
        }
    }

    /** POST to mark a song as played in the database. */
    function markAsPlayed(songId) {
        const fd = new FormData();
        fd.append('id', songId);
        fetch('/dj/dashboard/play', {
            method: 'POST',
            headers: { [csrf.header]: csrf.token },
            body: fd,
            redirect: 'manual'
        }).catch(function(err) { console.error('[YT Auto-Pilot] Mark-as-played error:', err); });
    }

    /**
     * Scans the queue table and returns the first playable guest song row, or null.
     * Does NOT start playback — used for lookahead checks.
     */
    function findNextGuestSong() {
        const tbody = document.getElementById('song-list');
        if (!tbody) return null;

        const rows = tbody.querySelectorAll('tr[data-song-id]');
        for (let i = 0; i < rows.length; i++) {
            const row = rows[i];
            const songId = row.getAttribute('data-song-id');
            if (!songId) continue;
            if (markedAsPlayedIds.has(songId) || skippedSongIds.has(songId)) continue;

            const videoId = extractVideoId(row.getAttribute('data-track-url'));
            if (!videoId) {
                skippedSongIds.add(songId);
                continue;
            }
            return row; // first playable song found
        }
        return null;
    }

    // ---- Player Event Handlers ----

    function onPlayerStateChange(event) {
        playerState = event.data;

        if (event.data === YT.PlayerState.PLAYING) {
            isLoadingSong = false;

            // Mark guest songs as played (not fallback songs)
            if (!isFallbackMode && currentlyPlayingSongId && !markedAsPlayedIds.has(currentlyPlayingSongId)) {
                markedAsPlayedIds.add(currentlyPlayingSongId);
                markAsPlayed(currentlyPlayingSongId);
                console.log('[YT Auto-Pilot] Now playing, marked ID=' + currentlyPlayingSongId + ' as PLAYED');
            }

            // ---- Playlist auto-advance guard ----
            // YouTube keeps a playlist loaded even after stopVideo(), so if the DJ
            // clicks the native play button the playlist resumes and auto-advances
            // between tracks. This guard runs REGARDLESS of isFallbackMode to catch
            // unmanaged playlist playback when Auto-Pilot is OFF.
            // It also handles the original case when isFallbackMode is still true.
            const currentPlaylistIndex = (typeof player.getPlaylistIndex === 'function')
                    ? player.getPlaylistIndex() : -1;

            if (currentPlaylistIndex >= 0 && !fallbackIsVideo) {
                const tbody = document.getElementById('song-list');
                const autoPilotOn = tbody && tbody.getAttribute('data-playback-mode') === 'AUTO';
                const indexChanged = fallbackTrackIndex >= 0 && currentPlaylistIndex !== fallbackTrackIndex;

                if (!autoPilotOn && indexChanged && !currentlyPlayingSongId) {
                    console.log('[YT Auto-Pilot] Playlist auto-advanced with Auto-Pilot OFF — stopping');
                    // Resume FROM this track when Auto-Pilot is re-enabled (don't skip it)
                    lastFallbackIndex = currentPlaylistIndex;
                    // Update tracked index so DJ can manually press play without re-triggering the guard
                    fallbackTrackIndex = currentPlaylistIndex;
                    player.stopVideo();
                    playerState = -1;
                    isFallbackMode = false;
                    fallbackIsVideo = false;
                    guestSongPending = false;
                    stopGuestSongWatcher();
                    return;
                }

                // Track playlist index for next auto-advance detection
                fallbackTrackIndex = currentPlaylistIndex;
            }

            // ---- Apply shuffle + loop once playlist is loaded and playing ----
            // setShuffle() and setLoop() are only accepted by the YouTube API
            // AFTER the playlist is fully loaded — calling them right after
            // loadPlaylist() is unreliable. We defer to the first PLAYING event.
            if (pendingPlaylistSetup && isFallbackMode && !fallbackIsVideo) {
                pendingPlaylistSetup = false;
                player.setShuffle(shuffleEnabled);
                player.setLoop(true);
                console.log('[YT Auto-Pilot] Playlist setup: shuffle=' + shuffleEnabled + ', loop=true');
            }

            if (isFallbackMode) {
                console.log('[YT Auto-Pilot] Fallback playlist track playing');
            }
        }

        if (event.data === YT.PlayerState.ENDED) {
            if (isFallbackMode) {
                // Check if Auto-Pilot is still enabled before switching to guest songs
                const tbody = document.getElementById('song-list');
                const autoPilotOn = tbody && tbody.getAttribute('data-playback-mode') === 'AUTO';

                const nextGuest = autoPilotOn ? findNextGuestSong() : null;
                if (nextGuest) {
                    // Guest song arrived! Save position, exit fallback, play guest.
                    // No stopVideo() needed — video already ended, and loadVideoById()
                    // in playGuestSong() handles the transition.
                    console.log('[YT Auto-Pilot] Fallback song ended, guest song waiting — switching to guest queue');
                    saveFallbackPosition();
                    stopGuestSongWatcher();
                    isFallbackMode = false;
                    fallbackIsVideo = false;
                    guestSongPending = false;
                    currentlyPlayingSongId = null;
                    isLoadingSong = false;
                    playGuestSong(nextGuest);
                } else if (!autoPilotOn) {
                    // Auto-Pilot OFF — track finished naturally, don't auto-advance
                    console.log('[YT Auto-Pilot] Fallback track ended, Auto-Pilot OFF — stopping');
                    saveFallbackPosition();
                    stopGuestSongWatcher();
                    player.stopVideo();
                    playerState = -1;
                    isFallbackMode = false;
                    fallbackIsVideo = false;
                    guestSongPending = false;
                } else if (fallbackIsVideo) {
                    // Single video fallback — loop it
                    console.log('[YT Auto-Pilot] Fallback video ended, replaying (loop)');
                    player.seekTo(0);
                    player.playVideo();
                } else {
                    // Queue still empty — let YouTube auto-advance to next playlist track
                    console.log('[YT Auto-Pilot] Fallback song ended, queue still empty — continuing playlist');
                }
            } else {
                // Guard: if a new song is already loading (e.g. the watcher just
                // called playGuestSong and this ENDED is a stale event from the
                // preceding stopVideo/transition), ignore — the new playback will
                // manage itself.  Without this guard, stopVideo() here kills the
                // newly loaded video, creating a destructive stop→load→stop cycle
                // that can corrupt the YouTube IFrame (invisible player).
                if (isLoadingSong) return;

                // Guest song ended — reset and try next immediately
                player.stopVideo();
                currentlyPlayingSongId = null;
                isLoadingSong = false;
                console.log('[YT Auto-Pilot] Song ended, checking for next song');
                tryAutoPlay();
            }
        }
    }

    function onPlayerError(event) {
        console.error('[YT Auto-Pilot] Player error (code=' + event.data + ') for ID=' + currentlyPlayingSongId);
        if (currentlyPlayingSongId) skippedSongIds.add(currentlyPlayingSongId);
        currentlyPlayingSongId = null;
        isLoadingSong = false;
        isFallbackMode = false;
        fallbackIsVideo = false;
        pendingPlaylistSetup = false;
        stopGuestSongWatcher();
    }



    /** Saves the current fallback playlist index so we can resume later. */
    function saveFallbackPosition() {
        if (!fallbackIsVideo && player && typeof player.getPlaylistIndex === 'function') {
            const idx = player.getPlaylistIndex();
            if (idx >= 0) {
                lastFallbackIndex = idx + 1; // resume from next track
                console.log('[YT Auto-Pilot] Saved fallback position: will resume at index ' + lastFallbackIndex);
            }
        }
    }

    /** Clears the guest-song watcher interval. */
    function stopGuestSongWatcher() {
        if (guestSongCheckInterval) {
            clearInterval(guestSongCheckInterval);
            guestSongCheckInterval = null;
        }
    }

    /**
     * Starts a fast 500ms interval that checks if the current fallback track
     * is within 1.5 seconds of ending. When detected, gracefully switches
     * to the waiting guest song without interrupting playback mid-song.
     *
     * Why not rely on ENDED? YouTube playlists auto-advance between tracks
     * without reliably firing the ENDED state change event, so we need
     * active polling to detect the natural end of each track.
     */
    function startGuestSongWatcher() {
        if (guestSongCheckInterval) return; // already watching
        guestSongCheckInterval = setInterval(function() {
            // Guard: stop watching if conditions no longer apply
            const tbody = document.getElementById('song-list');
            const autoPilotOff = !tbody || tbody.getAttribute('data-playback-mode') !== 'AUTO';
            if (!guestSongPending || !isFallbackMode || !player || autoPilotOff) {
                if (autoPilotOff) guestSongPending = false;
                stopGuestSongWatcher();
                return;
            }
            try {
                const currentTime = player.getCurrentTime();
                const duration = player.getDuration();
                // Switch when track is within 1.5 seconds of ending
                if (duration > 0 && currentTime >= duration - 1.5) {
                    stopGuestSongWatcher();
                    saveFallbackPosition();

                    // Find guest song BEFORE mutating state — it may have
                    // vanished since the watcher started.
                    const nextGuest = findNextGuestSong();
                    if (nextGuest) {
                        // Do NOT call player.stopVideo() here — loadVideoById()
                        // in playGuestSong() implicitly stops current playback.
                        // An explicit stopVideo() fires a stale ENDED event that
                        // races with the new video load and can kill the player.
                        isFallbackMode = false;
                        fallbackIsVideo = false;
                        guestSongPending = false;
                        currentlyPlayingSongId = null;
                        isLoadingSong = false;
                        console.log('[YT Auto-Pilot] Fallback track ending — switching to guest song');
                        playGuestSong(nextGuest);
                    } else {
                        // Guest song vanished — stay in fallback mode.
                        // The track is about to end; ENDED handler will loop
                        // or continue the playlist as normal.
                        guestSongPending = false;
                    }
                }
            } catch (e) {
                console.error('[YT Auto-Pilot] Watcher error:', e);
                stopGuestSongWatcher();
            }
        }, 500);
    }

    /**
     * Plays a specific guest song row from the queue table.
     */
    function playGuestSong(row) {
        const songId = row.getAttribute('data-song-id');
        const videoId = extractVideoId(row.getAttribute('data-track-url'));
        if (!videoId) return;

        isLoadingSong = true;
        currentlyPlayingSongId = songId;
        isFallbackMode = false;
        console.log('[YT Auto-Pilot] Playing: "' + (row.getAttribute('data-song-name') || '?') + '" (ID=' + songId + ', video=' + videoId + ')');
        player.loadVideoById(videoId);

        // Safety timeout: reset if playback doesn't start within 15s
        (function(sid) {
            setTimeout(function() {
                if (isLoadingSong && currentlyPlayingSongId === sid) {
                    console.warn('[YT Auto-Pilot] Timeout for ID=' + sid + ', resetting');
                    currentlyPlayingSongId = null;
                    isLoadingSong = false;
                }
            }, 15000);
        })(songId);
    }

    /**
     * Starts fallback playback in the embedded player.
     * Supports both playlist IDs and single video IDs (prefixed with "V:" from the server).
     */
    function startFallbackPlaylist() {
        if (!fallbackPlaylistId) return;
        isFallbackMode = true;
        guestSongPending = false;
        currentlyPlayingSongId = null;
        isLoadingSong = false;

        if (fallbackPlaylistId.startsWith('V:')) {
            // Single video fallback — loop it
            const videoId = fallbackPlaylistId.substring(2);
            console.log('[YT Auto-Pilot] Queue empty, starting fallback video (loop): ' + videoId);
            player.loadVideoById({ videoId: videoId });
            fallbackIsVideo = true;
            pendingPlaylistSetup = false;
        } else {
            // Playlist fallback — resume from last position, shuffle+loop applied on PLAYING event
            console.log('[YT Auto-Pilot] Queue empty, starting fallback playlist at index ' + lastFallbackIndex + ': ' + fallbackPlaylistId);
            player.loadPlaylist({ list: fallbackPlaylistId, listType: 'playlist', index: lastFallbackIndex });
            fallbackIsVideo = false;
            pendingPlaylistSetup = true;
        }
    }

    /**
     * Main Auto-Pilot entry point.
     * Scans the queue table for the oldest accepted song with a valid video ID.
     * If queue is empty and fallback is configured, starts the fallback playlist.
     * Called after each polling refresh and on player ready.
     */
    function tryAutoPlay() {
        if (!playerReady || !player || isLoadingSong) return;

        pruneStaleIds(); // clean up Sets periodically (at most once per minute)

        const tbody = document.getElementById('song-list');
        if (!tbody) return;
        if (tbody.getAttribute('data-playback-mode') !== 'AUTO') return;

        // Don't interrupt anything currently playing or paused
        if (playerState === YT.PlayerState.PLAYING
                || playerState === YT.PlayerState.BUFFERING
                || playerState === YT.PlayerState.PAUSED) {

            // If in fallback mode and a guest song arrived, flag it and start
            // the watcher interval. The watcher polls getCurrentTime/getDuration
            // every 500ms and switches to the guest song when the current
            // fallback track is within 1.5s of ending — no mid-song interrupt.
            if (isFallbackMode && findNextGuestSong()) {
                if (!guestSongPending) {
                    guestSongPending = true;
                    console.log('[YT Auto-Pilot] Guest song detected during fallback — waiting for current track to end');
                    startGuestSongWatcher();
                }
            }
            return;
        }

        // Try to find a guest song to play
        const nextGuest = findNextGuestSong();
        if (nextGuest) {
            if (isFallbackMode) {
                console.log('[YT Auto-Pilot] Exiting fallback mode — guest song available');
                isFallbackMode = false;
            }
            playGuestSong(nextGuest);
            return;
        }

        // Queue is empty — start fallback playlist if configured and not already playing
        if (fallbackPlaylistId && !isFallbackMode) {
            startFallbackPlaylist();
        }
    }

    // ---- Public API ----

    /**
     * Updates the fallback source dynamically (called after AJAX save).
     * If auto-pilot is enabled and the queue is empty, starts fallback immediately.
     *
     * @param extractedId the playlist/video ID extracted by the server (empty string = clear).
     *                    Server handles all URL parsing in DjDashboardController.extractPlaylistId()
     *                    — single source of truth, no client-side duplication.
     */
    window.updateFallbackSource = function(extractedId) {
        const newId = extractedId || null;

        // Stop current fallback if playing
        if (isFallbackMode && player) {
            player.stopVideo();
            isFallbackMode = false;
            fallbackIsVideo = false;
            guestSongPending = false;
            currentlyPlayingSongId = null;
            isLoadingSong = false;
            playerState = -1;
        }
        stopGuestSongWatcher();
        lastFallbackIndex = 0; // new source — start from beginning
        pendingPlaylistSetup = false;

        fallbackPlaylistId = newId;

        // Update DOM attribute for consistency
        if (playerCard) {
            if (newId) {
                playerCard.setAttribute('data-fallback-playlist', newId);
            } else {
                playerCard.removeAttribute('data-fallback-playlist');
            }
        }

        console.log('[YT Auto-Pilot] Fallback source updated: ' + (newId || '(cleared)'));

        // Trigger auto-play check — if queue is empty and new fallback is set, starts immediately
        if (newId) {
            tryAutoPlay();
        }
    };

    /**
     * Stops fallback playback and clears the fallback source.
     * Called by the Stop button on the dashboard.
     */
    window.stopFallback = function() {
        if (isFallbackMode && player) {
            player.stopVideo();
            playerState = -1;
        }
        isFallbackMode = false;
        fallbackIsVideo = false;
        guestSongPending = false;
        currentlyPlayingSongId = null;
        isLoadingSong = false;
        fallbackPlaylistId = null;
        lastFallbackIndex = 0;
        pendingPlaylistSetup = false;
        stopGuestSongWatcher();

        if (playerCard) {
            playerCard.removeAttribute('data-fallback-playlist');
        }
        console.log('[YT Auto-Pilot] Fallback stopped and cleared');
    };

    /**
     * Updates the shuffle setting at runtime (called after DJ toggles the checkbox).
     * If a fallback playlist is currently playing, applies setShuffle() immediately.
     *
     * @param {boolean} enabled true = shuffle on, false = sequential order.
     */
    window.updateFallbackShuffle = function(enabled) {
        shuffleEnabled = !!enabled;
        if (playerCard) {
            playerCard.setAttribute('data-fallback-shuffle', String(shuffleEnabled));
        }
        // Apply immediately if a playlist is currently loaded and playing
        if (isFallbackMode && !fallbackIsVideo && player && typeof player.setShuffle === 'function') {
            player.setShuffle(shuffleEnabled);
            console.log('[YT Auto-Pilot] Shuffle changed live to ' + shuffleEnabled);
        } else {
            console.log('[YT Auto-Pilot] Shuffle setting updated to ' + shuffleEnabled + ' (will apply on next playlist load)');
        }
    };

    /** Called by polling (dashboard.js) after each table refresh. */
    window.checkYouTubeAutoPlay = tryAutoPlay;

    /**
     * Plays a YouTube URL in the embedded player (manual play from ▶ YOUTUBE link).
     * Returns true if playback started, false if no valid video ID or player not ready.
     */
    window.playInEmbeddedPlayer = function(trackUrl) {
        if (!playerReady || !player) return false;
        const videoId = extractVideoId(trackUrl);
        if (!videoId) return false;
        // Manual play exits fallback mode
        if (isFallbackMode) saveFallbackPosition();
        stopGuestSongWatcher();
        isFallbackMode = false;
        guestSongPending = false;
        isLoadingSong = false;
        currentlyPlayingSongId = null;
        player.loadVideoById(videoId);
        console.log('[YT Player] Manual play: video=' + videoId);
        return true;
    };

})();

// ==========================================================================
// CLICK HANDLER: ▶ YOUTUBE links play in embedded player when possible.
// Search URLs (🔍 YOUTUBE) fall through to default behavior (new tab).
// ==========================================================================
document.addEventListener('click', function(e) {
    const link = e.target.closest('a.play-link');
    if (!link) return;
    const trackUrl = link.getAttribute('data-track-url');
    if (!trackUrl || !trackUrl.includes('youtube.com')) return;
    if (typeof window.playInEmbeddedPlayer === 'function' && window.playInEmbeddedPlayer(trackUrl)) {
        e.preventDefault();
    }
});
