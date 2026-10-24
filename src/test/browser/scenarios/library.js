// The DJ's library (V41, library.js on the page "Ustawienia imprezy"): the chosen file goes as the request's body — not a form's
// upload, which the server would read before it checks the login (DjLibraryController) — with the CSRF token in the header and the
// party in the address. A file the server refuses says the server's own sentence and nothing loads; a saved one loads the page
// again, so the library's state is the server's.
(function () {
    /** Puts a file in the input as if the DJ had chosen it. */
    function choose(text, name) {
        const files = new DataTransfer();
        files.items.add(new File([text], name));
        document.getElementById('libraryFile').files = files.files;
    }

    S2P.scenario({
        name: 'library-upload',
        page: 'settings',
        title: 'the DJ\'s library: the file as the body with the CSRF header; refused, the server\'s reason and no reload; saved, the page loads again',
        setup: { libraryStatus: 422, libraryAnswer: 'To nie jest kolekcja z rekordboxa (XML) ani playlista M3U / M3U8.' },
        run: async function (t) {
            const loads = [];
            navigation.addEventListener('navigate', function (e) {
                loads.push(e.navigationType);
                e.preventDefault();
            });
            const form = document.getElementById('libraryForm');
            const answer = document.getElementById('libraryAnswer');
            const button = form.querySelector('button[type="submit"]');
            t.step('at first no answer', answer.hidden, true);

            choose('<html><body>notes</body></html>', 'notes.html');
            button.click();
            await t.waitFor(function () { return answer.classList.contains('text-danger'); }, 'the refusal', 4000);
            t.step('refused: the server\'s own reason', answer.textContent,
                'To nie jest kolekcja z rekordboxa (XML) ani playlista M3U / M3U8.');
            t.step('refused: the button back, nothing loaded', [button.disabled, loads.length], [false, 0]);
            const sent = (await t.stand.requests('POST /dj/library'))[0].q;
            t.step('the file is the body, as it is', [sent.__contentType, sent.__body],
                ['application/octet-stream', '<html><body>notes</body></html>']);
            t.step('the CSRF token in the header, the party in the address', [sent['X-CSRF-TOKEN'], sent.partyCode],
                ['harness-csrf-token', 'HARN1']);

            await t.stand.config({ libraryStatus: 200, libraryAnswer: 'Gotowe — utwory w bibliotece: 2.' });
            choose('#EXTM3U\n#EXTINF:245,Wilki - Baśka\nC:\\Music\\wilki.mp3\n', 'wesele.m3u8');
            button.click();
            await t.waitFor(function () { return loads.length > 0; }, 'the page loaded again', 4000);
            t.step('saved: the server\'s sentence, then the page again', [answer.textContent, loads], ['Gotowe — utwory w bibliotece: 2.', ['reload']]);
            t.step('sent once each, never as the panel\'s form', await t.stand.count('POST /dj/library'), 2);
        }
    });
})();
