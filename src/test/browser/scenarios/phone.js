// The dashboard on a phone (the DJ in the booth, phone in hand): the settings, the vibe and the QR code fold under one button, so the
// queue comes right under the heading, and every waiting request is a card with big buttons. The window is a phone's (viewport, read by
// run.py).

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
    t.check('its buttons (' + buttons.join(', ') + ') are big enough for a thumb (at least 44 px high, a quarter of the card wide)',
        found.every(function (b) {
            const box = b && b.getBoundingClientRect();
            return !!box && box.height >= 44 && box.width >= row.getBoundingClientRect().width / 4;
        }));
    // "▶ Zagrane", the one used most, is the bigger target: about two thirds of the row, "⏭ Pomiń" one third; each on one line
    const played = row.querySelector('form[action="/dj/dashboard/play"] button').getBoundingClientRect();
    const skip = row.querySelector('form[action="/dj/dashboard/dismiss"] button').getBoundingClientRect();
    t.check('"▶ Zagrane" is about twice as wide as "⏭ Pomiń"', played.width > skip.width * 1.6);
    t.check('both labels on one line', played.height < 60 && skip.height < 60);
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
    title: 'the dashboard on a phone: the settings fold under one button, the queue comes first, a request is a card with big buttons',
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
    name: 'skip-undo-phone',
    title: 'on a phone the "Cofnij" bar is as wide as it needs: the song on one line, the bar inside the screen',
    viewport: '390,844',
    setup: { queue: [{ id: 1, name: 'Wilki - Baśka', url: 'https://www.youtube.com/results?search_query=Wilki' }] },
    run: async function (t) {
        t.step('the window is a phone\'s', window.innerWidth < 768, true);
        const bar = document.getElementById('undoSkip');
        // the rendered row has the buttons (the stand-in's polled rows do not): click before the first poll replaces it
        document.querySelector('#song-list tr[data-song-id="1"] form[action="/dj/dashboard/dismiss"] button').click();
        await t.waitFor(function () { return !bar.hidden; }, 'the bar', 2500).catch(function () {});
        const song = bar.querySelector('[data-undo-song]');
        const box = bar.getBoundingClientRect();
        // 2026-10-05, the owner's phone: the bar got half the screen (left: 50%) and the song one letter per line
        // a flex item has one box however many lines it takes: one line is less than two font sizes high
        t.check('the song is on one line (' + Math.round(song.getBoundingClientRect().height) + ' px high)',
            song.getBoundingClientRect().height < 2 * parseFloat(getComputedStyle(song).fontSize));
        t.check('the bar is inside the screen, with a margin', box.left >= 8 && box.right <= window.innerWidth - 8);
        t.check('the "Cofnij" button is big enough for a thumb (at least 36 px high)',
            bar.querySelector('[data-undo-button]').getBoundingClientRect().height >= 36);
    }
});

// The owner (2026-10-07): on a phone the history was a table of narrow columns — a title broke into a word per line, the skip's label
// ran off the screen. Now a card per request, as the queue.
S2P.scenario({
    name: 'history-phone',
    title: 'the history on a phone: a card per request — the song across it, the skip and "↩ Przywróć" inside the screen, the AI\'s comment shown',
    viewport: '390,844',
    setup: {},
    run: async function (t) {
        document.querySelector('[data-dj-tab="history"]').click();
        await t.waitFor(function () { return document.querySelector('#history-content tr[data-decision]'); }, 'the history', 5000);
        const card = document.querySelector('#history-content tr[data-song-name="Rejected Beat"]');
        const cardBox = card.getBoundingClientRect();
        const songBox = card.querySelector('td[data-sort-value="song"]').getBoundingClientRect();
        t.step('a request is a card', getComputedStyle(card).display, 'flex');
        t.check('the song goes across the card (not a narrow column)', songBox.width >= cardBox.width * 0.85);
        const label = card.querySelector('.s2p-skipped-label');
        const restore = card.querySelector('form[action="/dj/dashboard/restore"] button');
        t.check('"⏭ Pominięta przez DJ-a" and "↩ Przywróć" show inside the screen',
            [label, restore].every(function (el) { return shows(el) && el.getBoundingClientRect().right <= window.innerWidth; }));
        t.step('skipped in place of the AI\'s verdict: no red ✖ of a rejection',
            Array.from(card.querySelectorAll('.s2p-decision-icon')).filter(shows).length, 0);
        const comment = card.querySelector('td.s2p-comment-cell');
        t.check('the AI\'s comment shows (hidden in the table below a wide screen)', shows(comment) && /parkiet/.test(comment.textContent));
        // one line of it, a tap shows the whole of it, a second tap folds it (the owner, 2026-10-07: the comments took the cards)
        const lineHeight = parseFloat(getComputedStyle(comment).lineHeight);
        const folded = comment.getBoundingClientRect().height;
        comment.click();
        const opened = comment.getBoundingClientRect().height;
        comment.click();
        t.step('the comment: one line, a tap opens the whole of it, another folds it',
            [folded < lineHeight * 1.5, opened > folded * 1.5, comment.getBoundingClientRect().height === folded],
            [true, true, true]);
        t.step('the column sort stays: Piosenka and Głosy', Array.from(document.querySelectorAll('#history-content thead th'))
            .filter(shows).map(function (th) { return th.getAttribute('data-sort'); }), ['song', 'votes']);
        t.check('nothing wider than the screen', document.documentElement.scrollWidth <= window.innerWidth);
    }
});
