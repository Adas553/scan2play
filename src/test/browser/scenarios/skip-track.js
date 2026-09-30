// Skipping a track of the "up next" list for this round (dashboard.js skipFallbackTrack; PROJECT_CONTEXT.md Section 5.4, "Skipping a
// track"). Every row has a ✕ button (data-skip): a press sends POST fallback-queue/skip with the track id, then the list is fetched
// again — also when the server refuses (409: the player took that track a moment ago), so the list shows what really is queued — and
// a press on it never starts a drag or a move. While a change is on its way further presses are ignored, like for the moves.
//
// The list is the REAL fragment (rendered by DashboardPageRenderTest: four tracks, one skipped in this round); what the server does
// with the skip — the track leaving the round, coming back in the next — is checked against a real PostgreSQL, not here.
S2P.scenario({
    name: 'skip-track',
    title: '✕ on a row sends the skip and refreshes the list (also after a 409); one change at a time; no move, no drag',
    setup: {},
    run: async function (t) {
        const rows = function () { return document.querySelectorAll('#fallbackQueue li[data-track-id]'); };
        const skipRequests = function () { return t.stand.requests('POST /dj/dashboard/fallback-queue/skip'); };
        const listFetches = function () { return t.stand.count('GET /dj/dashboard/fallback-queue'); };

        await t.waitFor(function () { return rows().length === 4; }, 'the up-next list');
        t.step('four rows, each with a skip button that is enabled (the first and the last too)',
            Array.from(document.querySelectorAll('#fallbackQueue button[data-skip]')).map(function (b) { return b.disabled; }), [false, false, false, false]);
        t.check('the caption says one track was skipped in this round', document.getElementById('fallbackQueue').textContent.indexOf('Pominięte w tej rundzie: 1') >= 0);

        // a skip: the request, then a fresh list
        const fetchesBefore = await listFetches();
        rows()[1].querySelector('button[data-skip]').click();
        await t.sleep(500);
        const first = await skipRequests();
        t.step('the press on the second row sent POST skip with its track id and the party', first.map(function (r) { return [r.q.trackId, r.q.partyCode]; }), [['12', 'HARN1']]);
        t.step('the list was fetched again afterwards', (await listFetches()) - fetchesBefore, 1);

        // one change at a time: the second press comes while the first is on its way
        await t.stand.config({ delays: { '/dj/dashboard/fallback-queue/skip': 1.0 } });
        rows()[0].querySelector('button[data-skip]').click();
        rows()[2].querySelector('button[data-skip]').click();
        await t.sleep(1800);
        const second = await skipRequests();
        t.step('two quick presses on different rows: only the first is sent', second.map(function (r) { return r.q.trackId; }), ['12', '11']);
        await t.stand.config({ delays: { '/dj/dashboard/fallback-queue/skip': 0 } });   // (a config is merged: a key is set back, not left out)

        // the player took the track just now: 409 — the list is refreshed all the same
        await t.stand.config({ queueActionStatus: 409 });
        const beforeConflict = await listFetches();
        rows()[3].querySelector('button[data-skip]').click();
        await t.sleep(500);
        t.step('a 409 is sent and answered', (await skipRequests()).map(function (r) { return r.q.trackId; }), ['12', '11', '14']);
        t.step('… and the list is fetched again to show what is really queued', (await listFetches()) - beforeConflict, 1);

        t.step('no move and no drop was sent', [await t.stand.count('POST /dj/dashboard/fallback-queue/move'), await t.stand.count('POST /dj/dashboard/fallback-queue/place')], [0, 0]);
    }
});
