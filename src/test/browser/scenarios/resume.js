// Resume after a reload (the owner's decision 2026-09-30, youtube-autopilot.js `resumeLastTrack`). A dashboard loaded again used to ask
// next-track at once: the next track was handed out (marked played, in the history) while the browser — which refuses sound in a page
// nobody has touched — only loaded it, so every reload used up a track nobody heard. Now the window that plays brings back the newest
// entry of the server's timeline (recent-tracks, as ⏮ does) when it started at most 10 minutes ago; otherwise, and when the page was
// loaded with Auto-Pilot off, it asks next-track as before. The same on "play on this device" (the owner, the same day: a takeover used to
// skip the track the other device was playing).
(function () {
    const PLAYLIST = 'PLscenario0000000000000000000000000';
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
        setup: { lease: { fallbackPlaylistId: PLAYLIST }, recent: recent(601), nextTracks: [track(1, 'aaaaaaaaaaA')] },
        run: async function (t) {
            await t.waitForTrack(1, 'the first track');
            t.step('the first track comes from the queue', [t.fake.loads.map(t.letter), await t.stand.count(t.NEXT_TRACK)], [['a'], 1]);
        }
    });

    S2P.scenario({
        name: 'no-resume-when-loaded-with-autopilot-off',
        title: 'loaded with Auto-Pilot off: nothing starts; switching it on later starts from the queue, not the old track',
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
