// "Krój nagłówka" (V51, qr-print.js): the heading's font as the DJ picks it — the theme's (the default: the sans, the serif on the
// elegant theme), the sans, the serif or the script ("S2P Script", Dancing Script) — in the heading only: the DJ's line, the address,
// the code's texts stay in the sans. It shows at once, "Zapisz" sends it, the switch to the other layouts keeps it. With the longest
// words, the DJ's logo, three profiles and the tip, every font in every theme still fits A4, a card and a tent's face, the heading
// whole in its lines.
(function () {
    const THEMES = ['CLASSIC', 'ELEGANT', 'CLUB', 'PASTEL'];
    const FONTS = ['THEME', 'SANS', 'SERIF', 'SCRIPT'];
    const A4_PRINTABLE = (297 - 2 * 12) * 96 / 25.4;
    const PT = 96 / 72;

    function type(id, text) {
        const field = document.getElementById(id);
        field.value = text;
        field.dispatchEvent(new Event('input', { bubbles: true }));
    }

    function pick(id, value) {
        const select = document.getElementById(id);
        select.value = value;
        select.dispatchEvent(new Event('change', { bubbles: true }));
    }

    function family(el) { return getComputedStyle(el).fontFamily.split(',')[0].replace(/"/g, '').trim(); }

    function shown(el) { return !!el && el.checkVisibility(); }

    /** A heading whole (no line the clamp hid), in at most {@code most} lines. */
    function cut(el, most) {
        const line = parseFloat(getComputedStyle(el).lineHeight);
        return el.scrollHeight > el.clientHeight + line / 2 || Math.round(el.clientHeight / line) > most;
    }

    function each(run) {
        THEMES.forEach(function (theme) {
            pick('posterTheme', theme);
            FONTS.forEach(function (font) {
                pick('posterFont', font);
                run(theme + ' ' + font);
            });
        });
    }

    async function longest(t, logos) {
        await document.fonts.ready;
        type('posterHeading', 'W'.repeat(40));
        type('posterLine', 'W'.repeat(60));
        await t.waitFor(function () { return Array.from(document.querySelectorAll(logos)).every(function (i) { return i.complete; }); }, 'the logos', 3000);
        await document.fonts.load('700 30pt "S2P Script"', 'W');
        await document.fonts.load('700 30pt "S2P Serif"', 'W');
    }

    S2P.scenario({
        name: 'poster-font-choice',
        page: 'qr-print-poster',
        title: '"Krój nagłówka": the theme\'s, the sans, the serif or the script — on the heading at once, never on the line or the code\'s texts; "Zapisz" sends it',
        run: async function (t) {
            const select = document.getElementById('posterFont');
            t.step('four fonts, the theme\'s picked', [Array.from(select.options).map(function (o) { return o.value; }), select.value],
                [FONTS, 'THEME']);
            type('posterLine', 'Sobota, sala Pod Lipami');
            const heading = document.querySelector('.poster h1 .ours > span');
            const seen = [];
            [['CLASSIC', 'THEME'], ['ELEGANT', 'THEME'], ['ELEGANT', 'SANS'], ['CLASSIC', 'SERIF'], ['CLUB', 'SCRIPT']].forEach(function (pair) {
                pick('posterTheme', pair[0]);
                pick('posterFont', pair[1]);
                seen.push(pair.join(' ') + ': ' + family(heading));
            });
            t.step('the heading in the font picked (the theme\'s: its own)', seen, ['CLASSIC THEME: S2P Sans', 'ELEGANT THEME: S2P Serif',
                'ELEGANT SANS: S2P Sans', 'CLASSIC SERIF: S2P Serif', 'CLUB SCRIPT: S2P Script']);
            t.step('the line and the code\'s texts stay in the sans', [family(document.querySelector('.poster .own-line')),
                family(document.querySelector('.poster .code'))], ['S2P Sans', 'S2P Sans']);
            t.step('the script with Polish letters: both halves', (await document.fonts.load('700 30pt "S2P Script"', 'Zażółć gęślą jaźń')).length, 2);
            const form = document.getElementById('posterForm');
            t.step('not saved yet, it says', document.getElementById('posterAnswer').textContent, form.dataset.unsaved);
            document.getElementById('posterSave').click();
            await t.waitFor(function () { return document.getElementById('posterAnswer').textContent === form.dataset.saved; }, 'saved', 3000);
            t.step('sent', (await t.stand.requests('POST /dj/qr-print/settings')).pop().q.font, 'SCRIPT');
        }
    });

    S2P.scenario({
        name: 'poster-font-fits-a4',
        page: 'qr-print-poster-logo',
        title: 'every font in every theme with the longest words, the DJ\'s logo, three profiles and the tip: one A4 page, the heading whole in two lines',
        run: async function (t) {
            await longest(t, '.poster .dj-logo');
            const bad = [];
            each(function (name) {
                const height = document.querySelector('.poster').getBoundingClientRect().height;
                if (height > A4_PRINTABLE) bad.push(name + ' ' + Math.round(height) + ' px');
                if (cut(document.querySelector('.poster h1 .own'), 2)) bad.push(name + ' heading cut');
            });
            t.step('one page, the heading whole', bad, []);
        }
    });

    S2P.scenario({
        name: 'poster-font-fits-cards',
        page: 'qr-print-cards-logo',
        title: 'every font in every theme on a card with the longest words: every part inside, the heading whole in three lines, the script not under 5.5 pt (its small letters brought to the sans\'s height)',
        run: async function (t) {
            await longest(t, '.card .dj-logo');
            const bad = [];
            each(function (name) {
                const card = document.querySelector('.card'), whole = card.getBoundingClientRect();
                Array.from(card.querySelectorAll('.text > p, .side > *')).filter(shown).forEach(function (el) {
                    const r = el.getBoundingClientRect();
                    if (r.top < whole.top + 2 || r.bottom > whole.bottom - 2 || r.right > whole.right - 2) bad.push(name + ' out ' + el.className);
                });
                const title = card.querySelector('.title .own');
                if (cut(title, 3)) bad.push(name + ' heading cut');
                if (name.endsWith('SCRIPT') && parseFloat(getComputedStyle(title).fontSize) / PT < 5.49) bad.push(name + ' script too small');
            });
            t.step('inside, whole, readable', bad, []);
        }
    });

    S2P.scenario({
        name: 'poster-font-fits-tent',
        page: 'qr-print-tent-logo',
        title: 'every font in every theme on a tent with the longest words: every part on its face, the heading whole in three lines',
        run: async function (t) {
            await longest(t, '.tent .dj-logo');
            const bad = [];
            each(function (name) {
                document.querySelectorAll('.tent .face').forEach(function (face, i) {
                    const whole = face.getBoundingClientRect();
                    Array.from(face.querySelectorAll('.side > *, .text > *')).filter(shown).forEach(function (el) {
                        const r = el.getBoundingClientRect();
                        if (r.top < whole.top + 2 || r.bottom > whole.bottom - 2 || r.left < whole.left + 2 || r.right > whole.right - 2) {
                            bad.push(name + ' face ' + i + ' out ' + el.className);
                        }
                    });
                    if (cut(face.querySelector('.title .own'), 3)) bad.push(name + ' face ' + i + ' heading cut');
                });
            });
            t.step('on its face, whole', bad, []);
        }
    });
})();
