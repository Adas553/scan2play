// THE DESIGN REVIEW (2026-10-09): what the shared look promises — the guest's form first, everything a thumb taps at least 44 px
// high on a phone, the account's menu over the tab bar, and an empty queue that says what to do next (the QR code, "Ustaw klimat").
(function () {
    const shows = function (el) { return !!el && el.getClientRects().length > 0 && getComputedStyle(el).visibility !== 'hidden'; };
    /** The visible things to tap inside {@code root} that are lower than 44 px (WCAG's target size), named for the report. */
    const tooSmall = function (root) {
        return Array.from(root.querySelectorAll('a.btn, button, input:not([type="hidden"]):not([type="checkbox"]), select, textarea, summary, '
            + '.s2p-row, .s2p-chips a'))
            .filter(shows)
            .filter(function (el) { return el.getBoundingClientRect().height < 44 - 0.5; })
            .map(function (el) {
                return (el.id || el.getAttribute('name') || el.textContent.trim().slice(0, 20) || el.tagName) + ' ' + Math.round(el.getBoundingClientRect().height) + 'px';
            });
    };

    S2P.scenario({
        name: 'guest-form-first',
        title: 'the guest page on a phone: the field and "Wyślij prośbę" in the first screen, the DJ\'s profiles and tip (if any) under the list',
        page: 'guest',
        viewport: '390,844',
        run: async function (t) {
            const input = document.getElementById('songInput');
            const submit = document.getElementById('submitBtn');
            t.check('the field is in the upper half of the first screen', input.getBoundingClientRect().top < window.innerHeight * 0.5);
            t.check('"Wyślij prośbę" is in the first screen', submit.getBoundingClientRect().bottom <= window.innerHeight);
            const support = document.getElementById('djSupport');
            t.check('the profiles and the tip, when the DJ has them, come after the list',
                !support || !!(document.getElementById('guestQueueBox').compareDocumentPosition(support) & Node.DOCUMENT_POSITION_FOLLOWING));
        }
    });

    S2P.scenario({
        name: 'guest-touch-targets',
        title: 'the guest page on a phone: every button and field (the 👍 too, "Odśwież", "Pokaż pozostałe") is at least 44 px high',
        page: 'guest-many',
        viewport: '390,844',
        run: async function (t) {
            t.step('nothing to tap is lower than 44 px', tooSmall(document.body), []);
        }
    });

    S2P.scenario({
        name: 'dashboard-touch-targets',
        title: 'the dashboard on a phone: the account\'s buttons, the tabs, the queue\'s buttons and the unfolded settings are at least 44 px high',
        viewport: '390,844',
        setup: { queue: [{ id: 1, name: 'Wilki - Baśka', url: 'https://www.youtube.com/results?search_query=Wilki', number: 3 }] },
        run: async function (t) {
            t.step('the account\'s row, the tabs and the queue: nothing lower than 44 px',
                tooSmall(document.querySelector('.s2p-account-bar')).concat(tooSmall(document.getElementById('djTabBar')),
                    tooSmall(document.getElementById('queueList'))), []);
            document.getElementById('settingsToggle').click();
            t.step('the unfolded settings: nothing lower than 44 px', tooSmall(document.querySelector('.row.s2p-settings')), []);
        }
    });

    S2P.scenario({
        name: 'account-menu',
        title: 'the dashboard: "Zakończ" in words, the rest under "Konto" (about the person) — the open menu is over the sticky tab bar',
        viewport: '390,844',
        run: async function (t) {
            const end = document.querySelector('#end-party-form button');
            t.check('"Zakończ" says it in words on a phone too (beside the logo; the question names the party)',
                shows(end) && end.textContent.trim().length > 3);
            const logout = document.querySelector('form[action$="/dj/logout"] button');
            t.step('the logout is folded in the menu', shows(logout), false);
            document.getElementById('accountMenuBtn').click();
            await t.waitFor(function () { return shows(logout); }, 'the menu to open', 3000);
            const items = Array.from(document.querySelectorAll('.s2p-account-bar .dropdown-menu .dropdown-item'));
            const covered = items.filter(function (item) {
                const box = item.getBoundingClientRect();
                const hit = document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2);
                return !(hit === item || item.contains(hit));
            }).map(function (item) { return item.textContent.trim(); });
            // the owner's three — "Zgłoś uwagę", "Wyloguj", "Usuń konto" —, every one in reach
            t.step('every item of the menu can be tapped: nothing (the sticky tab bar) lies over one', [items.length, covered], [3, []]);
            t.step('every item is at least 44 px high', items.filter(function (i) { return i.getBoundingClientRect().height < 44 - 0.5; }).length, 0);
            const last = items[items.length - 1];
            t.check('the deletion of the account is the last one, in red', last && last.id === 'deleteAccountBtn'
                && getComputedStyle(last).color === 'rgb(255, 123, 123)');
            // the party's staff is a row of the settings, not the person's menu (2026-10-10)
            t.check('no page of the staff in the menu', !document.getElementById('staffMenuLink'));
        }
    });

    // THE PANEL'S CLEAN-UP (2026-10-10, the design review: four rows above a phone's queue, seven cards in the settings): our logo
    // beside "Zakończ" and "Konto", the settings as two cards and the rows of "Więcej", the rare settings on a page of their own.
    S2P.scenario({
        name: 'dashboard-heading-one-row',
        title: 'the dashboard on a phone: our logo, "Zakończ" and "Konto" in one row — the first request comes higher',
        viewport: '390,844',
        run: async function (t) {
            const logo = document.querySelector('.s2p-logo');
            const account = document.getElementById('accountMenuBtn');
            const end = document.querySelector('#end-party-form button');
            const middle = function (el) { const box = el.getBoundingClientRect(); return box.top + box.height / 2; };
            t.check('the logo is in the row of the account', !!logo.closest('.s2p-account-bar'));
            t.check('…on one line with "Zakończ" and "Konto"', Math.abs(middle(logo) - middle(account)) < 8 && Math.abs(middle(end) - middle(account)) < 8);
            t.check('nothing wider than the screen', document.documentElement.scrollWidth <= window.innerWidth);
            // the rendered rows (the stand-in's poll replaces them in 3 s)
            const first = document.querySelector('#song-list tr[data-song-id]');
            const top = Math.round(first.getBoundingClientRect().top);
            t.check('the first request comes higher than the 382 px of four rows: at ' + top + ' px', top < 340);
        }
    });

    S2P.scenario({
        name: 'settings-more-rows',
        title: 'the unfolded settings: two cards ("Impreza", the QR code) and a row with its state for each rare setting, leading to its page',
        run: async function (t) {
            document.getElementById('settingsToggle').click();
            const cards = Array.from(document.querySelectorAll('.s2p-settings section.card')).filter(shows).map(function (c) { return c.id; });
            t.step('two cards', cards, ['partyCard', 'qrCard']);
            const rows = Array.from(document.querySelectorAll('#settingsMore [data-settings-row]')).filter(shows);
            t.step('a row per rare setting, each to its card on the page "Ustawienia imprezy" (the staff: their own page)', rows.map(function (row) {
                return [row.dataset.settingsRow, row.getAttribute('href')];
            }), [['hosts', '/dj/settings?party=HARN1#hostListsCard'], ['links', '/dj/settings?party=HARN1#linksCard'],
                ['limits', '/dj/settings?party=HARN1#limitsCard'], ['staff', '/dj/staff?party=HARN1'], ['device', '/dj/settings?party=HARN1#deviceCard']]);
            const stateOf = function (row) {   // what shows of it (the row of the limits has its states near a limit hidden)
                const state = row.querySelector('.s2p-row-state');
                const parts = state.children.length ? Array.from(state.children).filter(shows) : [state];
                return parts.map(function (p) { return p.textContent.replace(/\s+/g, ' ').trim(); }).join(' ');
            };
            t.step('each says its state', rows.slice(0, 4).map(stateOf),
                ['🚫 0 · ⭐ 1 · link', 'Instagram · napiwki', '2 na gościa · 3 min przerwy', 'Osób: 1']);
            t.step('each a thumb\'s target', rows.filter(function (row) { return row.getBoundingClientRect().height < 44; }).length, 0);
            t.check('the queue starts in the first screen with them unfolded', document.getElementById('queueList').getBoundingClientRect().top < window.innerHeight);
        }
    });

    // The owner's page "Obsługa" (the owner, 2026-10-10: every person unfolded repeated the same picker): a person is one line — the
    // name, the role, since when —, a tap unfolds what they may do; the one just saved comes back unfolded with "Zapisano".
    S2P.scenario({
        name: 'staff-people-folded',
        title: 'the owner\'s page "Obsługa": each person folded to one line (name, role, since when); a tap unfolds the picker',
        page: 'staff',
        viewport: '390,844',
        run: async function (t) {
            // a closed <details> keeps its content laid out but not shown (content-visibility): checkVisibility() tells
            const picker = function (person) { return person.querySelector('[data-role-select]').checkVisibility(); };
            const kasia = document.querySelector('[data-staff-id="7"]');   // just saved (the rendered page: "Zapisano" for her)
            const tomek = document.querySelector('[data-staff-id="8"]');
            t.step('the one just saved unfolded with "Zapisano", the others folded', [picker(kasia),
                kasia.querySelector('[data-staff-saved]').checkVisibility(), picker(tomek)], [true, true, false]);
            const line = kasia.querySelector('summary');
            t.step('one line says who, the role and since when', line.textContent.replace(/\s+/g, ' ').trim(), 'Kasia Obsługa kolejki w obsłudze od 10.10');
            t.check('the line is a thumb\'s target', line.getBoundingClientRect().height >= 44 - 0.5);
            tomek.querySelector('summary').click();
            await t.sleep(100);
            t.step('a tap unfolds the picker, "Zapisz" and "Usuń dostęp"', [picker(tomek),
                tomek.querySelector('form[action="/dj/staff/permissions"] button[type="submit"]').checkVisibility(),
                tomek.querySelector('form[action="/dj/staff/remove"] button').checkVisibility()], [true, true, true]);
            line.click();
            await t.sleep(100);
            t.step('…and another tap folds it', picker(kasia), false);
        }
    });

    S2P.scenario({
        name: 'settings-page-touch-targets',
        title: 'the page "Ustawienia imprezy" on a phone: every button, field, jump and the server limits\' line at least 44 px high; no sideways scroll',
        page: 'settings',
        viewport: '390,844',
        run: async function (t) {
            t.step('nothing to tap is lower than 44 px', tooSmall(document.body), []);
            t.check('nothing wider than the screen', document.documentElement.scrollWidth <= window.innerWidth);
            const jumps = Array.from(document.querySelectorAll('#settingsJump a'));
            t.step('every jump to a card whole on the screen (wrapped, not cut at the edge)', jumps.filter(function (a) {
                const box = a.getBoundingClientRect();
                return box.left < 0 || box.right > window.innerWidth;
            }).map(function (a) { return a.textContent.trim(); }), []);
            t.check('"← Panel" leads to the panel of the same party', document.getElementById('backToPanel').getAttribute('href') === '/dj/dashboard?party=HARN1');
        }
    });

    S2P.scenario({
        name: 'queue-empty-next-step',
        title: 'the dashboard: a queue with nothing waiting shows what to do next — the QR code to print, "Ustaw klimat" opens the settings',
        setup: { queue: [] },
        run: async function (t) {
            const empty = document.getElementById('queueEmpty');
            t.step('while the rendered requests wait, no empty state', shows(empty), false);
            await t.waitFor(function () { return !document.querySelector('#song-list tr[data-song-id]'); }, 'the poll to empty the queue', 8000);
            await t.sleep(100);
            t.step('nothing waits: the next step shows, the table and its search do not',
                [shows(empty), shows(document.querySelector('#queueList .table-responsive')), shows(document.querySelector('#queueList [data-list-search]'))],
                [true, false, false]);
            t.step('the settings are folded', shows(document.getElementById('vibeSelect')), false);
            empty.querySelector('[data-open-settings]').click();
            await t.sleep(200);
            t.step('"Ustaw klimat" opens them, the vibe in view', shows(document.getElementById('vibeSelect')), true);
        }
    });
})();
