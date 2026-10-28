// "Ukryj „Nie masz”" beside the queue's search (V42, list-tools.js): it hides the requests the DJ's library has no song of ("✗"); the
// "? Podobny" ones and those without a mark stay. It holds over the polls that replace the rows and works with the search: the count
// says how many of all are shown.
(function () {
    const URL = 'https://www.youtube.com/results?search_query=x';

    function shownIds() {
        return Array.from(document.querySelectorAll('#song-list tr[data-song-id]'))
            .filter(function (row) { return !row.classList.contains('d-none'); })
            .map(function (row) { return Number(row.dataset.songId); });
    }

    S2P.scenario({
        name: 'library-only',
        page: 'dashboard-library',
        title: '"Ukryj „Nie masz”": the requests without a song in the DJ\'s library hidden, "?" and unmarked kept — over the polls, with the search',
        setup: {
            queue: [{ id: 11, name: 'Wilki - Baśka', url: URL, library: 'have' },
                { id: 12, name: 'sanah - Szampan', url: URL, library: 'missing' },
                { id: 13, name: 'Dżem - Baśka', url: URL, library: 'similar' },
                { id: 14, name: 'ABBA - SOS', url: URL }]
        },
        run: async function (t) {
            // the stand-in's rows (11…), not the rendered page's (1…)
            await t.waitFor(function () { return document.querySelector('#song-list tr[data-song-id="14"]'); }, 'the queue poll', 8000);
            const only = document.getElementById('libraryOnly');
            const count = document.querySelector('#queueList [data-list-count]');
            t.step('at first everything, the switch off', [shownIds(), only.getAttribute('aria-pressed'), count.textContent],
                [[11, 12, 13, 14], 'false', '4']);

            only.click();
            t.step('on: the "✗" one hidden, the count says of how many', [shownIds(), only.getAttribute('aria-pressed'), count.textContent],
                [[11, 13, 14], 'true', '3 / 4']);

            const search = document.querySelector('#queueList [data-list-search]');
            search.value = 'baska';
            search.dispatchEvent(new Event('input', { bubbles: true }));
            t.step('with the search: both', [shownIds(), count.textContent], [[11, 13], '2 / 4']);
            search.value = '';
            search.dispatchEvent(new Event('input', { bubbles: true }));

            await t.stand.config({ queue: [{ id: 11, name: 'Wilki - Baśka', url: URL, library: 'have' },
                { id: 12, name: 'sanah - Szampan', url: URL, library: 'missing' },
                { id: 13, name: 'Dżem - Baśka', url: URL, library: 'similar' },
                { id: 14, name: 'ABBA - SOS', url: URL },
                { id: 15, name: 'Akcent - Przez Twe Oczy Zielone', url: URL, library: 'missing' }] });
            await t.waitFor(function () { return document.querySelector('#song-list tr[data-song-id="15"]'); }, 'the next poll', 8000);
            t.step('a new "✗" request after a poll: hidden too', [shownIds(), count.textContent], [[11, 13, 14], '3 / 5']);

            only.click();
            t.step('off: everything again', [shownIds(), only.getAttribute('aria-pressed'), count.textContent],
                [[11, 12, 13, 14, 15], 'false', '5']);
        }
    });
})();

// The owner's list (C, 2026-10-28): on a phone with a library "Ukryj „Nie masz”", "+ Dodaj piosenkę" and the search were two rows above the
// queue. Said short ("Ukryj ✗", "+ Dodaj"; the whole words their aria-label) they share one line.
S2P.scenario({
    name: 'library-tools-one-row-phone',
    title: 'a phone with the DJ\'s library: "Ukryj ✗", "+ Dodaj" and the search on one line above the queue, each 44 px, the whole words for a screen reader',
    page: 'dashboard-library',
    viewport: '390,844',
    run: async function (t) {
        const hide = document.getElementById('libraryOnly');
        const add = document.getElementById('addSongToggle');
        const search = document.querySelector('#queueList .s2p-list-search');
        const top = function (el) { return Math.round(el.getBoundingClientRect().top); };
        t.step('the short words shown', [hide.innerText.trim(), add.innerText.trim()], ['Ukryj ✗', '+ Dodaj']);
        t.step('the whole words their names', [hide.getAttribute('aria-label'), add.getAttribute('aria-label')], ['Ukryj „Nie masz”', 'Dodaj piosenkę']);
        t.step('one line', [top(add) === top(hide), top(search) === top(hide)], [true, true]);
        t.check('the search keeps room to type (' + Math.round(search.getBoundingClientRect().width) + ' px)', search.getBoundingClientRect().width >= 120);
        t.check('each a thumb\'s target', [hide, add, search].every(function (el) { return el.getBoundingClientRect().height >= 44 - 0.5; }));
        t.check('nothing wider than the screen', document.documentElement.scrollWidth <= document.documentElement.clientWidth);
    }
});
