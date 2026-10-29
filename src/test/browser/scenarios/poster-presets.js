// The QR print's editor helps the DJ start (qr-print.js): "Wzór na okazję" — a wedding, an 18th, a birthday, a club, a pub, a company
// party — fills the heading with a text for the occasion in the print's language, as if typed (the preview, "Niezapisane zmiany"), and
// sets the look that suits it: the theme, the heading's font, the colour, the theme's paper; the list goes back to its prompt and the DJ
// changes any of it further.
(function () {
    function pick(select, value) {
        select.value = value;
        select.dispatchEvent(new Event('change', { bubbles: true }));
    }

    S2P.scenario({
        name: 'poster-presets',
        page: 'qr-print-poster',
        title: '"Wzór na okazję": an occasion fills the heading in the print\'s language, the preview shows it, the editor says unsaved; the list goes back to its prompt; every text within the heading\'s 40',
        run: async function (t) {
            const select = document.getElementById('posterPreset');
            const options = Array.from(select.options).slice(1);
            t.step('six occasions after the prompt', options.map(function (o) { return o.value; }),
                ['WEDDING', 'EIGHTEEN', 'BIRTHDAY', 'CLUB', 'PUB', 'COMPANY']);
            t.step('every text within 40 characters', options.filter(function (o) { return o.dataset.heading.length > 40 || !o.dataset.heading; })
                .map(function (o) { return o.value; }), []);
            pick(select, 'WEDDING');
            const heading = document.getElementById('posterHeading');
            const shown = document.querySelector('.poster [data-poster-heading]');
            t.step('the heading filled, the preview shows it', [heading.value, shown.textContent, shown.hidden],
                [options[0].dataset.heading, options[0].dataset.heading, false]);
            const form = document.getElementById('posterForm');
            t.step('not saved yet, it says', document.getElementById('posterAnswer').textContent, form.dataset.unsaved);
            t.step('the list back to its prompt', select.value, '');
            t.check('an English panel prints English: the wedding\'s text has no Polish letters (' + heading.value + ')',
                !/[ąćęłńóśźż]/i.test(heading.value));
        }
    });

    function look() {
        const form = document.getElementById('posterForm');
        return [form.elements.theme.value, form.elements.font.value, form.querySelector('input[name="accent"]:checked').value,
            form.querySelector('input[name="paper"]:checked').value];
    }

    S2P.scenario({
        name: 'poster-presets-look',
        page: 'qr-print-poster',
        title: '"Wzór na okazję" sets the look too: the wedding elegant with the script heading, the company classic in navy; the theme\'s paper again; on the preview at once',
        run: async function (t) {
            const own = document.getElementById('posterPaperOwn');
            own.checked = true;
            own.dispatchEvent(new Event('change', { bubbles: true }));
            const select = document.getElementById('posterPreset');
            pick(select, 'WEDDING');
            t.step('the wedding: elegant, the script, the theme\'s colour and paper', look(), ['ELEGANT', 'SCRIPT', '', '']);
            t.step('on the preview', [document.body.classList.contains('theme-elegant'), document.body.classList.contains('font-script'),
                document.body.classList.contains('paper-own')], [true, true, false]);
            pick(select, 'COMPANY');
            t.step('the company: classic, the sans, navy', look(), ['CLASSIC', 'SANS', '#1565c0', '']);
            pick(select, 'PUB');
            t.step('the pub after it: rustic, the theme\'s font and colour again', look(), ['RUSTIC', 'THEME', '', '']);
            pick(select, 'COMPANY');
            t.step('on the preview: the heading navy', getComputedStyle(document.querySelector('.poster [data-poster-heading]')).color,
                'rgb(21, 101, 192)');
            const options = Array.from(select.options).slice(1);
            t.step('every occasion has a look of the editor\'s', options.filter(function (o) {
                return !document.querySelector('#posterTheme option[value="' + o.dataset.theme + '"]')
                    || !document.querySelector('#posterFont option[value="' + o.dataset.font + '"]')
                    || !document.querySelector('input[name="accent"][value="' + (o.dataset.colour || '') + '"]');
            }).map(function (o) { return o.value; }), []);
        }
    });

    function shown(el) { return !!el && el.checkVisibility(); }

    S2P.scenario({
        name: 'poster-editor-fewer-fields',
        page: 'qr-print-poster',
        viewport: '1440,900',
        title: 'the editor starts with less: the occasion first, the paper, the font and the colour folded under "Więcej wyglądu", no tables on the poster; the weak heading\'s note in sight',
        run: async function (t) {
            const content = document.querySelector('#posterForm .editor-group');
            t.step('"Treść": the occasion, the heading, the second line, the language',
                Array.from(content.querySelectorAll('select, input')).filter(shown).map(function (el) { return el.id; }),
                ['posterPreset', 'posterHeading', 'posterLine', 'posterLanguage']);
            t.step('no tables on the poster', [shown(document.getElementById('posterTables')), shown(document.getElementById('posterTablesFrom'))],
                [false, false]);
            const more = document.getElementById('posterMoreLook');
            t.step('"Więcej wyglądu" folded, the paper, the font and the colour in it',
                [more.open, shown(document.getElementById('posterFont')), !!more.querySelector('#posterFont'),
                    !!more.querySelector('input[name="paper"]'), !!more.querySelector('input[name="accent"]')], [false, false, true, true, true]);
            t.step('the weak heading\'s note outside the fold', more.contains(document.getElementById('posterAccentNote')), false);
            more.open = true;
            t.step('unfolded: the fields there', shown(document.getElementById('posterFont')), true);
        }
    });

    S2P.scenario({
        name: 'poster-editor-tables-on-cards',
        page: 'qr-print-cards',
        viewport: '1440,900',
        title: 'the cards (and the tents): the number of tables and the first table\'s number shown, with what is on the print',
        run: async function (t) {
            const tables = document.getElementById('posterTables');
            t.step('shown', [shown(tables), shown(document.getElementById('posterTablesFrom'))], [true, true]);
            t.step('in the group of what is on the print', tables.closest('.editor-group').contains(document.querySelector('[data-poster-toggle="address"]')), true);
        }
    });

    S2P.scenario({
        name: 'poster-presets-both',
        page: 'qr-print-tent',
        title: '"Wzór na okazję" on a Polish panel (both languages): the text has Polish and English, both within 40',
        run: async function (t) {
            const select = document.getElementById('posterPreset');
            pick(select, 'CLUB');
            const value = document.getElementById('posterHeading').value;
            t.check('Polish / English: ' + value, value.indexOf(' / ') > 0 && value.length <= 40);
            t.check('every face shows it', Array.from(document.querySelectorAll('.face [data-poster-heading]'))
                .every(function (el) { return el.textContent === value && !el.hidden; }));
        }
    });
})();
