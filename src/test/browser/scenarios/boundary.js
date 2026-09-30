// ⏮ / ⏭ across the round boundary of a short playlist — the play log (V7, PROJECT_CONTEXT.md Section 5.4).
//
// A 3-track playlist (A B C) that loops. The stand-in replays the REAL answers of next-track and recent-tracks recorded from the
// real services on a real PostgreSQL (fixtures/play-log-boundary.json, 10 hand-outs: A1 B2 C3 A4 B5 C6 A7 …; the 3rd one already
// opens round 2, and the log does not start over). The player runs to B5, then goes back and forward across the boundary.
//
// "boundary-old-keys" is the CONTROL: the same plays keyed as before the play log (the id of the queue's track, so the same
// video has the same key in every round). The same walk must go wrong there — ⏮ goes round in circles, ⏭ skips entries — and the
// check must notice; if that scenario ever passes, the check cannot see the problem it exists for.
(function () {
    async function boundary(t) {
        const fake = t.fake;
        await t.waitForTrack(1, 'the first track (Auto-Pilot asks next-track)');
        for (let i = 2; i <= 5; i++) {
            fake.end();
            await t.waitForTrack(i, 'hand-out ' + i);
        }
        t.step('Auto-Pilot ran A B C A B — the 3rd hand-out opened round 2', fake.loads.map(t.letter), ['a', 'b', 'c', 'a', 'b']);

        fake.position = 30;
        t.step('⏮ after 30 s of the track only restarts it', await t.press('playerPreviousBtn'), 'restart');

        const back = [];
        for (let i = 0; i < 6; i++) back.push(await t.press('playerPreviousBtn'));
        t.step('⏮ x6 from B5: A4, then C3 (across the round boundary), B2, A1, then nothing older — twice the restart',
            back, ['a', 'c', 'b', 'a', 'restart', 'restart']);

        const forward = [];
        for (let i = 0; i < 5; i++) forward.push(await t.press('playerNextBtn'));
        t.step('⏭ x5 from A1 retraces B2 C3 A4 B5 and then asks next-track (C6)', forward, ['b', 'c', 'a', 'b', 'c']);

        t.step('next-track was asked 6 times (5 tracks + the one after the newest entry)', await t.stand.count(t.NEXT_TRACK), 6);
        t.step('no background track was confirmed as played (POST /play)', await t.stand.count(t.PLAY), 0);
    }

    S2P.scenario({
        name: 'boundary',
        title: '⏮ / ⏭ walk across the round boundary of a looping playlist (keys = ids of the plays)',
        setup: { replay: { fixture: 'play-log-boundary', keys: 'play' } },
        run: boundary
    });

    S2P.scenario({
        name: 'boundary-old-keys',
        title: 'CONTROL: the same walk with the keys of before the play log must go wrong',
        setup: { replay: { fixture: 'play-log-boundary', keys: 'old' } },
        control: { mustFail: ['⏮ x6', '⏭ x5', 'next-track was asked 6 times'] },
        run: boundary
    });
})();
