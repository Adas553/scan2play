// The folded cards of "Ustawienia imprezy" and "Obsługa" (details.s2p-fold, folds.js): every card one line — its heading and its
// state, as the panel's row — and a tap unfolds it; unfolded, the state goes (the fields say it). The card the address names
// (#libraryCard: a row of the panel, the page again after a form that reloads it) unfolds by itself, the others stay folded; a form
// saved in the background leaves its card unfolded.
(function () {
    function folds() { return Array.from(document.querySelectorAll('details.s2p-fold')); }
    /** Drawn on the screen: a folded card's content is laid out but not drawn (content-visibility), so no getClientRects() here. */
    function shows(element) { return !!element && element.checkVisibility(); }
    function text(element) { return element ? element.textContent.replace(/\s+/g, ' ').trim() : null; }
    function stateOf(id) { return text(document.querySelector('#' + id + ' > summary .s2p-fold-state')); }

    S2P.scenario({
        name: 'settings-folded',
        title: 'the page "Ustawienia imprezy": every card folded to one line — its heading and its state —, a tap unfolds it, the state goes',
        page: 'settings',
        viewport: '390,844',
        run: async function (t) {
            t.step('every card folded', folds().map(function (fold) { return [fold.id, fold.open]; }),
                [['hostListsCard', false], ['linksCard', false], ['limitsCard', false], ['libraryCard', false], ['deviceCard', false]]);
            t.step('each line\'s state, as the panel\'s rows', [stateOf('hostListsCard'), stateOf('linksCard'), stateOf('limitsCard'),
                stateOf('libraryCard')], ['🚫 0 · ⭐ 1 · link', 'Instagram · napiwki', '2 na gościa · 3 min', 'Nie wgrano']);
            t.step('"Obsługa" a line like them, leading to its page', [text(document.querySelector('#staffRow .s2p-fold-name')),
                document.getElementById('staffRow').getAttribute('href')], ['Obsługa', '/dj/staff?party=HARN1']);
            const lines = folds().map(function (fold) { return fold.querySelector('summary'); }).concat([document.getElementById('staffRow')]);
            t.step('each line a finger\'s height, the state beside its heading, inside the card', lines.filter(function (line) {
                const box = line.getBoundingClientRect();
                const state = line.querySelector('.s2p-fold-state');
                const name = line.querySelector('.s2p-fold-name').getBoundingClientRect();
                return box.height < 44 || (state && state.getBoundingClientRect().right > box.right)
                    || (state && state.getBoundingClientRect().left < name.right);
            }).map(function (line) { return line.parentElement.id || line.id; }), []);
            t.step('the page folded fits one phone screen', document.documentElement.scrollHeight <= 1400, true);
            t.step('no field of a card shown while folded', shows(document.getElementById('hostBlockedInput')), false);

            document.querySelector('#hostListsCard > summary').click();
            t.step('a tap: the card unfolds, its fields shown, its state gone (the fields say it)',
                [document.getElementById('hostListsCard').open, shows(document.getElementById('hostBlockedInput')),
                    shows(document.querySelector('#hostListsCard .s2p-fold-state'))], [true, true, false]);
            t.step('the others stay folded', folds().filter(function (fold) { return fold.open; }).map(function (fold) { return fold.id; }),
                ['hostListsCard']);

            document.getElementById('hostListsForm').querySelector('button[type="submit"]').click();
            await t.sleep(800);
            t.step('saved in the background (forms.js): sent once, the card stays unfolded',
                [await t.stand.count('POST /dj/dashboard/host-lists'), document.getElementById('hostListsCard').open], [1, true]);
            t.check('nothing wider than the screen', document.documentElement.scrollWidth <= document.documentElement.clientWidth);
        }
    });

    S2P.scenario({
        name: 'settings-unfolds-the-named-card',
        title: 'the page "Ustawienia imprezy": the card the address names (#libraryCard — a row of the panel) unfolds, the others stay folded',
        page: 'settings',
        run: async function (t) {
            t.step('at first every card folded', folds().filter(function (fold) { return fold.open; }).length, 0);
            location.hash = 'libraryCard';
            await t.waitFor(function () { return document.getElementById('libraryCard').open; }, 'the library unfolded', 2000);
            t.step('the library unfolded, its form shown; the others folded', [shows(document.getElementById('libraryForm')),
                folds().filter(function (fold) { return fold.open; }).map(function (fold) { return fold.id; })], [true, ['libraryCard']]);
            location.hash = 'serverLimitsInfo';   // an element inside a card: its card unfolds
            await t.waitFor(function () { return document.getElementById('limitsCard').open; }, 'the limits unfolded', 2000);
            t.step('an element inside a card unfolds its card', document.getElementById('limitsCard').open, true);
            location.hash = '%zz';
            await t.sleep(200);
            t.step('an address typed by hand breaks nothing', document.getElementById('libraryCard').open, true);
            history.replaceState(null, '', location.pathname + location.search);
        }
    });

    S2P.scenario({
        name: 'settings-folded-wide',
        title: 'the page "Ustawienia imprezy" on a computer: the folded lines one under another, the states on the right, all of one width',
        page: 'settings',
        viewport: '1280,900',
        run: async function (t) {
            const cards = folds().concat([document.getElementById('staffRow')]);
            const widths = cards.map(function (card) { return Math.round(card.getBoundingClientRect().width); });
            t.step('every card of one width, "Obsługa" too', widths.every(function (w) { return w === widths[0]; }), true);
            const lines = folds().map(function (fold) { return fold.querySelector('summary'); }).concat([document.getElementById('staffRow')]);
            t.step('each state right-aligned, near the line\'s end', lines.filter(function (line) {
                const state = line.querySelector('.s2p-fold-state');
                return state && shows(state) && line.getBoundingClientRect().right - state.getBoundingClientRect().right > 60;
            }).map(function (line) { return line.parentElement.id || line.id; }), []);
        }
    });

    S2P.scenario({
        name: 'staff-folded',
        title: 'the page "Obsługa": "Zaproś" and the link folded, each with its state; the link unfolds by its address (#staffLinkCard)',
        page: 'staff',
        viewport: '390,844',
        run: async function (t) {
            const invite = document.getElementById('staffInviteCard');
            const link = document.getElementById('staffLinkCard');
            t.step('both folded (people help already: inviting is not the first thing to do)', [invite.open, link.open], [false, false]);
            t.step('their states: the places free, the link\'s role and how long it works',
                [stateOf('staffInviteCard'), /^Podgląd · do \d{1,2}\.\d{2}$/.test(stateOf('staffLinkCard'))], ['Wolne miejsca: 7 z 10', true]);
            t.step('each line a finger\'s height', [invite, link].map(function (fold) {
                return fold.querySelector('summary').getBoundingClientRect().height >= 44;
            }), [true, true]);
            location.hash = 'staffLinkCard';
            await t.waitFor(function () { return link.open; }, 'the link unfolded', 2000);
            t.step('the link unfolded by its address, its field shown; "Zaproś" folded',
                [shows(document.getElementById('staffLinkInput')), invite.open], [true, false]);
            history.replaceState(null, '', location.pathname + location.search);
            t.check('nothing wider than the screen', document.documentElement.scrollWidth <= document.documentElement.clientWidth);
        }
    });
})();
