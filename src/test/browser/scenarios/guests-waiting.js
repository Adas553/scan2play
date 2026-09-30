// The line above the "up next" list that says how many guest songs wait (dashboard.js updateGuestsWaiting; PROJECT_CONTEXT.md
// Section 5.4, "Up next panel"). The first track of the playlist is marked "Next" although waiting guest songs play before it, and
// nothing said so. The line counts the songs of the queue table that Auto-Pilot can play — the ones with a YouTube video ID — and
// follows the table as the poll refreshes it (every 3 s). The plural form is the browser's: Polish has three.
//
// The rendered page has two playable songs; the stand-in's poll then answers with the rows the scenario configures.
(function () {
    const playable = function (id) { return { id: id, name: 'Guest song ' + id, url: 'https://www.youtube.com/watch?v=abcdefgh' + String(id).padStart(3, '0') }; };
    const search = function (id) { return { id: id, name: 'Guest song ' + id, url: 'https://www.youtube.com/results?search_query=guest+song+' + id }; };
    const many = function (n, make) { return Array.from({ length: n }, function (_, i) { return make(100 + i); }); };

    S2P.scenario({
        name: 'guests-waiting',
        title: 'the line above the "up next" list counts the guest songs Auto-Pilot can play, with the right Polish plural, and follows the queue',
        setup: {},
        run: async function (t) {
            const line = document.getElementById('fallbackGuestsWaiting');
            const text = function () { return line.classList.contains('d-none') ? null : line.textContent; };

            t.step('the rendered page has two playable songs in the queue: the line is there from the start', text() && text().slice(0, 21), 'Czekają 2 piosenki go');

            // each poll takes at most 3 s; the queue table is replaced when the stand-in's answer differs from the last one
            async function queueBecomes(rows, expectedText, label) {
                await t.stand.config({ queue: rows });
                await t.waitFor(function () { return text() === expectedText; }, label, 8000).catch(function () { /* the step below says what it was */ });
                t.step(label, text(), expectedText);
            }

            await queueBecomes(many(1, playable), 'Czeka 1 piosenka gościa — zagra jako pierwsza.', '1 playable song: "one" form');
            await queueBecomes(many(3, playable), 'Czekają 3 piosenki gości — zagrają jako pierwsze.', '3 songs: "few" form');
            await queueBecomes(many(5, playable), 'Czeka 5 piosenek gości — zagrają jako pierwsze.', '5 songs: "many" form');
            await queueBecomes(many(22, playable), 'Czekają 22 piosenki gości — zagrają jako pierwsze.', '22 songs: "few" again');
            await queueBecomes(many(3, playable).concat(many(4, search)), 'Czekają 3 piosenki gości — zagrają jako pierwsze.',
                '3 playable and 4 with only a search link: the search links are not counted');
            await queueBecomes(many(4, search), null, 'only search links: the line is hidden');
            await queueBecomes(many(2, playable), 'Czekają 2 piosenki gości — zagrają jako pierwsze.', 'two again: it comes back');
            await queueBecomes([], null, 'an empty queue: the line is hidden and empty');
            t.step('… and empty', line.textContent, '');
        }
    });
})();
