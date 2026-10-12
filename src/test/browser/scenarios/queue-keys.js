// "KOPIUJ" AND THE QUEUE'S KEYS (queue-keys.js): the song to the clipboard as words for the DJ software's search ("Daft Punk One More
// Time", no " - "), and ↑ ↓ / C / Enter / S / Esc on the queue — for the DJ at the laptop. Enter / S press the request's own
// "Zagrane" / "Pomiń" once; typing in the search box, Ctrl+C and the staff's "Podgląd" (no buttons) send nothing. The chosen request
// survives the poll that replaces the rows, and when it has left (played elsewhere) the one in its place is chosen.
(function () {
    const row = function (id) { return document.querySelector('#song-list tr[data-song-id="' + id + '"]'); };
    const chosen = function () {
        return Array.prototype.map.call(document.querySelectorAll('#song-list tr.s2p-chosen'), function (r) { return r.getAttribute('data-song-id'); });
    };
    const press = function (key, target, extra) {
        const event = new KeyboardEvent('keydown', Object.assign({ key: key, bubbles: true, cancelable: true }, extra || {}));
        (target || document.body).dispatchEvent(event);
        return event.defaultPrevented;
    };
    /** The clipboard as the page writes it: what the scripts put on it (headless Chrome has no clipboard to read back). */
    const catchClipboard = function () {
        const copied = [];
        Object.defineProperty(navigator, 'clipboard', {
            configurable: true,
            value: { writeText: function (text) { copied.push(text); return Promise.resolve(); } }
        });
        return copied;
    };
    const ids = async function (t, path) {
        return (await t.stand.requests(path)).map(function (r) { return String(r.q.id); });
    };

    S2P.scenario({
        name: 'queue-copy-button',
        title: '"Kopiuj" of a request puts its artist and title on the clipboard as words ("Daft Punk One More Time"), says "Skopiowano" for a moment and sends nothing',
        page: 'dashboard-cards',
        setup: { updatesStatus: 304 },   // the rendered rows stay: the poll answers "unchanged"
        run: async function (t) {
            const copied = catchClipboard();
            const button = row(11).querySelector('.s2p-btn-copy');
            t.step('"Kopiuj" beside "Podejrzyj" in every waiting request', [button.textContent.trim(),
                document.querySelectorAll('#song-list tr[data-song-id] .s2p-btn-copy').length], ['Kopiuj', 3]);
            button.click();
            await t.waitFor(function () { return button.textContent.trim() === 'Skopiowano'; }, '"Skopiowano"', 2000);
            t.step('the words of the song, no " - "', copied, ['Daft Punk One More Time']);
            t.step('"Skopiowano" for a moment', button.textContent.trim(), 'Skopiowano');
            await t.waitFor(function () { return button.textContent.trim() === 'Kopiuj'; }, '"Kopiuj" back', 3000);
            t.step('"Kopiuj" again after it', button.textContent.trim(), 'Kopiuj');
            t.step('nothing sent', (await t.stand.count('POST /dj/dashboard/play')) + (await t.stand.count('POST /dj/dashboard/dismiss')), 0);
        }
    });

    S2P.scenario({
        name: 'queue-keys',
        title: 'the queue\'s keys: ↓ ↑ choose, C copies the chosen song, Enter is its "Zagrane" (once), S its "Pomiń", Esc chooses none — and the search box, Ctrl+C and a key with nothing chosen send nothing',
        page: 'dashboard-cards',
        setup: { updatesStatus: 304 },
        run: async function (t) {
            const copied = catchClipboard();
            t.step('the keys named under the queue', document.getElementById('queueKeys').textContent.trim(),
                'Klawiatura: ↑ ↓ wybór · C kopiuj · Enter zagrane · S pomiń · Esc');
            t.step('nothing chosen at first', chosen(), []);

            press('Enter');
            await t.sleep(300);
            t.step('Enter with nothing chosen: the first request chosen, nothing sent', [chosen(), await t.stand.count(t.PLAY)], [['11'], 0]);
            press('Escape');
            t.step('Esc: none chosen', chosen(), []);

            t.step('↓ is the page\'s no more (it does not scroll it)', press('ArrowDown'), true);
            t.step('↓: the first request', chosen(), ['11']);
            press('ArrowDown');
            press('ArrowDown');
            press('ArrowDown');
            t.step('↓ ↓ ↓: the last one, and it stays there', chosen(), ['13']);
            press('ArrowUp');
            t.step('↑: the one before', [chosen(), row(12).getAttribute('aria-current')], [['12'], 'true']);

            press('c');
            await t.waitFor(function () { return row(12).querySelector('.s2p-btn-copy').textContent.trim() === 'Skopiowano'; }, '"Skopiowano"', 2000);
            t.step('C copies the chosen song', copied, ['sanah Szampan']);
            t.step('…and its "Kopiuj" says so', row(12).querySelector('.s2p-btn-copy').textContent.trim(), 'Skopiowano');
            press('c', document.body, { ctrlKey: true });
            await t.sleep(200);
            t.step('Ctrl+C is the browser\'s', copied.length, 1);

            const search = document.querySelector('#queueList [data-list-search]');
            search.focus();
            ['s', 'c', 'Enter'].forEach(function (key) { press(key, search); });
            await t.sleep(300);
            t.step('typing in the search box: nothing sent, nothing copied',
                [await t.stand.count(t.PLAY), await t.stand.count('POST /dj/dashboard/dismiss'), copied.length], [0, 0, 1]);
            search.blur();

            press('Enter');
            press('Enter');
            await t.waitFor(function () { return !document.getElementById('undoSkip').hidden; }, '"Cofnij"', 3000);
            t.step('Enter: "Zagrane" of the chosen request, once', await ids(t, t.PLAY), ['12']);

            press('ArrowDown');
            press('s');
            await t.sleep(500);
            t.step('S: "Pomiń" of the chosen request', await ids(t, 'POST /dj/dashboard/dismiss'), ['13']);
        }
    });

    S2P.scenario({
        name: 'queue-keys-follow-poll',
        title: 'the chosen request stays chosen when the poll replaces and reorders the rows; once it has left the queue (played on the phone), the request in its place is chosen',
        setup: { queue: [{ id: 1, name: 'Wilki - Baśka', url: '' }, { id: 2, name: 'sanah - Szampan', url: '' }, { id: 3, name: 'Dawid Podsiadło - Małomiasteczkowy', url: '' }] },
        run: async function (t) {
            await t.waitFor(function () { return row(3); }, 'the queue poll', 8000);
            press('ArrowDown');
            press('ArrowDown');
            t.step('the second request chosen', chosen(), ['2']);

            await t.stand.config({ queue: [{ id: 4, name: 'Kult - Arahja', url: '' }, { id: 1, name: 'Wilki - Baśka', url: '' }, { id: 2, name: 'sanah - Szampan', url: '' }, { id: 3, name: 'Dawid Podsiadło - Małomiasteczkowy', url: '' }] });
            await t.waitFor(function () { return row(4); }, 'the poll with a new request first', 8000);
            t.step('a new row above it: the same request still chosen', chosen(), ['2']);

            await t.stand.config({ queue: [{ id: 4, name: 'Kult - Arahja', url: '' }, { id: 1, name: 'Wilki - Baśka', url: '' }, { id: 3, name: 'Dawid Podsiadło - Małomiasteczkowy', url: '' }] });
            await t.waitFor(function () { return !row(2); }, 'the poll without it', 8000);
            t.step('it left the queue: the one in its place chosen (the next)', chosen(), ['3']);
        }
    });

    S2P.scenario({
        name: 'queue-keys-viewer',
        title: 'the staff\'s "Podgląd" (no buttons): the keys choose and copy, Enter and S send nothing, and the line under the queue names only those',
        page: 'dashboard-cards-viewer',
        setup: { updatesStatus: 304 },
        run: async function (t) {
            const copied = catchClipboard();
            t.step('the keys named', document.getElementById('queueKeys').textContent.trim(), 'Klawiatura: ↑ ↓ wybór · C kopiuj · Esc');
            press('ArrowDown');
            press('Enter');
            press('s');
            press('c');
            await t.waitFor(function () { return copied.length > 0; }, 'the copy', 2000);
            t.step('chosen and copied', [chosen(), copied], [['11'], ['Daft Punk One More Time']]);
            t.step('nothing sent', [await t.stand.count(t.PLAY), await t.stand.count('POST /dj/dashboard/dismiss')], [0, 0]);
        }
    });
})();
