// The guide's jump bar, "W skrócie" and its folds (guide-nav.js): the bar stays at the top while the page scrolls, a jump puts the
// section's heading just under it (not behind it), the section being read is marked in the bar — and on a phone kept in sight in
// its row —, a link into a folded "Więcej" opens it. Measured against document.documentElement.clientWidth (CLAUDE.md "Mind" 2).
(function () {
    const box = function (el) { return el.getBoundingClientRect(); };

    /** Waits until the page stops scrolling (a jump may scroll smoothly). */
    async function settled(t, label) {
        let last = -1;
        let same = 0;
        await t.waitFor(function () {
            same = window.scrollY === last ? same + 1 : 0;
            last = window.scrollY;
            return same >= 5;
        }, label + ': the page stops scrolling');
    }

    function marked() {
        return Array.from(document.querySelectorAll('#guideJump a[aria-current]')).map(function (a) {
            return a.getAttribute('href') + '=' + a.getAttribute('aria-current');
        });
    }

    async function run(t) {
        const nav = document.getElementById('guideJump');
        const row = nav.querySelector('.s2p-chips');
        const w = document.documentElement.clientWidth;
        t.check('the page does not scroll sideways', document.documentElement.scrollWidth <= w);
        t.check('the bar no wider than the screen', box(nav).left >= 0 && box(nav).right <= w + 0.5);
        t.step('the folds are closed at first', ['guidePollsMore', 'guideKioskMore', 'guideDeckMore'].map(function (id) {
            return document.getElementById(id).open;
        }), [false, false, false]);

        // a jump from the bar: the section's heading right under the bar, the bar stuck at the top, that section marked
        for (const id of ['after', 'party', 'guests']) {
            nav.querySelector('a[href="#' + id + '"]').click();
            await settled(t, '#' + id);
            const heading = document.querySelector('#' + id + ' h2');
            const bar = box(nav);
            t.check('#' + id + ': the bar stuck at the top (' + Math.round(bar.top) + ')', Math.abs(bar.top) <= 0.5);
            t.check('#' + id + ': its heading under the bar, not behind it (' + Math.round(box(heading).top) + ' vs '
                + Math.round(bar.bottom) + ')', box(heading).top >= bar.bottom && box(heading).top <= bar.bottom + 48);
            await t.waitFor(function () { return marked().length === 1 && marked()[0] === '#' + id + '=location'; },
                '#' + id + ' marked in the bar');
            const chip = nav.querySelector('a[aria-current]');
            t.check('#' + id + ': its chip in sight in the bar', box(chip).left >= box(row).left - 0.5 && box(chip).right <= box(row).right + 0.5);
        }

        // reading on: scrolled by hand into a section, that section marked
        window.scrollTo({top: window.scrollY + box(document.getElementById('staff')).top - box(nav).bottom - 4, behavior: 'instant'});
        await t.waitFor(function () { return marked()[0] === '#staff=location'; }, 'scrolled into #staff: marked');
        window.scrollTo({top: 0, behavior: 'instant'});
        await t.waitFor(function () { return marked().length === 0; }, 'at the top, above the sections: nothing marked');

        // "W skrócie": a step leads to its place
        document.querySelector('#short a[href="#guidePrint"]').click();
        await settled(t, '#guidePrint');
        t.check('"W skrócie" → the print\'s row, under the bar', box(document.getElementById('guidePrint')).top >= box(nav).bottom - 0.5
            && box(document.getElementById('guidePrint')).top <= box(nav).bottom + 48);

        // a link into a fold opens it
        location.hash = '#guidePicture4';
        await t.waitFor(function () { return document.getElementById('guideDeckMore').open; }, 'a link into "Więcej" opens it');
        await settled(t, '#guidePicture4');
        const picture = box(document.getElementById('guidePicture4'));
        t.check('… and the picture is in sight under the bar', picture.top >= box(nav).bottom - 0.5 && picture.top < window.innerHeight);
        t.check('still no sideways scrolling', document.documentElement.scrollWidth <= w);
    }

    S2P.scenario({
        name: 'guide-nav-phone',
        title: 'the guide on a phone: the jumps stay at the top in one row, a jump puts the heading under them and marks its chip, kept in sight; "W skrócie" leads to its step; a link into "Więcej" opens it',
        page: 'guide-pl',
        viewport: '390,844',
        run: run
    });

    S2P.scenario({
        name: 'guide-nav-computer',
        title: 'the guide on a computer: the same, the jumps wrapped',
        page: 'guide-pl',
        viewport: '1280,800',
        run: run
    });
})();
