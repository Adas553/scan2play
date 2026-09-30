// ⏭ after the DJ has changed the playlist (youtube-autopilot.js `playingFromHistory`; PROJECT_CONTEXT.md Section 5.4, "Back ⏮").
//
// After ⏮ the ⏭ button retraces the steps: it goes forward through the timeline of what played (skipToNext), because a guest song
// that has played is not in the queue any more and next-track would never hand it out again. That is right for a guest song, but not
// for the tracks of a playlist the DJ has just left: with three ⏮ behind them the DJ had to press ⏭ through every old track before
// the new playlist began. Decided with the owner on 2026-09-29: when the playlist changes — in this window (Save / Stop), or in
// another one (the lease answer names another playlist) — the retracing ends: ⏭ asks next-track at once and the new playlist
// starts; the track that runs now (one that came back through ⏮) is not interrupted, like a guest song; the old playlist's plays
// stay in the timeline (a ⏮ from the new playlist still goes back into the old one).
(function () {
    const OLD = 'PLold00000000000000000000000000000';
    const NEW = 'PLnew00000000000000000000000000000';
    const played = function (id, videoId) { return { source: 'BACKGROUND', id: id, videoId: videoId, playlistId: OLD }; };
    const entry = function (id, videoId) { return { key: 'B:' + id, source: 'BACKGROUND', id: id, videoId: videoId, title: 'Old ' + id }; };
    const fresh = function () { return { source: 'BACKGROUND', id: 4, videoId: 'nnnnnnnnnnN', playlistId: NEW }; };
    const guest = function () { return { source: 'GUEST', id: 77, videoId: 'gggggggggGg' }; };

    const setup = {
        lease: { fallbackPlaylistId: OLD },
        nextTracks: [played(1, 'aaaaaaaaaaA'), played(2, 'bbbbbbbbbbB'), played(3, 'cccccccccCc')],
        recent: [entry(3, 'cccccccccCc'), entry(2, 'bbbbbbbbbbB'), entry(1, 'aaaaaaaaaaA')],
        fallbackSave: { playlistId: NEW, import: 'ok', tracks: 5 }
    };

    async function playABC(t) {
        await t.waitForTrack(1, 'the first track');
        t.fake.end();
        await t.waitForTrack(2, 'the second track');
        t.fake.end();
        await t.waitForTrack(3, 'the third track');
        t.step('the old playlist ran A B C', t.fake.loads.map(t.letter), ['a', 'b', 'c']);
    }

    /** ⏮ ⏮ goes back to B and to A — the player is on a track that came back, and ⏭ would retrace. */
    async function wentBackTwice(t) {
        await playABC(t);
        t.step('⏮ goes back to B', await t.press('playerPreviousBtn'), 'b');
        t.step('⏮ goes back to A', await t.press('playerPreviousBtn'), 'a');
    }

    async function nextAsksAtOnce(t, expected, what) {
        t.step('⏭ ' + what, await t.press('playerNextBtn'), expected);
        t.step('next-track was asked 4 times (A B C, and now ⏭)', await t.stand.count(t.NEXT_TRACK), 4);
    }

    S2P.scenario({
        name: 'next-after-playlist-saved',
        title: 'the DJ saves another playlist here after ⏮ ⏮: ⏭ starts it at once, and A is not interrupted',
        setup: setup,
        run: async function (t) {
            await wentBackTwice(t);
            await t.stand.config({ nextTracks: [fresh()] });
            const stopsBefore = t.fake.calls.filter(function (c) { return c[0] === 'stopVideo'; }).length;
            document.getElementById('fallbackInput').value = 'https://www.youtube.com/playlist?list=' + NEW;
            document.getElementById('fallbackForm').requestSubmit();   // the real Save: dashboard.js posts the form and calls updateFallbackSource()
            await t.sleep(700);
            t.step('the playlist was saved (POST fallback-playlist)', await t.stand.count('POST /dj/dashboard/fallback-playlist'), 1);
            t.step('the track that came back (A) was not stopped', t.fake.calls.filter(function (c) { return c[0] === 'stopVideo'; }).length, stopsBefore);
            t.step('and it still plays', t.fake.state, 1);
            await nextAsksAtOnce(t, 'n', 'starts the new playlist at once (not B)');
        }
    });

    S2P.scenario({
        name: 'next-after-playlist-cleared',
        title: 'the DJ presses Stop after ⏮ ⏮: ⏭ asks next-track at once instead of retracing',
        setup: setup,
        run: async function (t) {
            await wentBackTwice(t);
            await t.stand.config({ nextTracks: [guest()] });   // what next-track has for a party without a playlist: a guest song
            window.stopFallbackPlaylist();                      // the real Stop button of the page
            await t.sleep(700);
            t.step('the playlist was cleared (POST fallback-playlist)', await t.stand.count('POST /dj/dashboard/fallback-playlist'), 1);
            await nextAsksAtOnce(t, 'g', 'plays the waiting guest song at once (not B)');
        }
    });

    S2P.scenario({
        name: 'next-after-playlist-changed-elsewhere',
        title: 'the DJ changes the playlist in another window after ⏮ ⏮: the next lease answer ends the retracing',
        setup: setup,
        run: async function (t) {
            await wentBackTwice(t);
            await t.stand.config({ lease: { fallbackPlaylistId: NEW }, nextTracks: [fresh()] });
            await t.reportAfterConfig();      // the report that this window sends next gets the new playlist back in its answer
            await t.sleep(300);
            await nextAsksAtOnce(t, 'n', 'starts the new playlist at once (not B)');
        }
    });

    S2P.scenario({
        name: 'back-into-the-old-playlist',
        title: 'the change came BEFORE the DJ went back: ⏮ from the new playlist goes back into the old one, and ⏭ retraces (the change is not news)',
        setup: setup,
        run: async function (t) {
            await playABC(t);
            await t.stand.config({ lease: { fallbackPlaylistId: NEW }, nextTracks: [fresh()] });
            await t.reportAfterConfig();
            await t.waitForTrack(4, 'the new playlist to start (the answer drops C, which came from the old one)');
            t.step('the change stopped C and the new playlist started (N)', t.fake.loads.map(t.letter), ['a', 'b', 'c', 'n']);
            t.step('⏮ from the new playlist goes back into the old one: C', await t.press('playerPreviousBtn'), 'c');
            t.step('⏮ goes on: B', await t.press('playerPreviousBtn'), 'b');
            t.step('⏭ retraces the steps: C (the change was before the DJ went back)', await t.press('playerNextBtn'), 'c');
            t.step('next-track was asked 4 times only (A B C N)', await t.stand.count(t.NEXT_TRACK), 4);
        }
    });

    S2P.scenario({
        name: 'retrace-survives-a-change-in-flight',
        title: 'the report that carries the change was sent BEFORE ⏮ ⏮ but is answered after it: ⏭ still retraces the steps',
        // every answer of the lease comes 2.5 s late, so a report can be in flight while the DJ presses ⏮
        setup: Object.assign({}, setup, { delays: { '/dj/dashboard/player-lease': 2.5 } }),
        run: async function (t) {
            await playABC(t);
            await t.sleep(3000);            // the late answers about the old playlist have arrived
            await t.stand.config({ lease: { fallbackPlaylistId: NEW }, nextTracks: [fresh()] });
            await t.reportAfterConfig();      // sent now, answered in 2.5 s — with the new playlist
            t.step('⏮ goes back to B (the answer is still on its way)', await t.press('playerPreviousBtn'), 'b');
            t.step('⏮ goes back to A (the answer is still on its way)', await t.press('playerPreviousBtn'), 'a');
            await t.sleep(3500);            // the answer has arrived: it describes a change that came before the DJ went back
            t.step('⏭ retraces the steps to B', await t.press('playerNextBtn'), 'b');
            t.step('next-track was asked 3 times only', await t.stand.count(t.NEXT_TRACK), 3);
        }
    });
})();
