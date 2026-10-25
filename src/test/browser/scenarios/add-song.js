// "DODAJ PIOSENKĘ" over the queue (V44; add-song.js, forms.js): a song a guest asked for at the booth. The button folds the form out
// under the queue's heading with the cursor in its field; "Dodaj" sends it in the background — once —, the field is emptied for the
// next one and stays open, and the queue is fetched at once, the song in it. The queue's keys (S = "Pomiń", Enter = "Zagrane") never
// fire while the DJ types a song. On a phone the form fits the screen, "Dodaj" beside its field.
(function () {
    const form = function () { return document.getElementById('addSongForm'); };
    const field = function () { return document.getElementById('addSongInput'); };
    const toggle = function () { return document.getElementById('addSongToggle'); };
    const titles = function () {
        return Array.from(document.querySelectorAll('#song-list tr[data-song-id] .song-title')).map(function (t) { return t.textContent.trim(); });
    };
    const press = function (key, target) {
        const event = new KeyboardEvent('keydown', { key: key, bubbles: true, cancelable: true });
        target.dispatchEvent(event);
        return event.defaultPrevented;
    };
    const QUEUE = [{ id: 1, name: 'Daft Punk - One More Time', url: '' }];

    S2P.scenario({
        name: 'add-song',
        title: '"Dodaj piosenkę": the form folds out with the cursor in its field, "Dodaj" sends once, the field empties and stays open, the song comes into the queue',
        setup: { queue: QUEUE },
        run: async function (t) {
            await t.sleep(400);
            t.step('at first only the button: the form folded', [!!toggle(), form().hidden, toggle().getAttribute('aria-expanded')],
                [true, true, 'false']);

            toggle().click();
            t.step('a tap: the form shown, the cursor in its field, the button says it is open',
                [form().hidden, document.activeElement === field(), toggle().getAttribute('aria-expanded'), form().checkVisibility()],
                [false, true, 'true', true]);
            t.step('the guests\' suggestions on the field', field().getAttribute('data-autocomplete'), 'songs');

            // typing a song: "s" and Enter are letters of it, not "Pomiń" / "Zagrane"
            const pollsBefore = await t.stand.count('GET /dj/dashboard/updates');
            field().value = 'Wilki – Baśka';
            const swallowed = [press('s', field()), press('Enter', field())];
            await t.sleep(300);
            t.step('typing in the field skips and plays nothing', [await t.stand.count('POST /dj/dashboard/dismiss'),
                await t.stand.count('POST /dj/dashboard/play'), swallowed[0]], [0, 0, false]);

            form().querySelector('button[type="submit"]').click();
            await t.waitFor(function () { return titles().indexOf('Wilki – Baśka') >= 0; }, 'the added song in the queue', 4000);
            const sent = await t.stand.requests('POST /dj/dashboard/add-song');
            t.step('sent once, with the song and the party', [sent.length, sent.length && sent[0].q.song, sent.length && sent[0].q.partyCode],
                [1, 'Wilki – Baśka', 'HARN1']);
            t.step('the queue fetched at once, the song at its end', [(await t.stand.count('GET /dj/dashboard/updates')) > pollsBefore,
                titles()], [true, ['Daft Punk - One More Time', 'Wilki – Baśka']]);
            t.step('the field empty for the next one, the form still open', [field().value, form().hidden], ['', false]);

            toggle().click();
            t.step('a second tap folds it away', [form().hidden, toggle().getAttribute('aria-expanded')], [true, 'false']);
        }
    });

    S2P.scenario({
        name: 'add-song-refused',
        title: '"Dodaj piosenkę" the server refuses (the session ran out): ✗ and the page\'s notice, the typed song kept in the field',
        setup: { queue: QUEUE, postStatus: { '/dj/dashboard/add-song': 403 } },
        run: async function (t) {
            toggle().click();
            field().value = 'Kult - Arahja';
            const before = titles();
            const button = form().querySelector('button[type="submit"]');
            button.click();
            await t.waitFor(function () { return button.textContent.trim() === '✗'; }, 'the button says ✗', 3000);
            t.step('✗, the notice shown, the song still in the field to send again', [button.textContent.trim(),
                !document.querySelector('[data-form-refused]').hidden, field().value], ['✗', true, 'Kult - Arahja']);
            t.step('nothing came into the queue', [titles(), titles().indexOf('Kult - Arahja')], [before, -1]);
        }
    });

    S2P.scenario({
        name: 'add-song-phone',
        title: '"Dodaj piosenkę" on a phone: the button in the queue\'s heading, the form within the screen, "Dodaj" beside its field',
        viewport: '390,844',
        setup: { queue: QUEUE },
        run: async function (t) {
            await t.sleep(300);
            toggle().click();
            const input = field().getBoundingClientRect();
            const button = form().querySelector('button[type="submit"]').getBoundingClientRect();
            t.step('"Dodaj" on the field\'s line, right of it, of its height',
                [Math.abs(button.top - input.top) < 1, button.left > input.right, Math.abs(button.height - input.height) < 1], [true, true, true]);
            t.step('the field wide enough to read a song (more than half the screen)', input.width > 195, true);
            t.check('nothing wider than the screen', document.documentElement.scrollWidth <= document.documentElement.clientWidth);
        }
    });
})();
