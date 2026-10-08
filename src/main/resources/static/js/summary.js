// The evening summary (templates/summary.html): "Print / PDF" opens the browser's print window ("Save as PDF" makes a file), and
// picking another evening shows it at once — so the "Show" button, which sends the form without the script, is hidden.
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
})();
