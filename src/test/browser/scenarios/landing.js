// The landing page (2026-10-17, GuestPageRenderTest renders it in both languages): at once how it looks — the DJ's panel in a laptop
// and the guest's page in a phone, real pictures of the app in the page's language —, then the steps, the features, for whom and the
// questions, folded. No script of its own: these check the layout under the real policy, on a phone and on a wide screen.
(function () {
    const shows = function (el) { return !!el && el.getClientRects().length > 0 && getComputedStyle(el).visibility !== 'hidden'; };
    const box = function (id) { return document.getElementById(id).getBoundingClientRect(); };
    /** Waits until the pictures are in: each loaded and drawn. */
    const pictures = async function (t) {
        const shots = [document.getElementById('landingDashboardShot'), document.getElementById('landingGuestShot')];
        await t.waitFor(function () { return shots.every(function (img) { return img.complete; }); }, 'the pictures load');
        return shots;
    };

    S2P.scenario({
        name: 'landing-phone',
        title: 'the landing page on a phone: the title and the start in the first screen, the pictures under them, nothing wider than the screen, the questions 44 px and folded',
        page: 'landing-pl',
        viewport: '390,844',
        run: async function (t) {
            const shots = await pictures(t);
            t.check('both pictures are drawn', shots.every(function (img) { return img.naturalWidth > 0 && img.getBoundingClientRect().width > 0; }));
            t.check('no scrolling sideways', document.documentElement.scrollWidth <= window.innerWidth);
            t.check('the title and "Zbieraj prośby gości" in the first screen', box('landingStart').bottom <= window.innerHeight
                && shows(document.querySelector('.s2p-landing-title')));
            t.check('the pictures under the start, within the screen', box('landingDashboardShot').top > box('landingStart').bottom
                && box('landingDashboardShot').right <= window.innerWidth && box('landingGuestShot').left >= 0);
            const questions = Array.from(document.querySelectorAll('#landingFaq summary'));
            t.step('six questions, each 44 px to tap', [questions.length,
                questions.filter(function (q) { return q.getBoundingClientRect().height >= 44 - 0.5; }).length], [6, 6]);
            const answer = questions[0].parentElement.querySelector('p');
            t.check('folded at first', !shows(answer) || !answer.checkVisibility());
            questions[0].click();
            await t.sleep(100);
            t.check('a tap shows the answer', answer.checkVisibility());
        }
    });

    S2P.scenario({
        name: 'landing-wide',
        title: 'the landing page on a wide screen: the laptop and the phone beside the title, the pictures in the page\'s language, the cards in rows',
        page: 'landing-en',
        run: async function (t) {
            const shots = await pictures(t);
            t.check('both pictures are drawn', shots.every(function (img) { return img.naturalWidth > 0; }));
            t.step('the pictures in English', shots.map(function (img) { return img.getAttribute('src'); }),
                ['/images/landing/dashboard-en.webp', '/images/landing/guest-en.webp']);
            const title = document.querySelector('.s2p-landing-title').getBoundingClientRect();
            t.check('the laptop beside the title', box('landingDashboardShot').left > title.right);
            t.check('the phone over the laptop\'s lower left', box('landingGuestShot').left < box('landingDashboardShot').left
                && box('landingGuestShot').bottom > box('landingDashboardShot').bottom);
            const cards = Array.from(document.querySelectorAll('#landingFeatures .s2p-landing-card'));
            t.step('the features three in a row', new Set(cards.map(function (c) { return Math.round(c.getBoundingClientRect().top); })).size, 2);
            t.check('no scrolling sideways', document.documentElement.scrollWidth <= window.innerWidth);
        }
    });
})();
