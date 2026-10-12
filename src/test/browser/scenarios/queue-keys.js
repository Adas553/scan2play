// "KOPIUJ" AND THE QUEUE'S KEYS (queue-keys.js): the song to the clipboard as words for the DJ software's search ("Daft Punk One More
// Time", no " - "), and ↑ ↓ / C / Enter / S / Esc on the queue — for the DJ at the laptop. Enter / S press the request's own
// "Zagrane" / "Pomiń" once; Ctrl+C copies the chosen song (a selected text stays the browser's), Ctrl+Z is "Cofnij"; typing in the search box and the staff's "Podgląd" (no buttons) send nothing. The chosen request
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
        title: 'the queue\'s keys: ↓ ↑ choose, C copies the chosen song, Enter is its "Zagrane" (once), S its "Pomiń", Esc chooses none, Ctrl+C copies the chosen song unless a text is selected — and the search box and a key with nothing chosen send nothing',
        page: 'dashboard-cards',
        setup: { updatesStatus: 304 },
        run: async function (t) {
            const copied = catchClipboard();
            t.step('the keys named under the queue', document.getElementById('queueKeys').textContent.trim(),
                'Klawiatura: ↑ ↓ wybór · C lub Ctrl+C kopiuj · Enter zagrane · S pomiń · Backspace lub Ctrl+Z cofnij · Esc');
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
            const title = row(13).querySelector('.song-title');
            window.getSelection().selectAllChildren(title);
            t.step('Ctrl+C with a text selected: the browser\'s (not taken)', press('c', document.body, { ctrlKey: true }), false);
            await t.sleep(200);
            t.step('…nothing copied by the page', copied.length, 1);
            window.getSelection().removeAllRanges();
            t.step('Ctrl+C with nothing selected: taken', press('c', document.body, { ctrlKey: true }), true);
            await t.waitFor(function () { return copied.length === 2; }, 'the second copy', 2000);
            t.step('…the chosen song copied', copied, ['sanah Szampan', 'sanah Szampan']);
            t.step('Ctrl+Shift+C is the browser\'s', press('c', document.body, { ctrlKey: true, shiftKey: true }), false);

            const search = document.querySelector('#queueList [data-list-search]');
            search.focus();
            ['s', 'c', 'Enter'].forEach(function (key) { press(key, search); });
            await t.sleep(300);
            t.step('typing in the search box: nothing sent, nothing copied',
                [await t.stand.count(t.PLAY), await t.stand.count('POST /dj/dashboard/dismiss'), copied.length], [0, 0, 2]);
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
        name: 'queue-keys-click',
        title: 'on a computer a click on a request chooses it for the keys (its "Kopiuj" too), and Enter then plays that one',
        page: 'dashboard-cards',
        setup: { updatesStatus: 304 },
        run: async function (t) {
            catchClipboard();
            row(12).querySelector('.song-title').click();
            t.step('a click on the song chooses its request', chosen(), ['12']);
            row(13).querySelector('.s2p-btn-copy').click();
            t.step('"Kopiuj" of another chooses that one', chosen(), ['13']);
            press('ArrowUp');
            t.step('the keys go on from it', chosen(), ['12']);
            row(11).querySelector('td').click();
            press('Enter');
            await t.waitFor(function () { return !document.getElementById('undoSkip').hidden; }, '"Cofnij"', 3000);
            t.step('Enter: "Zagrane" of the clicked one', await ids(t, t.PLAY), ['11']);
        }
    });

    S2P.scenario({
        name: 'queue-keys-click-phone',
        title: 'on a phone a tap on a card unfolds it, and chooses nothing (no frame)',
        page: 'dashboard-cards',
        viewport: '390,844',
        setup: { updatesStatus: 304 },
        run: async function (t) {
            row(12).querySelector('.song-title').click();
            t.step('unfolded, not chosen', [row(12).classList.contains('s2p-open'), chosen()], [true, []]);
        }
    });

    S2P.scenario({
        name: 'queue-keys-undo',
        title: 'Backspace is "Cofnij" while its bar is shown — the request put back is chosen again; with no bar, or typing in the search box, it does nothing',
        setup: { queue: [{ id: 1, name: 'Wilki - Baśka', url: '' }, { id: 2, name: 'sanah - Szampan', url: '' }] },
        run: async function (t) {
            await t.waitFor(function () { return row(2) && !row(2).querySelector('.s2p-btn-played'); }, 'the queue poll', 8000);
            t.step('no bar: Backspace does nothing (and leaves the key to the page)', [press('Backspace'),
                await t.stand.count('POST /dj/dashboard/restore')], [false, 0]);

            // the stand-in's rows have no buttons: "Zagrane" of the first one as the real row's form sends it
            const form = document.createElement('form');
            form.method = 'post';
            form.action = '/dj/dashboard/play';
            form.innerHTML = '<input type="hidden" name="id" value="1"><button type="submit" class="s2p-btn-played">▶</button>';
            row(1).lastElementChild.appendChild(form);
            press('ArrowDown');
            press('Enter');
            await t.waitFor(function () { return !document.getElementById('undoSkip').hidden && !row(1); }, '"Cofnij" and the row gone', 8000);
            t.step('the next request chosen meanwhile', chosen(), ['2']);

            const search = document.querySelector('#queueList [data-list-search]');
            search.focus();
            press('Backspace', search);
            await t.sleep(300);
            t.step('Backspace in the search box is the box\'s', await t.stand.count('POST /dj/dashboard/restore'), 0);
            search.blur();

            t.step('Backspace with the bar shown: taken', press('Backspace'), true);
            await t.waitFor(function () { return row(1); }, 'the request back in the queue', 8000);
            t.step('"Cofnij" sent for it, the bar gone, the request put back chosen', [await ids(t, 'POST /dj/dashboard/restore'),
                document.getElementById('undoSkip').hidden, chosen()], [['1'], true, ['1']]);
        }
    });

    S2P.scenario({
        name: 'queue-keys-undo-ctrl-z',
        title: 'Ctrl+Z (⌘Z on a Mac) is "Cofnij" too, while its bar is shown; without the bar it is left to the page',
        setup: { queue: [{ id: 1, name: 'Wilki - Baśka', url: '' }, { id: 2, name: 'sanah - Szampan', url: '' }] },
        run: async function (t) {
            await t.waitFor(function () { return row(2) && !row(2).querySelector('.s2p-btn-played'); }, 'the queue poll', 8000);
            t.step('no bar: Ctrl+Z not taken', press('z', document.body, { ctrlKey: true }), false);
            const play = async function (id) {
                const form = document.createElement('form');
                form.method = 'post';
                form.action = '/dj/dashboard/play';
                form.innerHTML = '<input type="hidden" name="id" value="' + id + '"><button type="submit" class="s2p-btn-played">▶</button>';
                row(id).lastElementChild.appendChild(form);
                row(id).click();
                press('Enter');
                await t.waitFor(function () { return !document.getElementById('undoSkip').hidden && !row(id); }, '"Cofnij" after ' + id, 8000);
            };
            await play(1);
            t.step('Ctrl+Z with the bar shown: taken', press('z', document.body, { ctrlKey: true }), true);
            await t.waitFor(function () { return row(1); }, 'the request back', 8000);
            await play(2);
            t.step('⌘Z: taken', press('z', document.body, { metaKey: true }), true);
            await t.waitFor(function () { return row(2); }, 'the second request back', 8000);
            t.step('"Cofnij" sent for both', await ids(t, 'POST /dj/dashboard/restore'), ['1', '2']);
        }
    });

    S2P.scenario({
        name: 'queue-keys-scroll',
        title: 'a long queue and the keys: the chosen row is never under the sticky header, and the first / the last one takes the list\'s scrollbar to its very end (the owner, 2026-10-12: it stopped short)',
        setup: {
            queue: Array.from({ length: 30 }, function (_, i) {
                return { id: i + 1, name: 'Artysta ' + (i + 1) + ' - Piosenka', url: '', card: true };
            })
        },
        run: async function (t) {
            await t.waitFor(function () { return row(30); }, 'the queue poll', 8000);
            const box = document.querySelector('#queueList .list-scroll');
            const head = box.querySelector('thead');
            t.check('the list scrolls in its own box', box.scrollHeight > box.clientHeight + 100);
            const atEnd = function () { return Math.abs(box.scrollTop + box.clientHeight - box.scrollHeight) <= 1; };
            const clearOfHead = function (r) { return r.getBoundingClientRect().top >= head.getBoundingClientRect().bottom - 1; };

            for (let i = 0; i < 30; i++) press('ArrowDown');
            t.step('↓ to the last one: the scrollbar at its end', [chosen(), atEnd()], [['30'], true]);
            for (let i = 0; i < 15; i++) press('ArrowUp');
            t.step('↑ in the middle: the chosen row below the header', [chosen(), clearOfHead(row(15))], [['15'], true]);
            for (let i = 0; i < 15; i++) press('ArrowUp');
            t.step('↑ to the first one: the scrollbar at the top', [chosen(), box.scrollTop], [['1'], 0]);
        }
    });

    S2P.scenario({
        name: 'queue-keys-viewer',
        title: 'the staff\'s "Podgląd" (no buttons): the keys choose and copy, Enter and S send nothing, and the line under the queue names only those',
        page: 'dashboard-cards-viewer',
        setup: { updatesStatus: 304 },
        run: async function (t) {
            const copied = catchClipboard();
            t.step('the keys named', document.getElementById('queueKeys').textContent.trim(), 'Klawiatura: ↑ ↓ wybór · C lub Ctrl+C kopiuj · Esc');
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
