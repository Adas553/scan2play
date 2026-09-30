// Auto-Pilot comes back after an ask that failed (REVIEW.md 3.1). The window that plays asks next-track only at a few moments (the
// player is ready, a track ends, a player error, Auto-Pilot switched on, the lease won) and when the dashboard's poll brings a
// changed queue. A failed ask — a network hiccup, a 5xx during a deploy, an expired login — used to leave the player idle, and
// with an unchanged queue (the poll answers 304) nothing ever asked again: silence until a guest added a song. The same after
// MAX_IMMEDIATE_RETRIES player errors in a row. Now such a window asks again with its next lease report (every 3 s).
(function () {
    const PLAYLIST = 'PLscenario0000000000000000000000000';
    const track = function (id, videoId) { return { source: 'BACKGROUND', id: id, videoId: videoId, playlistId: PLAYLIST }; };
    // The first poll of a page has no ETag yet, so it is answered in full and asks the player to start: a failure in the middle of a
    // party comes after it, when every poll is a 304. Wait for the second poll.
    const afterFirstPolls = async function (t) {
        for (let i = 0; i < 100 && (await t.stand.count('GET /dj/dashboard/updates')) < 2; i++) await t.sleep(100);
        t.step('the queue poll has run twice (from now on it answers 304)', (await t.stand.count('GET /dj/dashboard/updates')) >= 2, true);
    };

    S2P.scenario({
        name: 'recover-after-failed-ask',
        title: 'next-track fails (500) at the end of a track: the window asks again with each lease report until the server answers, and the music goes on',
        setup: { lease: { fallbackPlaylistId: PLAYLIST }, nextTracks: [track(1, 'aaaaaaaaaaA'), track(2, 'bbbbbbbbbbB')] },
        run: async function (t) {
            await t.waitForTrack(1, 'the first track');
            await afterFirstPolls(t);
            await t.stand.config({ nextTrackStatus: 500 });
            t.fake.end();
            await t.sleep(6500);   // two lease intervals and more, with the server still failing
            const failedAsks = (await t.stand.count(t.NEXT_TRACK)) - 1;
            t.step('the failing asks: one at the end of the track, then one per lease report (no tight loop)', failedAsks >= 2 && failedAsks <= 4, true);
            t.step('nothing new plays while the server fails', t.fake.loads.map(t.letter), ['a']);

            await t.stand.config({ nextTrackStatus: null });   // the server is back; the guest queue has not changed (the poll says 304)
            const before = await t.stand.count(t.NEXT_TRACK);
            await t.sleep(7000);
            t.step('the window asked again by itself and the next track plays', t.fake.loads.map(t.letter), ['a', 'b']);
            t.step('one ask after the server came back, and none once the track plays', (await t.stand.count(t.NEXT_TRACK)) - before, 1);
        }
    });

    S2P.scenario({
        name: 'recover-after-player-errors',
        title: 'six player errors in a row: five immediate asks, then the next ask comes with a lease report — not never',
        setup: {
            lease: { fallbackPlaylistId: PLAYLIST },
            nextTracks: [track(1, 'aaaaaaaaaaA'), track(2, 'bbbbbbbbbbB'), track(3, 'cccccccccCc'), track(4, 'dddddddddDd'),
                track(5, 'eeeeeeeeeEe'), track(6, 'fffffffffFf'), track(7, 'gggggggggGg'), track(8, 'hhhhhhhhhHh')]
        },
        run: async function (t) {
            const fake = t.fake;
            await t.waitForTrack(1, 'the first track');
            await afterFirstPolls(t);
            fake.holdState = 'UNSTARTED';   // from now on a load never plays: the player reports an error for it instead
            fake.end();
            for (let n = 2; n <= 7; n++) {
                await t.waitFor(function () { return fake.loads.length >= n; }, 'load ' + n);
                fake.player._events.onError({ data: 150 });   // "the owner does not allow embedding"
            }
            await t.sleep(500);
            t.step('after the sixth error in a row there is no immediate ask', fake.loads.length, 7);
            fake.holdState = null;
            await t.sleep(4500);   // a lease interval and more
            t.step('the next ask came by itself (with a lease report) and that track plays',
                [fake.loads.length, t.letter(fake.loads[7]), fake.state], [8, 'h', 1]);
        }
    });
})();
