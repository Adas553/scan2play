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
                [['partyHoursCard', false], ['hostListsCard', false], ['kioskCard', false], ['linksCard', false], ['logoCard', false], ['limitsCard', false],
                    ['libraryCard', false], ['deviceCard', false], ['partyCodeCard', false]]);
            t.step('each line\'s state, as the panel\'s rows', [stateOf('partyHoursCard'), stateOf('hostListsCard'), stateOf('linksCard'),
                stateOf('logoCard'), stateOf('limitsCard'), stateOf('libraryCard'), stateOf('partyCodeCard')],
                ['Nie ustawiono', '🚫 0 · ⭐ 1 · link', 'Instagram · napiwki', 'Brak', '2 na gościa · 3 min', 'Nie wgrano', 'HARN1']);
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

    /** A card's buttons on a phone (app.css): its width, or half of it in a row of two; a field's own button and a text one aside. */
    function buttonsOfOneWidth(t, label) {
        const odd = [];
        folds().concat(Array.from(document.querySelectorAll('details.s2p-staff-member'))).forEach(function (fold) {
            const body = fold.querySelector('.s2p-fold-body, .s2p-staff-body');
            const width = body.getBoundingClientRect().width - parseFloat(getComputedStyle(body).paddingLeft)
                - parseFloat(getComputedStyle(body).paddingRight);
            body.querySelectorAll('form .btn').forEach(function (button) {
                if (!button.checkVisibility() || button.closest('.input-group') || button.classList.contains('btn-link')
                    || button.parentElement.classList.contains('d-flex') && button.parentElement.querySelector('input')) return;
                // a row of n buttons: n shares of the width less the gaps (8 px) between them — one alone has it all
                const row = button.closest('.s2p-btn-row');
                const share = row ? (width - 8 * (row.children.length - 1)) / row.children.length : width;
                if (Math.abs(button.getBoundingClientRect().width - share) > 1.5) odd.push(button.textContent.trim());
            });
        });
        t.step(label, odd, []);
    }

    S2P.scenario({
        name: 'settings-buttons-one-width',
        title: 'the page "Ustawienia imprezy" on a phone, every card unfolded: each "Zapisz" and "Wgraj" the card\'s width, "Nowy link" / "Wyłącz" half each',
        page: 'settings',
        viewport: '390,844',
        run: async function (t) {
            folds().forEach(function (fold) { fold.querySelector('summary').click(); });
            buttonsOfOneWidth(t, 'every button of a card as wide as the card (a row of two: half each)');
            const row = Array.from(document.querySelectorAll('#hostLinkBox .s2p-btn-row .btn'));
            t.step('"Nowy link" and "Wyłącz" side by side, of one width', [row.length, row.length === 2
                && Math.abs(row[0].getBoundingClientRect().top - row[1].getBoundingClientRect().top) < 1
                && Math.abs(row[0].getBoundingClientRect().width - row[1].getBoundingClientRect().width) < 1], [2, true]);
        }
    });

    S2P.scenario({
        name: 'staff-buttons-one-width',
        title: 'the page "Obsługa" on a phone, everything unfolded: each "Zapisz", "Usuń dostęp", "Cofnij zaproszenie", "Nowy link" the card\'s width',
        page: 'staff',
        viewport: '390,844',
        run: async function (t) {
            document.querySelectorAll('details.s2p-fold, details.s2p-staff-member').forEach(function (fold) { fold.open = true; });
            buttonsOfOneWidth(t, 'every button of a card as wide as the card');
        }
    });

    /** A card's buttons on a computer: each as wide as its words, all of one height — the card's action ("Wgraj", "Zaproś") too. */
    function buttonsOfTheirWords(t) {
        const buttons = [];
        folds().concat(Array.from(document.querySelectorAll('details.s2p-staff-member'))).forEach(function (fold) {
            fold.querySelectorAll('.s2p-fold-body form .btn, .s2p-staff-body form .btn').forEach(function (button) {
                if (button.checkVisibility() && !button.closest('.input-group') && !button.classList.contains('btn-link')) buttons.push(button);
            });
        });
        const heights = buttons.map(function (button) { return Math.round(button.getBoundingClientRect().height); });
        t.step('no button of a card as wide as the card', buttons.filter(function (button) {
            return button.getBoundingClientRect().width > 300;
        }).map(function (button) { return button.textContent.trim(); }), []);
        t.step('every button of a card of one height', heights.every(function (h) { return h === heights[0]; }), true);
    }

    S2P.scenario({
        name: 'settings-buttons-wide',
        title: 'the page "Ustawienia imprezy" on a computer, every card unfolded: each button as wide as its words, all of one height, "Wgraj" too',
        page: 'settings',
        viewport: '1280,900',
        run: async function (t) {
            folds().forEach(function (fold) { fold.querySelector('summary').click(); });
            buttonsOfTheirWords(t);
            const row = Array.from(document.querySelectorAll('#hostLinkBox .s2p-btn-row .btn'));
            t.step('"Nowy link" and "Wyłącz" side by side', Math.abs(row[0].getBoundingClientRect().top - row[1].getBoundingClientRect().top) < 1, true);
        }
    });

    S2P.scenario({
        name: 'staff-buttons-wide',
        title: 'the page "Obsługa" on a computer, everything unfolded: each button as wide as its words, all of one height, "Zaproś" too',
        page: 'staff',
        viewport: '1280,900',
        run: async function (t) {
            document.querySelectorAll('details.s2p-fold, details.s2p-staff-member').forEach(function (fold) { fold.open = true; });
            buttonsOfTheirWords(t);
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
