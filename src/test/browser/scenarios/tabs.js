// THE THREE TABS Panel DJ-a / Kolejka / Historia in the bar that stays in view (dashboard.js initTabs; fragment dj-nav; PROJECT_CONTEXT.md
// Section 5.4, "Three tabs in a bar that stays in view"). A click lights the tab at once and brings the part of the page it stands for
// into view — just under the bar, unless it is in the upper part of the screen already; the History tab loads its content by AJAX and
// shows it in place of the queue (the page is not left); and the lit tab follows the part of the page in view when the DJ scrolls.
//
// The page of the harness has the real Bootstrap (from its webjar), the real app.css and the real layout engine, so positions and
// scrolling are real. The page is made taller first: with a short page the browser could not scroll as far as a tab asks.
(function () {
    const link = function (name) { return document.querySelector('[data-dj-tab="' + name + '"]'); };
    const lit = function () { return ['panel', 'queue', 'history'].filter(function (n) { return link(n).classList.contains('active'); }); };
    const bar = function () { return document.getElementById('djTabBar'); };
    const top = function (id) { return document.getElementById(id).getBoundingClientRect().top; };
    const shown = function (id) { return getComputedStyle(document.getElementById(id)).display !== 'none'; };   // what the DJ sees
    // "just under the bar, in the upper part of the screen": what the DJ needs to see the list without scrolling
    const inUpperPart = function (id) {
        const t = top(id);
        return t >= bar().getBoundingClientRect().height - 1 && t < window.innerHeight * 0.4;
    };
    // The smooth scroll of a click takes a moment: wait until the page has stopped moving
    async function settle(t) {
        let last = -1, still = 0;
        for (let i = 0; i < 100 && still < 8; i++) {
            await t.sleep(50);
            still = window.scrollY === last ? still + 1 : 0;
            last = window.scrollY;
        }
    }
    // A scroll that is not a click's: at once, and the lit tab follows (the smooth scroll of a click keeps it locked for ~1 s)
    async function scrollTo(t, y) {
        window.scrollTo({ top: y, behavior: 'instant' });
        await t.sleep(150);
    }

    S2P.scenario({
        name: 'tabs',
        title: 'the tabs: a click lights the tab and brings its part of the page under the bar; History loads in place of the queue; the lit tab follows the scroll',
        setup: {},
        run: async function (t) {
            document.body.style.paddingBottom = '2500px';
            const fetches = function () { return t.stand.count('GET /dj/history-view/fragment'); };

            // The script scrolls smoothly unless the browser asks for reduced motion (then at once). This scenario is about the smooth
            // path — the one the DJ gets — so a browser that asks for reduced motion would test the other one without saying so.
            t.step('the browser does not ask for reduced motion: a click scrolls smoothly', window.matchMedia('(prefers-reduced-motion: reduce)').matches, false);
            t.step('at the start the Panel tab is lit and the page is at its top', [lit(), window.scrollY], [['panel'], 0]);
            t.check('the tab bar has three tabs and its own place in the page', bar().querySelectorAll('[data-dj-tab]').length === 3);

            // ---- Queue ----
            link('queue').click();
            t.step('the Queue tab lights at once, before the page has moved', lit(), ['queue']);
            await settle(t);
            t.check('the top of the queue was brought into the upper part of the screen, just under the bar', inUpperPart('queue-content') && window.scrollY > 0);
            t.step('and the Queue tab is still the lit one (the scroll did not light another tab on its way)', lit(), ['queue']);

            // ---- History: loaded in place of the queue ----
            await t.sleep(1000);
            link('history').click();
            await t.waitFor(function () { return document.querySelector('#history-content [data-list]'); }, 'the history to arrive', 5000);
            t.step('the History tab loaded the history (one request), the history shows and the queue is hidden', [await fetches(), shown('history-content'), shown('queue-content')], [1, true, false]);
            await settle(t);
            t.step('the History tab is the lit one', lit(), ['history']);
            t.check('the top of the history was brought into the upper part of the screen, just under the bar', inUpperPart('history-content'));
            link('history').click();
            await t.sleep(300);
            t.step('a second click on History while it shows loads nothing again', [await fetches(), lit()], [1, ['history']]);
            await settle(t);

            // ---- the lit tab follows the scroll ----
            await t.sleep(1000);
            await scrollTo(t, 0);
            t.step('scrolled to the top: Panel is lit', lit(), ['panel']);
            await scrollTo(t, window.scrollY + top('history-content') - 100);
            t.step('scrolled until the history is near the top of the screen: History is lit (the list that shows)', lit(), ['history']);
            await scrollTo(t, window.scrollY + top('history-content') - window.innerHeight * 0.8);
            t.step('scrolled back until the history is in the lower part of the screen: Panel is lit again', lit(), ['panel']);

            // ---- Panel: the top of the page; the list that shows stays ----
            link('panel').click();
            t.step('the Panel tab lights at once', lit(), ['panel']);
            await settle(t);
            t.step('the page is at its top, and the history still shows (Panel changes only where the page is)', [window.scrollY, shown('history-content'), shown('queue-content')], [0, true, false]);

            // ---- back to the queue: the history goes away ----
            link('queue').click();
            await settle(t);
            t.step('the queue shows again and the history is hidden; the Queue tab is lit', [shown('queue-content'), shown('history-content'), lit()], [true, false, ['queue']]);
            await t.sleep(1000);
            await scrollTo(t, window.scrollY + top('queue-content') - 100);
            t.step('now the queue is the list that shows: scrolled to it, Queue is lit (not History)', lit(), ['queue']);
        }
    });
})();
