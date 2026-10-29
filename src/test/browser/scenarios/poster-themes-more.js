// The four themes of V52 (qr-print.css): "Rustykalny" (kraft, olive, a stitched — dashed — frame), "Gala" (ivory, gold, a double frame
// with diamonds), "Neon" (white, a frame in a magenta → violet → cyan gradient — the club's colours without its ink), "Konfetti" (white,
// coloured dots in the frame's band only). Each its paper and frame; the rustic and the gala headings in the serif (the theme's font);
// the decoration never under the code or the words. The other scenarios of the themes (readable colours, A4, the cards, the tent, the
// fonts, the pictures) take every theme of the editor, these four with them.
(function () {
    function pick(id, value) {
        const select = document.getElementById(id);
        select.value = value;
        select.dispatchEvent(new Event('change', { bubbles: true }));
    }

    function family(el) { return getComputedStyle(el).fontFamily.split(',')[0].replace(/"/g, '').trim(); }

    S2P.scenario({
        name: 'poster-themes-more',
        page: 'qr-print-poster',
        title: 'the themes of V52: rustic, gala, neon, confetti — each its paper, its frame and the theme\'s heading font; the dots of the confetti only in the frame\'s band, never under the code',
        run: async function (t) {
            t.step('eight themes, the four new at the end', Array.from(document.querySelectorAll('#posterTheme option')).map(function (o) { return o.value; }),
                ['CLASSIC', 'ELEGANT', 'CLUB', 'PASTEL', 'RUSTIC', 'GALA', 'NEON', 'CONFETTI']);
            const poster = document.querySelector('.poster');
            const heading = document.querySelector('.poster h1 .ours > span');
            const seen = ['RUSTIC', 'GALA', 'NEON', 'CONFETTI'].map(function (theme) {
                pick('posterTheme', theme);
                const style = getComputedStyle(poster);
                return [document.body.classList.contains('theme-' + theme.toLowerCase()), style.backgroundColor, style.borderTopStyle,
                    style.borderImageSource.indexOf('linear-gradient') >= 0, style.backgroundImage.indexOf('radial-gradient') >= 0, family(heading)];
            });
            t.step('rustic, gala, neon, confetti: [the class, the paper, the frame, a gradient frame, dots, the heading\'s font]', seen, [
                [true, 'rgb(244, 236, 220)', 'dashed', false, false, 'S2P Serif'],
                [true, 'rgb(255, 253, 246)', 'double', false, false, 'S2P Serif'],
                [true, 'rgb(255, 255, 255)', 'solid', true, false, 'S2P Sans'],
                [true, 'rgb(255, 255, 255)', 'solid', false, true, 'S2P Sans']]);
            pick('posterTheme', 'CONFETTI');
            const box = poster.getBoundingClientRect(), style = getComputedStyle(poster);
            const band = parseFloat(style.borderTopWidth);
            t.check('the dots\' band at least 4 mm (' + Math.round(band / 96 * 25.4 * 10) / 10 + ' mm)', band >= 4 * 96 / 25.4 - 0.5);
            const inside = Array.from(poster.querySelectorAll('h1, .qr, .link, .code')).every(function (el) {
                const r = el.getBoundingClientRect();
                return r.left >= box.left + band - 0.5 && r.right <= box.right - band + 0.5 && r.top >= box.top + band - 0.5 && r.bottom <= box.bottom - band + 0.5;
            });
            t.check('the heading, the code and the address inside the band, on the white', inside);
        }
    });
})();
