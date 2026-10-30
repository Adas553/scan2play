/**
 * The cards of "Ustawienia imprezy" whose server answer is the card itself ([data-saves-in-place]: "Godziny imprezy", V55; "Kiosk dla
 * gości", V56): a form of such a card is sent in the background (X-Requested-With: fetch) and the server answers with the card alone —
 * the same id —; its folded line's state and its body take the old ones' place, and the card is held where it was on the screen. A page
 * load scrolled to the card: the screen jumped (the owner, 2026-10-30, twice).
 *
 * As every card's "Zapisz" (forms.js flash): "✓" for a moment on the button that sent it — the same id in the new card —, or "✗" when
 * the card says why not ([data-card-error], focused: read out). A refused or lost answer leaves the card as it was and shows the
 * page's notice (common.js showRefused). A form with data-confirm asks first (dj-nav.js, before this). forms.js leaves these forms
 * alone (RELOADS): without this script they are page loads back to the card.
 *
 * Dependencies (DOM): <details data-saves-in-place id=…> with .s2p-fold-state and .s2p-fold-body; the forms inside its body.
 */
import { csrfHeaders, hideRefused, showRefused } from './common.js';
import { flash } from './forms.js';

document.addEventListener('submit', function (e) {
    const form = e.target.closest('form');
    const card = form ? form.closest('details[data-saves-in-place]') : null;
    if (!card || e.defaultPrevented) return;   // not such a card's, or a confirm() said no
    e.preventDefault();
    const sender = e.submitter;
    if (sender) sender.disabled = true;
    const headers = csrfHeaders();
    headers['X-Requested-With'] = 'fetch';
    fetch(form.action, { method: 'POST', headers: headers, body: new FormData(form) })
        .then(function (response) {
            if (!response.ok || response.redirected) throw new Error('HTTP ' + response.status);
            return response.text();
        })
        .then(function (html) {
            const answer = document.createElement('template');
            answer.innerHTML = html.trim();
            const fresh = answer.content.querySelector('#' + card.id);
            if (!fresh) throw new Error('an answer without the card');   // a Wi-Fi login page, the login page
            // the card's own element stays (the browser keeps the page's place by it): its line's state and its body are the
            // server's, and the card is held where it was on the screen
            const top = card.getBoundingClientRect().top;
            const state = card.querySelector('.s2p-fold-state');
            const freshState = fresh.querySelector('.s2p-fold-state');
            if (state && freshState) state.replaceWith(freshState);
            card.querySelector('.s2p-fold-body').replaceWith(fresh.querySelector('.s2p-fold-body'));
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
