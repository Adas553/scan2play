// THE GUEST'S SUGGESTIONS MARKED BY THE DJ'S LIBRARY (V58, song-autocomplete.js): a field with data-library-url asks our server
// which of iTunes's suggestions the DJ has, and shows those first, "✓ DJ ma" — each part in iTunes's order, the song picked without
// the mark. A slow or failed answer leaves the list as iTunes gave it, at once after the wait; an answer is asked once for a song.
// Both iTunes and our server are answered by the scenario (iTunes's host is blocked by the CSP before the network).
(function () {
    /** iTunes and "which does the DJ have" as the scenario answers them; the library's asks are recorded. */
    const stub = function (library) {
        const asked = [];
        const original = window.fetch;
        window.fetch = function (resource, init) {
            const url = new URL(String(resource && resource.url ? resource.url : resource), location.href);
            if (url.hostname === 'itunes.apple.com') {
                const term = url.searchParams.get('term');
                const names = { wilki: ['ABBA - SOS', 'Wilki - Baśka', 'Wilki - Urke'], kult: ['Kult - Arahja', 'Kult - Polska'] }[term] || [];
                return Promise.resolve(new Response(JSON.stringify({ results: names.map(function (n) {
                    const at = n.indexOf(' - ');
                    return { artistName: n.slice(0, at), trackName: n.slice(at + 3) };
                }) }), { headers: { 'Content-Type': 'application/json' } }));
            }
            if (url.pathname === '/p/ABC12/library/has') {
                const songs = url.searchParams.getAll('song');
                asked.push(songs);
                return library(songs, init && init.signal);
            }
            return original.apply(this, arguments);
        };
        return asked;
    };
    const type = function (input, text) {
        input.value = text;
        input.dispatchEvent(new Event('input', { bubbles: true }));
    };
    const items = function () {
        return Array.prototype.map.call(document.querySelectorAll('.song-autocomplete-item'), function (item) {
            const mark = item.querySelector('.ac-library');
            return item.dataset.label + (mark ? ' [' + mark.textContent + ']' : '');
        });
    };
    const json = function (body) {
        return Promise.resolve(new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } }));
    };

    S2P.scenario({
        name: 'song-suggestions-library-first',
        title: 'the guest\'s suggestions: the songs the DJ has first, "✓ DJ ma", each part in iTunes\'s order; picked without the mark; asked once',
        page: 'guest-library',
        run: async function (t) {
            const asked = stub(function (songs) {
                return json(songs.map(function (song) { return song === 'Wilki - Baśka'; }));
            });
            const input = document.getElementById('songInput');
            input.focus();

            type(input, 'wilki');
            await t.waitFor(function () { return items().length === 3; }, 'the suggestions for "wilki"', 3000);
            t.step('the library asked for the suggestions shown, in their order', asked, [['ABBA - SOS', 'Wilki - Baśka', 'Wilki - Urke']]);
            t.step('the DJ\'s song first, marked; the others as iTunes gave them', items(),
                ['Wilki – Baśka [✓ DJ ma]', 'ABBA – SOS', 'Wilki – Urke']);
            const first = document.querySelector('.song-autocomplete-item');
            t.step('the mark is the start of the artist\'s part', first.querySelector('.ac-artist').firstElementChild.className, 'ac-library');

            first.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
            t.step('picked: the song without the mark', input.value, 'Wilki – Baśka');

            type(input, 'wilk');
            await t.sleep(400);
            type(input, 'wilki');
            await t.waitFor(function () { return items().length === 3; }, 'the suggestions for "wilki" again', 3000);
            t.step('the same songs again: not asked again', asked.length, 1);
            t.step('… and marked all the same', items()[0], 'Wilki – Baśka [✓ DJ ma]');
        }
    });

    S2P.scenario({
        name: 'song-suggestions-library-slow',
        title: 'the library\'s answer slow or failed: the suggestions as iTunes gave them, unmarked, within a second',
        page: 'guest-library',
        run: async function (t) {
            let fail = false;
            stub(function (songs, signal) {
                return fail ? Promise.resolve(new Response('', { status: 404 }))
                    : new Promise(function (resolve, reject) {   // never answers: given up as a real fetch is, by its signal
                        signal.addEventListener('abort', function () { reject(new DOMException('aborted', 'AbortError')); });
                    });
            });
            const input = document.getElementById('songInput');
            input.focus();

            const started = performance.now();
            type(input, 'kult');
            await t.waitFor(function () { return items().length === 2; }, 'the suggestions for "kult"', 3000);
            const waited = performance.now() - started;
            t.step('unmarked, in iTunes\'s order', items(), ['Kult – Arahja', 'Kult – Polska']);
            t.check('shown after the wait for the library, not held up (' + Math.round(waited) + ' ms)', waited < 1500);

            fail = true;
            type(input, 'wilki');
            await t.waitFor(function () { return items().length === 3; }, 'the suggestions for "wilki"', 3000);
            t.step('a 404 (the DJ switched it off meanwhile): unmarked', items(), ['ABBA – SOS', 'Wilki – Baśka', 'Wilki – Urke']);
        }
    });

    S2P.scenario({
        name: 'song-suggestions-no-library',
        title: 'a party with nothing to tell (no data-library-url): the library never asked, the suggestions as before',
        page: 'guest',
        run: async function (t) {
            const asked = stub(function () { return json([true, true, true]); });
            const input = document.getElementById('songInput');
            input.focus();
            type(input, 'wilki');
            await t.waitFor(function () { return items().length === 3; }, 'the suggestions for "wilki"', 3000);
            t.step('not asked, unmarked', [asked.length, items()], [0, ['ABBA – SOS', 'Wilki – Baśka', 'Wilki – Urke']]);
        }
    });
})();
