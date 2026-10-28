// THE DESIGN REVIEW (2026-10-09): what the shared look promises — the guest's form first, everything a thumb taps at least 44 px
// high on a phone, the account's menu over the tab bar, and an empty queue that says what to do next (the QR code, "Ustaw klimat").
(function () {
    const shows = function (el) { return !!el && el.getClientRects().length > 0 && getComputedStyle(el).visibility !== 'hidden'; };
    /** The visible things to tap inside {@code root} that are lower than 44 px (WCAG's target size), named for the report. */
    const tooSmall = function (root) {
        return Array.from(root.querySelectorAll('a.btn, button, input:not([type="hidden"]):not([type="checkbox"]), select, textarea, summary, '
            + '.s2p-row, .s2p-chips a'))
            // drawn: not in a folded card (laid out, not drawn) nor transparent (the file input under its label, which is the target)
            .filter(function (el) { return el.checkVisibility({ opacityProperty: true, visibilityProperty: true }); })
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
        name: 'guest-send-after-back',
        title: 'the guest sent a request, then came back with "Wstecz" (the page from the browser\'s back-forward cache): "Wyślij prośbę" works again, without the spinner (review 2026-10-14)',
        page: 'guest',
        run: async function (t) {
            const form = document.getElementById('requestForm');
            const submit = document.getElementById('submitBtn');
            const label = document.getElementById('submitBtnText').textContent.trim();
            document.getElementById('songInput').value = 'Wilki - Baśka';
            form.dispatchEvent(new Event('submit', { cancelable: true }));   // the handler runs; a made-up event sends nothing
            t.step('sending: the button waits', submit.disabled, true);
            window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }));
            t.step('back from the cache: the button as it was', [submit.disabled, document.getElementById('submitBtnText').textContent.trim(),
                !!submit.querySelector('.spinner-border')], [false, label, false]);
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
            // the owner's four — "Instrukcja", "Pomoc i kontakt" (with the old "Zgłoś uwagę" in it, 2026-10-12), "Wyloguj", "Usuń
            // konto" — and, after the help, the language "Polski" / "English" (2026-10-18): every one in reach
            t.step('every item of the menu can be tapped: nothing (the sticky tab bar) lies over one', [items.length, covered], [6, []]);
            t.step('the guide and the help first', items.slice(0, 2).map(function (item) { return item.textContent.trim(); }),
                ['Instrukcja', 'Pomoc i kontakt']);
            t.step('every item is at least 44 px high', items.filter(function (i) { return i.getBoundingClientRect().height < 44 - 0.5; }).length, 0);
            const last = items[items.length - 1];
            t.check('the deletion of the account is the last one, in red', last && last.id === 'deleteAccountBtn'
                && getComputedStyle(last).color === 'rgb(255, 123, 123)');
            // the party's staff is a row of the settings, not the person's menu (2026-10-10)
            t.check('no page of the staff in the menu', !document.getElementById('staffMenuLink'));
        }
    });

    // "Pomoc i kontakt" (the owner, 2026-10-12): the address to write to with "Kopiuj", the guide, and the feedback form of the old
    // "Zgłoś uwagę" — one window, not two "write to us" side by side.
    S2P.scenario({
        name: 'help-window',
        title: 'the menu "Konto" → "Pomoc i kontakt": the address with "Kopiuj" (copied, or selected where the clipboard is refused), the guide, the feedback form; nothing lower than 44 px',
        viewport: '390,844',
        run: async function (t) {
            document.getElementById('accountMenuBtn').click();
            const help = document.getElementById('helpMenuBtn');
            await t.waitFor(function () { return shows(help); }, 'the menu to open', 3000);
            help.click();
            const modal = document.getElementById('feedbackModal');
            await t.waitFor(function () { return modal.classList.contains('show') && shows(document.getElementById('helpEmail')); }, 'the window', 3000);
            t.step('the address, the guide and the form', [document.getElementById('helpEmail').textContent.trim(),
                document.getElementById('helpGuideLink').getAttribute('href'), shows(document.getElementById('feedbackMessage'))],
                ['scan2playcontact@gmail.com', '/jak-to-dziala', true]);
            const copy = document.getElementById('helpEmailCopy');
            copy.click();
            await t.sleep(300);
            t.check('"Kopiuj" copies it, or selects it to copy ("' + copy.textContent.trim() + '")', copy.textContent.trim() === 'Skopiowano'
                || String(window.getSelection()) === 'scan2playcontact@gmail.com');
            t.step('nothing to tap is lower than 44 px', tooSmall(modal.querySelector('.modal-content')), []);
        }
    });

    // The guide (the owner, 2026-10-12): public, in the order of a party; the app's buttons as pictures, never controls.
    S2P.scenario({
        name: 'guide-page-phone',
        title: 'the guide "Jak działa Scan2Play" on a phone: the jumps whole on the screen and 44 px high, nothing wider than the screen, the pictures of buttons cannot be tapped or focused',
        page: 'guide-pl',
        viewport: '390,844',
        run: async function (t) {
            t.check('nothing wider than the screen', document.documentElement.scrollWidth <= document.documentElement.clientWidth);
            const jumps = Array.from(document.querySelectorAll('#guideJump a'));
            t.step('six jumps, each to its section', jumps.map(function (a) { return !!document.querySelector(a.getAttribute('href')); }),
                [true, true, true, true, true, true]);
            t.step('every jump whole on the screen', jumps.filter(function (a) {
                const box = a.getBoundingClientRect();
                return box.left < 0 || box.right > document.documentElement.clientWidth;
            }).map(function (a) { return a.textContent.trim(); }), []);
            t.step('the jumps and the way back at least 44 px high', tooSmall(document.getElementById('guideJump'))
                .concat(tooSmall(document.getElementById('guideBack').parentElement)), []);
            const pictures = Array.from(document.querySelectorAll('.s2p-guide-row:not(.s2p-guide-text) > :first-child, .s2p-guide-inline'));
            t.step('the pictures: spans, not focusable, not tappable', pictures.filter(function (el) {
                return el.tagName !== 'SPAN' || el.tabIndex >= 0 || getComputedStyle(el).pointerEvents !== 'none';
            }).length, 0);
            t.check('"↩ Przywróć" inside a sentence: as tall as its line, not a 44 px button',
                document.querySelector('.s2p-guide-inline').getBoundingClientRect().height < 32);
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
            t.check('nothing wider than the screen', document.documentElement.scrollWidth <= document.documentElement.clientWidth);
            // the rendered rows (the stand-in's poll replaces them in 3 s)
            const first = document.querySelector('#song-list tr[data-song-id]');
            const top = Math.round(first.getBoundingClientRect().top);
            t.check('the first request comes higher than the 382 px of four rows: at ' + top + ' px', top < 340);
        }
    });

    S2P.scenario({
        name: 'settings-more-rows',
        title: 'the unfolded settings: two cards ("Impreza", the QR code), then "Więcej" folded to one line; a tap shows a row with its state for each rare setting, leading to its page, and the tab remembers it',
        run: async function (t) {
            try { sessionStorage.removeItem('scan2play.settingsMoreOpen'); } catch (e) { /* none */ }
            document.getElementById('settingsToggle').click();
            const cards = Array.from(document.querySelectorAll('.s2p-settings section.card')).filter(shows).map(function (c) { return c.id; });
            t.step('two cards', cards, ['partyCard', 'qrCard']);
            const more = document.getElementById('settingsMoreFold');
            const line = more.querySelector('summary');
            t.step('"Więcej" folded to one line, its rows not shown', [more.open, line.textContent.trim(),
                document.querySelector('#settingsMore [data-settings-row]').checkVisibility()], [false, 'Więcej', false]);
            t.check('the line a thumb\'s target (' + Math.round(line.getBoundingClientRect().height) + ' px)', line.getBoundingClientRect().height >= 44);
            line.click();
            await t.sleep(50);
            t.step('a tap unfolds it, and the tab remembers', [more.open, sessionStorage.getItem('scan2play.settingsMoreOpen')], [true, '1']);
            const rows = Array.from(document.querySelectorAll('#settingsMore [data-settings-row]')).filter(shows);
            t.step('a row per rare setting, each to its card on the page "Ustawienia imprezy" (the staff: their own page)', rows.map(function (row) {
                return [row.dataset.settingsRow, row.getAttribute('href')];
            }), [['hosts', '/dj/settings?party=HARN1#hostListsCard'], ['links', '/dj/settings?party=HARN1#linksCard'],
                ['logo', '/dj/settings?party=HARN1#logoCard'], ['library', '/dj/settings?party=HARN1#libraryCard'], ['limits', '/dj/settings?party=HARN1#limitsCard'], ['staff', '/dj/staff?party=HARN1'], ['device', '/dj/settings?party=HARN1#deviceCard']]);
            const stateOf = function (row) {   // what shows of it (the row of the limits has its states near a limit hidden)
                const state = row.querySelector('.s2p-row-state');
                const parts = state.children.length ? Array.from(state.children).filter(shows) : [state];
                return parts.map(function (p) { return p.textContent.replace(/\s+/g, ' ').trim(); }).join(' ');
            };
            t.step('each says its state', rows.slice(0, 6).map(stateOf),
                ['🚫 0 · ⭐ 1 · link', 'Instagram · napiwki', 'Brak', 'Nie wgrano', '2 na gościa · 3 min', 'Osób: 1']);
            line.click();
            await t.sleep(50);
            t.step('folded again: forgotten', sessionStorage.getItem('scan2play.settingsMoreOpen'), null);
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

    // The heading and the tabs of every DJ's page (the review of 2026-10-28: the panel, the history, "Ustawienia imprezy", "Obsługa" and
    // the print had a top each, and three ways back). "Obsługa" is a page of the settings: their tab is lit, the way back to them is the
    // line above the heading.
    function oneHeading(t, tab) {
        const bar = document.getElementById('djTabBar');
        t.check('the tab bar and the heading row ("Zakończ imprezę", "Konto")',
            !!bar && !!document.getElementById('end-party-form') && !!document.getElementById('accountMenuBtn'));
        t.step('the tabs, each of the same party, and the lit one',
            Array.from(bar.querySelectorAll('[data-dj-tab]')).map(function (a) {
                return [a.getAttribute('data-dj-tab'), a.getAttribute('href'), a.classList.contains('active')];
            }),
            [['panel', '/dj/dashboard?party=HARN1', tab === 'panel'], ['history', '/dj/history-view?party=HARN1', tab === 'history'],
             ['settings', '/dj/settings?party=HARN1', tab === 'settings']]);
        t.step('no button back to the panel beside it', document.getElementById('backToPanel'), null);
    }

    S2P.scenario({
        name: 'staff-page-way-back',
        title: 'the owner\'s page "Obsługa" on a phone: the heading and the tabs of every DJ\'s page, "Ustawienia" lit, "← Ustawienia imprezy" above the heading, 44 px high',
        page: 'staff',
        viewport: '390,844',
        run: async function (t) {
            oneHeading(t, 'settings');
            const settings = document.getElementById('backToSettings');
            t.step('back to the settings of the same party', settings && settings.getAttribute('href'), '/dj/settings?party=HARN1');
            t.step('the heading, its tabs and the way back: nothing to tap lower than 44 px; nothing wider than the screen',
                [tooSmall(document.querySelector('.s2p-account-bar')).concat(tooSmall(document.getElementById('djTabBar')),
                    settings.getBoundingClientRect().height < 44 - 0.5 ? ['backToSettings'] : []),
                 document.documentElement.scrollWidth <= document.documentElement.clientWidth], [[], true]);
        }
    });

    S2P.scenario({
        name: 'settings-page-one-heading',
        title: 'the page "Ustawienia imprezy": the heading and the tabs of every DJ\'s page, "Ustawienia" lit, where the panel has them',
        page: 'settings',
        run: async function (t) {
            oneHeading(t, 'settings');
            const box = document.getElementById('djTabBar').getBoundingClientRect();
            // the panel's container: the bar as wide as the panel's (1280 px window: the container's 1140 less its padding)
            t.check('the bar as wide as the panel\'s (not the cards\' 800 px)', box.width > 1000);
            t.check('the cards stay 800 px', document.getElementById('settingsCards').getBoundingClientRect().width <= 800);
        }
    });

    S2P.scenario({
        name: 'history-page-one-heading',
        title: 'the history\'s own page: the heading and the tabs of every DJ\'s page, "Historia" lit, no second way back to the panel',
        page: 'history-page',
        run: async function (t) {
            oneHeading(t, 'history');
            t.step('no "Powrót do panelu DJ-a" under the list',
                Array.from(document.querySelectorAll('a.btn')).filter(function (a) { return /Powrót do panelu/.test(a.textContent); }).length, 0);
        }
    });

    // The panel's card "Impreza": its "Zapisz" the card's width on a phone, as the buttons of "Ustawienia imprezy" and of the QR
    // code's card beside it; as wide as its words on a computer
    function partySave() {
        document.getElementById('settingsToggle').click();
        return document.getElementById('partyWordsForm').querySelector('button[type="submit"]').getBoundingClientRect();
    }

    S2P.scenario({
        name: 'panel-party-save-phone',
        title: 'the panel\'s card "Impreza" on a phone: "Zapisz" the card\'s width, as the fields above it',
        viewport: '390,844',
        run: async function (t) {
            const save = partySave();
            t.step('"Zapisz" as wide as the field above it',
                Math.abs(save.width - document.getElementById('djNameInput').getBoundingClientRect().width) < 1, true);
        }
    });

    S2P.scenario({
        name: 'panel-party-save-wide',
        title: 'the panel\'s card "Impreza" on a computer: "Zapisz" as wide as its words',
        viewport: '1280,900',
        run: async function (t) {
            t.step('"Zapisz" much narrower than the fields', partySave().width < 150, true);
        }
    });

    // The review of 2026-10-28: in the card "Impreza" a label stood on the field above it — each form is a column of the row, and its
    // m-0 took the row's gap away (the gap is the column's margin-top)
    S2P.scenario({
        name: 'panel-party-card-spacing',
        title: 'the panel\'s card "Impreza": a row of fields keeps its gap from the row above it (no label standing on a field)',
        viewport: '1280,900',
        run: async function (t) {
            document.getElementById('settingsToggle').click();
            const bottom = function (id) { return document.getElementById(id).getBoundingClientRect().bottom; };
            const labelTop = function (id) { return document.querySelector('label[for="' + id + '"]').getBoundingClientRect().top; };
            t.step('"Dedykacje gości" at least 12 px under the vibe\'s field', labelTop('dedicationsSelect') - bottom('vibeSelect') >= 12, true);
            t.step('"Opis klimatu" at least 12 px under the dedications\' field', labelTop('vibeNoteInput') - bottom('dedicationsSelect') >= 12, true);
        }
    });

    // The review of 2026-10-28: one colour of ours for what is picked and what is done — the cyan of the action. The lit tab and the
    // line under the tables' head were the green of a status (left from Spotify).
    S2P.scenario({
        name: 'one-accent',
        title: 'the dashboard: the lit tab and the line under the queue\'s head in the action\'s cyan, not the green of a status',
        run: async function (t) {
            const action = getComputedStyle(document.querySelector('.btn-action')).backgroundColor;
            const tab = getComputedStyle(document.querySelector('[data-dj-tab].active'));
            const head = getComputedStyle(document.querySelector('#queueList thead th'));
            t.step('the action is the cyan', action, 'rgb(13, 202, 240)');
            t.step('the lit tab\'s words and line, the line under the head', [tab.color, tab.borderBottomColor, head.borderBottomColor],
                [action, action, action]);
        }
    });

    // The review of 2026-10-28: the panel's font was 'Segoe UI', Tahoma, Geneva, Verdana — Verdana on an iPhone, Geneva on a Mac, three
    // looks of one panel. The device's own font, as the TV and the print.
    S2P.scenario({
        name: 'one-font',
        title: 'the DJ\'s pages in the device\'s own font (system-ui), never a list that falls to Verdana',
        run: async function (t) {
            const font = getComputedStyle(document.body).fontFamily;
            t.step('the first font is the device\'s own', font.split(',')[0].trim(), 'system-ui');
            t.step('no Verdana, Tahoma or Geneva in the list', /Verdana|Tahoma|Geneva/.test(font), false);
        }
    });

    S2P.scenario({
        name: 'settings-page-touch-targets',
        title: 'the page "Ustawienia imprezy" on a phone: every folded line, and unfolded every button, field and the server limits\' line, at least 44 px high; no sideways scroll',
        page: 'settings',
        viewport: '390,844',
        run: async function (t) {
            t.step('folded: nothing to tap is lower than 44 px', tooSmall(document.body), []);
            t.check('folded: nothing wider than the screen', document.documentElement.scrollWidth <= document.documentElement.clientWidth);
            document.querySelectorAll('details.s2p-fold > summary').forEach(function (summary) { summary.click(); });
            t.step('every card unfolded: nothing to tap is lower than 44 px', tooSmall(document.body), []);
            t.check('unfolded: nothing wider than the screen', document.documentElement.scrollWidth <= document.documentElement.clientWidth);
            t.check('the Panel tab leads to the panel of the same party', document.querySelector('[data-dj-tab="panel"]').getAttribute('href') === '/dj/dashboard?party=HARN1');
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
    // The review of 2026-10-28: "Pierwszy raz? Zobacz, jak to działa" was Bootstrap's blue on the card, 3.6 : 1 — under the 4.5 : 1 a text
    // needs, and the only blue of the page. contrast() is the helper of dj-booking.js (the scenarios' scripts share the page).
    S2P.scenario({
        name: 'queue-empty-guide-readable',
        title: 'the empty queue: "Pierwszy raz? Zobacz, jak to działa" readable on its card (at least 4.5 : 1), in the action\'s cyan',
        setup: { queue: [] },
        run: async function (t) {
            const guide = document.getElementById('queueEmptyGuide');
            await t.waitFor(function () { return shows(guide); }, 'the empty queue', 8000);
            t.step('readable', contrast(guide) >= 4.5, true);
            t.step('the cyan of the action', getComputedStyle(guide).color, 'rgb(13, 202, 240)');
        }
    });
    // PROJECT_CONTEXT 6.7, one action per view (the owner, 2026-10-16): a closed party with nothing waiting showed two —
    // "Wznów imprezę" in the banner and "Drukuj plakat i karty" of the empty queue. The party's own action wins; "Drukuj" is an
    // outline while the party is closed, and the action again once it is resumed (the banner goes without a reload).
    S2P.scenario({
        name: 'closed-party-one-action',
        title: 'a closed party with an empty queue: one action button — "Wznów imprezę"; "Drukuj" an outline until the party is resumed',
        setup: { queue: [], partyActive: false },
        run: async function (t) {
            await t.waitFor(function () { return shows(document.getElementById('queueEmpty')); }, 'the empty queue', 8000);
            await t.waitFor(function () { return shows(document.getElementById('party-closed-banner')); }, 'the closed party', 8000);
            // a filled action: the cyan of --s2p-action
            const actions = Array.from(document.querySelectorAll('.btn-action')).filter(shows).filter(function (b) {
                return getComputedStyle(b).backgroundColor === 'rgb(13, 202, 240)';
            });
            t.step('one filled action: "Wznów imprezę"', actions.map(function (b) { return b.textContent.trim(); }), ['▶ Wznów imprezę']);
            t.step('"Drukuj" an outline: no fill, a light edge', (function () {
                const print = document.querySelector('#queueEmpty .btn-action');
                const style = getComputedStyle(print);
                return [style.backgroundColor, style.borderTopColor !== 'rgba(0, 0, 0, 0)'];
            })(), ['rgba(0, 0, 0, 0)', true]);
            await t.stand.config({ partyActive: true });   // resumed on another device: the next poll hides the banner
            await t.waitFor(function () { return !shows(document.getElementById('party-closed-banner')); }, 'the party resumed', 8000);
            await t.sleep(400);   // the button's colour transition (Bootstrap: 0.15 s)
            t.step('resumed: "Drukuj" the action again', getComputedStyle(document.querySelector('#queueEmpty .btn-action')).backgroundColor,
                'rgb(13, 202, 240)');
        }
    });
})();
