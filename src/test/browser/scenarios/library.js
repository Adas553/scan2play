// The DJ's library (V41, library.js on the page "Ustawienia imprezy"): the chosen file goes as the request's body — not a form's
// upload, which the server would read before it checks the login (DjLibraryController) — with the CSRF token in the header and the
// party in the address. A file the server refuses says the server's own sentence and nothing loads; a saved one brings the card
// "Twoja biblioteka" again from the server, in place — the page neither loads again nor moves (the owner, 2026-10-25: the screen
// jumped from the top to the card).
(function () {
    /** Puts a file in the input as if the DJ had chosen it: the browser says so with a "change". */
    function choose(text, name) {
        const files = new DataTransfer();
        files.items.add(new File([text], name));
        const input = document.getElementById('libraryFile');
        input.files = files.files;
        input.dispatchEvent(new Event('change', { bubbles: true }));
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

    // The file's field: the browser's input is hidden, its label is the field — "Wybierz plik", then the file's name and size —, a file
    // dropped on it is chosen too, and "Wgraj" (the card's one action) is enabled only with a file. On a phone it fills the card.
    S2P.scenario({
        name: 'library-file-field',
        page: 'settings',
        viewport: '390,844',
        title: 'the DJ\'s library: our own file field — "Wybierz plik", then the name and size; a dropped file chosen too; "Wgraj" only with a file',
        run: async function (t) {
            document.querySelector('#libraryCard > summary').click();   // unfolded, as the DJ does
            const input = document.getElementById('libraryFile');
            const drop = document.getElementById('libraryDrop');
            const upload = document.getElementById('libraryUpload');
            const name = drop.querySelector('[data-file-name]');
            t.step('at first: "Wybierz plik", "Wgraj" disabled, the browser\'s own input not drawn',
                [name.textContent.trim(), upload.disabled, input.getBoundingClientRect().width <= 1], ['Wybierz plik', true, true]);
            const card = document.getElementById('libraryCard').getBoundingClientRect();
            t.step('the field and "Wgraj" as wide as the card\'s content, the field a finger\'s target', [
                Math.abs(drop.getBoundingClientRect().width - upload.getBoundingClientRect().width) < 1,
                drop.getBoundingClientRect().width > card.width - 40, drop.getBoundingClientRect().height >= 44], [true, true, true]);

            choose('#EXTM3U\nWilki - Baśka.mp3\n', 'wesele.m3u8');
            t.step('chosen: its name and size in the field, "Wgraj" enabled',
                [name.textContent.trim(), drop.classList.contains('is-chosen'), upload.disabled], ['wesele.m3u8 · 1 KB', true, false]);

            const dropped = new DataTransfer();
            dropped.items.add(new File(['#EXTM3U\nABBA - SOS.mp3\n'], 'disco.m3u'));
            drop.dispatchEvent(new DragEvent('dragover', { bubbles: true, cancelable: true, dataTransfer: dropped }));
            t.step('a file dragged over: the field lights', drop.classList.contains('is-dragover'), true);
            drop.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: dropped }));
            t.step('dropped: it is the chosen file, the field says so', [input.files[0] && input.files[0].name, name.textContent.trim(),
                drop.classList.contains('is-dragover')], ['disco.m3u', 'disco.m3u · 1 KB', false]);
            upload.click();
            await t.waitFor(function () { return document.getElementById('libraryUpload') !== upload; }, 'the card again', 4000);
            const sent = (await t.stand.requests('POST /dj/library'))[0].q;
            t.step('the dropped file sent; the new card: "Wybierz plik", "Wgraj" disabled again', [sent.__body,
                document.querySelector('#libraryDrop [data-file-name]').textContent.trim(), document.getElementById('libraryUpload').disabled],
                ['#EXTM3U\nABBA - SOS.mp3\n', 'Wybierz plik', true]);
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

    // Two playlists of one name in one folder (rekordbox lets them be): the second offered as "Wesele (2)" and sent with its
    // occurrence, so the server reads that one, not the first.
    const TWINS = '<DJ_PLAYLISTS><COLLECTION><TRACK TrackID="1" Name="Baśka" Artist="Wilki"/><TRACK TrackID="2" Name="SOS" Artist="ABBA"/>'
        + '</COLLECTION><PLAYLISTS><NODE Type="0" Name="ROOT"><NODE Name="Wesele" Type="1"><TRACK Key="1"/></NODE>'
        + '<NODE Name="Wesele" Type="1"><TRACK Key="2"/></NODE></NODE></PLAYLISTS></DJ_PLAYLISTS>';

    S2P.scenario({
        name: 'library-playlist-twins',
        page: 'settings',
        title: 'the DJ\'s library: a second playlist of the same name offered as "Wesele (2)" and sent with its occurrence',
        run: async function (t) {
            const field = document.getElementById('libraryPlaylistField');
            const select = document.getElementById('libraryPlaylist');
            choose(TWINS, 'collection.xml');
            await t.waitFor(function () { return !field.hidden; }, 'the playlists offered', 4000);
            t.step('both offered, the second with its number', Array.from(select.options).slice(1).map(function (o) {
                return [o.value, o.textContent];
            }), [['Wesele', 'Wesele'], ['Wesele', 'Wesele (2)']]);

            const form = document.getElementById('libraryForm');
            select.selectedIndex = 2;
            form.querySelector('button[type="submit"]').click();
            await t.waitFor(function () { return document.getElementById('libraryForm') !== form; }, 'saved', 4000);
            const sent = (await t.stand.requests('POST /dj/library'))[0].q;
            t.step('the name and the occurrence in the address', [sent.playlist, sent.occurrence], ['Wesele', '2']);
        }
    });

    // A file larger than the server reads is refused in the browser: neither read for its playlists (a film picked by mistake would
    // fill a phone's memory as text) nor sent. And an answer that is no sentence of ours — a proxy's HTML page — is never shown as one.
    S2P.scenario({
        name: 'library-too-large',
        page: 'settings',
        title: 'the DJ\'s library: a file over the limit refused before it is read or sent; a proxy\'s HTML answer never shown as the sentence',
        setup: { libraryStatus: 413, libraryType: 'text/html; charset=utf-8',
                 libraryAnswer: '<html><head><title>413 Request Entity Too Large</title></head><body>cloudflare</body></html>' },
        run: async function (t) {
            const form = document.getElementById('libraryForm');
            const answer = document.getElementById('libraryAnswer');
            const input = document.getElementById('libraryFile');
            const limit = Number(form.dataset.maxBytes);
            t.step('the page knows the limit: 64 MB', limit, 64 * 1024 * 1024);

            let read = false;
            const text = Blob.prototype.text;
            Blob.prototype.text = function () { read = true; return text.call(this); };
            const big = new File([new Uint8Array(limit + 1)], 'film.xml');
            const files = new DataTransfer();
            files.items.add(big);
            input.files = files.files;
            input.dispatchEvent(new Event('change', { bubbles: true }));
            await t.sleep(200);
            t.step('chosen: "too large" said at once, the file not read', [answer.hidden, answer.textContent, read],
                [false, 'Plik jest za duży: najwyżej 64 MB.', false]);
            form.querySelector('button[type="submit"]').click();
            await t.sleep(300);
            t.step('"Wgraj": nothing sent', await t.stand.count('POST /dj/library'), 0);
            Blob.prototype.text = text;

            choose('#EXTM3U\nWilki - Baśka.mp3\n', 'wesele.m3u8');
            t.step('another file chosen: the old sentence gone', answer.hidden, true);
            form.querySelector('button[type="submit"]').click();
            await t.waitFor(function () { return answer.classList.contains('text-danger'); }, 'the refusal', 4000);
            t.step('a proxy\'s HTML page: the page\'s own sentence, not the HTML', answer.textContent, form.dataset.refused);
        }
    });

    // "Usuń bibliotekę": asks first (data-confirm, dj-nav.js) — the marks go at once and only the file brings them back; then sent in
    // the background, the card brought again in place — no page load, no jump to the card.
    S2P.scenario({
        name: 'library-delete',
        page: 'settings-library',
        title: 'the DJ\'s library: "Usuń bibliotekę" asks first; then sent in the background, the card again in place — no reload, no jump',
        run: async function (t) {
            const loads = [];
            navigation.addEventListener('navigate', function (e) {
                loads.push(e.navigationType);
                e.preventDefault();
            });
            const asked = [];
            let answer = false;
            window.confirm = function (text) { asked.push(text); return answer; };
            const card = document.getElementById('libraryCard');
            window.scrollTo({ top: card.getBoundingClientRect().top + window.scrollY - 100, behavior: 'instant' });
            const scrolled = window.scrollY;
            document.querySelector('#libraryDeleteForm button[type="submit"]').click();
            await t.sleep(300);
            t.step('asked first; "Anuluj": nothing sent, the card as it was', [asked.length, asked[0] && asked[0].indexOf('Usunąć bibliotekę?') === 0,
                await t.stand.count('POST /dj/library/delete'), document.getElementById('libraryCard') === card], [1, true, 0, true]);
            answer = true;
            document.querySelector('#libraryDeleteForm button[type="submit"]').click();
            await t.waitFor(function () { return document.getElementById('libraryCard') !== card; }, 'the card again', 4000);
            const sent = (await t.stand.requests('POST /dj/library/delete'))[0].q;
            t.step('sent once, with the party and the CSRF token in the header', [await t.stand.count('POST /dj/library/delete'), sent.partyCode,
                sent['X-CSRF-TOKEN']], [1, 'HARN1', 'harness-csrf-token']);
            t.step('nothing loaded, the screen where it was', [loads, Math.abs(window.scrollY - scrolled) < 2], [[], true]);
        }
    });
})();
