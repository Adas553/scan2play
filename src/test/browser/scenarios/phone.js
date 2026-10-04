// The dashboard on a phone (the DJ in the booth, phone in hand), every kind of party: the settings, the vibe, the background playlist
// and the QR code fold under one button, so what the DJ works with comes right under the heading — the player first at a YouTube
// party, then the queue — and every waiting request is a card with big buttons. The window is a phone's (viewport, read by run.py).

function shows(element) { return !!element && element.getClientRects().length > 0; }

/**
 * What every party's dashboard does on a phone. {@code buttons} are the forms of a waiting request's card (their actions); the rows
 * checked are the ones the server rendered — the stand-in's poll answers rows without buttons.
 */
async function phoneDashboard(t, buttons) {
    t.step('the window is a phone\'s', window.innerWidth < 768, true);
    const toggle = document.getElementById('settingsToggle');
    t.step('the settings, the vibe and the QR code are folded; the button to unfold them shows',
        [shows(document.getElementById('vibeSelect')), shows(document.getElementById('requestLimit')),
         shows(document.getElementById('partyLinkInput')), shows(toggle)],
        [false, false, false, true]);

    const queue = document.getElementById('queueList');
    const row = document.querySelector('#song-list tr[data-song-id]');
    t.step('a request is a card, with no table header', [getComputedStyle(row).display, shows(queue.querySelector('thead'))], ['flex', false]);
    const found = buttons.map(function (action) { return row.querySelector('form[action="' + action + '"] button'); });
    t.check('its buttons (' + buttons.join(', ') + ') are big enough for a thumb (at least 44 px high, a third of the card wide)',
        found.every(function (b) {
            const box = b && b.getBoundingClientRect();
            return !!box && box.height >= 44 && box.width >= row.getBoundingClientRect().width / 3;
        }));
    t.check('the list scrolls with the page, not in a box of its own',
        getComputedStyle(queue.querySelector('.list-scroll')).overflowY === 'visible');

    toggle.click();
    t.step('the button unfolds the settings, and the tab remembers it (a page reload keeps them open)',
        [shows(document.getElementById('vibeSelect')), shows(document.getElementById('partyLinkInput')),
         toggle.getAttribute('aria-expanded'), sessionStorage.getItem('scan2play.settingsOpen')],
        [true, true, 'true', '1']);
    toggle.click();
    t.step('and folds them again', [shows(document.getElementById('vibeSelect')), toggle.getAttribute('aria-expanded'),
        sessionStorage.getItem('scan2play.settingsOpen')], [false, 'false', null]);
    return queue;
}

S2P.scenario({
    name: 'requests-only-phone',
    title: 'a requests-only party on a phone: the settings fold under one button, the queue comes first, a request is a card with big buttons',
    page: 'dashboard-requests',
    viewport: '390,844',
    setup: { queue: [{ id: 1, name: 'Wilki - Baśka', url: 'https://www.youtube.com/results?search_query=Wilki' },
                     { id: 2, name: 'sanah - Szampan', url: 'https://www.youtube.com/results?search_query=sanah' }] },
    run: async function (t) {
        const queue = await phoneDashboard(t, ['/dj/dashboard/play', '/dj/dashboard/dismiss']);
        t.check('the queue starts in the first screen, under the heading', queue.getBoundingClientRect().top < window.innerHeight * 0.6);

        await t.waitFor(function () { return document.querySelector('#song-list [data-song-id="2"]'); }, 'the queue poll', 8000);
        t.step('after a poll the requests are still cards',
            getComputedStyle(document.querySelector('#song-list tr[data-song-id="2"]')).display, 'flex');
    }
});

S2P.scenario({
    name: 'youtube-phone',
    title: 'a YouTube party on a phone: the settings and the playlist fold, the player comes first and Auto-Pilot stays at hand, then the queue as cards',
    page: 'dashboard',
    viewport: '390,844',
    setup: { lease: { holder: true } },
    run: async function (t) {
        const queue = await phoneDashboard(t, ['/dj/dashboard/play']);
        const player = document.getElementById('yt-player-card');
        t.step('the background playlist\'s field is folded; the Auto-Pilot switch and the player are not',
            [shows(document.getElementById('fallbackInput')), shows(document.getElementById('autoToggle')), shows(player)],
            [false, true, true]);
        t.check('the player starts in the first screen, under the heading', player.getBoundingClientRect().top < window.innerHeight * 0.6);
        t.check('the queue comes after the player', queue.getBoundingClientRect().top > player.getBoundingClientRect().bottom);
    }
});
