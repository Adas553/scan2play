// ⏭ right after a guest song started, or while it is still loading (review item 1.3; youtube-autopilot.js `skipToNext`;
// PROJECT_CONTEXT.md Section 5.4, "Next ⏭").
//
// A guest song stays in the queue until the window that plays confirms it (POST /play, sent on PLAYING, fire-and-forget), and the
// server reads the queue through a cache of 3 s. So a ⏭ pressed while the song loads — or in the first seconds, before the
// confirmation has reached the server or the cache has expired — got the SAME song back from next-track: the DJ pressed "next" and
// the song started again. The stand-in gives that stale answer twice (the id 7 twice, as the real server did); only a request that
// excludes the song gets past it. The server now also drops the cached queue when a song is confirmed.
(function () {
    const PLAYLIST = 'PLscenario0000000000000000000000000';
    const guest = function () { return { source: 'GUEST', id: 7, videoId: 'gggggggggGg' }; };
    const background = function (id, videoId) { return { source: 'BACKGROUND', id: id, videoId: videoId, playlistId: PLAYLIST }; };

    async function nextSkipsTheGuestSong(t) {
        t.step('⏭ plays the next track, not the guest song again', await t.press('playerNextBtn', 800), 'b');
        const asks = await t.stand.requests(t.NEXT_TRACK);
        t.step('the ask of ⏭ excludes the guest song', asks[asks.length - 1].q.exclude || null, '7');
    }

    S2P.scenario({
        name: 'next-right-after-guest-song-started',
        title: '⏭ a second after a guest song started does not hand it out again',
        setup: { lease: { fallbackPlaylistId: PLAYLIST }, nextTracks: [guest(), guest(), background(2, 'bbbbbbbbbbB')] },
        run: async function (t) {
            await t.waitForTrack(1, 'the guest song');
            t.step('Auto-Pilot plays the guest song', t.fake.loads.map(t.letter), ['g']);
            await nextSkipsTheGuestSong(t);
        }
    });

    S2P.scenario({
        name: 'next-while-guest-song-loads',
        title: '⏭ while a guest song is still loading does not hand it out again',
        setup: {
            lease: { fallbackPlaylistId: PLAYLIST },
            nextTracks: [background(1, 'aaaaaaaaaaA'), guest(), guest(), background(2, 'bbbbbbbbbbB')]
        },
        run: async function (t) {
            const fake = t.fake;
            await t.waitForTrack(1, 'the first track');
            fake.holdState = 'UNSTARTED';
            fake.end();
            await t.waitFor(function () { return fake.loads.length >= 2; }, 'the guest song to be handed to the player');
            t.step('the guest song is loading, not playing yet', [t.letter(fake.loads[1]), fake.state], ['g', -1]);
            fake.holdState = null;
            await nextSkipsTheGuestSong(t);
        }
    });
})();
