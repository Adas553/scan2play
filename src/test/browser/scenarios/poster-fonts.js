// The print's own fonts (qr-print.css, static/fonts — OFL): "S2P Sans" (Source Sans 3) for every theme, "S2P Serif" (Playfair Display)
// for the elegant headings — served by us, so a poster lays out the same on the DJ's computer, a phone and GitHub's runner, and the
// browser tests measure what prints. And the DJ's heading and line are fitted (qr-print.js): a long one is made smaller until it takes
// its lines — two on the poster, three on a card and a tent's face — never cut; a short one keeps its full size.
(function () {
    /** Every theme the editor offers (V47, V52): a new theme is checked here by itself. */
    function themes() { return Array.from(document.querySelectorAll('#posterTheme option')).map(function (o) { return o.value; }); }
    const PT = 96 / 72;

    function type(id, text) {
        const field = document.getElementById(id);
        field.value = text;
        field.dispatchEvent(new Event('input', { bubbles: true }));
    }

    function pickTheme(theme) {
        const select = document.getElementById('posterTheme');
        select.value = theme;
        select.dispatchEvent(new Event('change', { bubbles: true }));
    }

    function loaded(family) {
        return Array.from(document.fonts).some(function (f) { return f.family.replace(/"/g, '') === family && f.status === 'loaded'; });
    }

    function family(el) { return getComputedStyle(el).fontFamily.split(',')[0].replace(/"/g, '').trim(); }

    function lines(el) {
        const style = getComputedStyle(el);
        return Math.round(el.getBoundingClientRect().height / parseFloat(style.lineHeight));
    }

    /** A fitted text: whole (nothing cut by the clamp), in at most {@code most} lines, no smaller than {@code min} pt. */
    function badFit(el, most, min) {
        const size = parseFloat(getComputedStyle(el).fontSize) / PT;
        const bad = [];
        if (el.scrollHeight > el.clientHeight + parseFloat(getComputedStyle(el).lineHeight) / 2) bad.push('cut');   // a hidden line
        if (lines(el) > most) bad.push(lines(el) + ' lines');
        if (size < min - 0.01) bad.push(size.toFixed(1) + ' pt');
        return bad.length ? el.className + ': ' + bad.join(', ') : null;
    }

    S2P.scenario({
        name: 'poster-fonts-ours',
        page: 'qr-print-poster',
        title: 'the print in our own fonts: "S2P Sans" loaded from /fonts and set on the paper, "S2P Serif" for the elegant heading; Polish letters in both',
        run: async function (t) {
            await document.fonts.ready;
            t.step('the paper\'s font', family(document.querySelector('.poster')), 'S2P Sans');
            await t.waitFor(function () { return loaded('S2P Sans'); }, 'S2P Sans loaded', 5000);
            // the Latin Extended half is fetched once a letter of it is needed: both halves for Polish
            t.step('Polish letters in S2P Sans: both halves', (await document.fonts.load('700 30pt "S2P Sans"', 'Zażółć gęślą jaźń')).length, 2);
            pickTheme('ELEGANT');
            const h1 = document.querySelector('.poster h1 .ours > span');
            t.step('the elegant heading\'s font', family(h1), 'S2P Serif');
            await document.fonts.ready;
            await t.waitFor(function () { return loaded('S2P Serif'); }, 'S2P Serif loaded', 5000);
            t.step('Polish letters in S2P Serif: both halves', (await document.fonts.load('700 30pt "S2P Serif"', 'Zażółć gęślą jaźń')).length, 2);
        }
    });

    S2P.scenario({
        name: 'poster-heading-fitted',
        page: 'qr-print-cards-logo',
        title: 'the longest heading and line on a card: made smaller until whole in three lines, never cut, in every theme; a short heading back to its full size',
        run: async function (t) {
            await document.fonts.ready;
            type('posterHeading', 'W'.repeat(40));
            type('posterLine', 'W'.repeat(60));
            const bad = [];
            themes().forEach(function (theme) {
                pickTheme(theme);
                const card = document.querySelector('.card');
                [badFit(card.querySelector('.title .own'), 3, 7), badFit(card.querySelector('.own-line'), 3, 6)]
                    .filter(Boolean).forEach(function (b) { bad.push(theme + ' ' + b); });
            });
            t.step('whole, three lines at most, readable', bad, []);
            type('posterHeading', 'Wesele');
            t.step('a short heading: its full 11 pt', Math.round(parseFloat(getComputedStyle(document.querySelector('.card .title .own')).fontSize) / PT * 10) / 10, 11);
        }
    });

    S2P.scenario({
        name: 'poster-heading-fitted-tent',
        page: 'qr-print-tent-logo',
        title: 'the longest heading and line on a tent\'s faces: whole in three lines, in every theme',
        run: async function (t) {
            await document.fonts.ready;
            type('posterHeading', 'W'.repeat(40));
            type('posterLine', 'W'.repeat(60));
            const bad = [];
            themes().forEach(function (theme) {
                pickTheme(theme);
                document.querySelectorAll('.tent .face').forEach(function (face, i) {
                    [badFit(face.querySelector('.title .own'), 3, 12), badFit(face.querySelector('.own-line'), 3, 9)]
                        .filter(Boolean).forEach(function (b) { bad.push(theme + ' face ' + i + ' ' + b); });
                });
            });
            t.step('whole, three lines at most', bad, []);
        }
    });

    S2P.scenario({
        name: 'poster-heading-fitted-poster',
        page: 'qr-print-poster-logo',
        title: 'the longest heading on the poster: two lines at most, the line three, in every theme; a short one keeps its 26 pt',
        run: async function (t) {
            await document.fonts.ready;
            type('posterHeading', 'W'.repeat(40));
            type('posterLine', 'W'.repeat(60));
            const bad = [];
            themes().forEach(function (theme) {
                pickTheme(theme);
                [badFit(document.querySelector('.poster h1 .own'), 2, 16), badFit(document.querySelector('.poster .own-line'), 3, 11)]
                    .filter(Boolean).forEach(function (b) { bad.push(theme + ' ' + b); });
            });
            t.step('two lines and three at most', bad, []);
            type('posterHeading', 'Osiemnastka Dawida');
            t.step('a short heading: its full 26 pt', Math.round(parseFloat(getComputedStyle(document.querySelector('.poster h1 .own')).fontSize) / PT), 26);
        }
    });
})();
