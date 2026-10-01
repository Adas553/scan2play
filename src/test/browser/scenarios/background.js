// A WINDOW IN THE BACKGROUND (review item 3.4; polling.js, youtube-autopilot.js leaseLoop). A dashboard that is hidden — the DJ's phone in
// a pocket, a second tab, the requests-only dashboard behind the DJ's own software — and does not play asks the server much less: no
// poll of the guest queue at all and a lease report every 15 s instead of every 3 s; when it is shown again it asks at once. The window
// that PLAYS goes on as before, hidden or not: its lease reports keep it the player, and its poll tells it that a guest song waits.
//
// Hidden and shown are simulated: document.visibilityState / document.hidden are redefined and a visibilitychange is sent, as the
// browser does when a tab goes to the background.
(function () {
    const UPDATES = 'GET /dj/dashboard/updates';
    const PLAYLIST = 'PLscenario0000000000000000000000000';
    const track = function (id, videoId) { return { source: 'BACKGROUND', id: id, videoId: videoId, playlistId: PLAYLIST }; };

    const setHidden = function (hidden) {
        Object.defineProperty(document, 'visibilityState', { configurable: true, get: function () { return hidden ? 'hidden' : 'visible'; } });
        Object.defineProperty(document, 'hidden', { configurable: true, get: function () { return hidden; } });
        document.dispatchEvent(new Event('visibilitychange'));
    };
    /** How many polls of the queue and lease reports reached the stand-in while `ms` passed. */
    const askedDuring = async function (t, ms) {
        const before = [await t.stand.count(UPDATES), await t.stand.count(t.LEASE)];
        await t.sleep(ms);
        return [(await t.stand.count(UPDATES)) - before[0], (await t.stand.count(t.LEASE)) - before[1]];
    };

    S2P.scenario({
        name: 'background-watcher-asks-less',
        title: 'a hidden window that does not play: no poll of the queue and at most one lease report in 8 s; shown again, it asks at once and every 3 s',
        setup: { lease: { holder: false, free: false, fallbackPlaylistId: PLAYLIST } },
        run: async function (t) {
            await t.waitFor(function () { return !document.getElementById('playerLeaseBanner').classList.contains('d-none'); }, 'the banner');
            setHidden(true);
            await t.sleep(3500);   // what was already on its way (one poll, one report) has gone
            const [polls, reports] = await askedDuring(t, 8000);
            t.step('hidden: no poll of the queue in 8 s', polls, 0);
            t.check('hidden: at most one lease report in 8 s (every 15 s), was ' + reports, reports <= 1);

            const before = [await t.stand.count(UPDATES), await t.stand.count(t.LEASE)];
            setHidden(false);
            await t.sleep(1000);
            t.step('shown again: a poll and a lease report within a second',
                [(await t.stand.count(UPDATES)) > before[0], (await t.stand.count(t.LEASE)) > before[1]], [true, true]);
            const [pollsShown, reportsShown] = await askedDuring(t, 6500);
            t.check('shown: polls every 3 s again (2 in 6.5 s), were ' + pollsShown, pollsShown >= 2);
            t.check('shown: lease reports every 3 s again (2 in 6.5 s), were ' + reportsShown, reportsShown >= 2);
        }
    });

    S2P.scenario({
        name: 'background-player-keeps-asking',
        title: 'the window that plays goes on asking when hidden: lease reports and polls every 3 s, and a guest song that comes meanwhile is played next',
        setup: { lease: { fallbackPlaylistId: PLAYLIST }, nextTracks: [track(1, 'aaaaaaaaaaA')] },
        run: async function (t) {
            await t.waitForTrack(1);
            setHidden(true);
            const [polls, reports] = await askedDuring(t, 7000);
            t.check('hidden but playing: polls go on (2 in 7 s), were ' + polls, polls >= 2);
            t.check('hidden but playing: lease reports go on (2 in 7 s), were ' + reports, reports >= 2);

            // a guest song comes while the window is hidden, after the background track ended and nothing was left to play
            t.fake.end();
            await t.sleep(500);
            await t.stand.config({ nextTracks: [{ source: 'GUEST', id: 7, videoId: 'ggggggggggg', playlistId: null }],
                queue: [{ id: 7, name: 'Guest song', url: 'https://www.youtube.com/watch?v=ggggggggggg' }] });
            await t.waitForTrack(2, 'the guest song, played by the hidden window');
            t.step('the hidden window played the guest song', t.fake.loads.map(t.letter), ['a', 'g']);
            setHidden(false);
        }
    });

    S2P.scenario({
        name: 'background-requests-only-asks-at-once-when-shown',
        title: 'a requests-only dashboard (no player) behind the DJ\'s software: no poll while hidden; shown again, the new requests come at once',
        page: 'dashboard-requests',
        run: async function (t) {
            setHidden(true);
            await t.sleep(3500);
            const before = await t.stand.count(UPDATES);
            await t.sleep(7000);
            t.step('hidden: no poll of the queue in 7 s', (await t.stand.count(UPDATES)) - before, 0);

            await t.stand.config({ queue: [{ id: 9, name: 'Sent while hidden', url: 'https://www.youtube.com/results?search_query=x' }] });
            setHidden(false);
            await t.sleep(1000);
            t.check('shown again: the request sent meanwhile is on the list within a second',
                document.querySelector('#song-list tr[data-song-id="9"]') !== null);
        }
    });
})();
