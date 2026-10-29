// "Do internetu" (qr-social.js on /dj/qr-print): a PNG for Instagram — a story 1080 × 1920, a post 1080 × 1350 — drawn in the page
// from what the preview shows (unsaved changes too): the heading, the line, the code on a white tile, the party's code large (a guest
// cannot scan their own screen), the address, the profiles, the tip, the logo; in the theme's paper and colour. Saved as a file named
// after the party (a phone that can share files opens its share sheet instead). With the longest words, the DJ's logo, three profiles
// and the tip, everything stays inside the picture, in every theme.
(function () {
    /** Every theme the editor offers (V47, V52): a new theme is checked here by itself. */
    function themes() { return Array.from(document.querySelectorAll('#posterTheme option')).map(function (o) { return o.value; }); }

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

    /** Clicks a button and takes the file it saves: the blob given to URL.createObjectURL and the name of the link clicked. */
    async function make(t, kind) {
        const caught = {};
        const create = URL.createObjectURL, click = HTMLAnchorElement.prototype.click;
        URL.createObjectURL = function (blob) { caught.blob = blob; return create.call(URL, blob); };
        HTMLAnchorElement.prototype.click = function () { caught.name = this.download; };
        try {
            document.getElementById(kind === 'story' ? 'posterStory' : 'posterPost').click();
            await t.waitFor(function () { return caught.name; }, 'the ' + kind + ' saved', 5000);
        } finally {
            URL.createObjectURL = create;
            HTMLAnchorElement.prototype.click = click;
        }
        const bitmap = await createImageBitmap(caught.blob);
        const canvas = document.createElement('canvas');
        canvas.width = bitmap.width;
        canvas.height = bitmap.height;
        const ctx = canvas.getContext('2d');
        ctx.drawImage(bitmap, 0, 0);
        return { name: caught.name, type: caught.blob.type, width: bitmap.width, height: bitmap.height,
            layout: JSON.parse(document.getElementById('posterSocial').dataset.lastLayout),
            pixel: function (x, y) { return Array.from(ctx.getImageData(x, y, 1, 1).data.slice(0, 3)); },
            dark: function (x, y, w) { let n = 0; const d = ctx.getImageData(x, y, w, 1).data; for (let i = 0; i < d.length; i += 4) if (d[i] < 80) n++; return n; } };
    }

    function rgb(css) { return (css.match(/[\d.]+/g) || []).slice(0, 3).map(Number); }

    function far(a, b, d) { return Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]) > (d || 60); }

    /** Along a row from x0 to x1 at y: how many runs of pixels far from the paper (the frame's lines). */
    function lines(made, x0, x1, y, paper) {
        let runs = 0, inside = false;
        for (let x = x0; x <= x1; x++) {
            const ink = far(made.pixel(x, y), paper, 40);
            if (ink && !inside) runs++;
            inside = ink;
        }
        return runs;
    }

    S2P.scenario({
        name: 'poster-social-story',
        page: 'qr-print-poster',
        title: 'the Instagram story: a 1080 × 1920 PNG named after the party, the theme\'s paper, the code on white with its modules, the DJ\'s unsaved heading, everything inside',
        run: async function (t) {
            type('posterHeading', 'Wesele Ani i Tomka');
            const story = await make(t, 'story');
            t.step('a PNG, 1080 × 1920, named after the party', [story.type, story.width, story.height, story.name],
                ['image/png', 1080, 1920, 'scan2play-PRNT1-story.png']);
            t.step('the paper of the theme in the corner', story.pixel(5, 5), rgb(getComputedStyle(document.querySelector('.poster')).backgroundColor));
            const qr = story.layout.qr;
            t.check('the code at least 500 px (' + qr.size + ')', qr.size >= 500);
            const across = story.dark(qr.x, Math.round(qr.y + qr.size / 2), qr.size);
            t.check('its modules: black and white across its middle (' + across + ' dark of ' + qr.size + ')', across > qr.size * 0.15 && across < qr.size * 0.85);
            t.step('the unsaved heading drawn', story.layout.heading.join(' '), 'Wesele Ani i Tomka');
            t.check('the party\'s code drawn large (' + story.layout.code + ')', story.layout.code === 'PRNT1');
            t.check('inside: ' + JSON.stringify([story.layout.left, story.layout.right, story.layout.top, story.layout.bottom]),
                story.layout.left >= 60 && story.layout.right <= 1020 && story.layout.top >= 60 && story.layout.bottom <= 1860);
        }
    });

    S2P.scenario({
        name: 'poster-social-post',
        page: 'qr-print-cards',
        title: 'the Instagram post: a 1080 × 1350 PNG from the cards\' page too, the code on white, everything inside',
        run: async function (t) {
            const post = await make(t, 'post');
            t.step('a PNG, 1080 × 1350, named after the party', [post.width, post.height, post.name], [1080, 1350, 'scan2play-PRNT1-post.png']);
            const qr = post.layout.qr;
            t.check('the code at least 400 px (' + qr.size + ')', qr.size >= 400);
            t.check('inside', post.layout.left >= 60 && post.layout.right <= 1020 && post.layout.top >= 60 && post.layout.bottom <= 1290);
        }
    });

    S2P.scenario({
        name: 'poster-social-longest',
        page: 'qr-print-poster-logo',
        title: 'the story and the post with the longest words, the DJ\'s logo, three profiles and the tip: everything inside the picture, the code still large, in every theme; the code on white on the dark club paper',
        run: async function (t) {
            type('posterHeading', 'W'.repeat(40));
            type('posterLine', 'W'.repeat(60));
            await t.waitFor(function () { return document.querySelector('.poster .dj-logo').complete; }, 'the logo', 3000);
            const out = [];
            for (const theme of themes()) {
                pickTheme(theme);
                for (const kind of ['story', 'post']) {
                    const made = await make(t, kind);
                    const l = made.layout, h = made.height;
                    if (l.left < 60 || l.right > 1020 || l.top < 60 || l.bottom > h - 60) out.push(theme + ' ' + kind + ' out ' + JSON.stringify([l.left, l.right, l.top, l.bottom]));
                    if (l.qr.size < (kind === 'story' ? 420 : 340)) out.push(theme + ' ' + kind + ' code ' + l.qr.size);
                    if (!l.logo) out.push(theme + ' ' + kind + ' no logo');
                    if (theme === 'CLUB') {
                        const tile = made.pixel(l.qr.x + 3, l.qr.y + 3), paper = made.pixel(5, 5);
                        if (tile.some(function (v) { return v < 240; }) || paper.every(function (v) { return v > 100; })) out.push('club tile ' + tile + ' paper ' + paper);
                    }
                }
            }
            t.step('inside, large, with the logo, in every theme', out, []);
        }
    });

    // A story's top and foot are under Instagram's own bars (the profile above, the reply field below — some 250 px each): the
    // picture's words, logo and code stay between them; the frame, a decoration, may run under them.
    S2P.scenario({
        name: 'poster-social-story-safe',
        page: 'qr-print-poster-logo',
        title: 'the story with the longest words, the DJ\'s logo, three profiles and the tip, in every theme: nothing under Instagram\'s bars (250 px at the top and the foot), the code still at least 420 px',
        run: async function (t) {
            type('posterHeading', 'W'.repeat(40));
            type('posterLine', 'W'.repeat(60));
            await t.waitFor(function () { return document.querySelector('.poster .dj-logo').complete; }, 'the logo', 3000);
            const out = [];
            for (const theme of themes()) {
                pickTheme(theme);
                const l = (await make(t, 'story')).layout;
                if (l.top < 250 || l.bottom > 1920 - 250) out.push(theme + ' ' + l.top + ' … ' + l.bottom);
                if (l.qr.size < 420) out.push(theme + ' code ' + l.qr.size);
            }
            t.step('between the bars, the code large', out, []);
        }
    });

    // The pictures in the print's look (qr-print.css): each theme's frame as on paper — the elegant double, the club and the pastel
    // rounded, the rustic stitched, the gala's navy band with a gold line, the neon in a gradient, the confetti's dots —; the party's
    // code in the print's font, not a typewriter's.
    S2P.scenario({
        name: 'poster-social-frames',
        page: 'qr-print-poster',
        title: '"Do internetu" in the print\'s look: every theme\'s frame as on paper, the party\'s code in the print\'s font',
        run: async function (t) {
            const bad = [];
            for (const theme of themes()) {
                pickTheme(theme);
                const made = await make(t, 'story');
                const paper = made.pixel(5, 5), mid = 960;
                const across = lines(made, 10, 120, mid, paper);
                if (/monospace/.test(made.layout.codeFont || 'monospace')) bad.push(theme + ' code in ' + made.layout.codeFont);
                if (theme === 'CLASSIC' && across !== 0) bad.push('CLASSIC framed: ' + across);
                if (theme === 'ELEGANT' && across !== 2) bad.push('ELEGANT not double: ' + across);
                if ((theme === 'CLUB' || theme === 'PASTEL') && (across !== 1 || far(made.pixel(36, 36), paper, 40))) {
                    bad.push(theme + ' not one rounded line: ' + across + ' corner ' + made.pixel(36, 36));
                }
                if (theme === 'RUSTIC') {
                    let runs = 0, inside = false;
                    for (let y = 300; y < 1600; y++) {
                        const ink = far(made.pixel(34, y), paper, 40);
                        if (ink && !inside) runs++;
                        inside = ink;
                    }
                    if (runs < 20 || across < 1) bad.push('RUSTIC not stitched: ' + runs + ' stitches, ' + across + ' lines');
                }
                if (theme === 'GALA') {
                    const band = made.pixel(42, mid);
                    if (band.some(function (v) { return v > 90; }) || across !== 2) bad.push('GALA no dark band and gold line: ' + band + ', ' + across);
                }
                if (theme === 'NEON' && !far(made.pixel(42, 300), made.pixel(1038, 1620), 120)) {
                    bad.push('NEON no gradient: ' + made.pixel(42, 300) + ' / ' + made.pixel(1038, 1620));
                }
                if (theme === 'CONFETTI') {
                    const colours = new Set();
                    for (let x = 20; x < 1060; x += 2) {
                        for (let y = 15; y < 75; y += 2) {
                            const p = made.pixel(x, y);
                            if (far(p, paper, 120)) colours.add(p.map(function (v) { return Math.round(v / 64); }).join());
                        }
                    }
                    if (colours.size < 3) bad.push('CONFETTI dots of ' + colours.size + ' colours');
                }
            }
            t.step('every theme\'s frame as on paper; the code in the print\'s font', bad, []);
        }
    });
})();
