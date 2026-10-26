/**
 * THE SETTINGS — the settings, the vibe, the lists, the staff and the QR code fold under one button (#settingsToggle) on every screen,
 * so the queue comes right under the heading (on a phone first; on a computer too since 2026-10-09). They are folded by app.css alone
 * (until <body> has .s2p-settings-open), so nothing jumps while the page loads; this opens and closes them.
 * The choice is kept for this tab (sessionStorage): a page reload leaves them open. So is the fold "Więcej" (#settingsMoreFold).
 */
const KEY = 'scan2play.settingsOpen';
const MORE_KEY = 'scan2play.settingsMoreOpen';

(function initMoreFold() {
    const more = document.getElementById('settingsMoreFold');
    if (!more) return;
    try {
        if (sessionStorage.getItem(MORE_KEY) === '1') more.open = true;
    } catch (e) { /* storage blocked: start folded */ }
    more.addEventListener('toggle', function () {
        try {
            if (more.open) sessionStorage.setItem(MORE_KEY, '1');
            else sessionStorage.removeItem(MORE_KEY);
        } catch (e) { /* storage blocked: only this page remembers */ }
    });
})();

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

    // "Ustaw klimat" under an empty queue (data-open-settings): the settings open, the party's card comes into view
    document.querySelectorAll('[data-open-settings]').forEach(function (opener) {
        opener.addEventListener('click', function () {
            if (!document.body.classList.contains('s2p-settings-open')) button.click();
            const card = document.getElementById('partyCard');
            if (card) card.scrollIntoView({ behavior: 'smooth', block: 'start' });
        });
    });
})();
