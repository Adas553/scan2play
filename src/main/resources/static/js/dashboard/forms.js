/**
 * The forms of the DJ panel: the POST forms submitted by AJAX (a YouTube party), the Auto-Pilot switch — one setting for all the
 * DJ's windows — and the "copy" button of the party link.
 */
import { EVENTS, emit, on } from './events.js';
import { csrfHeaders, isYouTubeProvider } from './common.js';
import { refreshFallbackQueue, showFallbackImportResult } from './fallback-queue.js';

// ==========================================================================
// AJAX FORM INTERCEPTOR (YouTube only)
//
// When YouTube provider is active, most POST forms on the dashboard
// are submitted via fetch() to avoid a full page reload that would
// destroy the YouTube IFrame player.
//
// Excluded: logout, delete-account (page reload / redirect is expected).
// ==========================================================================

(function initAjaxFormInterceptor() {
    if (!isYouTubeProvider()) return;

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

        // Auto-Pilot toggle has its own AJAX handler — skip
        if (form.id === 'autoPilotForm') return;

        e.preventDefault();

        fetch(action, {
            method: 'POST',
            headers: csrfHeaders(),
            body: new FormData(form),
            redirect: 'manual'
        }).then(function(response) {
            // --- Party state toggle (end/start party) ---
            if (action.includes('/end-party') || action.includes('/start-party')) {
                const isEnding = action.includes('/end-party');
                const banner  = document.getElementById('party-closed-banner');
                const endForm = document.getElementById('end-party-form');
                if (banner)  banner.classList.toggle('d-none', !isEnding);
                if (endForm) endForm.classList.toggle('d-none', isEnding);
                return; // no flash needed for these buttons
            }

            // --- Fallback playlist save ---
            let importFailed = false;
            if (action.includes('/fallback-playlist') && response.headers.get('X-Fallback-Saved') === 'false') {
                // The server refused the link (a YouTube Mix) and kept the party's playlist: say why, touch nothing else
                importFailed = showFallbackImportResult(response, null);
            } else if (action.includes('/fallback-playlist')) {
                // Server returns the extracted playlist/video ID in X-Fallback-Id header
                // so we don't need to duplicate the URL parsing logic client-side.
                const extractedId = response.headers.get('X-Fallback-Id') || '';
                emit(EVENTS.FALLBACK_PLAYLIST_SAVED);
                refreshFallbackQueue();
                // Show/hide the stop button based on whether an ID was extracted
                const stopBtn = document.getElementById('fallbackStopBtn');
                if (stopBtn) {
                    stopBtn.classList.toggle('d-none', !extractedId);
                }
                importFailed = showFallbackImportResult(response, extractedId);
            }

            // Flash the submit button briefly as confirmation: green, or red when the playlist could not be imported
            const btn = form.querySelector('button[type="submit"]');
            if (btn) {
                const original = btn.textContent;
                const flashClass = importFailed ? 'btn-danger' : 'btn-success';
                btn.textContent = importFailed ? '✗' : '✓';
                btn.classList.add(flashClass);
                setTimeout(function() {
                    btn.textContent = original;
                    btn.classList.remove(flashClass);
                }, 1500);
            }
            // Clear "add item" forms after successful submission
            if (form.classList.contains('reset-on-success')) {
                form.reset();
            }
        }).catch(function(err) {
            console.error('[Dashboard] AJAX form error:', err);
        });
    });
})();

// ==========================================================================
// AUTO-PILOT TOGGLE
//
// The switch #autoToggle. YouTube: AJAX POST + local UI update (no reload). Spotify: standard form submit (page reload is fine).
// ==========================================================================

/** When the DJ last flipped the Auto-Pilot switch in this window (Date.now()), see the PLAYBACK_MODE_REPORTED listener. */
let playbackModeChangedHereAt = 0;

function submitAutoPilotToggle(checkbox) {
    if (!isYouTubeProvider()) {
        checkbox.form.submit();
        return;
    }

    const formData = new FormData(checkbox.form);
    // The state the switch shows now, not "toggle": a window whose switch showed an old state (Auto-Pilot changed on another
    // device) would otherwise have inverted the setting.
    const newMode = checkbox.checked ? 'AUTO' : 'MANUAL';
    formData.append('mode', newMode);
    playbackModeChangedHereAt = Date.now();

    fetch('/dj/dashboard/playback-mode', {
        method: 'POST',
        headers: csrfHeaders(),
        body: formData,
        redirect: 'manual'
    }).then(function() {
        setPlaybackMode(newMode);
        console.log('[Auto-Pilot] Mode set to: ' + newMode);
    }).catch(function(err) {
        console.error('[Auto-Pilot] Toggle error:', err);
        checkbox.checked = !checkbox.checked;
    });
}

/**
 * Makes this window follow an Auto-Pilot setting: the attribute youtube-autopilot.js reads (what happens when a track ends),
 * the switch; when it changed, EVENTS.PLAYBACK_MODE tells the screen wake lock and the player (switched on, it starts if idle).
 */
function setPlaybackMode(mode) {
    const tbody = document.getElementById('song-list');
    if (!tbody) return;
    const changed = tbody.getAttribute('data-playback-mode') !== mode;
    tbody.setAttribute('data-playback-mode', mode);
    const toggle = document.getElementById('autoToggle');
    if (toggle) toggle.checked = mode === 'AUTO';
    if (changed) emit(EVENTS.PLAYBACK_MODE, { mode: mode });
}

/**
 * The party's Auto-Pilot setting as the server says it (every lease answer carries it — youtube-autopilot.js): one setting for
 * all the DJ's windows, so a change made on another device reaches this one within a report. Before, a window knew only the value
 * it was loaded with (the queue poll brings a new one only when the guest queue changes), and a window that took the playback
 * over with a stale "off" left its player empty. An answer to a report sent before the DJ flipped the switch here is ignored: it
 * may still say the old value.
 */
on(EVENTS.PLAYBACK_MODE_REPORTED, function (report) {
    if (report.mode !== 'AUTO' && report.mode !== 'MANUAL') return;
    if (report.sentAt <= playbackModeChangedHereAt) return;
    setPlaybackMode(report.mode);
});

const autoToggle = document.getElementById('autoToggle');
if (autoToggle) autoToggle.addEventListener('change', function () { submitAutoPilotToggle(autoToggle); });

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
