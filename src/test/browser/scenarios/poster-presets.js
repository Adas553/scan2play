// The QR print's editor helps the DJ write (qr-print.js): "Nagłówek na okazję" fills the heading with a text for the occasion — a wedding,
// an 18th, a birthday, a club, a pub, a company party — in the print's language, as if typed (the preview, "Niezapisane zmiany"), and
// goes back to its prompt; the DJ edits it further. And the dark club theme says it takes a lot of ink at home.
(function () {
    function pick(select, value) {
        select.value = value;
        select.dispatchEvent(new Event('change', { bubbles: true }));
    }

    S2P.scenario({
        name: 'poster-presets',
        page: 'qr-print-poster',
        title: '"Nagłówek na okazję": an occasion fills the heading in the print\'s language, the preview shows it, the editor says unsaved; the list goes back to its prompt; every text within the heading\'s 40',
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

    S2P.scenario({
        name: 'poster-presets-both',
        page: 'qr-print-tent',
        title: '"Nagłówek na okazję" on a Polish panel (both languages): the text has Polish and English, both within 40',
        run: async function (t) {
            const select = document.getElementById('posterPreset');
            pick(select, 'CLUB');
            const value = document.getElementById('posterHeading').value;
            t.check('Polish / English: ' + value, value.indexOf(' / ') > 0 && value.length <= 40);
            t.check('every face shows it', Array.from(document.querySelectorAll('.face [data-poster-heading]'))
                .every(function (el) { return el.textContent === value && !el.hidden; }));
        }
    });

    S2P.scenario({
        name: 'poster-club-ink',
        page: 'qr-print-poster',
        title: 'the club theme warns of the ink a dark sheet takes at home; the other themes do not',
        run: async function (t) {
            const hint = document.getElementById('posterInkHint');
            const theme = document.getElementById('posterTheme');
            const seen = ['CLASSIC', 'ELEGANT', 'CLUB', 'PASTEL'].map(function (value) {
                pick(theme, value);
                return value + ' ' + !hint.hidden;
            });
            t.step('only with the club theme', seen, ['CLASSIC false', 'ELEGANT false', 'CLUB true', 'PASTEL false']);
        }
    });
})();
