// The pages "Dla kogo" (2026-10-21, ForWhomPagesWebTest renders them in both languages): the landing page's look for one kind of
// party — the hero with the same pictures, four steps, four features, three questions, the start again and the other kinds as cards
// that are links. No script of their own: these check the layout under the real policy, on a phone and on a wide screen, and that the
// landing page's cards lead to them. "Fits the screen" is measured against document.documentElement.clientWidth: a phone widens
// window.innerWidth by what sticks out (2026-10-19).
(function () {
    const box = function (el) { return el.getBoundingClientRect(); };
    const width = function () { return document.documentElement.clientWidth; };
    const rows = function (els) { return new Set(els.map(function (el) { return Math.round(box(el).top); })).size; };
    /** The element a tap at the card's lower right corner reaches: the card's link when the whole card is one. */
    const tapped = function (card) {
        card.scrollIntoView({block: 'center', behavior: 'instant'});   // not smooth (Bootstrap's): the box is read at once
        const c = box(card);
        return document.elementFromPoint(c.right - 6, c.bottom - 6);
    };

    S2P.scenario({
        name: 'for-whom-phone',
        title: 'a page "Dla kogo" on a phone: the heading and the start in the first screen, nothing sticks out sideways, one column, the questions 44 px',
        page: 'for-whom-dj-weselny-pl',
        viewport: '390,844',
        run: async function (t) {
            const shots = [document.getElementById('landingDashboardShot'), document.getElementById('landingGuestShot')];
            await t.waitFor(function () { return shots.every(function (img) { return img.complete; }); }, 'the pictures load');
            t.check('both pictures are drawn', shots.every(function (img) { return img.naturalWidth > 0; }));
            t.check('no scrolling sideways', document.documentElement.scrollWidth <= width());
            const wide = Array.from(document.querySelectorAll('body *')).filter(function (el) {
                return box(el).width > 0 && (box(el).right > width() + 0.5 || box(el).left < -0.5);
            });
            t.step('nothing sticks out of the screen', wide.map(function (el) { return el.tagName + '.' + el.className; }), []);
            t.check('the heading and "Zbieraj prośby gości" in the first screen',
                box(document.getElementById('forWhomStart')).bottom <= window.innerHeight && box(document.querySelector('h1')).top >= 0);
            // where one is, at once (the owner, 2026-10-21: from the landing page's card the page looked the same)
            const crumbs = document.getElementById('forWhomBreadcrumb');
            const kind = document.getElementById('forWhomKind');
            t.check('the breadcrumbs and the kind above the heading, in the first screen', box(crumbs).height > 0
                && box(crumbs).bottom <= box(kind).top && box(kind).bottom <= box(document.querySelector('h1')).top
                && box(kind).height > 0);
            t.step('the breadcrumbs say the way back', Array.from(crumbs.querySelectorAll('li')).map(function (li) { return li.textContent.trim(); }),
                ['Scan2Play', 'Dla kogo', 'DJ weselny']);
            const steps = Array.from(document.querySelectorAll('#forWhomSteps .s2p-landing-step'));
            t.step('four steps, one under another', [steps.length, rows(steps)], [4, 4]);
            const questions = Array.from(document.querySelectorAll('#forWhomFaq summary'));
            t.step('three questions, each 44 px to tap', [questions.length,
                questions.filter(function (q) { return box(q).height >= 44 - 0.5; }).length], [3, 3]);
            const others = Array.from(document.querySelectorAll('#forWhomOthers .s2p-landing-card'));
            t.step('the other two kinds, each a link as a whole card', others.map(function (card) {
                const hit = tapped(card);
                return hit && hit.closest('a') ? hit.closest('a').getAttribute('href') : null;
            }), ['/kluby', '/puby']);
        }
    });

    S2P.scenario({
        name: 'for-whom-wide',
        title: 'a page "Dla kogo" on a wide screen: the pictures beside the heading in the page\'s language, the steps and the features four in a row',
        page: 'for-whom-kluby-en',
        run: async function (t) {
            const dashboard = document.getElementById('landingDashboardShot');
            await t.waitFor(function () { return dashboard.complete; }, 'the picture loads');
            t.step('the picture in English', dashboard.getAttribute('src'), '/images/landing/dashboard-en.webp');
            t.check('the laptop beside the heading', box(dashboard).left > box(document.querySelector('h1')).right);
            t.step('the steps in one row', rows(Array.from(document.querySelectorAll('#forWhomSteps .s2p-landing-step'))), 1);
            t.step('the features in one row', rows(Array.from(document.querySelectorAll('#forWhomFeatures .s2p-landing-card'))), 1);
            t.check('no scrolling sideways', document.documentElement.scrollWidth <= width());
        }
    });

    S2P.scenario({
        name: 'landing-who-cards-lead-to-their-pages',
        title: 'the landing page\'s "Dla kogo": a tap anywhere on the wedding, club or pub card opens its page; karaoke is not a link yet',
        page: 'landing-pl',
        viewport: '390,844',
        run: async function (t) {
            const cards = Array.from(document.querySelectorAll('#landingWho .s2p-landing-card'));
            t.step('where a tap on each card leads', cards.map(function (card) {
                const hit = tapped(card);
                return hit && hit.closest('a') ? hit.closest('a').getAttribute('href') : null;
            }), ['/dj-weselny', '/kluby', '/puby', null]);
            t.check('no scrolling sideways', document.documentElement.scrollWidth <= width());
        }
    });
})();
