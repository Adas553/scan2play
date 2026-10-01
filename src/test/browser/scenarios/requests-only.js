// A requests-only party (the DJ plays from their own software): the dashboard has no player, so youtube-autopilot.js is not on the
// page. The dashboard's own modules must work without it — the queue poll, the lists, the tabs — and nothing may ask for the player
// lease or for a track. (Before this kind of party, only a Spotify party had a dashboard without the player, and no scenario ran one.)

S2P.scenario({
    name: 'requests-only-dashboard',
    title: 'a requests-only party: the dashboard runs without the player — the queue is polled, nothing asks for the lease or a track',
    page: 'dashboard-requests',
    setup: { queue: [{ id: 1, name: 'Wilki - Baśka', url: 'https://www.youtube.com/results?search_query=Wilki' }] },
    run: async function (t) {
        const dismiss = document.querySelector('form[action="/dj/dashboard/dismiss"] button[type="submit"]');
        t.check('a waiting request can be skipped ("Pomiń")', dismiss && dismiss.textContent.trim() === 'Pomiń');
        t.check('and marked as played', !!document.querySelector('form[action="/dj/dashboard/play"]'));
        t.check('there is no player on the page', !document.getElementById('yt-player') && !window.onYouTubeIframeAPIReady);

        await t.waitFor(function () { return document.querySelector('#song-list [data-song-id="1"]'); }, 'the queue poll', 8000);
        t.step('the queue is polled and shown', document.querySelector('#song-list [data-song-id="1"]').getAttribute('data-song-name'),
            'Wilki - Baśka');
        t.step('nothing asks for the player lease, a track or the timeline',
            [await t.stand.count(t.LEASE), await t.stand.count(t.NEXT_TRACK), await t.stand.count('GET /dj/dashboard/recent-tracks')],
            [0, 0, 0]);

        const search = document.querySelector('#queueList [data-list-search]');
        search.value = 'zzz';
        search.dispatchEvent(new Event('input', { bubbles: true }));
        await t.sleep(200);
        t.check('the list search works ("nothing matches")', !document.querySelector('#queueList [data-nomatch]').classList.contains('d-none'));
    }
});

S2P.scenario({
    name: 'requests-only-history-in-place',
    title: 'a party without the player: the History tab loads the history in place of the queue — the page is not left',
    page: 'dashboard-requests',
    setup: {},
    run: async function (t) {
        // Whether the page handled the click itself (no navigation); the scenario stops a navigation either way, so that a page
        // that would leave can still report its verdict
        let handledInPlace = null;
        document.addEventListener('click', function (e) {
            if (!e.target.closest('[data-dj-tab="history"]')) return;
            handledInPlace = e.defaultPrevented;
            e.preventDefault();
        });
        document.querySelector('[data-dj-tab="history"]').click();
        t.step('the click does not leave the page', handledInPlace, true);

        const box = document.getElementById('history-content');
        await t.waitFor(function () { return box.querySelector('[data-list]'); }, 'the history to arrive', 5000).catch(function () {});
        t.step('the history fragment was asked for once', (await t.stand.requests('GET /dj/history-view/fragment')).length, 1);
        t.step('the history shows in place of the queue',
            [getComputedStyle(box).display !== 'none', getComputedStyle(document.getElementById('queue-content')).display !== 'none'],
            [true, false]);

        document.querySelector('[data-dj-tab="queue"]').click();
        t.step('the Queue tab brings the queue back',
            [getComputedStyle(box).display !== 'none', getComputedStyle(document.getElementById('queue-content')).display !== 'none'],
            [false, true]);
    }
});

S2P.scenario({
    name: 'requests-only-skip-in-place',
    title: 'a requests-only party: "Pomiń" is sent in the background — the page stays, and the queue is fetched again at once',
    page: 'dashboard-requests',
    setup: { queue: [{ id: 1, name: 'Wilki - Baśka', url: 'https://www.youtube.com/results?search_query=Wilki' },
                     { id: 2, name: 'sanah - Szampan', url: 'https://www.youtube.com/results?search_query=sanah' }] },
    run: async function (t) {
        // whether the page sent the form itself (no navigation); the scenario stops a navigation either way
        let handledInPlace = null;
        document.addEventListener('submit', function (e) {
            handledInPlace = e.defaultPrevented;
            e.preventDefault();
        });
        const clickedAt = performance.now();
        document.querySelector('#song-list tr[data-song-id="1"] form[action="/dj/dashboard/dismiss"] button').click();
        t.step('the page stays (the form goes in the background)', handledInPlace, true);

        await t.waitFor(function () { return !document.querySelector('#song-list tr[data-song-id="1"]'); },
            'the skipped request to leave the list', 2500).catch(function () {});
        t.step('the server was told which request to skip', (await t.stand.requests('POST /dj/dashboard/dismiss')).map(function (r) { return r.q.id; }), ['1']);
        t.check('the list lost it at once, not with the next 3 s poll', !document.querySelector('#song-list tr[data-song-id="1"]')
            && performance.now() - clickedAt < 2500);
        t.check('the other request stays', !!document.querySelector('#song-list tr[data-song-id="2"]'));
    }
});
