// THE THREE STATES IN WHICH A DASHBOARD WINDOW IS SILENT — what the DJ sees today, recorded before anything is built.
// (SESSION_HANDOFF.md, "Next" 2: "The Auto-Pilot hint and the IFrame-API message — first reproduce, then build".)
//
// A friend of the owner tried a fresh party and the music did not start: her screenshot showed the first playlist track still marked
// "Next" with 120 left in the round, i.e. the window had never asked next-track. Three things fit that, and the owner wants her
// steps reproduced on a real phone (https://dev.scan2play.com.pl) before a hint is built on a hypothesis:
//   1. Auto-Pilot is off — a new party starts with it off, and with it off nothing starts by itself (Phase 2 stage 4);
//   2. the YouTube IFrame API does not load (an ad blocker, Brave shields) — the script has no handler for that: the failure is silent;
//   3. another window holds the player lease — that one HAS a banner ("playback runs on another device").
// These scenarios only state facts about each state: what the window asks the server, what plays, and what the page says. They pass
// today; when a hint or a message is built for state 1 or 2, the last step of that scenario is the one to change.
(function () {
    const PLAYLIST = 'PLscenario0000000000000000000000000';
    const track = { source: 'BACKGROUND', id: 1, videoId: 'aaaaaaaaaaA', playlistId: PLAYLIST };
    const banner = function () { return document.getElementById('playerLeaseBanner'); };
    const bannerShown = function () { return !banner().classList.contains('d-none'); };

    S2P.scenario({
        name: 'silent-autopilot-off',
        title: 'STATE 1, a new party: Auto-Pilot off — nothing is asked, nothing plays, no word about it; switching it on starts the music',
        page: 'dashboard-manual',
        setup: { playbackMode: 'MANUAL', lease: { fallbackPlaylistId: PLAYLIST }, nextTracks: [track] },
        run: async function (t) {
            const toggle = document.getElementById('autoToggle');
            t.step('the page is the one of a new party: the Auto-Pilot switch is off', toggle.checked, false);
            await t.sleep(4000);   // a few polls and lease reports
            t.step('the window holds the player lease (no banner)', bannerShown(), false);
            t.step('it never asked the server for a track', await t.stand.count(t.NEXT_TRACK), 0);
            t.step('and nothing plays', t.fake.loads.length, 0);
            t.step('the player is there and ready (the fake API loaded)', t.fake.player !== null, true);
            // FACT TO CHANGE when a hint is built: today no text on the page says that Auto-Pilot is off and that is why nothing plays.
            t.check('nothing in the player card mentions Auto-Pilot', document.getElementById('yt-player-card').innerText.toLowerCase().indexOf('auto-pilot') < 0);

            toggle.click();   // the DJ switches it on: the page's own handler posts the mode and asks the player to start
            await t.waitForTrack(1, 'the first track after Auto-Pilot was switched on');
            t.step('switching Auto-Pilot on starts the music at once (one ask, the track from the server)', [await t.stand.count(t.NEXT_TRACK), t.fake.loads.map(t.letter)], [1, ['a']]);
        }
    });

    S2P.scenario({
        name: 'silent-iframe-api-blocked',
        title: 'STATE 2, Auto-Pilot on: the YouTube IFrame API is blocked — no player, no request, no word about it; ⏭ does nothing',
        fake: { blockApi: true },
        setup: { lease: { fallbackPlaylistId: PLAYLIST }, nextTracks: [track] },
        run: async function (t) {
            t.step('the page is the one of a party with Auto-Pilot on', document.getElementById('autoToggle').checked, true);
            await t.sleep(4000);
            t.step('the window holds the player lease (no banner)', bannerShown(), false);
            t.step('no player was ever made (the API never called back)', t.fake.player, null);
            t.step('it never asked the server for a track (a window without a player does not ask)', await t.stand.count(t.NEXT_TRACK), 0);
            t.step('the player box is empty', document.getElementById('yt-player').children.length, 0);
            t.step('⏭ pressed on such a window does nothing, silently', [await t.press('playerNextBtn'), await t.stand.count(t.NEXT_TRACK)], ['nothing', 0]);
            // FACT TO CHANGE when a message is built: today the card says nothing about the API not loading.
            t.check('nothing in the player card says that the player could not be loaded',
                /nie za[łl]adowa|could not load|blocked|zablokowan/i.test(document.getElementById('yt-player-card').innerText) === false);
        }
    });

    S2P.scenario({
        name: 'silent-lease-elsewhere',
        title: 'STATE 3: another window holds the player lease — this one shows the banner, asks nothing, and ⏭ goes to the other as a command',
        setup: { lease: { holder: false, free: false, fallbackPlaylistId: PLAYLIST }, nextTracks: [track] },
        run: async function (t) {
            await t.waitFor(bannerShown, 'the banner');
            t.step('the banner says that playback runs on another device', banner().querySelector('[data-role="text"]').textContent, banner().dataset.textOther);
            await t.sleep(3500);
            t.step('this window never asked the server for a track', await t.stand.count(t.NEXT_TRACK), 0);
            t.step('and nothing plays here', t.fake.loads.length, 0);
            document.getElementById('playerNextBtn').click();
            await t.sleep(600);
            t.step('⏭ is sent to the window that plays (POST player-command NEXT)', (await t.stand.requests('POST /dj/dashboard/player-command')).map(function (r) { return r.q.command; }), ['NEXT']);
        }
    });
})();
