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
        }).then(function() {
            // A song played or skipped, or the queue cleared: the queue is fetched now, so the rows go at once
            if (action.includes('/dashboard/play') || action.includes('/dashboard/dismiss') || action.includes('/dashboard/clear-queue')) {
                emit(EVENTS.GUEST_QUEUE_CHANGED);
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
