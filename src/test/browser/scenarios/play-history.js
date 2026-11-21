// "ZAGRANE SAMO" (play-history.js; docs/ideas.md item 19): the card of the queue pairs the DJ's program on the computer, which reads
// VirtualDJ's or Serato's play history and sends the songs itself (/api/device/plays). The panel reads no folder itself any more
// (variant E of item 18, parked): one way only.
(function () {
    const toggle = function () { return document.getElementById('deckToggle'); };
    const reader = function () { return document.getElementById('deckReader'); };

    S2P.scenario({
        name: 'deck-reader-program-only',
        title: '"Zagrane samo": in Chrome, which could read a folder, the card offers the program on the computer only — no folder to pick, nothing sent by the panel, nothing remembered',
        setup: { devices: [] },
        run: async function (t) {
            await t.sleep(500);
            t.step('the button shown on a computer, the card folded', [toggle().hidden, reader().hidden], [false, true]);
            toggle().click();
            t.step('a tap unfolds the card with the program\'s part', [reader().hidden, toggle().getAttribute('aria-expanded'),
                document.getElementById('deviceBlock').hidden], [false, 'true', false]);
            t.step('no reading in this browser, though it has the folder picker', [typeof window.showDirectoryPicker,
                !!document.getElementById('deckPick'), !!document.getElementById('deckBrowser')], ['function', false, false]);
            const databases = typeof indexedDB.databases === 'function' ? await indexedDB.databases() : [];
            t.step('no folder remembered by the panel', databases.some(function (d) { return d.name === 's2p-deck'; }), false);
            await t.sleep(300);
            t.step('the panel sends no songs itself', await t.stand.count('POST /dj/dashboard/plays'), 0);
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

    S2P.scenario({
        name: 'deck-computers-code-before-list',
        title: '"Połącz komputer" pressed before the list of computers came: the code stays until a program takes it',
        setup: { devices: [], delays: { '/dj/devices': 1.5 } },
        run: async function (t) {
            const code = function () { return document.getElementById('deviceCode').textContent; };
            await t.sleep(300);
            toggle().click();
            document.getElementById('deviceConnect').click();
            await t.waitFor(function () { return code().indexOf('123 456') >= 0; }, 'the code', 3000);
            await t.waitFor(function () { return document.getElementById('deviceList').textContent === reader().dataset.deviceNone; },
                'the (empty) list came', 4000);
            await t.sleep(300);
            t.check('the code still shown after the list came', code().indexOf('123 456') >= 0);
        }
    });
})();
