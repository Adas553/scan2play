// Resume after a reload (the owner's decision 2026-09-30, youtube-autopilot.js `resumeLastTrack`). A dashboard loaded again used to ask
// next-track at once: the next track was handed out (marked played, in the history) while the browser — which refuses sound in a page
// nobody has touched — only loaded it, so every reload used up a track nobody heard. Now the window that plays brings back the newest
// entry of the server's timeline (recent-tracks, as ⏮ does) when it started at most 10 minutes ago; otherwise it asks next-track as
// before. The same on "play on this device" (the owner, the same day: a takeover used to skip the track the other device was playing).
//
// Since 2026-10-01 (the owner's report: a track playing, Auto-Pilot switched off, a reload, Auto-Pilot on → the NEXT track played) only
// a track the reload INTERRUPTED comes back: the page that played notes its key in sessionStorage when it goes away while the track
// plays or is paused (`scan2play.interruptedTrack`), and a track that ended by itself leaves no note. With Auto-Pilot off at the
// reload the note waits until Auto-Pilot is switched on. The note is what `session` writes below — what the page before the reload left.
(function () {
    const PLAYLIST = 'PLscenario0000000000000000000000000';
    const NOTE = 'scan2play.interruptedTrack';
    // the note: {key, paused} — paused: the DJ had paused the track, so after the reload it waits for "resume" (owner's choice A)
    const interrupted = function (key, paused) { const s = {}; s[NOTE] = JSON.stringify({ key: key, paused: !!paused }); return s; };
    const note = function () { return JSON.parse(sessionStorage.getItem(NOTE)); };
    const pauseButton = function () { return document.getElementById('playerPauseBtn'); };
    const showsResume = function (t) { return t.label('playerPauseBtn') === pauseButton().dataset.textResume; };
    const pagehide = function () { window.dispatchEvent(new Event('pagehide')); };
    const track = function (id, videoId) { return { source: 'BACKGROUND', id: id, videoId: videoId, playlistId: PLAYLIST }; };
    const recent = function (secondsAgo) {
        return [
            { key: 'B:9', source: 'BACKGROUND', id: 9, videoId: 'rrrrrrrrrrR', title: 'The track that played', secondsAgo: secondsAgo },
            { key: 'G:4', source: 'GUEST', id: 4, videoId: 'ppppppppppP', title: 'The one before', secondsAgo: secondsAgo + 200 }
        ];
    };
    const banner = function () { return document.getElementById('playerLeaseBanner'); };
    const bannerShown = function () { return !banner().classList.contains('d-none'); };

    S2P.scenario({
        name: 'resume-after-reload',
        title: 'a reload brings back the track that played last (from its start), asks nothing of the queue; its end and ⏭ go on from the queue',
        session: interrupted('B:9'),
        setup: { lease: { fallbackPlaylistId: PLAYLIST }, recent: recent(120), nextTracks: [track(1, 'aaaaaaaaaaA'), track(2, 'bbbbbbbbbbB')] },
        run: async function (t) {
            await t.waitForTrack(1, 'the first track');
            t.step('the first track is the one that played last', t.fake.loads.map(t.letter), ['r']);
            t.step('no track was taken off the queue for it', await t.stand.count(t.NEXT_TRACK), 0);
            t.step('and it is not confirmed as played again', await t.stand.count(t.PLAY), 0);

            t.fake.end();
            await t.waitForTrack(2, 'the track after it');
            t.step('when it ends Auto-Pilot carries on with the queue', [t.fake.loads.map(t.letter), await t.stand.count(t.NEXT_TRACK)], [['r', 'a'], 1]);
            t.step('⏭ then asks the queue too', [await t.press('playerNextBtn', 800), await t.stand.count(t.NEXT_TRACK)], ['b', 2]);
        }
    });

    S2P.scenario({
        name: 'resume-back-goes-further',
        title: 'after the resume ⏮ goes to the track before it (the resumed track has its place in the timeline)',
        session: interrupted('B:9'),
        setup: { lease: { fallbackPlaylistId: PLAYLIST }, recent: recent(60), nextTracks: [track(1, 'aaaaaaaaaaA')] },
        run: async function (t) {
            await t.waitForTrack(1, 'the resumed track');
            t.step('the resumed track plays', t.letter(t.fake.loads[0]), 'r');
            t.step('⏮ at its start goes to the one before', await t.press('playerPreviousBtn', 800), 'p');
        }
    });

    S2P.scenario({
        name: 'no-resume-when-long-ago',
        title: 'the last track started more than 10 minutes ago: the reload starts from the queue, as before',
        session: interrupted('B:9'),
        setup: { lease: { fallbackPlaylistId: PLAYLIST }, recent: recent(601), nextTracks: [track(1, 'aaaaaaaaaaA')] },
        run: async function (t) {
            await t.waitForTrack(1, 'the first track');
            t.step('the first track comes from the queue', [t.fake.loads.map(t.letter), await t.stand.count(t.NEXT_TRACK)], [['a'], 1]);
        }
    });

    S2P.scenario({
        name: 'no-resume-when-loaded-with-autopilot-off',
        title: 'loaded with Auto-Pilot off after a track that ended: nothing starts; switching it on later starts from the queue, not the old track',
        page: 'dashboard-manual',
        setup: { playbackMode: 'MANUAL', lease: { fallbackPlaylistId: PLAYLIST }, recent: recent(30), nextTracks: [track(1, 'aaaaaaaaaaA')] },
        run: async function (t) {
            await t.sleep(3500);
            t.step('nothing plays', t.fake.loads.length, 0);
            document.getElementById('autoToggle').click();
            await t.waitForTrack(1, 'the first track after Auto-Pilot was switched on');
            t.step('the track comes from the queue', [t.fake.loads.map(t.letter), await t.stand.count(t.NEXT_TRACK)], [['a'], 1]);
        }
    });

    S2P.scenario({
        name: 'resume-when-autopilot-is-switched-on-after-a-reload',
        title: 'the owner\'s case: a track interrupted by a reload with Auto-Pilot off comes back (from its start) when Auto-Pilot is switched on',
        page: 'dashboard-manual',
        session: interrupted('B:9'),
        setup: { playbackMode: 'MANUAL', lease: { fallbackPlaylistId: PLAYLIST }, recent: recent(30), nextTracks: [track(1, 'aaaaaaaaaaA')] },
        run: async function (t) {
            await t.sleep(3500);
            t.step('with Auto-Pilot off nothing plays', t.fake.loads.length, 0);
            document.getElementById('autoToggle').click();
            await t.waitForTrack(1, 'the first track after Auto-Pilot was switched on');
            t.step('it is the track the reload interrupted', t.fake.loads.map(t.letter), ['r']);
            t.step('no track was taken off the queue for it', await t.stand.count(t.NEXT_TRACK), 0);
            t.fake.end();
            await t.waitForTrack(2, 'the track after it');
            t.step('its end goes on with the queue', t.fake.loads.map(t.letter), ['r', 'a']);
        }
    });

    S2P.scenario({
        name: 'no-resume-of-a-track-that-ended',
        title: 'a reload after the last track ended by itself (no note): the first track comes from the queue, the ended one is not played again',
        setup: { lease: { fallbackPlaylistId: PLAYLIST }, recent: recent(30), nextTracks: [track(1, 'aaaaaaaaaaA')] },
        run: async function (t) {
            await t.waitForTrack(1, 'the first track');
            t.step('the first track comes from the queue', [t.fake.loads.map(t.letter), await t.stand.count(t.NEXT_TRACK)], [['a'], 1]);
        }
    });

    S2P.scenario({
        name: 'no-resume-when-another-track-is-newer',
        title: 'the interrupted track is no longer the newest of the timeline (another window played since): the queue goes on',
        session: interrupted('B:7'),
        setup: { lease: { fallbackPlaylistId: PLAYLIST }, recent: recent(30), nextTracks: [track(1, 'aaaaaaaaaaA')] },
        run: async function (t) {
            await t.waitForTrack(1, 'the first track');
            t.step('the first track comes from the queue', [t.fake.loads.map(t.letter), await t.stand.count(t.NEXT_TRACK)], [['a'], 1]);
        }
    });

    S2P.scenario({
        name: 'the-page-notes-an-interrupted-track-when-it-goes-away',
        title: 'going away while a track plays or is paused leaves its key for the next page; after a track that ended, nothing',
        setup: { lease: { fallbackPlaylistId: PLAYLIST }, nextTracks: [track(1, 'aaaaaaaaaaA')] },
        run: async function (t) {
            await t.waitForTrack(1, 'the first track');
            pagehide();
            t.step('playing: its key is noted', note(), { key: 'B:1', paused: false });
            document.getElementById('playerPauseBtn').click();
            await t.waitFor(function () { return t.fake.state === 2; }, 'paused');
            pagehide();
            t.step('paused: noted as paused', note(), { key: 'B:1', paused: true });
            document.getElementById('playerPauseBtn').click();
            await t.waitForTrack(1, 'playing again');
            t.fake.end();               // the queue has nothing more (204): the player stays at ENDED
            await t.sleep(800);
            pagehide();
            t.step('ended by itself: no note', note(), null);
        }
    });

    S2P.scenario({
        name: 'a-page-that-played-nothing-keeps-the-note',
        title: 'a page loaded with Auto-Pilot off and reloaded again before anything played passes the note on',
        page: 'dashboard-manual',
        session: interrupted('B:9'),
        setup: { playbackMode: 'MANUAL', lease: { fallbackPlaylistId: PLAYLIST }, recent: recent(30) },
        run: async function (t) {
            await t.sleep(3500);
            pagehide();
            t.step('the note is still there', note(), { key: 'B:9', paused: false });
        }
    });

    S2P.scenario({
        name: 'resume-button-after-a-reload-with-autopilot-off',
        title: 'after a reload with Auto-Pilot off, "resume" brings back the interrupted track (it did nothing: the player was empty)',
        page: 'dashboard-manual',
        session: interrupted('B:9'),
        setup: { playbackMode: 'MANUAL', lease: { fallbackPlaylistId: PLAYLIST }, recent: recent(30), nextTracks: [track(1, 'aaaaaaaaaaA')] },
        run: async function (t) {
            await t.sleep(3500);
            t.step('nothing plays, the button says "resume"', [t.fake.loads.length, showsResume(t)], [0, true]);
            t.step('"resume" brings back the interrupted track', await t.press('playerPauseBtn', 800), 'r');
            t.step('nothing was taken off the queue, nothing confirmed again',
                [await t.stand.count(t.NEXT_TRACK), await t.stand.count(t.PLAY)], [0, 0]);
            t.fake.end();
            await t.sleep(800);
            t.step('Auto-Pilot is off: nothing follows it', t.fake.loads.map(t.letter), ['r']);
        }
    });

    S2P.scenario({
        name: 'resume-button-after-a-reload-without-a-note',
        title: 'after a reload that interrupted nothing (the track had ended), "resume" plays nothing, as before',
        page: 'dashboard-manual',
        setup: { playbackMode: 'MANUAL', lease: { fallbackPlaylistId: PLAYLIST }, recent: recent(30), nextTracks: [track(1, 'aaaaaaaaaaA')] },
        run: async function (t) {
            await t.sleep(3500);
            t.step('"resume" loads nothing', await t.press('playerPauseBtn', 800), 'nothing');
            t.step('and asks nothing', await t.stand.count(t.NEXT_TRACK), 0);
            // the owner's report (2026-10-01): a second "resume" called playVideo on the empty player, and YouTube showed its error
            // screen ("An error occurred… playback ID") instead of the black player
            t.step('a second "resume" loads nothing either', await t.press('playerPauseBtn', 800), 'nothing');
            t.step('and never starts the empty player', t.fake.calls.filter(function (c) { return c[0] === 'playVideo'; }).length, 0);
        }
    });

    // The big ▶ of YouTube’s own player on an empty player (after a reload nothing is loaded): the real player reports error 2 and
    // shows "An error occurred… playback ID" (the owner, 2026-10-01). The owner's decision: it starts the music — like "resume" (the
    // track the reload interrupted comes back), and when there is nothing to bring back, like ⏭ — with Auto-Pilot off too.
    S2P.scenario({
        name: 'youtube-play-on-an-empty-player-resumes-the-interrupted-track',
        title: 'after a reload with Auto-Pilot off, YouTube’s own ▶ on the empty player brings back the interrupted track',
        page: 'dashboard-manual',
        session: interrupted('B:9'),
        setup: { playbackMode: 'MANUAL', lease: { fallbackPlaylistId: PLAYLIST }, recent: recent(30), nextTracks: [track(1, 'aaaaaaaaaaA')] },
        run: async function (t) {
            await t.sleep(3500);
            t.step('nothing plays yet', t.fake.loads.length, 0);
            t.fake.clickPlay();
            await t.sleep(800);
            t.step('the interrupted track comes back', t.fake.loads.map(t.letter), ['r']);
            t.step('nothing was taken off the queue', await t.stand.count(t.NEXT_TRACK), 0);
        }
    });

    S2P.scenario({
        name: 'youtube-play-on-an-empty-player-plays-the-next-track',
        title: 'after a reload that interrupted nothing, YouTube’s own ▶ on the empty player plays the next track, as ⏭ (Auto-Pilot off)',
        page: 'dashboard-manual',
        setup: { playbackMode: 'MANUAL', lease: { fallbackPlaylistId: PLAYLIST }, recent: recent(30),
                 nextTracks: [track(1, 'aaaaaaaaaaA'), track(2, 'bbbbbbbbbbB')] },
        run: async function (t) {
            await t.sleep(3500);
            t.fake.clickPlay();
            await t.waitForTrack(1, 'the next track plays');
            t.step('the next track of the queue plays', t.fake.loads.map(t.letter), ['a']);
            t.step('asked once', await t.stand.count(t.NEXT_TRACK), 1);
            t.fake.end();
            await t.sleep(800);
            t.step('Auto-Pilot is off: nothing follows it', t.fake.loads.map(t.letter), ['a']);
        }
    });

    S2P.scenario({
        name: 'youtube-play-on-an-empty-player-resumes-a-paused-track',
        title: 'a track paused before the reload waits (Auto-Pilot on); YouTube’s own ▶ on the empty player brings it back, as "resume"',
        session: interrupted('B:9', true),
        setup: { lease: { fallbackPlaylistId: PLAYLIST }, recent: recent(30), nextTracks: [track(1, 'aaaaaaaaaaA')] },
        run: async function (t) {
            await t.sleep(3500);
            t.step('the paused track waits', t.fake.loads.length, 0);
            t.fake.clickPlay();
            await t.sleep(800);
            t.step('it comes back', t.fake.loads.map(t.letter), ['r']);
            t.step('nothing was taken off the queue', await t.stand.count(t.NEXT_TRACK), 0);
        }
    });

    S2P.scenario({
        name: 'a-paused-track-waits-after-a-reload',
        title: 'a track paused when the page was reloaded stays held with Auto-Pilot on (a pause is the DJ\'s choice); "resume" brings it back',
        session: interrupted('B:9', true),
        setup: { lease: { fallbackPlaylistId: PLAYLIST }, recent: recent(30), nextTracks: [track(1, 'aaaaaaaaaaA')] },
        run: async function (t) {
            await t.sleep(7000);   // the poll and two lease reports would have started something
            t.step('nothing starts, the queue is not asked', [t.fake.loads.length, await t.stand.count(t.NEXT_TRACK)], [0, 0]);
            t.step('the button says "resume"', showsResume(t), true);
            t.step('"resume" brings back the paused track', await t.press('playerPauseBtn', 800), 'r');
            t.fake.end();
            await t.waitForTrack(2, 'the track after it');
            t.step('Auto-Pilot is on: its end goes on with the queue', t.fake.loads.map(t.letter), ['r', 'a']);
        }
    });

    S2P.scenario({
        name: 'a-paused-track-from-long-ago-gives-way-to-the-queue',
        title: 'the paused track started more than 10 minutes ago: held until "resume", which then starts the queue (Auto-Pilot on)',
        session: interrupted('B:9', true),
        setup: { lease: { fallbackPlaylistId: PLAYLIST }, recent: recent(601), nextTracks: [track(1, 'aaaaaaaaaaA')] },
        run: async function (t) {
            await t.sleep(3500);
            t.step('nothing starts', t.fake.loads.length, 0);
            t.step('"resume" starts the queue', await t.press('playerPauseBtn', 1000), 'a');
            t.step('asked once', await t.stand.count(t.NEXT_TRACK), 1);
        }
    });

    S2P.scenario({
        name: 'resume-on-takeover',
        title: '"play on this device" while another device plays: this window carries on with that track (from its start), not the next one',
        setup: { lease: { holder: false, free: false, fallbackPlaylistId: PLAYLIST }, recent: recent(30), nextTracks: [track(1, 'aaaaaaaaaaA')] },
        run: async function (t) {
            window.confirm = function () { return true; };
            await t.waitFor(bannerShown, 'the banner');
            await t.sleep(3500);
            t.step('while another device plays this window plays nothing', t.fake.loads.length, 0);
            await t.stand.config({ lease: { holder: true, free: false } });
            document.getElementById('playerLeaseTakeover').click();
            await t.waitForTrack(1, 'the track after the takeover');
            t.step('it is the track the other device was playing', t.fake.loads.map(t.letter), ['r']);
            t.step('no track was taken off the queue', await t.stand.count(t.NEXT_TRACK), 0);
            t.fake.end();
            await t.waitForTrack(2, 'the track after it');
            t.step('its end goes on with the queue', t.fake.loads.map(t.letter), ['r', 'a']);
        }
    });

    S2P.scenario({
        name: 'resume-on-takeover-when-a-watch-answer-comes-first',
        title: 'a WATCH report in flight during "play on this device" is answered first (holder): the window still resumes the last track',
        setup: { lease: { holder: false, free: false, fallbackPlaylistId: PLAYLIST }, recent: recent(30), nextTracks: [track(1, 'aaaaaaaaaaA')] },
        run: async function (t) {
            window.confirm = function () { return true; };
            await t.waitFor(bannerShown, 'the banner');
            // The server has just given this window the lease; the answers of the lease reports take a moment.
            await t.stand.config({ lease: { holder: true, free: false }, delays: { '/dj/dashboard/player-lease': 1.5 } });
            const watch = await t.nextLeaseReport();   // a WATCH on its way: its answer (holder) comes before the TAKE_OVER's
            t.step('a WATCH report is on its way', watch.mode, 'WATCH');
            document.getElementById('playerLeaseTakeover').click();
            await t.waitForTrack(1, 'the track after the takeover');
            t.step('it is the track the other device was playing', t.fake.loads.map(t.letter), ['r']);
            t.step('no track was taken off the queue', await t.stand.count(t.NEXT_TRACK), 0);
            await t.stand.config({ delays: { '/dj/dashboard/player-lease': 0 } });
        }
    });

    S2P.scenario({
        name: 'old-window-cannot-play-after-takeover',
        title: 'a window that lost the lease stops its player, and when the DJ presses play in its YouTube player it is stopped again',
        setup: { lease: { fallbackPlaylistId: PLAYLIST }, nextTracks: [track(1, 'aaaaaaaaaaA')] },
        run: async function (t) {
            await t.waitForTrack(1, 'the first track');
            await t.stand.config({ lease: { holder: false, free: false } });   // another device took over
            await t.waitFor(bannerShown, 'the banner');
            const stops = function () { return t.fake.calls.filter(function (c) { return c[0] === 'stopVideo'; }).length; };
            t.step('its player was stopped', stops(), 1);
            t.fake.emit(1);   // the DJ presses play in the YouTube player of this window (PLAYING)
            await t.sleep(300);
            t.step('it is stopped again at once', [stops(), t.fake.state], [2, -1]);
            t.step('and it asks nothing', await t.stand.count(t.NEXT_TRACK), 1);
        }
    });

    S2P.scenario({
        name: 'resume-on-takeover-of-a-free-lease',
        title: 'nobody plays and the last track started long ago: "play on this device" starts from the queue',
        setup: { lease: { holder: false, free: true, fallbackPlaylistId: PLAYLIST }, recent: recent(900), nextTracks: [track(1, 'aaaaaaaaaaA')] },
        run: async function (t) {
            await t.waitFor(bannerShown, 'the banner');
            await t.stand.config({ lease: { holder: true, free: false } });
            document.getElementById('playerLeaseTakeover').click();
            await t.waitForTrack(1, 'the track after the takeover');
            t.step('the track comes from the queue', [t.fake.loads.map(t.letter), await t.stand.count(t.NEXT_TRACK)], [['a'], 1]);
        }
    });
})();
