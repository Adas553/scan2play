// The review of 2026-10-14, what was left of "Formularze mówią prawdę": a History tab that would not load, a guest's 👍 and "↻
// Odśwież" that did not go through said nothing; a poll answered 200 without the queue (a Wi-Fi login page) put that page into the
// queue's table, and a poll refused for good (403) loaded the page again every 3 s.
(function () {
    const visible = function (el) { return !!el && el.checkVisibility(); };
    const notice = function () { return document.querySelector('[data-form-refused]'); };
    const noticeSays = function (kind) {
        return visible(notice()) && visible(notice().querySelector('[data-refused-text="' + kind + '"]'));
    };

    S2P.scenario({
        name: 'history-load-failure-says-so',
        title: 'the History tab, or its filter, that the server does not answer: the notice says it did not load; the queue stays; a good load takes the notice away',
        setup: { historyStatus: 500 },
        run: async function (t) {
            document.querySelector('[data-dj-tab="history"]').click();
            await t.waitFor(function () { return visible(notice()); }, 'the notice', 3000).catch(function () {});
            t.step('the notice says it did not load (not "not saved")', [noticeSays('load'), noticeSays('save')], [true, false]);
            t.step('the queue still shows', document.getElementById('queue-content').hidden, false);

            await t.stand.config({ historyStatus: null });
            document.querySelector('[data-dj-tab="history"]').click();
            const box = document.getElementById('history-content');
            await t.waitFor(function () { return !box.hidden && box.querySelector('[data-list]'); }, 'the history', 5000);
            t.step('loaded: the notice is gone', visible(notice()), false);

            await t.stand.config({ historyStatus: 500 });
            box.querySelector('[data-list-filter="played"]').click();
            await t.waitFor(function () { return visible(notice()); }, 'the notice', 3000).catch(function () {});
            t.step('a filter that did not load says so too', noticeSays('load'), true);
        }
    });

    const rowOf = function (name) {
        return Array.from(document.querySelectorAll('#guestQueueBox li[data-song-id]')).find(function (li) {
            return li.querySelector('.s2p-song-name').textContent.trim() === name;
        });
    };

    S2P.scenario({
        name: 'guest-vote-failure-says-so',
        title: 'the guest page: a 👍 the server refuses (the session ran out) says it did not go through, and can be tapped again',
        page: 'guest-many',
        viewport: '390,844',
        setup: { postStatus: { '/p/ABC12/vote': 403 } },
        run: async function (t) {
            const button = rowOf('Top song').querySelector('.s2p-vote-btn');
            const count = button.textContent.trim();
            button.click();
            await t.waitFor(function () { return document.getElementById('voteNote'); }, 'the note', 3000).catch(function () {});
            const note = document.getElementById('voteNote');
            t.step('a note says it did not go through', !!note && /Nie udało się/.test(note.textContent), true);
            t.step('the 👍 as it was, and it can be tapped again', [button.textContent.trim(), button.disabled, button.isConnected],
                [count, false, true]);
        }
    });

    S2P.scenario({
        name: 'guest-refresh-failure-says-so',
        title: 'the guest page: "↻ Odśwież" that does not get the list says so, the list stays, and the button works again',
        page: 'guest-many',
        viewport: '390,844',
        setup: { guestQueueStatus: 500 },
        run: async function (t) {
            const button = document.querySelector('[data-guest-queue-refresh]');
            button.click();
            await t.waitFor(function () { return document.getElementById('voteNote'); }, 'the note', 3000).catch(function () {});
            const note = document.getElementById('voteNote');
            t.step('a note says it did not go through', !!note && /Nie udało się/.test(note.textContent), true);
            t.step('the list stays, the button works again', [!!rowOf('Top song'), button.disabled], [true, false]);
        }
    });

    S2P.scenario({
        name: 'poll-without-the-list-keeps-the-queue',
        title: 'a poll answered 200 without the queue (a Wi-Fi login page): the queue stays as it was, and after a few such polls the bar says the list may be out of date',
        setup: { queue: [{ id: 1, name: 'Wilki - Baśka', url: 'https://www.youtube.com/results?search_query=Wilki' }] },
        run: async function (t) {
            await t.waitFor(function () { return document.querySelector('#song-list [data-song-id="1"]'); }, 'the queue poll', 8000);
            await t.stand.config({ updatesBody: '<html><body><h1>Zaloguj się do sieci Wi-Fi</h1></body></html>' });
            await t.waitFor(function () { return visible(document.querySelector('[data-poll-offline]')); }, 'the bar', 14000)
                .catch(function () {});
            t.step('the queue as it was, no login page in it', [!!document.querySelector('#song-list [data-song-id="1"]'),
                /Wi-Fi/.test(document.getElementById('song-list').textContent)], [true, false]);
            t.step('the bar says the list may be out of date', visible(document.querySelector('[data-poll-offline]')), true);
            await t.stand.config({ updatesBody: null });
            await t.waitFor(function () { return !visible(document.querySelector('[data-poll-offline]')); }, 'the bar gone', 8000)
                .catch(function () {});
            t.step('the real answer again: the bar is gone', visible(document.querySelector('[data-poll-offline]')), false);
        }
    });

    S2P.scenario({
        name: 'poll-refused-reloads-once',
        title: 'a poll refused for good (403): the page loads itself again once, not every 3 s — then the notice says the list did not load',
        setup: { queue: [{ id: 1, name: 'Wilki - Baśka', url: 'https://www.youtube.com/results?search_query=Wilki' }] },
        run: async function (t) {
            sessionStorage.clear();
            await t.waitFor(function () { return document.querySelector('#song-list [data-song-id="1"]'); }, 'the queue poll', 8000);
            let reloads = 0;
            // a reload would end the scenario: count it and stay (the Navigation API)
            navigation.addEventListener('navigate', function (e) {
                if (e.navigationType === 'reload') {
                    reloads++;
                    e.preventDefault();
                }
            });
            await t.stand.config({ updatesStatus: 403 });
            await t.sleep(10500);
            t.step('loaded again once, not once a poll', reloads, 1);
            t.step('the notice says the list did not load', noticeSays('load'), true);
            await t.stand.config({ updatesStatus: null });
            await t.sleep(3500);
            await t.stand.config({ updatesStatus: 403 });
            await t.sleep(3500);
            t.step('after a good poll, a new refusal may load the page again', reloads, 2);
        }
    });

    S2P.scenario({
        name: 'scroll-memory-without-storage',
        title: 'a browser that refuses its session storage (a private window, storage blocked): the page shows at once, no error — not hidden for 2 s (review 2026-10-14)',
        run: async function (t) {
            const src = document.querySelector('script[src*="scroll-restore"]').src;
            Object.defineProperty(window, 'sessionStorage', {
                configurable: true,
                get: function () { throw new DOMException('The operation is insecure.', 'SecurityError'); }
            });
            const errors = [];
            window.addEventListener('error', function (e) { errors.push(e.message); });
            const again = document.createElement('script');   // scroll-restore.js once more, as on a page load
            again.src = src;
            await new Promise(function (resolve) { again.onload = resolve; document.head.appendChild(again); });
            document.dispatchEvent(new Event('DOMContentLoaded'));
            await t.sleep(100);
            t.step('the page shown, no error', [document.documentElement.style.visibility, errors], ['', []]);
            window.dispatchEvent(new Event('beforeunload'));
            await t.sleep(50);
            t.step('leaving the page: no error either', errors, []);
        }
    });
})();
