// "Tylko mam" beside the queue's search (V42, list-tools.js): it hides the requests the DJ's library has no song of ("✗"); the
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
        title: '"Tylko mam": the requests without a song in the DJ\'s library hidden, "?" and unmarked kept — over the polls, with the search',
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
