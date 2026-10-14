// THE PLANS (V35, the owner 2026-10-14, variant B of https://claude.ai/artifact/32eMmdwz9pTGLs8pRQzLBv): an organiser without Pro, the
// plans enforced, sees each Pro feature where it was, with a quiet "PRO" by its name and — on the page "Ustawienia imprezy" — one
// sentence and "Zobacz, co daje Pro" in place of its form; never in the queue. The pages are rendered by DashboardPageRenderTest /
// SettingsPageRenderTest with PlanService enforced (dashboard-free.html, settings-free.html). While the plans are not enforced
// (production today) nothing says "Pro" — the render tests check that.
(function () {
    const shows = function (el) { return !!el && el.getClientRects().length > 0 && getComputedStyle(el).visibility !== 'hidden'; };
    const text = function (el) { return el.textContent.replace(/\s+/g, ' ').trim(); };
    const middle = function (el) { const box = el.getBoundingClientRect(); return box.top + box.height / 2; };
    /** The visible things to tap inside {@code root} that are lower than 44 px (as design.js), named for the report. */
    const tooSmall = function (root) {
        return Array.from(root.querySelectorAll('a.btn, button, input:not([type="hidden"]):not([type="checkbox"]), select, textarea, summary, '
            + '.s2p-row, .s2p-chips a'))
            .filter(shows)
            .filter(function (el) { return el.getBoundingClientRect().height < 44 - 0.5; })
            .map(function (el) { return (el.id || text(el).slice(0, 20) || el.tagName) + ' ' + Math.round(el.getBoundingClientRect().height) + 'px'; });
    };
    /** "PRO" as the page draws it: a pill with a border (app.css), on the line of the name it marks. */
    const markOf = function (named) {
        const mark = named.querySelector('.s2p-pro');
        if (!shows(mark)) return 'no mark';
        const style = getComputedStyle(mark);
        const name = named.firstElementChild;
        return [text(mark), style.borderTopStyle, parseFloat(style.borderTopLeftRadius) >= 8, Math.abs(middle(mark) - middle(name)) < 4];
    };

    S2P.scenario({
        name: 'pro-free-panel',
        title: 'an organiser without Pro, on a phone: the rows of the Pro features say "PRO" and "W planie Pro" (the limits that hold), the other comment styles are not to pick, the queue has no "PRO"',
        page: 'dashboard-free',
        viewport: '390,844',
        run: async function (t) {
            document.getElementById('settingsToggle').click();
            const row = function (name) { return document.querySelector('#settingsMore [data-settings-row="' + name + '"]'); };
            t.step('the rows stay, each where it was', Array.from(document.querySelectorAll('#settingsMore [data-settings-row]'))
                .filter(shows).map(function (r) { return r.dataset.settingsRow; }), ['hosts', 'links', 'limits', 'staff', 'device']);
            t.step('"PRO" by the name of each Pro feature: a pill on the name\'s line',
                ['hosts', 'links', 'limits'].map(function (name) { return markOf(row(name).querySelector('.s2p-row-name')); }),
                [['PRO', 'solid', true, true], ['PRO', 'solid', true, true], ['PRO', 'solid', true, true]]);
            t.step('what each says', ['hosts', 'links', 'limits'].map(function (name) {
                const state = row(name).querySelector('.s2p-row-state');
                const parts = state.children.length ? Array.from(state.children).filter(shows) : [state];
                return parts.map(text).join(' ');
            }), ['W planie Pro', 'W planie Pro', '2 na gościa · 3 min przerwy']);
            t.step('no "PRO" where nothing is Pro', [markOf(row('staff').querySelector('.s2p-row-name'))], ['no mark']);
            t.step('each row one line, a thumb\'s target, inside the screen', ['hosts', 'links', 'limits'].filter(function (name) {
                const box = row(name).getBoundingClientRect();
                return box.height < 44 - 0.5 || box.height > 60 || box.right > window.innerWidth;
            }), []);
            const options = Array.from(document.querySelectorAll('#commentStyleSelect option'));
            t.step('the comment styles: "Klasyczne" to pick, the others marked and not', options.map(function (o) { return [o.textContent.trim(), o.disabled]; }),
                [['Klasyczne', false], ['Zabawne · Pro', true], ['Sarkastyczne · Pro', true], ['Krótkie · Pro', true]]);
            t.step('nothing of Pro in the queue', document.querySelectorAll('#queueList .s2p-pro, #queueList [data-pro-offer]').length, 0);
            t.check('nothing wider than the screen', document.documentElement.scrollWidth <= window.innerWidth);
        }
    });

    S2P.scenario({
        name: 'pro-free-settings-phone',
        title: 'an organiser without Pro, the page "Ustawienia imprezy" on a phone: each Pro card says what waits or holds and leads to /pro, no form of it; 44 px targets',
        page: 'settings-free',
        viewport: '390,844',
        run: async function (t) {
            const cards = ['hostListsCard', 'linksCard', 'limitsCard'].map(function (id) { return document.getElementById(id); });
            t.step('"PRO" by each card\'s title', cards.map(function (card) { return markOf(card.querySelector('.s2p-card-title')); }),
                [['PRO', 'solid', true, true], ['PRO', 'solid', true, true], ['PRO', 'solid', true, true]]);
            t.step('in place of the form: what waits, or what holds, and the way to what Pro adds', cards.map(function (card) {
                const offer = card.querySelector('[data-pro-offer]');
                return shows(offer) && [getComputedStyle(offer).borderTopStyle, offer.querySelector('a').getAttribute('href'),
                    text(offer.querySelector('a'))];
            }), [['dashed', '/pro', 'Zobacz, co daje Pro'], ['dashed', '/pro', 'Zobacz, co daje Pro'], ['dashed', '/pro', 'Zobacz, co daje Pro']]);
            t.step('no form of a Pro feature on the page', document.querySelectorAll('form[action="/dj/dashboard/host-lists"], '
                + 'form[action="/dj/dashboard/host-link"], form[action="/dj/dashboard/dj-links"], form[action="/dj/dashboard/tip-link"], '
                + 'form[action="/dj/dashboard/limits"]').length, 0);
            t.step('the lists wait — said, not lost', text(cards[0].querySelector('[data-pro-offer] p')).indexOf('Twoje listy są zachowane') === 0, true);
            t.step('nothing to tap is lower than 44 px', tooSmall(document.body), []);
            t.check('nothing wider than the screen', document.documentElement.scrollWidth <= window.innerWidth);
            t.step('every jump whole on the screen', Array.from(document.querySelectorAll('#settingsJump a')).filter(function (a) {
                const box = a.getBoundingClientRect();
                return box.left < 0 || box.right > window.innerWidth;
            }).map(text), []);
        }
    });

    S2P.scenario({
        name: 'pro-free-settings-wide',
        title: 'an organiser without Pro, the page "Ustawienia imprezy" on a computer: the offer inside its card, the title with "PRO" on one line',
        page: 'settings-free',
        viewport: '1280,900',
        run: async function (t) {
            const card = document.getElementById('hostListsCard');
            const offer = card.querySelector('[data-pro-offer]');
            const inside = function (inner, outer) {
                const a = inner.getBoundingClientRect(), b = outer.getBoundingClientRect();
                return a.left >= b.left && a.right <= b.right && a.top >= b.top && a.bottom <= b.bottom;
            };
            t.step('the offer inside its card, its button inside the offer', [inside(offer, card), inside(offer.querySelector('a'), offer)], [true, true]);
            const title = card.querySelector('.s2p-card-title');
            t.check('the title and its "PRO" on one line', title.getBoundingClientRect().height < 40);
        }
    });
})();
