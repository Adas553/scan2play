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
    t.check('no scrolling sideways', document.documentElement.scrollWidth <= window.innerWidth);
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
