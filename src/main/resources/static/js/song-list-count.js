// The hosts' lists (the page "Ustawienia imprezy" and the hosts' page /h/…): under each list, how many entries it holds as it is
// typed — counted as the server keeps them (SongList.tidy: one per line, a repeat once, none under three letters or digits) — and a
// warning when it holds more than the server keeps (the owner, 2026-10-16: 120 lines were saved as 100 without a word). A file of
// its own for the Content-Security-Policy; the texts and the limit come in data attributes.
(function () {
    /** A line's words as the server compares them (SongList.words): lower case, no accents, "ł" as "l", the rest one space. */
    function words(line) {
        return line.normalize('NFD').replace(/\p{M}/gu, '').replace(/ł/g, 'l').replace(/Ł/g, 'L').toLowerCase()
            .replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
    }
    /** The entries of a list as the server keeps them, without its limit (SongList.entries). */
    function entries(text) {
        const seen = new Set();
        text.split(/\r\n|\r|\n/).forEach(function (raw) {
            const key = words(raw.slice(0, 150));
            if (key.replace(/ /g, '').length >= 3) seen.add(key);
        });
        return seen.size;
    }
    document.querySelectorAll('[data-list-for]').forEach(function (box) {
        const field = document.getElementById(box.dataset.listFor);
        if (!field) return;
        const max = Number(box.dataset.listMax);
        const count = box.querySelector('[data-list-count]');
        const over = box.querySelector('[data-list-over]');
        function show() {
            const n = entries(field.value);
            const tooMany = n > max;
            count.textContent = box.dataset.textCount.replace('{0}', String(n)).replace('{1}', String(max));
            over.textContent = tooMany ? box.dataset.textOver.replace('{0}', String(max)) : '';
            over.hidden = !tooMany;
            box.classList.toggle('s2p-list-over', tooMany);
        }
        field.addEventListener('input', show);
        show();
    });
})();
