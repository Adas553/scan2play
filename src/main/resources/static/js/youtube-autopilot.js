/**
 * YouTube Auto-Pilot — automatic playback via YouTube IFrame Player API.
 *
 * State Machine:
 *   IDLE → (find accepted song) → LOADING → PLAYING → ENDED → IDLE
 *
 * How it works:
 *   1. Polling (dashboard.js) refreshes the <tbody id="song-list"> every 5s
 *   2. After each refresh, checkYouTubeAutoPlay() is called
 *   3. Auto-Pilot scans the table for the oldest accepted song with a valid video URL
 *   4. Loads the video in the embedded player via loadVideoById()
 *   5. On PLAYING state, marks the song as PLAYED via fetch POST
 *   6. On ENDED state, resets and waits for the next poll cycle
 *
 * Quota optimization:
 *   Video URLs are resolved server-side via YouTube Data API v3 with 24h caching.
 *   Each unique song costs 100 quota units only once per day.
 *
 * Dependencies (DOM):
 *   - <div id="yt-player">            YouTube IFrame container
 *   - <meta name="_csrf">             CSRF token
 *   - <meta name="_csrf_header">      CSRF header name
 *   - <tbody id="song-list">          with data-playback-mode attribute
 *   - Each <tr> has: data-song-id, data-song-name, data-track-url
 *
 * Exposes (global):
 *   - window.checkYouTubeAutoPlay     — called by polling after table refresh
 *   - window.playInEmbeddedPlayer(url) — called by ▶ YOUTUBE link click handler
 */
(function() {
    'use strict';

    // ---- State ----
    let player = null;
    let playerReady = false;
    let playerState = -1;                // YT.PlayerState.UNSTARTED
    let currentlyPlayingSongId = null;
    let isLoadingSong = false;
    const markedAsPlayedIds = new Set();
    const skippedSongIds   = new Set();    // Songs without a valid video URL

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

    // ---- Player Event Handlers ----

    function onPlayerStateChange(event) {
        playerState = event.data;

        if (event.data === YT.PlayerState.PLAYING) {
            isLoadingSong = false;
            if (currentlyPlayingSongId && !markedAsPlayedIds.has(currentlyPlayingSongId)) {
                markedAsPlayedIds.add(currentlyPlayingSongId);
                markAsPlayed(currentlyPlayingSongId);
                console.log('[YT Auto-Pilot] Now playing, marked ID=' + currentlyPlayingSongId + ' as PLAYED');
            }
        }

        if (event.data === YT.PlayerState.ENDED) {
            player.stopVideo();
            currentlyPlayingSongId = null;
            isLoadingSong = false;
            console.log('[YT Auto-Pilot] Song ended, waiting for next poll cycle');
        }
    }

    function onPlayerError(event) {
        console.error('[YT Auto-Pilot] Player error (code=' + event.data + ') for ID=' + currentlyPlayingSongId);
        if (currentlyPlayingSongId) skippedSongIds.add(currentlyPlayingSongId);
        currentlyPlayingSongId = null;
        isLoadingSong = false;
    }

    // ---- Core Auto-Pilot Logic ----

    /**
     * Scans the queue table for the oldest accepted song with a valid video ID.
     * Called after each polling refresh and on player ready.
     */
    function tryAutoPlay() {
        if (!playerReady || !player || isLoadingSong) return;

        const tbody = document.getElementById('song-list');
        if (!tbody) return;
        if (tbody.getAttribute('data-playback-mode') !== 'AUTO') return;
        if (playerState === YT.PlayerState.PLAYING
                || playerState === YT.PlayerState.BUFFERING
                || playerState === YT.PlayerState.PAUSED) return;

        const rows = tbody.querySelectorAll('tr[data-song-id]');
        if (rows.length === 0) return;

        // Iterate from oldest (last row — table is sorted DESC) to newest
        for (let i = rows.length - 1; i >= 0; i--) {
            const row    = rows[i];
            const songId = row.getAttribute('data-song-id');
            if (!songId) continue;
            if (markedAsPlayedIds.has(songId) || skippedSongIds.has(songId)) continue;

            const videoId = extractVideoId(row.getAttribute('data-track-url'));
            if (!videoId) {
                skippedSongIds.add(songId);
                console.warn('[YT Auto-Pilot] No video ID for song ID=' + songId + ', skipping');
                continue;
            }

            // Found a playable song — lock & play
            isLoadingSong = true;
            currentlyPlayingSongId = songId;
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

            return;
        }
    }

    // ---- Public API ----

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

