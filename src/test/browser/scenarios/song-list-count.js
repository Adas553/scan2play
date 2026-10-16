// The hosts' lists say how many entries they hold, as they are typed, and warn when a list holds more than the server keeps (the
// owner, 2026-10-16: 120 lines were saved as 100 without a word) — on "Ustawienia imprezy" and on the hosts' page /h/… The count is
// the server's (SongList.tidy): one entry per line, a repeat once, none under three letters.
(function () {
    function type(field, text) {
        field.value = text;
        field.dispatchEvent(new Event('input', { bubbles: true }));
    }
    function lines(n) {
        return Array.from({ length: n }, function (_, i) { return 'Song ' + (i + 1); }).join('\n');
    }
    /** What the counter under {@code fieldId} says, and its warning (null while hidden). */
    function counter(fieldId) {
        const box = document.querySelector('[data-list-for="' + fieldId + '"]');
        const over = box.querySelector('[data-list-over]');
        return [box.querySelector('[data-list-count]').textContent, over.hidden ? null : over.textContent];
    }

    S2P.scenario({
        name: 'host-lists-count',
        title: '"Ustawienia imprezy": under each hosts\' list how many entries it holds of 100, a warning over 100 — as it is typed',
        page: 'settings',
        run: async function (t) {
            const blocked = document.getElementById('hostBlockedInput');
            type(blocked, 'Akcent\nakcent\nA\n\nBaby Shark');
            t.step('counted as the server keeps them: a repeat once, a line too short not at all', counter('hostBlockedInput'),
                ['2 z 100 pozycji', null]);
            type(blocked, lines(120));
            t.step('over the limit: the warning, before it is saved', counter('hostBlockedInput'),
                ['120 z 100 pozycji', 'Za dużo pozycji — zapisze się tylko 100 pierwszych.']);
            t.step('in the warning\'s yellow', getComputedStyle(document.querySelector('[data-list-for="hostBlockedInput"]')).color,
                'rgb(255, 193, 7)');
            t.step('the other list counts its own', counter('hostWantedInput')[0], '1 z 100 pozycji');
            type(blocked, lines(100));
            t.step('100 fit: no warning', counter('hostBlockedInput'), ['100 z 100 pozycji', null]);
        }
    });

    S2P.scenario({
        name: 'host-page-count',
        title: 'the hosts\' page: under each list how many entries it holds of 100 (the saved lists counted at once), a warning over 100',
        page: 'host',
        run: async function (t) {
            t.step('the saved lists counted at once', [counter('blocked')[0], counter('wanted')[0]], ['2 z 100 pozycji', '1 z 100 pozycji']);
            type(document.getElementById('wanted'), lines(101));
            t.step('over the limit: the warning', counter('wanted'), ['101 z 100 pozycji', 'Za dużo pozycji — zapisze się tylko 100 pierwszych.']);
        }
    });
})();
