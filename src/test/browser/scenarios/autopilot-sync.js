// Auto-Pilot is one setting of the party, and every window follows it (the lease answers carry it — youtube-autopilot.js, dashboard.js
// applyPlaybackMode). The owner found (2026-09-30): with Auto-Pilot switched on on one device and a second device that still showed it
// off (a window knew only the value it was loaded with — the queue poll brings a new one only when the guest queue changes), taking
// the playback over on the second one left its player empty. And the switch sent "toggle", so a click on a stale switch inverted the
// setting. What matters most is what a track does when it ends: with Auto-Pilot on the queue goes on, with it off the player stops.
(function () {
    const PLAYLIST = 'PLscenario0000000000000000000000000';
    const track = function (id, videoId) { return { source: 'BACKGROUND', id: id, videoId: videoId, playlistId: PLAYLIST }; };
    const recent = [{ key: 'B:9', source: 'BACKGROUND', id: 9, videoId: 'rrrrrrrrrrR', title: 'The track that played', secondsAgo: 30 }];
    const banner = function () { return document.getElementById('playerLeaseBanner'); };
    const bannerShown = function () { return !banner().classList.contains('d-none'); };
    const toggle = function () { return document.getElementById('autoToggle'); };
    const mode = function () { return document.getElementById('song-list').getAttribute('data-playback-mode'); };

    S2P.scenario({
        name: 'autopilot-follows-another-device',
        title: 'Auto-Pilot switched on on another device: this window shows it within a report and, as it plays, starts the queue',
        page: 'dashboard-manual',
        setup: { playbackMode: 'MANUAL', lease: { fallbackPlaylistId: PLAYLIST }, nextTracks: [track(1, 'aaaaaaaaaaA')] },
        run: async function (t) {
            await t.sleep(3500);
            t.step('loaded with Auto-Pilot off: the switch is off, nothing plays', [toggle().checked, t.fake.loads.length], [false, 0]);
            await t.stand.config({ playbackMode: 'AUTO' });   // the DJ switched it on on the phone
            await t.reportAfterConfig();
            await t.waitForTrack(1, 'the first track');
            t.step('the switch follows', [toggle().checked, mode()], [true, 'AUTO']);
            t.step('and the queue plays', [t.fake.loads.map(t.letter), await t.stand.count(t.NEXT_TRACK)], [['a'], 1]);
        }
    });

    S2P.scenario({
        name: 'takeover-after-autopilot-switched-on-elsewhere',
        title: 'the owner\'s case: Auto-Pilot switched on elsewhere, this window loaded with it off takes over — the last track plays, its end goes on with the queue',
        page: 'dashboard-manual',
        setup: { playbackMode: 'MANUAL', lease: { holder: false, free: false, fallbackPlaylistId: PLAYLIST }, recent: recent,
            nextTracks: [track(1, 'aaaaaaaaaaA')] },
        run: async function (t) {
            window.confirm = function () { return true; };
            await t.waitFor(bannerShown, 'the banner');
            await t.stand.config({ playbackMode: 'AUTO' });   // the other device runs Auto-Pilot
            await t.reportAfterConfig();
            await t.sleep(300);
            t.step('this window now shows Auto-Pilot on', toggle().checked, true);
            await t.stand.config({ lease: { holder: true, free: false } });
            document.getElementById('playerLeaseTakeover').click();
            await t.waitForTrack(1, 'the track after the takeover');
            t.step('it plays the track the other device was playing', t.fake.loads.map(t.letter), ['r']);
            t.fake.end();
            await t.waitForTrack(2, 'the track after it');
            t.step('when it ends Auto-Pilot takes the next one from the queue', [t.fake.loads.map(t.letter), await t.stand.count(t.NEXT_TRACK)], [['r', 'a'], 1]);
        }
    });

    S2P.scenario({
        name: 'takeover-with-autopilot-off',
        title: 'Auto-Pilot off everywhere: "play on this device" still brings the last track back — and when it ends the player stops',
        page: 'dashboard-manual',
        setup: { playbackMode: 'MANUAL', lease: { holder: false, free: false, fallbackPlaylistId: PLAYLIST }, recent: recent,
            nextTracks: [track(1, 'aaaaaaaaaaA')] },
        run: async function (t) {
            window.confirm = function () { return true; };
            await t.waitFor(bannerShown, 'the banner');
            await t.stand.config({ lease: { holder: true, free: false } });
            document.getElementById('playerLeaseTakeover').click();
            await t.waitForTrack(1, 'the track after the takeover');
            t.step('the last track plays, the queue is not asked', [t.fake.loads.map(t.letter), await t.stand.count(t.NEXT_TRACK)], [['r'], 0]);
            t.fake.end();
            await t.sleep(4000);   // a poll and a lease report
            t.step('when it ends nothing else starts (Auto-Pilot is off)', [t.fake.loads.length, await t.stand.count(t.NEXT_TRACK)], [1, 0]);
        }
    });

    S2P.scenario({
        name: 'autopilot-switched-off-elsewhere-while-playing',
        title: 'Auto-Pilot switched off on another device while a track plays here: the track plays on, and when it ends the player stops',
        setup: { lease: { fallbackPlaylistId: PLAYLIST }, nextTracks: [track(1, 'aaaaaaaaaaA'), track(2, 'bbbbbbbbbbB')] },
        run: async function (t) {
            await t.waitForTrack(1, 'the first track');
            await t.stand.config({ playbackMode: 'MANUAL' });
            await t.reportAfterConfig();
            await t.sleep(300);
            t.step('the switch follows, the track plays on', [toggle().checked, t.fake.state, t.fake.loads.length], [false, 1, 1]);
            t.fake.end();
            await t.sleep(4000);
            t.step('when it ends nothing else starts', [t.fake.loads.length, await t.stand.count(t.NEXT_TRACK)], [1, 1]);
        }
    });

    S2P.scenario({
        name: 'autopilot-switch-sends-the-mode',
        title: 'the switch sends the state it shows (not "toggle"), and a lease answer to a report sent before the click does not undo it',
        page: 'dashboard-manual',
        // Nothing to play: a track that started would send a report of its own at once (with the new mode), and its answer would hide
        // the old one right after it came.
        setup: { playbackMode: 'MANUAL', lease: { fallbackPlaylistId: PLAYLIST }, nextTracks: [],
            delays: { '/dj/dashboard/player-lease': 1.5 } },
        run: async function (t) {
            await t.nextLeaseReport();   // a report on its way: its answer (sent before the click) says MANUAL
            toggle().click();
            await t.sleep(200);
            const posts = await t.stand.requests('POST /dj/dashboard/playback-mode');
            t.step('the switch sent mode=AUTO', posts.map(function (r) { return r.q.mode; }), ['AUTO']);
            await t.sleep(2000);         // the old answer has arrived meanwhile
            t.step('the old answer did not turn the switch back', [toggle().checked, mode()], [true, 'AUTO']);
            await t.stand.config({ delays: { '/dj/dashboard/player-lease': 0 } });
        }
    });
})();
