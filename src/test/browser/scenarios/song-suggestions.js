// THE GUEST'S SONG SUGGESTIONS (song-autocomplete.js): iTunes asked as the guest types. An answer that comes after the guest typed on
// is not the field's any more — it is not shown (review 2026-10-14: a slow answer for "ab" showed over the field that said
// something else). And the field is a combobox for a screen reader: it says whether the list is open and which suggestion is chosen.
(function () {
    /** iTunes as the test answers it: each asked query waits until the test lets it answer. */
    const holdITunes = function () {
        const pending = [];
        const original = window.fetch;
        window.fetch = function (resource) {
            const url = new URL(String(resource && resource.url ? resource.url : resource), location.href);
            if (url.hostname !== 'itunes.apple.com') return original.apply(this, arguments);   // the harness's own
            return new Promise(function (resolve) {
                pending.push({
                    term: url.searchParams.get('term'),
                    answer: function (names) {
                        resolve(new Response(JSON.stringify({ results: names.map(function (n) {
                            const parts = n.split(' - ');
                            return { artistName: parts[0], trackName: parts[1] };
                        }) }), { headers: { 'Content-Type': 'application/json' } }));
                    }
                });
            });
        };
        return pending;
    };
    const type = function (input, text) {
        input.value = text;
        input.dispatchEvent(new Event('input', { bubbles: true }));
    };
    const open = function () {
        const list = document.querySelector('.song-autocomplete-dropdown');
        return !!list && list.classList.contains('is-open');
    };

    S2P.scenario({
        name: 'song-suggestions-late-answer',
        title: 'a late answer of iTunes for what the guest typed before is not shown: not after the field was cleared, not over a newer query served from memory',
        page: 'guest',
        run: async function (t) {
            const pending = holdITunes();
            const input = document.getElementById('songInput');
            input.focus();

            type(input, 'abba');
            await t.waitFor(function () { return pending.length === 1; }, 'iTunes asked for "abba"', 3000);
            pending[0].answer(['ABBA - Dancing Queen']);
            await t.waitFor(open, 'the suggestions for "abba"', 3000);

            type(input, 'abb');
            await t.waitFor(function () { return pending.length === 2; }, 'iTunes asked for "abb"', 3000);
            type(input, 'abba');   // "abba" again: from memory, at once — while "abb" is still on its way
            await t.sleep(450);
            pending[1].answer(['Abbey Road - Something']);
            await t.sleep(200);
            t.step('the field says "abba": its suggestions, not the late "abb"',
                Array.prototype.map.call(document.querySelectorAll('.song-autocomplete-item'), function (i) { return i.dataset.label; }),
                ['ABBA – Dancing Queen']);

            type(input, 'kult');
            await t.waitFor(function () { return pending.length === 3; }, 'iTunes asked for "kult"', 3000);
            type(input, '');
            await t.sleep(450);
            pending[2].answer(['Kult - Arahja']);
            await t.sleep(200);
            t.step('the field cleared meanwhile: no list', open(), false);
        }
    });

    S2P.scenario({
        name: 'song-suggestions-combobox',
        title: 'the song field is a combobox: it says whether its list is open, points at the list, and ↓ names the chosen suggestion',
        page: 'guest',
        run: async function (t) {
            const pending = holdITunes();
            const input = document.getElementById('songInput');
            const list = document.querySelector('.song-autocomplete-dropdown');
            t.step('a combobox of a list box, closed', [input.getAttribute('role'), input.getAttribute('aria-controls'),
                input.getAttribute('aria-expanded'), list.getAttribute('role')], ['combobox', list.id, 'false', 'listbox']);

            input.focus();
            type(input, 'abba');
            await t.waitFor(function () { return pending.length === 1; }, 'iTunes asked', 3000);
            pending[0].answer(['ABBA - Dancing Queen', 'ABBA - Mamma Mia']);
            await t.waitFor(open, 'the suggestions', 3000);
            t.step('open', input.getAttribute('aria-expanded'), 'true');

            input.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, cancelable: true }));
            const items = document.querySelectorAll('.song-autocomplete-item');
            t.step('↓: the first suggestion chosen, named by the field', [input.getAttribute('aria-activedescendant'),
                items[0].getAttribute('aria-selected'), items[1].getAttribute('aria-selected')], [items[0].id, 'true', 'false']);
            t.check('each suggestion has its own id', items[0].id && items[0].id !== items[1].id);

            input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
            t.step('Esc: closed, nothing chosen', [input.getAttribute('aria-expanded'), input.hasAttribute('aria-activedescendant')],
                ['false', false]);
        }
    });
})();
