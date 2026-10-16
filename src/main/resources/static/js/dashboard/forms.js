/**
 * The forms of the DJ panel: the POST forms submitted by AJAX. ("Kopiuj" of the party link: dj-nav.js, as on every DJ page.)
 */
import { EVENTS, emit } from './events.js';
import { csrfHeaders, hideRefused, partyCode, showPartyActive, showRefused } from './common.js';

// ==========================================================================
// AJAX FORM INTERCEPTOR
//
// Most POST forms on the dashboard are submitted via fetch(): a full page
// reload would take the DJ away from their place in the list (and, on a
// phone, fold the settings away).
//
// Excluded: logout, delete-account, the hosts' link, the panel switcher, a pasted invitation and leaving a staff (V30) — a page
// reload / redirect is expected: the new link, the list or the other party's panel is shown at once.
//
// What a form does is chosen by its path, the whole of it (review 2026-10-14: a part of the address was looked for — "/staff-link"
// and "/staff-remove" matched no form at all, and a new path holding an old one would have taken its behaviour).
// ==========================================================================

/** The forms sent as a page load, not by fetch(). */
const RELOADS = new Set(['/dj/logout', '/dj/delete-account', '/dj/dashboard/host-link', '/dj/panel', '/dj/join', '/dj/staff/leave']);
/** The forms after which the queue is fetched at once (a song played, skipped, put back; the hosts' lists; a tip; ...). */
const CHANGE_THE_QUEUE = new Set(['/dj/dashboard/play', '/dj/dashboard/dismiss', '/dj/dashboard/clear-queue', '/dj/dashboard/restore',
    '/dj/dashboard/host-lists', '/dj/dashboard/guest-limit-reset', '/dj/dashboard/tip-count']);
/** The forms after which the history is fetched again. */
const CHANGE_THE_HISTORY = new Set(['/dj/dashboard/restore', '/dj/dashboard/clear-history']);
/** The forms whose button does not say ✓: what follows shows it (the history fetched again, the new count, the party's state). */
const NO_FLASH = new Set(['/dj/dashboard/restore', '/dj/dashboard/tip-count', '/dj/dashboard/clear-history', '/dj/end-party',
    '/dj/start-party']);

/** The form's path ("/dj/dashboard/play"), without the address and the query. */
function pathOf(form) {
    try {
        return new URL(form.action, location.href).pathname;
    } catch (e) {
        return '';
    }
}

(function initAjaxFormInterceptor() {
    document.addEventListener('submit', function(e) {
        const form = e.target.closest('form');
        if (!form || form.method.toLowerCase() !== 'post') return;
        if (!form.closest('.container')) return;

        // Respect confirm() dialogs — if onsubmit returned false, the browser
        // called preventDefault(). The event still bubbles, so we must check.
        if (e.defaultPrevented) return;

        // Allow these actions to do a full page reload
        const action = form.action || '';
        const path = pathOf(form);
        if (RELOADS.has(path)) return;

        e.preventDefault();

        // The button that sent the form waits for the answer: a double tap sends once (the review of 2026-10-14, W11 — two taps on
        // 💸 counted two tips). A form sent without a button (the vibe's select) is not held back: a second choice must be sent.
        const btn = e.submitter || form.querySelector('button[type="submit"]');
        const sender = e.submitter;
        if (sender) sender.disabled = true;

        fetch(action, {
            method: 'POST',
            headers: csrfHeaders(),
            body: withParty(new FormData(form)),
            redirect: 'manual'
        }).then(function(response) {
            // Refused — the session ran out (403 for the CSRF token), the plan, the access, a field the server does not take: ✗ and
            // why, and nothing that would show the change as made (the review of 2026-10-14, W1: ✓ while nothing was saved)
            if (!accepted(response)) {
                refused(form, btn);
                return;
            }
            hideRefused();
            const error = form.querySelector('[data-form-error]');
            if (error) error.hidden = true;

            // The queue fetched now, so the change shows at once: a song played, skipped or put back from the history, the queue
            // cleared, the hosts' lists saved (V29: a song on the wish list gets its ⭐), the party's day of guest requests started
            // again (W4: the poll says so at once, X-Guest-Limits — the warning goes), a tip counted or taken back (V28)
            if (CHANGE_THE_QUEUE.has(path)) emit(EVENTS.GUEST_QUEUE_CHANGED);
            // A request put back (it is no longer in the history) or the history cleared (what is left of it: a skip that still
            // keeps its song out): the history fetched again
            if (CHANGE_THE_HISTORY.has(path)) emit(EVENTS.HISTORY_CHANGED);
            // A skip or a "Zagrane" that went through: "Cofnij" for a few seconds (the server answers with a redirect, read as
            // opaqueredirect) — "Zagrane" is the button tapped most, so the one tapped by mistake most (the owner, 2026-10-10)
            if (path === '/dj/dashboard/dismiss' || path === '/dj/dashboard/play') {
                offerUndo(form, path === '/dj/dashboard/play' ? 'play' : 'dismiss');
            }
            // The party ended or started: the panel shows it (the other windows follow with their next poll)
            if (path === '/dj/end-party' || path === '/dj/start-party') showPartyActive(path === '/dj/start-party');
            if (NO_FLASH.has(path)) return;

            flash(btn, true);   // the submit button says ✓ for a moment
        }).catch(function(err) {
            // the request never reached the server (no network): as refused — nothing was saved
            console.error('[Dashboard] AJAX form error:', err);
            refused(form, btn);
        }).finally(function() {
            if (sender) sender.disabled = false;
        });
    });
})();

/**
 * The button says ✓ (saved) or ✗ (not) for a moment, then its own label again — its markup, not only its text ("Zakończ imprezę"
 * has a long and a short label in spans). A second answer within the moment keeps the first label.
 */
function flash(btn, ok) {
    if (!btn) return;
    if (btn.s2pLabel === undefined) btn.s2pLabel = btn.innerHTML;
    const style = ok ? 'btn-success' : 'btn-danger';
    btn.classList.remove('btn-success', 'btn-danger');
    btn.textContent = ok ? '✓' : '✗';
    btn.classList.add(style);
    clearTimeout(btn.s2pFlash);
    btn.s2pFlash = setTimeout(function() {
        btn.innerHTML = btn.s2pLabel;
        btn.s2pLabel = undefined;
        btn.classList.remove(style);
    }, 1500);
}

/**
 * A form the server refused, or that never reached it: ✗ on its button, and why — the form's own note when it has one (the DJ's
 * profiles: not a profile on that site), else the page's notice (fragments/components :: formRefused): not saved, copy what you
 * typed and reload. The page is not reloaded by itself: what the DJ typed would go with it.
 */
function refused(form, btn) {
    const error = form.querySelector('[data-form-error]');
    if (error) {
        error.hidden = false;
    } else {
        showRefused();
    }
    flash(btn, false);
}

document.querySelectorAll('[data-form-refused-reload]').forEach(function (button) {
    button.addEventListener('click', function () { window.location.reload(); });
});
document.querySelectorAll('[data-form-refused-close]').forEach(function (button) {
    button.addEventListener('click', hideRefused);
});

/**
 * The party the page shows goes with every form (V32): the server works on that party and checks the person's permissions there —
 * two tabs on two parties each do what they show (until 2026-10-10 the session's one party decided, and a tab still showing
 * another party's queue cleared the person's own).
 */
function withParty(body) {
    const party = partyCode();
    if (party && !body.has('partyCode')) body.append('partyCode', party);
    return body;
}

/** Whether the server took a form: it answers with a redirect to the dashboard (fetch with redirect: 'manual' sees opaqueredirect). */
function accepted(response) {
    return response.type === 'opaqueredirect' || response.ok;
}

// ==========================================================================
// "COFNIJ" AFTER "POMIŃ" OR "ZAGRANE"
//
// A skip or a "Zagrane" by mistake (a thumb on a phone) is put right at once: for UNDO_MS a bar at the bottom of the screen says
// which ("Pominięto:" / "Zagrane:"), names the song and offers "Cofnij", which puts the request back in the queue (POST
// /dj/dashboard/restore — a "Zagrane" taken back no longer counts as played, so the AI no longer refuses the song as played). A later
// one replaces the bar; after the bar is gone the history still offers "↩ Przywróć".
// ==========================================================================

const UNDO_MS = 8000;
let undoTimer = null;

function offerUndo(form, kind) {
    const bar = document.getElementById('undoSkip');
    const idInput = form.querySelector('input[name="id"]');
    if (!bar || !idInput) return;
    const row = form.closest('tr[data-song-name]');
    bar.querySelectorAll('[data-undo-label]').forEach(function (label) {
        label.hidden = label.getAttribute('data-undo-label') !== kind;
    });
    bar.querySelector('[data-undo-song]').textContent = row ? row.getAttribute('data-song-name') : '';
    bar.setAttribute('data-undo-id', idInput.value);
    bar.hidden = false;
    clearTimeout(undoTimer);
    undoTimer = setTimeout(hideUndo, UNDO_MS);
}

function hideUndo() {
    const bar = document.getElementById('undoSkip');
    if (bar) bar.hidden = true;
}

const undoButton = document.querySelector('#undoSkip [data-undo-button]');
if (undoButton) {
    undoButton.addEventListener('click', function () {
        const bar = document.getElementById('undoSkip');
        const body = new FormData();
        body.append('id', bar.getAttribute('data-undo-id'));
        withParty(body);
        clearTimeout(undoTimer);
        hideUndo();
        fetch('/dj/dashboard/restore', { method: 'POST', headers: csrfHeaders(), body: body, redirect: 'manual' })
            .then(function (response) {
                // refused (the session ran out): say so — the song is not back (it said nothing before)
                if (!accepted(response)) {
                    showRefused();
                    return;
                }
                emit(EVENTS.GUEST_QUEUE_CHANGED);
                emit(EVENTS.HISTORY_CHANGED);
            })
            .catch(function (err) {
                console.error('[Dashboard] Undo error:', err);
                showRefused();
            });
    });
}

// A select that saves as soon as the DJ picks (the party's vibe): requestSubmit, so the form goes through the submit listener
// above (fetch) — data-auto-submit instead of an inline onchange (CSP, review 5.1).
document.querySelectorAll('select[data-auto-submit]').forEach(function (select) {
    select.addEventListener('change', function () { select.form.requestSubmit(); });
});
