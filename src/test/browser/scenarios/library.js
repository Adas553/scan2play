// The DJ's library (V41, library.js on the page "Ustawienia imprezy"): the chosen file goes as the request's body — not a form's
// upload, which the server would read before it checks the login (DjLibraryController) — with the CSRF token in the header and the
// party in the address. A file the server refuses says the server's own sentence and nothing loads; a saved one brings the card
// "Twoja biblioteka" again from the server, in place — the page neither loads again nor moves (the owner, 2026-10-25: the screen
// jumped from the top to the card).
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
        title: 'the DJ\'s library: the file as the body with the CSRF header; refused, the server\'s reason; saved, the card again in place — no reload, no jump',
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
            window.scrollTo({ top: document.getElementById('libraryCard').getBoundingClientRect().top + window.scrollY - 100, behavior: 'instant' });
            const scrolled = window.scrollY;
            choose('#EXTM3U\n#EXTINF:245,Wilki - Baśka\nC:\\Music\\wilki.mp3\n', 'wesele.m3u8');
            button.click();
            await t.waitFor(function () { return document.getElementById('libraryForm') !== form; }, 'the card again', 4000);
            const fresh = document.getElementById('libraryAnswer');
            t.step('saved: the card from the server in place, with the server\'s sentence', [fresh.textContent, fresh.hidden],
                ['Gotowe — utwory w bibliotece: 2.', false]);
            t.step('saved: nothing loaded, the screen where it was', [loads, Math.abs(window.scrollY - scrolled) < 2], [[], true]);

            const second = document.getElementById('libraryForm');
            choose('#EXTM3U\nABBA - SOS.mp3\n', 'disco.m3u');
            second.querySelector('button[type="submit"]').click();
            await t.waitFor(function () { return document.getElementById('libraryForm') !== second; }, 'the new card sends too', 4000);
            t.step('the new card\'s form sends as the first did, never as the panel\'s form', await t.stand.count('POST /dj/library'), 3);
        }
    });

    // One playlist of a rekordbox collection (V42): library.js reads the playlists' names from the chosen file in the browser — the
    // file is not sent for it — and offers them with their folders, as rekordbox shows the tree; the one chosen goes in the address.
    // An M3U is one playlist already: nothing to choose.
    const COLLECTION = '<?xml version="1.0" encoding="UTF-8"?>\n<DJ_PLAYLISTS Version="1.0.0"><COLLECTION Entries="1">'
        + '<TRACK TrackID="1" Name="Baśka" Artist="Wilki"/></COLLECTION><PLAYLISTS><NODE Type="0" Name="ROOT" Count="2">'
        + '<NODE Name="Wesele &amp; poprawiny" Type="1" KeyType="0" Entries="1"><TRACK Key="1"/></NODE>'
        + '<NODE Type="0" Name="Kluby" Count="2"><NODE Name="Piątek" Type="1" KeyType="0" Entries="1"><TRACK Key="1"/></NODE>'
        + '<NODE Name="Pusta" Type="1" KeyType="0" Entries="0"/></NODE></NODE></PLAYLISTS></DJ_PLAYLISTS>';

    S2P.scenario({
        name: 'library-playlist',
        page: 'settings',
        title: 'the DJ\'s library: a rekordbox collection\'s playlists offered with their folders; the chosen one sent; an M3U offers none',
        run: async function (t) {
            const loads = [];
            navigation.addEventListener('navigate', function (e) {
                loads.push(e.navigationType);
                e.preventDefault();
            });
            const field = document.getElementById('libraryPlaylistField');
            const select = document.getElementById('libraryPlaylist');
            const input = document.getElementById('libraryFile');
            t.step('at first nothing to choose', field.hidden, true);

            choose(COLLECTION, 'collection.xml');
            input.dispatchEvent(new Event('change', { bubbles: true }));
            await t.waitFor(function () { return !field.hidden; }, 'the playlists offered', 4000);
            t.step('the whole collection first, then each playlist by its folders', Array.from(select.options).map(function (o) {
                return [o.value, o.textContent];
            }), [['', 'Cała kolekcja'], ['Wesele & poprawiny', 'Wesele & poprawiny'], ['Kluby / Piątek', 'Kluby / Piątek'],
                ['Kluby / Pusta', 'Kluby / Pusta']]);

            const form = document.getElementById('libraryForm');
            select.value = 'Kluby / Piątek';
            form.querySelector('button[type="submit"]').click();
            await t.waitFor(function () { return document.getElementById('libraryForm') !== form; }, 'saved', 4000);
            const sent = (await t.stand.requests('POST /dj/library'))[0].q;
            t.step('the playlist in the address, the whole file as the body', [sent.playlist, sent.__body.length], ['Kluby / Piątek', COLLECTION.length]);

            // the card came again from the server: its own field and select
            const fresh = document.getElementById('libraryForm');
            const freshInput = document.getElementById('libraryFile');
            const files = new DataTransfer();
            files.items.add(new File(['#EXTM3U\nWilki - Baśka.mp3\n'], 'wesele.m3u8'));
            freshInput.files = files.files;
            freshInput.dispatchEvent(new Event('change', { bubbles: true }));
            await t.sleep(300);
            t.step('an M3U: nothing to choose', document.getElementById('libraryPlaylistField').hidden, true);
            fresh.querySelector('button[type="submit"]').click();
            await t.waitFor(function () { return document.getElementById('libraryForm') !== fresh; }, 'saved again', 4000);
            t.step('and no playlist sent', (await t.stand.requests('POST /dj/library'))[1].q.playlist, undefined);
            t.step('nothing loaded the page', loads, []);
        }
    });

    // "Usuń bibliotekę": sent in the background too, the card brought again in place — no page load, no jump to the card.
    S2P.scenario({
        name: 'library-delete',
        page: 'settings-library',
        title: 'the DJ\'s library: "Usuń bibliotekę" sent in the background, the card again in place — no reload, no jump',
        run: async function (t) {
            const loads = [];
            navigation.addEventListener('navigate', function (e) {
                loads.push(e.navigationType);
                e.preventDefault();
            });
            const card = document.getElementById('libraryCard');
            window.scrollTo({ top: card.getBoundingClientRect().top + window.scrollY - 100, behavior: 'instant' });
            const scrolled = window.scrollY;
            document.querySelector('#libraryDeleteForm button[type="submit"]').click();
            await t.waitFor(function () { return document.getElementById('libraryCard') !== card; }, 'the card again', 4000);
            const sent = (await t.stand.requests('POST /dj/library/delete'))[0].q;
            t.step('sent once, with the party and the CSRF token in the header', [await t.stand.count('POST /dj/library/delete'), sent.partyCode,
                sent['X-CSRF-TOKEN']], [1, 'HARN1', 'harness-csrf-token']);
            t.step('nothing loaded, the screen where it was', [loads, Math.abs(window.scrollY - scrolled) < 2], [[], true]);
        }
    });
})();
