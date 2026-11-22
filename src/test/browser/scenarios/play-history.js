// "ZAGRANE SAMO" (play-history.js; docs/ideas.md item 19): the card of the queue pairs the DJ's program on the computer, which reads
// VirtualDJ's or Serato's play history and sends the songs itself (/api/device/plays). The panel reads no folder itself any more
// (variant E of item 18, parked): one way only. Without a computer the card shows three steps (download, start, connect); with one,
// only its row — and the queue's button turns green as soon as the page loads, so the DJ sees it works without opening the card.
(function () {
    const toggle = function () { return document.getElementById('deckToggle'); };
    const reader = function () { return document.getElementById('deckReader'); };
    const setup = function () { return document.getElementById('deviceSetup'); };
    const another = function () { return document.getElementById('deviceAnother'); };
    const list = function () { return document.getElementById('deviceList'); };
    const code = function () { return document.getElementById('deviceCode').textContent; };
    const rows = function () { return document.querySelectorAll('#deviceList [data-device-id]').length; };
    const green = function () { return toggle().classList.contains('btn-outline-success'); };
    const loaded = function () { return list().dataset.loaded === 'true'; };
    const at = new Date().toISOString();
    const device = function (id, name) { return { id: id, name: name || 'Komputer ' + id, createdAt: at, lastSeenAt: at }; };

    S2P.scenario({
        name: 'deck-reader-program-only',
        title: '"Zagrane samo": in Chrome, which could read a folder, the card offers the program on the computer only — no folder to pick, nothing sent by the panel, nothing remembered',
        setup: { devices: [] },
        run: async function (t) {
            await t.waitFor(loaded, 'the list of computers asked when the page loaded, before the card is opened', 3000);
            t.step('the button shown on a computer, not green (no computer), the card folded', [toggle().hidden, green(), reader().hidden],
                [false, false, true]);
            toggle().click();
            t.step('a tap unfolds the card: the three steps, nothing else to choose', [reader().hidden, toggle().getAttribute('aria-expanded'),
                setup().hidden, setup().querySelectorAll('ol > li').length, another().hidden], [false, 'true', false, 3, true]);
            t.step('the downloads are buttons, as the rest of the panel', Array.from(setup().querySelectorAll('a[href*="releases/download"]'))
                .map(function (a) { return a.classList.contains('btn'); }), [true, true, true]);
            t.step('no reading in this browser, though it has the folder picker', [typeof window.showDirectoryPicker,
                !!document.getElementById('deckPick'), !!document.getElementById('deckBrowser')], ['function', false, false]);
            const databases = typeof indexedDB.databases === 'function' ? await indexedDB.databases() : [];
            t.step('no folder remembered by the panel', databases.some(function (d) { return d.name === 's2p-deck'; }), false);
            t.step('the panel sends no songs itself', await t.stand.count('POST /dj/dashboard/plays'), 0);
        }
    });

    S2P.scenario({
        name: 'deck-connected-on-load',
        title: '"Zagrane samo": a computer paired meanwhile (the program\'s window, another tab) — back on the panel the queue\'s button is green without opening the card; the card shows the computer\'s row and no steps; "Połącz kolejny komputer" brings them back',
        setup: { devices: [] },
        run: async function (t) {
            await t.waitFor(loaded, 'the list asked when the page loaded', 3000);
            t.check('not green: no computer yet', !green());
            await t.stand.config({ devices: [device(5, 'DESKTOP-1AB2 — Windows')] });
            document.dispatchEvent(new Event('visibilitychange'));   // the page shown again
            await t.waitFor(green, 'the button green without opening the card', 3000);
            t.check('the card still folded', reader().hidden);
            toggle().click();
            t.step('the computer\'s row, no steps', [rows(), list().textContent.indexOf('DESKTOP-1AB2') >= 0, setup().hidden, another().hidden],
                [1, true, true, false]);
            another().click();
            t.step('"Połącz kolejny komputer": the steps again, the link gone', [setup().hidden, another().hidden], [false, true]);
        }
    });

    S2P.scenario({
        name: 'deck-computers',
        title: '"Program na komputerze" (V78): "Połącz komputer" shows the code until the program takes it, then the card is the computer\'s row and the button green; "Odłącz" the last one: the steps again',
        setup: { devices: [] },
        run: async function (t) {
            await t.waitFor(loaded, 'the list', 3000);
            toggle().click();
            t.step('none yet: the steps, no rows', [rows(), setup().hidden], [0, false]);

            document.getElementById('deviceConnect').click();
            await t.waitFor(function () { return code().indexOf('123 456') >= 0; }, 'the code', 3000);
            const copy = document.querySelector('#deviceCode button[data-copy-text]');
            t.step('the code bold, a "Kopiuj" beside it as the party link\'s (the digits alone, the program takes them so)',
                [!!document.querySelector('#deviceCode .s2p-device-code'), !!copy && copy.classList.contains('btn-outline-secondary'),
                    copy && copy.getAttribute('data-copy-text'), copy && copy.textContent.trim()], [true, true, '123456', 'Kopiuj']);
            const written = [];
            Object.defineProperty(navigator, 'clipboard', { configurable: true, value: {
                writeText: function (text) { written.push(text); return Promise.resolve(); } } });
            copy.click();
            await t.sleep(100);
            t.step('a tap copies it and says so for a moment', [written, copy.textContent.trim()], [['123456'], 'Skopiowano']);
            const asked = (await t.stand.requests('POST /dj/devices/code'))[0].q;
            t.step('the code asked for the party, with the CSRF token', [asked.partyCode, !!asked['X-CSRF-TOKEN']],
                [document.getElementById('partyCode').value, true]);

            await t.stand.config({ devices: [device(5, 'Laptop DJ-a — Windows')] });
            await t.waitFor(function () { return rows() === 1; }, 'the paired computer', 8000);
            t.step('the program took the code: the code gone, the steps folded away, the button green',
                [code(), setup().hidden, another().hidden, green()], ['', true, false, true]);

            document.querySelector('#deviceList [data-device-id="5"] button').click();
            await t.waitFor(function () { return rows() === 0; }, 'the list without it', 3000);
            const removed = (await t.stand.requests('POST /dj/devices/remove'))[0].q;
            t.step('"Odłącz" names the computer and the party', [removed.id, removed.partyCode, !!removed['X-CSRF-TOKEN']],
                ['5', document.getElementById('partyCode').value, true]);
            t.step('no computer left: the steps again, the button not green', [setup().hidden, another().hidden, green()], [false, true, false]);
        }
    });

    S2P.scenario({
        name: 'deck-computers-code-kept',
        title: '"Połącz komputer": the code stays while another computer is disconnected, and goes when a program took it even with the list as long as before (3 computers: the fourth replaces the oldest)',
        setup: { devices: [] },
        run: async function (t) {
            await t.stand.config({ devices: [device(5), device(6), device(7)] });
            await t.sleep(300);
            toggle().click();
            await t.waitFor(function () { return rows() === 3; }, 'three computers', 3000);
            t.step('a "Odłącz" names its computer for a screen reader',
                document.querySelector('#deviceList [data-device-id="5"] button').getAttribute('aria-label'), 'Odłącz Komputer 5');

            another().click();
            document.getElementById('deviceConnect').click();
            await t.waitFor(function () { return code().indexOf('123 456') >= 0; }, 'the code', 3000);

            await t.stand.config({ devices: [device(6), device(7)] });                  // one disconnected: the code is still good
            await t.waitFor(function () { return rows() === 2; }, 'two computers', 8000);
            t.check('the code and the steps stay after another computer was disconnected', code().indexOf('123 456') >= 0 && !setup().hidden);

            await t.stand.config({ devices: [device(6), device(7), device(8)] });       // a program took it
            await t.waitFor(function () { return code() === ''; }, 'the code gone', 8000);
            t.check('the steps folded away again', setup().hidden);

            another().click();
            document.getElementById('deviceConnect').click();
            await t.waitFor(function () { return code().indexOf('123 456') >= 0; }, 'a second code', 3000);
            await t.stand.config({ devices: [device(7), device(8), device(9)] });       // the list as long as before: the oldest replaced
            await t.waitFor(function () { return code() === ''; }, 'the code gone, the list as long as before', 8000);
        }
    });

    S2P.scenario({
        name: 'deck-computers-code-before-list',
        title: '"Połącz komputer" pressed before the list of computers came: the code stays until a program takes it',
        setup: { devices: [], delays: { '/dj/devices': 1.5 } },
        run: async function (t) {
            await t.sleep(300);
            toggle().click();
            document.getElementById('deviceConnect').click();
            await t.waitFor(function () { return code().indexOf('123 456') >= 0; }, 'the code', 3000);
            await t.waitFor(loaded, 'the (empty) list came', 4000);
            await t.sleep(300);
            t.check('the code still shown after the list came', code().indexOf('123 456') >= 0);
        }
    });
})();
