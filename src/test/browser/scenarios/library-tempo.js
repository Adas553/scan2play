// The tempo and the key of the DJ's track beside its library mark (V57: from a rekordbox collection, as the file writes them) — "✓ Masz
// 128 BPM · 5A". On the mark's line, never a line of its own: on a phone's folded card a line more is a card less on the screen. The
// rendered page's own rows (1 …), read before the first poll replaces them with the stand-in's.
(function () {
    function lineOf(id) {
        const row = document.querySelector('#song-list tr[data-song-id="' + id + '"]');
        const mark = row && row.querySelector('.s2p-library');
        const tempo = row && row.querySelector('.s2p-library-tempo');
        return { row: row, mark: mark, tempo: tempo };
    }

    function middle(el) {
        const box = el.getBoundingClientRect();
        return box.top + box.height / 2;
    }

    function checks(t) {
        const have = lineOf(1);
        const similar = lineOf(4);
        const missing = lineOf(2);
        t.step('the words beside "✓" and "?", none beside "✗"', [have.tempo && have.tempo.innerText.trim(),
            similar.tempo && similar.tempo.innerText.trim(), missing.tempo === null], ['128 BPM · 5A', '122.5 BPM', true]);
        t.step('the tempo on the mark\'s line', Math.abs(middle(have.tempo) - middle(have.mark)) < 4, true);
        t.check('right of the mark', have.tempo.getBoundingClientRect().left >= have.mark.getBoundingClientRect().right);
        t.check('nothing wider than the screen', document.documentElement.scrollWidth <= document.documentElement.clientWidth);
    }

    S2P.scenario({
        name: 'library-tempo-beside-mark',
        page: 'dashboard-library',
        title: 'the DJ\'s library: the tempo and the key of the DJ\'s track on the line of its "✓" / "?" mark, none at "✗"',
        run: async function (t) {
            checks(t);
        }
    });

    S2P.scenario({
        name: 'library-tempo-beside-mark-phone',
        page: 'dashboard-library',
        viewport: '390,844',
        title: 'a phone: the tempo and the key on the mark\'s line in the folded card, shown folded',
        run: async function (t) {
            const have = lineOf(1);
            t.step('the card folded', have.row.classList.contains('s2p-open'), false);
            t.check('the tempo shown folded', have.tempo.checkVisibility());
            checks(t);
        }
    });
})();
