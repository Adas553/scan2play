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
 * With Auto-Pilot off nothing starts by itself when a track ends. After a reload, and in a window that has just taken playback
 * over from another device, the first track is the one that played last (from its start, if it started within 10 minutes), not
 * the next one — after a reload only if the reload interrupted it, and with Auto-Pilot off at the reload once it is switched on;
 * see resumeLastTrack.
 *
 * Only ONE dashboard window plays (PROJECT_CONTEXT.md Section 5.4, "One window plays"). Every open dashboard has
 * its own player, and a second one — the DJ peeking from a phone — would take tracks off the queue that the first
 * never plays. A window reports to POST /dj/dashboard/player-lease every 3 s and asks for tracks only while the
 * server says it holds the lease; the others show a banner with a "play on this device" button. The server
 * enforces it as well: next-track answers 409 to a window that does not hold the lease. The same reports carry
 * the party's current fallback playlist, so a playlist the DJ replaces or clears in another window stops the
 * background track that came from the old one (next-track names the playlist of every background track).
 *
 * The ⏭ button skips to the next track (whatever the player is doing, with Auto-Pilot off too): at once in the window
 * that plays; in another window — the phone as a remote control — it sends POST /dj/dashboard/player-command, and the
 * window that plays carries it out when its next lease report brings it (within ~3 s). The reports also carry a version
 * of the "up next" list, so a window that did not change the list itself fetches it again when it changed elsewhere.
 * The ⏮ button goes back like a normal player: a track that has played for more than 3 s starts again, otherwise the
 * track that played before it comes back (GET /dj/dashboard/recent-tracks — the server's timeline of what played, so it
 * is the same whichever window played the tracks); pressed again, the one before that. A second press within 20 s of a
 * restart that ⏮ caused goes back a track as well (from another window the presses are always more than 3 s apart, so
 * without that the previous track could not be reached from there). The single ⏮ is what the window that plays shows; a window
 * that does not play — the phone as a remote — has two plain buttons instead, because those rules are hard to use from a remote:
 * "back" (PREVIOUS_TRACK: always the previous track) and "from the start" (RESTART: the track that runs, again from the start);
 * they work through the same channel as ⏭ (renderBackButtons, goToPreviousTrack, restartTrack). ⏮ works from any window in the
 * same way as ⏭, and when a track that came back ends Auto-Pilot carries on with the queue. ⏭ pressed on a track that
 * came back goes forward along the same timeline (to the entry one newer) instead of asking for a new track, until it is
 * back at the newest entry: ⏮ then ⏭ returns to where the DJ was, guest song included — unless the DJ has changed the
 * playlist meanwhile (here, or in another window): then ⏭ asks for a new track at once and the new playlist starts.
 * The ⏯ button pauses and resumes the music, also from any window: the window that plays says in its lease reports
 * whether its player makes sound, the answers tell that to the others, and a window that does not play shows "pause" or
 * "resume" accordingly and sends the explicit command PAUSE or RESUME (a no-op if the state has changed meanwhile). A track
 * that is being loaded counts as playing (for 10 s at most), so the label does not flip to "resume" between two videos.
 *
 * Guest songs are still confirmed by the client via POST /dj/dashboard/play once the video
 * actually reaches PLAYING. Background tracks need no confirmation and no error report: a track
 * that fails to play is already PLAYED, so the client just asks for the next one.
 *
 * Exposes: checkYouTubeAutoPlay, playInEmbeddedPlayer, updateFallbackSource, stopFallback, setKnownQueueVersion
 */
(function () {
    'use strict';

    // ---- State ----
    let player = null, playerReady = false, playerState = -1;

    // The track in the player — everything about it in one object (REVIEW.md 3.2: it used to be ten variables, each place that
    // loaded or stopped a track setting its own subset). Created only by startTrack, which every load goes through; its phase
    // then moves LOADING → RUNNING (it reached PLAYING) → OVER (it ended, failed or was stopped). null before the first load.
    //   kind         'GUEST' | 'BACKGROUND' | 'HISTORY' (came back through ⏮ or a resume) | 'MANUAL' (▶ picked by hand)
    //   songId       the guest song's request id (GUEST): confirmed once it plays, excluded when ⏭ skips it
    //   key          its entry in the server's timeline of what played ('G:<request id>' or 'B:<play id>' — a background
    //                track's id is that of one *play*, so the same video in two rounds has two keys); null for MANUAL. It is kept
    //                when the track is over: "back" from a track that ended starts there
    //   playlistId   the playlist a BACKGROUND track came from (null: unknown)
    //   loadedAtSeq  the number of the last lease report sent before it was loaded: only a report sent after it says anything
    //                about it (dropStaleBackgroundTrack, notePlaylist)
    //   loadNo       which load it was (loads counts them): a note about one track ("⏮ restarted it") ends with the next load
    //   startedAt    when it was handed to the player (Date.now())
    //   retrace      ⏭ retraces the timeline instead of asking for a new track (skipToNext): true for HISTORY until the DJ
    //                changes the playlist or the window loses the lease
    //   phase        'LOADING' | 'RUNNING' | 'OVER'
    let current = null;
    let loads = 0;

    /** Whether a track has been handed to the player and has not reached PLAYING yet (nor ended, failed or been stopped). */
    function isLoadingSong() { return current !== null && current.phase === 'LOADING'; }
    /** Whether the player holds a background (fallback playlist) track that is loading or playing. */
    function isBackgroundTrack() { return current !== null && current.kind === 'BACKGROUND' && current.phase !== 'OVER'; }
    /** The request id of the guest song that is loading or playing, or null. */
    function runningGuestSongId() { return current !== null && current.kind === 'GUEST' && current.phase !== 'OVER' ? current.songId : null; }
    /** The timeline key of the track in the player (kept when it is over), or null. */
    function nowPlayingKey() { return current !== null ? current.key : null; }
    /** Whether ⏭ retraces the timeline (the track came back through ⏮). */
    function isRetracing() { return current !== null && current.retrace; }
    function endRetrace() { if (current !== null) current.retrace = false; }
    function trackIsOver() { if (current !== null) current.phase = 'OVER'; }

    // How long a track that has been handed to the player but does not play yet still counts as "playing" for the pause button
    // and the lease reports (isPlayingOrLoading). Between two videos the player is UNSTARTED or CUED for a moment, and saying
    // "not playing" then made the button read "resume" right after the DJ had started a track. Only for a while: a browser that
    // refuses to start sound in a page nobody has touched leaves the player at CUED for good, and then "resume" is the way out.
    const LOADING_COUNTS_AS_PLAYING_MS = 10000;
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
    // followed by an immediate "next track"; after that the lease reports set the pace (askAgain), so a
    // playlist of unplayable videos is not burnt through in a tight loop.
    let consecutiveErrors = 0;
    const MAX_IMMEDIATE_RETRIES = 5;
    // True when the last ask for a track failed (a network error, a 5xx, an expired login) or the player gave up after
    // MAX_IMMEDIATE_RETRIES errors: the next lease report (every 3 s) asks again. Nothing else would — the dashboard's poll asks
    // only when the guest queue has changed, so an idle player stayed silent until a guest added a song.
    let askAgain = false;
    // Resume the track that played last (the owner's decisions 2026-09-30): after a reload, and after "play on this device", the
    // first track this window plays by itself is the one that played last — the newest entry of the server's timeline, if it started
    // at most RESUME_WITHIN_SECONDS ago — from its start, instead of the next one. Asking next-track then used up a track (it is marked
    // played when handed out): at load nobody heard it (the browser refuses sound in a page nobody has touched, so it was only
    // loaded), and a takeover skipped the track the other device was playing. Only for that first track: any load clears it
    // (startTrack), and so does an answer that another window plays; "play on this device" sets it again once this window holds
    // the lease (takeOverPending, applyLease).
    // After a reload (2026-10-01, the owner's report) only a track the reload INTERRUPTED comes back — the one the page before noted
    // when it went away (INTERRUPTED_TRACK_KEY, see the pagehide handler): a track that had ended by itself is not played again. With
    // Auto-Pilot off at the reload the resume waits until Auto-Pilot is switched on or "resume" is pressed (it used to be dropped, and
    // the next track played); a track the DJ had paused waits for "resume" even with Auto-Pilot on (isHeldByPause).
    let resumeLastTrack = true;
    // "Play on this device" has been pressed and no answer has made this window the holder yet (applyLease).
    let takeOverPending = false;
    // The resume comes from "play on this device": it happens with Auto-Pilot off too (tryAutoPlay).
    let resumeWithoutAutoPilot = false;
    const RESUME_WITHIN_SECONDS = 600;
    // sessionStorage (kept across a reload of the tab): the key of the track the page interrupted when it went away.
    const INTERRUPTED_TRACK_KEY = 'scan2play.interruptedTrack';
    // Which window plays (see the file header): null = the server has not answered yet, true = this window plays,
    // false = another window or device does. Tracks are asked for only while it is true.
    let isPlayerDevice = null;
    // True while no window holds the lease (the one that played has gone away): the banner then offers "play on
    // this device" without asking for confirmation.
    let leaseFree = false;
    // Numbers the lease reports so that an answer that arrives late cannot undo a newer one.
    let leaseRequestSeq = 0, leaseAppliedSeq = 0;
    // Whether the window that plays says its player makes sound (true) or is paused (false), from the lease answers;
    // null = unknown. A window that does not play shows "pause" or "resume" by it (the window that plays looks at its own player).
    let holderPlaying = null;
    // The playlist that the last lease answer named (undefined = no answer yet, null = the party has none): when an answer names
    // another one the DJ has replaced or cleared it (notePlaylist).
    let lastSeenPlaylistId;
    // The version of the "up next" list this window last saw (from the list itself or from a lease answer): when the
    // server reports another one, the list was changed elsewhere and is fetched again. null = not seen yet.
    let lastQueueVersion = null;
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
     * @param {number|null} [skippedSongId] a guest song ⏭ is skipping: not handed out again by this ask (see skipToNext)
     * @returns {Promise<{source: 'GUEST'|'BACKGROUND', id: number, videoId: string}|null>}
     */
    async function fetchNextTrack(skippedSongId) {
        if (!partyCodeValue) return null;
        try {
            let url = '/dj/dashboard/next-track?partyCode=' + encodeURIComponent(partyCodeValue)
                + '&deviceId=' + encodeURIComponent(deviceId);
            const exclude = new Set(erroredSongIds);
            if (skippedSongId) exclude.add(skippedSongId);
            if (exclude.size > 0) {
                url += '&exclude=' + Array.from(exclude).join(',');
            }
            const response = await fetch(url, { method: 'POST', headers: { [csrf.header]: csrf.token } });
            if (response.status === 409) { // another window holds the lease: this one only looks
                askAgain = false;
                applyLease(false, false);
                return null;
            }
            // A redirect is the login page: the session has expired (it may come back, e.g. after a login in another tab).
            askAgain = response.status !== 204 && (!response.ok || response.redirected);
            if (response.status === 204 || askAgain) return null;
            const track = await response.json();
            return track && track.videoId ? track : null;
        } catch (e) {
            console.error('[YT] fetchNextTrack error:', e);
            askAgain = true;
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

    /** The player is making sound: playing, or buffering on its way to it. */
    function isPlaying() {
        return typeof YT !== 'undefined'
            && (playerState === YT.PlayerState.PLAYING || playerState === YT.PlayerState.BUFFERING);
    }

    /**
     * Whether the music is "on" as the DJ sees it: the player makes sound, or a track has just been handed to it and it is
     * still loading (UNSTARTED / CUED between two videos, or still the ENDED of the last one). This is what the pause button
     * shows and what the lease reports say. A paused player is not "loading" (the DJ paused it), and a load that has not
     * started within LOADING_COUNTS_AS_PLAYING_MS is not counted any more.
     */
    function isPlayingOrLoading() {
        if (isPlaying()) return true;
        return isLoadingSong() && playerState !== YT.PlayerState.PAUSED
            && Date.now() - current.startedAt < LOADING_COUNTS_AS_PLAYING_MS;
    }

    // ---- Event Handlers ----

    function onPlayerStateChange(event) {
        // Only the window that holds the lease makes sound. The YouTube player of another window still has its own play button:
        // pressed there, the video is stopped at once (the banner offers "play on this device" instead).
        if (isPlayerDevice === false
                && (event.data === YT.PlayerState.PLAYING || event.data === YT.PlayerState.BUFFERING)) {
            player.stopVideo();
            playerState = -1;
            updatePauseButton();
            return;
        }
        playerState = event.data;
        updatePauseButton();
        // Say at once that the music started or stopped, instead of at the next 3 s report: a window that does not
        // play shows "pause" / "resume" by it, and would otherwise be up to two report intervals behind.
        if (isPlayerDevice === true
                && (event.data === YT.PlayerState.PLAYING || event.data === YT.PlayerState.PAUSED)) {
            reportLease('CLAIM');
        }

        if (event.data === YT.PlayerState.PLAYING) {
            if (isLoadingSong()) current.phase = 'RUNNING';
            consecutiveErrors = 0;

            // Mark guest songs as played (once per song, see lastMarkedPlayedId above)
            const songId = runningGuestSongId();
            if (songId && songId !== lastMarkedPlayedId) {
                lastMarkedPlayedId = songId;
                markAsPlayed(songId);
            }
        }

        if (event.data === YT.PlayerState.ENDED) {
            if (isLoadingSong()) return; // stale ENDED during transition
            trackIsOver();
            tryAutoPlay();
        }
    }

    function onPlayerError(event) {
        const songId = runningGuestSongId();
        console.error('[YT] Player error ' + event.data + ' for ' + (songId ? 'song ID=' + songId : 'a background/manual track'));
        if (songId) erroredSongIds.add(songId);
        trackIsOver();
        playerState = -1; // the failed video is not playing — do not wait for a state event that may not come
        if (++consecutiveErrors <= MAX_IMMEDIATE_RETRIES) tryAutoPlay();
        else askAgain = true; // no tight loop through unplayable videos: the next lease report asks
    }

    // ---- Core Playback ----

    /**
     * Hands a track to the player — the one way every track goes in, so `current` always describes what the player holds.
     * Any load also ends a pending resume (resumeLastTrack).
     *
     * @param {'GUEST'|'BACKGROUND'|'HISTORY'|'MANUAL'} kind
     * @param {string} videoId
     * @param {{songId?: number, key?: string, playlistId?: string}} [about]
     */
    function startTrack(kind, videoId, about) {
        about = about || {};
        resumeLastTrack = resumeWithoutAutoPilot = false;
        current = {
            kind: kind,
            songId: kind === 'GUEST' ? about.songId : null,
            key: about.key || null,
            playlistId: kind === 'BACKGROUND' ? (about.playlistId || null) : null,
            loadedAtSeq: leaseRequestSeq,
            loadNo: ++loads,
            startedAt: Date.now(),
            retrace: kind === 'HISTORY',
            phase: 'LOADING'
        };
        player.loadVideoById(videoId);
    }

    /** A track the server handed out (next-track). */
    function playTrack(track) {
        const guest = track.source === 'GUEST';
        startTrack(guest ? 'GUEST' : 'BACKGROUND', track.videoId,
            { songId: track.id, key: (guest ? 'G:' : 'B:') + track.id, playlistId: track.playlistId });
        // The server has just taken a background track off the queue — let the dashboard show what comes next.
        if (!guest && typeof window.refreshFallbackQueue === 'function') window.refreshFallbackQueue();
    }

    /** Stops a running background track (the DJ changed or cleared the playlist). A guest song keeps playing. */
    function stopBackgroundTrack() {
        if (!isBackgroundTrack() || !player) return;
        player.stopVideo();
        playerState = -1;
        trackIsOver();
    }

    /**
     * The DJ replaced or cleared the fallback playlist — perhaps in another window, which cannot stop this window's
     * player: a background track that came from the old playlist stops, and the next one is asked for. A report
     * that was sent before the running track was loaded may still describe the old playlist, so it is not trusted.
     */
    function dropStaleBackgroundTrack(currentPlaylistId, reportSeq) {
        if (!isBackgroundTrack() || current.playlistId === null || current.playlistId === currentPlaylistId) return;
        if (reportSeq <= current.loadedAtSeq) return;
        stopBackgroundTrack();
        tryAutoPlay();
    }

    /**
     * The lease answer names the party's current playlist (the DJ can change it in another window). When it is another one than
     * the last answer named, the playlist has been replaced or cleared, and the retracing of ⏭ ends (endRetrace): the
     * tracks of the playlist that was left are not walked through again, ⏭ asks next-track at once and the new playlist starts.
     * The track that plays now — one that came back through ⏮ — is not interrupted, like a guest song. Like
     * dropStaleBackgroundTrack it trusts only a report sent after that track was loaded: an earlier one may be about a change
     * that came before the DJ went back to look at the old tracks, which is what ⏭ should then retrace.
     * (In the window where the DJ saved or cleared the playlist updateFallbackSource / stopFallback do the same at once.)
     */
    function notePlaylist(currentPlaylistId, reportSeq) {
        const changed = lastSeenPlaylistId !== undefined && currentPlaylistId !== lastSeenPlaylistId;
        lastSeenPlaylistId = currentPlaylistId;
        if (changed && current !== null && reportSeq > current.loadedAtSeq) endRetrace();
    }

    /** Main entry point — called by polling, on player ready, on ENDED and after a player error. */
    async function tryAutoPlay() {
        if (isPlayerDevice !== true || !playerReady || !player || isLoadingSong() || tryAutoPlayInFlight) return;
        // "Play on this device" brings the last track back even with Auto-Pilot off: the DJ asked for music here. Auto-Pilot only
        // decides what happens when it ends — the queue goes on (on) or the player stops (off).
        const takeOver = resumeLastTrack && resumeWithoutAutoPilot;
        // Auto-Pilot off: nothing starts by itself. A resume after a reload waits for Auto-Pilot to be switched on (or "resume").
        if (!isAutoPilotOn() && !takeOver) return;
        if (!isPlayerIdle()) return;
        // The reload interrupted a track the DJ had paused: held like a paused player — nothing starts, not even the queue,
        // until "resume" (resumeHere). A pause is the DJ's choice (the owner's decision 2026-10-01, option A).
        if (resumeLastTrack && !takeOver && isHeldByPause()) return;
        tryAutoPlayInFlight = true;

        try {
            if (resumeLastTrack) {
                const outcome = await resumeLastPlayed(takeOver, function () { return isAutoPilotOn() || takeOver; });
                if (outcome !== 'none') return; // resumed, or the situation changed while we asked
            }
            if (!isAutoPilotOn()) return; // a takeover with Auto-Pilot off plays the last track only, never the next one
            const track = await fetchNextTrack();
            // Playback was taken over while we were asking (the DJ picked a track, Auto-Pilot was
            // switched off, another window took the lease) — drop the answer. A guest song is only
            // consumed once it plays; a background track handed out here is skipped for this round of
            // the playlist.
            if (!track || isPlayerDevice !== true || isLoadingSong() || !isAutoPilotOn() || !isPlayerIdle()) return;
            playTrack(track);
        } finally {
            tryAutoPlayInFlight = false;
        }
    }

    /**
     * Brings back the track that played last (resumeLastTrack), from its start, as ⏮ does: not confirmed again, ⏭ and its end
     * go on from the queue. A takeover carries on with whatever the other device played last; after a reload only the track the
     * reload interrupted comes back (the note of the page before) — if it is still the newest entry of the timeline and started at
     * most RESUME_WITHIN_SECONDS ago. Either way the resume is used up. The caller holds tryAutoPlayInFlight.
     *
     * @param {boolean} takeOver whether this is "play on this device"
     * @param {function(): boolean} stillWanted asked again after the answer of recent-tracks (e.g. Auto-Pilot still on)
     * @returns {Promise<'resumed'|'none'|'changed'>} 'none': nothing to bring back; 'changed': there was, but the player or the
     *          lease changed while we asked
     */
    async function resumeLastPlayed(takeOver, stillWanted) {
        resumeLastTrack = false;
        resumeWithoutAutoPilot = false;
        const interrupted = takeOver ? null : readInterruptedTrack();
        if (!takeOver && !interrupted) return 'none';
        const recent = await fetchRecentTracks();
        const last = recent && recent[0];
        if (!last || typeof last.secondsAgo !== 'number' || last.secondsAgo > RESUME_WITHIN_SECONDS) return 'none';
        if (!takeOver && last.key !== interrupted.key) return 'none';
        if (isPlayerDevice !== true || isLoadingSong() || !stillWanted() || !isPlayerIdle()) return 'changed';
        replayTrack(last);
        return 'resumed';
    }

    /** A resume after a reload is pending for a track the DJ had paused (see tryAutoPlay). */
    function isHeldByPause() {
        const interrupted = readInterruptedTrack();
        return interrupted !== null && interrupted.paused;
    }

    /**
     * "Resume" in a window that has loaded nothing since a reload: the player is empty, so there is nothing to carry on — the track
     * the reload interrupted comes back instead (with Auto-Pilot off, or paused before the reload). With nothing to bring back the
     * resume is over: with Auto-Pilot on the queue goes on, with it off nothing plays (as before).
     */
    async function resumeAfterReload() {
        if (tryAutoPlayInFlight) return;
        tryAutoPlayInFlight = true;
        let outcome;
        try {
            outcome = await resumeLastPlayed(false, function () { return true; });
        } finally {
            tryAutoPlayInFlight = false;
        }
        if (outcome === 'none') tryAutoPlay();
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

    /**
     * A window that does not play has two buttons for "back" instead of the single ⏮: the previous track (always) and the
     * current track from the start. The single ⏮ has rules — a track that has played for a while starts again, a second press
     * within 20 s goes back a track — that work at the computer, where two presses are a moment apart, but are hard to use from
     * a remote (the DJ's report, 2026-09-30: "wstecz nie jest intuicyjne"). The window that plays, and a window that does not
     * know yet (the first answer of the server is on its way), shows the single ⏮ as before.
     */
    function renderBackButtons() {
        const remote = isPlayerDevice === false;
        const single = document.getElementById('playerPreviousBtn');
        if (single) single.classList.toggle('d-none', remote);
        ['playerBackBtn', 'playerRestartBtn'].forEach(function (id) {
            const button = document.getElementById(id);
            if (button) button.classList.toggle('d-none', !remote);
        });
    }

    /** This window lost the lease: what it plays stops, the window that took over carries on from the queue. */
    function stopPlaybackHere() {
        if (player && playerReady) player.stopVideo();
        playerState = -1;
        trackIsOver();
        endRetrace();
    }

    /** The server says who plays: the answer of every lease report, and a 409 from next-track. */
    function applyLease(holder, free) {
        const wasPlayer = isPlayerDevice === true;
        isPlayerDevice = holder;
        if (!holder) resumeLastTrack = resumeWithoutAutoPilot = false; // another window plays
        // "Play on this device" was pressed: whichever answer makes this window the holder first — the TAKE_OVER's, or a WATCH report
        // that was on its way and was answered after the server had moved the lease — it carries on with the track the other device
        // was playing, not the next one. (Setting it only on the TAKE_OVER's answer lost it whenever the WATCH answer came first:
        // the window had already asked next-track, and the TAKE_OVER's answer was then dropped as the older one.)
        if (holder && !wasPlayer && takeOverPending) resumeLastTrack = resumeWithoutAutoPilot = true;
        if (holder) takeOverPending = false;
        leaseFree = !holder && free;
        renderLeaseBanner();
        renderBackButtons();
        updatePauseButton();
        if (holder && !wasPlayer) {
            tryAutoPlay();
        } else if (!holder && (wasPlayer || isPlaying())) {
            stopPlaybackHere(); // also a window that never played but whose YouTube player the DJ started by hand
        }
    }

    // ---- The "up next" list of a window that did not change it itself ----

    /** The list was fetched (dashboard.js) with this version: remember it, so that the next lease answer does not ask for it again. */
    window.setKnownQueueVersion = function (version) {
        lastQueueVersion = version;
    };

    function noteQueueVersion(version) {
        const changedElsewhere = lastQueueVersion !== null && version !== lastQueueVersion;
        lastQueueVersion = version;
        if (changedElsewhere && typeof window.refreshFallbackQueue === 'function') window.refreshFallbackQueue();
    }

    // ---- Next: skip to the next track, from any window ----

    /**
     * Skips to whatever next-track hands out now — whatever the player is doing, and with Auto-Pilot off too: it is a
     * deliberate act of the DJ. Only the window that plays can do it; a window that does not sends the command to it
     * (onControlClick). When there is nothing to play (204) the track that plays now carries on.
     *
     * After ⏮ it retraces the steps instead (isRetracing): the track that came back is followed by the entry of the
     * server's timeline that is one *newer* — so ⏮ then ⏭ returns to where the DJ was, guest song included (a guest song
     * that has played is no longer in the queue, so next-track would never hand it out again). Only at the newest entry
     * — or with a track the DJ picked by hand, which is not in the timeline — it asks next-track as usual. Like ⏮, it does
     * nothing when the server cannot say what played.
     */
    async function skipToNext() {
        if (isPlayerDevice !== true || !playerReady || !player || tryAutoPlayInFlight) return;
        tryAutoPlayInFlight = true;
        try {
            if (isRetracing()) {
                const recent = await fetchRecentTracks();
                if (!recent || isPlayerDevice !== true) return; // the server could not say, or the lease moved while we asked
                const key = nowPlayingKey();
                const position = key ? recent.findIndex(function (t) { return t.key === key; }) : -1;
                if (position > 0) {   // newest first: the entry before this one is the newer one
                    replayTrack(recent[position - 1]);
                    return;
                }
            }
            // The guest song that runs now is excluded: it leaves the queue only once the server has its confirmation (POST /play,
            // sent on PLAYING) — a ⏭ while it loads, or before the confirmation arrived, got the same song back.
            const track = await fetchNextTrack(runningGuestSongId());
            if (!track || isPlayerDevice !== true) return; // nothing to play, or the lease moved while we asked
            playTrack(track);
        } finally {
            tryAutoPlayInFlight = false;
        }
    }

    // ---- Back: like a normal player, along the server's timeline of what played ----

    // A track that has played for more than this many seconds starts again first. (The button's tooltip in the message
    // bundles says "3 seconds".)
    const RESTART_AFTER_SECONDS = 3;

    // A second ⏮ within this long after a restart that ⏮ itself caused goes back a track instead of restarting the
    // track again. Needed because a press from another window reaches the window that plays only with its next report
    // (every 3 s, and the button is disabled for 3.5 s), so two presses are always more than RESTART_AFTER_SECONDS
    // apart — without this the previous track could never be reached from the phone. (The tooltip says "20 seconds".)
    const DOUBLE_PRESS_MS = 20000;

    // The restart ⏮ caused last: the number of the load it restarted (so it counts only for that track) and the time.
    let lastRestart = null;

    /** The tracks that played most recently and can be played again, newest first — null when the server could not say. */
    async function fetchRecentTracks() {
        if (!partyCodeValue) return null;
        try {
            const response = await fetch('/dj/dashboard/recent-tracks?partyCode=' + encodeURIComponent(partyCodeValue));
            if (!response.ok || response.redirected) return null;
            return await response.json();
        } catch (e) {
            console.error('[YT] fetchRecentTracks error:', e);
            return null;
        }
    }

    /** True while the player is on a track that has been running for a while (playing, paused or buffering). */
    function hasPlayedForAWhile() {
        const running = playerState === YT.PlayerState.PLAYING || playerState === YT.PlayerState.PAUSED
            || playerState === YT.PlayerState.BUFFERING;
        return running && typeof player.getCurrentTime === 'function' && player.getCurrentTime() > RESTART_AFTER_SECONDS;
    }

    /** True when the track that runs now was restarted by ⏮ a moment ago (less than DOUBLE_PRESS_MS): a second ⏮ then goes back a track. */
    function restartedByBackJustNow() {
        return lastRestart !== null && current !== null && lastRestart.loadNo === current.loadNo
            && Date.now() - lastRestart.at < DOUBLE_PRESS_MS;
    }

    /** Plays a track that has played before: nothing to confirm, not a background track, Auto-Pilot carries on when it ends. */
    function replayTrack(track) {
        startTrack('HISTORY', track.videoId, { key: track.key });
    }

    /**
     * Back, like a normal player: a track that has played for more than a few seconds starts again; otherwise the
     * track that played before it comes back — and, pressed again, the one before that. A second press soon after a
     * restart that ⏮ caused (DOUBLE_PRESS_MS) counts as "otherwise": it goes back a track at once, however long the
     * restarted track has played by then (from another window the presses are always more than a few seconds apart).
     * The list is the server's
     * timeline of what played (guest songs and background tracks), so it is the same whichever window played them; the
     * track that plays now is found in it by its key, and one the DJ picked by hand is not in it — then the newest entry
     * is the one to go back to. When nothing plays (the track ended), the track that ended is the one that comes back.
     * A track that comes back is not marked as played again, and when it ends Auto-Pilot carries on with the queue, so a
     * party does not hear the tracks in between twice by accident; ⏭ on the other hand retraces the steps (skipToNext).
     * With nothing older to go back to the track starts again.
     */
    async function skipToPrevious() {
        if (isPlayerDevice !== true || !playerReady || !player || tryAutoPlayInFlight) return;
        if (hasPlayedForAWhile() && !restartedByBackJustNow()) {
            lastRestart = { loadNo: current.loadNo, at: Date.now() };
            player.seekTo(0, true);
            return;
        }
        return goToPreviousTrack();
    }

    /**
     * The previous track, always — no restart first: the window that does not play has this as its own button (PREVIOUS_TRACK),
     * next to "from the start" (RESTART), because the single ⏮ with its rules (restart first, back on a second press) is hard to
     * use from a remote. The same walk back as in skipToPrevious, including "the track that ended is what back goes to"; with
     * nothing older the track starts again.
     */
    async function goToPreviousTrack() {
        if (isPlayerDevice !== true || !playerReady || !player || tryAutoPlayInFlight) return;
        tryAutoPlayInFlight = true;
        try {
            const recent = await fetchRecentTracks();
            if (!recent || isPlayerDevice !== true) return; // the server could not say, or the lease moved while we asked
            const key = nowPlayingKey();
            const position = key ? recent.findIndex(function (t) { return t.key === key; }) : -1;
            const target = recent[isPlayerIdle() && position >= 0 ? position : position + 1];
            if (target) replayTrack(target);
            else player.seekTo(0, true);
        } finally {
            tryAutoPlayInFlight = false;
        }
    }

    /**
     * "From the start" (RESTART): the track that runs starts again, however long it has played — playing, paused or still
     * loading (a paused track stays paused). It never goes back a track and does not count for the double press of ⏮. When
     * nothing runs because the track ended (Auto-Pilot off), that track plays again, like back does; a track the DJ picked
     * by hand is not in the list, so then there is nothing to play again.
     */
    async function restartTrack() {
        if (isPlayerDevice !== true || !playerReady || !player || tryAutoPlayInFlight) return;
        if (!isPlayerIdle() || isLoadingSong()) {
            player.seekTo(0, true);
            return;
        }
        const key = nowPlayingKey();
        if (!key) return;
        tryAutoPlayInFlight = true;
        try {
            const recent = await fetchRecentTracks();
            if (!recent || isPlayerDevice !== true) return; // the server could not say, or the lease moved while we asked
            const entry = recent.find(function (t) { return t.key === key; });
            if (entry) replayTrack(entry);
        } finally {
            tryAutoPlayInFlight = false;
        }
    }

    /** Carries out a command in this window, which plays: what a press here does, and what a command from another window does. */
    function runCommandHere(command) {
        if (command === 'NEXT') skipToNext();
        else if (command === 'PREVIOUS') skipToPrevious();
        else if (command === 'PREVIOUS_TRACK') goToPreviousTrack();
        else if (command === 'RESTART') restartTrack();
        else if (command === 'PAUSE') pauseHere();
        else if (command === 'RESUME') resumeHere();
    }

    // A little longer than one lease report interval: the time a command needs to reach the window that plays.
    const COMMAND_PENDING_MS = 3500;

    function setCommandPending(button, pending) {
        if (!button) return;
        button.disabled = pending;
        const label = button.querySelector('[data-role="label"]');
        if (label) label.textContent = pending ? button.dataset.textSent : button.dataset.textLabel;
        if (!pending && button.id === 'playerPauseBtn') updatePauseButton(); // its label follows the player, not a fixed text
    }

    // ---- Pause / resume, from any window ----

    /** Pauses the music here (nothing to do when it is paused already). A pause is the DJ's choice: Auto-Pilot leaves a paused player alone. */
    function pauseHere() {
        if (player && playerReady && typeof player.pauseVideo === 'function') player.pauseVideo();
    }

    /**
     * Carries on after a pause. (A browser may refuse to start sound in a window nobody has touched — then it stays paused.) Right
     * after a reload the player is empty: then the track the reload interrupted comes back (resumeAfterReload).
     */
    function resumeHere() {
        if (!player || !playerReady) return;
        if (resumeLastTrack && !resumeWithoutAutoPilot && isPlayerDevice === true && !isLoadingSong() && isPlayerIdle()) {
            resumeAfterReload();
            return;
        }
        if (typeof player.playVideo === 'function') player.playVideo();
    }

    /**
     * What a press of the pause button would do: pause while the music plays, resume while it is paused. In the window
     * that plays that is its own player; in another window it is what the window that plays last reported (unknown →
     * "pause", the usual case). Explicit commands rather than a toggle: a stale button cannot invert the state.
     */
    function pauseButtonShowsPause() {
        const playing = isPlayerDevice === true ? isPlayingOrLoading() : holderPlaying;
        return playing !== false;
    }

    // After a remote pause or resume the button keeps saying "Sent…" until the window that plays reports that its player
    // has changed — or a while has passed — instead of showing the old label again for a moment.
    const PAUSE_PENDING_MAX_MS = 9000;
    let pausePendingTarget = null, pausePendingTimer = null;

    function finishPausePending() {
        clearTimeout(pausePendingTimer);
        pausePendingTimer = null;
        pausePendingTarget = null;
        setCommandPending(document.getElementById('playerPauseBtn'), false);
    }

    function updatePauseButton() {
        const button = document.getElementById('playerPauseBtn');
        if (!button) return;
        if (pausePendingTarget !== null && holderPlaying === pausePendingTarget) {
            finishPausePending(); // the player did what was asked; this call comes back through setCommandPending
            return;
        }
        button.dataset.textLabel = pauseButtonShowsPause() ? button.dataset.textPause : button.dataset.textResume;
        if (button.disabled) return; // "Sent…" is showing; the label comes back when that is over
        const label = button.querySelector('[data-role="label"]');
        if (label) label.textContent = button.dataset.textLabel;
    }

    /**
     * The ⏮, ⏯ and ⏭ buttons. In the window that plays a button acts at once; in another window it sends the command
     * to the one that plays (it carries it out with its next report, so within a few seconds) and stays disabled
     * meanwhile — a second press would not act twice: only one command waits, the last one pressed.
     */
    async function onControlClick(command, button) {
        if (isPlayerDevice === true) {
            runCommandHere(command);
            return;
        }
        if (isPlayerDevice !== false || !partyCodeValue) return; // the server has not said who plays yet
        setCommandPending(button, true);
        try {
            const response = await fetch('/dj/dashboard/player-command', {
                method: 'POST',
                headers: { [csrf.header]: csrf.token },
                body: new URLSearchParams({ partyCode: partyCodeValue, command: command })
            });
            if (response.status === 409) { // nobody plays: the banner says so and offers to play here
                applyLease(false, true);
                setCommandPending(button, false);
                return;
            }
            if (!response.ok || response.redirected) {
                setCommandPending(button, false);
                return;
            }
            if (command === 'PAUSE' || command === 'RESUME') {
                pausePendingTarget = command === 'RESUME';   // the state the player should end up in: playing after RESUME
                pausePendingTimer = setTimeout(finishPausePending, PAUSE_PENDING_MAX_MS);
            } else {
                setTimeout(function () { setCommandPending(button, false); }, COMMAND_PENDING_MS);
            }
        } catch (e) {
            console.error('[YT] player-command error:', e);
            setCommandPending(button, false);
        }
    }

    const previousButton = document.getElementById('playerPreviousBtn');
    if (previousButton) previousButton.addEventListener('click', function () { onControlClick('PREVIOUS', previousButton); });
    // The two buttons of a window that does not play (renderBackButtons): the previous track, and the current track from the start.
    const backButton = document.getElementById('playerBackBtn');
    if (backButton) backButton.addEventListener('click', function () { onControlClick('PREVIOUS_TRACK', backButton); });
    const restartButton = document.getElementById('playerRestartBtn');
    if (restartButton) restartButton.addEventListener('click', function () { onControlClick('RESTART', restartButton); });
    const pauseButton = document.getElementById('playerPauseBtn');
    if (pauseButton) pauseButton.addEventListener('click', function () {
        onControlClick(pauseButtonShowsPause() ? 'PAUSE' : 'RESUME', pauseButton);
    });
    const nextButton = document.getElementById('playerNextBtn');
    if (nextButton) nextButton.addEventListener('click', function () { onControlClick('NEXT', nextButton); });

    /** @param {'CLAIM'|'WATCH'|'TAKE_OVER'} mode see PlayerLeaseMode on the server */
    async function reportLease(mode) {
        if (!partyCodeValue) return;
        const seq = ++leaseRequestSeq;
        const sentAt = Date.now();
        try {
            const params = { partyCode: partyCodeValue, deviceId: deviceId, mode: mode };
            // Whether this window's player makes sound — only the window that plays is believed, so a watcher says nothing.
            if (mode !== 'WATCH') params.playing = String(isPlayingOrLoading());
            const response = await fetch('/dj/dashboard/player-lease', {
                method: 'POST',
                headers: { [csrf.header]: csrf.token },
                body: new URLSearchParams(params)
            });
            // A hiccup (server error, login redirect) keeps the current role: it must neither silence the
            // window that plays nor make another one start.
            if (!response.ok || response.redirected) return;
            const lease = await response.json();
            if (seq < leaseAppliedSeq) return; // a newer answer has been applied already
            leaseAppliedSeq = seq;
            // The party's Auto-Pilot setting, one for all windows (dashboard.js) — before applyLease, so that a window that has just
            // become the holder decides with the current value.
            if (lease.playbackMode && typeof window.applyPlaybackMode === 'function') window.applyPlaybackMode(lease.playbackMode, sentAt);
            applyLease(lease.holder === true, lease.free === true);
            notePlaylist(lease.fallbackPlaylistId || null, seq);
            if (lease.holder === true) dropStaleBackgroundTrack(lease.fallbackPlaylistId || null, seq);
            if (lease.queueVersion) noteQueueVersion(lease.queueVersion);
            holderPlaying = typeof lease.playing === 'boolean' ? lease.playing : null;
            updatePauseButton();
            // A command the DJ gave from another window (the phone as a remote control), handed out once.
            if (lease.holder === true && lease.command) runCommandHere(lease.command);
        } catch (e) {
            console.error('[YT] reportLease error:', e);
        }
    }

    function leaseLoop() {
        // A window that has been told another one plays only watches; it takes the lease again only when the DJ asks.
        reportLease(isPlayerDevice === false ? 'WATCH' : 'CLAIM')
            .finally(() => {
                if (askAgain) tryAutoPlay(); // tryAutoPlay itself checks: this window plays, Auto-Pilot on, the player idle
                setTimeout(leaseLoop, LEASE_REPORT_INTERVAL_MS);
            });
    }

    function takeOverPlayback() {
        const banner = document.getElementById('playerLeaseBanner');
        // Taking the lease from a window that still plays stops it there — ask first (a stray tap on a phone).
        if (!leaseFree && banner && !window.confirm(banner.dataset.confirm)) return;
        takeOverPending = true;
        reportLease('TAKE_OVER');
    }

    const takeOverButton = document.getElementById('playerLeaseTakeover');
    if (takeOverButton) takeOverButton.addEventListener('click', takeOverPlayback);

    /**
     * The track the page before this one (a reload of the tab) interrupted when it went away: {key, paused} — paused when the DJ
     * had paused it — or null.
     */
    function readInterruptedTrack() {
        try {
            const note = JSON.parse(window.sessionStorage.getItem(INTERRUPTED_TRACK_KEY));
            return note && typeof note.key === 'string' ? { key: note.key, paused: note.paused === true } : null;
        } catch (e) {
            return null; // storage blocked or an unreadable note: a reload starts from the queue
        }
    }

    /**
     * Going away (a reload, the tab closed): note the track this page interrupts, for a reload of the tab (resumeLastTrack) — the
     * track that plays, is paused or is loading, if this window plays and the timeline knows it (a track picked by hand does not
     * count). Nothing is noted for a track that ended by itself or when another window plays. A page that has loaded nothing passes
     * on the note it found (it was reloaded again before Auto-Pilot was switched on).
     */
    function noteInterruptedTrack() {
        try {
            const paused = playerState === YT.PlayerState.PAUSED;
            const interrupted = isPlayerDevice === true && nowPlayingKey() && (isPlayingOrLoading() || paused);
            if (interrupted) {
                window.sessionStorage.setItem(INTERRUPTED_TRACK_KEY, JSON.stringify({ key: nowPlayingKey(), paused: paused }));
            } else if (loads > 0 || isPlayerDevice === false) {
                window.sessionStorage.removeItem(INTERRUPTED_TRACK_KEY);
            }
        } catch (e) {
            // storage blocked: nothing to note
        }
    }

    // Going away (tab closed, another page opened): give the lease up at once, so that the next window does not
    // have to wait for the timeout. sendBeacon cannot set headers, so the CSRF token goes in the body.
    window.addEventListener('pagehide', function () {
        noteInterruptedTrack();
        if (isPlayerDevice !== true || !partyCodeValue || !navigator.sendBeacon) return;
        navigator.sendBeacon('/dj/dashboard/player-lease/release', new URLSearchParams({
            partyCode: partyCodeValue, deviceId: deviceId, _csrf: csrf.token
        }));
    });

    leaseLoop();

    // ---- Public API ----

    window.checkYouTubeAutoPlay = tryAutoPlay;

    /**
     * The DJ saved a different background playlist (the server already switched): drop the old track, start from the new one.
     * A track that came back through ⏮ keeps playing, but ⏭ no longer retraces the steps (notePlaylist) — it starts the new playlist.
     */
    window.updateFallbackSource = function () {
        endRetrace();
        stopBackgroundTrack();
        tryAutoPlay();
    };

    /** The DJ cleared the playlist: the running background track stops (a guest song plays on), and ⏭ no longer retraces (notePlaylist). */
    window.stopFallback = function () {
        endRetrace();
        stopBackgroundTrack();
    };

    window.playInEmbeddedPlayer = function (trackUrl) {
        // A window that does not hold the lease must not start sound by a stray tap: the ▶ link then simply opens on YouTube.
        if (isPlayerDevice !== true || !playerReady || !player) return false;
        const videoId = extractVideoId(trackUrl);
        if (!videoId) return false;
        // Picked by hand: nothing to confirm, not a background track. Auto-Pilot carries on when it ends.
        startTrack('MANUAL', videoId);
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
