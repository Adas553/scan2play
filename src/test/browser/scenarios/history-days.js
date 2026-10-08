// The history split by day (2026-10-03): it keeps 30 days, so the parties of a month share it — a heading where a new day starts
// ("wtorek, 29.09"; "dziś —" / "wczoraj —" before it for the last two days). The headings are the server's (history.html); the
// search hides a heading with no rows left under it, and a sort by a column hides them all (the rows of a day are no longer
// together) — until the sort is undone, which puts the rows back in the server's order. The sample timeline
// (DashboardPageRenderTest): five entries on Tuesday 29.09, five on Monday 28.09.
(function () {
    const names = function (rows) { return Array.from(rows).map(function (row) { return row.getAttribute('data-song-name'); }); };
    const type = function (input, text) {
        input.value = text;
        input.dispatchEvent(new Event('input', { bubbles: true }));
    };

    S2P.scenario({
        name: 'history-by-day',
        title: 'the history: a heading where a new day starts; the search hides a day with nothing left, a sort hides the headings, undoing it brings the server\'s order back',
        setup: {},
        run: async function (t) {
            const box = document.getElementById('history-content');
            const list = function () { return box.querySelector('[data-list]'); };
            const titles = function () { return names(list().querySelectorAll('tbody tr[data-song-name]')); };
            /** The rows of the list as the DJ sees them: a day heading as "# its text", a song as its name. */
            const seen = function () {
                return Array.from(list().querySelectorAll('tbody tr')).filter(function (row) {
                    return row.getClientRects().length > 0 && (row.hasAttribute('data-day-heading') || row.hasAttribute('data-song-name'));
                }).map(function (row) {
                    return row.hasAttribute('data-day-heading') ? '# ' + row.textContent.replace(/\s+/g, ' ').trim() : row.getAttribute('data-song-name');
                });
            };
            document.querySelector('[data-dj-tab="history"]').click();
            await t.waitFor(function () { return list(); }, 'the history', 5000);
            t.step('the first page: one day, its heading on top', seen(),
                ['# wtorek, 29.09', 'Żółć — piosenka', 'Played Alpha', 'Rejected Beat', 'Played Bravo']);

            list().querySelector('[data-history-more]').click();
            await t.waitFor(function () { return titles().length === 10; }, 'the longer history', 5000);
            t.step('the whole timeline: a second heading where Monday starts', seen(),
                ['# wtorek, 29.09', 'Żółć — piosenka', 'Played Alpha', 'Rejected Beat', 'Played Bravo', 'Guest Charlie',
                 '# poniedziałek, 28.09', 'Rejected Delta', 'Played Echo', 'Guest Foxtrot', 'Rejected Golf', 'Played Hotel']);

            const search = list().querySelector('[data-list-search]');
            type(search, 'played');
            t.step('a search that finds rows of both days keeps both headings', seen(),
                ['# wtorek, 29.09', 'Played Alpha', 'Played Bravo', '# poniedziałek, 28.09', 'Played Echo', 'Played Hotel']);
            type(search, 'echo');
            t.step('a search that finds rows of one day shows only its heading', seen(), ['# poniedziałek, 28.09', 'Played Echo']);
            type(search, 'nothing like this');
            t.step('nothing found: no heading either', seen(), []);
            type(search, '');

            const songHeader = list().querySelector('th[data-sort="song"]');
            songHeader.click();
            t.step('sorted by song: no headings (the days are mixed)', seen().slice(0, 3), ['Guest Charlie', 'Guest Foxtrot', 'Played Alpha']);
            songHeader.click();
            songHeader.click();
            t.step('the sort undone (third click): the server\'s order and the headings are back', seen(),
                ['# wtorek, 29.09', 'Żółć — piosenka', 'Played Alpha', 'Rejected Beat', 'Played Bravo', 'Guest Charlie',
                 '# poniedziałek, 28.09', 'Rejected Delta', 'Played Echo', 'Guest Foxtrot', 'Rejected Golf', 'Played Hotel']);
        }
    });

    // The owner (2026-10-03): "jak naciskam pokaż więcej, to filtr znika" — the longer list replaced the old one, and the sort by a
    // column was gone (the filter button and the search text were kept). Now the sort is kept too.
    S2P.scenario({
        name: 'history-more-keeps-the-sort',
        title: 'the history sorted by a column: "Show more" and a filter button keep the sort (and the search text)',
        setup: {},
        run: async function (t) {
            const box = document.getElementById('history-content');
            const list = function () { return box.querySelector('[data-list]'); };
            const titles = function () { return names(list().querySelectorAll('tbody tr[data-song-name]')); };
            const headingsShown = function () {
                return Array.from(list().querySelectorAll('tr[data-day-heading]')).filter(function (row) { return row.getClientRects().length > 0; }).length;
            };
            const sortOf = function (key) { return list().querySelector('th[data-sort="' + key + '"]').className.match(/sort-(asc|desc)/); };
            document.querySelector('[data-dj-tab="history"]').click();
            await t.waitFor(function () { return list(); }, 'the history', 5000);

            const songHeader = function () { return list().querySelector('th[data-sort="song"]'); };
            songHeader().click();
            songHeader().click();
            t.step('sorted by song, Z to A', titles(), ['Żółć — piosenka', 'Rejected Beat', 'Played Bravo', 'Played Alpha']);

            list().querySelector('[data-history-more]').click();
            await t.waitFor(function () { return titles().length === 10; }, 'the longer history', 5000);
            t.step('after "Show more" the longer list is sorted the same way, the header says so, no day headings',
                [titles().slice(0, 3), sortOf('song') && sortOf('song')[1], headingsShown()], [['Żółć — piosenka', 'Rejected Golf', 'Rejected Delta'], 'desc', 0]);

            type(list().querySelector('[data-list-search]'), 'rejected');
            list().querySelector('[data-list-filter="rejected"]').click();
            await t.waitFor(function () { return titles().length === 3; }, 'the rejected entries', 5000);
            t.step('a filter button keeps the sort and the search text', [titles(), sortOf('song') && sortOf('song')[1],
                list().querySelector('[data-list-search]').value], [['Rejected Golf', 'Rejected Delta', 'Rejected Beat'], 'desc', 'rejected']);

            songHeader().click();
            t.step('the third click still undoes it: the server\'s order', titles(), ['Rejected Beat', 'Rejected Delta', 'Rejected Golf']);
        }
    });
})();

// The owner (2026-10-08): on a wide screen "⏭ Pominięta przez DJ-a" / "🧹 Wyczyszczona przez DJ-a" stretched the verdict's column
// (the songs broke into four lines) and "↩ Przywróć" went under the words. Now one word in capitals, as "ZAGRANE" / "ODRZUCONE",
// what it means in its title, and "↩ Przywróć" on the same line.
S2P.scenario({
    name: 'history-wide-skip-and-clear',
    title: 'the history on a wide screen: a skip and a clear are one word in capitals (the meaning in the title), "↩ Przywróć" on the same line',
    setup: {},
    run: async function (t) {
        const row = function (name) { return document.querySelector('#history-content tr[data-song-name="' + name + '"]'); };
        const word = function (tr) {
            const label = Array.from(tr.querySelectorAll('.s2p-skipped-label')).filter(function (el) { return el.getClientRects().length > 0; })[0];
            return label ? [label.innerText.trim(), /przez DJ-a/.test(label.title)] : null;
        };
        const middle = function (el) { const box = el.getBoundingClientRect(); return box.top + box.height / 2; };
        document.querySelector('[data-dj-tab="history"]').click();
        await t.waitFor(function () { return row('Rejected Beat'); }, 'the history', 5000);
        const skipped = row('Rejected Beat');
        t.step('skipped: one word in capitals, the meaning in its title', word(skipped), ['POMINIĘTE', true]);
        const label = skipped.querySelector('.s2p-skipped-label');
        const restore = skipped.querySelector('form[action="/dj/dashboard/restore"] button');
        t.check('"↩ Przywróć" beside the word, on the same line',
            restore.getBoundingClientRect().left > label.getBoundingClientRect().right && Math.abs(middle(restore) - middle(label)) < 6);
        document.querySelector('#history-content [data-history-more]').click();   // the cleared one is older than the first four
        await t.waitFor(function () { return row('Rejected Delta'); }, 'the older entries', 5000);
        t.step('cleared: one word in capitals, the meaning in its title, no "↩ Przywróć"',
            [word(row('Rejected Delta')), row('Rejected Delta').querySelectorAll('form[action="/dj/dashboard/restore"]').length],
            [['WYCZYSZCZONE', true], 0]);
    }
});
