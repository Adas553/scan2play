// The print page of the party's QR code (templates/qr-print.html): its "Print" button opens the browser's print window, where
// "Save as PDF" makes a file too. A file, not an inline handler: the pages run under a Content-Security-Policy.
//
// The editor (#posterForm, V46 — for whom may change the party's card "Impreza"): what is typed or ticked shows on the poster or the
// cards at once — the DJ's own heading in place of ours, the DJ's line, the parts of the DJ's shown or left off (data-poster-part;
// the DJ's logo left off brings ours back, and the cards' code its full size). "Zapisz" keeps it with the party in the background
// (POST /dj/qr-print/settings, 204); the print's language is written by the server, so a new one is saved and the page loads again.
//
// The look (V47): the theme is the class of <body> (qr-print.css), the DJ's colour its data-accent. A colour is made readable on the
// theme's paper here — darkened on a light paper, lightened on a dark one, until it has a contrast of 4.5:1 (WCAG AA) — and set as
// --accent; none, and the theme's own applies. On every load, for whoever prints, and at once while the editor changes them.
(function () {
    'use strict';
    const button = document.getElementById('printBtn');
    if (button) button.addEventListener('click', function () { window.print(); });

    function rgb(hex) {
        const m = /^#?([0-9a-f]{6})$/i.exec((hex || '').trim());
        if (!m) return null;
        const n = parseInt(m[1], 16);
        return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
    }

    function luminance(c) {
        const lin = c.map(function (v) { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); });
        return 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2];
    }

    function contrast(a, b) {
        const x = luminance(a), y = luminance(b);
        return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
    }

    function hex(c) {
        return '#' + c.map(function (v) { return Math.round(v).toString(16).padStart(2, '0'); }).join('');
    }

    /** {@code colour} as it reads on {@code paper}: as it is, or mixed towards black / white step by step until 4.5:1 (and a little). */
    function readable(colour, paper) {
        const c = rgb(colour), p = rgb(paper);
        if (!c || !p) return colour;
        const target = luminance(p) > 0.5 ? [0, 0, 0] : [255, 255, 255];
        for (let t = 0; t <= 1.0001; t += 0.05) {
            const mixed = c.map(function (v, i) { return v + (target[i] - v) * t; });
            if (contrast(mixed, p) >= 4.6) return hex(mixed);   // 4.6: a margin over AA's 4.5 for the rounding to whole values
        }
        return hex(target);
    }

    function showLook(theme, accent) {
        const body = document.body;
        if (theme) {
            body.className = body.className.replace(/\btheme-\w+\b/g, '').trim() + ' theme-' + theme.toLowerCase();
        }
        const paper = getComputedStyle(body).getPropertyValue('--paper').trim();
        if (accent) body.style.setProperty('--accent', readable(accent, paper));
        else body.style.removeProperty('--accent');
    }

    showLook(null, document.body.dataset.accent);

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

    const picker = document.getElementById('posterAccentPicker');
    const own = document.getElementById('posterAccentOwn');

    function chosenAccent() {
        const checked = form.querySelector('input[name="accent"]:checked');
        return checked ? checked.value : '';
    }

    function changeLook() { showLook(form.elements.theme.value, chosenAccent()); }

    form.elements.theme.addEventListener('change', changeLook);
    Array.prototype.forEach.call(form.querySelectorAll('input[name="accent"]'), function (radio) {
        radio.addEventListener('change', changeLook);
    });
    if (picker && own) {
        picker.addEventListener('input', function () {
            own.value = picker.value;
            own.checked = true;
            changeLook();
        });
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
