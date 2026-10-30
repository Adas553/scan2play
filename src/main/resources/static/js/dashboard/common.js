/** What every part of the dashboard needs: the CSRF token, the party, whether it is open, which device it runs on. */

const csrfToken = document.querySelector('meta[name="_csrf"]');
const csrfHeader = document.querySelector('meta[name="_csrf_header"]');

/** The CSRF header of a POST: {name: token}. */
export function csrfHeaders() {
    return csrfToken && csrfHeader ? { [csrfHeader.getAttribute('content')]: csrfToken.getAttribute('content') } : {};
}

/**
 * The demo's panel (/demo, DemoController): its queue and history are fetched from /demo/…, and nothing is sent (demo.js). The
 * page says so on its body (data-demo).
 */
export function demoMode() {
    return document.body !== null && document.body.dataset.demo === 'true';
}

/** The party code of the page (the dashboard, the settings, the standalone history page), or null. */
export function partyCode() {
    const input = document.getElementById('partyCode');
    return input ? input.value : null;
}

let partyStateChangedHereAt = 0;

/**
 * Shows the party open or closed: the "party closed" banner with its "resume" button, or the "end party" button. Used by the
 * window where the DJ pressed one of them (forms.js) and by every window when the queue poll says the state (X-Party-Active,
 * polling.js) — the party may have been ended on the phone; and the card "Godziny imprezy" of the settings page.
 */
export function showPartyActive(active, pollSentAt) {
    if (pollSentAt === undefined) {
        partyStateChangedHereAt = Date.now();      // the DJ pressed the button here
    } else if (pollSentAt <= partyStateChangedHereAt) {
        return;                                    // a poll sent before that may still say the old state
    }
    const banner = document.getElementById('party-closed-banner');
    const endForm = document.getElementById('end-party-form');
    if (banner) banner.classList.toggle('d-none', active);
    if (endForm) endForm.classList.toggle('d-none', !active);
    // the card "Godziny imprezy" (settings, V55): "Teraz impreza jest otwarta / zamknięta" follows; a manual change pressed here may
    // have dropped a planned moment on the server (PartySchedule.settle) — with one shown, the page loads again to show what is left
    const now = document.getElementById('partyHoursNow');
    if (now) now.textContent = active ? now.dataset.open : now.dataset.closed;
    if (pollSentAt === undefined && document.querySelector('.s2p-hours-planned')) window.location.reload();
}

/**
 * The page's notice that something the DJ asked for did not happen (fragments/components :: formRefused), in one of its two texts:
 * 'save' — a form the server refused or that never reached it (forms.js): not saved, copy what you typed and reload; 'load' — a list
 * that did not come (the History tab, its filter, a poll refused for good): reload. The page is not reloaded by itself.
 */
export function showRefused(kind) {
    const notice = document.querySelector('[data-form-refused]');
    if (!notice) return;
    const shown = kind || 'save';
    notice.querySelectorAll('[data-refused-text]').forEach(function (text) {
        text.hidden = text.dataset.refusedText !== shown;
    });
    notice.hidden = false;
}

/** Hides the notice; with a kind ('load': the list came after all), only when it says that one. */
export function hideRefused(kind) {
    const notice = document.querySelector('[data-form-refused]');
    if (!notice) return;
    if (typeof kind === 'string') {
        const text = notice.querySelector('[data-refused-text="' + kind + '"]');
        if (!text || text.hidden) return;
    }
    notice.hidden = true;
}

/** An iPhone or an iPad (an iPad says "Macintosh", but has a touch screen). */
export function isIos() {
    return /iPhone|iPad|iPod/.test(navigator.userAgent)
        || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
}

/** The dashboard opened as the installed app (from the Home Screen), not in a browser's tab. */
export function isInstalledApp() {
    return window.matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;
}

const FOCUSABLE = 'a[href], button, input, select, textarea, summary, [tabindex]';

/**
 * Where the keyboard's focus is inside {@code container}, so that it can be put back after the container's rows are replaced (a
 * poll's new rows): the request's row (data-song-id) and the place of the focused element among that row's focusable ones. Returns a
 * function that focuses the same place in what is there then — or does nothing when the focus was elsewhere, or the request has left
 * (review 2026-10-14: the focus fell to the page's top every 3 s).
 */
export function focusSpot(container) {
    const focused = document.activeElement;
    const row = container && focused && container.contains(focused) ? focused.closest('[data-song-id]') : null;
    if (!row) return function () {};
    const id = row.getAttribute('data-song-id');
    const at = Array.prototype.indexOf.call(row.querySelectorAll(FOCUSABLE), focused);
    return function (now) {
        const again = now && now.querySelector('[data-song-id="' + CSS.escape(id) + '"]');
        const target = again && again.querySelectorAll(FOCUSABLE)[at];
        if (target) target.focus({ preventScroll: true });
    };
}
