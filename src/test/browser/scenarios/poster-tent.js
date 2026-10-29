// The table tent (V50, /dj/qr-print?layout=tent): one A4 sheet folded across its middle — two faces, the upper one turned upside down,
// so the tent stands on a table and reads from both sides. And the number of tables (V50, the editor's "Liczba stołów"): the cards and
// the tents numbered 1 … N at once, as many of them as there are tables (the cards eight on a page as before); empty — no numbers, eight
// cards and one tent. With the longest words, the DJ's logo, three profiles, the tip and a table's number every part stays on its face
// and on its card, in every theme.
(function () {
    /** Every theme the editor offers (V47, V52): a new theme is checked here by itself. */
    function themes() { return Array.from(document.querySelectorAll('#posterTheme option')).map(function (o) { return o.value; }); }
    /** The printable height of an A4 page in CSS px: 297 mm less the page's margins (qr-print.css, @page 12 mm). */
    const A4_PRINTABLE = (297 - 2 * 12) * 96 / 25.4;
    const MM = 96 / 25.4;

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

    function shown(el) { return !!el && el.checkVisibility(); }

    function numbers(selector) {
        return Array.from(document.querySelectorAll(selector)).map(function (item) {
            const no = item.querySelector('[data-table-number]');
            return shown(no) ? no.querySelector('[data-table-n]').textContent : '';
        });
    }

    /** The parts of {@code box} (a face, a card) that are shown and reach out of it — 2 px inside its edge. */
    function outOf(box, parts) {
        const whole = box.getBoundingClientRect();
        return parts.filter(shown).filter(function (el) {
            const r = el.getBoundingClientRect();
            return r.top < whole.top + 2 || r.bottom > whole.bottom - 2 || r.left < whole.left + 2 || r.right > whole.right - 2;
        });
    }

    function overlap(a, b) {
        const x = a.getBoundingClientRect(), y = b.getBoundingClientRect();
        return x.left < y.right && y.left < x.right && x.top < y.bottom && y.top < x.bottom;
    }

    S2P.scenario({
        name: 'poster-tent-layout',
        page: 'qr-print-tent',
        title: 'the table tent: one A4 sheet, two faces of the same code and words, the upper one upside down, the fold across the middle; the switch has three layouts',
        run: async function (t) {
            const switches = Array.from(document.querySelectorAll('[data-layout-switch]'));
            t.step('three layouts, the tent\'s shown', [switches.length, switches.filter(function (a) { return a.classList.contains('active'); })
                .map(function (a) { return new URL(a.href).searchParams.get('layout'); })], [3, ['tent']]);
            const tents = document.querySelectorAll('.tent');
            t.step('one tent without tables', tents.length, 1);
            const faces = tents[0].querySelectorAll('.face');
            t.step('two faces', faces.length, 2);
            t.step('the upper face upside down, the lower one upright',
                [getComputedStyle(faces[0]).transform, getComputedStyle(faces[1]).transform], ['matrix(-1, 0, 0, -1, 0, 0)', 'none']);
            t.step('the code, the heading and the address on both faces', Array.from(faces).map(function (face) {
                return [shown(face.querySelector('.qr')), shown(face.querySelector('[data-poster-ours-heading]')), shown(face.querySelector('.link'))];
            }), [[true, true, true], [true, true, true]]);
            const sheet = tents[0].getBoundingClientRect();
            t.check('one A4 page: ' + Math.round(sheet.height) + ' ≤ ' + Math.round(A4_PRINTABLE) + ' px', sheet.height <= A4_PRINTABLE);
            const fold = tents[0].querySelector('.fold').getBoundingClientRect();
            t.check('the fold across the middle of the sheet (' + Math.round(fold.top - sheet.top) + ' of ' + Math.round(sheet.height) + ' px)',
                Math.abs(fold.top - (sheet.top + sheet.height / 2)) <= 1 && Math.abs(fold.width - sheet.width) <= 1);
            const upper = faces[0].getBoundingClientRect(), lower = faces[1].getBoundingClientRect();
            t.check('each face on its half, away from the fold (≥ 4 mm)', upper.bottom <= fold.top - 4 * MM && lower.top >= fold.top + 4 * MM);
            t.check('the code at least 60 mm wide (' + Math.round(faces[1].querySelector('.qr').getBoundingClientRect().width / MM) + ' mm)',
                faces[1].querySelector('.qr').getBoundingClientRect().width >= 60 * MM);
        }
    });

    S2P.scenario({
        name: 'poster-tent-longest-fits',
        page: 'qr-print-tent-logo',
        title: 'the table tent with the longest words, the DJ\'s logo, three profiles, the tip and a table\'s number: every part on its face, the sheet one A4 page, in every theme',
        run: async function (t) {
            type('posterHeading', 'W'.repeat(40));
            type('posterLine', 'W'.repeat(60));
            type('posterTables', '50');
            type('posterTablesFrom', '950');
            await t.waitFor(function () { return Array.from(document.querySelectorAll('.tent .dj-logo')).every(function (i) { return i.complete; }); }, 'the logos', 3000);
            const out = [];
            const pages = [];
            themes().forEach(function (theme) {
                pickTheme(theme);
                const tent = document.querySelectorAll('.tent')[49];
                tent.querySelectorAll('.face').forEach(function (face, i) {
                    outOf(face, Array.from(face.querySelectorAll('.side > *, .text > *'))).forEach(function (el) {
                        out.push(theme + ' face ' + i + ' ' + el.className);
                    });
                });
                if (tent.getBoundingClientRect().height > A4_PRINTABLE) pages.push(theme);
            });
            t.step('every part on its face, in every theme', out, []);
            t.step('one A4 page in every theme', pages, []);
            t.step('the 50th tent says it', numbers('.tent .face')[99], '999');
        }
    });

    S2P.scenario({
        name: 'poster-tables-cards',
        page: 'qr-print-cards',
        title: 'the number of tables on the cards: as many cards as tables, numbered 1 … N at once; emptied — eight cards, no numbers; more than 50 — 50; "Zapisz" sends it',
        run: async function (t) {
            const field = document.getElementById('posterTables');
            t.step('the field: a number, 1 … 50, empty at first', [field.type, field.min, field.max, field.value], ['number', '1', '50', '']);
            t.step('at first eight cards without numbers', numbers('.card'), ['', '', '', '', '', '', '', '']);
            type('posterTables', '12');
            t.step('12 tables: 12 cards, numbered', numbers('.card'), ['1', '2', '3', '4', '5', '6', '7', '8', '9', '10', '11', '12']);
            t.check('every card has its code', Array.from(document.querySelectorAll('.card')).every(function (c) { return shown(c.querySelector('.qr')); }));
            type('posterTables', '3');
            t.step('3 tables: 3 cards', numbers('.card'), ['1', '2', '3']);
            type('posterTables', '');
            t.step('emptied: eight cards again, no numbers', numbers('.card'), ['', '', '', '', '', '', '', '']);
            type('posterTables', '120');
            t.step('more than 50: 50 cards', document.querySelectorAll('.card').length, 50);
            type('posterTables', '12');
            document.getElementById('posterSave').click();
            const form = document.getElementById('posterForm');
            await t.waitFor(function () { return document.getElementById('posterAnswer').textContent === form.dataset.saved; }, 'saved', 3000);
            const sent = (await t.stand.requests('POST /dj/qr-print/settings')).pop().q;
            t.step('sent with the rest', [sent.tables, sent.partyCode], ['12', 'PRNT1']);
        }
    });

    S2P.scenario({
        name: 'poster-tables-from',
        page: 'qr-print-cards',
        title: 'the first table\'s number: 22 tables from 22 — tables 22 … 43; a reprint of one — 1 from 16; empty — from 1; at most 999; the summary under the fields says what prints; never saved',
        run: async function (t) {
            const summary = document.getElementById('posterTablesSummary');
            type('posterTables', '22');
            type('posterTablesFrom', '22');
            const printed = numbers('.card');
            t.step('22 from 22: tables 22 … 43', [printed.length, printed[0], printed[21]], [22, '22', '43']);
            t.step('the summary: the tables and the pages (8 cards a page)', [summary.hidden, summary.textContent.indexOf('22–43') >= 0,
                /\b3\b/.test(summary.textContent)], [false, true, true]);
            type('posterTables', '1');
            type('posterTablesFrom', '16');
            t.step('a reprint: 1 from 16 — the 16th alone', numbers('.card'), ['16']);
            t.check('the summary names the one table: ' + summary.textContent, summary.textContent.indexOf('16') >= 0 && summary.textContent.indexOf('–') < 0);
            type('posterTablesFrom', '');
            t.step('emptied: from 1', numbers('.card'), ['1']);
            type('posterTablesFrom', '5000');
            t.step('at most 999', numbers('.card'), ['999']);
            type('posterTables', '');
            t.step('no tables: eight cards, no numbers, no summary, whatever the first number', [numbers('.card'), summary.hidden],
                [['', '', '', '', '', '', '', ''], true]);
            type('posterTables', '16');
            document.getElementById('posterSave').click();
            const form = document.getElementById('posterForm');
            await t.waitFor(function () { return document.getElementById('posterAnswer').textContent === form.dataset.saved; }, 'saved', 3000);
            const sent = (await t.stand.requests('POST /dj/qr-print/settings')).pop().q;
            t.step('saved: the tables, never the first number', [sent.tables, Object.keys(sent).filter(function (k) { return /from/i.test(k); })], ['16', []]);
            type('posterTablesFrom', '12');
            t.step('the first number changed after "Zapisz": still saved, nothing unsaved', document.getElementById('posterAnswer').textContent, form.dataset.saved);
        }
    });

    S2P.scenario({
        name: 'poster-tables-from-tents',
        page: 'qr-print-tent',
        title: 'tents from a number: 1 from 16 — one tent, both faces 16; 3 from 10 — 10, 11, 12, a page each',
        run: async function (t) {
            type('posterTables', '1');
            type('posterTablesFrom', '16');
            t.step('the 16th tent alone', numbers('.tent .face'), ['16', '16']);
            type('posterTables', '3');
            type('posterTablesFrom', '10');
            t.step('3 from 10', numbers('.tent .face'), ['10', '10', '11', '11', '12', '12']);
            t.check('the summary: 3 pages — ' + document.getElementById('posterTablesSummary').textContent,
                /\b3\b/.test(document.getElementById('posterTablesSummary').textContent.split('·').pop()));
        }
    });

    S2P.scenario({
        name: 'poster-tables-tents',
        page: 'qr-print-tent',
        title: 'the number of tables on the tents: a tent a table, both faces numbered; emptied — one tent without a number',
        run: async function (t) {
            type('posterTables', '3');
            t.step('3 tables: 3 tents, both faces numbered', numbers('.tent .face'), ['1', '1', '2', '2', '3', '3']);
            type('posterTables', '');
            t.step('emptied: one tent, no number', numbers('.tent .face'), ['', '']);
        }
    });

    S2P.scenario({
        name: 'poster-tables-cards-fit',
        page: 'qr-print-cards-logo',
        title: 'a table\'s number on a card with the longest words, the DJ\'s logo, three profiles and the tip: inside the card, over none of its parts, in every theme',
        run: async function (t) {
            type('posterHeading', 'W'.repeat(40));
            type('posterLine', 'W'.repeat(60));
            type('posterTables', '50');
            type('posterTablesFrom', '950');
            await t.waitFor(function () { return Array.from(document.querySelectorAll('.card .dj-logo')).every(function (i) { return i.complete; }); }, 'the logos', 3000);
            const bad = [];
            themes().forEach(function (theme) {
                pickTheme(theme);
                const card = document.querySelectorAll('.card')[49];
                const no = card.querySelector('[data-table-number]');
                const parts = Array.from(card.querySelectorAll('.text > p, .side > *')).filter(shown);
                if (!shown(no)) bad.push(theme + ' no number');
                outOf(card, parts.concat([no])).forEach(function (el) { bad.push(theme + ' out ' + el.className); });
                parts.filter(function (el) { return overlap(no, el); }).forEach(function (el) { bad.push(theme + ' over ' + el.className); });
            });
            t.step('the number in the card, over no part, in every theme', bad, []);
        }
    });

    S2P.scenario({
        name: 'poster-tent-phone',
        page: 'qr-print-tent',
        viewport: '390,844',
        title: 'the table tent on a phone: the preview fits the screen, no sideways scroll',
        run: async function (t) {
            const width = document.documentElement.clientWidth;
            t.check('no sideways scroll (' + document.documentElement.scrollWidth + ' ≤ ' + width + ')', document.documentElement.scrollWidth <= width);
            const tent = document.querySelector('.tent').getBoundingClientRect();
            t.check('the sheet inside the screen', tent.left >= 0 && tent.right <= width);
        }
    });
})();

// A phone's preview (qr-print.js): the sheet laid out as it prints — the poster 186 mm wide, the cards two by two, the tent — and made
// smaller to the screen's width, so the frame, the code's tile and the margins look as on paper; before, the poster was laid out
// again for the narrow screen (the code over the frame). The DJ's heading is fitted as on a computer.
(function () {
    const MM = 96 / 25.4;

    function type(id, text) {
        const field = document.getElementById(id);
        field.value = text;
        field.dispatchEvent(new Event('input', { bubbles: true }));
    }

    function inside(inner, outer) {
        const a = inner.getBoundingClientRect(), b = outer.getBoundingClientRect();
        return a.left >= b.left - 0.5 && a.right <= b.right + 0.5 && a.top >= b.top - 0.5 && a.bottom <= b.bottom + 0.5;
    }

    S2P.scenario({
        name: 'poster-phone-preview',
        page: 'qr-print-poster-logo',
        viewport: '390,844',
        title: 'the poster on a phone: laid out as it prints (186 mm), made smaller to the screen, the code inside the frame, no sideways scroll; the heading fitted',
        run: async function (t) {
            const theme = document.getElementById('posterTheme');
            theme.value = 'ELEGANT';
            theme.dispatchEvent(new Event('change', { bubbles: true }));
            type('posterHeading', 'W'.repeat(40));
            await document.fonts.ready;
            await t.sleep(200);
            const poster = document.querySelector('.poster');
            const width = document.documentElement.clientWidth, box = poster.getBoundingClientRect();
            t.check('laid out as on paper: ' + poster.offsetWidth + ' px', Math.abs(poster.offsetWidth - 186 * MM) < 2);
            t.check('made smaller to the screen (' + Math.round(box.left) + ' … ' + Math.round(box.right) + ' of ' + width + ')',
                box.left >= 0 && box.right <= width && box.width >= width - 48);
            t.check('the code\'s tile inside the frame', inside(document.querySelector('.poster .qr'), poster));
            t.check('no sideways scroll (' + document.documentElement.scrollWidth + ' ≤ ' + width + ')', document.documentElement.scrollWidth <= width);
            const own = document.querySelector('.poster h1 .own'), line = parseFloat(getComputedStyle(own).lineHeight);
            t.check('the heading fitted to two lines', Math.round(own.clientHeight / line) <= 2);
            const foot = document.querySelector('.poster .powered').getBoundingClientRect();
            window.scrollTo(0, document.documentElement.scrollHeight);
            await t.sleep(100);
            t.check('nothing after the sheet but its margin: the page ends within 40 mm (' + Math.round(document.documentElement.scrollHeight - (foot.bottom + window.scrollY)) + ' px)',
                document.documentElement.scrollHeight - (poster.getBoundingClientRect().bottom + window.scrollY) <= 40 * MM);
        }
    });

    // A phone's address bar hides and shows while the page scrolls: the window's height changes and "resize" comes, again and again.
    // The scale depends on the width alone, so such a resize must not touch the sheet (taking the zoom off and on made the page
    // longer for a moment, and the page jumped at its foot).
    S2P.scenario({
        name: 'poster-phone-preview-steady',
        page: 'qr-print-poster',
        viewport: '390,844',
        title: 'the phone\'s preview stays put while the address bar comes and goes: a resize of the same width touches nothing',
        run: async function (t) {
            const main = document.querySelector('body > main');
            const zoom = main.style.zoom;
            const changes = [];
            new MutationObserver(function (list) { list.forEach(function (m) { changes.push(m.attributeName + ' ' + main.getAttribute('style')); }); })
                .observe(main, { attributes: true, attributeFilter: ['style'] });
            for (let i = 0; i < 5; i++) window.dispatchEvent(new Event('resize'));
            await t.sleep(100);
            t.step('the sheet untouched', [changes, main.style.zoom], [[], zoom]);
        }
    });

    S2P.scenario({
        name: 'poster-phone-preview-cards',
        page: 'qr-print-cards',
        viewport: '390,844',
        title: 'the cards on a phone: two by two as they print, made smaller to the screen, no sideways scroll',
        run: async function (t) {
            const cards = document.querySelectorAll('.card');
            const a = cards[0].getBoundingClientRect(), b = cards[1].getBoundingClientRect(), width = document.documentElement.clientWidth;
            t.check('two in a row, as on the sheet', Math.abs(a.top - b.top) < 1 && b.left > a.right - 1);
            t.check('inside the screen (' + Math.round(a.left) + ' … ' + Math.round(b.right) + ' of ' + width + ')', a.left >= 0 && b.right <= width);
            t.check('no sideways scroll', document.documentElement.scrollWidth <= width);
        }
    });
})();
