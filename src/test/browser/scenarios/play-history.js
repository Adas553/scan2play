// "ZAGRANE SAMO" (play-history.js; docs/ideas.md item 18, variant E): the panel reads VirtualDJ's or Serato's play history in a folder
// the DJ picks and sends each new song to POST /dj/dashboard/plays, the server marks the request played. The browser's folder picker
// cannot be clicked in a headless browser, so window.showDirectoryPicker is replaced by one that gives a folder made here — handles
// with the few methods the script uses (values, getDirectoryHandle, getFile; the file's text, bytes and lastModified).
(function () {
    const params = new URLSearchParams(location.search);
    // A browser that cannot read a folder (Firefox, Safari): taken away before the page's modules run (they run after this file).
    if (params.get('scenario') === 'deck-reader-hidden') delete window.showDirectoryPicker;

    const now = function () { return Math.floor(Date.now() / 1000); };
    const toggle = function () { return document.getElementById('deckToggle'); };
    const reader = function () { return document.getElementById('deckReader'); };
    const status = function () { return document.getElementById('deckStatus').textContent; };
    const PLAYS = 'POST /dj/dashboard/plays';

    /** t.waitFor takes a condition that answers at once; the stand-in's log is fetched: wait for `count` calls of PLAYS here. */
    async function waitForPlays(t, count, label, timeoutMs) {
        const until = Date.now() + timeoutMs;
        while (Date.now() < until) {
            if ((await t.stand.count(PLAYS)) >= count) return;
            await t.sleep(250);
        }
        t.check('TIMEOUT waiting for ' + label, false);
        throw new Error('timeout: ' + label);
    }

    /** A file of the made-up folder: its contents can grow, as a history file does while the set plays. */
    function fakeFile(name, contents) {
        const file = { kind: 'file', name: name, contents: contents, lastModified: Date.now() };
        file.getFile = async function () { return new File([file.contents], name, { lastModified: file.lastModified }); };
        file.grow = function (more) {
            file.contents = typeof more === 'string' ? file.contents + more : concat(file.contents, more);
            file.lastModified += 1000;
        };
        return file;
    }

    function fakeFolder(name, entries) {
        return {
            kind: 'directory', name: name,
            values: async function* () { for (const entry of entries) yield entry; },
            getDirectoryHandle: async function (child) {
                const found = entries.find(function (e) { return e.kind === 'directory' && e.name === child; });
                if (!found) throw new DOMException('no ' + child, 'NotFoundError');
                return found;
            },
            queryPermission: async function () { return 'granted'; },
            requestPermission: async function () { return 'granted'; }
        };
    }

    function concat(a, b) {
        const all = new Uint8Array(a.byteLength + b.byteLength);
        all.set(new Uint8Array(a), 0);
        all.set(new Uint8Array(b), a.byteLength);
        return all.buffer;
    }

    function vdjLine(at, artist, title) {
        return '#EXTVDJ:<time>21:39</time><lastplaytime>' + at + '</lastplaytime><artist>' + artist + '</artist><title>' + title
            + '</title>\r\nC:\\Muzyka\\song.mp3\r\n';
    }

    // ---- Serato's binary session file, as SSLScrobbler and What's Now Playing describe it ----
    function utf16(text) {
        const bytes = new Uint8Array(text.length * 2);
        for (let i = 0; i < text.length; i++) { bytes[2 * i] = text.charCodeAt(i) >> 8; bytes[2 * i + 1] = text.charCodeAt(i) & 255; }
        return bytes;
    }
    function chunk(name, body) {
        const out = new Uint8Array(8 + body.length);
        for (let i = 0; i < 4; i++) out[i] = name.charCodeAt(i);
        new DataView(out.buffer).setUint32(4, body.length);
        out.set(body, 8);
        return out;
    }
    function field(id, body) {
        const out = new Uint8Array(8 + body.length);
        new DataView(out.buffer).setUint32(0, id);
        new DataView(out.buffer).setUint32(4, body.length);
        out.set(body, 8);
        return out;
    }
    function u32(n) { const b = new Uint8Array(4); new DataView(b.buffer).setUint32(0, n); return b; }
    function join(parts) {
        const all = new Uint8Array(parts.reduce(function (n, p) { return n + p.length; }, 0));
        let at = 0;
        parts.forEach(function (p) { all.set(p, at); at += p.length; });
        return all;
    }
    function seratoSong(at, artist, title, played) {
        return chunk('oent', chunk('adat', join([field(6, utf16(title)), field(7, utf16(artist)), field(28, u32(at)), field(31, u32(1)),
            field(50, new Uint8Array([played ? 1 : 0]))])));
    }

    S2P.scenario({
        name: 'deck-reader-virtualdj',
        title: '"Zagrane samo": the VirtualDJ folder picked, the earlier set is not sent, a song that plays now is sent once (artist, title, time, CSRF) and the marked request is said',
        setup: { queue: [{ id: 1, name: 'Sanah - Szampan', url: '' }], deckMarked: ['Sanah - Szampan'] },
        run: async function (t) {
            const today = fakeFile('2026-11-21.m3u', vdjLine(now() - 7200, 'Wcześniej', 'Grane dwie godziny temu'));
            const older = fakeFile('2026-11-20.m3u', vdjLine(now() - 90000, 'Wczoraj', 'Inny dzień'));
            older.lastModified -= 86400000;
            const virtualDj = fakeFolder('VirtualDJ', [fakeFolder('History', [older, today]), fakeFolder('Playlists', [])]);
            window.showDirectoryPicker = async function () { return virtualDj; };
            await t.sleep(300);

            t.step('the button shown in a browser that reads folders, the card folded', [toggle().hidden, reader().hidden], [false, true]);
            toggle().click();
            t.step('a tap unfolds the card', [reader().hidden, toggle().getAttribute('aria-expanded')], [false, 'true']);

            document.getElementById('deckPick').click();
            await t.waitFor(function () { return status().indexOf('VirtualDJ') >= 0; }, 'reading VirtualDJ', 4000);
            t.check('the newest file of the History folder under the one picked is read', status().indexOf('2026-11-21.m3u') >= 0);
            t.check('"Wyłącz" shown while reading', !document.getElementById('deckStop').hidden);
            await t.sleep(600);
            t.step('nothing of the earlier set sent', await t.stand.count(PLAYS), 0);

            today.grow(vdjLine(now(), 'Sanah', 'Szampan &amp; więcej'));
            await waitForPlays(t, 1, 'the new song sent', 8000);
            const sent = (await t.stand.requests(PLAYS))[0].q;
            t.step('sent: the party, the song as the file writes it, its time', [sent.partyCode, sent.plays.length, sent.plays[0].artist,
                sent.plays[0].title, Math.abs(sent.plays[0].at - now()) < 30], [document.getElementById('partyCode').value, 1, 'Sanah',
                'Szampan & więcej', true]);
            t.check('as JSON, with the CSRF token', /application\/json/.test(sent['Content-Type']) && !!sent['X-CSRF-TOKEN']);
            t.check('with this computer\'s clock (sentAt), so the server corrects a clock that is off', Math.abs(sent.sentAt - now()) < 30);
            await t.waitFor(function () { return document.getElementById('deckMarked').textContent.indexOf('Sanah - Szampan') >= 0; },
                'the marked request said', 3000);

            await t.sleep(5600);
            t.step('a song is sent once, though the file is read again', await t.stand.count(PLAYS), 1);

            document.getElementById('deckStop').click();
            today.grow(vdjLine(now(), 'Dawid Podsiadło', 'Małomiasteczkowy'));
            await t.sleep(5600);
            t.step('"Wyłącz": nothing read or sent any more', [await t.stand.count(PLAYS), document.getElementById('deckStop').hidden, status()],
                [1, true, '']);
        }
    });

    S2P.scenario({
        name: 'deck-reader-serato',
        title: '"Zagrane samo": _Serato_ picked, the newest session under History/Sessions read; a song only loaded on a deck is not sent',
        setup: { queue: [], deckMarked: [] },
        run: async function (t) {
            const session = fakeFile('12.session', join([chunk('vrsn', utf16('1.0/Serato Scratch LIVE Review'))]).buffer);
            const serato = fakeFolder('_Serato_', [fakeFolder('History', [fakeFolder('Sessions', [session])]), fakeFolder('Crates', [])]);
            window.showDirectoryPicker = async function () { return serato; };
            await t.sleep(300);
            toggle().click();
            document.getElementById('deckPick').click();
            await t.waitFor(function () { return status().indexOf('Serato') >= 0; }, 'reading Serato', 4000);

            session.grow(join([seratoSong(now(), 'Preview', 'Tylko załadowana', false), seratoSong(now(), 'Zenon Martyniuk', 'Przekorny los', true)]).buffer);
            await waitForPlays(t, 1, 'the played song sent', 8000);
            const plays = (await t.stand.requests(PLAYS))[0].q.plays;
            t.step('only the played song, its UTF-16 text read', plays.map(function (p) { return p.artist + ' - ' + p.title; }),
                ['Zenon Martyniuk - Przekorny los']);
        }
    });

    S2P.scenario({
        name: 'deck-reader-wrong-folder',
        title: '"Zagrane samo": a folder with no history says so and reads nothing',
        setup: { queue: [] },
        run: async function (t) {
            window.showDirectoryPicker = async function () { return fakeFolder('Pobrane', [fakeFile('notatki.txt', 'x')]); };
            await t.sleep(300);
            toggle().click();
            document.getElementById('deckPick').click();
            await t.waitFor(function () { return status().length > 0; }, 'an answer', 3000);
            t.step('the reason said, no "Wyłącz"', [status() === reader().dataset.none, document.getElementById('deckStop').hidden], [true, true]);
        }
    });

    S2P.scenario({
        name: 'deck-reader-hidden',
        title: '"Zagrane samo": a browser that cannot read a folder offers the program on the computer only',
        run: async function (t) {
            await t.sleep(500);
            toggle().click();
            t.step('the program\'s part shown, not the browser\'s', [typeof window.showDirectoryPicker, reader().hidden,
                document.getElementById('deviceBlock').hidden, document.getElementById('deckBrowser').hidden], ['undefined', false, false, true]);
        }
    });

    S2P.scenario({
        name: 'deck-computers',
        title: '"Program na komputerze" (V78): the card lists the paired computers, "Połącz komputer" shows the code until the program takes it, "Odłącz" forgets one',
        setup: { devices: [] },
        run: async function (t) {
            const list = function () { return document.getElementById('deviceList').textContent; };
            const code = function () { return document.getElementById('deviceCode').textContent; };
            await t.sleep(300);
            toggle().click();
            await t.waitFor(function () { return list().length > 0; }, 'the list', 3000);
            t.step('none yet', list(), reader().dataset.deviceNone);

            document.getElementById('deviceConnect').click();
            await t.waitFor(function () { return code().indexOf('123 456') >= 0; }, 'the code', 3000);
            const asked = (await t.stand.requests('POST /dj/devices/code'))[0].q;
            t.step('the code asked for the party, with the CSRF token', [asked.partyCode, !!asked['X-CSRF-TOKEN']],
                [document.getElementById('partyCode').value, true]);

            await t.stand.config({ devices: [{ id: 5, name: 'Laptop DJ-a — Windows', createdAt: new Date().toISOString(),
                lastSeenAt: new Date().toISOString() }] });
            await t.waitFor(function () { return list().indexOf('Laptop DJ-a') >= 0; }, 'the paired computer', 8000);
            t.step('the code gone once the program took it', code(), '');

            document.querySelector('#deviceList [data-device-id="5"] button').click();
            await t.waitFor(function () { return list() === reader().dataset.deviceNone; }, 'the list without it', 3000);
            const removed = (await t.stand.requests('POST /dj/devices/remove'))[0].q;
            t.step('"Odłącz" names the computer and the party', [removed.id, removed.partyCode, !!removed['X-CSRF-TOKEN']],
                ['5', document.getElementById('partyCode').value, true]);
        }
    });

    S2P.scenario({
        name: 'deck-reader-bad-entity',
        title: '"Zagrane samo": a character reference to no character in a line (&#x110000;) does not stop the file being read: the next song is still sent',
        setup: { queue: [], deckMarked: [] },
        run: async function (t) {
            const today = fakeFile('2026-11-21.m3u', vdjLine(now() - 7200, 'Wcześniej', 'Grane dwie godziny temu'));
            const virtualDj = fakeFolder('VirtualDJ', [fakeFolder('History', [today])]);
            window.showDirectoryPicker = async function () { return virtualDj; };
            await t.sleep(300);
            toggle().click();
            document.getElementById('deckPick').click();
            await t.waitFor(function () { return status().indexOf('VirtualDJ') >= 0; }, 'reading VirtualDJ', 4000);
            today.grow(vdjLine(now(), 'Sanah', 'Zepsuty &#x110000; wpis'));
            today.grow(vdjLine(now(), 'Dawid Podsiadło', 'Małomiasteczkowy'));
            await waitForPlays(t, 1, 'the songs sent', 8000);
            const sent = (await t.stand.requests(PLAYS))[0].q;
            t.step('both songs read, the bad reference left as written', sent.plays.map(function (song) { return song.title; }),
                ['Zepsuty &#x110000; wpis', 'Małomiasteczkowy']);
        }
    });

    S2P.scenario({
        name: 'deck-computers-code-kept',
        title: '"Połącz komputer": the code stays while another computer is disconnected, and goes when a program took it even with the list as long as before (3 computers: the fourth replaces the oldest)',
        setup: { devices: [] },
        run: async function (t) {
            const code = function () { return document.getElementById('deviceCode').textContent; };
            const at = new Date().toISOString();
            const device = function (id) { return { id: id, name: 'Komputer ' + id, createdAt: at, lastSeenAt: at }; };
            await t.stand.config({ devices: [device(5), device(6), device(7)] });
            await t.sleep(300);
            toggle().click();
            await t.waitFor(function () { return document.querySelectorAll('#deviceList [data-device-id]').length === 3; }, 'three computers', 3000);
            t.step('a "Odłącz" names its computer for a screen reader',
                document.querySelector('#deviceList [data-device-id="5"] button').getAttribute('aria-label'), 'Odłącz Komputer 5');

            document.getElementById('deviceConnect').click();
            await t.waitFor(function () { return code().indexOf('123 456') >= 0; }, 'the code', 3000);

            await t.stand.config({ devices: [device(6), device(7)] });                  // one disconnected: the code is still good
            await t.waitFor(function () { return document.querySelectorAll('#deviceList [data-device-id]').length === 2; }, 'two computers', 8000);
            t.check('the code stays after another computer was disconnected', code().indexOf('123 456') >= 0);

            await t.stand.config({ devices: [device(6), device(7), device(8)] });       // a program took it
            await t.waitFor(function () { return code() === ''; }, 'the code gone', 8000);

            document.getElementById('deviceConnect').click();
            await t.waitFor(function () { return code().indexOf('123 456') >= 0; }, 'a second code', 3000);
            await t.stand.config({ devices: [device(7), device(8), device(9)] });       // the list as long as before: the oldest replaced
            await t.waitFor(function () { return code() === ''; }, 'the code gone, the list as long as before', 8000);
        }
    });
})();
