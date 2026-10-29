// The QR print's look (V47, qr-print.js): a theme picked in the editor is the page's class at once, a colour (the palette's, the DJ's
// own) is made readable on the theme's paper — 4.5:1 for the heading whatever the DJ picks, a pale yellow on cream too —, the code
// stays black on white in every theme, and every theme with the longest words still fits one A4 page and the cards.
(function () {
    /** Every theme the editor offers (V47, V52): a new theme is checked here by itself. */
    function themes() { return Array.from(document.querySelectorAll('#posterTheme option')).map(function (o) { return o.value; }); }
    /** The theme's own, and the colours that read worst: white, a pale yellow, black (on the dark club), a light cyan. */
    const ACCENTS = ['', '#ffffff', '#ffff66', '#000000', '#00bcd4'];
    const A4_PRINTABLE = (297 - 2 * 12) * 96 / 25.4;

    function rgb(css) { return (css.match(/[\d.]+/g) || []).slice(0, 3).map(Number); }

    function luminance(c) {
        const lin = c.map(function (v) { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); });
        return 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2];
    }

    function contrast(a, b) {
        const x = luminance(rgb(a)), y = luminance(rgb(b));
        return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
    }

    function pickTheme(theme) {
        const select = document.getElementById('posterTheme');
        select.value = theme;
        select.dispatchEvent(new Event('change', { bubbles: true }));
    }

    /** The colour picked as the DJ would: a swatch of the palette, "Motywu", or the browser's colour field for one of their own. */
    function pickAccent(colour) {
        const swatch = document.querySelector('#posterForm input[name="accent"][value="' + colour + '"]');
        if (swatch) {
            swatch.checked = true;
            swatch.dispatchEvent(new Event('change', { bubbles: true }));
            return;
        }
        const picker = document.getElementById('posterAccentPicker');
        picker.value = colour;
        picker.dispatchEvent(new Event('input', { bubbles: true }));
    }

    S2P.scenario({
        name: 'poster-theme-preview',
        page: 'qr-print-poster',
        title: 'the QR print\'s look: a theme is the page\'s at once, a swatch or the DJ\'s own colour colours the heading; "Zapisz" sends the theme and the colour',
        run: async function (t) {
            const h1 = document.querySelector('.poster h1');
            const paper = function () { return getComputedStyle(document.querySelector('.poster')).backgroundColor; };
            t.step('at first classic: white paper', [document.body.classList.contains('theme-classic'), paper()], [true, 'rgb(255, 255, 255)']);
            pickTheme('CLUB');
            t.step('the club theme: the page\'s class, a dark paper, at once', [document.body.classList.contains('theme-club'),
                document.body.classList.contains('theme-classic'), luminance(rgb(paper())) < 0.05], [true, false, true]);
            pickAccent('#c2185b');
            const club = getComputedStyle(h1).color;
            t.check('a swatch colours the heading, made readable on the dark paper (' + club + ', ' + contrast(club, paper()).toFixed(1) + ':1)',
                contrast(club, paper()) >= 4.5 && club !== 'rgb(194, 24, 91)');
            pickTheme('PASTEL');
            pickAccent('#123abc');
            t.step('the DJ\'s own colour: "Własny" picked, the heading in it', [document.getElementById('posterAccentOwn').checked,
                document.getElementById('posterAccentOwn').value, getComputedStyle(h1).color], [true, '#123abc', 'rgb(18, 58, 188)']);
            document.getElementById('posterSave').click();
            const form = document.getElementById('posterForm');
            await t.waitFor(function () { return document.getElementById('posterAnswer').textContent === form.dataset.saved; }, 'saved', 3000);
            const sent = (await t.stand.requests('POST /dj/qr-print/settings'))[0].q;
            t.step('sent: the theme and the colour', [sent.theme, sent.accent], ['PASTEL', '#123abc']);
            pickAccent('');
            t.step('"Motywu": the theme\'s own colour again', getComputedStyle(h1).color, 'rgb(176, 22, 79)');
        }
    });

    S2P.scenario({
        name: 'poster-themes-readable',
        page: 'qr-print-poster',
        title: 'every theme with the colours that read worst: the heading, the text and the quieter lines at least 4.5:1 on the paper; the code black on white',
        run: async function (t) {
            const poster = document.querySelector('.poster');
            const bad = [];
            themes().forEach(function (theme) {
                pickTheme(theme);
                ACCENTS.forEach(function (accent) {
                    pickAccent(accent);
                    const paper = getComputedStyle(poster).backgroundColor;
                    // the panel's language decides which of the pair are printed: a line not there is not checked
                    [['heading', '.poster h1 .ours > span'], ['English', '.poster h1 .second'], ['text', '.poster .code'],
                        ['quiet', '.poster .link .second']].forEach(function (pair) {
                        const el = document.querySelector(pair[1]);
                        if (!el) return;
                        const ratio = contrast(getComputedStyle(el).color, paper);
                        if (ratio < 4.5) bad.push(theme + ' ' + (accent || 'own') + ' ' + pair[0] + ' ' + ratio.toFixed(2));
                    });
                    const qr = document.querySelector('.poster .qr');
                    const tile = getComputedStyle(qr).backgroundColor;
                    if (theme !== 'CLASSIC' && tile !== 'rgb(255, 255, 255)') bad.push(theme + ' the code\'s tile ' + tile);
                    if (theme === 'CLASSIC' && paper !== 'rgb(255, 255, 255)') bad.push('classic paper ' + paper);
                });
            });
            t.step('nothing below 4.5:1, the code always on white', bad, []);
        }
    });

    S2P.scenario({
        name: 'poster-themes-fit',
        page: 'qr-print-poster-logo',
        title: 'every theme with the DJ\'s logo, three profiles, the tip and the longest words: one A4 page',
        run: async function (t) {
            document.getElementById('posterHeading').value = 'W'.repeat(40);
            document.getElementById('posterHeading').dispatchEvent(new Event('input', { bubbles: true }));
            document.getElementById('posterLine').value = 'W'.repeat(60);
            document.getElementById('posterLine').dispatchEvent(new Event('input', { bubbles: true }));
            const heights = themes().map(function (theme) {
                pickTheme(theme);
                return theme + ' ' + Math.round(document.querySelector('.poster').getBoundingClientRect().height);
            });
            t.step('one A4 page in every theme (' + heights.join(', ') + ' ≤ ' + Math.round(A4_PRINTABLE) + ')',
                heights.filter(function (h) { return Number(h.split(' ')[1]) > A4_PRINTABLE; }), []);
        }
    });

    S2P.scenario({
        name: 'poster-themes-powered-aligned',
        page: 'qr-print-poster-logo',
        title: 'every theme with the DJ\'s logo: "Działa na" and our small mark at the foot on one centre line; our big mark (no DJ\'s logo) apart from the code\'s tile',
        run: async function (t) {
            const ours = document.querySelector('.poster .powered');
            const off = [];
            themes().forEach(function (theme) {
                pickTheme(theme);
                const words = ours.firstElementChild.getBoundingClientRect(), mark = ours.querySelector('.logo img').getBoundingClientRect();
                const apart = Math.abs((words.top + words.bottom) / 2 - (mark.top + mark.bottom) / 2);
                if (apart > 1) off.push(theme + ' ' + Math.round(apart * 10) / 10 + ' px');
            });
            t.step('the words and the mark on one centre line in every theme', off, []);
            const box = document.querySelector('[data-poster-toggle="logo"]');
            box.checked = false;
            box.dispatchEvent(new Event('change', { bubbles: true }));
            const close = themes().filter(function (theme) { return theme !== 'CLASSIC'; }).filter(function (theme) {
                pickTheme(theme);
                const big = document.querySelector('.poster > .logo').getBoundingClientRect();
                return document.querySelector('.poster .qr').getBoundingClientRect().top - big.bottom < 3 * 96 / 25.4;
            });
            t.step('our big mark at least 3 mm above the code\'s tile in every theme with a paper of its own', close, []);
        }
    });

    S2P.scenario({
        name: 'poster-themes-cards-fit',
        page: 'qr-print-cards-logo',
        title: 'every theme on the cards with the DJ\'s logo, three profiles, the tip and the longest words: nothing leaves a card',
        run: async function (t) {
            document.getElementById('posterHeading').value = 'W'.repeat(40);
            document.getElementById('posterHeading').dispatchEvent(new Event('input', { bubbles: true }));
            document.getElementById('posterLine').value = 'W'.repeat(60);
            document.getElementById('posterLine').dispatchEvent(new Event('input', { bubbles: true }));
            const out = [];
            themes().forEach(function (theme) {
                pickTheme(theme);
                const card = document.querySelector('.card');
                const whole = card.getBoundingClientRect();
                Array.from(card.querySelectorAll('.text > p, .side > *')).filter(function (el) { return el.checkVisibility(); }).forEach(function (el) {
                    const box = el.getBoundingClientRect();
                    if (box.top < whole.top + 2 || box.bottom > whole.bottom - 2 || box.right > whole.right - 2) out.push(theme + ' ' + el.className);
                });
            });
            t.step('every part inside its card, in every theme', out, []);
        }
    });
})();
