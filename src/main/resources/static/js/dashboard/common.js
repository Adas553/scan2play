/** What every part of the dashboard needs: the CSRF token, the party, whether it is open, which device it runs on. */

const csrfToken = document.querySelector('meta[name="_csrf"]');
const csrfHeader = document.querySelector('meta[name="_csrf_header"]');

/** The CSRF header of a POST: {name: token}. */
export function csrfHeaders() {
    return csrfToken && csrfHeader ? { [csrfHeader.getAttribute('content')]: csrfToken.getAttribute('content') } : {};
}

/** The party code of the page, or null (the standalone history page has none). */
export function partyCode() {
    const input = document.getElementById('partyCode');
    return input ? input.value : null;
}

let partyStateChangedHereAt = 0;

/**
 * Shows the party open or closed: the "party closed" banner with its "resume" button, or the "end party" button. Used by the
 * window where the DJ pressed one of them (forms.js) and by every window when the queue poll says the state (X-Party-Active,
 * polling.js) — the party may have been ended on the phone.
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
