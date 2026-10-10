// THE DESIGN REVIEW (2026-10-09): what the shared look promises — the guest's form first, everything a thumb taps at least 44 px
// high on a phone, the account's menu over the tab bar, and an empty queue that says what to do next (the QR code, "Ustaw klimat").
(function () {
    const shows = function (el) { return !!el && el.getClientRects().length > 0 && getComputedStyle(el).visibility !== 'hidden'; };
    /** The visible things to tap inside {@code root} that are lower than 44 px (WCAG's target size), named for the report. */
    const tooSmall = function (root) {
        return Array.from(root.querySelectorAll('a.btn, button, input:not([type="hidden"]):not([type="checkbox"]), select, textarea, summary'))
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
        title: 'the dashboard: "Zakończ imprezę" in words, the rest under "Konto" — the open menu is over the sticky tab bar, not under it',
        viewport: '390,844',
        run: async function (t) {
            const end = document.querySelector('#end-party-form button');
            t.check('"Zakończ imprezę" says it in words on a phone too', shows(end) && end.textContent.trim().length > 3);
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
            // the owner's four (V32: "Obsługa imprezy" first — the staff's page), every one in reach
            t.step('every item of the menu can be tapped: nothing (the sticky tab bar) lies over one', [items.length, covered], [4, []]);
            t.step('every item is at least 44 px high', items.filter(function (i) { return i.getBoundingClientRect().height < 44 - 0.5; }).length, 0);
            const last = items[items.length - 1];
            t.check('the deletion of the account is the last one, in red', last && last.id === 'deleteAccountBtn'
                && getComputedStyle(last).color === 'rgb(255, 123, 123)');
            t.step('the page of the staff is the first one', items[0] && items[0].id, 'staffMenuLink');
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
