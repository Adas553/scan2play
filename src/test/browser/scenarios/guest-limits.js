// The DJ sees the server's guest limits (review item 4.1, the owner's wish 2026-09-30: "Czy DJ wie jakie ma limity na imprezę?").
//
// Since 2026-10-10 (the design review: three lines explained limits the DJ does not set) the panel says them only when they matter:
// the row "Limity gości" of the settings shows the guests' own limit, and from 80 % of a server limit its use in yellow ("Impreza
// 250/300", "Sieć 26/30"); above the queue a warning from 80 % of the party's limit, and when the party has used its 300 — the limit
// that stops guest songs — "wyczerpany". The page learns it from the X-Guest-Limits and X-Guest-Limits-Use headers of every queue poll,
// the 304s included: the queue does not change when a limit is reached. The page "Ustawienia imprezy" has the use in one line, what the
// limits are unfolded (30 requests from one network per 10 min, 300 per party per 24 h — the values the render tests use).
(function () {
    const polls = async function (t) { return t.stand.count('GET /dj/dashboard/updates'); };
    /** Waits until two more polls have been answered with the state the last config set. */
    const afterPolls = async function (t) {
        const before = await polls(t);
        const started = performance.now();
        while ((await polls(t)) < before + 2) {
            if (performance.now() - started > 10000) throw new Error('timeout: two more polls of the queue');
            await t.sleep(100);
        }
        await t.sleep(200);
    };
    const shows = function (element) { return !!element && !element.hidden && element.getClientRects().length > 0; };

    S2P.scenario({
        name: 'guest-limit-warnings',
        title: 'the party\'s limit reached: a warning appears and goes with the X-Guest-Limits header of the queue poll',
        setup: { guestLimits: 'none' },
        run: async function (t) {
            const warning = function (flag) { return document.querySelector('#guestLimitWarnings [data-guest-limit="' + flag + '"]'); };
            const shown = function () {
                return Array.from(document.querySelectorAll('#guestLimitWarnings [data-guest-limit]')).filter(function (w) {
                    return !w.classList.contains('d-none');
                }).map(function (w) { return w.getAttribute('data-guest-limit'); });
            };
            t.step('no limit is reached: no warning', shown(), []);
            t.check('the YouTube searches have no warning any more (one warning: the party limit)',
                !warning('search-spent') && document.querySelectorAll('#guestLimitWarnings [data-guest-limit]').length === 1);

            await t.stand.config({ guestLimits: 'party-full' });
            await afterPolls(t);
            t.step('the party limit is reached: its warning shows (the poll answered 304)', shown(), ['party-full']);
            t.step('…in a few words', warning('party-full').textContent.trim(), '⚠ Limit 300 próśb na dobę wyczerpany — goście nie mogą teraz wysyłać.');

            await t.stand.config({ guestLimits: 'search-spent,party-full' });
            await afterPolls(t);
            t.step('a flag the page does not know (an older server\'s) changes nothing', shown(), ['party-full']);

            await t.stand.config({ guestLimits: 'none' });
            await afterPolls(t);
            t.step('the limits reset: the warnings go', shown(), []);
        }
    });

    // The use of the two server limits follows the poll (the owner, 2026-09-30: "liczby się odświeżają dopiero jak odświeżę
    // przeglądarkę"): the X-Guest-Limits-Use header of every answer, '<busiest network>,<party>', shows the warning near the party's
    // limit and the state of the row "Limity gości".
    S2P.scenario({
        name: 'guest-limit-near-follows-the-poll',
        title: 'near a server limit (80 %): the warning above the queue and the row "Limity gości" follow X-Guest-Limits-Use, without a reload',
        setup: { guestLimitsUse: '0,0' },
        run: async function (t) {
            const near = document.querySelector('#guestLimitWarnings [data-limit-near="party"]');
            const row = document.querySelector('[data-settings-row="limits"]');
            document.getElementById('settingsToggle').click();   // the row is in the settings
            const state = function () {
                const visible = Array.from(row.querySelectorAll('.s2p-row-state > span')).filter(shows);
                return [shows(near) ? near.textContent.trim() : null, visible.map(function (s) { return s.textContent.trim(); })];
            };
            t.step('as the page was loaded: no warning, the row says the guests\' own limit', state(), [null, ['2 na gościa · 3 min przerwy']]);

            await t.stand.config({ guestLimitsUse: '0,250' });
            await afterPolls(t);
            t.step('250 of the party\'s 300 (80 %): the warning says how much, the row too, in yellow', state(),
                ['Impreza: 250 z 300 próśb na dobę', ['Impreza 250/300']]);

            await t.stand.config({ guestLimitsUse: '26,10' });
            await afterPolls(t);
            t.step('26 of a network\'s 30, the party far from its limit: no warning, the row says the network', state(),
                [null, ['Sieć 26/30']]);

            await t.stand.config({ guestLimitsUse: '26,300' });
            await afterPolls(t);
            t.step('the party at its limit: "wyczerpany" takes over from the warning (X-Guest-Limits), the row says the party first', state(),
                [null, ['Impreza 300/300']]);

            await t.stand.config({ guestLimitsUse: '2,5' });
            await afterPolls(t);
            t.step('both windows over: back to the guests\' own limit', state(), [null, ['2 na gościa · 3 min przerwy']]);
        }
    });

    S2P.scenario({
        name: 'server-limits-on-the-settings-page',
        title: 'the page "Ustawienia imprezy": the use of the server limits in one line, grey / yellow / red; what they are unfolded',
        page: 'settings',
        viewport: '390,844',
        run: async function (t) {
            const box = document.getElementById('serverLimitsInfo');
            const summary = box.querySelector('summary');
            t.step('one line, with each limit\'s window (the owner: "30 na 10 min", "300 na 24 h")', summary.textContent.replace(/\s+/g, ' ').trim(),
                'Limity serwera: sieć 0/30 na 10 min impreza 0/300 na 24 h');
            t.step('the badges grey (nothing used)', Array.from(box.querySelectorAll('[data-limit-use]')).map(function (b) {
                return b.classList.contains('text-bg-secondary');
            }), [true, true]);
            const help = box.querySelector('p');
            t.step('what they are: folded', help.checkVisibility(), false);
            t.check('the line is a thumb\'s target (44 px)', summary.getBoundingClientRect().height >= 44 - 0.5);
            summary.click();
            t.step('…unfolded on a tap', [help.checkVisibility(), help.textContent.trim().indexOf('Jedna sieć to np. Wi-Fi lokalu')],
                [true, 0]);
        }
    });
})();
