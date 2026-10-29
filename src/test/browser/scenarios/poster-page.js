// The poster as its A4 page (qr-print.css .poster, qr-print.js fitPoster): the sheet is the printable page — 186 × 272 mm, the
// theme's frame down to its foot — on the computer's preview as on paper (the preview took the width of its words, 118 mm, and the
// frame ended three quarters down the printed page). The room the words and the parts leave goes to the code, larger up to 140 mm, read
// from further away; what is left centres them. A computer's column narrower than the sheet makes it smaller on the screen (zoom)
// and never on paper.
(function () {
    const MM = 96 / 25.4;
    const PAGE = 272 * MM;          // the sheet: the printable height of A4 (297 mm less @page's 12 mm twice) and a millimetre to spare
    const QR_MOST = 140 * MM;
    const THEMES = ['CLASSIC', 'ELEGANT', 'CLUB', 'PASTEL', 'RUSTIC', 'GALA', 'NEON', 'CONFETTI'];

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

    function tick(part, on) {
        const box = document.querySelector('[data-poster-toggle="' + part + '"]');
        box.checked = on;
        box.dispatchEvent(new Event('change', { bubbles: true }));
    }

    /** Every shown part of the poster inside its sheet. */
    function inside(poster) {
        const sheet = poster.getBoundingClientRect();
        return Array.from(poster.children).filter(function (el) { return el.checkVisibility(); }).every(function (el) {
            const box = el.getBoundingClientRect();
            return box.top >= sheet.top - 1 && box.bottom <= sheet.bottom + 1 && box.left >= sheet.left - 1 && box.right <= sheet.right + 1;
        });
    }

    S2P.scenario({
        name: 'poster-preview-a4-wide',
        page: 'qr-print-poster',
        viewport: '1440,900',
        title: 'the computer\'s preview of the poster is its A4 page: 186 mm wide whatever its words, as high as the printed page, beside the editor',
        run: async function (t) {
            await document.fonts.ready;
            const poster = document.querySelector('main.poster');
            t.check('186 mm wide (' + (poster.offsetWidth / MM).toFixed(1) + ' mm)', Math.abs(poster.offsetWidth - 186 * MM) < 2);
            t.check('as high as the printed page (' + (poster.offsetHeight / MM).toFixed(1) + ' mm)', Math.abs(poster.offsetHeight - PAGE) < 2);
            const box = poster.getBoundingClientRect(), editor = document.getElementById('posterForm').getBoundingClientRect();
            t.check('at its size, beside the editor', Math.abs(box.width - 186 * MM) < 2 && box.left > editor.right);
            t.check('no sideways scroll', document.documentElement.scrollWidth <= document.documentElement.clientWidth);
        }
    });

    S2P.scenario({
        name: 'poster-preview-a4-laptop',
        page: 'qr-print-poster',
        viewport: '1100,800',
        title: 'a column narrower than the sheet (1100 px): the preview made smaller to it, laid out as on paper, the page\'s white margin inside the window',
        run: async function (t) {
            await document.fonts.ready;
            const poster = document.querySelector('main.poster');
            t.check('laid out as on paper (' + (poster.offsetWidth / MM).toFixed(1) + ' mm)', Math.abs(poster.offsetWidth - 186 * MM) < 2);
            const box = poster.getBoundingClientRect(), editor = document.getElementById('posterForm').getBoundingClientRect();
            const zoom = box.width / poster.offsetWidth;
            const margin = 12 * MM * zoom, width = document.documentElement.clientWidth;
            t.check('made smaller (' + zoom.toFixed(2) + ')', zoom < 1);
            t.check('the sheet and its white margin between the editor and the window\'s edge (' + Math.round(box.left - margin) + ' … '
                + Math.round(box.right + margin) + ' of ' + width + ')', box.left - margin >= editor.right && box.right + margin <= width);
            t.check('no sideways scroll', document.documentElement.scrollWidth <= width);
            t.step('only on the screen: never a zoom of its own (the print has none)', poster.style.zoom, '');
        }
    });

    S2P.scenario({
        name: 'poster-fills-the-page',
        page: 'qr-print-poster',
        viewport: '1440,900',
        title: 'the poster fills its page in every theme: the sheet as high as the page, the room left to the code — up to 140 mm —, every part inside',
        run: async function (t) {
            await document.fonts.ready;
            const poster = document.querySelector('main.poster'), qr = poster.querySelector('.qr');
            const bad = [];
            for (const theme of THEMES) {
                pick('posterTheme', theme);
                const size = qr.offsetWidth;
                if (Math.abs(poster.offsetHeight - PAGE) > 2) bad.push(theme + ': the sheet ' + (poster.offsetHeight / MM).toFixed(1) + ' mm');
                if (Math.abs(size - QR_MOST) > 2) bad.push(theme + ': the code ' + (size / MM).toFixed(1) + ' mm, not the most');
                if (Math.abs(qr.offsetHeight - size) > 1) bad.push(theme + ': the code not square');
                if (!inside(poster)) bad.push(theme + ': a part outside the sheet');
            }
            t.step('our words alone: the code at its most, the sheet the page, everything inside', bad, []);
        }
    });

    S2P.scenario({
        name: 'poster-fills-the-page-longest',
        page: 'qr-print-poster-logo',
        viewport: '1440,900',
        title: 'the longest words, the DJ\'s logo, three profiles and the tip: still one page, the code given what is left — never under its 100 mm —; the logo off, the code larger',
        run: async function (t) {
            const logo = document.querySelector('.poster .dj-logo');
            await t.waitFor(function () { return logo.complete; }, 'the logo', 3000);
            await document.fonts.ready;
            type('posterHeading', 'W'.repeat(40));
            type('posterLine', 'W'.repeat(60));
            const poster = document.querySelector('main.poster'), qr = poster.querySelector('.qr');
            const bad = [];
            for (const theme of THEMES) {
                pick('posterTheme', theme);
                if (poster.offsetHeight > PAGE + 2) bad.push(theme + ': the sheet ' + (poster.offsetHeight / MM).toFixed(1) + ' mm');
                if (qr.offsetWidth < 100 * MM - 1) bad.push(theme + ': the code ' + (qr.offsetWidth / MM).toFixed(1) + ' mm');
                if (!inside(poster)) bad.push(theme + ': a part outside the sheet');
            }
            t.step('one page in every theme, the code at least 100 mm, every part inside', bad, []);
            pick('posterTheme', 'CLASSIC');
            const withLogo = qr.offsetWidth;
            tick('logo', false);
            t.check('the logo off: its room to the code (' + (withLogo / MM).toFixed(1) + ' → ' + (qr.offsetWidth / MM).toFixed(1) + ' mm)',
                qr.offsetWidth > withLogo + MM);
            tick('logo', true);
            t.step('back on: the code as before', Math.round(qr.offsetWidth), Math.round(withLogo));
        }
    });
})();
