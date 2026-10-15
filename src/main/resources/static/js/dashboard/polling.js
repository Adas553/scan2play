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
import { csrfHeaders, hideRefused, partyCode, showPartyActive, showRefused } from './common.js';
import { applyListFilters, reapplySort } from './list-tools.js';
import { reopenCards } from './cards.js';
import { keepChosen } from './queue-keys.js';

let currentETag = null;

/**
 * Shows the warning of each server limit the header names (party-full) and hides the others. A missing
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
    applyLimitsNear(used[0], used[1]);
}

/**
 * Near a server limit (from 80 %, the template's rule): each [data-limit-near] of the limit shows how much is used — the warning
 * above the queue (data-below: only under the limit, where "wyczerpany" takes over) and the row "Limity gości" of the settings,
 * whose state is the guests' limit otherwise (data-limit-calm) and the party's use before the network's.
 */
function applyLimitsNear(network, party) {
    const near = { network: false, party: false };
    document.querySelectorAll('[data-limit-near]').forEach(function (element) {
        const kind = element.dataset.limitNear;
        const used = kind === 'party' ? party : network;
        const limit = parseInt(element.dataset.limit, 10);
        if (isNaN(limit) || limit <= 0) return;
        const isNear = used * 5 >= limit * 4 && (element.dataset.below !== 'true' || used < limit);
        if (element.dataset.below !== 'true') near[kind] = isNear;
        element.textContent = element.dataset.template.replace('{used}', used).replace('{limit}', limit);
        element.hidden = !isNear;
    });
    // the row shows one state: the party's use, else the network's, else the guests' own limit
    const row = document.querySelector('[data-settings-row="limits"]');
    if (!row) return;
    const networkInRow = row.querySelector('[data-limit-near="network"]');
    if (networkInRow && near.party) networkInRow.hidden = true;
    const calm = row.querySelector('[data-limit-calm]');
    if (calm) calm.hidden = near.party || near.network;
}

/** Whether the poll says other permissions than the page was made with (#panelAccess); a missing header changes nothing. */
function accessChanged(value) {
    const shown = document.getElementById('panelAccess');
    return value !== null && shown !== null && shown.value !== '' && value !== shown.value;
}

/**
 * Polls that failed in a row (no network, a 5xx). From OFFLINE_AFTER the bar [data-poll-offline] says the list may be out of date —
 * one failure is a hiccup the next poll puts right; the first answer hides it (the review of 2026-10-14, W12: the DJ's Wi-Fi gone
 * for minutes, and the queue looked current).
 */
const OFFLINE_AFTER = 3;
let failedPolls = 0;

function pollAnswered(ok) {
    failedPolls = ok ? 0 : failedPolls + 1;
    const bar = document.querySelector('[data-poll-offline]');
    if (bar) bar.hidden = failedPolls < OFFLINE_AFTER;
    if (ok) {
        forgetReload();
        hideRefused('load');
    }
}

const RELOADED = 's2p-poll-reloaded';

/**
 * The page loads itself again when the poll says it must (the access taken away or changed, the session gone) — once in a run of
 * such answers: a page that comes back to the same refusal (a 403 the new page cannot cure) loaded itself again every 3 s for as long
 * as it was open (the review of 2026-10-14, M14). The second time the notice says the list did not load, with its "reload" button,
 * and the poll counts as failed; a good answer starts over. Remembered in the tab's sessionStorage, which a reload keeps.
 *
 * @return whether the page is being loaded again
 */
function reloadOnce() {
    let reloadedAlready = false;
    try {
        reloadedAlready = sessionStorage.getItem(RELOADED) === '1';
        if (!reloadedAlready) sessionStorage.setItem(RELOADED, '1');
    } catch (e) {
        // no storage (a private window may refuse it): loaded again as before
    }
    if (reloadedAlready) {
        showRefused('load');
        return false;
    }
    window.location.reload();
    return true;
}

function forgetReload() {
    try {
        sessionStorage.removeItem(RELOADED);
    } catch (e) {
        // no storage: nothing remembered
    }
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

        // The person's access (V32): taken away (403) or changed by the organiser (X-Panel-Access) — the page loads itself again, and
        // the panel says what happened; before, the queue stood still and only the console knew
        if (response.status === 403 || accessChanged(response.headers.get('X-Panel-Access'))) {
            if (!reloadOnce()) pollAnswered(false);
            return;
        }

        // 304 Not Modified — queue unchanged, skip DOM replacement
        if (response.status === 304) {
            pollAnswered(true);
            return;
        }

        if (response.redirected) {   // the login page: the session is gone
            if (!reloadOnce()) pollAnswered(false);
            return;
        }
        if (!response.ok) throw new Error('HTTP ' + response.status);

        const html = await response.text();

        // Use <table> as temp container — <div>.innerHTML strips tbody/tr/td
        const tempTable = document.createElement('table');
        tempTable.innerHTML = html;
        const newTbody = tempTable.querySelector('#song-list');
        // An answer without the queue is not the server's (a Wi-Fi login page that took the request over): the queue stays as it
        // was, and the poll counts as failed — it once put that page into the table (review 2026-10-14, M14)
        if (!newTbody) throw new Error('an answer without the queue');
        pollAnswered(true);

        // Store new ETag for next poll
        const newETag = response.headers.get('ETag');
        if (newETag) {
            currentETag = newETag;
        }
        document.getElementById('song-list').replaceWith(newTbody);

        // Re-apply user's sort preference after table refresh
        reapplySort();

        // The rows are new: apply the search text again, and update the number of songs in the heading
        applyListFilters(document.getElementById('queueList'));
        // …and open again the cards of a phone that were open (cards.js)
        reopenCards(document.getElementById('song-list'));
        // …and choose again the request the keyboard works on (queue-keys.js)
        keepChosen();
    } catch (err) {
        console.error('[Polling] Refresh error:', err);
        pollAnswered(false);
    } finally {
        polling = false;
        nextPoll = pollAgain || !document.hidden ? setTimeout(refreshTable, pollAgain ? 0 : 3000) : null;
        pollAgain = false;
    }
}

nextPoll = setTimeout(refreshTable, 3000);
