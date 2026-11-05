// "Do internetu" of the evening summary (templates/summary.html, the hosts' version on the DJ's page): a picture of the evening —
// a story 1080 × 1920 or a post 1080 × 1350 — drawn on a canvas in the page from what the page shows: the title, the day, the DJ, the
// guests' votes and the songs played, the five most wanted, the DJ's logo and ours. A phone that can share files opens its share sheet,
// anything else saves "scan2play-<day>-story.png". Nothing is sent. The words the picture says (its labels) are the panel's data-*.
(function () {
    'use strict';
    const panel = document.getElementById('summarySocial');
    if (!panel) return;
    panel.hidden = false;

    const FONT = '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif';
    const INK = '#f8f9fa', MUTED = '#a3acb6', ACCENT = '#0dcaf0', PAGE = '#0d1117';

    function text(el) { return el ? el.textContent.replace(/\s+/g, ' ').trim() : ''; }

    /** What the picture says, read from the page as it is shown. */
    function content() {
        const stats = document.querySelectorAll('#stats .stat strong');
        const logo = document.getElementById('djLogo');
        return {
            title: text(document.querySelector('main h1')),
            day: text(document.querySelector('main .when span')),
            dj: text(document.querySelector('main .dj')),
            votes: text(stats[0]), played: text(stats[1]),
            top: Array.prototype.slice.call(document.querySelectorAll('#top .song'), 0, 5).map(text),
            logo: logo && logo.complete && logo.naturalWidth > 0 ? logo : null
        };
    }

    /** The lines of {@code words} at most {@code width} wide in the context's font; a word longer than a line is cut. */
    function wrap(ctx, words, width) {
        const lines = [];
        let line = '';
        words.split(' ').forEach(function (word) {
            const tried = line ? line + ' ' + word : word;
            if (ctx.measureText(tried).width <= width || !line) {
                line = tried;
            } else {
                lines.push(line);
                line = word;
            }
        });
        if (line) lines.push(line);
        return lines.map(function (l) {
            let cut = l;
            while (ctx.measureText(cut).width > width && cut.length > 1) cut = cut.slice(0, -1);
            return cut === l ? l : cut.slice(0, -1) + '…';
        });
    }

    /** At most {@code max} lines, the last one ending "…" when there was more. */
    function atMost(lines, max, ctx, width) {
        if (lines.length <= max) return lines;
        const kept = lines.slice(0, max);
        let last = kept[max - 1] + '…';
        while (ctx.measureText(last).width > width && last.length > 2) last = last.slice(0, -2) + '…';
        kept[max - 1] = last;
        return kept;
    }

    /**
     * The picture's blocks top to bottom at scale {@code s}: what each says, its font, colour and height — measured, so that the
     * whole is shrunk until it fits between the margins (a story's words stay between Instagram's bars, 250 px at the top and foot).
     */
    function plan(ctx, c, size, s) {
        const width = size.w - 2 * size.side;
        const items = [];
        function block(words, px, weight, colour, gap, maxLines) {
            ctx.font = weight + ' ' + Math.round(px * s) + 'px ' + FONT;
            atMost(wrap(ctx, words, width), maxLines, ctx, width).forEach(function (line) {
                items.push({ kind: 'text', text: line, font: ctx.font, colour: colour, height: Math.round(px * s * 1.18) });
            });
            items.push({ kind: 'gap', height: Math.round(gap * s) });
        }
        if (c.day) block(c.day, 40, 500, MUTED, 14, 1);
        block(c.title, size.story ? 96 : 84, 800, INK, 18, 3);
        if (c.dj) block(c.dj, 44, 600, INK, 46, 1);
        items.push({ kind: 'numbers', height: Math.round(200 * s), s: s });
        items.push({ kind: 'gap', height: Math.round(40 * s) });
        if (c.top.length) {
            block(panel.dataset.topLabel, 46, 700, ACCENT, 14, 1);
            c.top.forEach(function (song, i) { block((i + 1) + '. ' + song, 42, 500, INK, 10, 2); });
        }
        const height = items.reduce(function (sum, item) { return sum + item.height; }, 0);
        return { items: items, height: height };
    }

    function roundRect(ctx, x, y, w, h, r) {
        ctx.beginPath();
        ctx.moveTo(x + r, y);
        ctx.arcTo(x + w, y, x + w, y + h, r);
        ctx.arcTo(x + w, y + h, x, y + h, r);
        ctx.arcTo(x, y + h, x, y, r);
        ctx.arcTo(x, y, x + w, y, r);
        ctx.closePath();
    }

    function draw(kind) {
        const story = kind === 'story';
        const size = { w: 1080, h: story ? 1920 : 1350, top: story ? 250 : 90, foot: story ? 250 : 90, side: 90, story: story };
        const canvas = document.createElement('canvas');
        canvas.width = size.w;
        canvas.height = size.h;
        const ctx = canvas.getContext('2d');
        // the page of every Scan2Play picture: dark, a glow of the action's cyan from two corners
        ctx.fillStyle = PAGE;
        ctx.fillRect(0, 0, size.w, size.h);
        [[0, 0, 0.32], [size.w, size.h, 0.22]].forEach(function (glow) {
            const g = ctx.createRadialGradient(glow[0], glow[1], 0, glow[0], glow[1], size.h * 0.7);
            g.addColorStop(0, 'rgba(13, 202, 240, ' + glow[2] + ')');
            g.addColorStop(1, 'rgba(13, 202, 240, 0)');
            ctx.fillStyle = g;
            ctx.fillRect(0, 0, size.w, size.h);
        });

        const c = content();
        const footer = 150;
        const room = size.h - size.top - size.foot - footer;
        let s = 1, laid = plan(ctx, c, size, s);
        while (laid.height > room && s > 0.5) {
            s -= 0.05;
            laid = plan(ctx, c, size, s);
        }
        let y = size.top;
        ctx.textBaseline = 'top';
        laid.items.forEach(function (item) {
            if (item.kind === 'text') {
                ctx.font = item.font;
                ctx.fillStyle = item.colour;
                ctx.fillText(item.text, size.side, y);
            } else if (item.kind === 'numbers') {
                [[c.votes, panel.dataset.votesLabel], [c.played, panel.dataset.playedLabel]].forEach(function (pair, i) {
                    const x = size.side + i * (size.w - 2 * size.side) / 2;
                    ctx.fillStyle = ACCENT;
                    ctx.font = '800 ' + Math.round(130 * item.s) + 'px ' + FONT;
                    ctx.fillText(pair[0], x, y);
                    ctx.fillStyle = MUTED;
                    ctx.font = '500 ' + Math.round(38 * item.s) + 'px ' + FONT;
                    ctx.fillText(pair[1], x, y + Math.round(145 * item.s));
                });
            }
            y += item.height;
        });

        // the foot: the DJ's logo on a white badge, and ours — "Działa na Scan2Play" (in English "Powered by", as the poster's foot), the address
        const footY = size.h - size.foot - footer + 20;
        if (c.logo) {
            const box = 130, ratio = Math.min(box / c.logo.naturalWidth, box / c.logo.naturalHeight);
            const w = c.logo.naturalWidth * ratio, h = c.logo.naturalHeight * ratio;
            ctx.fillStyle = '#ffffff';
            roundRect(ctx, size.side, footY, box + 20, box + 20, 18);
            ctx.fill();
            ctx.drawImage(c.logo, size.side + 10 + (box - w) / 2, footY + 10 + (box - h) / 2, w, h);
        }
        ctx.textAlign = 'right';
        ctx.fillStyle = MUTED;
        ctx.font = '500 34px ' + FONT;
        ctx.fillText(panel.dataset.made, size.w - size.side, footY + 30);
        ctx.font = '800 52px ' + FONT;
        const right = size.w - size.side;
        const play = 'Play', two = '2', scan = 'Scan';
        ctx.fillStyle = INK;
        ctx.fillText(play, right, footY + 76);
        const playW = ctx.measureText(play).width;
        ctx.fillStyle = ACCENT;
        ctx.fillText(two, right - playW, footY + 76);
        ctx.fillStyle = INK;
        ctx.fillText(scan, right - playW - ctx.measureText(two).width, footY + 76);
        ctx.textAlign = 'left';

        panel.dataset.lastLayout = JSON.stringify({ kind: kind, scale: Math.round(s * 100) / 100, top: size.top, bottom: y,
            room: size.h - size.foot - footer, lines: laid.items.filter(function (i) { return i.kind === 'text'; }).map(function (i) { return i.text; }) });
        return canvas;
    }

    function save(kind) {
        const canvas = draw(kind);
        const name = panel.dataset.name + '-' + kind + '.png';
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

    document.getElementById('summaryStory').addEventListener('click', function () { save('story'); });
    document.getElementById('summaryPost').addEventListener('click', function () { save('post'); });
})();
