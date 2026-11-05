// "PODPOWIEDZI GOŚCI" (V58): the organiser's choice in the library's card ("Twoja biblioteka", "Ustawienia imprezy"). Picked, it is
// sent at once in the background (data-auto-submit → cards-in-place.js, as every card that saves in place): the party, the choice, the
// fetch's header — the server's part of the card put in place with the new choice and "✓ Zapisane", the focus kept on the choice,
// the library's upload untouched, no page load.
(function () {
    S2P.scenario({
        name: 'library-hints-switch',
        title: '"Podpowiedzi gości" switched to "Bez zaznaczania": sent at once in the background, the server\'s part in place with "✓ Zapisane", the focus on the choice, no page load',
        page: 'settings-library',
        viewport: '390,844',
        run: async function (t) {
            const loads = [];
            navigation.addEventListener('navigate', function (e) { loads.push(e.destination.url); e.preventDefault(); });
            const card = document.getElementById('libraryCard');
            card.open = true;
            const upload = document.getElementById('libraryForm');
            const select = document.getElementById('libraryHintsSelect');
            t.step('on: the DJ\'s songs first', select.value, 'true');
            t.step('nothing saved yet', !!document.getElementById('libraryHintsSaved'), false);

            select.focus();
            select.value = 'false';
            select.dispatchEvent(new Event('change', { bubbles: true }));
            await t.waitFor(function () { return document.getElementById('libraryHintsSaved'); }, 'the server\'s part', 4000);
            const sent = await t.stand.requests('POST /dj/library/hints');
            t.step('sent once, in the background: the choice, the party, the fetch\'s header', sent.map(function (r) {
                return [r.q.on, r.q.partyCode, r.q['X-Requested-With']];
            }), [['false', 'HARN1', 'fetch']]);
            t.step('"✓ Zapisane", the new choice', [document.getElementById('libraryHintsSaved').textContent.trim(),
                document.getElementById('libraryHintsSelect').value], ['✓ Zapisane', 'false']);
            t.step('the focus on the choice', document.activeElement.id, 'libraryHintsSelect');
            t.step('the same card, its upload form untouched, no page load',
                [document.getElementById('libraryCard') === card, document.getElementById('libraryForm') === upload, card.open, loads],
                [true, true, true, []]);
        }
    });
})();
