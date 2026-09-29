/**
 * YouTube Auto-Pilot — automatic playback via YouTube IFrame Player API.
 *
 * A "dumb player": the server decides what plays next (POST /dj/dashboard/next-track — a waiting
 * guest song first, otherwise the next track of the DJ's background/fallback playlist, see
 * NextTrackService and PROJECT_CONTEXT.md Section 14). The client plays whatever video ID it is
 * given via loadVideoById() and knows nothing about playlists, their order or shuffle.
 *
 * It asks the server only when a track is about to be loaded — Auto-Pilot on and the player idle
 * (page load, Auto-Pilot switched on, the dashboard's 3 s poll), on ENDED, and after a player
 * error. The call is NOT read-only (a background track is marked PLAYED as it is handed out), so
 * it is never used to poll. Consequences, by design: a guest song that arrives while a track is
 * playing waits for it to end, and a paused player is left alone — a pause is the DJ's choice.
 * With Auto-Pilot off nothing starts by itself when a track ends.
 *
 * Only ONE dashboard window plays (PROJECT_CONTEXT.md Section 5.4, "One window plays"). Every open dashboard has
 * its own player, and a second one — the DJ peeking from a phone — would take tracks off the queue that the first
 * never plays. A window reports to POST /dj/dashboard/player-lease every 3 s and asks for tracks only while the
 * server says it holds the lease; the others show a banner with a "play on this device" button. The server
 * enforces it as well: next-track answers 409 to a window that does not hold the lease. The same reports carry
 * the party's current fallback playlist, so a playlist the DJ replaces or clears in another window stops the
 * background track that came from the old one (next-track names the playlist of every background track).
 *
 * Guest songs are still confirmed by the client via POST /dj/dashboard/play once the video
 * actually reaches PLAYING. Background tracks need no confirmation and no error report: a track
 * that fails to play is already PLAYED, so the client just asks for the next one.
 *
 * Exposes: checkYouTubeAutoPlay, playInEmbeddedPlayer, updateFallbackSource, stopFallback
 */
(function () {
    'use strict';

    // ---- State ----
    let player = null, playerReady = false, playerState = -1;
    // Song request ID of the guest song that is loading/playing; null for a background track
    // or a track the DJ picked by hand.
    let currentlyPlayingSongId = null, isLoadingSong = false;
    // True while a background (fallback playlist) track is loading/playing.
    let isBackgroundTrack = false;
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
    // Player errors in a row without a video reaching PLAYING in between. The first few are
    // followed by an immediate "next track"; after that the dashboard's poll sets the pace, so a
    // playlist of unplayable videos is not burnt through in a tight loop.
    let consecutiveErrors = 0;
    const MAX_IMMEDIATE_RETRIES = 5;
    // Which window plays (see the file header): null = the server has not answered yet, true = this window plays,
    // false = another window or device does. Tracks are asked for only while it is true.
    let isPlayerDevice = null;
    // True while no window holds the lease (the one that played has gone away): the banner then offers "play on
    // this device" without asking for confirmation.
    let leaseFree = false;
    // Numbers the lease reports so that an answer that arrives late cannot undo a newer one.
    let leaseRequestSeq = 0, leaseAppliedSeq = 0;
    // The playlist the running background track came from (null: unknown, or not a background track), and the number
    // of the last lease report sent before that track was loaded: only a report sent after it says anything about it.
    let playingPlaylistId = null, trackLoadedAtLeaseSeq = 0;
    const LEASE_REPORT_INTERVAL_MS = 3000;
    const deviceId = loadDeviceId();

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

    function markAsPlayed(songId) {
        const fd = new FormData();
        fd.append('id', songId);
        fetch('/dj/dashboard/play', {
            method: 'POST', headers: { [csrf.header]: csrf.token }, body: fd, redirect: 'manual'
        }).catch(e => console.error('[YT] markAsPlayed error:', e));
    }

    /**
     * Asks the backend what to play next. NOT read-only (see the file header) — call it only
     * when the answer is about to be loaded. Returns null on "nothing to play" (204) or on a
     * network error.
     *
     * @returns {Promise<{source: 'GUEST'|'BACKGROUND', id: number, videoId: string}|null>}
     */
    async function fetchNextTrack() {
        if (!partyCodeValue) return null;
        try {
            let url = '/dj/dashboard/next-track?partyCode=' + encodeURIComponent(partyCodeValue)
                + '&deviceId=' + encodeURIComponent(deviceId);
            if (erroredSongIds.size > 0) {
                url += '&exclude=' + Array.from(erroredSongIds).join(',');
            }
            const response = await fetch(url, { method: 'POST', headers: { [csrf.header]: csrf.token } });
            if (response.status === 409) { // another window holds the lease: this one only looks
                applyLease(false, false);
                return null;
            }
            if (response.status === 204 || !response.ok) return null;
            const track = await response.json();
            return track && track.videoId ? track : null;
        } catch (e) {
            console.error('[YT] fetchNextTrack error:', e);
            return null;
        }
    }

    function isAutoPilotOn() {
        const tbody = document.getElementById('song-list');
        return tbody && tbody.getAttribute('data-playback-mode') === 'AUTO';
    }

    /** Nothing is playing, buffering or paused — safe to load the next track. */
    function isPlayerIdle() {
        return playerState === YT.PlayerState.UNSTARTED
            || playerState === YT.PlayerState.ENDED
            || playerState === YT.PlayerState.CUED;
    }

    // ---- Event Handlers ----

    function onPlayerStateChange(event) {
        playerState = event.data;

        if (event.data === YT.PlayerState.PLAYING) {
            isLoadingSong = false;
            consecutiveErrors = 0;

            // Mark guest songs as played (once per song, see lastMarkedPlayedId above)
            if (currentlyPlayingSongId && currentlyPlayingSongId !== lastMarkedPlayedId) {
                lastMarkedPlayedId = currentlyPlayingSongId;
                markAsPlayed(currentlyPlayingSongId);
            }
        }

        if (event.data === YT.PlayerState.ENDED) {
            if (isLoadingSong) return; // stale ENDED during transition
            currentlyPlayingSongId = null;
            isBackgroundTrack = false;
            tryAutoPlay();
        }
    }

    function onPlayerError(event) {
        console.error('[YT] Player error ' + event.data + ' for '
            + (currentlyPlayingSongId ? 'song ID=' + currentlyPlayingSongId : 'a background/manual track'));
        if (currentlyPlayingSongId) erroredSongIds.add(currentlyPlayingSongId);
        currentlyPlayingSongId = null;
        isBackgroundTrack = false;
        isLoadingSong = false;
        playerState = -1; // the failed video is not playing — do not wait for a state event that may not come
        if (++consecutiveErrors <= MAX_IMMEDIATE_RETRIES) tryAutoPlay();
    }

    // ---- Core Playback ----

    function playTrack(track) {
        isLoadingSong = true;
        isBackgroundTrack = track.source === 'BACKGROUND';
        currentlyPlayingSongId = track.source === 'GUEST' ? track.id : null;
        playingPlaylistId = isBackgroundTrack ? (track.playlistId || null) : null;
        trackLoadedAtLeaseSeq = leaseRequestSeq;
        player.loadVideoById(track.videoId);
        // The server has just taken a background track off the queue — let the dashboard show what comes next.
        if (isBackgroundTrack && typeof window.refreshFallbackQueue === 'function') window.refreshFallbackQueue();
    }

    /** Stops a running background track (the DJ changed or cleared the playlist). A guest song keeps playing. */
    function stopBackgroundTrack() {
        if (!isBackgroundTrack || !player) return;
        player.stopVideo();
        playerState = -1;
        isBackgroundTrack = false;
        isLoadingSong = false;
        playingPlaylistId = null;
    }

    /**
     * The DJ replaced or cleared the fallback playlist — perhaps in another window, which cannot stop this window's
     * player: a background track that came from the old playlist stops, and the next one is asked for. A report
     * that was sent before the running track was loaded may still describe the old playlist, so it is not trusted.
     */
    function dropStaleBackgroundTrack(currentPlaylistId, reportSeq) {
        if (!isBackgroundTrack || playingPlaylistId === null || playingPlaylistId === currentPlaylistId) return;
        if (reportSeq <= trackLoadedAtLeaseSeq) return;
        stopBackgroundTrack();
        tryAutoPlay();
    }

    /** Main entry point — called by polling, on player ready, on ENDED and after a player error. */
    async function tryAutoPlay() {
        if (isPlayerDevice !== true || !playerReady || !player || isLoadingSong || tryAutoPlayInFlight) return;
        if (!isAutoPilotOn() || !isPlayerIdle()) return;
        tryAutoPlayInFlight = true;

        try {
            const track = await fetchNextTrack();
            // Playback was taken over while we were asking (the DJ picked a track, Auto-Pilot was
            // switched off, another window took the lease) — drop the answer. A guest song is only
            // consumed once it plays; a background track handed out here is skipped for this round of
            // the playlist.
            if (!track || isPlayerDevice !== true || isLoadingSong || !isAutoPilotOn() || !isPlayerIdle()) return;
            playTrack(track);
        } finally {
            tryAutoPlayInFlight = false;
        }
    }

    // ---- Which window plays (the player lease) ----

    function newDeviceId() {
        if (window.crypto && typeof window.crypto.randomUUID === 'function') return window.crypto.randomUUID();
        return 'w' + Math.random().toString(36).slice(2) + Date.now().toString(36); // plain-HTTP pages have no randomUUID
    }

    /** A random id of this window, kept for the life of the tab so that a reload keeps its role. */
    function loadDeviceId() {
        const key = 'scan2play.playerDeviceId';
        try {
            let id = window.sessionStorage.getItem(key);
            if (!id) {
                id = newDeviceId();
                window.sessionStorage.setItem(key, id);
            }
            return id;
        } catch (e) {
            return newDeviceId(); // storage is blocked: the id lives as long as this page
        }
    }

    function renderLeaseBanner() {
        const banner = document.getElementById('playerLeaseBanner');
        if (!banner) return;
        const show = isPlayerDevice === false;
        banner.classList.toggle('d-none', !show);
        if (show) {
            banner.querySelector('[data-role="text"]').textContent =
                leaseFree ? banner.dataset.textFree : banner.dataset.textOther;
        }
    }

    /** This window lost the lease: what it plays stops, the window that took over carries on from the queue. */
    function stopPlaybackHere() {
        if (player && playerReady) player.stopVideo();
        playerState = -1;
        currentlyPlayingSongId = null;
        isBackgroundTrack = false;
        isLoadingSong = false;
    }

    /** The server says who plays: the answer of every lease report, and a 409 from next-track. */
    function applyLease(holder, free) {
        const wasPlayer = isPlayerDevice === true;
        isPlayerDevice = holder;
        leaseFree = !holder && free;
        renderLeaseBanner();
        if (holder && !wasPlayer) {
            tryAutoPlay();
        } else if (!holder && wasPlayer) {
            stopPlaybackHere();
        }
    }

    /** @param {'CLAIM'|'WATCH'|'TAKE_OVER'} mode see PlayerLeaseMode on the server */
    async function reportLease(mode) {
        if (!partyCodeValue) return;
        const seq = ++leaseRequestSeq;
        try {
            const response = await fetch('/dj/dashboard/player-lease', {
                method: 'POST',
                headers: { [csrf.header]: csrf.token },
                body: new URLSearchParams({ partyCode: partyCodeValue, deviceId: deviceId, mode: mode })
            });
            // A hiccup (server error, login redirect) keeps the current role: it must neither silence the
            // window that plays nor make another one start.
            if (!response.ok || response.redirected) return;
            const lease = await response.json();
            if (seq < leaseAppliedSeq) return; // a newer answer has been applied already
            leaseAppliedSeq = seq;
            applyLease(lease.holder === true, lease.free === true);
            if (lease.holder === true) dropStaleBackgroundTrack(lease.fallbackPlaylistId || null, seq);
        } catch (e) {
            console.error('[YT] reportLease error:', e);
        }
    }

    function leaseLoop() {
        // A window that has been told another one plays only watches; it takes the lease again only when the DJ asks.
        reportLease(isPlayerDevice === false ? 'WATCH' : 'CLAIM')
            .finally(() => setTimeout(leaseLoop, LEASE_REPORT_INTERVAL_MS));
    }

    function takeOverPlayback() {
        const banner = document.getElementById('playerLeaseBanner');
        // Taking the lease from a window that still plays stops it there — ask first (a stray tap on a phone).
        if (!leaseFree && banner && !window.confirm(banner.dataset.confirm)) return;
        reportLease('TAKE_OVER');
    }

    const takeOverButton = document.getElementById('playerLeaseTakeover');
    if (takeOverButton) takeOverButton.addEventListener('click', takeOverPlayback);

    // Going away (tab closed, another page opened): give the lease up at once, so that the next window does not
    // have to wait for the timeout. sendBeacon cannot set headers, so the CSRF token goes in the body.
    window.addEventListener('pagehide', function () {
        if (isPlayerDevice !== true || !partyCodeValue || !navigator.sendBeacon) return;
        navigator.sendBeacon('/dj/dashboard/player-lease/release', new URLSearchParams({
            partyCode: partyCodeValue, deviceId: deviceId, _csrf: csrf.token
        }));
    });

    leaseLoop();

    // ---- Public API ----

    window.checkYouTubeAutoPlay = tryAutoPlay;

    /** The DJ saved a different background playlist (the server already switched): drop the old track, start from the new one. */
    window.updateFallbackSource = function () {
        stopBackgroundTrack();
        tryAutoPlay();
    };

    window.stopFallback = stopBackgroundTrack;

    window.playInEmbeddedPlayer = function (trackUrl) {
        // A window that does not hold the lease must not start sound by a stray tap: the ▶ link then simply opens on YouTube.
        if (isPlayerDevice !== true || !playerReady || !player) return false;
        const videoId = extractVideoId(trackUrl);
        if (!videoId) return false;
        // Picked by hand: nothing to confirm, not a background track. Auto-Pilot carries on when it ends.
        currentlyPlayingSongId = null;
        isBackgroundTrack = false;
        isLoadingSong = true;
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
