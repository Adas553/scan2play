/**
 * A new organiser's welcome card above the queue (V48): "Pierwszy raz? Napisz do nas". "✕" closes it at once; "Napisz do nas" opens
 * "Pomoc i kontakt" (Bootstrap's modal, data-bs-toggle) and closes it once that window closes — the card stays behind the window, not
 * gone from under it. Either way it is closed for good on the server, not in this browser: the DJ logs in on a phone and a laptop, and
 * the next panel on any of them has no card. A failed save leaves the card closed on this page only; it comes back on the next one.
 *
 * Dependencies (DOM): <section id="welcomeCard"> with [data-welcome-close] buttons ("✕" and #welcomeWrite), #feedbackModal,
 * #queueList.
 */
import { csrfHeaders, partyCode } from './common.js';

(function initWelcome() {
    const card = document.getElementById('welcomeCard');
    if (!card) return;
    let sent = false;

    function dismissOnTheServer() {
        if (sent) return;
        sent = true;
        const body = new URLSearchParams({ partyCode: partyCode() });
        // keepalive: "Napisz do nas" may be followed by the guide's page at once
        fetch('/dj/dashboard/welcome-dismiss', { method: 'POST', headers: csrfHeaders(), body: body, keepalive: true })
            .catch(function () { /* the card is closed on this page; the next one shows it again */ });
    }

    /** The card away, the focus on the queue under it (the button that had it is gone with the card). */
    function hide() {
        card.hidden = true;
        const queue = document.getElementById('queueList');
        if (queue) {
            queue.tabIndex = -1;
            queue.focus({ preventScroll: true });
        }
    }

    card.querySelectorAll('[data-welcome-close]').forEach(function (button) {
        button.addEventListener('click', function () {
            dismissOnTheServer();
            const modal = button.dataset.bsTarget ? document.querySelector(button.dataset.bsTarget) : null;
            if (modal) modal.addEventListener('hidden.bs.modal', hide, { once: true });
            else hide();
        });
    });
})();
