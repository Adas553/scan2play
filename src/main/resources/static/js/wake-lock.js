/**
 * Screen Wake Lock — prevents the mobile screen from sleeping during a DJ session.
 *
 * The lock is acquired when Auto-Pilot is ON and released when it is turned OFF.
 * Browsers automatically drop the lock whenever the page becomes hidden (tab
 * switch, phone lock screen, etc.), so we re-acquire it on every `visibilitychange`
 * as long as Auto-Pilot is still enabled.
 *
 * Graceful degradation: when the Screen Wake Lock API is unavailable (older
 * browsers, non-secure contexts) the script does nothing.
 *
 * It follows the Auto-Pilot switch through the dashboard's event 's2p:playback-mode'
 * ({mode: 'AUTO'|'MANUAL'}, js/dashboard/events.js, PLAYBACK_MODE) — a classic script,
 * so it listens by the event's name; nothing calls it.
 */
(function () {
    'use strict';

    // ---- Graceful degradation: API unavailable ----
    if (!('wakeLock' in navigator)) {
        console.info('[WakeLock] Screen Wake Lock API not supported — nothing to do.');
        return;
    }

    // ---- State ----
    /** The live WakeLockSentinel, or null when not held. */
    let sentinel = null;

    /**
     * Whether the wake lock *should* be held right now.
     * Survives transient browser-side releases (visibility changes, errors).
     */
    let desired = false;

    // ---- Core ----

    /**
     * Attempts to acquire the wake lock.
     * Safe to call repeatedly — exits early when already held or when the
     * document is not visible (the API throws in that case anyway).
     */
    async function acquire() {
        if (sentinel !== null) return;           // already held
        if (document.visibilityState !== 'visible') return; // browser would reject it

        try {
            sentinel = await navigator.wakeLock.request('screen');

            sentinel.addEventListener('release', function () {
                sentinel = null;
                // Re-acquire only when the release was browser-triggered
                // (e.g. tab hidden then restored) rather than our own call.
                if (desired && document.visibilityState === 'visible') {
                    acquire();
                }
            });

            console.info('[WakeLock] Screen Wake Lock acquired.');
        } catch (err) {
            sentinel = null;
            // NotAllowedError = non-secure context or permission denied — not a bug.
            console.warn('[WakeLock] Could not acquire wake lock:', err.name, err.message);
        }
    }

    /**
     * Releases the wake lock and clears the desired flag so that the 'release'
     * event listener does not attempt a re-acquire.
     */
    function release() {
        desired = false;
        if (sentinel !== null) {
            sentinel.release();
            // sentinel is set to null by the 'release' event handler above.
        }
    }

    // ---- Visibility change: re-acquire after returning to the tab ----
    // Browsers unconditionally drop the lock when the page becomes hidden.
    // Polling (dashboard.js, 3 s) is NOT affected — fetch() continues in the
    // background regardless of the wake lock state.
    document.addEventListener('visibilitychange', function () {
        if (desired && document.visibilityState === 'visible') {
            acquire();
        }
    });

    // ---- Follow the Auto-Pilot switch ----

    /**
     * Syncs wake lock state with the Auto-Pilot setting: on → request/keep the lock; off → release it.
     */
    document.addEventListener('s2p:playback-mode', function (e) {
        if (e.detail && e.detail.mode === 'AUTO') {
            desired = true;
            acquire();
        } else {
            release();
        }
    });

    // ---- Initial sync on page load ----
    // Scripts are placed at the bottom of <body>, so the DOM is fully parsed here.
    (function syncOnLoad() {
        const toggle = document.getElementById('autoToggle');
        if (toggle && toggle.checked) {
            desired = true;
            acquire();
        }
    })();

})();

