// "Do internetu" on the print page (templates/qr-print.html, #posterSocial — for whom has the editor): a PNG for Instagram — a story
// 1080 × 1920 or a post 1080 × 1350 — drawn here on a canvas from what the preview shows, unsaved changes too, so nothing is sent: the
// logo, the heading, the DJ's line, the code on a white tile, the party's code large and the address (a guest never scans the screen
// they hold), the profiles, the tip; the theme's paper, ink and colour, its frame. Too much for the picture, and every size is made a
// little smaller until it fits. A phone that can share files opens its share sheet (straight to Instagram); anything else saves the
// file, named after the party. The layout of the last picture is kept in #posterSocial's data-last-layout for the browser tests.
(function () {
    'use strict';
    const group = document.getElementById('posterSocial');
    if (!group) return;

    const SIZES = { story: { width: 1080, height: 1920 }, post: { width: 1080, height: 1350 } };
    const MARGIN = 90;
    // our mark as a PNG: a canvas draws an SVG without a size of its own badly in some browsers
    const ourMark = new Image();
    ourMark.src = '/images/icon-192.png';

    function shown(el) { return !!el && el.checkVisibility(); }

    function text(el) { return el ? el.textContent.replace(/\s+/g, ' ').trim() : ''; }

    /** What the first printed item (the poster, a card, a tent's upright face) shows, and the theme's colours and fonts. */
    function content() {
        const root = document.querySelector('main.poster, main.cards .card, main.tents .face-lower');
        const code = document.querySelector('#posterForm input[name="partyCode"]').value;
        const own = root.querySelector('[data-poster-heading]');
        const heading = shown(own) ? [{ text: text(own), second: false, el: own }]
            : Array.prototype.filter.call(root.querySelectorAll('[data-poster-ours-heading] > span'), shown)
                .map(function (el) { return { text: text(el), second: el.classList.contains('second'), el: el }; });
        const address = Array.prototype.filter.call(root.querySelectorAll('[data-poster-part="address"]'), shown);
        const codeEl = address.find(function (el) { const b = el.querySelector('strong'); return b && b.textContent.trim() === code; });
        const linkEl = address.find(function (el) { return el !== codeEl; });
        const djLogo = root.querySelector('.dj-logo');
        const links = root.querySelector('.dj-links');
        const style = getComputedStyle(document.body);
        const paperFont = getComputedStyle(root).fontFamily;   // the print's own (qr-print.css: "S2P Sans")
        return {
            code: code,
            heading: heading,
            line: shown(root.querySelector('[data-poster-line]')) ? text(root.querySelector('[data-poster-line]')) : '',
            codeLabel: codeEl ? text(codeEl).replace(code, '').replace(/:\s*$/, '').trim() : '',
            link: linkEl ? text(linkEl.querySelector('strong') || linkEl) : '',
            links: shown(links) ? Array.prototype.map.call(links.querySelectorAll('span'), text) : [],
            tip: shown(root.querySelector('.tip')) ? text(root.querySelector('.tip')) : '',
            logo: shown(djLogo) && djLogo.complete && djLogo.naturalWidth ? djLogo : null,
            powered: shown(djLogo) ? text(document.querySelector('.powered > span')) : '',
            qr: root.querySelector('.qr'),
            paper: style.getPropertyValue('--paper').trim() || '#ffffff',
            ink: style.getPropertyValue('--ink').trim() || '#111111',
            muted: style.getPropertyValue('--muted').trim() || '#555555',
            accent: heading.length ? getComputedStyle(heading[0].el).color : '#111111',
            framed: !document.body.classList.contains('theme-classic'),
            club: document.body.classList.contains('theme-club'),
            badge: document.body.classList.contains('theme-club') || document.body.classList.contains('paper-own'),
            font: paperFont,
            headingFont: heading.length ? getComputedStyle(heading[0].el).fontFamily : paperFont,
            // the DJ's line in its own font (the serif on the elegant theme), never the heading's script
            lineFont: getComputedStyle(root.querySelector('[data-poster-line]') || root).fontFamily
        };
    }

    /** {@code words} in lines no wider than {@code width}; a word wider than a line is broken where it reaches the edge. */
    function wrap(ctx, words, width) {
        const lines = [];
        let line = '';
        words.split(' ').forEach(function (word) {
            while (ctx.measureText(word).width > width) {
                let cut = word.length - 1;
                while (cut > 1 && ctx.measureText((line ? line + ' ' : '') + word.slice(0, cut)).width > width) cut--;
                if (line && ctx.measureText(line + ' ' + word.slice(0, cut)).width > width) { lines.push(line); line = ''; continue; }
                lines.push((line ? line + ' ' : '') + word.slice(0, cut));
                line = '';
                word = word.slice(cut);
            }
            const next = line ? line + ' ' + word : word;
            if (line && ctx.measureText(next).width > width) {
                lines.push(line);
                line = word;
            } else {
                line = next;
            }
        });
        if (line) lines.push(line);
        return lines;
    }

    /**
     * The picture as a list of things to draw, from the top, at the scale {@code s}: each text block wrapped to the width, the logo
     * and the code in their boxes. Its height tells whether it fits.
     */
    function plan(ctx, c, size, s) {
        const story = size.height > 1500;
        const width = size.width - 2 * MARGIN;
        const items = [];
        const add = function (item) { items.push(item); };
        function block(words, px, weight, family, colour, gap, spacing, role) {
            if (!words) return;
            ctx.font = weight + ' ' + Math.round(px * s) + 'px ' + family;
            ctx.letterSpacing = spacing ? Math.round(spacing * s) + 'px' : '0px';
            wrap(ctx, words, width).forEach(function (line) {
                add({ kind: 'text', text: line, font: ctx.font, spacing: ctx.letterSpacing, colour: colour, height: Math.round(px * s * 1.18),
                    role: role });
            });
            ctx.letterSpacing = '0px';
            add({ kind: 'gap', height: Math.round(gap * s) });
        }
        if (c.logo) {
            const box = { w: (story ? 560 : 440) * s, h: (story ? 220 : 150) * s };
            const ratio = Math.min(box.w / c.logo.naturalWidth, box.h / c.logo.naturalHeight);
            add({ kind: 'logo', image: c.logo, w: Math.round(c.logo.naturalWidth * ratio), h: Math.round(c.logo.naturalHeight * ratio), badge: c.badge });
            add({ kind: 'gap', height: Math.round((story ? 56 : 40) * s) });
        } else {
            add({ kind: 'ours', h: Math.round((story ? 84 : 64) * s), font: '800 ' + Math.round((story ? 64 : 50) * s) + 'px ' + c.font });
            add({ kind: 'gap', height: Math.round((story ? 56 : 36) * s) });
        }
        // the script's small letters are low: larger, as the print's font-size-adjust makes it (a canvas has none)
        const script = /S2P Script/.test(c.headingFont) ? 1.3 : 1;
        c.heading.forEach(function (part, i) {
            const last = i === c.heading.length - 1;
            block(part.text, (part.second ? (story ? 62 : 50) : (story ? 92 : 74)) * script, part.second ? 600 : 700, c.headingFont,
                part.second ? c.muted : c.accent, last ? (story ? 34 : 24) : 10, 0, part.second ? 'second' : 'heading');
        });
        block(c.line, story ? 54 : 44, 600, c.lineFont, c.ink, story ? 48 : 30);
        const qr = Math.round(Math.min(width, (story ? 640 : 500) * s));
        add({ kind: 'qr', size: qr });
        add({ kind: 'gap', height: Math.round((story ? 44 : 30) * s) });
        block(c.codeLabel, story ? 42 : 36, 400, c.font, c.muted, 4);
        if (c.codeLabel) block(c.code, story ? 132 : 104, 800, 'ui-monospace, "DejaVu Sans Mono", Consolas, monospace', c.ink, story ? 18 : 12, 14, 'code');
        block(c.link, story ? 40 : 34, 400, c.font, c.ink, story ? 36 : 24);
        // a profile never broken across lines ("Facebook djkoko"): its own spaces made non-breaking, only the separators wrap
        block(c.links.map(function (l) { return l.replace(/ /g, '\u00a0'); }).join(' · '), story ? 40 : 34, 600, c.font, c.ink, 12);
        block(c.tip, story ? 40 : 34, 400, c.font, c.ink, 0);
        if (c.powered) {
            add({ kind: 'gap', height: Math.round((story ? 48 : 30) * s) });
            add({ kind: 'powered', text: c.powered, font: '400 ' + Math.round((story ? 34 : 28) * s) + 'px ' + c.font,
                height: Math.round((story ? 44 : 36) * s), mark: Math.round((story ? 40 : 32) * s) });
        }
        while (items.length && items[items.length - 1].kind === 'gap') items.pop();
        const height = items.reduce(function (sum, item) {
            return sum + (item.kind === 'logo' ? item.h : item.kind === 'ours' ? item.h : item.kind === 'qr' ? item.size : item.height);
        }, 0);
        return { items: items, height: height };
    }

    function roundRect(ctx, x, y, w, h, r) {
        ctx.beginPath();
        ctx.roundRect(x, y, w, h, r);
    }

    /** Draws the picture of {@code kind} on a new canvas; gives the canvas and its layout. */
    function draw(kind) {
        const size = SIZES[kind];
        const c = content();
        const canvas = document.createElement('canvas');
        canvas.width = size.width;
        canvas.height = size.height;
        const ctx = canvas.getContext('2d');
        let s = 1, laid = plan(ctx, c, size, s);
        while (laid.height > size.height - 2 * MARGIN && s > 0.4) {
            s *= 0.94;
            laid = plan(ctx, c, size, s);
        }
        ctx.fillStyle = c.paper;
        ctx.fillRect(0, 0, size.width, size.height);
        if (c.framed) {
            ctx.strokeStyle = c.accent;
            ctx.lineWidth = 10;
            roundRect(ctx, 34, 34, size.width - 68, size.height - 68, c.club ? 28 : 40);
            ctx.stroke();
        }
        const layout = { kind: kind, width: size.width, height: size.height, left: size.width, right: 0, heading: [], code: '', logo: false };
        let y = Math.round(Math.max(MARGIN, (size.height - laid.height) / 2));
        layout.top = y;
        const centre = size.width / 2;
        const reach = function (w) {
            layout.left = Math.min(layout.left, Math.round(centre - w / 2));
            layout.right = Math.max(layout.right, Math.round(centre + w / 2));
        };
        ctx.textAlign = 'center';
        ctx.textBaseline = 'alphabetic';
        laid.items.forEach(function (item) {
            if (item.kind === 'gap') {
                y += item.height;
            } else if (item.kind === 'logo') {
                if (item.badge) {   // the club's dark paper: the logo on a white badge, as on the print
                    ctx.fillStyle = '#ffffff';
                    roundRect(ctx, centre - item.w / 2 - 18, y - 12, item.w + 36, item.h + 24, 16);
                    ctx.fill();
                }
                ctx.drawImage(item.image, Math.round(centre - item.w / 2), y, item.w, item.h);
                reach(item.w);
                layout.logo = true;
                y += item.h;
            } else if (item.kind === 'ours') {
                ctx.font = item.font;
                const name = 'Scan2Play';
                const w = item.h + item.h * 0.25 + ctx.measureText(name).width;
                const x = centre - w / 2;
                if (ourMark.complete && ourMark.naturalWidth) ctx.drawImage(ourMark, Math.round(x), y, item.h, item.h);
                ctx.textAlign = 'left';
                ctx.fillStyle = c.ink;
                ctx.fillText('Scan', x + item.h * 1.25, y + item.h * 0.74);
                const scan = ctx.measureText('Scan').width;
                ctx.fillStyle = '#0aa2c0';
                ctx.fillText('2', x + item.h * 1.25 + scan, y + item.h * 0.74);
                ctx.fillStyle = c.ink;
                ctx.fillText('Play', x + item.h * 1.25 + scan + ctx.measureText('2').width, y + item.h * 0.74);
                ctx.textAlign = 'center';
                reach(w);
                layout.logo = true;
                y += item.h;
            } else if (item.kind === 'qr') {
                ctx.fillStyle = '#ffffff';
                roundRect(ctx, centre - item.size / 2, y, item.size, item.size, 18);
                ctx.fill();
                ctx.imageSmoothingEnabled = false;
                ctx.drawImage(c.qr, Math.round(centre - item.size / 2), y, item.size, item.size);
                ctx.imageSmoothingEnabled = true;
                layout.qr = { x: Math.round(centre - item.size / 2), y: y, size: item.size };
                reach(item.size);
                y += item.size;
            } else if (item.kind === 'powered') {
                ctx.font = item.font;
                const words = item.text + ' ';
                const w = ctx.measureText(words).width + item.mark * 1.2 + ctx.measureText('Scan2Play').width;
                const x = centre - w / 2;
                ctx.textAlign = 'left';
                ctx.fillStyle = c.muted;
                ctx.fillText(words, x, y + item.height * 0.75);
                if (ourMark.complete && ourMark.naturalWidth) ctx.drawImage(ourMark, Math.round(x + ctx.measureText(words).width), y, item.mark, item.mark);
                ctx.fillStyle = c.ink;
                ctx.fillText('Scan2Play', x + ctx.measureText(words).width + item.mark * 1.2, y + item.height * 0.75);
                ctx.textAlign = 'center';
                reach(w);
                y += item.height;
            } else {
                ctx.font = item.font;
                ctx.letterSpacing = item.spacing;
                ctx.fillStyle = item.colour;
                ctx.fillText(item.text, centre, y + item.height * 0.8);
                reach(ctx.measureText(item.text).width);
                ctx.letterSpacing = '0px';
                if (item.role === 'heading') layout.heading.push(item.text);
                if (item.role === 'code') layout.code = item.text;
                y += item.height;
            }
        });
        layout.bottom = y;
        group.dataset.lastLayout = JSON.stringify(layout);
        return canvas;
    }

    // a canvas draws in a font only once it is loaded — Polish letters are in each font's second half, fetched when first needed
    const FONTS_NEEDED = ['400 40px "S2P Sans"', '700 40px "S2P Sans"', '700 40px "S2P Serif"', '700 40px "S2P Script"'];

    async function fontsIn() {
        if (!document.fonts) return;
        try {
            await Promise.all(FONTS_NEEDED.map(function (font) { return document.fonts.load(font, 'Aa ąćęłńóśźż ĄĆĘŁŃÓŚŹŻ'); }));
        } catch (e) { /* a font that does not load: the canvas draws in the fallback */ }
    }

    async function save(kind) {
        await fontsIn();
        const canvas = draw(kind);
        const name = group.dataset.name + '-' + kind + '.png';
        canvas.toBlob(async function (blob) {
            if (!blob) return;
            const file = typeof File === 'function' ? new File([blob], name, { type: 'image/png' }) : null;
            if (file && navigator.canShare && navigator.canShare({ files: [file] })) {
                try {
                    await navigator.share({ files: [file] });
                    return;
                } catch (e) {
                    if (e && e.name === 'AbortError') return;   // the DJ closed the share sheet
                }
            }
            const url = URL.createObjectURL(blob);
            const link = document.createElement('a');
            link.href = url;
            link.download = name;
            document.body.appendChild(link);
            link.click();
            link.remove();
            setTimeout(function () { URL.revokeObjectURL(url); }, 30000);
        }, 'image/png');
    }

    document.getElementById('posterStory').addEventListener('click', function () { save('story'); });
    document.getElementById('posterPost').addEventListener('click', function () { save('post'); });
})();
