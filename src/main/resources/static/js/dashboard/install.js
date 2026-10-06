/**
 * "📲 ZAINSTALUJ APLIKACJĘ" — the dashboard as an app on the DJ's phone (the web app manifest, /manifest.webmanifest).
 *
 * The browser's own offer to install comes when the browser decides, and after it was dismissed (or the app removed) not again for
 * months; this button asks when the DJ wants:
 *   - Chrome / Edge (Android, a computer): the browser says the app can be installed (`beforeinstallprompt`) — the event is kept and
 *     the button opens the browser's install window; installed (`appinstalled`), the button goes. The event opens the window once:
 *     closed without installing ("Dowiedz się więcej" closes it too), the button stays and says where the browser's menu has it,
 *     until the browser offers again;
 *   - an iPhone / iPad in Safari: no such event — the button shows the steps ("Udostępnij" → "Do ekranu początkowego"); there the
 *     app is needed for the notifications too;
 *   - the installed app itself, or a browser that never offered: nothing is shown.
 *
 * Dependencies (DOM): <div id="installApp">, <button id="installAppBtn">, its notes [data-install-note="ios" | "menu"].
 */
import { isInstalledApp, isIos } from './common.js';

const section = document.getElementById('installApp');
const button = document.getElementById('installAppBtn');

/** The browser's install offer, kept for the button (it opens the install window once). */
let offer = null;

function show(visible) {
    section.hidden = !visible;
}

/** Shows one note under the button (or none). */
function showNote(name) {
    section.querySelectorAll('[data-install-note]').forEach(function (note) {
        note.classList.toggle('d-none', note.dataset.installNote !== name);
    });
}

if (section && button && !isInstalledApp()) {
    window.addEventListener('beforeinstallprompt', function (event) {
        event.preventDefault();          // no mini-infobar of the browser: the button asks instead
        offer = event;
        showNote(null);
        show(true);
    });
    window.addEventListener('appinstalled', function () {
        offer = null;
        show(false);
    });
    if (isIos()) {
        show(true);
    }

    button.addEventListener('click', function () {
        if (offer) {
            const asked = offer;
            offer = null;                // used up: the browser opens its window once per offer
            asked.prompt();
            // Installed: `appinstalled` hides the section. Closed without installing: the button stays — the next click says where
            // the browser's menu has it (a new offer of the browser replaces that)
        } else {
            showNote(isIos() ? 'ios' : 'menu');
        }
    });
}
