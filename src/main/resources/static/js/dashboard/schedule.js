/**
 * The card "Godziny imprezy" of the page "Ustawienia imprezy" (V55).
 *
 * The quick choices ("dziś 20:00", "jutro 3:00") fill the day and the time of their moment — the day from the server's today and
 * tomorrow in Polish time (the form's data-today / data-tomorrow), not this device's clock. Nothing is sent: "Zapisz" saves.
 *
 * "Zapisz" and "✕" are sent in the background (X-Requested-With: fetch): the server answers with the card alone — the plan as saved,
 * or why not under "Zapisz" —; its line's state and its body take the old ones' place, and the page stays where it is (a page load
 * scrolled to the card: the screen jumped). As every card's: "✓" or "✗" on "Zapisz" for a moment (forms.js flash).
 * A refused or lost answer leaves the card as it was and shows the page's notice (common.js showRefused). forms.js leaves these forms
 * alone (RELOADS): without this script they are page loads.
 *
 * Dependencies (DOM): <details id="partyHoursCard"> with #partyHoursForm (data-today, data-tomorrow), the cancel forms, buttons
 * [data-hours-fill="open|close"] with data-day ("today" / "tomorrow") and data-time ("03:00"); the fields #openDate, #openTime,
 * #closeDate, #closeTime; #partyHoursSave, and #partyHoursError in a refusal.
 */
import { csrfHeaders, hideRefused, showRefused } from './common.js';
import { flash } from './forms.js';

document.addEventListener('click', function (e) {
    const button = e.target.closest('#partyHoursCard [data-hours-fill]');
    const form = document.getElementById('partyHoursForm');
    if (!button || !form) return;
    const kind = button.dataset.hoursFill;
    const date = document.getElementById(kind + 'Date');
    const time = document.getElementById(kind + 'Time');
    if (!date || !time) return;
    date.value = button.dataset.day === 'tomorrow' ? form.dataset.tomorrow : form.dataset.today;
    time.value = button.dataset.time;
});

document.addEventListener('submit', function (e) {
    const form = e.target.closest('form');
    const card = form ? form.closest('#partyHoursCard') : null;
    if (!card || e.defaultPrevented) return;   // not this card's, or a confirm() said no
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
            const fresh = answer.content.querySelector('#partyHoursCard');
            if (!fresh) throw new Error('an answer without the card');   // a Wi-Fi login page, the login page
            // the card's own element stays (the browser keeps the page's place by it): its line's state and its body are the
            // server's, and the card is held where it was on the screen
            const top = card.getBoundingClientRect().top;
            card.querySelector('.s2p-fold-state').replaceWith(fresh.querySelector('.s2p-fold-state'));
            card.querySelector('.s2p-fold-body').replaceWith(fresh.querySelector('.s2p-fold-body'));
            card.open = true;
            window.scrollBy(0, card.getBoundingClientRect().top - top);
            hideRefused();
            // as every card: "✓" on "Zapisz" for a moment, or "✗" and why under it — focused (read out), the page not moved
            const save = card.querySelector('#partyHoursSave');
            const why = card.querySelector('#partyHoursError');
            if (why) {
                flash(save, false);
                why.focus({ preventScroll: true });
            } else if (form.id === 'partyHoursForm') {
                flash(save, true);
                save.focus({ preventScroll: true });
            }
        })
        .catch(function (err) {
            console.error('[Party hours] not saved:', err);
            flash(sender, false);
            showRefused();
        })
        .finally(function () {
            if (sender && sender.isConnected) sender.disabled = false;
        });
});
