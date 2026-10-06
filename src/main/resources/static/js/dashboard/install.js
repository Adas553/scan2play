/**
 * "📲 ZAINSTALUJ APLIKACJĘ" — the dashboard as an app on the DJ's phone (the web app manifest, /manifest.webmanifest).
 *
 * The browser's own offer to install comes when the browser decides, and after it was dismissed (or the app removed) not again for
 * months; this button asks when the DJ wants:
 *   - Chrome / Edge (Android, a computer): the browser says the app can be installed (`beforeinstallprompt`) — the event is kept and
 *     the button opens the browser's install window; installed (`appinstalled`), the button goes;
 *   - an iPhone / iPad in Safari: no such event — the button shows the steps ("Udostępnij" → "Do ekranu początkowego"); there the
 *     app is needed for the notifications too;
 *   - the installed app itself, or a browser that cannot install: nothing is shown.
 *
 * Dependencies (DOM): <div id="installApp">, <button id="installAppBtn">, <div id="installAppIos">.
 */
import { isInstalledApp, isIos } from './common.js';

const section = document.getElementById('installApp');
const button = document.getElementById('installAppBtn');
const iosSteps = document.getElementById('installAppIos');

/** The browser's install offer, kept for the button (it can be used once). */
let offer = null;

function show(visible) {
    section.hidden = !visible;
}

if (section && button && !isInstalledApp()) {
    window.addEventListener('beforeinstallprompt', function (event) {
        event.preventDefault();          // no mini-infobar of the browser: the button asks instead
        offer = event;
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
            offer = null;
            asked.prompt();
            asked.userChoice.then(function (choice) {
                // Accepted: `appinstalled` hides the section; dismissed: the browser may offer again later (a new event)
                if (choice.outcome !== 'accepted') show(false);
            }).catch(function () { show(false); });
        } else if (iosSteps) {
            iosSteps.classList.remove('d-none');
        }
    });
}
