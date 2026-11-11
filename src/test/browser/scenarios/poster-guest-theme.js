// The poster editor's line under "Motyw": the guests' page has a theme of its own, set in "Ustawienia imprezy". Its "Zmień" was the
// browser's own blue underlined link, small on the dark editor; it is the editor's outlined button now (the toolbar's look), a
// thumb's target, on the hint's line.
S2P.scenario({
    name: 'poster-guest-theme-button',
    page: 'qr-print-poster',
    viewport: '1280,900',   // the computer's panel beside the paper (on a phone the editor waits behind "Edytuj")
    title: 'the poster editor\'s "Change" of the guests\' theme: an outlined button like the toolbar\'s, 44 px, on the hint\'s line, to the settings\' card',
    run: async function (t) {
        const hint = document.getElementById('posterGuestTheme');
        const text = hint.querySelector('span');
        const link = hint.querySelector('a');
        const style = getComputedStyle(link);
        const back = getComputedStyle(document.querySelector('.toolbar-back'));
        t.step('the words', [text.textContent.trim(), link.textContent.trim()], ['Guest page: Classic', 'Change']);
        t.step('to the settings\' card', link.getAttribute('href').endsWith('#guestThemeCard'), true);
        t.step('outlined like the toolbar, not underlined, not the browser\'s blue',
            [style.borderTopStyle, style.borderTopColor, style.borderTopLeftRadius, style.textDecorationLine, style.color === 'rgb(0, 0, 238)'],
            ['solid', 'rgb(108, 117, 125)', back.borderTopLeftRadius, 'none', false]);
        t.check('a thumb\'s target (' + Math.round(link.getBoundingClientRect().height) + ' px)', link.getBoundingClientRect().height >= 44 - 0.5);
        const mid = function (el) { const r = el.getBoundingClientRect(); return r.top + r.height / 2; };
        t.check('on the hint\'s line', Math.abs(mid(link) - mid(text)) < 4);
        t.check('nothing wider than the screen', document.documentElement.scrollWidth <= document.documentElement.clientWidth);
    }
});
