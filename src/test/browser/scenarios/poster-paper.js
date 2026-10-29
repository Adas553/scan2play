// "Kolor tła" (V53, qr-print.js): the paper as the DJ picks it — the theme's (the default), one of six light tints, or the DJ's own
// colour: a light one made a light tint here (mixed towards white until it is as light as a pale paper: little ink), a dark one a dark
// paper (darkened only until light words read on it) — on the poster, the cards and the tent. On a light paper the words turn dark,
// on a dark one light, and the heading's colour is made readable on it; the code stays on its white tile; on a dark paper the DJ's logo
// on a white badge as on the club's paper; the classic paper, coloured, keeps the words off its edges.
// "Zapisz" sends it, the switch keeps it.
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
    }

    function paper() { return getComputedStyle(document.querySelector('.poster')).backgroundColor; }

    S2P.scenario({
        name: 'poster-paper',
        page: 'qr-print-poster',
        title: '"Kolor tła": a light tint as it is, the DJ\'s light colour a light tint, a dark one a dark paper; the words readable on it in every theme; the code on white; "Zapisz" sends it',
        run: async function (t) {
            const swatches = Array.from(document.querySelectorAll('#posterForm input[name="paper"]')).map(function (r) { return r.value; });
            t.step('the theme\'s, six tints, your own', [swatches.length, swatches[0]], [8, '']);
            pickPaper('#e8f3fb');
            t.step('a tint: the paper as it is', paper(), 'rgb(232, 243, 251)');
            pickPaper('#f48fb1');
            const own = rgb(paper());
            t.check('your own pink made a light pink (' + own + ')', luminance(own) >= 0.84 && own[0] > own[1]);
            pickPaper('#3b0a14');
            const deep = rgb(paper());
            t.check('your own dark claret a dark paper, still claret (' + deep + ')', luminance(deep) <= 0.09 && deep[0] > deep[1]);
            const bad = [];
            Array.from(document.querySelectorAll('#posterTheme option')).map(function (o) { return o.value; }).forEach(function (theme) {
                pick('posterTheme', theme);
                [['#c2185b', 'dark'], ['#111111', 'dark'], ['#f48fb1', 'light'], ['#fbf3e2', 'light']].forEach(function (pair) {
                    const colour = pair[0];
                    pickPaper(colour);
                    const p = paper(), l = luminance(rgb(p));
                    if (pair[1] === 'light' ? l < 0.84 : l > 0.09) bad.push(theme + ' ' + colour + ' paper ' + p);
                    [['heading', '.poster h1 .ours > span'], ['English', '.poster h1 .second'], ['text', '.poster .code'], ['quiet', '.poster .link .second']]
                        .forEach(function (pair) {
                            const el = document.querySelector(pair[1]);
                            if (el && contrast(getComputedStyle(el).color, p) < 4.5) bad.push(theme + ' ' + colour + ' ' + pair[0] + ' ' + contrast(getComputedStyle(el).color, p).toFixed(2));
                        });
                    if (getComputedStyle(document.querySelector('.poster .qr')).backgroundColor !== 'rgb(255, 255, 255)' && theme !== 'CLASSIC') bad.push(theme + ' code not on white');
                });
            });
            t.step('light or dark as picked, readable, the code on white — every theme, a deep, a black, a pink and a tint', bad, []);
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

// The DJ's colour of the heading and the frame made readable on the paper (qr-print.js): a near-black on a dark paper becomes the light
// words' colour (a grey made of it read as a mistake), a near-white on a light paper the dark words' colour; any colour changed so says
// it under the colours — lighter or darker — and nothing is said when it prints as picked or the theme's own is used.
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

    function ownPaper(colour) {
        const picker = document.getElementById('posterPaperPicker');
        picker.value = colour;
        picker.dispatchEvent(new Event('input', { bubbles: true }));
    }

    S2P.scenario({
        name: 'poster-accent-adjusted',
        page: 'qr-print-poster',
        title: 'the colour of the heading made readable: black on a dark paper the light words\' colour, a pale yellow on white darker; the editor says so, and says nothing when the colour prints as picked',
        run: async function (t) {
            const note = document.getElementById('posterAccentNote');
            const heading = document.querySelector('.poster h1 .ours > span');
            const seen = function () { return [getComputedStyle(heading).color, note.hidden ? '' : note.textContent.trim() === note.dataset.lighter ? 'lighter' : 'darker']; };
            pick('posterTheme', 'GALA');
            ownPaper('#8b1a1a');
            radio('accent', '#111111');
            t.step('black on a dark claret: the light words\' colour, said lighter', seen(), ['rgb(245, 245, 247)', 'lighter']);
            radio('accent', '#1565c0');
            const blue = seen();
            t.check('a blue on the claret: lightened, not the words\' colour, said lighter (' + blue + ')', blue[0] !== 'rgb(245, 245, 247)' && blue[1] === 'lighter');
            radio('accent', '');
            t.step('the theme\'s own: nothing said', seen()[1], '');
            pick('posterTheme', 'CLASSIC');
            radio('paper', '');
            radio('accent', '#111111');
            t.step('black on white: as picked, nothing said', seen(), ['rgb(17, 17, 17)', '']);
            const picker = document.getElementById('posterAccentPicker');
            picker.value = '#ffff66';
            picker.dispatchEvent(new Event('input', { bubbles: true }));
            t.step('a pale yellow on white: darker, said so', seen()[1], 'darker');
            picker.value = '#fafafa';
            picker.dispatchEvent(new Event('input', { bubbles: true }));
            t.step('a near-white on white: the dark words\' colour', seen(), ['rgb(17, 17, 17)', 'darker']);
        }
    });
})();
