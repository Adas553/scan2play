// The print itself (run.py, "pdf"): once a scenario has set the page — the longest words, the DJ's logo, a theme, the tables —, the page
// is printed to a PDF as Chrome prints it and its A4 pages counted. The other scenarios measure "one A4 page" on the screen, against the
// stylesheet's printable height; this is what a printer gets (the print's own rules: @media print, @page). The PDFs stay in results/.
(function () {
    function type(id, text) {
        const field = document.getElementById(id);
        field.value = text;
        field.dispatchEvent(new Event('input', { bubbles: true }));
    }

    function pick(id, value) {
        const field = document.getElementById(id);
        field.value = value;
        field.dispatchEvent(new Event('change', { bubbles: true }));
    }

    /** The longest words the editor takes, the theme and the font; the logos loaded and the fonts in before the verdict (and the print). */
    async function longest(t, theme, font) {
        type('posterHeading', 'W'.repeat(40));
        type('posterLine', 'W'.repeat(60));
        pick('posterTheme', theme);
        pick('posterFont', font);
        await t.waitFor(function () { return Array.from(document.querySelectorAll('main img')).every(function (i) { return i.complete; }); }, 'the images', 3000);
        await document.fonts.ready;
        t.step('set: ' + theme + ', ' + font, document.body.classList.contains('theme-' + theme.toLowerCase()), true);
    }

    S2P.scenario({
        name: 'poster-print-pdf',
        page: 'qr-print-poster-logo',
        pdf: 1,
        title: 'the poster printed (the gala, the script, the longest words, the DJ\'s logo, three profiles, the tip): one A4 page',
        run: async function (t) { await longest(t, 'GALA', 'SCRIPT'); }
    });

    S2P.scenario({
        name: 'poster-print-pdf-large',
        page: 'qr-print-poster-logo',
        pdf: 1,
        title: 'the poster printed with the larger heading (V54) in the script, the gala, the longest words, the DJ\'s logo, three profiles, the tip: one A4 page',
        run: async function (t) {
            const radio = document.querySelector('#posterForm input[name="size"][value="LARGE"]');
            radio.checked = true;
            radio.dispatchEvent(new Event('change', { bubbles: true }));
            await longest(t, 'GALA', 'SCRIPT');
            t.step('larger', document.body.classList.contains('size-large'), true);
        }
    });

    S2P.scenario({
        name: 'poster-print-pdf-cards',
        page: 'qr-print-cards-logo',
        pdf: 1,
        title: 'the eight cards printed (the confetti, the serif, the longest words, the DJ\'s logo, three profiles, the tip): one A4 page',
        run: async function (t) { await longest(t, 'CONFETTI', 'SERIF'); }
    });

    S2P.scenario({
        name: 'poster-print-pdf-tables',
        page: 'qr-print-cards',
        pdf: 3,
        title: 'twenty-two numbered cards from table 22 printed: three A4 pages',
        run: async function (t) {
            type('posterTables', '22');
            type('posterTablesFrom', '22');
            t.step('the cards', document.querySelectorAll('.card').length, 22);
        }
    });

    S2P.scenario({
        name: 'poster-print-pdf-tent',
        page: 'qr-print-tent-logo',
        pdf: 1,
        title: 'the table tent printed (the rustic, the longest words, the DJ\'s logo, three profiles, the tip): one A4 page',
        run: async function (t) { await longest(t, 'RUSTIC', 'THEME'); }
    });

    S2P.scenario({
        name: 'poster-print-pdf-tents',
        page: 'qr-print-tent',
        pdf: 3,
        title: 'three numbered tents printed: three A4 pages, a tent a page',
        run: async function (t) {
            type('posterTables', '3');
            t.step('the tents', document.querySelectorAll('.tent').length, 3);
        }
    });

    // The control: nine cards are two pages — the count is told one, and must fail, or it cannot see a print a page too long.
    S2P.scenario({
        name: 'poster-print-pdf-control',
        page: 'qr-print-cards',
        pdf: 1,
        control: { mustFail: ['the print'] },
        title: 'CONTROL: nine cards printed are two pages, told one — the count must fail',
        run: async function (t) {
            type('posterTables', '9');
            t.step('nine cards', document.querySelectorAll('.card').length, 9);
        }
    });
})();
