// The party (the DJ plays from their own software): the dashboard has no player. Its modules — the queue poll, the lists, the tabs —
// work on their own, and nothing asks for the player's old endpoints (the lease, a track, the timeline: gone from the server).

S2P.scenario({
    name: 'requests-only-dashboard',
    title: 'the dashboard runs without a player — the queue is polled, nothing asks for the lease or a track',
    setup: { queue: [{ id: 1, name: 'Wilki - Baśka', url: 'https://www.youtube.com/results?search_query=Wilki' }] },
    run: async function (t) {
        const dismiss = document.querySelector('form[action="/dj/dashboard/dismiss"] button[type="submit"]');
        t.check('a waiting request can be skipped ("⏭ Pomiń")', dismiss && dismiss.textContent.trim() === '⏭ Pomiń');
        t.check('and marked as played', !!document.querySelector('form[action="/dj/dashboard/play"]'));
        t.check('there is no player on the page', !document.getElementById('yt-player') && !window.onYouTubeIframeAPIReady);
        // on a computer too the settings fold under the button (the owner, 2026-10-09: there are many of them), the queue first
        const fold = document.getElementById('settingsToggle');
        const shown = function (id) { return document.getElementById(id).getClientRects().length > 0; };
        t.step('on a wide screen the settings and the QR code are folded, the button that unfolds them shows',
            [shown('vibeSelect'), shown('partyLinkInput'), shown('settingsMore'), !!fold && fold.getClientRects().length > 0],
            [false, false, false, true]);
        fold.click();
        t.step('the button unfolds them', [shown('vibeSelect'), shown('partyLinkInput'), shown('settingsMore')], [true, true, true]);
        fold.click();
        t.step('and folds them again', shown('vibeSelect'), false);

        await t.waitFor(function () { return document.querySelector('#song-list [data-song-id="1"]'); }, 'the queue poll', 8000);
        t.step('the queue is polled and shown', document.querySelector('#song-list [data-song-id="1"]').getAttribute('data-song-name'),
            'Wilki - Baśka');
        t.step('nothing asks for the player lease, a track or the timeline',
            [await t.stand.count('POST /dj/dashboard/player-lease'), await t.stand.count('POST /dj/dashboard/next-track'),
             await t.stand.count('GET /dj/dashboard/recent-tracks')],
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
    title: 'the History tab loads the history in place of the queue — the page is not left',
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
    title: 'the queue: "Pomiń" is sent in the background — the page stays, and the queue is fetched again at once',
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

// V28: a payment titled "#27" came in — "💸" at the song counts the tip in the background; the page stays and the queue is fetched
// again at once with the new count (not with the next 3 s poll)
S2P.scenario({
    name: 'tip-count-in-place',
    title: 'the queue: "💸" counts a tip in the background — the page stays, and the queue comes back at once with "💸 1"',
    setup: { queue: [{ id: 1, name: 'Wilki - Baśka', url: 'https://www.youtube.com/results?search_query=Wilki', number: 27 },
                     { id: 2, name: 'sanah - Szampan', url: 'https://www.youtube.com/results?search_query=sanah', number: 28 }] },
    run: async function (t) {
        let handledInPlace = null;
        document.addEventListener('submit', function (e) {
            handledInPlace = e.defaultPrevented;
            e.preventDefault();
        });
        const tipButton = function () { return document.querySelector('#song-list tr[data-song-id="1"] form[action="/dj/dashboard/tip-count"] button'); };
        await t.waitFor(function () { return !!tipButton(); }, 'the queue with its "💸"', 8000);
        const clickedAt = performance.now();
        tipButton().click();
        t.step('the page stays (the form goes in the background)', handledInPlace, true);
        await t.waitFor(function () { return tipButton() && tipButton().textContent === '💸 1'; }, 'the new count', 2500).catch(function () {});
        t.step('the server was told which song', (await t.stand.requests('POST /dj/dashboard/tip-count')).map(function (r) { return r.q.id; }), ['1']);
        t.check('the count shows at once, not with the next 3 s poll', tipButton() && tipButton().textContent === '💸 1'
            && performance.now() - clickedAt < 2500);
        t.check('the other song has none', document.querySelector('#song-list tr[data-song-id="2"] .s2p-btn-tip').textContent === '💸');
    }
});

S2P.scenario({
    name: 'skip-undo',
    title: '"Pomiń" by mistake: a bar names the song and offers "Cofnij", which puts the request back in the queue at once',
    setup: { queue: [{ id: 1, name: 'Wilki - Baśka', url: 'https://www.youtube.com/results?search_query=Wilki' },
                     { id: 2, name: 'sanah - Szampan', url: 'https://www.youtube.com/results?search_query=sanah' }] },
    run: async function (t) {
        const bar = document.getElementById('undoSkip');
        const shows = function () { return !!bar && !bar.hidden && bar.getClientRects().length > 0; };
        t.check('no bar before a skip', !!bar && !shows());

        // the rendered rows have the buttons (the stand-in's polled rows do not): click before the first poll replaces them
        document.querySelector('#song-list tr[data-song-id="1"] form[action="/dj/dashboard/dismiss"] button').click();
        await t.waitFor(shows, 'the "Cofnij" bar', 2500).catch(function () {});
        t.step('after the skip the bar names the song', shows() ? bar.querySelector('[data-undo-song]').textContent : null, 'Wilki - Baśka');
        await t.waitFor(function () { return !document.querySelector('#song-list tr[data-song-id="1"]'); }, 'the row to go', 2500)
            .catch(function () {});

        const clickedAt = performance.now();
        bar.querySelector('[data-undo-button]').click();
        t.check('"Cofnij" hides the bar at once', !shows());
        await t.waitFor(function () { return document.querySelector('#song-list tr[data-song-id="1"]'); }, 'the request back', 2500)
            .catch(function () {});
        t.step('the server was told which request to put back', (await t.stand.requests('POST /dj/dashboard/restore'))
            .map(function (r) { return r.q.id; }), ['1']);
        t.check('the request is back in the queue at once, not with the next 3 s poll',
            !!document.querySelector('#song-list tr[data-song-id="1"]') && performance.now() - clickedAt < 2500);
    }
});

S2P.scenario({
    name: 'skip-undo-goes',
    title: 'the "Cofnij" bar goes by itself after a few seconds, and nothing is put back',
    setup: { queue: [{ id: 2, name: 'sanah - Szampan', url: 'https://www.youtube.com/results?search_query=sanah' }] },
    run: async function (t) {
        const bar = document.getElementById('undoSkip');
        const shows = function () { return !!bar && !bar.hidden && bar.getClientRects().length > 0; };
        document.querySelector('#song-list tr[data-song-id="2"] form[action="/dj/dashboard/dismiss"] button').click();
        await t.waitFor(shows, 'the bar', 2500).catch(function () {});
        t.check('the bar shows after the skip', shows());
        const shownAt = performance.now();
        await t.waitFor(function () { return !shows(); }, 'the bar to go by itself', 12000).catch(function () {});
        t.check('it goes by itself, after some seconds — not at once', !shows() && performance.now() - shownAt > 5000);
        t.step('nothing was put back', await t.stand.count('POST /dj/dashboard/restore'), 0);
    }
});

S2P.scenario({
    name: 'queue-numbers-and-clear',
    title: 'the active queue: every request has its place number (a wide screen); "Wyczyść kolejkę" asks first, then empties the queue in place, and is gone with nothing to clear',
    setup: { queue: [{ id: 1, name: 'Wilki - Baśka', url: 'https://www.youtube.com/results?search_query=Wilki' },
                     { id: 2, name: 'sanah - Szampan', url: 'https://www.youtube.com/results?search_query=sanah' }] },
    run: async function (t) {
        const shows = function (element) { return !!element && element.getClientRects().length > 0; };
        await t.waitFor(function () { return document.querySelector('#song-list [data-song-id="2"]'); }, 'the queue poll', 8000);
        // the numbers are a CSS counter: a row counts itself, its song cell shows the count (a hidden row does not count)
        const row = document.querySelector('#song-list tr[data-song-id="2"]');
        const cell = row.querySelector('.song-title');
        t.step('every request is numbered (a counter the rows count up, shown before the song)',
            [/s2p-queue/.test(getComputedStyle(row).counterIncrement), /counter\(s2p-queue\)/.test(getComputedStyle(cell, '::before').content)],
            [true, true]);

        const button = document.getElementById('clearQueueBtn');
        t.check('"Wyczyść kolejkę" shows while requests wait', shows(button));

        const asked = [];
        let answer = false;
        window.confirm = function (text) { asked.push(text); return answer; };
        let handledInPlace = null;
        document.addEventListener('submit', function (e) { handledInPlace = e.defaultPrevented; e.preventDefault(); });

        button.click();
        await t.sleep(300);
        t.step('it asks first; "no" sends nothing',
            [asked.length, /historii/.test(asked[0] || ''), await t.stand.count('POST /dj/dashboard/clear-queue')], [1, true, 0]);

        answer = true;
        const clickedAt = performance.now();
        button.click();
        t.step('"yes": the page stays (the form goes in the background)', handledInPlace, true);
        await t.waitFor(function () { return !document.querySelector('#song-list tr[data-song-id]'); }, 'the queue to empty', 2500).catch(function () {});
        t.step('the server was told once', await t.stand.count('POST /dj/dashboard/clear-queue'), 1);
        t.check('the queue emptied at once, not with the next 3 s poll',
            !document.querySelector('#song-list tr[data-song-id]') && performance.now() - clickedAt < 2500);
        t.check('with nothing to clear the button is gone', !shows(button));
    }
});

S2P.scenario({
    name: 'history-clear',
    title: 'the History tab: "Wyczyść historię" asks first, then goes in the background and the tab is fetched again in place',
    setup: {},
    run: async function (t) {
        const fetches = function () { return t.stand.count('GET /dj/history-view/fragment'); };
        document.querySelector('[data-dj-tab="history"]').click();
        await t.waitFor(function () { return document.getElementById('clearHistoryBtn'); }, 'the history with its button', 5000);

        const asked = [];
        let answer = false;
        window.confirm = function (text) { asked.push(text); return answer; };
        let handledInPlace = null;
        document.addEventListener('submit', function (e) { handledInPlace = e.defaultPrevented; e.preventDefault(); });

        document.getElementById('clearHistoryBtn').click();
        await t.sleep(300);
        t.step('it asks first (the words go too, the AI forgets); "no" sends nothing',
            [asked.length, /na zawsze/.test(asked[0] || ''), await t.stand.count('POST /dj/dashboard/clear-history')], [1, true, 0]);

        answer = true;
        const before = await fetches();
        document.getElementById('clearHistoryBtn').click();
        t.step('"yes": the page stays (the form goes in the background)', handledInPlace, true);
        for (let i = 0; i < 25 && await fetches() === before; i++) await t.sleep(100);   // the stand-in counts the fetch
        t.step('the server was told once, and the tab fetched the history again at once',
            [await t.stand.count('POST /dj/dashboard/clear-history'), await fetches() - before], [1, 1]);
        t.check('the history still shows in place of the queue', !document.getElementById('history-content').hidden
            && !!document.querySelector('#history-content [data-list]'));
    }
});
