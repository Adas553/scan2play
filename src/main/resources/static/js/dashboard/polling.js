/**
 * TABLE POLLING — refreshes the guest queue table every 3 seconds.
 *
 * Uses ETag / 304 Not Modified to skip DOM replacement when the queue hasn't changed. This preserves client-side sorting and
 * reduces bandwidth. Every answer, 304 too, also carries the server's guest limits (X-Guest-Limits, X-Guest-Limits-Use).
 * When the rows are new the player is told (EVENTS.GUEST_QUEUE_UPDATED): a guest song may be waiting.
 */
import { EVENTS, emit } from './events.js';
import { csrfHeaders, partyCode, showPartyActive } from './common.js';
import { applyListFilters, reapplySort } from './list-tools.js';

let currentETag = null;

/**
 * Shows the warning of each server limit the header names (search-spent, party-full) and hides the others. A missing
 * header (an answer from before this version, an error page) changes nothing.
 */
function applyGuestLimits(value) {
    if (value === null) return;
    const active = value.split(',').map(function (flag) { return flag.trim(); });
    document.querySelectorAll('#guestLimitWarnings [data-guest-limit]').forEach(function (warning) {
        warning.classList.toggle('d-none', active.indexOf(warning.dataset.guestLimit) < 0);
    });
}

/**
 * Sets the badges with the use of the server limits from '<busiest network>,<party>': "used/limit", grey, yellow from 80 %,
 * red at the limit — the same rule as the page's template. A missing or malformed header changes nothing.
 */
function applyGuestLimitsUse(value) {
    if (value === null) return;
    const used = value.split(',').map(function (n) { return parseInt(n, 10); });
    if (used.length !== 2 || used.some(isNaN)) return;
    [['network', used[0]], ['party', used[1]]].forEach(function (pair) {
        const badge = document.querySelector('[data-limit-use="' + pair[0] + '"]');
        const limit = badge ? parseInt(badge.dataset.limit, 10) : NaN;
        if (isNaN(limit)) return;
        badge.textContent = pair[1] + '/' + limit;
        badge.classList.toggle('text-bg-danger', pair[1] >= limit);
        badge.classList.toggle('text-bg-warning', pair[1] < limit && pair[1] * 5 >= limit * 4);
        badge.classList.toggle('text-bg-secondary', pair[1] * 5 < limit * 4);
    });
}

async function refreshTable() {
    try {
        const party = partyCode();
        if (!party) return;

        const headers = csrfHeaders();
        if (currentETag) {
            headers['If-None-Match'] = currentETag;
        }

        const sentAt = Date.now();
        const response = await fetch('/dj/dashboard/updates?partyCode=' + encodeURIComponent(party), {
            method: 'GET',
            headers: headers
        });

        // Every answer, 304 too, says which server limit stops guest songs now, and how much of each is used
        applyGuestLimits(response.headers.get('X-Guest-Limits'));
        applyGuestLimitsUse(response.headers.get('X-Guest-Limits-Use'));
        // …and whether the party is open: it may have been ended or resumed in another window
        const partyActive = response.headers.get('X-Party-Active');
        if (partyActive === 'true' || partyActive === 'false') showPartyActive(partyActive === 'true', sentAt);

        // 304 Not Modified — queue unchanged, skip DOM replacement
        if (response.status === 304) {
            return;
        }

        if (response.redirected) {
            window.location.reload();
            return;
        }
        if (!response.ok) throw new Error('HTTP ' + response.status);

        // Store new ETag for next poll
        const newETag = response.headers.get('ETag');
        if (newETag) {
            currentETag = newETag;
        }

        const html = await response.text();

        // Use <table> as temp container — <div>.innerHTML strips tbody/tr/td
        const tempTable = document.createElement('table');
        tempTable.innerHTML = html;
        const newTbody = tempTable.querySelector('#song-list');
        if (newTbody) {
            document.getElementById('song-list').replaceWith(newTbody);
        } else {
            document.getElementById('song-list').innerHTML = html;
        }

        emit(EVENTS.GUEST_QUEUE_UPDATED);

        // Re-apply user's sort preference after table refresh
        reapplySort();

        // The rows are new: apply the search text again, and update the number of songs in the heading
        applyListFilters(document.getElementById('queueList'));
        updateGuestsWaiting();
    } catch (err) {
        console.error('[Polling] Refresh error:', err);
    } finally {
        setTimeout(refreshTable, 3000);
    }
}

/**
 * The line above the "up next" list: how many guest songs wait — they play before the track marked "Next". Counted from the queue
 * table (accepted songs, refreshed by the poll above) — only the ones that have a YouTube video ID, i.e. that Auto-Pilot can play
 * (the row's data-video-id, set by the server — YouTubeUrls.extractVideoId); the ones with a search link are for the DJ to play by
 * hand. Hidden when there are none. The plural form is the browser's (Polish has three), the four texts
 * travel in data attributes of the line (dashboard.html); there is no such line for a Spotify party.
 */
function updateGuestsWaiting() {
    const line = document.getElementById('fallbackGuestsWaiting');
    if (!line) return;
    let waiting = 0;
    document.querySelectorAll('#song-list tr[data-song-id]').forEach(function(row) {
        if (row.getAttribute('data-video-id')) waiting++;
    });
    if (waiting === 0) {
        line.classList.add('d-none');
        line.textContent = '';
        return;
    }
    const form = new Intl.PluralRules(line.dataset.lang || 'en').select(waiting);   // one | few | many | other
    const text = line.dataset['text' + form.charAt(0).toUpperCase() + form.slice(1)] || line.dataset.textOther || '';
    line.textContent = text.replace('{0}', String(waiting));
    line.classList.remove('d-none');
}

updateGuestsWaiting();
setTimeout(refreshTable, 3000);
