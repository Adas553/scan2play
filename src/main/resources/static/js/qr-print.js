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
// The look (V47, V53): the theme is the class of <body> (qr-print.css), the DJ's colour its data-accent, the DJ's paper its
// data-paper — both printed as picked, set as --accent / --paper; the words on the DJ's paper black or white, whichever reads better.
// A heading that stands out poorly from its paper is said under the colours, never changed: the DJ decides. On every load, for
// whoever prints, and at once while the editor changes them.
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

    /** A heading — large letters — stands out poorly from its paper under this contrast (WCAG's least for large text). */
    const WEAK = 3;

    function showLook(theme, accent, font, paperColour, keepNote) {
        const body = document.body;
        if (theme) {
            body.className = body.className.replace(/\btheme-\w+\b/g, '').trim() + ' theme-' + theme.toLowerCase();
        }
        if (font) {   // the heading's font (V51): the theme's has no class
            body.className = body.className.replace(/\bfont-\w+\b/g, '').trim() + (font === 'THEME' ? '' : ' font-' + font.toLowerCase());
        }
        // The DJ's paper (V53) and colour print as picked — the DJ decides; nothing is changed by itself. The words on the DJ's paper
        // black or white, whichever reads better (always at least 4.5:1); on it the DJ's logo on a white badge and the classic paper's
        // words off its edges (qr-print.css .paper-own).
        const picked = paperColour ? rgb(paperColour) : null;
        const whiteWords = !!picked && contrast(picked, [255, 255, 255]) > contrast(picked, [0, 0, 0]);
        ['--paper', '--ink', '--muted', '--accent'].forEach(function (name) { body.style.removeProperty(name); });
        body.classList.toggle('paper-own', !!picked);
        if (picked) {
            body.style.setProperty('--paper', hex(picked));
            body.style.setProperty('--ink', whiteWords ? '#ffffff' : '#000000');
            body.style.setProperty('--muted', whiteWords ? '#ffffff' : '#000000');
        }
        if (rgb(accent)) body.style.setProperty('--accent', hex(rgb(accent)));
        // a heading that stands out poorly from the paper — the DJ's colour, or the theme's on the DJ's paper — is said under the
        // colours; the theme's own colours on its own paper always read. Kept as it is while the DJ drags in a colour picker: shown and
        // hidden then, it moved the editor under the open picker and the browser closed it
        const note = document.getElementById('posterAccentNote');
        if (note && !keepNote) {
            const style = getComputedStyle(body);
            const heading = rgb(accent) || rgb(style.getPropertyValue('--accent-base').trim());
            const paper = rgb(style.getPropertyValue('--paper').trim());
            const weak = !!heading && !!paper && contrast(heading, paper) < WEAK;
            note.hidden = !weak;
            note.textContent = weak ? note.dataset.weak : '';
        }
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

    /** Laid out on the page — neither it nor a parent hidden. checkVisibility where the browser has it: Safari has it only from 17.4,
     *  and a call to it there throws at load, which leaves the editor doing nothing. */
    function visible(el) {
        return el.checkVisibility ? el.checkVisibility() : el.getClientRects().length > 0;
    }

    function tooLong(el, most) {
        const line = parseFloat(getComputedStyle(el).lineHeight);
        // a line the clamp hid, not the font's letters reaching a little past their line
        return el.scrollHeight > el.clientHeight + line / 2 || Math.round(el.clientHeight / line) > most;
    }

    function fitTexts() {
        FITS.forEach(function (fit) {
            const texts = Array.prototype.slice.call(document.querySelectorAll(fit[0]));
            texts.forEach(function (el) { el.style.removeProperty('font-size'); });
            const first = texts.find(visible);
            if (!first) return;
            const least = fit[3] && document.body.classList.contains('font-script') ? fit[3] : fit[2];
            let size = parseFloat(getComputedStyle(first).fontSize) / PT;
            while (size > least && tooLong(first, fit[1])) {
                size = Math.max(least, size - 0.5);
                first.style.fontSize = size + 'pt';
            }
            texts.forEach(function (el) { el.style.fontSize = first.style.fontSize; });
        });
        fitPoster();
        fitPreview();
    }

    // The poster fills its page (qr-print.css .poster: as high as the printable page): the room its words and parts leave goes to the
    // code — larger, read from further away — up to QR_MOST; what is left centres them. The room is measured with the sheet as high
    // as its words, and the code's new size taken as a ratio of its own: the same on a preview made smaller (zoom).
    const QR_MOST = 140 * 96 / 25.4;

    function fitPoster() {
        const poster = document.querySelector('main.poster');
        const qr = poster && poster.querySelector('.qr');
        if (!qr) return;
        qr.style.removeProperty('width');
        qr.style.removeProperty('height');
        const page = poster.offsetHeight;
        poster.style.minHeight = '0';
        const room = page - poster.offsetHeight;
        poster.style.removeProperty('min-height');
        const base = parseFloat(getComputedStyle(qr).width);
        const size = Math.min(QR_MOST, base * (qr.offsetHeight + room) / qr.offsetHeight);
        if (size > base) {
            qr.style.width = size + 'px';
            qr.style.height = size + 'px';
        }
    }

    // A phone (narrower than the paper), or a computer's column beside the editor narrower than the sheet and its white margin: the
    // sheet keeps its printed width (qr-print.css) and is made smaller here with zoom — the page then scrolls as far as the smaller
    // sheet (a transform left its full height empty below it), and the sizes the fitting above measures (a box's height, a line's) stay
    // the print's, unzoomed alike. The zoom and a phone's margin are --preview-zoom and --preview-margin, used by the stylesheet on
    // the screen only: never on paper (a margin left on the sheet printed the cards off the page's middle).
    const PAPER = 186 * 96 / 25.4;
    const wide = matchMedia('screen and (min-width: 1100px)');   // qr-print.css: the editor a panel beside the page
    const SHEET_MARGIN = 12 * 96 / 25.4 + 24;   // the page's white margin around the sheet and the desk's air (qr-print.css)

    // Set only when it changes, never taken off and put back: a phone's address bar, hiding and showing as the page scrolls, sends
    // "resize" again and again with the same width — the sheet unzoomed for a moment made the page longer and it jumped at its foot.
    function fitPreview() {
        const main = document.querySelector('body > main');
        if (!main) return;
        let zoom = '', margin = '';
        const editor = document.getElementById('posterForm');
        if (narrow.matches) {
            const room = document.documentElement.clientWidth - 32;
            const scale = Math.min(1, room / PAPER);
            zoom = String(scale);
            margin = (16 + (room - PAPER * scale) / 2) / scale + 'px';   // a zoomed box's margin is zoomed too
        } else if (wide.matches && editor) {
            const room = document.documentElement.clientWidth - editor.offsetWidth - 2 * SHEET_MARGIN;
            if (room < PAPER) zoom = String(room / PAPER);
        }
        if (main.style.getPropertyValue('--preview-zoom') !== zoom) {
            if (zoom) main.style.setProperty('--preview-zoom', zoom);
            else main.style.removeProperty('--preview-zoom');
        }
        if (main.style.getPropertyValue('--preview-margin') !== margin) {
            if (margin) main.style.setProperty('--preview-margin', margin);
            else main.style.removeProperty('--preview-margin');
        }
    }

    fitTexts();
    if (document.fonts) document.fonts.ready.then(fitTexts);
    // the DJ's logo takes its height once loaded: the code is given the room again
    Array.prototype.forEach.call(document.querySelectorAll('body > main img'), function (img) {
        if (!img.complete) img.addEventListener('load', fitTexts);
    });
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

    // "Edytuj | Podgląd" (below 1100 px, qr-print.css): the editor or the preview, the page opening on the preview — what prints. The
    // preview away is still laid out, so the fitting and the print have it; each tap starts at the page's top
    const modes = document.getElementById('posterModes');

    function showMode(name) {
        document.body.classList.toggle('mode-edit', name === 'edit');
        document.body.classList.toggle('mode-preview', name !== 'edit');
        Array.prototype.forEach.call(modes.querySelectorAll('[data-mode]'), function (button) {
            button.setAttribute('aria-pressed', String(button.dataset.mode === name));
        });
        window.scrollTo(0, 0);
    }

    if (modes) {
        modes.addEventListener('click', function (e) {
            const button = e.target.closest('[data-mode]');
            if (button) showMode(button.dataset.mode);
        });
        showMode('preview');
    }
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
        fitPoster();   // a part more or less: the code's room
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

    function changeLook(keepNote) {
        showLook(form.elements.theme.value, chosenAccent(), form.elements.font.value, chosenPaper(), keepNote === true);
        fitTexts();   // another font, another width
    }

    form.elements.theme.addEventListener('change', changeLook);
    form.elements.font.addEventListener('change', changeLook);
    Array.prototype.forEach.call(form.querySelectorAll('input[name="accent"]'), function (radio) {
        radio.addEventListener('change', changeLook);
    });
    // a colour picker: the preview follows the drag ("input"), the note about a weak heading waits for the colour picked ("change")
    if (picker && own) {
        picker.addEventListener('input', function () {
            own.value = picker.value;
            own.checked = true;
            changeLook(true);
        });
        picker.addEventListener('change', function () { changeLook(); });
    }
    Array.prototype.forEach.call(form.querySelectorAll('input[name="paper"]'), function (radio) {
        radio.addEventListener('change', changeLook);
    });
    if (paperPicker && ownPaper) {
        paperPicker.addEventListener('input', function () {
            ownPaper.value = paperPicker.value;
            ownPaper.checked = true;
            changeLook(true);
        });
        paperPicker.addEventListener('change', function () { changeLook(); });
    }
    // "Własny": the whole chip opens the colour field — only its small square did, and a tap on the word picked the colour with no way
    // to change it. A click on the field itself, or the radio's own click that follows the label's, is left to the browser
    [[own, picker], [ownPaper, paperPicker]].forEach(function (pair) {
        if (!pair[0] || !pair[1]) return;
        pair[0].closest('label').addEventListener('click', function (e) {
            if (e.target.tagName === 'INPUT' || !pair[1].showPicker) return;
            try {
                pair[1].showPicker();
            } catch (err) { /* not allowed here: the square still opens it */ }
        });
    });

    form.elements.heading.addEventListener('input', showHeading);
    // "Wzór na okazję": the occasion's text in the heading, as if typed (the preview, "Niezapisane zmiany"), and its look — the theme,
    // the heading's font, the colour (empty: the theme's) and the theme's paper; the list back to its prompt
    const preset = document.getElementById('posterPreset');
    if (preset) {
        preset.addEventListener('change', function () {
            const option = preset.options[preset.selectedIndex];
            if (!option || !option.dataset.heading) return;
            form.elements.theme.value = option.dataset.theme;
            form.elements.font.value = option.dataset.font;
            [['accent', option.dataset.colour || ''], ['paper', '']].forEach(function (pick) {
                const radio = form.querySelector('input[name="' + pick[0] + '"][value="' + pick[1] + '"]');
                if (radio) radio.checked = true;
            });
            changeLook();
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

    /** The fields that go along: every one of the form by its name — the language saves itself, the party's code is the page's. */
    function carried(el) { return el.name && el.name !== 'partyCode' && el.name !== 'language' && el.type !== 'hidden'; }

    // Every field by its name — a text, a list, a number its value, a box ticked or not, a group of colours the one picked —, and the
    // colour fields (no name) by their id: a new field of the print goes along with nothing added here
    function stash() {
        const state = { fields: {}, pickers: {} };
        Array.prototype.filter.call(form.elements, carried).forEach(function (el) {
            if (el.type === 'checkbox') state.fields[el.name] = el.checked;
            else if (el.type !== 'radio') state.fields[el.name] = el.value;
            else if (el.checked) state.fields[el.name] = el.value;
        });
        all('#posterForm input[type="color"]').forEach(function (field) { state.pickers[field.id] = field.value; });
        try { sessionStorage.setItem(stashKey, JSON.stringify(state)); } catch (e) { /* no storage: the switch loses them, as before */ }
    }

    function unstash() {
        let state = null;
        try {
            state = JSON.parse(sessionStorage.getItem(stashKey) || 'null');
            sessionStorage.removeItem(stashKey);
        } catch (e) { return; }
        if (!state || !state.fields) return;
        Object.keys(state.pickers || {}).forEach(function (id) {
            const field = document.getElementById(id);
            if (field && state.pickers[id]) field.value = state.pickers[id];
        });
        Array.prototype.filter.call(form.elements, carried).forEach(function (el) {
            if (!(el.name in state.fields)) return;
            const value = state.fields[el.name];
            if (el.type === 'checkbox') {
                el.checked = value === true;
            } else if (el.type !== 'radio') {
                // a list keeps its value only if it still has it
                if (el.tagName !== 'SELECT' || el.querySelector('option[value="' + value + '"]')) el.value = value;
            }
        });
        // a group of colours: the swatch of that value, else the DJ's own (the radio beside a colour field) given it
        Object.keys(state.fields).forEach(function (name) {
            const radios = Array.prototype.filter.call(form.elements, function (el) { return el.type === 'radio' && el.name === name; });
            if (!radios.length) return;
            const own = radios.find(function (r) { return r.closest('label').querySelector('input[type="color"]'); });
            const swatch = radios.find(function (r) { return r !== own && r.value === state.fields[name]; });
            if (swatch) swatch.checked = true;
            else if (own) { own.value = state.fields[name]; own.checked = true; }
        });
        all('[data-poster-toggle]').forEach(showPart);
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

    // the language saves itself; the helpers (#posterHelperForm: "Wzór na okazję", "Numer pierwszego stołu") are never saved —
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

    // the switch to another layout takes the unsaved changes along (stash): leaving by it asks nothing
    let switching = false;

    all('[data-layout-switch]').forEach(function (link) {
        link.addEventListener('click', function (e) {
            if (!e.ctrlKey && !e.metaKey && !e.shiftKey && e.button === 0) switching = true;
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
            unsaved = false;   // the whole form was sent: the reload keeps it
            location.reload();
        } catch (e) {
            say(form.dataset.refused, true);
        }
    });

    // Changes not saved: leaving the page asks first — "Powrót do panelu", the tab closed after printing —, or they are gone without
    // a word
    window.addEventListener('beforeunload', function (e) {
        if (!unsaved || switching) return;
        e.preventDefault();
        e.returnValue = '';
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
