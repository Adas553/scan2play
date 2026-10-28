// The QR print as the DJ sets it (V46, qr-print.js on /dj/qr-print): what is typed or ticked in the editor shows on the poster and the
// cards at once; "Zapisz" keeps it in the background (POST /dj/qr-print/settings, the CSRF token in the header) and says so; a new
// language is saved and the page loads again (the server writes the print's language). With the longest words the fields take and
// every part shown, the poster is still one A4 page and nothing leaves a card.
(function () {
    function type(id, text) {
        const field = document.getElementById(id);
        field.value = text;
        field.dispatchEvent(new Event('input', { bubbles: true }));
    }

    function tick(part, on) {
        const box = document.querySelector('[data-poster-toggle="' + part + '"]');
        box.checked = on;
        box.dispatchEvent(new Event('change', { bubbles: true }));
    }

    function shown(el) { return !!el && el.checkVisibility(); }

    /** The printable height of an A4 page in CSS px: 297 mm less the page's margins (qr-print.css, @page 12 mm). */
    const A4_PRINTABLE = (297 - 2 * 12) * 96 / 25.4;
    const LONGEST_HEADING = 'W'.repeat(40);
    const LONGEST_LINE = 'W'.repeat(60);

    S2P.scenario({
        name: 'poster-editor-preview',
        page: 'qr-print-poster',
        title: 'the QR print\'s editor: the heading, the line and the parts show at once; "Zapisz" sends them in the background and says so; a new language is saved, then the page loads again',
        run: async function (t) {
            const loads = [];
            navigation.addEventListener('navigate', function (e) {
                loads.push(e.navigationType);
                e.preventDefault();
            });
            const h1 = document.querySelector('.poster h1');
            t.step('at first ours', [shown(h1.querySelector('[data-poster-ours-heading]')), shown(h1.querySelector('[data-poster-heading]')),
                shown(document.querySelector('.poster [data-poster-line]'))], [true, false, false]);

            type('posterHeading', 'Osiemnastka Dawida');
            type('posterLine', 'Sobota, sala Pod Lipami');
            t.step('typed: the DJ\'s heading in place of ours, the line under it',
                [h1.querySelector('[data-poster-heading]').textContent, shown(h1.querySelector('[data-poster-heading]')),
                    shown(h1.querySelector('[data-poster-ours-heading]')), document.querySelector('.poster [data-poster-line]').textContent],
                ['Osiemnastka Dawida', true, false, 'Sobota, sala Pod Lipami']);
            type('posterHeading', '   ');
            t.step('emptied: ours again', [shown(h1.querySelector('[data-poster-ours-heading]')), shown(h1.querySelector('[data-poster-heading]'))],
                [true, false]);
            type('posterHeading', 'Osiemnastka Dawida');

            tick('address', false);
            tick('links', false);
            t.step('ticked off: the address, the code and the profiles leave the paper',
                [shown(document.querySelector('.poster .link')), shown(document.querySelector('.poster .code')),
                    shown(document.querySelector('.poster .dj-links'))], [false, false, false]);
            tick('links', true);
            t.step('ticked on: back', shown(document.querySelector('.poster .dj-links')), true);

            document.getElementById('posterSave').click();
            const form = document.getElementById('posterForm');
            await t.waitFor(function () { return document.getElementById('posterAnswer').textContent === form.dataset.saved; }, 'saved', 3000);
            const sent = (await t.stand.requests('POST /dj/qr-print/settings'))[0].q;
            t.step('sent: the words, the language (the English panel\'s, picked until the DJ picks another), the boxes ticked; the party; the CSRF token in the header',
                [sent.heading, sent.line, sent.language, sent.showLinks, sent.showTip, sent.showAddress, sent.partyCode, sent['X-CSRF-TOKEN']],
                ['Osiemnastka Dawida', 'Sobota, sala Pod Lipami', 'EN', 'true', 'true', undefined, 'PRNT1', 'harness-csrf-token']);
            t.step('nothing loaded', loads, []);

            const select = document.getElementById('posterLanguage');
            select.value = 'PL';
            select.dispatchEvent(new Event('change', { bubbles: true }));
            await t.waitFor(function () { return loads.length > 0; }, 'the page again', 3000);
            const second = (await t.stand.requests('POST /dj/qr-print/settings'))[1].q;
            t.step('a new language: saved first, then the page again', [second.language, loads], ['PL', ['reload']]);

            await t.stand.config({ postStatus: { '/dj/qr-print/settings': 403 } });
            document.getElementById('posterSave').click();
            await t.waitFor(function () { return document.getElementById('posterAnswer').classList.contains('refused'); }, 'refused', 3000);
            t.step('refused (the session ran out): said, never "saved"', document.getElementById('posterAnswer').textContent, form.dataset.refused);
            await t.stand.config({ postStatus: { '/dj/qr-print/settings': null } });
        }
    });

    S2P.scenario({
        name: 'poster-editor-longest-fits-a4',
        page: 'qr-print-poster-logo',
        title: 'the QR poster with the longest heading and line the editor takes, the DJ\'s logo, three profiles and the tip: one A4 page',
        run: async function (t) {
            t.step('the fields take no more than the columns', [document.getElementById('posterHeading').maxLength,
                document.getElementById('posterLine').maxLength], [40, 60]);
            type('posterHeading', LONGEST_HEADING + 'extra');
            type('posterLine', LONGEST_LINE);
            const logo = document.querySelector('.poster .dj-logo');
            await t.waitFor(function () { return logo.complete; }, 'the logo', 3000);
            t.check('everything shown', [logo, document.querySelector('.poster .dj-links'), document.querySelector('.poster .tip'),
                document.querySelector('.poster .link'), document.querySelector('.poster [data-poster-line]')].every(shown));
            const poster = document.querySelector('.poster').getBoundingClientRect();
            t.check('one A4 page: ' + Math.round(poster.height) + ' ≤ ' + Math.round(A4_PRINTABLE) + ' px', poster.height <= A4_PRINTABLE);
            const page = document.documentElement.clientWidth;
            t.check('the long words wrap inside the page', Array.from(document.querySelectorAll('.poster h1, .poster .own-line'))
                .every(function (el) { const box = el.getBoundingClientRect(); return box.left >= 0 && box.right <= page; }));
        }
    });

    S2P.scenario({
        name: 'poster-editor-longest-fits-cards',
        page: 'qr-print-cards-logo',
        title: 'the QR cards with the longest heading and line, the DJ\'s logo, three profiles and the tip: nothing leaves a card',
        run: async function (t) {
            type('posterHeading', LONGEST_HEADING);
            type('posterLine', LONGEST_LINE);
            const cards = Array.from(document.querySelectorAll('.card'));
            await t.waitFor(function () { return Array.from(document.querySelectorAll('.card .dj-logo')).every(function (i) { return i.complete; }); }, 'the logos', 3000);
            const out = cards.filter(function (card) {
                const whole = card.getBoundingClientRect();
                return Array.from(card.querySelectorAll('.text > p, .side > *')).filter(shown).some(function (el) {
                    const box = el.getBoundingClientRect();
                    return box.top < whole.top + 2 || box.bottom > whole.bottom - 2 || box.right > whole.right - 2;
                });
            });
            t.step('every part inside its card', out.length, 0);
        }
    });

    S2P.scenario({
        name: 'poster-editor-phone',
        page: 'qr-print-poster',
        viewport: '390,844',
        title: 'the QR print\'s editor on a phone: every field and box at least 44 px high, nothing wider than the screen',
        run: async function (t) {
            const width = document.documentElement.clientWidth;
            const small = Array.from(document.querySelectorAll('#posterForm input[type="text"], #posterForm select, #posterForm .editor-checks label, #posterForm button'))
                .filter(shown).filter(function (el) { return el.getBoundingClientRect().height < 43.5; });
            t.step('every target at least 44 px', small.map(function (el) { return el.id || el.textContent.trim(); }), []);
            t.check('no sideways scroll (' + document.documentElement.scrollWidth + ' ≤ ' + width + ')', document.documentElement.scrollWidth <= width);
        }
    });

    // The review of 2026-10-28: the editor and its bar were a blue-grey of their own with white fields and a light grey desk — another
    // app beside the panel. Now the panel's colours; only the paper is light. contrast() is the helper of dj-booking.js.
    S2P.scenario({
        name: 'poster-editor-panel-colours',
        page: 'qr-print-poster',
        viewport: '1440,900',
        title: 'the QR print\'s editor in the panel\'s colours: the bar and the editor a card\'s #161616, a group #1e1e1e, black fields, a dark desk; the paper white; every word readable',
        run: async function (t) {
            const bg = function (el) { return getComputedStyle(el).backgroundColor; };
            const editor = document.querySelector('.editor');
            const field = editor.querySelector('input[type="text"]');
            t.step('the bar, the editor, a group, a field', [bg(document.querySelector('.toolbar')), bg(editor), bg(editor.querySelector('.editor-group')), bg(field)],
                ['rgb(22, 22, 22)', 'rgb(22, 22, 22)', 'rgb(30, 30, 30)', 'rgb(0, 0, 0)']);
            t.step('the desk dark, the paper white', [getComputedStyle(document.body).backgroundImage.indexOf('rgb(11, 11, 11)') >= 0, bg(document.querySelector('.poster'))],
                [true, 'rgb(255, 255, 255)']);
            const words = Array.from(editor.querySelectorAll('h2, label, legend, input[type="text"], select'));
            t.step('every word of the editor readable (4.5 : 1)', words.filter(function (el) { return contrast(el) < 4.5; })
                .map(function (el) { return el.tagName + ' ' + el.textContent.trim().slice(0, 20); }), []);
        }
    });
})();
