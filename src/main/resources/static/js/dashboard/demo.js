/**
 * The demo's panel (/demo, DemoController): every card and button is there, nothing is sent. A form sent (a click, Enter, a select
 * that sends itself) and a link to a DJ's page (/dj/…: the settings, the staff, the print) stop here and the page says why — "start
 * your own party to use this" — before forms.js or the browser go on. Caught on the way down (capture), so forms.js sees the submit
 * already prevented and leaves it. Nothing happens outside the demo.
 *
 * Dependencies (DOM): <body data-demo="true">, #demoNotice (the note, hidden).
 */
import { demoMode } from './common.js';

/** How long the note stays, in ms. */
const NOTICE_MS = 4000;

let hideTimer = null;

function showNotice() {
    const notice = document.getElementById('demoNotice');
    if (!notice) return;
    notice.hidden = false;
    clearTimeout(hideTimer);
    hideTimer = setTimeout(function () { notice.hidden = true; }, NOTICE_MS);
}

if (demoMode()) {
    document.addEventListener('submit', function (e) {
        e.preventDefault();
        e.stopImmediatePropagation();
        showNotice();
    }, true);
    document.addEventListener('click', function (e) {
        const link = e.target.closest ? e.target.closest('a[href]') : null;
        if (!link) return;
        const path = new URL(link.getAttribute('href'), location.href).pathname;
        if (path.startsWith('/dj/')) {
            e.preventDefault();
            e.stopImmediatePropagation();
            showNotice();
        }
    }, true);
}
