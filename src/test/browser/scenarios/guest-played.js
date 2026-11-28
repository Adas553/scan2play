// "Co to było?" (V83, DeckSetService): the last songs of the DJ's set under the guest's list — folded, fetched only when unfolded
// (GET /p/ABC12/played, the real fragment GuestPageRenderTest renders), never with the page nor by a timer.
(function () {
    const details = function () { return document.getElementById('playedTonight'); };

    S2P.scenario({
        name: 'guest-played-fetched-on-unfolding',
        title: '"Co to było?": folded, nothing asked with the page; unfolded — the set\'s last songs fetched once, the newest first; again at each unfolding',
        page: 'guest-with-set',
        viewport: '390,844',
        run: async function (t) {
            await t.sleep(500);
            t.step('folded, nothing asked yet', [details().open, await t.stand.count('GET /p/ABC12/played')], [false, 0]);
            details().querySelector('summary').click();
            await t.waitFor(function () { return !!document.getElementById('playedSongs'); }, 'the songs', 3000);
            t.step('the songs, the newest first, with their times', Array.from(document.querySelectorAll('#playedSongs li')).map(function (li) {
                return li.textContent.replace(/\s+/g, ' ').trim();
            }).slice(0, 2), ['21:42 Wilki - Baśka', '21:38 Golec uOrkiestra - Ściernisco']);
            t.step('asked once', await t.stand.count('GET /p/ABC12/played'), 1);
            const box = details().getBoundingClientRect();
            t.check('nothing wider than the phone', box.right <= document.documentElement.clientWidth);
            details().querySelector('summary').click();
            await t.stand.config({ guestPlayed: 'guest-played-none' });
            details().querySelector('summary').click();
            await t.waitFor(function () { return !!document.getElementById('playedNone'); }, '"nothing yet" fetched again', 3000);
            t.step('asked again on the second unfolding', await t.stand.count('GET /p/ABC12/played'), 2);
        }
    });

    S2P.scenario({
        name: 'guest-played-not-loaded',
        title: '"Co to było?" that does not load (offline, the server failing): a line says so, unfolding again asks again',
        page: 'guest-with-set',
        viewport: '390,844',
        setup: { guestPlayedStatus: 500 },
        run: async function (t) {
            details().querySelector('summary').click();
            await t.waitFor(function () { return details().querySelector('.s2p-played-list p') !== null; }, 'the line', 3000);
            t.check('says so', details().querySelector('.s2p-played-list').textContent.indexOf('Nie udało się') >= 0);
            await t.stand.config({ guestPlayedStatus: null });
            details().querySelector('summary').click();
            details().querySelector('summary').click();
            await t.waitFor(function () { return !!document.getElementById('playedSongs'); }, 'the songs at the next unfolding', 3000);
        }
    });
})();
