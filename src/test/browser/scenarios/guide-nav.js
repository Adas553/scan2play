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

    /** A tap as a finger makes it: the pointer down first (the page's smooth jumps start with the reader's first touch), then the click. */
    function tap(el) {
        el.dispatchEvent(new PointerEvent('pointerdown', {bubbles: true}));
        el.click();
    }

    /** The reader's own scroll (a wheel's, as a finger's or a key's would be): the place read is marked again, not the chip tapped. */
    function byHand(top) {
        window.dispatchEvent(new WheelEvent('wheel', {deltaY: top - window.scrollY}));
        window.scrollTo({top: top, behavior: 'instant'});
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
            tap(nav.querySelector('a[href="#' + id + '"]'));
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
        byHand(window.scrollY + box(document.getElementById('staff')).top - box(nav).bottom - 4);
        await t.waitFor(function () { return marked()[0] === '#staff=location'; }, 'scrolled into #staff: marked');
        byHand(0);
        await t.waitFor(function () { return marked().length === 0; }, 'at the top, above the sections: nothing marked');

        // "W skrócie": a step leads to its place
        tap(document.querySelector('#short a[href="#guidePrint"]'));
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

    // A screen taller than the page's last sections: a jump to "Obsługa" ends at the page's end, where the last section is marked by
    // default — the chip tapped stays marked; once the reader scrolls by themselves, the place read is marked again
    S2P.scenario({
        name: 'guide-nav-chosen-tall',
        title: 'the guide on a tall screen: a jump that ends at the page\'s end marks the chip tapped, not the last section; scrolling by hand marks the place read',
        page: 'guide-pl',
        viewport: '1280,1800',
        run: async function (t) {
            const nav = document.getElementById('guideJump');
            tap(nav.querySelector('a[href="#staff"]'));
            await settled(t, '#staff');
            const end = document.documentElement.scrollHeight - window.innerHeight;
            t.check('the jump ended at the page\'s end (' + Math.round(window.scrollY) + ' of ' + Math.round(end) + ')', window.scrollY >= end - 2);
            await t.sleep(100);
            t.step('the chip tapped stays marked', marked(), ['#staff=location']);
            // the reader's own scroll up (the wheel, as a finger's or a key's would): the place read again
            byHand(window.scrollY + box(document.getElementById('after')).top - box(nav).bottom - 4);
            await t.waitFor(function () { return marked()[0] === '#after=location'; }, 'scrolled by hand into #after: marked');
        }
    });

    /** Entered by a link into a place (the panel's "Zagrane samo", a link someone sent): there at once, not scrolled down to. */
    async function entered(t, id, sectionId) {
        const nav = document.getElementById('guideJump');
        const target = document.getElementById(id);
        // the browser's own jump may come just after the load event (Chrome): watched frame by frame until the page stays put
        const places = [];
        let same = 0;
        await t.waitFor(function () {
            const y = Math.round(window.scrollY);
            same = places[places.length - 1] === y ? same + 1 : 0;
            if (!same) places.push(y);
            return same >= 10;
        }, 'the page stays put');
        t.check('there at once, not slid down to (' + places.length + ' places: ' + places.slice(0, 4).join(', ') + ' …)', places.length <= 2);
        t.check(id + ' under the bar, not behind it (' + Math.round(box(target).top) + ' vs ' + Math.round(box(nav).bottom) + ')',
            box(target).top >= box(nav).bottom - 0.5 && box(target).top <= box(nav).bottom + 48);
        await t.waitFor(function () { return marked()[0] === '#' + sectionId + '=location'; }, '#' + sectionId + ' marked in the bar');
    }

    S2P.scenario({
        name: 'guide-nav-entry-phone',
        title: 'the guide entered as /jak-to-dziala#deck on a phone: the section there at once, under the bar, marked',
        page: 'guide-pl',
        viewport: '390,844',
        hash: '#deck',
        run: function (t) { return entered(t, 'deck', 'deck'); }
    });

    S2P.scenario({
        name: 'guide-nav-entry-fold',
        title: 'the guide entered as /jak-to-dziala#guidePicture4 (in a folded "Więcej"): the fold open, the picture there at once, its section marked',
        page: 'guide-en',
        viewport: '1280,800',
        hash: '#guidePicture4',
        run: async function (t) {
            t.check('its fold open', document.getElementById('guideDeckMore').open);
            await entered(t, 'guidePicture4', 'deck');
        }
    });

    // Tabbing back up the page: what gets the focus is shown under the bar, not behind it (WCAG 2.4.11), as a jump's place is
    async function focusShown(t) {
        const nav = document.getElementById('guideJump');
        for (const selector of ['#guidePollsMore > summary', '#short a[href="#after"]', '#guideKioskMore > summary']) {
            const el = document.querySelector(selector);
            // its top just above the screen, as after Shift+Tab from the line under it
            window.scrollTo({top: window.scrollY + box(el).top + 10, behavior: 'instant'});
            el.focus();
            await settled(t, selector);
            t.check(selector + ' focused: under the bar (' + Math.round(box(el).top) + ' vs ' + Math.round(box(nav).bottom) + ')',
                document.activeElement === el && box(el).top >= box(nav).bottom - 0.5);
        }
    }

    S2P.scenario({
        name: 'guide-nav-focus-phone',
        title: 'the guide on a phone: a control focused above the screen comes into sight under the bar, not behind it',
        page: 'guide-pl',
        viewport: '390,844',
        run: focusShown
    });

    S2P.scenario({
        name: 'guide-nav-focus-computer',
        title: 'the guide on a computer: the same under the bar\'s two rows',
        page: 'guide-en',
        viewport: '1024,768',
        run: focusShown
    });
    // A phone's row of jumps: as wide as the screen, and it shows that it slides — faded at the side where more chips are
    S2P.scenario({
        name: 'guide-nav-row-fades',
        title: 'the guide on a phone: the row of jumps reaches the screen\'s edges and fades where more chips are — at the right at first, at the left at the end',
        page: 'guide-pl',
        viewport: '390,844',
        run: async function (t) {
            const nav = document.getElementById('guideJump');
            const row = nav.querySelector('.s2p-chips');
            const w = document.documentElement.clientWidth;
            const fades = function () {
                return ['s2p-guide-more-start', 's2p-guide-more-end'].filter(function (c) { return row.classList.contains(c); });
            };
            // the marked chip is never under a faded side (2.5rem = 40 px): it is slid out of it, whatever the fonts' widths
            const clear = function (label) {
                const chip = box(nav.querySelector('a[aria-current]'));
                const left = box(row).left + (row.classList.contains('s2p-guide-more-start') ? 40 : 0);
                const right = box(row).right - (row.classList.contains('s2p-guide-more-end') ? 40 : 0);
                t.check(label + ': the marked chip clear of the fades (' + Math.round(chip.left) + '…' + Math.round(chip.right) + ' in '
                    + Math.round(left) + '…' + Math.round(right) + ')', chip.left >= left - 0.5 && chip.right <= right + 0.5);
            };
            t.check('the row from the screen\'s left edge to its right (' + Math.round(box(row).left) + '…' + Math.round(box(row).right)
                + ' of ' + w + ')', Math.abs(box(row).left) <= 0.5 && Math.abs(box(row).right - w) <= 0.5);
            t.check('… its first chip where the page\'s text starts (' + Math.round(box(row.firstElementChild).left) + ')',
                Math.abs(box(row.firstElementChild).left - box(document.querySelector('.s2p-guide h1')).left) <= 1);
            t.step('at first: faded at the right only', fades(), ['s2p-guide-more-end']);
            t.check('… the mask drawn', getComputedStyle(row).maskImage !== 'none' || getComputedStyle(row).webkitMaskImage !== 'none');
            tap(nav.querySelector('a[href="#staff"]'));
            await settled(t, '#staff');
            await t.waitFor(function () { return fades().length === 2; }, 'in the middle of the row: faded at both sides');
            clear('#staff');
            tap(nav.querySelector('a[href="#guests"]'));
            await settled(t, '#guests');
            await t.waitFor(function () { return fades().join() === 's2p-guide-more-start'; }, 'at the row\'s end: faded at the left only');
            clear('#guests');
            t.check('still no sideways scrolling of the page', document.documentElement.scrollWidth <= w);
        }
    });

    // "W skrócie" on a phone: a step's line of what it is stays one line, so the five steps fit in about half a screen (it was one and
    // a half: 710 px of 844)
    S2P.scenario({
        name: 'guide-short-phone',
        title: 'the guide on a phone: "W skrócie" — each step\'s description on one line, the card in about half the screen, the whole text still in the page',
        page: 'guide-pl',
        viewport: '390,844',
        run: async function (t) {
            const spans = Array.from(document.querySelectorAll('#short .s2p-guide-steps span'));
            t.step('every description one line', spans.filter(function (s) {
                return box(s).height > parseFloat(getComputedStyle(s).lineHeight) * 1.5;
            }).map(function (s) { return s.textContent.trim().slice(0, 30); }), []);
            const card = box(document.getElementById('short'));
            t.check('the card in about half the screen (' + Math.round(card.height) + ' px)', card.height <= window.innerHeight * 0.55);
            t.check('the full text still there for a screen reader', spans[0].textContent.indexOf('według nich AI ocenia prośby') > 0);
        }
    });

    // The bar taller than the page reserved for it (a third row: a longer name, text made larger): a jump still puts the heading
    // under it — the bar's own height is measured
    S2P.scenario({
        name: 'guide-nav-taller-bar',
        title: 'the guide with its bar grown taller: a jump still puts the section\'s heading under the bar, not behind it',
        page: 'guide-en',
        viewport: '390,844',
        run: async function (t) {
            const nav = document.getElementById('guideJump');
            nav.querySelectorAll('a').forEach(function (a) { a.style.minHeight = '130px'; });
            await t.sleep(100);
            for (const id of ['deck', 'staff']) {
                tap(nav.querySelector('a[href="#' + id + '"]'));
                await settled(t, '#' + id);
                const heading = box(document.querySelector('#' + id + ' h2'));
                t.check('#' + id + ': its heading under the ' + Math.round(box(nav).height) + ' px bar (' + Math.round(heading.top)
                    + ' vs ' + Math.round(box(nav).bottom) + ')', heading.top >= box(nav).bottom && heading.top <= box(nav).bottom + 48);
            }
        }
    });
})();
