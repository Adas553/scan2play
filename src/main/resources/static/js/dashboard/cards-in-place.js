/**
 * The cards of "Ustawienia imprezy" answered in place ([data-saves-in-place]: "Godziny imprezy", V55; "Kiosk dla gości", V56; the hosts'
 * link of "Lista gospodarzy", V29): a form of such a card marked [data-in-place] is sent in the background (X-Requested-With: fetch).
 * The server answers with the card alone (the same id), or with the page "Ustawienia imprezy" its redirect leads to — the card is
 * taken from it either way. The card's folded line takes the answer's state, and its body — or only the part the form names
 * (data-in-place="hostLinkBox": the hosts' link, so the lists typed beside it stay as typed) — the answer's; the card is held where it
 * was on the screen. A page load scrolled to the card: the screen jumped (the owner, 2026-10-30, three times).
 *
 * As every card's "Zapisz" (forms.js flash): "✓" for a moment on the button that sent it — the same id in the new card —, or "✗" when
 * the card says why not ([data-card-error], focused: read out). A refused or lost answer (an answer without the card: the login page,
 * a Wi-Fi's page) leaves the card as it was and shows the page's notice (common.js showRefused). A form with data-confirm asks first
 * (dj-nav.js, before this). forms.js leaves these forms alone (RELOADS): without this script they are page loads back to the card.
 *
 * Dependencies (DOM): <details data-saves-in-place id=…> with .s2p-fold-state and .s2p-fold-body; its forms [data-in-place].
 */
import { csrfHeaders, hideRefused, showRefused } from './common.js';
import { flash } from './forms.js';

document.addEventListener('submit', function (e) {
    const form = e.target.closest('form[data-in-place]');
    const card = form ? form.closest('details[data-saves-in-place]') : null;
    if (!card || e.defaultPrevented) return;   // not such a card's, or a confirm() said no
    e.preventDefault();
    const sender = e.submitter;
    if (sender) sender.disabled = true;
    const headers = csrfHeaders();
    headers['X-Requested-With'] = 'fetch';
    fetch(form.action, { method: 'POST', headers: headers, body: new FormData(form) })
        .then(function (response) {
            if (!response.ok) throw new Error('HTTP ' + response.status);
            return response.text();   // the card, or the settings page a redirect led to: the card must be in it
        })
        .then(function (html) {
            const answer = document.createElement('template');
            answer.innerHTML = html.trim();
            const fresh = answer.content.querySelector('#' + card.id);
            if (!fresh) throw new Error('an answer without the card');   // a Wi-Fi login page, the login page
            // the card's own element stays (the browser keeps the page's place by it): its line's state and its body are the
            // server's, and the card is held where it was on the screen
            const part = form.dataset.inPlace ? '#' + form.dataset.inPlace : '.s2p-fold-body';
            const freshPart = fresh.querySelector(part);
            if (!freshPart || !card.querySelector(part)) throw new Error('an answer without ' + part);
            const top = card.getBoundingClientRect().top;
            const state = card.querySelector('.s2p-fold-state');
            const freshState = fresh.querySelector('.s2p-fold-state');
            if (state && freshState) state.replaceWith(freshState);
            card.querySelector(part).replaceWith(freshPart);
            card.open = true;
            window.scrollBy(0, card.getBoundingClientRect().top - top);
            hideRefused();
            const why = card.querySelector('[data-card-error]');
            const again = sender && sender.id ? card.querySelector('#' + sender.id) : null;
            if (why) {
                flash(again, false);
                why.focus({ preventScroll: true });
            } else if (again) {
                flash(again, true);
                again.focus({ preventScroll: true });
            }
        })
        .catch(function (err) {
            console.error('[Settings] not saved:', err);
            flash(sender, false);
            showRefused();
        })
        .finally(function () {
            if (sender && sender.isConnected) sender.disabled = false;
        });
});
