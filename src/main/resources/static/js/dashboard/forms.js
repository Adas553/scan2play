/**
 * The forms of the DJ panel: the POST forms submitted by AJAX, and the "copy" button of the party link.
 */
import { EVENTS, emit } from './events.js';
import { csrfHeaders, showPartyActive } from './common.js';

// ==========================================================================
// AJAX FORM INTERCEPTOR
//
// Most POST forms on the dashboard are submitted via fetch(): a full page
// reload would take the DJ away from their place in the list (and, on a
// phone, fold the settings away).
//
// Excluded: logout, delete-account (page reload / redirect is expected).
// ==========================================================================

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
        if (action.includes('/logout') || action.includes('/delete-account')) return;

        e.preventDefault();

        fetch(action, {
            method: 'POST',
            headers: csrfHeaders(),
            body: new FormData(form),
            redirect: 'manual'
        }).then(function(response) {
            // A song played or skipped, or the queue cleared: the queue is fetched now, so the rows go at once
            if (action.includes('/dashboard/play') || action.includes('/dashboard/dismiss') || action.includes('/dashboard/clear-queue')) {
                emit(EVENTS.GUEST_QUEUE_CHANGED);
            }
            // A skip that went through: "Cofnij" for a few seconds (the server answers with a redirect, read as opaqueredirect)
            if (action.includes('/dashboard/dismiss') && accepted(response)) {
                offerUndo(form);
            }
            // A skipped request put back from the history: it is in the queue again and no longer in the history
            if (action.includes('/dashboard/restore')) {
                emit(EVENTS.GUEST_QUEUE_CHANGED);
                emit(EVENTS.HISTORY_CHANGED);
                return;   // the history is fetched again: no button left to flash
            }

            // --- Party state toggle (end/start party) ---
            if (action.includes('/end-party') || action.includes('/start-party')) {
                showPartyActive(!action.includes('/end-party'));   // the other windows follow with their next poll
                return; // no flash needed for these buttons
            }

            // Flash the submit button briefly as confirmation
            const btn = form.querySelector('button[type="submit"]');
            if (btn) {
                const original = btn.textContent;
                btn.textContent = '✓';
                btn.classList.add('btn-success');
                setTimeout(function() {
                    btn.textContent = original;
                    btn.classList.remove('btn-success');
                }, 1500);
            }
        }).catch(function(err) {
            console.error('[Dashboard] AJAX form error:', err);
        });
    });
})();

/** Whether the server took a form: it answers with a redirect to the dashboard (fetch with redirect: 'manual' sees opaqueredirect). */
function accepted(response) {
    return response.type === 'opaqueredirect' || response.ok;
}

// ==========================================================================
// "COFNIJ" AFTER "POMIŃ"
//
// A skip by mistake (a thumb on a phone) is put right at once: for UNDO_MS a bar at the bottom of the screen names the song and
// offers "Cofnij", which puts the request back in the queue (POST /dj/dashboard/restore). A later skip replaces the bar; after the
// bar is gone the history still offers "↩ Przywróć".
// ==========================================================================

const UNDO_MS = 8000;
let undoTimer = null;

function offerUndo(form) {
    const bar = document.getElementById('undoSkip');
    const idInput = form.querySelector('input[name="id"]');
    if (!bar || !idInput) return;
    const row = form.closest('tr[data-song-name]');
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
        clearTimeout(undoTimer);
        hideUndo();
        fetch('/dj/dashboard/restore', { method: 'POST', headers: csrfHeaders(), body: body, redirect: 'manual' })
            .then(function () {
                emit(EVENTS.GUEST_QUEUE_CHANGED);
                emit(EVENTS.HISTORY_CHANGED);
            })
            .catch(function (err) { console.error('[Dashboard] Undo error:', err); });
    });
}

// A select that saves as soon as the DJ picks (the party's vibe): requestSubmit, so the form goes through the submit listener
// above (fetch) — data-auto-submit instead of an inline onchange (CSP, review 5.1).
document.querySelectorAll('select[data-auto-submit]').forEach(function (select) {
    select.addEventListener('change', function () { select.form.requestSubmit(); });
});

// ==========================================================================
// COPY PARTY LINK
// ==========================================================================

function copyPartyLink(button) {
    const input = document.getElementById('partyLinkInput');
    navigator.clipboard.writeText(input.value).then(function() {
        const original = button.innerText;
        const copied = button.getAttribute('data-copied') || 'Copied!';
        button.innerText = copied;
        setTimeout(function() { button.innerText = original; }, 2000);
    }).catch(function() {
        // Fallback for non-HTTPS or denied permissions
        input.select();
        input.setSelectionRange(0, 99999);
        document.execCommand('copy');
    });
}

const copyButton = document.getElementById('copyPartyLinkBtn');
if (copyButton) copyButton.addEventListener('click', function () { copyPartyLink(copyButton); });
