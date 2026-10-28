// The print page of the party's QR code (templates/qr-print.html): its "Print" button opens the browser's print window, where
// "Save as PDF" makes a file too. A file, not an inline handler: the pages run under a Content-Security-Policy.
//
// The editor (#posterForm, V46 — for whom may change the party's card "Impreza"): what is typed or ticked shows on the poster or the
// cards at once — the DJ's own heading in place of ours, the DJ's line, the parts of the DJ's shown or left off (data-poster-part;
// the DJ's logo left off brings ours back, and the cards' code its full size). "Zapisz" keeps it with the party in the background
// (POST /dj/qr-print/settings, 204); the print's language is written by the server, so a new one is saved and the page loads again.
(function () {
    'use strict';
    const button = document.getElementById('printBtn');
    if (button) button.addEventListener('click', function () { window.print(); });

    const form = document.getElementById('posterForm');
    if (!form) return;
    const answer = document.getElementById('posterAnswer');
    const save = document.getElementById('posterSave');

    function all(selector) { return Array.prototype.slice.call(document.querySelectorAll(selector)); }

    function showHeading() {
        const own = form.elements.heading.value.replace(/\s+/g, ' ').trim();
        all('[data-poster-heading]').forEach(function (el) { el.textContent = own; el.hidden = !own; });
        all('[data-poster-ours-heading]').forEach(function (el) { el.hidden = !!own; });
    }

    function showLine() {
        const own = form.elements.line.value.replace(/\s+/g, ' ').trim();
        all('[data-poster-line]').forEach(function (el) { el.textContent = own; el.hidden = !own; });
    }

    function showPart(box) {
        const part = box.dataset.posterToggle;
        all('[data-poster-part="' + part + '"]').forEach(function (el) { el.hidden = !box.checked; });
        if (part === 'logo') {
            all('[data-poster-part="ours"]').forEach(function (el) { el.hidden = box.checked; });
            all('.poster, .card').forEach(function (el) { el.classList.toggle('with-logo', box.checked); });
        }
    }

    function say(text, refused) {
        answer.textContent = text;
        answer.classList.toggle('refused', !!refused);
    }

    async function send() {
        const headers = { 'Content-Type': 'application/x-www-form-urlencoded' };
        const token = document.querySelector('meta[name="_csrf"]');
        const header = document.querySelector('meta[name="_csrf_header"]');
        if (token && header) headers[header.content] = token.content;
        const response = await fetch(form.action, { method: 'POST', headers: headers, body: new URLSearchParams(new FormData(form)),
            redirect: 'manual' });
        if (response.status !== 204) throw new Error('not saved: ' + response.status);
    }

    form.elements.heading.addEventListener('input', showHeading);
    form.elements.line.addEventListener('input', showLine);
    all('[data-poster-toggle]').forEach(function (box) {
        box.addEventListener('change', function () { showPart(box); });
    });

    form.elements.language.addEventListener('change', async function () {
        say(form.dataset.sending, false);
        try {
            await send();
            location.reload();
        } catch (e) {
            say(form.dataset.refused, true);
        }
    });

    form.addEventListener('submit', async function (e) {
        e.preventDefault();
        save.disabled = true;
        say(form.dataset.sending, false);
        try {
            await send();
            say(form.dataset.saved, false);
        } catch (err) {
            say(form.dataset.refused, true);
        }
        save.disabled = false;
    });
})();
