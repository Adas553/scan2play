// A heading of two or three lines balanced (qr-print.css text-wrap: balance, qr-social.js wrap): a line filled as far as it goes left
// one word alone below it — "Poproś o piosenkę dla Młodej" over "Pary" (the owner's screenshot, the wedding's look). Its lines now as
// long as one another, so no word stands alone; the number of lines stays what it was, so the fitting and the page are as before.
(function () {
    const WEDDING = 'Poproś o piosenkę dla Młodej Pary';
    // the occasions' headings and a few of a DJ's own, in two languages: some end with a short word a greedy line leaves alone
    const HEADINGS = [WEDDING, 'Wybierz hit na osiemnastkę', 'Poproś o piosenkę na urodziny', 'Masz ulubiony kawałek? Poproś!',
        'Wybierz muzykę na naszą imprezę', 'Request a song for the newlyweds', 'Osiemnastka Dawida — zamów swój hit', 'Zeskanuj i zamów piosenkę u DJ-a',
        'Wesele Ani i Tomka — Twoja piosenka', 'Got a favourite song? Ask the DJ now'];

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

    /** The widths of an element's lines, from its text's boxes (one per line). */
    function lines(el) {
        const range = document.createRange();
        range.selectNodeContents(el);
        const rows = {};
        Array.from(range.getClientRects()).forEach(function (r) {
            const top = Math.round(r.top);
            rows[top] = (rows[top] || 0) + r.width;
        });
        return Object.keys(rows).sort(function (a, b) { return a - b; }).map(function (k) { return rows[k]; });
    }

    /** Lines no shorter than half the longest, and no line one word: [the lines' count, all balanced]. */
    function balanced(el) {
        const widths = lines(el);
        const words = el.textContent.trim().split(/\s+/).length;
        return [widths.length, widths.length < 2 || (Math.min.apply(null, widths) >= 0.5 * Math.max.apply(null, widths) && words > widths.length)];
    }

    async function wedding(t) {
        pick('posterTheme', 'ELEGANT');
        pick('posterFont', 'SCRIPT');
        type('posterHeading', WEDDING);
        await document.fonts.ready;
        await t.sleep(100);
    }

    S2P.scenario({
        name: 'poster-heading-balanced',
        page: 'qr-print-poster',
        viewport: '1440,900',
        title: 'the wedding\'s heading in the script on the poster: two lines alike, never "Pary" alone',
        run: async function (t) {
            await wedding(t);
            t.step('two lines, balanced', balanced(document.querySelector('.poster h1 .own')), [2, true]);
        }
    });

    S2P.scenario({
        name: 'poster-heading-balanced-many',
        page: 'qr-print-cards',
        viewport: '1440,900',
        title: 'ten headings of two or three lines on a card, in the sans and the script: every one balanced',
        run: async function (t) {
            const bad = [];
            for (const font of ['THEME', 'SCRIPT']) {
                pick('posterFont', font);
                for (const heading of HEADINGS) {
                    type('posterHeading', heading);
                    await document.fonts.ready;
                    const b = balanced(document.querySelector('.card .title .own'));
                    if (!b[1]) bad.push(font + ': ' + heading + ' ' + JSON.stringify(lines(document.querySelector('.card .title .own')).map(Math.round)));
                }
            }
            t.step('every heading balanced', bad, []);
        }
    });

    // Polish typesetting: a one-letter word — "i", "o", "w", "z", "a", "u" — never ends a line ("Ani i" over "Tomka"): the space after it
    // a non-breaking one, in what is printed and drawn (the field keeps what the DJ typed)
    S2P.scenario({
        name: 'poster-heading-one-letter-words',
        page: 'qr-print-cards',
        viewport: '1440,900',
        title: 'a one-letter word never at a line\'s end: the space after "i", "o", "w", "z" a non-breaking one on the print, the field as typed',
        run: async function (t) {
            type('posterHeading', 'Wesele Ani i Tomka w Dworze o zmroku');
            type('posterLine', 'Zatańczmy z nami u Ani a potem w Lipnicy');
            const heading = document.querySelector('.card .title .own'), line = document.querySelector('.card .own-line');
            const loose = function (el) { return /(^|\s)[aiouwz] /i.test(el.textContent); };
            t.step('printed: no plain space after a one-letter word', [loose(heading), loose(line)], [false, false]);
            t.step('the field as typed', document.getElementById('posterHeading').value, 'Wesele Ani i Tomka w Dworze o zmroku');
            t.step('the heading reads the same', heading.textContent.replace(/\u00a0/g, ' '), 'Wesele Ani i Tomka w Dworze o zmroku');
            t.step('ours too: "Zeskanuj i poproś o piosenkę"', loose(document.querySelector('.card .title .ours')), false);
        }
    });

    S2P.scenario({
        name: 'poster-heading-balanced-cards-tent',
        page: 'qr-print-tent',
        viewport: '1440,900',
        title: 'the same on a tent\'s faces and the DJ\'s longest line: balanced lines, none of one word',
        run: async function (t) {
            await wedding(t);
            type('posterLine', 'Wesele Katarzyny i Bartłomieja w Dworze pod Lipami, 14 czerwca');
            await t.sleep(100);
            const face = document.querySelector('.face-lower');
            t.step('the heading balanced', balanced(face.querySelector('.title .own'))[1], true);
            t.step('the line balanced', balanced(face.querySelector('.own-line'))[1], true);
        }
    });

    S2P.scenario({
        name: 'poster-heading-balanced-story',
        page: 'qr-print-poster',
        viewport: '1440,900',
        title: 'the story draws the heading balanced too: its lines alike, never one word alone',
        run: async function (t) {
            await wedding(t);
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
            const heading = JSON.parse(document.getElementById('posterSocial').dataset.lastLayout).heading;
            const words = heading.map(function (l) { return l.split(' ').length; });
            t.step('every line more than one word', [heading.length > 1, words.every(function (n) { return n > 1; })], [true, true]);
            // and the others: a line's letters at least half the longest's
            const bad = [];
            for (const text of HEADINGS) {
                type('posterHeading', text);
                saved = false;
                URL.createObjectURL = function (blob) { return create.call(URL, blob); };
                HTMLAnchorElement.prototype.click = function () { saved = true; };
                try {
                    document.getElementById('posterStory').click();
                    await t.waitFor(function () { return saved; }, 'the story', 5000);
                } finally {
                    URL.createObjectURL = create;
                    HTMLAnchorElement.prototype.click = click;
                }
                const drawn = JSON.parse(document.getElementById('posterSocial').dataset.lastLayout).heading;
                const sizes = drawn.map(function (l) { return l.length; });
                if (drawn.length > 1 && Math.min.apply(null, sizes) < 0.5 * Math.max.apply(null, sizes)) {
                    bad.push(JSON.stringify(drawn));
                }
            }
            t.step('every heading drawn balanced', bad, []);
        }
    });
})();
