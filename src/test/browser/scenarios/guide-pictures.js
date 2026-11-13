// The guide's pictures (guide-pictures.js): a tap shows a picture whole in the middle of the screen, on the same page — the owner,
// 2026-11-12: the link opened the bare file, left at the top, and only "Wstecz" led back. A tap anywhere or "✕" closes it; the page
// stays where it was. Measured against document.documentElement.clientWidth (CLAUDE.md "Mind" 2).
(function () {
    const box = function (el) { return el.getBoundingClientRect(); };

    /** One picture tapped and closed by {@code close}: shown whole and in the middle, no page load, the page where it was. */
    async function tapAndClose(t, id, close) {
        const loads = [];
        navigation.addEventListener('navigate', function (e) { loads.push(e.destination.url); e.preventDefault(); });
        const link = document.querySelector('#' + id + ' a');
        const dialog = document.getElementById('guidePictureDialog');
        const big = dialog.querySelector('img');
        link.scrollIntoView({block: 'center', behavior: 'instant'});
        const scrolled = window.scrollY;

        link.click();
        t.step(id + ': the picture in the dialog, its description too', [dialog.open, big.getAttribute('src'), big.alt.length > 20],
            [true, link.getAttribute('href'), true]);
        await t.waitFor(function () { return big.complete && big.naturalWidth > 0; }, id + ': the picture loads');
        const w = document.documentElement.clientWidth;
        const h = window.innerHeight;
        const b = box(big);
        if (w < 768 && dialog.classList.contains('s2p-picture-wide')) {
            // a computer's screen on an upright phone: twice as wide as the screen, slid along in the dialog, the page itself still
            t.check(id + ': twice the screen\'s width (' + Math.round(b.width) + '), from its left edge, as high as it fits',
                Math.abs(b.width - 2 * w) <= 2 && Math.abs(b.left) <= 0.5 && b.top >= 0 && b.bottom <= h + 0.5);
            t.check(id + ': slid along in the dialog', dialog.scrollWidth > dialog.clientWidth + w / 2);
            dialog.scrollLeft = w;
            t.check(id + ': … with a finger (scrolled ' + dialog.scrollLeft + ')', dialog.scrollLeft >= w - 1);
            t.check(id + ': the page does not scroll sideways', document.documentElement.scrollWidth <= w);
        } else {
            t.check(id + ': whole on the screen (' + Math.round(b.left) + ',' + Math.round(b.top) + ' – ' + Math.round(b.right) + ','
                + Math.round(b.bottom) + ' of ' + w + ' × ' + h + ')', b.left >= 0 && b.top >= 0 && b.right <= w + 0.5 && b.bottom <= h + 0.5);
            t.check(id + ': in the middle (±2 px)', Math.abs((b.left + b.right) / 2 - w / 2) <= 2 && Math.abs((b.top + b.bottom) / 2 - h / 2) <= 2);
        }
        t.step(id + ': wide (a computer\'s screen) or upright', dialog.classList.contains('s2p-picture-wide'), id !== 'guidePicture2');
        const x = dialog.querySelector('.btn-close');
        t.check(id + ': "✕" 44 px to tap, on the screen', box(x).width >= 44 - 0.5 && box(x).height >= 44 - 0.5 && box(x).right <= w);

        close(dialog);
        t.step(id + ': closed, no page load, the page where it was', [dialog.open, loads, window.scrollY], [false, [], scrolled]);
    }

    /** The three pictures, each closed another way: a tap on the picture, "✕", the backdrop (a click on the dialog itself). */
    async function allThree(t) {
        t.check('the dialog is there, closed', !!document.getElementById('guidePictureDialog')
            && !document.getElementById('guidePictureDialog').open);
        await tapAndClose(t, 'guidePicture1', function (dialog) { dialog.querySelector('img').click(); });
        await tapAndClose(t, 'guidePicture2', function (dialog) { dialog.querySelector('.btn-close').click(); });
        await tapAndClose(t, 'guidePicture3', function (dialog) { dialog.click(); });
    }

    S2P.scenario({
        name: 'guide-pictures-phone',
        title: 'the guide\'s pictures on a phone: a tap shows one big on the same page — the upright one whole in the middle, a computer\'s screen twice the screen\'s width to slide along; a tap on it, on the backdrop or "✕" closes it, the page where it was',
        page: 'guide-pl',
        viewport: '390,844',
        run: allThree
    });

    S2P.scenario({
        name: 'guide-pictures-computer',
        title: 'the guide\'s pictures on a computer: the same, the phone\'s upright picture as high as the screen allows',
        page: 'guide-pl',
        viewport: '1280,800',
        run: allThree
    });
})();
