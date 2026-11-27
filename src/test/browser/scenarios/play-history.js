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

    // ---- V81: each computer's line says whether its program sees songs; amber during the party when it stopped; "Ostrzeż po" ----

    const amber = function () { return toggle().classList.contains('btn-outline-warning'); };
    const row = function (id) { return document.querySelector('#deviceList [data-device-id="' + id + '"]'); };
    const minutesAgo = function (m) { return new Date(Date.now() - m * 60000).toISOString(); };
    const hhmm = function (iso) {
        return new Date(iso).toLocaleString(document.documentElement.lang || undefined, { hour: '2-digit', minute: '2-digit' });
    };

    S2P.scenario({
        name: 'deck-computer-lines',
        title: '"Zagrane samo" watched (V81): a computer\'s line by its state — sees songs (the last one\'s time and program), waiting for the first song (the test before the party), no new songs / not connected in amber during the party, in grey after it — and the queue\'s button amber while one is amber',
        setup: { devices: [] },
        run: async function (t) {
            const played = minutesAgo(25);
            const seen = minutesAgo(9);
            await t.stand.config({ devices: [
                Object.assign(device(1, 'Gra'), { lastPlayAt: minutesAgo(2), program: 'VirtualDJ', state: 'OK' }),
                Object.assign(device(2, 'Nowy'), { state: 'WAITING' }),
                Object.assign(device(3, 'Cichy'), { lastPlayAt: played, program: 'Serato', state: 'SILENT', warn: true }),
                Object.assign(device(4, 'Zamknięty'), { lastSeenAt: seen, state: 'OFFLINE', warn: true })] });
            document.dispatchEvent(new Event('visibilitychange'));
            await t.waitFor(function () { return rows() === 4; }, 'four computers', 3000);
            t.step('the button amber, not green: one program stopped during the party', [amber(), green()], [true, false]);
            t.step('the amber said in the button\'s name and title too, not by its colour alone',
                [toggle().getAttribute('aria-label').indexOf('program nie widzi piosenek') >= 0,
                    toggle().title.indexOf('program nie widzi piosenek') >= 0], [true, true]);
            toggle().click();
            t.step('sees songs: the last one\'s time and the program, a green tick', [row(1).textContent.indexOf('widzi piosenki') >= 0,
                row(1).textContent.indexOf('(VirtualDJ)') >= 0, row(1).querySelector('.s2p-deck-device-ok').textContent], [true, true, '✓']);
            t.check('no song yet: what to do to test it', row(2).textContent.indexOf('puść dowolny utwór') >= 0);
            t.step('silent during the party: since when, in amber', [row(3).textContent.indexOf('brak nowych piosenek od ' + hhmm(played)) >= 0,
                !!row(3).querySelector('.s2p-deck-device-warn'), !!row(3).querySelector('.text-warning')], [true, true, true]);
            t.check('gone during the party: since its last call', row(4).textContent.indexOf('program nie łączy się od ' + hhmm(seen)) >= 0);

            await t.stand.config({ devices: [
                Object.assign(device(3, 'Cichy'), { lastPlayAt: played, program: 'Serato', state: 'SILENT', warn: false }),
                Object.assign(device(4, 'Zamknięty'), { lastSeenAt: seen, state: 'OFFLINE', warn: false })] });
            document.dispatchEvent(new Event('visibilitychange'));
            await t.waitFor(function () { return rows() === 2; }, 'the two after the party', 3000);
            t.step('after the party: the last song and the last call in grey, no amber, the button green again',
                [row(3).textContent.indexOf('ostatnia piosenka ' + hhmm(played)) >= 0, row(4).textContent.indexOf('ostatnio ' + hhmm(seen)) >= 0,
                    document.querySelectorAll('#deviceList .s2p-deck-device-warn').length, amber(), green()], [true, true, 0, false, true]);
            t.check('the button\'s name without the warning again', toggle().getAttribute('aria-label').indexOf('nie widzi') < 0);
        }
    });

    S2P.scenario({
        name: 'deck-silence-not-saved',
        title: '"Ostrzeż po" not saved (the server refuses: no Pro any more): said, and the select back at the minutes the server keeps — even with the focus still on it',
        setup: { devices: [], postStatus: { '/dj/devices/silence': 403 } },
        run: async function (t) {
            const select = function () { return document.getElementById('deviceSilence'); };
            const failed = function () { return document.getElementById('deviceSilenceFailed'); };
            await t.stand.config({ devices: [device(5)], silenceMinutes: 20 });
            document.dispatchEvent(new Event('visibilitychange'));
            await t.waitFor(function () { return rows() === 1; }, 'the computer', 3000);
            toggle().click();
            t.step('nothing said yet', failed().hidden, true);

            select().focus();
            select().value = '10';
            select().dispatchEvent(new Event('change'));
            await t.waitFor(function () { return !failed().hidden; }, 'the refusal said', 3000);
            await t.waitFor(function () { return select().value === '20'; }, 'the select back at 20', 3000);
            t.step('said, the select at what the server keeps', [failed().textContent.indexOf('Nie udało się') >= 0, select().value],
                [true, '20']);
        }
    });

    S2P.scenario({
        name: 'deck-older-answer-dropped',
        title: '"Zagrane samo": an answer to an older ask of the list (slow), coming after a newer one, is dropped — the card shows the newer',
        setup: { devices: [] },
        run: async function (t) {
            await t.stand.config({ devices: [device(5, 'Stary')], delays: { '/dj/devices': 1.2 } });
            document.dispatchEvent(new Event('visibilitychange'));   // asked first, answered last
            await t.sleep(200);
            await t.stand.config({ devices: [device(6, 'Nowy')], delays: { '/dj/devices': 0 } });
            document.dispatchEvent(new Event('visibilitychange'));   // asked second, answered at once
            await t.waitFor(function () { return row(6) !== null; }, 'the newer list', 3000);
            await t.sleep(1600);   // the older answer has come by now
            t.step('the newer list stays', [row(6) !== null, row(5) === null], [true, true]);
        }
    });

    S2P.scenario({
        name: 'deck-silence-choice',
        title: '"Ostrzeż po" (V81): shown once a computer is paired, set to the party\'s minutes, saved at once with the CSRF token',
        setup: { devices: [] },
        run: async function (t) {
            const watch = function () { return document.getElementById('deviceWatch'); };
            const select = function () { return document.getElementById('deviceSilence'); };
            // the list asked again after this scenario's setup: the page's own first ask may have met the stand-in's state before it
            delete list().dataset.loaded;
            document.dispatchEvent(new Event('visibilitychange'));
            await t.waitFor(loaded, 'the list', 3000);
            toggle().click();
            t.check('no computer: nothing to watch', watch().hidden);

            await t.stand.config({ devices: [device(5)], silenceMinutes: 30 });
            document.dispatchEvent(new Event('visibilitychange'));
            await t.waitFor(function () { return rows() === 1; }, 'the computer', 3000);
            t.step('shown, at the party\'s minutes; the choices', [watch().hidden, select().value,
                Array.from(select().options).map(function (o) { return o.value; })], [false, '30', ['10', '20', '30', '0']]);

            select().value = '10';
            select().dispatchEvent(new Event('change'));
            await t.waitFor(function () { return !select().disabled; }, 'saved, the list asked again', 3000);
            await t.sleep(300);
            t.step('saved once', await t.stand.count('POST /dj/devices/silence'), 1);
            const saved = (await t.stand.requests('POST /dj/devices/silence'))[0].q;
            t.step('saved for the party, with the CSRF token', [saved.minutes, saved.partyCode, !!saved['X-CSRF-TOKEN']],
                ['10', document.getElementById('partyCode').value, true]);
            await t.sleep(300);
            t.check('the choice stays after the list came again', select().value === '10');
        }
    });
})();
