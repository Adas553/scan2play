// "Edytuj | Podgląd" (qr-print.html #posterModes, qr-print.js): below 1100 px the editor stood above the paper — on a phone 1800 px of
// fields before the preview, the DJ typed a heading and saw nothing. Now one switch, held at the top: the preview (the default — the page
// opens on what prints) or the editor. The preview hidden while editing is still laid out, so the fitting measures it and the print
// always has it; on a computer both side by side and no switch.
(function () {
    function type(id, text) {
        const field = document.getElementById(id);
        field.value = text;
        field.dispatchEvent(new Event('input', { bubbles: true }));
    }

    function mode(name) { document.querySelector('#posterModes [data-mode="' + name + '"]').click(); }

    function pressed() {
        return Array.from(document.querySelectorAll('#posterModes [data-mode]'))
            .filter(function (b) { return b.getAttribute('aria-pressed') === 'true'; }).map(function (b) { return b.dataset.mode; });
    }

    function seen(el) { return el.checkVisibility({ visibilityProperty: true }); }

    S2P.scenario({
        name: 'poster-phone-modes',
        page: 'qr-print-poster',
        viewport: '390,844',
        title: 'a phone: "Edytuj | Podgląd" at the top — the preview first, the editor on a tap and the paper out of the way, what was typed in the preview on the tap back',
        run: async function (t) {
            await document.fonts.ready;
            const form = document.getElementById('posterForm'), main = document.querySelector('body > main');
            const modes = document.getElementById('posterModes');
            t.step('the switch shown, the preview picked', [seen(modes), pressed()], [true, ['preview']]);
            t.step('the preview first: the sheet begins in the first screen\'s upper half, the editor out of the way',
                [main.getBoundingClientRect().top < window.innerHeight / 2, seen(form)], [true, false]);

            mode('edit');
            t.step('"Edytuj": the editor, the paper out of the way', [pressed(), seen(form), seen(main)], [['edit'], true, false]);
            const end = form.getBoundingClientRect().bottom + window.scrollY;
            t.check('the page ends with the editor (' + document.documentElement.scrollHeight + ' ≤ ' + Math.round(end + 40) + ')',
                document.documentElement.scrollHeight <= end + 40);
            window.scrollTo(0, 900);
            await t.sleep(50);
            t.check('the switch held at the top while the editor scrolls (' + Math.round(modes.getBoundingClientRect().top) + ')',
                Math.abs(modes.getBoundingClientRect().top) < 2);
            type('posterHeading', 'Osiemnastka Dawida');

            mode('preview');
            const own = document.querySelector('.poster [data-poster-heading]');
            t.step('"Podgląd": the heading typed on the sheet, the editor away', [seen(main), own.textContent, seen(form)],
                [true, 'Osiemnastka Dawida', false]);
            t.check('the sheet in view (' + Math.round(main.getBoundingClientRect().top) + ')', main.getBoundingClientRect().top < 844);
            t.step('both buttons full taps', Array.from(modes.querySelectorAll('button')).filter(function (b) {
                return b.getBoundingClientRect().height < 43.5; }).length, 0);
            t.check('no sideways scroll', document.documentElement.scrollWidth <= document.documentElement.clientWidth);
        }
    });

    S2P.scenario({
        name: 'poster-phone-modes-fitted',
        page: 'qr-print-cards',
        viewport: '360,780',
        title: 'a heading typed while the preview is away is fitted all the same: the cards\' longest heading smaller, whole in three lines',
        run: async function (t) {
            await document.fonts.ready;
            mode('edit');
            type('posterHeading', 'W'.repeat(40));
            mode('preview');
            const own = document.querySelector('.card .title .own'), line = parseFloat(getComputedStyle(own).lineHeight);
            t.check('made smaller (' + own.style.fontSize + ')', parseFloat(own.style.fontSize) < 11);
            t.check('whole in three lines', own.scrollHeight <= own.clientHeight + line / 2 && Math.round(own.clientHeight / line) <= 3);
        }
    });

    S2P.scenario({
        name: 'poster-modes-wide',
        page: 'qr-print-poster',
        viewport: '1440,900',
        title: 'a computer: the editor and the preview side by side, no switch',
        run: async function (t) {
            t.step('no switch; the editor and the sheet both shown', [seen(document.getElementById('posterModes')),
                seen(document.getElementById('posterForm')), seen(document.querySelector('body > main'))], [false, true, true]);
        }
    });
})();
