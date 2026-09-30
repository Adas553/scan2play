// The pause button and the lease reports while a track is being loaded (youtube-autopilot.js; PROJECT_CONTEXT.md Section 5.4,
// "Pause ⏯"). The owner saw "Wznów" instead of "Pauza" when the rewind took a moment: between two videos the real player is
// UNSTARTED or CUED for a while (after loadVideoById), nothing plays yet, and the window that plays reported playing = false — so
// its own button, and the button of the phone, said "resume" although the DJ had just started a track.
//
// The fake player can stay in UNSTARTED / CUED (fake.holdState + fake.release()): without that the check would prove nothing.
// A load that never gets to PLAYING (a browser that refuses sound in a page nobody has touched leaves the player at CUED) must not
// keep saying "playing" for ever — the DJ's way out is the "resume" button — so that counts only for a while (10 s).
(function () {
    const PLAYLIST = 'PLscenario0000000000000000000000000';
    const track = function (id, videoId) { return { source: 'BACKGROUND', id: id, videoId: videoId, playlistId: PLAYLIST }; };

    S2P.scenario({
        name: 'pause-while-loading',
        title: 'a track that is loading counts as playing (button and lease reports), for 10 s at most',
        setup: {
            lease: { fallbackPlaylistId: PLAYLIST },
            nextTracks: [track(1, 'aaaaaaaaaaA'), track(2, 'bbbbbbbbbbB'), track(3, 'cccccccccCc'), track(4, 'dddddddddDd')]
        },
        run: async function (t) {
            const fake = t.fake;
            const button = document.getElementById('playerPauseBtn');
            const PAUSE = button.dataset.textPause, RESUME = button.dataset.textResume;
            const label = function () { return t.label('playerPauseBtn'); };

            await t.waitForTrack(1, 'the first track');
            t.step('a track that plays: the button says Pause', label(), PAUSE);

            for (const held of ['UNSTARTED', 'CUED']) {
                const n = fake.loads.length + 1;
                fake.holdState = held;
                fake.end();
                await t.waitFor(function () { return fake.loads.length >= n; }, 'the next track to be handed to the player');
                t.step('the player is ' + held + ' (loading, not playing yet)', fake.state, held === 'UNSTARTED' ? -1 : 5);
                const report = await t.nextLeaseReport();
                t.step('a track loading in ' + held + ': the lease report says playing = true', report.playing, 'true');
                t.step('a track loading in ' + held + ': the button says Pause', label(), PAUSE);
                fake.holdState = null;
                fake.release();
                await t.waitForTrack(n, 'the track to play');
                t.step('after the load: the button says Pause', label(), PAUSE);
            }

            // a load that does not get anywhere: after 10 s it is not "playing" any more
            fake.holdState = 'UNSTARTED';
            fake.end();
            await t.waitFor(function () { return fake.loads.length >= 4; }, 'the fourth track to be handed to the player');
            fake.advanceClock(11000);
            const stuck = await t.nextLeaseReport();
            t.step('a load that has not started for 11 s: the lease report says playing = false', stuck.playing, 'false');
            t.step('a load that has not started for 11 s: the button says Resume', label(), RESUME);
            document.getElementById('playerPauseBtn').click();
            await t.sleep(300);
            fake.holdState = null;
            t.step('Resume starts it (playVideo)', fake.calls.filter(function (c) { return c[0] === 'playVideo'; }).length, 1);
            t.step('and then the button says Pause', label(), PAUSE);

            // a real pause is still a pause
            document.getElementById('playerPauseBtn').click();
            await t.sleep(300);
            t.step('a pause: the button says Resume', label(), RESUME);
            t.step('a pause: the lease report says playing = false', (await t.nextLeaseReport()).playing, 'false');
        }
    });
})();
