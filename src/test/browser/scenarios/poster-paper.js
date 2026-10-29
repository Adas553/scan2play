// "Kolor tła" (V53, qr-print.js): the paper as the DJ picks it — the theme's (the default), one of six light tints, or the DJ's own
// colour made a light tint here (mixed towards white until it is as light as a pale paper: little ink, the words readable) — on the
// poster, the cards and the tent. On a picked paper the words turn dark (the club's white ink would vanish) and the heading's colour is
// made readable on it, as on a theme's paper; the code stays on its white tile. "Zapisz" sends it, the switch keeps it.
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
        title: '"Kolor tła": a light tint as it is, the DJ\'s own colour made a light tint, the theme\'s again; the words readable on it in every theme, the club\'s words dark; the code on white; "Zapisz" sends it',
        run: async function (t) {
            const swatches = Array.from(document.querySelectorAll('#posterForm input[name="paper"]')).map(function (r) { return r.value; });
            t.step('the theme\'s, six tints, your own', [swatches.length, swatches[0]], [8, '']);
            pickPaper('#e8f3fb');
            t.step('a tint: the paper as it is', paper(), 'rgb(232, 243, 251)');
            pickPaper('#c2185b');
            const own = rgb(paper());
            t.check('your own deep pink made a light pink (' + own + ')', luminance(own) >= 0.84 && own[0] > own[1]);
            const bad = [];
            Array.from(document.querySelectorAll('#posterTheme option')).map(function (o) { return o.value; }).forEach(function (theme) {
                pick('posterTheme', theme);
                ['#c2185b', '#111111', '#fbf3e2'].forEach(function (colour) {
                    pickPaper(colour);
                    const p = paper();
                    if (luminance(rgb(p)) < 0.84) bad.push(theme + ' ' + colour + ' paper ' + p);
                    [['heading', '.poster h1 .ours > span'], ['English', '.poster h1 .second'], ['text', '.poster .code'], ['quiet', '.poster .link .second']]
                        .forEach(function (pair) {
                            const el = document.querySelector(pair[1]);
                            if (el && contrast(getComputedStyle(el).color, p) < 4.5) bad.push(theme + ' ' + colour + ' ' + pair[0] + ' ' + contrast(getComputedStyle(el).color, p).toFixed(2));
                        });
                    if (getComputedStyle(document.querySelector('.poster .qr')).backgroundColor !== 'rgb(255, 255, 255)' && theme !== 'CLASSIC') bad.push(theme + ' code not on white');
                });
            });
            t.step('light, readable, the code on white — every theme, a deep, a black and a tint', bad, []);
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
