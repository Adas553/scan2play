// "Życzenia przed imprezą" (V68): the guests' page /w/{token} on a phone, the DJ's page /dj/wishes on a phone, and the invitation's
// print — the editor of the QR print shows its changes and saves none (qr-print.js, data-preview-only), so it never says "not saved"
// nor asks on leaving.
(function () {
    /** Nothing wider than the screen: measured on the document, not window.innerWidth (CLAUDE.md, "Mind" 2). */
    const fits = function () {
        return document.documentElement.scrollWidth <= document.documentElement.clientWidth;
    };

    /** The guests' page on a phone: the event, the field and "Wyślij życzenie" on the first screen, tappable, nothing sideways. */
    const phonePage = async function (t) {
        const field = document.getElementById('songInput');
        const send = document.getElementById('wishSubmit');
        t.check('nothing sideways', fits());
        t.check('the event named', document.getElementById('wishEventName').textContent.trim() === 'Wesele Ani i Kuby');
        t.check('the field and the button at least 44 px high',
            field.getBoundingClientRect().height >= 44 && send.getBoundingClientRect().height >= 44);
        t.check('"Wyślij życzenie" on the first screen', send.getBoundingClientRect().bottom <= window.innerHeight);
    };

    S2P.scenario({
        name: 'wish-page-phone-390',
        title: 'the guests\' page of wishes on a phone (390 × 844): the event, the field and "Wyślij życzenie" on the first screen, tappable, nothing sideways',
        page: 'wish',
        viewport: '390,844',
        run: phonePage
    });

    S2P.scenario({
        name: 'wish-page-phone-320',
        title: 'the guests\' page of wishes on a small phone (320 × 640): the same',
        page: 'wish',
        viewport: '320,640',
        run: phonePage
    });

    S2P.scenario({
        name: 'wish-page-closed',
        title: 'the guests\' page of an event that stopped collecting: no field, the farewell, nothing sideways',
        page: 'wish-closed',
        viewport: '390,844',
        run: async function (t) {
            t.check('no field', !document.getElementById('songInput'));
            t.check('the farewell shown', document.getElementById('wishClosed').checkVisibility());
            t.check('nothing sideways', fits());
        }
    });

    S2P.scenario({
        name: 'wishes-page-phone',
        title: 'the DJ\'s page of wishes on a phone: a long song wraps in its row, every "⭐ Do „Koniecznie zagrać”" whole on the screen, nothing sideways',
        page: 'wishes',
        viewport: '390,844',
        run: async function (t) {
            t.check('nothing sideways', fits());
            const width = document.documentElement.clientWidth;
            const stars = Array.from(document.querySelectorAll('.s2p-wish-star'));
            t.check('a star on the songs not on the list yet', stars.length >= 3);
            t.step('every star inside the screen', stars.filter(function (b) {
                const box = b.getBoundingClientRect();
                return box.left < 0 || box.right > width;
            }).length, 0);
            const rows = Array.from(document.querySelectorAll('#wishRows > li'));
            t.step('every row inside the screen', rows.filter(function (row) { return row.getBoundingClientRect().right > width; }).length, 0);
        }
    });

    S2P.scenario({
        name: 'wishes-answer-in-place',
        title: 'the DJ\'s page of wishes after a form: the page where it was (scroll-restore.js, no anchor), what was done said at the '
            + 'bottom of the screen whatever the scroll — the page under it not moved — and closed with ✕',
        page: 'wishes-empty',
        viewport: '390,844',
        run: async function (t) {
            t.check('the page remembers its scroll (scroll-restore.js)', !!document.querySelector('script[src*="scroll-restore"]'));
            t.step('no link to the wishes or the settings jumps to an anchor', Array.from(document.querySelectorAll('a[href*="/dj/wishes"], a[href*="/dj/settings"]'))
                .filter(function (a) { return a.getAttribute('href').indexOf('#') >= 0; }).map(function (a) { return a.getAttribute('href'); }), []);
            const done = document.getElementById('wishDone');
            const box = done.getBoundingClientRect();
            t.check('said on the screen, at its bottom', box.bottom <= window.innerHeight && box.top > window.innerHeight / 2);
            t.check('over the page, not in it', getComputedStyle(done).position === 'fixed');
            window.scrollTo({ top: document.documentElement.scrollHeight, behavior: 'instant' });
            t.check('still on the screen at the end of the page', done.getBoundingClientRect().bottom <= window.innerHeight);
            const close = done.querySelector('[data-toast-close]');
            t.check('✕ a finger\'s size', close.getBoundingClientRect().height >= 44 && close.getBoundingClientRect().width >= 44);
            close.click();
            t.step('✕ closes it', done.hidden, true);
            t.check('nothing sideways', fits());
        }
    });

    S2P.scenario({
        name: 'qr-print-invitation-saves-nothing',
        title: 'the invitation\'s print: a heading typed shows on every card, the editor says nothing of "not saved" and leaving asks nothing',
        page: 'qr-print-cards-own-tables-invitation',
        viewport: '1280,900',
        run: async function (t) {
            const form = document.getElementById('posterForm');
            t.check('no "Zapisz", no language', !document.getElementById('posterSave') && !form.elements.language);
            const heading = form.elements.heading;
            heading.value = 'Twoja piosenka na nasze wesele';
            heading.dispatchEvent(new Event('input', { bubbles: true }));
            await t.sleep(50);
            t.step('the cards show it', Array.from(document.querySelectorAll('.card [data-poster-heading]')).filter(function (el) {
                return !el.hidden && el.textContent.indexOf('Twoja piosenka') >= 0;
            }).length, 8);
            t.step('no "not saved" note', document.getElementById('posterAnswer').textContent.trim(), '');
            const leaving = new Event('beforeunload', { cancelable: true });
            window.dispatchEvent(leaving);
            t.check('leaving asks nothing', !leaving.defaultPrevented);
            form.requestSubmit();
            await t.sleep(50);
            t.check('Enter in a field sends nothing: the page stays', document.getElementById('posterForm') === form);
        }
    });
})();
