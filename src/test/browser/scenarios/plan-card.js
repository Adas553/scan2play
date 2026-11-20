// "Plan wieczoru" above the queue ("Plan imprezy", V77, variant B, js/dashboard/plan-card.js): the next moment is the first not
// ticked off, with its time and the countdown; ✓ ticks a moment off (and back), this device keeps the ticks; every moment ticked
// says so; a moment before 6:00 belongs to the night after the evening's day; the queue marks a song of the plan "📋 z planu".
(function () {
    const fits = function () {
        return document.documentElement.scrollWidth <= document.documentElement.clientWidth;
    };
    const nextLabel = function () {
        return document.querySelector('#planNext [data-plan-next-label]').textContent;
    };
    const tick = function (index) {
        document.querySelector('[data-plan-moment][data-index="' + index + '"] [data-plan-tick]').click();
    };

    S2P.scenario({
        name: 'plan-card-next-and-ticks',
        title: 'the panel\'s "Plan wieczoru": the next moment with its countdown, ✓ ticks off and back, kept on this device, all ticked said',
        page: 'dashboard-plan',
        viewport: '390,844',
        run: async function (t) {
            t.check('nothing sideways', fits());
            t.check('the next moment shown', !document.getElementById('planNext').hidden);
            t.step('the next: the first moment', nextLabel(), 'Podziękowania dla rodziców');
            t.check('its time and a countdown', /^21:00 · (za \d+ min|za \d+ h \d\d min|teraz)$/
                .test(document.querySelector('#planNext [data-plan-next-when]').textContent));
            t.step('its song beside it', document.querySelector('#planNext [data-plan-next-song]').textContent, ' · Wilki - Baśka');
            t.check('a ✓ on every moment', Array.from(document.querySelectorAll('[data-plan-tick]')).every(function (b) { return !b.hidden; }));

            tick(0);
            t.step('ticked: the next is the cake', nextLabel(), 'Tort');
            const first = document.querySelector('[data-plan-moment][data-index="0"]');
            t.check('the ticked moment greyed and its ✓ pressed', first.classList.contains('s2p-plan-moment-done')
                && first.querySelector('[data-plan-tick]').getAttribute('aria-pressed') === 'true');
            t.step('kept on this device', window.localStorage.getItem('s2p-plan-done-7'), '[0]');
            tick(0);
            t.step('ticked back: the first again', nextLabel(), 'Podziękowania dla rodziców');

            [0, 1, 2, 3].forEach(tick);
            t.step('every moment ticked', document.querySelector('#planNext [data-plan-next-when]').textContent, '✓ Wszystkie momenty odhaczone');
            t.step('no moment named then', nextLabel(), '');

            // the clock's rules, from the module itself
            const plan = await import('/js/dashboard/plan-card.js');
            const afterMidnight = plan.momentAt('2027-06-14', '00:30');
            t.step('0:30 is the night after the evening\'s day', [afterMidnight.getDate(), afterMidnight.getHours(), afterMidnight.getMinutes()],
                [15, 0, 30]);
            t.step('23:00 is the evening\'s day', plan.momentAt('2027-06-14', '23:00').getDate(), 14);
            t.check('no time: no moment on the clock', plan.momentAt('2027-06-14', '') === null);
            const texts = { in: 'za {0} min', inHours: 'za {0} h {1} min', now: 'teraz' };
            t.step('the countdown\'s words', [plan.untilText(12, texts), plan.untilText(65, texts), plan.untilText(0, texts),
                plan.untilText(-5, texts)], ['za 12 min', 'za 1 h 05 min', 'teraz', 'teraz']);

            t.step('the plan\'s song marked in the queue', document.querySelectorAll('#song-list .s2p-plan-song').length, 1);
            t.check('nothing sideways after it all', fits());
        }
    });
})();
