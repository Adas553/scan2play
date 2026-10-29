// "Rozmiar nagłówka" (V54, qr-print.css --heading-scale): the heading smaller, as the theme has it or larger — on the poster, the cards,
// the tent and the pictures. The fitting still makes a long one smaller to its lines (qr-print.js), so the larger with the longest words
// in every theme and font still fits A4, a card and a tent; the poster's code takes what is left.
(function () {
    const MM = 96 / 25.4;
    const PAGE = 272 * MM;

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

    function size(value) {
        const radio = document.querySelector('#posterForm input[name="size"][value="' + value + '"]');
        radio.checked = true;
        radio.dispatchEvent(new Event('change', { bubbles: true }));
    }

    function themes() { return Array.from(document.querySelectorAll('#posterTheme option')).map(function (o) { return o.value; }); }

    function px(el) { return parseFloat(getComputedStyle(el).fontSize); }

    function inside(part, box) {
        const a = part.getBoundingClientRect(), b = box.getBoundingClientRect();
        return a.top >= b.top - 1 && a.bottom <= b.bottom + 1 && a.left >= b.left - 1 && a.right <= b.right + 1;
    }

    S2P.scenario({
        name: 'poster-size-choice',
        page: 'qr-print-poster',
        viewport: '1440,900',
        title: '"Rozmiar nagłówka": smaller, as it is (the default) or larger — under "Więcej wyglądu", on the heading at once (ours too), sent by "Zapisz"',
        run: async function (t) {
            const radios = Array.from(document.querySelectorAll('#posterForm input[name="size"]'));
            t.step('three sizes, as it is picked, in the fold', [radios.map(function (r) { return r.value; }),
                radios.filter(function (r) { return r.checked; }).map(function (r) { return r.value; }),
                radios.every(function (r) { return document.getElementById('posterMoreLook').contains(r); })],
                [['SMALL', 'NORMAL', 'LARGE'], ['NORMAL'], true]);
            const ours = document.querySelector('.poster h1 .ours > span');
            const normal = px(ours);
            size('LARGE');
            t.step('larger: the page\'s class, the heading 1.2 times', [document.body.classList.contains('size-large'), Math.round(px(ours) / normal * 100)],
                [true, 120]);
            size('SMALL');
            t.step('smaller: 0.85 times', [document.body.classList.contains('size-small'), document.body.classList.contains('size-large'),
                Math.round(px(ours) / normal * 100)], [true, false, 85]);
            size('LARGE');
            const form = document.getElementById('posterForm');
            document.getElementById('posterSave').click();
            await t.waitFor(function () { return document.getElementById('posterAnswer').textContent === form.dataset.saved; }, 'saved', 3000);
            t.step('sent', (await t.stand.requests('POST /dj/qr-print/settings')).pop().q.size, 'LARGE');
        }
    });

    S2P.scenario({
        name: 'poster-size-large-fits-a4',
        page: 'qr-print-poster-logo',
        viewport: '1440,900',
        title: 'the larger heading with the longest words, the DJ\'s logo, three profiles and the tip, in every theme and font: one A4 page, the heading in two lines, the code at least 100 mm',
        run: async function (t) {
            const logo = document.querySelector('.poster .dj-logo');
            await t.waitFor(function () { return logo.complete; }, 'the logo', 3000);
            type('posterHeading', 'W'.repeat(40));
            type('posterLine', 'W'.repeat(60));
            size('LARGE');
            const poster = document.querySelector('main.poster'), qr = poster.querySelector('.qr'), own = poster.querySelector('h1 .own');
            const bad = [];
            for (const font of ['THEME', 'SANS', 'SERIF', 'SCRIPT']) {
                pick('posterFont', font);
                await document.fonts.ready;
                for (const theme of themes()) {
                    pick('posterTheme', theme);
                    const line = parseFloat(getComputedStyle(own).lineHeight);
                    if (poster.offsetHeight > PAGE + 1) bad.push(theme + ' ' + font + ': ' + (poster.offsetHeight / MM).toFixed(1) + ' mm');
                    if (Math.round(own.clientHeight / line) > 2 || own.scrollHeight > own.clientHeight + line / 2) bad.push(theme + ' ' + font + ': the heading not in two lines');
                    if (qr.offsetWidth < 100 * MM - 1) bad.push(theme + ' ' + font + ': the code ' + (qr.offsetWidth / MM).toFixed(1) + ' mm');
                    if (!Array.from(poster.children).filter(function (el) { return el.checkVisibility(); }).every(function (el) { return inside(el, poster); })) {
                        bad.push(theme + ' ' + font + ': a part outside');
                    }
                }
            }
            t.step('one page, everything inside, in every theme and font', bad, []);
        }
    });

    S2P.scenario({
        name: 'poster-size-large-fits-cards',
        page: 'qr-print-cards-logo',
        viewport: '1440,900',
        title: 'the larger heading with the longest words on a card, in every theme and font: every part inside, the heading whole in three lines',
        run: async function (t) {
            await t.waitFor(function () { return Array.from(document.querySelectorAll('.card .dj-logo')).every(function (i) { return i.complete; }); }, 'the logos', 3000);
            type('posterHeading', 'W'.repeat(40));
            type('posterLine', 'W'.repeat(60));
            size('LARGE');
            const card = document.querySelector('.card'), own = card.querySelector('.title .own');
            const bad = [];
            for (const font of ['THEME', 'SCRIPT']) {
                pick('posterFont', font);
                await document.fonts.ready;
                for (const theme of themes()) {
                    pick('posterTheme', theme);
                    const line = parseFloat(getComputedStyle(own).lineHeight);
                    if (own.scrollHeight > own.clientHeight + line / 2) bad.push(theme + ' ' + font + ': the heading cut');
                    Array.from(card.querySelectorAll('.text > p, .side > *')).filter(function (el) { return el.checkVisibility(); }).forEach(function (el) {
                        if (!inside(el, card)) bad.push(theme + ' ' + font + ': ' + el.className + ' outside');
                    });
                }
            }
            t.step('inside, whole', bad, []);
        }
    });

    S2P.scenario({
        name: 'poster-size-large-fits-tent',
        page: 'qr-print-tent-logo',
        viewport: '1440,900',
        title: 'the larger heading with the longest words on a tent\'s face, in every theme and font: every part inside its face',
        run: async function (t) {
            await t.waitFor(function () { return Array.from(document.querySelectorAll('.face .dj-logo')).every(function (i) { return i.complete; }); }, 'the logos', 3000);
            type('posterHeading', 'W'.repeat(40));
            type('posterLine', 'W'.repeat(60));
            size('LARGE');
            const face = document.querySelector('.face-lower');
            const bad = [];
            for (const font of ['THEME', 'SCRIPT']) {
                pick('posterFont', font);
                await document.fonts.ready;
                for (const theme of themes()) {
                    pick('posterTheme', theme);
                    Array.from(face.querySelectorAll('.text > p, .side > *')).filter(function (el) { return el.checkVisibility(); }).forEach(function (el) {
                        if (!inside(el, face)) bad.push(theme + ' ' + font + ': ' + el.className + ' outside');
                    });
                }
            }
            t.step('inside', bad, []);
        }
    });

    S2P.scenario({
        name: 'poster-size-story',
        page: 'qr-print-poster',
        viewport: '1440,900',
        title: 'the pictures draw the heading in its size: larger than as it is, smaller smaller',
        run: async function (t) {
            type('posterHeading', 'Wesele');
            const drawn = {};
            for (const value of ['NORMAL', 'LARGE', 'SMALL']) {
                size(value);
                const create = URL.createObjectURL, click = HTMLAnchorElement.prototype.click;
                let saved = false;
                URL.createObjectURL = function (blob) { return create.call(URL, blob); };
                HTMLAnchorElement.prototype.click = function () { saved = true; };
                try {
                    document.getElementById('posterStory').click();
                    await t.waitFor(function () { return saved; }, 'the story', 5000);
                } finally {
                    URL.createObjectURL = create;
                    HTMLAnchorElement.prototype.click = click;
                }
                drawn[value] = JSON.parse(document.getElementById('posterSocial').dataset.lastLayout).headingPx;
            }
            t.step('larger, then smaller', [drawn.LARGE > drawn.NORMAL, drawn.SMALL < drawn.NORMAL], [true, true]);
        }
    });
})();
