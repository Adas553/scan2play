// THE TABS Panel DJ-a / Historia / Ustawienia in the bar that stays in view (js/dashboard/tabs.js; fragment dj-nav; PROJECT_CONTEXT.md
// Section 6.4). On the panel the first two swap the queue and the history without leaving the page — the History tab loads its
// content by AJAX and shows it in place of the queue — and a click brings the list into view, just under the bar, unless it is in the
// upper part of the screen already. The lit tab is the list that shows, wherever the page is scrolled. Ustawienia is a page of its own.
//
// The page of the harness has the real Bootstrap (from its webjar), the real app.css and the real layout engine, so positions and
// scrolling are real. The page is made taller first: with a short page the browser could not scroll as far as a tab asks.
(function () {
    const link = function (name) { return document.querySelector('[data-dj-tab="' + name + '"]'); };
    const lit = function () { return ['panel', 'history', 'settings'].filter(function (n) { return link(n).classList.contains('active'); }); };
    const bar = function () { return document.getElementById('djTabBar'); };
    const top = function (id) { return document.getElementById(id).getBoundingClientRect().top; };
    const shown = function (id) { return getComputedStyle(document.getElementById(id)).display !== 'none'; };   // what the DJ sees
    const seen = function (id) { return document.getElementById(id).checkVisibility(); };   // drawn, its parents too
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
    async function scrollTo(t, y) {
        window.scrollTo({ top: y, behavior: 'instant' });
        await t.sleep(150);
    }

    S2P.scenario({
        name: 'tabs',
        title: 'the tabs: Panel and History swap the lists in place and bring them under the bar; the lit tab is the list that shows; Settings is a page',
        setup: {},
        run: async function (t) {
            document.body.style.paddingBottom = '2500px';
            // Folded, the settings leave the queue right under the heading (the design review, 2026-10-09) — in the upper part of
            // the screen already, where a tab need not scroll. Unfolded they push it down, as for a DJ who left them open.
            document.getElementById('settingsToggle').click();
            const fetches = function () { return t.stand.count('GET /dj/history-view/fragment'); };

            // The script scrolls smoothly unless the browser asks for reduced motion (then at once). This scenario is about the smooth
            // path — the one the DJ gets — so a browser that asks for reduced motion would test the other one without saying so.
            t.step('the browser does not ask for reduced motion: a click scrolls smoothly', window.matchMedia('(prefers-reduced-motion: reduce)').matches, false);
            t.step('at the start the Panel tab is lit and the page is at its top', [lit(), window.scrollY], [['panel'], 0]);
            t.step('three tabs: Panel, History and the page "Ustawienia imprezy" of the same party',
                [bar().querySelectorAll('[data-dj-tab]').length, link('settings').getAttribute('href')], [3, '/dj/settings?party=HARN1']);

            // ---- History: loaded in place of the queue ----
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

            // ---- the lit tab is the list that shows, wherever the page is ----
            await scrollTo(t, 0);
            t.step('scrolled to the top (the settings): History is still lit — the history shows', lit(), ['history']);

            // ---- Panel: the queue again, brought into view ----
            link('panel').click();
            t.step('the Panel tab lights at once', lit(), ['panel']);
            await settle(t);
            t.step('the queue shows again and the history is hidden', [shown('queue-content'), shown('history-content')], [true, false]);
            t.check('the top of the queue was brought into the upper part of the screen, just under the bar', inUpperPart('queue-content') && window.scrollY > 0);
            await scrollTo(t, 0);
            t.step('scrolled to the top: Panel stays lit', lit(), ['panel']);
            await scrollTo(t, window.scrollY + top('queue-content') + 400);
            t.step('scrolled deep into the queue: Panel stays lit', lit(), ['panel']);
        }
    });
    // The owner (2026-10-29): from the panel the History tab showed "Impreza i kod QR" above the history; from "Ustawienia" (the
    // standalone page) it did not. The history in place is the page "Historia": the panel's own button and its settings wait.
    S2P.scenario({
        name: 'history-tab-like-its-page',
        title: 'the History tab in place, as the page "Historia": no "Impreza i kod QR" and no settings above it — the Panel tab brings them back as they were',
        setup: {},
        run: async function (t) {
            const toggle = document.getElementById('settingsToggle');
            t.step('the panel: "Impreza i kod QR" shows', seen('settingsToggle'), true);
            toggle.click();   // the DJ left the settings unfolded
            link('history').click();
            await t.waitFor(function () { return document.querySelector('#history-content [data-list]'); }, 'the history to arrive', 5000);
            t.step('the history: no "Impreza i kod QR", no settings card', [seen('settingsToggle'), seen('partyCard'), seen('qrCard')],
                [false, false, false]);
            link('panel').click();
            await t.sleep(200);
            t.step('the Panel tab: the button and the unfolded settings back', [seen('settingsToggle'), seen('partyCard')], [true, true]);
        }
    });

    S2P.scenario({
        name: 'history-tab-like-its-page-wide',
        title: 'a wide screen, the settings unfolded beside the queue: the History tab gives the history the whole width, the Panel tab puts the settings back beside the queue',
        viewport: '1500,900',
        setup: {},
        run: async function (t) {
            document.getElementById('settingsToggle').click();
            const body = document.querySelector('.s2p-panel-body');
            const width = function (id) { return Math.round(document.getElementById(id).getBoundingClientRect().width); };
            t.check('the settings beside the queue', seen('partyCard') && width('queue-content') < body.getBoundingClientRect().width - 200);
            link('history').click();
            await t.waitFor(function () { return document.querySelector('#history-content [data-list]'); }, 'the history to arrive', 5000);
            t.step('the history the whole width, no settings beside it', [Math.abs(width('history-content') - Math.round(body.getBoundingClientRect().width)) <= 1,
                seen('partyCard')], [true, false]);
            link('panel').click();
            await t.sleep(200);
            t.check('the settings beside the queue again', seen('partyCard') && width('queue-content') < body.getBoundingClientRect().width - 200);
        }
    });
})();
