/**
 * The panel's card "Nowości" (V80): what is new in Scan2Play. "✕" closes it at once and for good on the server — the DJ logs in on a
 * phone and a laptop, and the next panel on any of them has no card; a failed save leaves it closed on this page only. "Wysyłaj mi
 * nowości mailem" switches the news e-mails on (the organiser's consent, kept with its time) and says so in the card's place of the
 * button; a failed save puts the button back.
 *
 * Dependencies (DOM): <section id="newsCard"> with [data-news-close], #newsEmailsAsk with #newsEmailsOn, #newsEmailsDone, #queueList.
 */
import { csrfHeaders, partyCode } from './common.js';

(function initNews() {
    const card = document.getElementById('newsCard');
    if (!card) return;

    card.querySelector('[data-news-close]').addEventListener('click', function () {
        const body = new URLSearchParams({ partyCode: partyCode() });
        fetch('/dj/dashboard/news-dismiss', { method: 'POST', headers: csrfHeaders(), body: body, keepalive: true })
            .catch(function () { /* closed on this page; the next one shows it again */ });
        card.hidden = true;
        const queue = document.getElementById('queueList');
        if (queue) {
            queue.tabIndex = -1;
            queue.focus({ preventScroll: true });
        }
    });

    const ask = document.getElementById('newsEmailsAsk');
    const on = document.getElementById('newsEmailsOn');
    const done = document.getElementById('newsEmailsDone');
    if (!ask || !on || !done) return;
    on.addEventListener('click', function () {
        on.disabled = true;
        const body = new URLSearchParams({ partyCode: partyCode(), on: 'true' });
        const headers = Object.assign({ 'X-Requested-With': 'fetch' }, csrfHeaders());
        fetch('/dj/dashboard/news-emails', { method: 'POST', headers: headers, body: body })
            .then(function (response) {
                if (!response.ok) throw new Error(String(response.status));
                ask.hidden = true;
                done.hidden = false;
            })
            .catch(function () { on.disabled = false; });
    });
})();
