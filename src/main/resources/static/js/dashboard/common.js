/** What every part of the dashboard needs: the CSRF token, the party, the provider. */

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

/** True when the YouTube embedded player is on the page (a YouTube party). */
export function isYouTubeProvider() {
    return !!document.getElementById('yt-player-card');
}
