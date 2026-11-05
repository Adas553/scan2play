// "PORANNE PODSUMOWANIE" (V64): the organiser's switch of the morning e-mail in "Ustawienia imprezy". Folded to its state; switched,
// it is sent at once in the background (data-auto-submit → cards-in-place.js) — the party, the switch's value (none: off), the
// fetch's header and the token —, the server's card put in place with its new state and "✓ Zapisane", the focus kept on the switch,
// no page load, no "Zapisz" with the script. On a phone, 44 px to tap and nothing wider than the screen.
(function () {
    S2P.scenario({
        name: 'morning-summary-switch',
        title: '"Poranne podsumowanie" on a phone: folded to "Włączone"; switched off it is sent at once in the background, the server\'s card in place ("Wyłączone", "✓ Zapisane"), the focus on the switch, no page load',
        page: 'settings',
        viewport: '390,844',
        run: async function (t) {
            const loads = [];
            navigation.addEventListener('navigate', function (e) { loads.push(e.destination.url); e.preventDefault(); });
            const card = document.getElementById('morningSummaryCard');
            t.check('the organiser\'s card is there', !!card);
            if (!card) return;
            t.step('folded to its state', [card.open, card.querySelector('.s2p-fold-state').textContent.trim()], [false, 'Włączone']);
            card.open = true;
            const toggle = document.getElementById('morningSummaryToggle');
            t.step('the switch is on', toggle.checked, true);
            const row = toggle.closest('.s2p-switch-row') || toggle.parentElement;
            t.check('the switch\'s row is 44 px to tap (' + Math.round(row.getBoundingClientRect().height) + ')',
                row.getBoundingClientRect().height >= 44 - 0.5);
            t.step('no "Zapisz" with the script (it saves at once)', Array.from(card.querySelectorAll('button')).filter(function (b) {
                return b.checkVisibility();
            }).length, 0);
            t.check('nothing wider than the screen', document.documentElement.scrollWidth <= document.documentElement.clientWidth);

            toggle.focus();
            toggle.click();
            await t.waitFor(function () { return document.getElementById('morningSummarySaved'); }, 'the server\'s card', 4000);
            const sent = await t.stand.requests('POST /dj/dashboard/morning-summary');
            t.step('sent once, in the background, the switch off (no value), the party and the token', sent.map(function (r) {
                return [r.q.on === undefined ? null : r.q.on, r.q.partyCode, r.q['X-Requested-With'], r.q['X-CSRF-TOKEN']];
            }), [[null, 'HARN1', 'fetch', 'harness-csrf-token']]);
            t.step('the card says it: the folded line and "✓ Zapisane"', [card.querySelector('.s2p-fold-state').textContent.trim(),
                document.getElementById('morningSummarySaved').textContent.trim(), card.open], ['Wyłączone', '✓ Zapisane', true]);
            t.step('the focus on the switch (the new card\'s), off', [document.activeElement.id, document.activeElement.checked],
                ['morningSummaryToggle', false]);
            t.step('the same card element, no page load', [document.getElementById('morningSummaryCard') === card, loads], [true, []]);
        }
    });
})();
