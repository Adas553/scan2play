// The demo (/demo, DemoController; the owner, 2026-10-24): the real panel, only to look at. Its queue is polled from /demo/updates,
// never from a DJ's address; a form (a 💸, a "Zagrane", a select that sends itself) and a link to a DJ's page send nothing — the
// page says "start your own party" instead (demo.js).
(function () {
    S2P.scenario({
        name: 'demo-sends-nothing',
        title: 'the demo\'s panel polls /demo/updates, and no form or link to a DJ\'s page sends anything — the page says why',
        page: 'dashboard-demo',
        setup: { queue: [{ id: 1, name: 'Wilki - Baśka', url: 'https://www.youtube.com/results?search_query=Wilki', number: 3 }] },
        run: async function (t) {
            // the stand-in's row (number 3) in place of the rendered ones: the poll has answered
            const polled = '#song-list tr[data-song-number="3"] form button';
            await t.waitFor(function () { return document.querySelector(polled); }, 'the demo\'s poll', 10000);
            t.step('polled from the demo, never from a DJ\'s address',
                [(await t.stand.count('GET /demo/updates')) > 0, await t.stand.count('GET /dj/dashboard/updates')], [true, 0]);

            const notice = document.getElementById('demoNotice');
            document.querySelector(polled).click();
            await t.sleep(400);
            const posts = (await t.stand.requests()).filter(function (r) { return r.m === 'POST'; });
            t.step('a 💸 sends nothing, the page says why', [posts.length, notice.hidden], [0, false]);

            const link = document.querySelector('a[href^="/dj/"]');
            t.check('the page has a link to a DJ\'s page (a settings row, the print)', link);
            notice.hidden = true;
            const at = location.href;
            link.click();
            await t.sleep(300);
            t.step('a link to a DJ\'s page leaves nothing, the page says why', [location.href, notice.hidden], [at, false]);
        }
    });
})();
