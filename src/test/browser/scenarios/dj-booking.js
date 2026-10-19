// The DJ's contact after the party (an idea from Rekwest, 2026-10-16): the page "party ended" and the result of a request that reached
// the DJ show the DJ's profiles under "Zarezerwuj tego DJ-a na swoją imprezę" (GuestPageRenderTest renders both). The pages have no
// script of their own: these check that the buttons and their icons show under the real policy and fit a phone's screen.

/** The booking box: its three buttons, each with its icon drawn, and nothing wider than the screen. */
function bookingShows(t) {
    const box = document.getElementById('djBooking');
    t.check('the box "Zarezerwuj tego DJ-a" is shown', !!box && box.getBoundingClientRect().height > 0);
    if (!box) {
        return;
    }
    const icons = Array.from(box.querySelectorAll('a svg'));
    t.step('the DJ\'s profiles: three buttons, each with its icon drawn', [box.querySelectorAll('a').length,
        icons.filter(function (svg) { const r = svg.getBoundingClientRect(); return r.width >= 14 && r.height >= 14; }).length], [3, 3]);
    t.check('no scrolling sideways', document.documentElement.scrollWidth <= document.documentElement.clientWidth);
    // every line of the box readable on the box's own background (2026-10-17: the heading was #212529 on #1e1e1e, the owner: "nic nie widać")
    const unreadable = Array.from(box.querySelectorAll(':scope > :not(#djLinks)')).filter(function (p) { return contrast(p) < 4.5; })
        .map(function (p) { return p.textContent.trim() + ' ' + contrast(p).toFixed(1) + ' : 1'; });
    t.step('every line of the box readable (contrast at least 4.5 : 1)', unreadable, []);
}

/** The contrast of an element's text on the first background under it that is not transparent (WCAG). */
function contrast(element) {
    function rgb(css) { return css.match(/[\d.]+/g).map(Number); }
    function luminance(c) {
        const v = c.slice(0, 3).map(function (x) { x /= 255; return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); });
        return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2];
    }
    let under = element;
    let background = rgb(getComputedStyle(under).backgroundColor);
    while ((background.length === 4 && background[3] === 0) && under.parentElement) {
        under = under.parentElement;
        background = rgb(getComputedStyle(under).backgroundColor);
    }
    const a = luminance(rgb(getComputedStyle(element).color));
    const b = luminance(background);
    return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
}

S2P.scenario({
    name: 'dj-booking-party-ended',
    title: 'the page "party ended": the DJ\'s profiles under "Zarezerwuj tego DJ-a", under the real policy',
    page: 'party-ended',
    viewport: '390,844',   // a phone's screen
    run: async function (t) {
        bookingShows(t);
    }
});

S2P.scenario({
    name: 'dj-booking-result',
    title: 'the result of an accepted request: the DJ\'s profiles under "Zarezerwuj tego DJ-a", under the real policy',
    page: 'result',
    viewport: '390,844',   // a phone's screen
    run: async function (t) {
        bookingShows(t);
    }
});
