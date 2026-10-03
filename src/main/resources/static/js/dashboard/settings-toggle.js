/**
 * SETTINGS ON A PHONE — the settings, the vibe, the background playlist and the QR code fold under one button (#settingsToggle), so
 * the player (YouTube) and the queue come right under the heading. They are folded by app.css alone (a narrow screen, until <body>
 * has .s2p-settings-open), so nothing jumps while the page loads; this opens and closes them. On a wide screen the button is hidden
 * and they always show.
 * The choice is kept for this tab (sessionStorage): a form that reloads the page — a Spotify party's do — leaves them open.
 */
const KEY = 'scan2play.settingsOpen';

(function initSettingsToggle() {
    const button = document.getElementById('settingsToggle');
    if (!button) return;

    function show(open) {
        document.body.classList.toggle('s2p-settings-open', open);
        button.setAttribute('aria-expanded', String(open));
    }

    try {
        if (sessionStorage.getItem(KEY) === '1') show(true);
    } catch (e) { /* storage blocked: start folded */ }

    button.addEventListener('click', function () {
        const open = !document.body.classList.contains('s2p-settings-open');
        show(open);
        try {
            if (open) sessionStorage.setItem(KEY, '1');
            else sessionStorage.removeItem(KEY);
        } catch (e) { /* storage blocked: only this page remembers */ }
    });
})();
