// The QR print's editor (qr-print.js): changes not saved yet go along to the other layout (poster ↔ cards ↔ tent), so the DJ can compare the
// two before "Zapisz". A scenario lives on one page, so each side is checked on its own: the poster keeps them for the tab when its
// switch is clicked (the click's navigation held back here), and the cards — the script loaded again, as the next page would — take
// them, show them and say they are not saved; the stash is then gone.
(function () {
    function type(id, value) {
        const el = document.getElementById(id);
        el.value = value;
        el.dispatchEvent(new Event('input', { bubbles: true }));
    }

    function stashKey() { return 's2p-poster-' + document.querySelector('#posterForm input[name="partyCode"]').value; }

    function cardsLink() { return document.querySelector('[data-layout-switch]:not(.active)'); }

    /** The switch clicked as the DJ would, its navigation held back (the scenario would end with the page). */
    function clickSwitch() {
        const hold = function (e) { e.preventDefault(); };
        document.addEventListener('click', hold);
        cardsLink().click();
        document.removeEventListener('click', hold);
    }

    S2P.scenario({
        name: 'poster-switch-keeps',
        page: 'qr-print-poster',
        title: 'the poster\'s switch to the cards keeps what was changed and not saved — the heading, the line, the theme, the colour, the parts — for the tab',
        run: async function (t) {
            sessionStorage.removeItem(stashKey());
            clickSwitch();
            t.step('nothing changed: nothing kept', sessionStorage.getItem(stashKey()), null);
            type('posterHeading', 'Wesele Ani i Tomka');
            type('posterLine', 'Zatańczmy razem');
            type('posterTables', '12');
            const theme = document.getElementById('posterTheme');
            theme.value = 'ELEGANT';
            theme.dispatchEvent(new Event('change', { bubbles: true }));
            const picker = document.getElementById('posterAccentPicker');
            picker.value = '#123abc';
            picker.dispatchEvent(new Event('input', { bubbles: true }));
            const address = document.querySelector('[data-poster-toggle="address"]');
            address.checked = false;
            address.dispatchEvent(new Event('change', { bubbles: true }));
            const form = document.getElementById('posterForm');
            t.step('the editor says the changes are not saved', document.getElementById('posterAnswer').textContent, form.dataset.unsaved);
            clickSwitch();
            const kept = JSON.parse(sessionStorage.getItem(stashKey()) || '{}');
            t.step('kept for the tab', [kept.heading, kept.line, kept.tables, kept.theme, kept.accent, kept.parts && kept.parts.address],
                ['Wesele Ani i Tomka', 'Zatańczmy razem', '12', 'ELEGANT', '#123abc', false]);
            sessionStorage.removeItem(stashKey());
        }
    });

    S2P.scenario({
        name: 'poster-switch-takes',
        page: 'qr-print-cards',
        title: 'the cards after the switch: the kept changes in the editor and on the cards, "Niezapisane zmiany", the stash gone; "Zapisz" sends them',
        run: async function (t) {
            sessionStorage.setItem(stashKey(), JSON.stringify({ heading: 'Wesele Ani i Tomka', line: 'Zatańczmy razem', tables: '12', theme: 'ELEGANT',
                accent: '#123abc', picker: '#123abc', parts: { address: false } }));
            const script = document.createElement('script');
            script.src = document.querySelector('script[src*="qr-print.js"]').src;
            await new Promise(function (resolve, reject) { script.onload = resolve; script.onerror = reject; document.body.appendChild(script); });
            const form = document.getElementById('posterForm');
            const heading = document.querySelector('.card [data-poster-heading]');
            t.step('the editor has them', [form.elements.heading.value, form.elements.line.value, form.elements.tables.value, form.elements.theme.value,
                document.getElementById('posterAccentOwn').checked, document.getElementById('posterAccentPicker').value,
                document.querySelector('[data-poster-toggle="address"]').checked],
                ['Wesele Ani i Tomka', 'Zatańczmy razem', '12', 'ELEGANT', true, '#123abc', false]);
            t.step('the cards show them', [heading.textContent, heading.hidden, document.querySelectorAll('.card').length,
                document.body.classList.contains('theme-elegant'), document.querySelector('.card [data-poster-part="address"]').hidden],
                ['Wesele Ani i Tomka', false, 12, true, true]);
            const answer = document.getElementById('posterAnswer');
            t.step('not saved, it says; the stash gone', [answer.textContent, answer.classList.contains('unsaved'),
                sessionStorage.getItem(stashKey())], [form.dataset.unsaved, true, null]);
            document.getElementById('posterSave').click();
            await t.waitFor(function () { return answer.textContent === form.dataset.saved; }, 'saved', 3000);
            const sent = (await t.stand.requests('POST /dj/qr-print/settings')).pop().q;
            t.step('"Zapisz" sends them', [sent.heading, sent.tables, sent.theme, sent.accent, sent.showAddress], ['Wesele Ani i Tomka', '12',
                'ELEGANT', '#123abc', undefined]);
        }
    });

    // "Numer pierwszego stołu" is never saved, but goes along with the switch — the DJ compares the layouts —, also with nothing else
    // changed; a reload (no switch) starts from 1 again.
    function firstKey() { return 's2p-poster-first-' + document.querySelector('#posterForm input[name="partyCode"]').value; }

    S2P.scenario({
        name: 'poster-switch-keeps-first-table',
        page: 'qr-print-cards',
        title: 'the switch keeps the first table\'s number for the next layout, with nothing else changed',
        run: async function (t) {
            sessionStorage.removeItem(firstKey());
            sessionStorage.removeItem(stashKey());
            type('posterTables', '1');
            document.getElementById('posterSave').click();
            const form = document.getElementById('posterForm');
            await t.waitFor(function () { return document.getElementById('posterAnswer').textContent === form.dataset.saved; }, 'saved', 3000);
            type('posterTablesFrom', '16');
            clickSwitch();
            t.step('kept for the next page; nothing else stashed', [sessionStorage.getItem(firstKey()), sessionStorage.getItem(stashKey())], ['16', null]);
            sessionStorage.removeItem(firstKey());
        }
    });

    S2P.scenario({
        name: 'poster-switch-takes-first-table',
        page: 'qr-print-tent',
        title: 'the next layout takes the first table\'s number: the field and the numbers; then it is gone, so a reload starts from 1',
        run: async function (t) {
            type('posterTables', '1');
            sessionStorage.setItem(firstKey(), '16');
            const script = document.createElement('script');
            script.src = document.querySelector('script[src*="qr-print.js"]').src;
            await new Promise(function (resolve, reject) { script.onload = resolve; script.onerror = reject; document.body.appendChild(script); });
            const no = document.querySelector('.tent [data-table-n]');
            t.step('the field and the tent', [document.getElementById('posterTablesFrom').value, no.textContent], ['16', '16']);
            t.step('gone once taken', sessionStorage.getItem(firstKey()), null);
        }
    });
})();
