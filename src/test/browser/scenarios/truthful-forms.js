// The review of 2026-10-14, "Formularze mówią prawdę": a form sent in the background said ✓ when the server refused it (W1), a
// double tap on 💸 counted two tips (W11), a failed poll left the queue looking current (W12), and the standalone history page lost
// the party it showed when a filter or "Pokaż więcej" asked for the list again (W7).
(function () {
    const posts = function (t, path) { return t.stand.count('POST ' + path); };
    const waitForPosts = async function (t, path, n, label) {
        const started = performance.now();
        while ((await posts(t, path)) < n) {
            if (performance.now() - started > 5000) {
                t.step('TIMEOUT waiting for ' + label, false, true);
                throw new Error('timeout: ' + label);
            }
            await t.sleep(50);
        }
    };
    const visible = function (el) { return !!el && el.checkVisibility(); };

    // W1: the hosts' lists saved after the session ran out (a 403 for the CSRF token) — the DJ saw ✓ and lost the lists
    S2P.scenario({
        name: 'refused-form-shows-error',
        page: 'settings',
        title: 'a form the server refuses (403: the session ran out) says ✗ and why, keeps what was written, and offers to reload',
        setup: { postStatus: { '/dj/dashboard/host-lists': 403 } },
        run: async function (t) {
            const form = document.getElementById('hostListsForm');
            const button = form.querySelector('button[type="submit"]');
            const notice = document.querySelector('[data-form-refused]');
            t.step('at first no notice', visible(notice), false);
            document.getElementById('hostWantedInput').value = 'Hej sokoły';
            button.click();
            await waitForPosts(t, '/dj/dashboard/host-lists', 1, 'the lists sent');
            await t.waitFor(function () { return button.textContent !== 'Zapisz' && button.textContent !== 'Save'; }, 'the button\'s answer', 2000)
                .catch(function () {});
            t.step('the button says ✗, not ✓', [button.textContent, button.classList.contains('btn-danger')], ['✗', true]);
            t.step('a notice says it was not saved, with a way to reload', [visible(notice),
                visible(notice && notice.querySelector('[data-form-refused-reload]'))], [true, true]);
            t.step('what was written stays in the field', document.getElementById('hostWantedInput').value, 'Hej sokoły');

            await t.stand.config({ postStatus: { '/dj/dashboard/host-lists': null } });
            await t.sleep(1700);
            button.click();
            await waitForPosts(t, '/dj/dashboard/host-lists', 2, 'the lists sent again');
            await t.waitFor(function () { return button.textContent === '✓'; }, 'the ✓', 2000).catch(function () {});
            t.step('saved: ✓ and the notice gone', [button.textContent, visible(notice)], ['✓', false]);
        }
    });

    S2P.scenario({
        name: 'refused-end-party-keeps-the-panel',
        title: '"Zakończ imprezę" refused by the server: the panel does not show the party closed, the notice says it was not done',
        setup: { postStatus: { '/dj/end-party': 403 } },
        run: async function (t) {
            const banner = document.getElementById('party-closed-banner');
            const endForm = document.getElementById('end-party-form');
            const shown = function (el) { return !el.classList.contains('d-none'); };
            window.confirm = function () { return true; };
            endForm.querySelector('button[type="submit"]').click();
            await waitForPosts(t, '/dj/end-party', 1, 'the end of the party sent');
            await t.waitFor(function () { return visible(document.querySelector('[data-form-refused]')); }, 'the notice', 2000)
                .catch(function () {});
            t.step('still open: no banner, "Zakończ" shown', [shown(banner), shown(endForm)], [false, true]);
            t.step('the notice says it was not done', visible(document.querySelector('[data-form-refused]')), true);
            const button = endForm.querySelector('button[type="submit"]');
            t.step('the button says ✗', button.textContent, '✗');
            await t.sleep(1700);
            t.step('…then its own label again, the long and the short one', button.querySelectorAll('span').length, 2);
        }
    });

    // "Cofnij" after "Pomiń" refused (the session ran out): it said nothing, and the DJ thought the song was back in the queue
    S2P.scenario({
        name: 'refused-undo-says-so',
        title: '"Cofnij" refused by the server: the notice says it was not done (the song is not back)',
        setup: { queue: [{ id: 1, name: 'Wilki - Baśka', url: 'https://www.youtube.com/results?search_query=Wilki' }],
                 postStatus: { '/dj/dashboard/restore': 403 } },
        run: async function (t) {
            const bar = document.getElementById('undoSkip');
            // the rendered row has the buttons (the stand-in's polled rows do not): click before the first poll replaces it
            document.querySelector('#song-list tr[data-song-id="1"] form[action="/dj/dashboard/dismiss"] button').click();
            await t.waitFor(function () { return !bar.hidden; }, '"Cofnij"', 2500);
            bar.querySelector('[data-undo-button]').click();
            await waitForPosts(t, '/dj/dashboard/restore', 1, 'the restore sent');
            await t.waitFor(function () { return visible(document.querySelector('[data-form-refused]')); }, 'the notice', 2000)
                .catch(function () {});
            t.step('the notice says it was not done', visible(document.querySelector('[data-form-refused]')), true);
        }
    });

    // W11: a payment came in, the DJ taps 💸 twice in a hurry (or the phone sends the tap twice) — one tip, not two
    S2P.scenario({
        name: 'tip-double-tap-counts-once',
        title: 'a double tap on 💸 sends one tip: the button waits for the answer before it can be pressed again',
        setup: { queue: [{ id: 1, name: 'Wilki - Baśka', url: 'https://www.youtube.com/results?search_query=Wilki', number: 27 }],
                 delays: { '/dj/dashboard/tip-count': 1 } },
        run: async function (t) {
            await t.waitFor(function () { return document.querySelector('#song-list form[action="/dj/dashboard/tip-count"] button'); },
                'the queue poll', 8000);
            const button = document.querySelector('#song-list form[action="/dj/dashboard/tip-count"] button');
            button.click();
            button.click();
            await t.sleep(1600);
            t.step('one tip sent', await posts(t, '/dj/dashboard/tip-count'), 1);
        }
    });

    // W12: the DJ's Wi-Fi goes for a few minutes — the queue must not look current while it is not
    S2P.scenario({
        name: 'poll-failure-shows-offline',
        title: 'after a few failed polls a bar says the list may be out of date; the next good poll takes it away',
        setup: { queue: [{ id: 1, name: 'Wilki - Baśka', url: 'https://www.youtube.com/results?search_query=Wilki' }] },
        run: async function (t) {
            await t.waitFor(function () { return document.querySelector('#song-list [data-song-id="1"]'); }, 'the queue poll', 8000);
            const bar = document.querySelector('[data-poll-offline]');
            t.step('at first no bar', visible(bar), false);
            await t.stand.config({ updatesStatus: 500 });
            await t.sleep(4000);
            t.step('one failed poll: no bar yet (a single hiccup)', visible(bar), false);
            await t.waitFor(function () { return visible(document.querySelector('[data-poll-offline]')); }, 'the bar', 12000)
                .catch(function () {});
            t.step('failed polls: the bar shows', visible(document.querySelector('[data-poll-offline]')), true);
            await t.stand.config({ updatesStatus: null });
            await t.waitFor(function () { return !visible(document.querySelector('[data-poll-offline]')); }, 'the bar gone', 8000)
                .catch(function () {});
            t.step('a good poll: the bar is gone', visible(document.querySelector('[data-poll-offline]')), false);
        }
    });

    // W7: the standalone history page of a party chosen in the panel switcher ("?party=") — a filter or "Pokaż więcej" asked for
    // the list without it, and the server showed the person's default party under this one's heading
    S2P.scenario({
        name: 'history-page-keeps-the-party',
        page: 'history-page',
        title: 'the standalone history page: a filter and "Pokaż więcej" ask for the list of the same party',
        run: async function (t) {
            const asked = [];
            // the page loads itself again for both: catch where it would go, and stay (the Navigation API)
            navigation.addEventListener('navigate', function (e) {
                asked.push(new URL(e.destination.url).search);
                e.preventDefault();
            });
            document.querySelector('[data-list-filter="played"]').click();
            const more = document.querySelector('[data-history-more]');
            if (more) more.click();
            await t.sleep(200);
            t.step('both asked', asked.length, 2);
            t.step('each with the party', asked.map(function (q) { return new URLSearchParams(q).get('party'); }), ['HARN1', 'HARN1']);
            t.step('the filter and the limit kept', [new URLSearchParams(asked[0]).get('filter'), new URLSearchParams(asked[1] || '').get('limit')],
                ['played', '100']);
        }
    });
})();
