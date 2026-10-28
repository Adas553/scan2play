/**
 * "Panel jako aplikacja" under an empty queue on a phone (the review of 2026-10-28: "Zainstaluj aplikację" was only in a folded card
 * of "Ustawienia imprezy", and on an iPhone the notifications of new requests need the app). Shown only where the app can be
 * installed — an iPhone's Safari, or a browser that offered it (`beforeinstallprompt`) —, never in the installed app; "Nie teraz"
 * hides it on this device for good. Its link leads to the card "To urządzenie", where install.js installs. app.css shows it on a
 * phone and under an empty queue only — never over a party's requests.
 *
 * Dependencies (DOM): <div id="installHint" hidden> with a [data-install-hint-later] button.
 */
import { isInstalledApp, isIos } from './common.js';

const KEY = 'scan2play.installHintDismissed';

(function initInstallHint() {
    const hint = document.getElementById('installHint');
    if (!hint || isInstalledApp()) return;
    try {
        if (localStorage.getItem(KEY) === '1') return;
    } catch (e) { /* storage blocked: the hint shows, "Nie teraz" hides it for this page */ }

    if (isIos()) hint.hidden = false;
    else window.addEventListener('beforeinstallprompt', function () { hint.hidden = false; });

    hint.querySelector('[data-install-hint-later]').addEventListener('click', function () {
        hint.hidden = true;
        try {
            localStorage.setItem(KEY, '1');
        } catch (e) { /* storage blocked: only this page remembers */ }
    });
})();
