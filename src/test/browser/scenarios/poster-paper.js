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

// The white badge only colours the room the DJ's logo always has: its padding counted inside the logo's box on the badge's papers
// alone, and the logo shrank when the DJ picked a paper of their own or the club's; the badge put outside it then touched the code
// on a card and a tent, and a wide logo's badge reached past a card's column into the heading.
(function () {
    const MM = 96 / 25.4;

    function wideLogo() {
        const canvas = document.createElement('canvas');
        canvas.width = 800;
        canvas.height = 200;
        canvas.getContext('2d').fillRect(0, 0, 800, 200);
        return canvas.toDataURL();
    }

    function steady(page, selector, below, column) {
        return async function (t) {
            const logo = document.querySelector(selector);
            const measure = function () {
                const r = logo.getBoundingClientRect(), s = getComputedStyle(logo);
                const px = function (v) { return parseFloat(v); };
                return [r.left + px(s.paddingLeft), r.top + px(s.paddingTop), r.width - px(s.paddingLeft) - px(s.paddingRight),
                    r.height - px(s.paddingTop) - px(s.paddingBottom), document.querySelector(below).getBoundingClientRect().top]
                    .map(function (v) { return Math.round(v * 10) / 10; });
            };
            const theme = function (name) {
                const select = document.getElementById('posterTheme');
                select.value = name;
                select.dispatchEvent(new Event('change', { bubbles: true }));
            };
            const badge = function () { return getComputedStyle(logo).backgroundColor; };
            // a theme with a paper of its own: the DJ's paper changes nothing else on it (on the classic one it moves the words off the
            // edges, and the code is fitted anew)
            theme('ELEGANT');
            const plain = measure();
            const bare = badge();
            const picker = document.getElementById('posterPaperPicker');
            picker.value = '#3b0a14';
            picker.dispatchEvent(new Event('input', { bubbles: true }));
            const own = measure();
            t.step('the badge on the DJ\'s paper only', [bare, badge()], ['rgba(0, 0, 0, 0)', 'rgb(255, 255, 255)']);
            t.step('on the DJ\'s paper the logo and the words under it where and as large as on the theme\'s (left, top, width, height, next)',
                own, plain);
            const gap = document.querySelector(below).getBoundingClientRect().top - logo.getBoundingClientRect().bottom;
            t.check('the badge 1 mm or more above the next part (' + (gap / MM).toFixed(1) + ' mm)', gap >= MM);
            if (column) {
                await new Promise(function (loaded) { logo.onload = loaded; logo.src = wideLogo(); });
                const reach = logo.getBoundingClientRect().right, text = document.querySelector(column).getBoundingClientRect().left;
                t.check('a wide logo\'s badge within the code\'s column (' + ((text - reach) / MM).toFixed(1) + ' mm before the texts)',
                    reach <= text);
            }
            // the club's own paper: its frame differs, the logo's size not
            picker.value = '#ffffff';
            document.querySelector('#posterForm input[name="paper"][value=""]').click();
            theme('CLASSIC');
            const classic = measure();
            theme('CLUB');
            t.step('on the club\'s paper: on the badge, as large as on the classic one (width, height)',
                [badge(), measure().slice(2, 4)], ['rgb(255, 255, 255)', classic.slice(2, 4)]);
        };
    }

    S2P.scenario({
        name: 'poster-paper-logo-size',
        page: 'qr-print-poster-logo',
        title: 'the DJ\'s logo on the white badge (own paper, club): as large and where it was, the heading not moved',
        run: steady('qr-print-poster-logo', '.poster .dj-logo', '.poster h1')
    });
    S2P.scenario({
        name: 'poster-paper-logo-size-cards',
        page: 'qr-print-cards-logo',
        title: 'the DJ\'s logo on a card on the white badge: as large and where it was, the code not moved nor touched, a wide one within its column',
        run: steady('qr-print-cards-logo', '.card .dj-logo', '.card .qr', '.card .text')
    });
    S2P.scenario({
        name: 'poster-paper-logo-size-tent',
        page: 'qr-print-tent-logo',
        title: 'the DJ\'s logo on a tent on the white badge: as large and where it was, the code not moved nor touched, a wide one within its column',
        run: steady('qr-print-tent-logo', '.face-lower .dj-logo', '.face-lower .qr', '.face-lower .text')
    });
})();

// "Białe tło pod logo" (V70): the badge on every paper, on none, or as the papers have it — at once in the preview, the logo keeping
// its size and place; the choice goes along to the other layouts with the editor's unsaved changes.
(function () {
    S2P.scenario({
        name: 'poster-logo-badge-choice',
        page: 'qr-print-poster-logo',
        title: '"Białe tło pod logo": always — the badge on the classic paper, never — none on the club\'s; the logo as large and where it was',
        run: async function (t) {
            const logo = document.querySelector('.poster .dj-logo');
            const box = function () { const r = logo.getBoundingClientRect(); return [r.left, r.top, r.width, r.height].map(Math.round); };
            const badge = function () { return getComputedStyle(logo).backgroundColor; };
            const pick = function (value) {
                const radio = document.querySelector('#posterForm input[name="badge"][value="' + value + '"]');
                radio.checked = true;
                radio.dispatchEvent(new Event('change', { bubbles: true }));
            };
            const theme = function (name) {
                const select = document.getElementById('posterTheme');
                select.value = name;
                select.dispatchEvent(new Event('change', { bubbles: true }));
            };
            t.step('automatic on the classic paper: none', badge(), 'rgba(0, 0, 0, 0)');
            const plain = box();
            pick('ALWAYS');
            t.step('always: the badge, the logo where it was', [badge(), box(), document.body.classList.contains('badge-always')],
                ['rgb(255, 255, 255)', plain, true]);
            theme('CLUB');
            pick('NEVER');
            t.step('never, on the club\'s paper: none', [badge(), document.body.className.indexOf('badge-') < 0 ? 'no class' : 'class'],
                ['rgba(0, 0, 0, 0)', 'class']);
            pick('AUTO');
            t.step('automatic on the club\'s paper: the badge, no class', [badge(), /\bbadge-/.test(document.body.className)],
                ['rgb(255, 255, 255)', false]);
            t.step('the choice is unsaved', document.getElementById('posterAnswer').classList.contains('unsaved'), true);
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

// The colours told by name (qr-print.html, PosterColours): a screen reader said "#fbf3e2"; the swatch's name now — "Kremowy", "Malinowy"
// —, its title too. "Własny" opens the colour field from the whole chip: only its 28 × 24 px square did before, a tap on the word
// picked the colour without a way to change it.
(function () {
    S2P.scenario({
        name: 'poster-colours-named',
        page: 'qr-print-poster',
        title: 'the paper\'s and the heading\'s colours named for a screen reader, never by their code; "Własny" opens the colour field from the whole chip',
        run: async function (t) {
            const swatches = Array.from(document.querySelectorAll('#posterForm input[name="paper"], #posterForm input[name="accent"]'))
                .filter(function (r) { return r.id !== 'posterPaperOwn' && r.id !== 'posterAccentOwn' && r.value; });
            const names = swatches.map(function (r) { return r.getAttribute('aria-label') || ''; });
            t.step('fourteen colours, each a name', [swatches.length, names.filter(function (n) { return !n || n.charAt(0) === '#'; })], [14, []]);
            t.step('no two alike in a group', [new Set(names.slice(0, 6)).size, new Set(names.slice(6)).size], [6, 8]);
            t.step('the title the same name', swatches.every(function (r) { return r.closest('label').title === r.getAttribute('aria-label'); }), true);
            const opened = [];
            const show = HTMLInputElement.prototype.showPicker;
            HTMLInputElement.prototype.showPicker = function () { opened.push(this.id); };
            try {
                document.querySelector('#posterAccentOwn + .swatch-chip > span').click();
                document.querySelector('#posterPaperOwn + .swatch-chip > span').click();
            } finally {
                HTMLInputElement.prototype.showPicker = show;
            }
            t.step('a tap on "Własny": the colour field opens, the own colour picked', [opened, document.getElementById('posterAccentOwn').checked,
                document.getElementById('posterPaperOwn').checked], [['posterAccentPicker', 'posterPaperPicker'], true, true]);
        }
    });
})();
