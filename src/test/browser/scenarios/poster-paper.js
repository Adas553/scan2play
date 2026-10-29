// "Kolor tła" (V53, qr-print.js): the paper as the DJ picks it — the theme's (the default), one of six light tints, or the DJ's own
// colour — printed as picked (the DJ decides: two shades alike print alike), on the poster, the cards and the tent. The words on it
// black or white, whichever reads better; the code stays on its white tile; on a paper with white words the DJ's logo on a white
// badge as on the club's paper; the classic paper, coloured, keeps the words off its edges. "Zapisz" sends it, the switch keeps it.
(function () {
    function rgb(css) { return (css.match(/[\d.]+/g) || []).slice(0, 3).map(Number); }

    function luminance(c) {
        const lin = c.map(function (v) { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); });
        return 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2];
    }

    function contrast(a, b) {
        const x = luminance(rgb(a)), y = luminance(rgb(b));
        return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
    }

    function pick(id, value) {
        const select = document.getElementById(id);
        select.value = value;
        select.dispatchEvent(new Event('change', { bubbles: true }));
    }

    function pickPaper(colour) {
        const swatch = document.querySelector('#posterForm input[name="paper"][value="' + colour + '"]');
        if (swatch && swatch.id !== 'posterPaperOwn') {
            swatch.checked = true;
            swatch.dispatchEvent(new Event('change', { bubbles: true }));
            return;
        }
        const picker = document.getElementById('posterPaperPicker');
        picker.value = colour;
        picker.dispatchEvent(new Event('input', { bubbles: true }));
        picker.dispatchEvent(new Event('change', { bubbles: true }));
    }

    function paper() { return getComputedStyle(document.querySelector('.poster')).backgroundColor; }

    S2P.scenario({
        name: 'poster-paper',
        page: 'qr-print-poster',
        title: '"Kolor tła": a tint and the DJ\'s own colour as picked — two mustards alike print alike —; the words black or white, whichever reads, in every theme; the code on white; "Zapisz" sends it',
        run: async function (t) {
            const swatches = Array.from(document.querySelectorAll('#posterForm input[name="paper"]')).map(function (r) { return r.value; });
            t.step('the theme\'s, six tints, your own', [swatches.length, swatches[0]], [8, '']);
            pickPaper('#e8f3fb');
            t.step('a tint: the paper as it is', paper(), 'rgb(232, 243, 251)');
            pickPaper('#a97a28');
            const first = paper(), firstWords = getComputedStyle(document.querySelector('.poster .code')).color;
            pickPaper('#b48027');
            t.step('two mustards alike: each as picked, the same words on both', [first, paper(), firstWords,
                getComputedStyle(document.querySelector('.poster .code')).color],
                ['rgb(169, 122, 40)', 'rgb(180, 128, 39)', 'rgb(0, 0, 0)', 'rgb(0, 0, 0)']);
            const bad = [];
            Array.from(document.querySelectorAll('#posterTheme option')).map(function (o) { return o.value; }).forEach(function (theme) {
                pick('posterTheme', theme);
                ['#3b0a14', '#c2185b', '#777777', '#a97a28', '#f48fb1', '#fbf3e2'].forEach(function (colour) {
                    pickPaper(colour);
                    const p = paper();
                    const n = parseInt(colour.slice(1), 16);
                    if (p !== 'rgb(' + [(n >> 16) & 255, (n >> 8) & 255, n & 255].join(', ') + ')') bad.push(theme + ' ' + colour + ' paper changed to ' + p);
                    [['English', '.poster h1 .second'], ['text', '.poster .code'], ['quiet', '.poster .link .second']].forEach(function (pair) {
                        const el = document.querySelector(pair[1]);
                        if (!el) return;
                        const c = getComputedStyle(el).color;
                        if (c !== 'rgb(0, 0, 0)' && c !== 'rgb(255, 255, 255)') bad.push(theme + ' ' + colour + ' ' + pair[0] + ' ' + c);
                        if (contrast(c, p) < 4.5) bad.push(theme + ' ' + colour + ' ' + pair[0] + ' ' + contrast(c, p).toFixed(2));
                    });
                    if (getComputedStyle(document.querySelector('.poster .qr')).backgroundColor !== 'rgb(255, 255, 255)' && theme !== 'CLASSIC') bad.push(theme + ' code not on white');
                });
            });
            t.step('every theme, six colours: the paper as picked, the words black or white and readable, the code on white', bad, []);
            pick('posterTheme', 'CLASSIC');
            pickPaper('#e8f3fb');
            const field = document.getElementById('posterHeading');
            field.value = 'W'.repeat(40);
            field.dispatchEvent(new Event('input', { bubbles: true }));
            const box = document.querySelector('.poster').getBoundingClientRect(), h1 = document.querySelector('.poster h1 .own').getBoundingClientRect();
            t.check('the classic paper coloured: the heading at least 5 mm off its edges (' + Math.round(h1.left - box.left) + ' px)',
                h1.left - box.left >= 5 * 96 / 25.4 - 0.5 && box.right - h1.right >= 5 * 96 / 25.4 - 0.5);
            field.value = '';
            field.dispatchEvent(new Event('input', { bubbles: true }));
            pick('posterTheme', 'CLUB');
            pickPaper('');
            t.step('the theme\'s again: the club\'s dark paper', paper(), 'rgb(20, 20, 28)');
            pickPaper('#fbf3e2');
            const form = document.getElementById('posterForm');
            t.step('not saved yet, it says', document.getElementById('posterAnswer').textContent, form.dataset.unsaved);
            document.getElementById('posterSave').click();
            await t.waitFor(function () { return document.getElementById('posterAnswer').textContent === form.dataset.saved; }, 'saved', 3000);
            t.step('sent', (await t.stand.requests('POST /dj/qr-print/settings')).pop().q.paper, '#fbf3e2');
        }
    });

    S2P.scenario({
        name: 'poster-paper-cards-tent',
        page: 'qr-print-tent',
        title: '"Kolor tła" on a tent\'s faces too, both of them',
        run: async function (t) {
            pickPaper('#e9f6ee');
            t.step('both faces', Array.from(document.querySelectorAll('.tent .face')).map(function (f) { return getComputedStyle(f).backgroundColor; }),
                ['rgb(233, 246, 238)', 'rgb(233, 246, 238)']);
        }
    });
})();

// The DJ's colour of the heading and the frame prints as picked — the DJ decides —; one that stands out poorly from the paper (under
// 3:1, a large heading's least) is said under the colours, never changed; nothing is said for a colour that reads or the theme's own.
(function () {
    function pick(id, value) {
        const select = document.getElementById(id);
        select.value = value;
        select.dispatchEvent(new Event('change', { bubbles: true }));
    }

    function radio(name, value) {
        const r = document.querySelector('#posterForm input[name="' + name + '"][value="' + value + '"]');
        r.checked = true;
        r.dispatchEvent(new Event('change', { bubbles: true }));
    }

    function own(id, colour) {
        const picker = document.getElementById(id);
        picker.value = colour;
        picker.dispatchEvent(new Event('input', { bubbles: true }));
        picker.dispatchEvent(new Event('change', { bubbles: true }));   // the picker closed: the colour picked
    }

    S2P.scenario({
        name: 'poster-accent-exact',
        page: 'qr-print-poster',
        title: 'the colour of the heading as picked: black on a dark claret stays black and is said to stand out poorly; a pale yellow on white too; a colour that reads, or the theme\'s own, says nothing',
        run: async function (t) {
            const note = document.getElementById('posterAccentNote');
            const heading = document.querySelector('.poster h1 .ours > span');
            const seen = function () { return [getComputedStyle(heading).color, !note.hidden]; };
            pick('posterTheme', 'GALA');
            own('posterPaperPicker', '#8b1a1a');
            radio('accent', '#111111');
            t.step('black on a dark claret: black, said', seen(), ['rgb(17, 17, 17)', true]);
            t.step('the note\'s words', note.textContent.trim(), note.dataset.weak);
            radio('accent', '#00bcd4');
            t.step('a cyan on the claret: as picked, reads, nothing said', seen(), ['rgb(0, 188, 212)', false]);
            radio('paper', '');
            radio('accent', '');
            t.step('the theme\'s own on the theme\'s paper: nothing said', seen()[1], false);
            pick('posterTheme', 'CLASSIC');
            radio('paper', '');
            radio('accent', '#111111');
            t.step('black on white: nothing said', seen(), ['rgb(17, 17, 17)', false]);
            own('posterAccentPicker', '#ffff66');
            t.step('a pale yellow on white: as picked, said', seen(), ['rgb(255, 255, 102)', true]);
        }
    });
})();

// The DJ's logo on the DJ's own paper: always on the same white badge, whatever the words' colour — it changed its shape when the
// words turned from black to white (the badge was for white words only).
(function () {
    S2P.scenario({
        name: 'poster-paper-logo-steady',
        page: 'qr-print-poster-logo',
        title: 'the DJ\'s logo on the DJ\'s own paper: the same white badge with black words and with white ones',
        run: async function (t) {
            const logo = document.querySelector('.poster .dj-logo');
            const look = function () { const s = getComputedStyle(logo); return [s.backgroundColor, s.paddingTop, s.borderTopLeftRadius]; };
            const picker = document.getElementById('posterPaperPicker');
            const words = [];
            const looks = ['#e10ea9', '#3b0a14', '#f2c94c'].map(function (colour) {
                picker.value = colour;
                picker.dispatchEvent(new Event('input', { bubbles: true }));
                words.push(getComputedStyle(document.querySelector('.poster .code')).color);
                return look();
            });
            t.check('black and white words both seen (' + words.join(' / ') + ')', words.indexOf('rgb(0, 0, 0)') >= 0 && words.indexOf('rgb(255, 255, 255)') >= 0);
            t.step('one badge for all', [looks[0][0], JSON.stringify(looks[0]) === JSON.stringify(looks[1]) && JSON.stringify(looks[1]) === JSON.stringify(looks[2])],
                ['rgb(255, 255, 255)', true]);
        }
    });
})();

// While the DJ drags in the browser's colour picker ("input", again and again) the heading follows at once, but the note about a weak
// heading waits for the colour picked ("change"): shown and hidden while dragging, it made the editor shorter and longer under the
// open picker, the panel scrolled and the browser closed the picker.
(function () {
    S2P.scenario({
        name: 'poster-accent-note-steady',
        page: 'qr-print-poster',
        title: 'dragging in the colour picker: the heading follows, the note waits until the colour is picked — nothing below moves under the open picker',
        run: async function (t) {
            const note = document.getElementById('posterAccentNote');
            const picker = document.getElementById('posterAccentPicker');
            const heading = document.querySelector('.poster h1 .ours > span');
            picker.value = '#fafafa';
            picker.dispatchEvent(new Event('input', { bubbles: true }));
            t.step('dragging: the heading in the colour, the note not yet', [getComputedStyle(heading).color, note.hidden], ['rgb(250, 250, 250)', true]);
            picker.dispatchEvent(new Event('change', { bubbles: true }));
            t.step('picked: the note', note.hidden, false);
            picker.value = '#123abc';
            picker.dispatchEvent(new Event('input', { bubbles: true }));
            t.step('dragging on: the note stays as it was', note.hidden, false);
            picker.dispatchEvent(new Event('change', { bubbles: true }));
            t.step('picked a colour that reads: gone', note.hidden, true);
            const paper = document.getElementById('posterPaperPicker');
            paper.value = '#123abc';
            paper.dispatchEvent(new Event('input', { bubbles: true }));
            t.step('the paper\'s picker dragged to the heading\'s colour: the note waits', note.hidden, true);
            paper.dispatchEvent(new Event('change', { bubbles: true }));
            t.step('picked: the note', note.hidden, false);
        }
    });
})();
