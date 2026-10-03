// A Spotify party's dashboard sends its forms in the background, as a YouTube and a requests-only party's do (2026-10-03): a page
// reload after every "Played", "🎵 Spotify" or Auto-Pilot click threw the DJ back to the top of the list, and on a phone folded
// the settings away. Only what changes the whole page still reloads it (connecting Spotify, logging out, deleting the account).
(function () {
    const posts = function (t, path) { return t.stand.count('POST ' + path); };
    /** Waits (up to 5 s) until the stand-in has had n POSTs to path. */
    const waitForPosts = async function (t, path, n, label) {
        const started = performance.now();
        while ((await posts(t, path)) < n) {
            if (performance.now() - started > 5000) {
                t.step('TIMEOUT waiting for ' + label, false, true);
                throw new Error('timeout: ' + label);
            }
            await t.sleep(50);
        }
    };
    /**
     * How many times this tab has loaded the page: a scenario starts with an empty sessionStorage and a reload keeps it, so a form
     * that reloads the page runs the scenario again — and this step is red the second time.
     */
    const loadedOnce = function (t) {
        const loads = Number(sessionStorage.getItem('spotify-scenario-loads') || 0) + 1;
        sessionStorage.setItem('spotify-scenario-loads', String(loads));
        t.step('the page is loaded once (no form reloaded it)', loads, 1);
    };

    // The queue poll is held back, so the rows the server rendered (with their buttons) stay on the page.
    S2P.scenario({
        name: 'spotify-forms-in-place',
        title: 'a Spotify party: "Played", "🎵 Spotify" and the limits are sent in the background — the page stays, the queue is asked again',
        page: 'dashboard-spotify',
        setup: { delays: { '/dj/dashboard/updates': 30000 } },
        run: async function (t) {
            loadedOnce(t);
            const row = document.querySelector('#song-list tr[data-song-id="1"]');
            const button = function (action) { return row.querySelector('form[action="' + action + '"] button[type="submit"]'); };

            const polls = await t.stand.count('GET /dj/dashboard/updates');
            button('/dj/requests/1/push-to-spotify').click();
            await waitForPosts(t, '/dj/requests/1/push-to-spotify', 1, 'the song sent to Spotify');
            await t.sleep(300);
            t.check('after "🎵 Spotify" the queue is asked again at once (no 3 s wait)',
                (await t.stand.count('GET /dj/dashboard/updates')) > polls);

            button('/dj/dashboard/play').click();
            await waitForPosts(t, '/dj/dashboard/play', 1, 'the song marked played');
            t.step('"Played" sends the song\'s id', (await t.stand.requests('POST /dj/dashboard/play'))[0].q.id, '1');

            document.querySelector('form[action="/dj/dashboard/limits"] button[type="submit"]').click();
            await waitForPosts(t, '/dj/dashboard/limits', 1, 'the limits saved');
            await t.sleep(300);
            t.check('the page did not reload (the row is the same element)', document.contains(row));
        }
    });

    S2P.scenario({
        name: 'spotify-autopilot-in-place',
        title: 'a Spotify party: the Auto-Pilot switch saves the mode it shows in the background; the page stays',
        page: 'dashboard-spotify',
        setup: { playbackMode: 'MANUAL' },
        run: async function (t) {
            loadedOnce(t);
            const toggle = document.getElementById('autoToggle');
            t.step('loaded with Auto-Pilot off', toggle.checked, false);
            toggle.click();
            await waitForPosts(t, '/dj/dashboard/playback-mode', 1, 'the mode saved');
            t.step('the mode the switch shows is sent', (await t.stand.requests('POST /dj/dashboard/playback-mode'))[0].q.mode, 'AUTO');
            await t.sleep(300);
            t.step('the page did not reload: the switch is the same and on', [document.contains(toggle), toggle.checked], [true, true]);
        }
    });
})();
