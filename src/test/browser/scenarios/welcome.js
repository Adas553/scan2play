// A FIRST CONTACT WITH A NEW DJ (V48): the panel of a new organiser has a welcome card above the queue — "Pierwszy raz? Napisz do nas" —
// until they close it: "✕", or "Napisz do nas", which opens "Pomoc i kontakt". Closed on the server (POST /dj/dashboard/welcome-dismiss),
// not in the browser's storage: the DJ logs in on a phone and a laptop. Its button an outline: the view's one action stays "Drukuj".
// While it shows, the empty queue's own "Pierwszy raz? Zobacz, jak to działa" and "Panel jako aplikacja" wait — the card says both.
(function () {
    const shows = function (el) { return !!el && el.checkVisibility(); };
    const DISMISS = 'POST /dj/dashboard/welcome-dismiss';
    const card = function () { return document.getElementById('welcomeCard'); };
    /** The visible things to tap inside {@code root} lower than 44 px (as design.js's), named for the report. */
    const tooSmall = function (root) {
        return Array.from(root.querySelectorAll('a.btn, button'))
            .filter(function (el) { return el.checkVisibility(); })
            .filter(function (el) { return el.getBoundingClientRect().height < 44 - 0.5; })
            .map(function (el) { return (el.id || el.textContent.trim().slice(0, 20)) + ' ' + Math.round(el.getBoundingClientRect().height) + 'px'; });
    };
    /** Whether the elements stand on one line: their middles within a few pixels. */
    const oneLine = function (elements) {
        const middles = elements.map(function (el) { const box = el.getBoundingClientRect(); return (box.top + box.bottom) / 2; });
        return middles.every(function (middle) { return Math.abs(middle - middles[0]) < 6; });
    };
    /** Waits until the closes sent reach {@code n} (the stand-in's log is fetched: waitFor takes no promise). */
    const sent = async function (t, n) {
        for (let i = 0; i < 100; i++) {
            if (await t.stand.count(DISMISS) >= n) return true;
            await t.sleep(30);
        }
        return false;
    };
    const lines = function (el) {
        return Math.round(el.getBoundingClientRect().height / parseFloat(getComputedStyle(el).lineHeight));
    };

    S2P.scenario({
        name: 'welcome-card-new-organiser',
        title: 'a new organiser\'s panel on a phone: "Pierwszy raz? Napisz do nas" above the empty queue, an outline button — "Drukuj" the one action —, the empty queue\'s guide link and "Panel jako aplikacja" wait',
        page: 'dashboard-welcome',
        viewport: '390,844',
        setup: { queue: [] },
        run: async function (t) {
            await t.waitFor(function () { return shows(document.getElementById('queueEmpty')); }, 'the empty queue', 8000);
            window.dispatchEvent(new Event('beforeinstallprompt'));   // the browser offers the app: the hint would show
            t.step('the card shows', shows(card()), true);
            t.step('its heading', card() && card().querySelector('h2').textContent.trim(), 'Pierwszy raz? Napisz do nas');
            t.check('above the queue', !!card() && card().getBoundingClientRect().bottom <= document.getElementById('queueList').getBoundingClientRect().top);
            t.step('in the first screen', !!card() && card().getBoundingClientRect().bottom <= document.documentElement.clientHeight, true);
            const actions = Array.from(document.querySelectorAll('.btn-action')).filter(shows).map(function (el) { return el.textContent.trim(); });
            t.step('one action in view, "Drukuj …"', actions.length === 1 && actions[0].startsWith('Drukuj'), true);
            t.step('its guide link leads to the guide', !!card() && card().querySelector('#welcomeGuide').getAttribute('href'), '/jak-to-dziala');
            t.step('while it shows: no second "Pierwszy raz?" link, no "Panel jako aplikacja"',
                [shows(document.getElementById('queueEmptyGuide')), shows(document.getElementById('installHint'))], [false, false]);
            t.step('a thumb\'s target, each', card() ? tooSmall(card()) : ['no card'], []);
            t.step('nothing sent yet', await t.stand.count(DISMISS), 0);
        }
    });

    S2P.scenario({
        name: 'welcome-card-write',
        title: '"Napisz do nas" opens "Pomoc i kontakt" with our address, and closes the card for good: one POST with the party and the CSRF token; the card gone once the window closes, the empty queue\'s guide link back',
        page: 'dashboard-welcome',
        viewport: '390,844',
        setup: { queue: [] },
        run: async function (t) {
            await t.waitFor(function () { return shows(document.getElementById('queueEmpty')); }, 'the empty queue', 8000);
            document.getElementById('welcomeWrite').click();
            const modal = document.getElementById('feedbackModal');
            await t.waitFor(function () { return modal.classList.contains('show'); }, '"Pomoc i kontakt" open', 3000);
            t.step('"Pomoc i kontakt" shows our address', shows(document.getElementById('helpEmail')), true);
            t.step('the close sent', await sent(t, 1), true);
            const close = (await t.stand.requests(DISMISS))[0];
            t.step('for this party, with the CSRF token', close && [close.q.partyCode, close.q['X-CSRF-TOKEN']], ['HARN1', 'harness-csrf-token']);
            t.step('the card still behind the window', shows(card()), true);
            await t.sleep(400);   // the window's fade: Bootstrap ignores a close while it is still opening
            modal.querySelector('[data-bs-dismiss="modal"]').click();
            await t.waitFor(function () { return !modal.classList.contains('show'); }, '"Pomoc i kontakt" closed', 3000);
            await t.sleep(400);
            t.step('the card gone', shows(card()), false);
            t.step('the empty queue\'s guide link back', shows(document.getElementById('queueEmptyGuide')), true);
            t.step('sent once', await t.stand.count(DISMISS), 1);
        }
    });

    S2P.scenario({
        name: 'welcome-card-close',
        title: '"✕" closes the card at once and for good: one POST, the focus on the queue\'s heading, not lost to the page',
        page: 'dashboard-welcome',
        setup: { queue: [] },
        run: async function (t) {
            const close = card() && card().querySelector('button[data-welcome-close].btn-close');
            t.step('"✕" named for a screen reader', close && close.getAttribute('aria-label'), 'Zamknij powitanie');
            t.check('on a computer "Napisz do nas" and the guide on one line', oneLine([document.getElementById('welcomeWrite'), document.getElementById('welcomeGuide')]));
            close.click();
            t.step('gone at once', shows(card()), false);
            t.check('the focus on the queue, not lost', document.activeElement !== document.body && document.getElementById('queueList').contains(document.activeElement));
            t.step('the close sent', await sent(t, 1), true);
            const request = (await t.stand.requests(DISMISS))[0];
            t.step('for this party, with the CSRF token', request && [request.q.partyCode, request.q['X-CSRF-TOKEN']], ['HARN1', 'harness-csrf-token']);
        }
    });

    // One line, as the guest's and the panel's one-line layouts (the fonts of GitHub's runner: DejaVu, Liberation — run it there too):
    // the heading on the narrowest phone, in both languages, "✕" in the corner off it.
    /** Whether the text of {@code a} (its words, not its box) and the box of {@code b} do not overlap. */
    const apart = function (a, b) {
        const range = document.createRange();
        range.selectNodeContents(a);
        const x = range.getBoundingClientRect(), y = b.getBoundingClientRect();
        return x.right <= y.left || y.right <= x.left || x.bottom <= y.top || y.bottom <= x.top;
    };
    const oneLineRun = function (heading) {
        return async function (t) {
            const title = card() && card().querySelector('h2');
            t.step('the heading', title && title.textContent.trim(), heading);
            t.step('the heading one line', title && lines(title), 1);
            t.check('"✕" in the corner, off the heading', !!title && apart(title, card().querySelector('.btn-close')));
            const write = document.getElementById('welcomeWrite'), guide = document.getElementById('welcomeGuide');
            t.check('"Napisz do nas" the card\'s width, the guide under it', !!card()
                && Math.abs(write.getBoundingClientRect().width - write.parentElement.getBoundingClientRect().width) < 1
                && guide.getBoundingClientRect().top >= write.getBoundingClientRect().bottom);
            t.step('nothing wider than the screen', document.documentElement.scrollWidth <= document.documentElement.clientWidth, true);
        };
    };
    S2P.scenario({
        name: 'welcome-card-one-line-pl',
        title: 'a 320 px phone: "Pierwszy raz? Napisz do nas" one line, "✕" in the corner off it, "Napisz do nas" the card\'s width and the guide under it, nothing wider than the screen',
        page: 'dashboard-welcome',
        viewport: '320,700',
        run: oneLineRun('Pierwszy raz? Napisz do nas')
    });
    S2P.scenario({
        name: 'welcome-card-one-line-en',
        title: 'a 320 px phone in English: "First time? Write to us" one line, "✕" off it',
        page: 'dashboard-welcome-en',
        viewport: '320,700',
        run: oneLineRun('First time? Write to us')
    });

    S2P.scenario({
        name: 'welcome-card-not-again',
        title: 'an organiser who closed it (the parties of before too), and the staff: no welcome card',
        run: async function (t) {
            t.step('the organiser\'s panel', !!card(), false);
            const staff = await fetch('/dj/dashboard?page=dashboard-staff').then(function (r) { return r.text(); });
            t.step('the staff\'s panel', staff.includes('id="welcomeCard"'), false);
            const demo = await fetch('/dj/dashboard?page=dashboard-demo').then(function (r) { return r.text(); });
            t.step('the demo', demo.includes('id="welcomeCard"'), false);
            const welcome = await fetch('/dj/dashboard?page=dashboard-welcome').then(function (r) { return r.text(); });
            t.step('(the control: a new organiser\'s page has it)', welcome.includes('id="welcomeCard"'), true);
        }
    });
})();
