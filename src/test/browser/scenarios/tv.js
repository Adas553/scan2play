// The screen for a TV (Next 7 c, the owner 2026-10-18: public, the ten most wanted): the page TvScreenWebTest renders (tv.html), its
// list fetched again by js/tv.js every 10 s (the stand-in answers the rendered tv-list*.html). Real timers: these wait for a fetch.

/** The whole screen at once: the code and the ten songs inside the window, nothing to scroll, each song on one line. */
function screenFits(t) {
    const qr = document.querySelector('.tv-qr');
    const box = qr ? qr.getBoundingClientRect() : null;
    t.check('the QR code is shown, at least 40 % of the screen\'s height', !!box && box.width >= 0.4 * window.innerHeight
        && box.bottom <= window.innerHeight);
    const songs = Array.from(document.querySelectorAll('.tv-song'));
    t.step('ten songs', songs.length, 10);
    t.step('every song inside the window', songs.filter(function (li) { return li.getBoundingClientRect().bottom > window.innerHeight; })
        .map(function (li) { return li.textContent.trim(); }), []);
    t.step('every song on one line', songs.filter(function (li) {
        const name = li.querySelector('.tv-name');
        return name.getBoundingClientRect().height > parseFloat(getComputedStyle(name).fontSize) * 1.6;
    }).length, 0);
    t.check('nothing to scroll', document.documentElement.scrollHeight <= window.innerHeight
        && document.documentElement.scrollWidth <= document.documentElement.clientWidth);
}

/** The first song's name on the screen. */
function firstSong() {
    const name = document.querySelector('.tv-song .tv-name');
    return name ? name.textContent.trim() : null;
}

S2P.scenario({
    name: 'tv-fits-a-full-hd-tv',
    title: 'the screen for a TV at 1920 × 1080: the code and the ten most wanted at once, nothing to scroll, under the real policy',
    page: 'tv',
    viewport: '1920,1080',
    run: async function (t) {
        screenFits(t);
    }
});

S2P.scenario({
    name: 'tv-fits-a-small-tv',
    // the window 1280 × 720 shows a page of ~1262 × 568 in headless Chrome — as a laptop's browser, not full screen, on a projector
    title: 'the screen for a TV in a window of 1280 × 720 (a laptop on a projector, not full screen): the same, nothing to scroll',
    page: 'tv',
    viewport: '1280,720',
    run: async function (t) {
        screenFits(t);
    }
});

S2P.scenario({
    name: 'tv-list-refreshes',
    title: 'the screen for a TV fetches its list again by itself: the room\'s votes change the screen, the code stays',
    page: 'tv',
    viewport: '1920,1080',
    setup: { tvList: 'tv-list-next' },
    run: async function (t) {
        t.step('at first: the song with the most votes', firstSong(), 'Zenek Martyniuk - Przez twe oczy zielone');
        const qr = document.querySelector('.tv-qr');
        await t.waitFor(function () { return firstSong() === 'ABBA - Dancing Queen'; }, 'the list fetched again', 13000);
        t.step('the next list on the screen, still ten songs', document.querySelectorAll('.tv-song').length, 10);
        t.check('the code is the same element (not loaded again)', document.querySelector('.tv-qr') === qr);
        t.check('the list asked of the server', (await t.stand.count('GET /p/ABC12/tv/list')) >= 1);
    }
});

S2P.scenario({
    name: 'tv-keeps-the-list-when-offline',
    title: 'the screen for a TV: a failed fetch keeps the list on the screen; the party ended — the screen says so',
    page: 'tv',
    viewport: '1920,1080',
    setup: { tvListStatus: 500 },
    run: async function (t) {
        let asked = 0;
        const started = performance.now();
        while (asked === 0 && performance.now() - started < 13000) {
            await t.sleep(250);
            asked = await t.stand.count('GET /p/ABC12/tv/list');
        }
        t.check('the list asked of the server (and answered 500)', asked >= 1);
        await t.sleep(300);
        t.step('the list stays on the screen', [document.querySelectorAll('.tv-song').length, firstSong()],
            [10, 'Zenek Martyniuk - Przez twe oczy zielone']);

        await t.stand.config({ tvListStatus: null, tvList: 'tv-list-ended' });
        await t.waitFor(function () { return !!document.getElementById('tvEnded'); }, 'the party ended on the screen', 13000);
        t.step('no songs once the party has ended', document.querySelectorAll('.tv-song').length, 0);
    }
});
