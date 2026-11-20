// "Plan wieczoru" above the queue ("Plan imprezy", V77, variant B, js/dashboard/plan-card.js): the next moment is the first not
// ticked off, with its time and the countdown to its data-at (the server's moment in Polish time); ✓ ticks a moment off (and back),
// this device keeps the ticks by the moments' names and times — a tick of a moment gone from the plan is dropped; every moment ticked
// says so; the queue marks a song of the plan "📋 z planu".
(function () {
    const fits = function () {
        return document.documentElement.scrollWidth <= document.documentElement.clientWidth;
    };
    const nextLabel = function () {
        return document.querySelector('#planNext [data-plan-next-label]').textContent;
    };
    const moment = function (index) {
        return document.querySelectorAll('[data-plan-moment]')[index];
    };
    const tick = function (index) {
        moment(index).querySelector('[data-plan-tick]').click();
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
            const first = moment(0);
            t.check('the ticked moment greyed and its ✓ pressed', first.classList.contains('s2p-plan-moment-done')
                && first.querySelector('[data-plan-tick]').getAttribute('aria-pressed') === 'true');
            t.step('kept on this device, by the name and time', window.localStorage.getItem('s2p-plan-done-7'),
                '["Podziękowania dla rodziców|21:00"]');
            tick(0);
            t.step('ticked back: the first again', nextLabel(), 'Podziękowania dla rodziców');

            [0, 1, 2, 3].forEach(tick);
            t.step('every moment ticked', document.querySelector('#planNext [data-plan-next-when]').textContent, '✓ Wszystkie momenty odhaczone');
            t.step('no moment named then', nextLabel(), '');

            // the countdown is to the server's moment (data-at), whatever this device's zone
            [0, 1, 2, 3].forEach(tick);   // all back: the first is next again
            const minutes = Math.ceil((Number(first.dataset.at) - Date.now()) / 60000);
            const when = document.querySelector('#planNext [data-plan-next-when]').textContent;
            t.check('counted down to data-at (' + when + ')', minutes <= 0 ? /teraz$/.test(when)
                : when.indexOf(minutes < 60 ? 'za ' + minutes + ' min' : 'za ' + Math.floor(minutes / 60) + ' h') > 0);
            t.check('a moment without a time has no data-at', !moment(3).hasAttribute('data-at'));
            const plan = await import('/js/dashboard/plan-card.js');
            const texts = { in: 'za {0} min', inHours: 'za {0} h {1} min', now: 'teraz' };
            t.step('the countdown\'s words', [plan.untilText(12, texts), plan.untilText(65, texts), plan.untilText(0, texts),
                plan.untilText(-5, texts)], ['za 12 min', 'za 1 h 05 min', 'teraz', 'teraz']);

            t.step('the plan\'s song marked in the queue', document.querySelectorAll('#song-list .s2p-plan-song').length, 1);
            t.check('nothing sideways after it all', fits());
        }
    });
})();
