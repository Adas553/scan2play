// The print page of the party's QR code (templates/qr-print.html): its "Print" button opens the browser's print window, where
// "Save as PDF" makes a file too. A file, not an inline handler: the pages run under a Content-Security-Policy.
//
// The editor (#posterForm, V46 — for whom may change the party's card "Impreza"): what is typed or ticked shows on the poster or the
// cards at once — the DJ's own heading in place of ours, the DJ's line, the parts of the DJ's shown or left off (data-poster-part;
// the DJ's logo left off brings ours back, and the cards' code its full size). "Zapisz" keeps it with the party in the background
// (POST /dj/qr-print/settings, 204); the print's language is written by the server, so a new one is saved and the page loads again.
// What is changed and not saved yet says so, and goes along to the other layouts (sessionStorage, for this tab), to compare them.
//
// The number of tables (V50): the cards and the table tents (data-print-item) as many as the tables, numbered 1 … N — the first one
// copied —, at once; empty, eight cards and one tent without numbers. The poster has none. "Numer pierwszego stołu" (never sent nor
// saved: the next print starts from 1) numbers them from it — 22 from 22 are tables 22 … 43, a reprint of one is 1 from 16 —, and
// the summary under it says which tables print on how many pages.
//
// The look (V47): the theme is the class of <body> (qr-print.css), the DJ's colour its data-accent. A colour is made readable on the
// theme's paper here — darkened on a light paper, lightened on a dark one, until it has a contrast of 4.5:1 (WCAG AA) — and set as
// --accent; none, and the theme's own applies. The DJ's paper (V53, data-paper): a light colour made a light tint, a dark one a dark
// paper, set as --paper — the words on it dark or light, the heading's colour (the theme's too) readable on it. On every load, for whoever prints, and at once while the editor
// changes them.
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

    /** {@code colour} as a light paper (V53): mixed towards white until it is as light as a pale paper — little ink, dark words read. */
    function light(colour) {
        const c = rgb(colour);
        if (!c) return null;
        for (let t = 0; t <= 1.0001; t += 0.02) {
            const mixed = c.map(function (v) { return v + (255 - v) * t; });
            if (luminance(mixed) >= 0.85) return hex(mixed);
        }
        return '#ffffff';
    }

    /** {@code colour} as a dark paper (V53): darkened only until light words read on it — the quiet ones at 4.5:1 too. */
    function deep(colour) {
        const c = rgb(colour);
        for (let t = 0; t <= 1.0001; t += 0.02) {
            const mixed = c.map(function (v) { return v * (1 - t); });
            if (luminance(mixed) <= 0.09) return hex(mixed);
        }
        return '#000000';
    }

    /** A colour the DJ picks for the paper is a dark paper below this luminance, else a light tint. */
    const DARK_PAPER = 0.25;

    function showLook(theme, accent, font, paperColour) {
        const body = document.body;
        if (theme) {
            body.className = body.className.replace(/\btheme-\w+\b/g, '').trim() + ' theme-' + theme.toLowerCase();
        }
        if (font) {   // the heading's font (V51): the theme's has no class
            body.className = body.className.replace(/\bfont-\w+\b/g, '').trim() + (font === 'THEME' ? '' : ' font-' + font.toLowerCase());
        }
        // the DJ's paper (V53): a light colour a light tint, the words on it dark (the club's white ink would vanish); a dark one a
        // dark paper, the words light — and the classes for the rest: words off the classic paper's edges, the logo on a badge
        const picked = paperColour ? rgb(paperColour) : null;
        const dark = !!picked && luminance(picked) < DARK_PAPER;
        const tint = picked ? (dark ? deep(paperColour) : light(paperColour)) : null;
        ['--paper', '--ink', '--muted'].forEach(function (name) { body.style.removeProperty(name); });
        body.classList.toggle('paper-own', !!tint);
        body.classList.toggle('paper-dark', dark);
        if (tint) {
            body.style.setProperty('--paper', tint);
            body.style.setProperty('--ink', dark ? '#f5f5f7' : '#1a1a1a');
            body.style.setProperty('--muted', dark ? '#c9cad3' : '#555555');
        }
        const paper = getComputedStyle(body).getPropertyValue('--paper').trim();
        // the heading's colour readable on the paper: the DJ's, or on a paper of the DJ's the theme's own too (the club's cyan on pale)
        // (a theme's near-black heading on a dark paper: the light words' colour, not a grey made of it)
        let base = accent || (tint ? getComputedStyle(body).getPropertyValue('--accent-base').trim() : '');
        if (!accent && dark && rgb(base) && luminance(rgb(base)) < 0.05) base = '#f5f5f7';
        if (base) body.style.setProperty('--accent', readable(base, paper));
        else body.style.removeProperty('--accent');
    }

    showLook(null, document.body.dataset.accent, null, document.body.dataset.paper);

    // The DJ's own heading and line fitted to their lines, in the print's own font (qr-print.css): a long one made smaller, half a point
    // at a time, until it takes its lines whole — two for the poster's heading, three elsewhere — and no smaller than its least; a short
    // one keeps the stylesheet's size. Measured on the first card or tent, the same size given to all (their words are the same). On a
    // phone too: the sheet is laid out there as it prints (fitPreview). The line clamp of the stylesheet stays the last guard. For
    // whoever prints, on every load once the fonts are in.
    // [the text, its most lines, its least size in pt, the least of a heading in the script]. The script's size is brought to the
    // sans's height of small letters (qr-print.css font-size-adjust), its letters wider: 40 of them need a smaller size on a card
    const FITS = [['.poster h1 .own', 2, 16], ['.poster .own-line', 3, 11], ['.card .title .own', 3, 7, 5.5], ['.card .own-line', 3, 6],
        ['.face .title .own', 3, 12, 10], ['.face .own-line', 3, 9]];
    const PT = 96 / 72;
    const narrow = matchMedia('screen and (max-width: 200mm)');

    function tooLong(el, most) {
        const line = parseFloat(getComputedStyle(el).lineHeight);
        // a line the clamp hid, not the font's letters reaching a little past their line
        return el.scrollHeight > el.clientHeight + line / 2 || Math.round(el.clientHeight / line) > most;
    }

    function fitTexts() {
        FITS.forEach(function (fit) {
            const texts = Array.prototype.slice.call(document.querySelectorAll(fit[0]));
            texts.forEach(function (el) { el.style.removeProperty('font-size'); });
            const first = texts.find(function (el) { return el.checkVisibility(); });
            if (!first) return;
            const least = fit[3] && document.body.classList.contains('font-script') ? fit[3] : fit[2];
            let size = parseFloat(getComputedStyle(first).fontSize) / PT;
            while (size > least && tooLong(first, fit[1])) {
                size = Math.max(least, size - 0.5);
                first.style.fontSize = size + 'pt';
            }
            texts.forEach(function (el) { el.style.fontSize = first.style.fontSize; });
        });
        fitPreview();
    }

    // A phone (narrower than the paper): the sheet keeps its printed width (qr-print.css) and is made smaller to the screen here with
    // zoom — the page then scrolls as far as the smaller sheet (a transform left its full height empty below it), and the sizes the
    // fitting above measures (a box's height, a line's) stay the print's, unzoomed alike.
    const PAPER = 186 * 96 / 25.4;

    // Set only when it changes, never taken off and put back: a phone's address bar, hiding and showing as the page scrolls, sends
    // "resize" again and again with the same width — the sheet unzoomed for a moment made the page longer and it jumped at its foot.
    function fitPreview() {
        const main = document.querySelector('body > main');
        if (!main) return;
        let zoom = '', margin = '';
        if (narrow.matches) {
            const room = document.documentElement.clientWidth - 32;
            const scale = Math.min(1, room / PAPER);
            zoom = String(scale);
            margin = (16 + (room - PAPER * scale) / 2) / scale + 'px';   // a zoomed box's margin is zoomed too
        }
        if (main.style.zoom !== zoom) main.style.zoom = zoom;
        if (main.style.marginLeft !== margin) main.style.marginLeft = margin;
    }

    fitTexts();
    if (document.fonts) document.fonts.ready.then(fitTexts);
    narrow.addEventListener('change', fitTexts);
    // the scale depends on the width alone: a resize of the height (the address bar) is nothing to do
    let previewWidth = document.documentElement.clientWidth;
    window.addEventListener('resize', function () {
        if (document.documentElement.clientWidth === previewWidth) return;
        previewWidth = document.documentElement.clientWidth;
        fitPreview();
    });

    const form = document.getElementById('posterForm');
    if (!form) return;
    const answer = document.getElementById('posterAnswer');
    const save = document.getElementById('posterSave');

    function all(selector) { return Array.prototype.slice.call(document.querySelectorAll(selector)); }

    function showHeading() {
        const own = form.elements.heading.value.replace(/\s+/g, ' ').trim();
        all('[data-poster-heading]').forEach(function (el) { el.textContent = own; el.hidden = !own; });
        all('[data-poster-ours-heading]').forEach(function (el) { el.hidden = !!own; });
        fitTexts();
    }

    function showLine() {
        const own = form.elements.line.value.replace(/\s+/g, ' ').trim();
        all('[data-poster-line]').forEach(function (el) { el.textContent = own; el.hidden = !own; });
        fitTexts();
    }

    function showPart(box) {
        const part = box.dataset.posterToggle;
        all('[data-poster-part="' + part + '"]').forEach(function (el) { el.hidden = !box.checked; });
        if (part === 'logo') {
            all('[data-poster-part="ours"]').forEach(function (el) { el.hidden = box.checked; });
            all('.poster, .card, .face').forEach(function (el) { el.classList.toggle('with-logo', box.checked); });
        }
    }

    const MAX_TABLES = Number(form.elements.tables.max) || 50;

    /** The number of tables typed: 1 … the most, more is the most; anything else none. */
    function tablesTyped() {
        const n = parseInt(form.elements.tables.value, 10);
        return n >= 1 ? Math.min(n, MAX_TABLES) : null;
    }

    const fromField = document.getElementById('posterTablesFrom');
    const summary = document.getElementById('posterTablesSummary');

    /** The first table's number: 1 … its field's most, more is the most; anything else 1. */
    function firstTable() {
        const n = parseInt(fromField ? fromField.value : '', 10);
        return n >= 1 ? Math.min(n, Number(fromField.max) || 999) : 1;
    }

    function showSummary(tables, first, pages) {
        if (!summary) return;
        summary.hidden = !tables;
        if (!tables) return;
        summary.textContent = (tables === 1 ? summary.dataset.one : summary.dataset.many)
            .replace('{from}', first).replace('{to}', first + tables - 1).replace('{pages}', pages);
    }

    function showTables() {
        const items = all('[data-print-item]');
        if (!items.length) return;
        const cards = items[0].classList.contains('card');
        const tables = tablesTyped();
        const first = firstTable();
        const count = tables || (cards ? 8 : 1);
        for (let i = items.length; i < count; i++) {
            items.push(items[0].parentNode.appendChild(items[0].cloneNode(true)));
        }
        items.splice(count).forEach(function (item) { item.remove(); });
        fitPreview();   // more or fewer sheets
        items.forEach(function (item, i) {
            item.querySelectorAll('[data-table-number]').forEach(function (no) {
                no.hidden = !tables;
                no.querySelector('[data-table-n]').textContent = String(first + i);
            });
        });
        showSummary(tables, first, cards ? Math.ceil(count / 8) : count);
    }

    function say(text, refused) {
        answer.textContent = text;
        answer.classList.remove('unsaved');
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

    const paperPicker = document.getElementById('posterPaperPicker');
    const ownPaper = document.getElementById('posterPaperOwn');

    function chosenPaper() {
        const checked = form.querySelector('input[name="paper"]:checked');
        return checked ? checked.value : '';
    }

    function changeLook() {
        showLook(form.elements.theme.value, chosenAccent(), form.elements.font.value, chosenPaper());
        fitTexts();   // another font, another width
    }

    form.elements.theme.addEventListener('change', changeLook);
    form.elements.font.addEventListener('change', changeLook);
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
    Array.prototype.forEach.call(form.querySelectorAll('input[name="paper"]'), function (radio) {
        radio.addEventListener('change', changeLook);
    });
    if (paperPicker && ownPaper) {
        paperPicker.addEventListener('input', function () {
            ownPaper.value = paperPicker.value;
            ownPaper.checked = true;
            changeLook();
        });
    }

    form.elements.heading.addEventListener('input', showHeading);
    // "Nagłówek na okazję": the occasion's text in the heading, as if typed (the preview, "Niezapisane zmiany"); the list back to its prompt
    const preset = document.getElementById('posterPreset');
    if (preset) {
        preset.addEventListener('change', function () {
            const option = preset.options[preset.selectedIndex];
            if (!option || !option.dataset.heading) return;
            form.elements.heading.value = option.dataset.heading;
            form.elements.heading.dispatchEvent(new Event('input', { bubbles: true }));
            preset.value = '';
        });
    }
    form.elements.line.addEventListener('input', showLine);
    form.elements.tables.addEventListener('input', showTables);
    if (fromField) fromField.addEventListener('input', showTables);
    showTables();   // the summary of the saved tables
    all('[data-poster-toggle]').forEach(function (box) {
        box.addEventListener('change', function () { showPart(box); });
    });

    // Changes not saved yet go with the DJ to the other layouts (poster, cards, tent), so they can be compared before "Zapisz": kept in
    // this tab only, for the next page, and gone once it has them.
    const stashKey = 's2p-poster-' + form.elements.partyCode.value;

    function stash() {
        const state = { heading: form.elements.heading.value, line: form.elements.line.value, tables: form.elements.tables.value,
            theme: form.elements.theme.value, font: form.elements.font.value,
            accent: chosenAccent(), picker: picker ? picker.value : '',
            paper: chosenPaper(), paperPicker: paperPicker ? paperPicker.value : '', parts: {} };
        all('[data-poster-toggle]').forEach(function (box) { state.parts[box.dataset.posterToggle] = box.checked; });
        try { sessionStorage.setItem(stashKey, JSON.stringify(state)); } catch (e) { /* no storage: the switch loses them, as before */ }
    }

    function unstash() {
        let state = null;
        try {
            state = JSON.parse(sessionStorage.getItem(stashKey) || 'null');
            sessionStorage.removeItem(stashKey);
        } catch (e) { return; }
        if (!state) return;
        form.elements.heading.value = state.heading || '';
        form.elements.line.value = state.line || '';
        form.elements.tables.value = state.tables || '';
        if (form.querySelector('#posterTheme option[value="' + state.theme + '"]')) form.elements.theme.value = state.theme;
        if (form.querySelector('#posterFont option[value="' + state.font + '"]')) form.elements.font.value = state.font;
        if (picker && state.picker) picker.value = state.picker;
        const swatch = Array.prototype.find.call(form.querySelectorAll('input[name="accent"]'), function (radio) {
            return radio !== own && radio.value === state.accent;
        });
        if (swatch) swatch.checked = true;
        else if (own && state.accent) { own.value = state.accent; own.checked = true; }
        if (paperPicker && state.paperPicker) paperPicker.value = state.paperPicker;
        const tint = Array.prototype.find.call(form.querySelectorAll('input[name="paper"]'), function (radio) {
            return radio !== ownPaper && radio.value === (state.paper || '');
        });
        if (tint) tint.checked = true;
        else if (ownPaper && state.paper) { ownPaper.value = state.paper; ownPaper.checked = true; }
        all('[data-poster-toggle]').forEach(function (box) {
            if (typeof state.parts[box.dataset.posterToggle] === 'boolean') box.checked = state.parts[box.dataset.posterToggle];
            showPart(box);
        });
        showHeading();
        showLine();
        showTables();
        changeLook();
        markUnsaved();
    }

    let unsaved = false;

    function markUnsaved() {
        unsaved = true;
        say(form.dataset.unsaved, false);
        answer.classList.add('unsaved');
    }

    // the language saves itself; the helpers (#posterHelperForm: "Nagłówek na okazję", "Numer pierwszego stołu") are never saved —
    // none of them makes the editor's changes unsaved by itself
    function unsaving(e) { return e.target.name !== 'language' && e.target.form === form; }
    form.addEventListener('input', function (e) { if (unsaving(e)) markUnsaved(); });
    form.addEventListener('change', function (e) { if (unsaving(e)) markUnsaved(); });
    // The first table's number is never saved, yet the DJ switching layouts to compare them keeps it: it goes along with the switch
    // alone (unsaved changes or not), and the next page takes it once — a reload starts from 1 again.
    const firstKey = 's2p-poster-first-' + form.elements.partyCode.value;

    function takeFirst() {
        let first = null;
        try {
            first = sessionStorage.getItem(firstKey);
            sessionStorage.removeItem(firstKey);
        } catch (e) { return; }
        if (first && fromField) {
            fromField.value = first;
            showTables();
        }
    }

    all('[data-layout-switch]').forEach(function (link) {
        link.addEventListener('click', function () {
            if (unsaved) stash();
            try {
                if (fromField && fromField.value) sessionStorage.setItem(firstKey, fromField.value);
            } catch (e) { /* no storage: the next layout starts from 1 */ }
        });
    });
    unstash();
    takeFirst();

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
            unsaved = false;
            say(form.dataset.saved, false);
        } catch (err) {
            say(form.dataset.refused, true);
        }
        save.disabled = false;
    });
})();
