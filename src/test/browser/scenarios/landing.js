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

    // The owner, 2026-10-19: the page moved a little to the right and left on a phone. The hero's row with Bootstrap's g-5 (a
    // negative margin of 1.5 rem) stuck out 12 px each side of the container (0.75 rem). A phone widens window.innerWidth to what
    // sticks out, so "fits" is measured against the layout viewport, document.documentElement.clientWidth.
    S2P.scenario({
        name: 'landing-narrow-phone',
        title: 'the landing page on a narrow phone (320 px): nothing sticks out sideways, the page does not move left and right',
        page: 'landing-pl',
        viewport: '320,700',
        run: async function (t) {
            await pictures(t);
            const width = document.documentElement.clientWidth;
            const out = Array.from(document.querySelectorAll('body *')).filter(function (el) {
                const r = el.getBoundingClientRect();
                return r.width > 0 && (r.left < -0.5 || r.right > width + 0.5);
            }).map(function (el) { return el.tagName.toLowerCase() + '.' + String(el.className).trim().split(/\s+/).slice(0, 2).join('.'); });
            t.step('nothing sticks out of the screen', out.slice(0, 5), []);
            t.check('no scrolling sideways', document.documentElement.scrollWidth <= width);
        }
    });

    S2P.scenario({
        name: 'landing-phone',
        title: 'the landing page on a phone: the title and the start in the first screen, the pictures under them, nothing wider than the screen, the questions 44 px and folded',
        page: 'landing-pl',
        viewport: '390,844',
        run: async function (t) {
            const shots = await pictures(t);
            t.check('both pictures are drawn', shots.every(function (img) { return img.naturalWidth > 0 && img.getBoundingClientRect().width > 0; }));
            t.check('no scrolling sideways', document.documentElement.scrollWidth <= document.documentElement.clientWidth);
            t.check('the title and "Zbieraj prośby gości" in the first screen', box('landingStart').bottom <= window.innerHeight
                && shows(document.querySelector('.s2p-landing-title')));
            t.check('the pictures under the start, within the screen', box('landingDashboardShot').top > box('landingStart').bottom
                && box('landingDashboardShot').right <= document.documentElement.clientWidth && box('landingGuestShot').left >= 0);
            const questions = Array.from(document.querySelectorAll('#landingFaq summary'));
            t.step('seven questions (the tips\' too), each 44 px to tap', [questions.length,
                questions.filter(function (q) { return q.getBoundingClientRect().height >= 44 - 0.5; }).length], [7, 7]);
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
            t.check('no scrolling sideways', document.documentElement.scrollWidth <= document.documentElement.clientWidth);
        }
    });
    // The review of 2026-10-21, U4: the links in an unfolded answer ("Polityka prywatności →") were Bootstrap's #0d6efd on the card,
    // 4.0 : 1 — under the 4.5 : 1 a text needs. contrast() is the helper of dj-booking.js (the scenarios' scripts share the page).
    S2P.scenario({
        name: 'landing-faq-links-readable',
        title: 'the landing page: a link in an unfolded answer is readable on its card (contrast at least 4.5 : 1)',
        page: 'landing-pl',
        viewport: '390,844',
        run: async function (t) {
            const links = Array.from(document.querySelectorAll('.s2p-landing-faq details a'));
            t.check('the questions have a link', links.length > 0);
            links.forEach(function (a) { a.closest('details').open = true; });
            const unreadable = links.filter(function (a) { return contrast(a) < 4.5; })
                .map(function (a) { return a.textContent.trim() + ' ' + contrast(a).toFixed(1) + ' : 1'; });
            t.step('every link readable', unreadable, []);
        }
    });
})();
