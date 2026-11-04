// The evening summary (templates/summary.html): "Print / PDF" opens the browser's print window ("Save as PDF" makes a file), and
// picking another evening shows it at once — so the "Show" button, which sends the form without the script, is hidden.
// What played, in order, is folded on a phone (a long list under the rest) and unfolded to print — the paper always has it; without
// the script it stays unfolded. "Kopiuj listę" of the library's gaps is the DJ pages' "Kopiuj" (dj-nav.js): shown only with a script.
(function () {
    'use strict';
    const print = document.getElementById('printBtn');
    if (print) print.addEventListener('click', function () { window.print(); });
    const evening = document.getElementById('eveningSelect');
    if (evening) {
        evening.addEventListener('change', function () { evening.form.submit(); });
        const show = evening.form.querySelector('button[type="submit"]');
        if (show) show.hidden = true;
    }
    const setlist = document.getElementById('setlistFold');
    if (setlist) {
        if (window.matchMedia('(max-width: 575.98px)').matches) setlist.open = false;
        window.addEventListener('beforeprint', function () { setlist.open = true; });
    }
    const copy = document.getElementById('copyLibrary');
    if (copy) copy.hidden = false;
})();
