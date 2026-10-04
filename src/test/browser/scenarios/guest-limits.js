// The DJ sees the server's guest limits (review item 4.1, the owner's wish 2026-09-30: "Czy DJ wie jakie ma limity na imprezę?").
//
// The limits card says what the server allows (30 requests from one network per 10 min, 300 per party per 24 h — the values
// DashboardPageRenderTest renders the page with). And when the party has used its 300 — the limit that stops guest songs — a warning
// above the queue says so. The page learns it from the X-Guest-Limits header of every queue poll, the 304s included: the queue does
// not change when a limit is reached.
S2P.scenario({
    name: 'guest-limit-warnings',
    title: 'the server limits are shown, and a warning appears and goes with the X-Guest-Limits header of the queue poll',
    setup: { guestLimits: 'none' },
    run: async function (t) {
        const warning = function (flag) { return document.querySelector('#guestLimitWarnings [data-guest-limit="' + flag + '"]'); };
        const shown = function () {
            return Array.from(document.querySelectorAll('#guestLimitWarnings [data-guest-limit]')).filter(function (w) {
                return !w.classList.contains('d-none');
            }).map(function (w) { return w.getAttribute('data-guest-limit'); });
        };
        const polls = async function () { return t.stand.count('GET /dj/dashboard/updates'); };
        /** Waits until two more polls have been answered with the state the last config set. */
        const afterPolls = async function () {
            const before = await polls();
            const started = performance.now();
            while ((await polls()) < before + 2) {
                if (performance.now() - started > 10000) throw new Error('timeout: two more polls of the queue');
                await t.sleep(100);
            }
            await t.sleep(200);
        };

        const lines = Array.from(document.querySelectorAll('#serverLimitsInfo li')).map(function (li) {
            return li.textContent.replace(/\s+/g, ' ').trim();
        });
        t.step('the limits card has a line per server limit', lines.length, 2);
        t.check('one network: 30 per 10 min and how much the busiest network has used: ' + lines[0],
            lines[0].indexOf('10 min') >= 0 && lines[0].indexOf('0/30') >= 0);
        t.check('the whole party: 300 per 24 h and how much it has used: ' + lines[1], lines[1].indexOf('0/300') >= 0);
        t.step('no limit is reached: no warning', shown(), []);

        t.check('the YouTube searches have no warning any more (one warning: the party limit)',
            !warning('search-spent') && document.querySelectorAll('#guestLimitWarnings [data-guest-limit]').length === 1);

        await t.stand.config({ guestLimits: 'party-full' });
        await afterPolls();
        t.step('the party limit is reached: its warning shows (the poll answered 304)', shown(), ['party-full']);

        await t.stand.config({ guestLimits: 'search-spent,party-full' });
        await afterPolls();
        t.step('a flag the page does not know (an older server\'s) changes nothing', shown(), ['party-full']);

        await t.stand.config({ guestLimits: 'none' });
        await afterPolls();
        t.step('the limits reset: the warnings go', shown(), []);
    }
});

// The use of the two server limits follows the poll too (the owner, 2026-09-30: "liczby się odświeżają dopiero jak odświeżę
// przeglądarkę"): the X-Guest-Limits-Use header of every answer, '<busiest network>,<party>', sets the badges — their text and colour
// (grey, yellow from 80 %, red at the limit).
S2P.scenario({
    name: 'guest-limit-use-follows-the-poll',
    title: 'the badges with the use of the server limits follow the X-Guest-Limits-Use header of the queue poll, without a reload',
    setup: { guestLimitsUse: '0,0' },
    run: async function (t) {
        const badge = function (limit) { return document.querySelector('[data-limit-use="' + limit + '"]'); };
        const colour = function (element) {
            return ['text-bg-secondary', 'text-bg-warning', 'text-bg-danger'].filter(function (c) { return element.classList.contains(c); });
        };
        const state = function () {
            return [badge('network').textContent.trim(), colour(badge('network')), badge('party').textContent.trim(), colour(badge('party'))];
        };
        const polls = async function () { return t.stand.count('GET /dj/dashboard/updates'); };
        const afterPolls = async function () {
            const before = await polls();
            const started = performance.now();
            while ((await polls()) < before + 2) {
                if (performance.now() - started > 10000) throw new Error('timeout: two more polls of the queue');
                await t.sleep(100);
            }
            await t.sleep(200);
        };

        t.step('as the page was loaded', state(), ['0/30', ['text-bg-secondary'], '0/300', ['text-bg-secondary']]);

        await t.stand.config({ guestLimitsUse: '24,5' });
        await afterPolls();
        t.step('24 from one network (80 %), 5 at the party: yellow and grey', state(),
            ['24/30', ['text-bg-warning'], '5/300', ['text-bg-secondary']]);

        await t.stand.config({ guestLimitsUse: '30,300' });
        await afterPolls();
        t.step('both at the limit: red', state(), ['30/30', ['text-bg-danger'], '300/300', ['text-bg-danger']]);

        await t.stand.config({ guestLimitsUse: '2,300' });
        await afterPolls();
        t.step('the network window has ended: grey again, the party stays red', state(),
            ['2/30', ['text-bg-secondary'], '300/300', ['text-bg-danger']]);
    }
});
