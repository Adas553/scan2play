/**
 * TABLE POLLING — refreshes the guest queue table every 3 seconds.
 *
 * Uses ETag / 304 Not Modified to skip DOM replacement when the queue hasn't changed. This preserves client-side sorting and
 * reduces bandwidth. Every answer, 304 too, also carries the server's guest limits (X-Guest-Limits, X-Guest-Limits-Use).
 *
 * A hidden window does not poll at all — the DJ's phone in a pocket, a second tab, the dashboard behind the DJ's own software: nobody
 * sees the list, and it is fetched at once when the window is shown again.
 */
import { EVENTS, on } from './events.js';
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

// One chain of polls: the next one is scheduled when one ends; a poll asked for meanwhile (pollNow) comes right after it
let nextPoll = null;
let polling = false;
let pollAgain = false;

/** Fetches the queue now instead of at the next 3 s tick (EVENTS.GUEST_QUEUE_CHANGED: the DJ changed it here). */
function pollNow() {
    if (polling) {
        pollAgain = true;
        return;
    }
    clearTimeout(nextPoll);
    refreshTable();
}
on(EVENTS.GUEST_QUEUE_CHANGED, pollNow);

document.addEventListener('visibilitychange', function () {
    if (document.visibilityState === 'visible') pollNow();
});

async function refreshTable() {
    polling = true;
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

        // Re-apply user's sort preference after table refresh
        reapplySort();

        // The rows are new: apply the search text again, and update the number of songs in the heading
        applyListFilters(document.getElementById('queueList'));
    } catch (err) {
        console.error('[Polling] Refresh error:', err);
    } finally {
        polling = false;
        nextPoll = pollAgain || !document.hidden ? setTimeout(refreshTable, pollAgain ? 0 : 3000) : null;
        pollAgain = false;
    }
}

nextPoll = setTimeout(refreshTable, 3000);
