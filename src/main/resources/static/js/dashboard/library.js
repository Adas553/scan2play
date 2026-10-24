/**
 * "Twoja biblioteka" on the page "Ustawienia imprezy" (settings.html, V41): the chosen file is sent as it is, as the request's body
 * (DjLibraryController — not a form's upload, which the server would read before it checks who sent it). The server answers in one
 * sentence: shown under the form; after a library was saved the page loads again, so its state and "Usuń bibliotekę" are the server's.
 *
 * Dependencies (DOM): <form id="libraryForm"> with the party code, a file input and data-sending / data-refused; <p id="libraryAnswer">.
 */
import { csrfHeaders } from './common.js';

/** The answers that carry the server's own sentence: saved, too large, not a library. */
const SENTENCES = new Set([200, 413, 422]);

(function initLibraryUpload() {
    const form = document.getElementById('libraryForm');
    if (!form) return;
    const answer = document.getElementById('libraryAnswer');

    function say(text, refused) {
        answer.textContent = text;
        answer.classList.toggle('text-danger', refused);
        answer.classList.toggle('text-success', !refused);
        answer.hidden = false;
    }

    form.addEventListener('submit', async function (e) {
        e.preventDefault();   // forms.js leaves a form whose submit was taken here
        const file = form.querySelector('input[type="file"]').files[0];
        if (!file) return;
        const button = form.querySelector('button[type="submit"]');
        const partyCode = form.querySelector('input[name="partyCode"]').value;
        button.disabled = true;
        say(form.dataset.sending, false);
        try {
            const response = await fetch(form.action + '?partyCode=' + encodeURIComponent(partyCode), {
                method: 'POST',
                headers: Object.assign({ 'Content-Type': 'application/octet-stream' }, csrfHeaders()),
                body: file
            });
            const text = SENTENCES.has(response.status) ? await response.text() : '';
            if (response.ok) {
                say(text, false);
                location.reload();
                return;
            }
            say(text || form.dataset.refused, true);
        } catch (err) {
            say(form.dataset.refused, true);
        }
        button.disabled = false;
    });
})();
