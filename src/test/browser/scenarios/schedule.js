// "GODZINY IMPREZY" (V55): the party opens and closes by itself. The page "Ustawienia imprezy" has the card (the day and the time of
// each, quick choices that fill them from the server's today and tomorrow, "Zapisz" and "✕" as page loads); the panel has a line under
// the heading per planned moment ("⏰ Zamknie się jutro o 3:00 ✕"), hidden by the poll (X-Party-Schedule) once the moment is no longer
// planned; the guests of a closed party read "Prośby od 20:00" on their page and the TV. Rendered on Friday 2026-10-30 at noon: a
// closed party that opens today at 20:00 and closes tomorrow at 3:00 (open:1793386800, close:1793412000).
(function () {
    const PLAN = 'open:1793386800,close:1793412000';

    const shows = function (el) { return !!el && el.checkVisibility(); };

    /** The page load a form would make, caught: [path, the form's fields] — the scenario stays on the page. */
    function catchLoads() {
        const loads = [];
        navigation.addEventListener('navigate', function (e) {
            const fields = {};
            if (e.formData) e.formData.forEach(function (value, key) { fields[key] = value; });
            loads.push([e.destination.url.replace(location.origin, ''), fields]);
            e.preventDefault();
        });
        return loads;
    }

    function unfold() {
        const card = document.getElementById('partyHoursCard');
        if (card && !card.open) card.querySelector('summary').click();
        return card;
    }

    function value(id) {
        const field = document.getElementById(id);
        return field ? field.value : null;
    }

    function quick(kind, day) {
        return document.querySelector('[data-hours-fill="' + kind + '"][data-day="' + day + '"]');
    }

    /** Elements smaller than a finger (44 px) either way. */
    function smallTargets(selector) {
        return Array.from(document.querySelectorAll(selector)).filter(shows).filter(function (el) {
            const box = el.getBoundingClientRect();
            return box.height < 44 || box.width < 44;
        }).map(function (el) { return (el.id || el.className) + ' ' + Math.round(el.getBoundingClientRect().width) + '×'
            + Math.round(el.getBoundingClientRect().height); });
    }

    function fitsTheScreen() {
        return document.documentElement.scrollWidth <= document.documentElement.clientWidth;
    }

    S2P.scenario({
        name: 'schedule-quick-choices',
        title: '"Godziny imprezy": the first card, folded to the next moment; "jutro 3:00" / "dziś 20:00" fill the day and the time from the server\'s today; "Zapisz" is a page load with the four fields',
        page: 'settings-hours',
        run: async function (t) {
            const loads = catchLoads();
            const card = document.getElementById('partyHoursCard');
            t.step('the first card, folded to the next moment', [card === document.querySelector('#settingsCards > details'),
                !!card && !card.open, card && card.querySelector('#partyHoursState').textContent.trim()],
                [true, true, 'Otworzy się dziś o 20:00']);
            unfold();
            document.getElementById('openDate').value = '';
            document.getElementById('openTime').value = '';
            document.getElementById('closeDate').value = '';
            document.getElementById('closeTime').value = '';

            quick('close', 'tomorrow').click();
            t.step('"jutro 3:00" fills the close', [value('closeDate'), value('closeTime')], ['2026-10-31', '03:00']);
            quick('open', 'today').click();
            t.step('"dziś 20:00" fills the open', [value('openDate'), value('openTime')], ['2026-10-30', '20:00']);
            t.step('the other fields untouched', [value('closeDate'), value('closeTime')], ['2026-10-31', '03:00']);
            quick('close', 'today').click();
            t.step('"dziś 23:00"', [value('closeDate'), value('closeTime')], ['2026-10-30', '23:00']);
            t.check('a quick choice sends nothing', loads.length === 0);

            document.getElementById('partyHoursSave').click();
            await t.sleep(300);
            t.step('"Zapisz": a page load of the form with the party and the four fields', loads, [['/dj/dashboard/schedule',
                { partyCode: 'HARN1', openDate: '2026-10-30', openTime: '20:00', closeDate: '2026-10-30', closeTime: '23:00' }]]);
            t.step('not sent in the background', await t.stand.count('POST /dj/dashboard/schedule'), 0);
        }
    });

    S2P.scenario({
        name: 'schedule-card-cancel',
        title: '"Godziny imprezy": "✕" beside a planned moment is a page load of its own form — that moment only, none of the typed fields',
        page: 'settings-hours',
        run: async function (t) {
            const loads = catchLoads();
            unfold();
            const planned = document.getElementById('closePlanned');
            t.step('the planned moments, the open first', [shows(document.getElementById('openPlanned')), shows(planned),
                document.getElementById('openPlanned').compareDocumentPosition(planned) & Node.DOCUMENT_POSITION_FOLLOWING],
                [true, true, Node.DOCUMENT_POSITION_FOLLOWING]);
            document.getElementById('openTime').value = '21:00';
            planned.querySelector('button').click();
            await t.sleep(300);
            t.step('the close cancelled alone', loads, [['/dj/dashboard/schedule', { partyCode: 'HARN1', cancel: 'close' }]]);
        }
    });

    /** The card on a phone: the day and the time side by side, every field and button a finger's target, nothing too wide. */
    async function cardOnAPhone(t) {
        const summary = document.querySelector('#partyHoursCard > summary');
        const state = summary.querySelector('.s2p-fold-state').getBoundingClientRect();
        t.step('folded: the next moment beside the heading, inside the line', [
            state.left >= summary.querySelector('.s2p-fold-name').getBoundingClientRect().right,
            state.right <= summary.getBoundingClientRect().right], [true, true]);
        unfold();
        const day = document.getElementById('closeDate').getBoundingClientRect();
        const time = document.getElementById('closeTime').getBoundingClientRect();
        t.step('side by side', [Math.round(day.top) === Math.round(time.top), day.right <= time.left], [true, true]);
        t.check('the day wide enough to read (≥ 130 px)', day.width >= 130);
        t.step('every target ≥ 44 px', smallTargets('#partyHoursCard input, #partyHoursCard .s2p-hours-cancel'), []);
        t.step('the buttons ≥ 44 px tall', Array.from(document.querySelectorAll('#partyHoursCard .btn')).filter(shows)
            .filter(function (b) { return b.getBoundingClientRect().height < 44; }).length, 0);
        t.step('the quick choices each on one line', Array.from(document.querySelectorAll('.s2p-hours-quick')).filter(function (b) {
            return b.getBoundingClientRect().height > 60;
        }).length, 0);
        t.step('nothing wider than the screen', fitsTheScreen(), true);
    }

    S2P.scenario({
        name: 'schedule-card-phone-390',
        title: '"Godziny imprezy" on a 390 px phone: the day and the time side by side, every field and button a finger\'s target, nothing wider than the screen',
        page: 'settings-hours',
        viewport: '390,844',
        run: cardOnAPhone
    });

    S2P.scenario({
        name: 'schedule-card-phone-360',
        title: '"Godziny imprezy" on a 360 px phone: the same',
        page: 'settings-hours',
        viewport: '360,740',
        run: cardOnAPhone
    });

    S2P.scenario({
        name: 'schedule-card-refused-phone-360',
        title: 'a plan refused, on a 360 px phone: the card open, why in its words, the fields as typed, nothing wider than the screen',
        page: 'settings-hours-error',
        viewport: '360,740',
        run: async function (t) {
            const card = document.getElementById('partyHoursCard');
            const why = document.getElementById('partyHoursError');
            t.step('open, saying why', [!!card && card.open, shows(why), why && why.textContent.trim()],
                [true, true, 'Ta pora już minęła: wybierz późniejszą.']);
            t.step('the fields as typed', [value('openDate'), value('openTime'), value('closeDate')], ['2026-10-29', '20:00', '']);
            t.step('nothing wider than the screen', fitsTheScreen(), true);
        }
    });

    S2P.scenario({
        name: 'schedule-panel-line-follows-the-poll',
        title: 'the panel\'s line of the plan: a moment the poll no longer names is hidden (applied, cancelled elsewhere, made pointless) — then the whole line',
        page: 'dashboard-schedule',
        setup: { partyActive: false, partySchedule: PLAN },
        run: async function (t) {
            const open = document.querySelector('[data-schedule-key="open:1793386800"]');
            const close = document.querySelector('[data-schedule-key="close:1793412000"]');
            t.step('both shown, the open first, under the heading', [shows(open), shows(close),
                document.getElementById('partySchedule').compareDocumentPosition(document.getElementById('djTabBar'))
                & Node.DOCUMENT_POSITION_FOLLOWING], [true, true, Node.DOCUMENT_POSITION_FOLLOWING]);
            await t.sleep(3500);
            t.step('a poll naming both changes nothing', [shows(open), shows(close)], [true, true]);

            await t.stand.config({ partyActive: true, partySchedule: 'close:1793412000' });
            await t.waitFor(function () { return !shows(open); }, 'the open applied: its line hidden', 8000);
            t.step('the close stays', shows(close), true);
            await t.stand.config({ partySchedule: '' });
            await t.waitFor(function () { return !shows(document.getElementById('partySchedule')); }, 'nothing planned: the line hidden', 8000);
        }
    });

    S2P.scenario({
        name: 'schedule-panel-cancel',
        title: 'the panel\'s "✕": the moment cancelled in the background, its line gone at once (the queue polled now), the other kept, the page stays',
        page: 'dashboard-schedule',
        setup: { partyActive: false, partySchedule: PLAN },
        run: async function (t) {
            const loads = catchLoads();
            await t.sleep(3500);   // past the first poll: the next one is 3 s away
            const close = document.querySelector('[data-schedule-key="close:1793412000"]');
            const sentAt = Date.now();
            close.querySelector('button').click();
            await t.waitFor(function () { return !shows(close); }, 'the line hidden', 2500);
            t.check('sooner than the next regular poll', Date.now() - sentAt < 2000);
            const sent = await t.stand.requests('POST /dj/dashboard/schedule/cancel');
            t.step('sent once, with the party and the moment', sent.map(function (r) { return [r.q.partyCode, r.q.event]; }), [['HARN1', 'close']]);
            t.step('the open stays', shows(document.querySelector('[data-schedule-key="open:1793386800"]')), true);
            t.step('no page load', loads, []);
        }
    });

    S2P.scenario({
        name: 'schedule-panel-end-party',
        title: '"Wznów imprezę" against the plan: the panel asks the server at once, and the open it made pointless leaves the line',
        page: 'dashboard-schedule',
        setup: { partyActive: false, partySchedule: PLAN },
        run: async function (t) {
            await t.sleep(3500);
            // the server after "Wznów": open, the planned open made pointless
            await t.stand.config({ partyActive: true, partySchedule: 'close:1793412000' });
            const open = document.querySelector('[data-schedule-key="open:1793386800"]');
            const sentAt = Date.now();
            document.querySelector('#party-closed-banner form button').click();
            await t.waitFor(function () { return !shows(open); }, 'the open\'s line hidden', 2500);
            t.check('sooner than the next regular poll', Date.now() - sentAt < 2000);
        }
    });

    /** The panel's line on a phone: each moment on its line, its words whole, "✕" a finger's target, nothing too wide. */
    async function panelLineOnAPhone(t) {
        const items = Array.from(document.querySelectorAll('#partySchedule .s2p-schedule-item'));
        t.step('two moments', items.filter(shows).length, 2);
        t.step('"✕" ≥ 44 px', smallTargets('#partySchedule .s2p-schedule-cancel'), []);
        t.step('the words on one line each', items.filter(function (item) {
            const text = item.querySelector('.s2p-schedule-text');
            return text.getBoundingClientRect().height > parseFloat(getComputedStyle(text).fontSize) * 1.6;
        }).length, 0);
        t.step('nothing wider than the screen', fitsTheScreen(), true);
    }

    S2P.scenario({
        name: 'schedule-panel-phone-390',
        title: 'the panel\'s line of the plan on a 390 px phone: each moment on its line, its words whole, "✕" a finger\'s target, nothing wider than the screen',
        page: 'dashboard-schedule',
        viewport: '390,844',
        setup: { partyActive: false, partySchedule: PLAN },
        run: panelLineOnAPhone
    });

    S2P.scenario({
        name: 'schedule-panel-phone-360',
        title: 'the panel\'s line of the plan on a 360 px phone: the same',
        page: 'dashboard-schedule',
        viewport: '360,740',
        setup: { partyActive: false, partySchedule: PLAN },
        run: panelLineOnAPhone
    });

    /** A closed party's page that says when it opens, on a phone: when, the way to check again, nothing too wide. */
    function opensOnAPhone(when) {
        return async function (t) {
            const title = document.getElementById('partyEndedTitle');
            t.step('when', title && title.textContent.trim(), when);
            t.step('the heading and the button seen', [shows(title), shows(document.getElementById('checkAgainBtn'))], [true, true]);
            t.step('nothing wider than the screen', fitsTheScreen(), true);
        };
    }

    S2P.scenario({
        name: 'schedule-guest-opens-phone-360',
        title: 'a closed party that opens today at 20:00, on a 360 px phone: the guest reads "Prośby od 20:00", the way to check again, nothing wider than the screen',
        page: 'party-ended-opens',
        viewport: '360,740',
        run: opensOnAPhone('Prośby od 20:00')
    });

    S2P.scenario({
        name: 'schedule-guest-opens-phone-390-en',
        title: 'the same in English on Saturday, on a 390 px phone: "Requests from Saturday, 20:00"',
        page: 'party-ended-opens-en',
        viewport: '390,844',
        run: opensOnAPhone('Requests from Saturday, 20:00')
    });

    S2P.scenario({
        name: 'schedule-tv-says-when',
        title: 'the screen for a TV of a closed party that opens later: its list says "Prośby od 20:00" / "Requests from 20:00"',
        page: 'tv',
        viewport: '1920,1080',
        setup: { tvList: 'tv-list-opens' },
        run: async function (t) {
            await t.waitFor(function () { return !!document.getElementById('tvEnded'); }, 'the list fetched again', 13000);
            t.step('when, in both languages', document.getElementById('tvEnded').textContent.replace(/\s+/g, ' ').trim(),
                'Prośby od 20:00 Requests from 20:00');
        }
    });
    S2P.scenario({
        name: 'schedule-card-follows-end-party',
        title: '"Godziny imprezy" with nothing planned: "Zakończ imprezę" pressed on the settings page — the card says the party is closed now, "Wznów" open again; no page load',
        page: 'settings',
        run: async function (t) {
            const loads = catchLoads();
            window.confirm = function () { return true; };
            unfold();
            const now = document.getElementById('partyHoursNow');
            t.step('at first', now.textContent.trim(), 'Teraz impreza jest otwarta.');
            document.querySelector('#end-party-form button').click();
            await t.waitFor(function () { return now.textContent.trim() === 'Teraz impreza jest zamknięta.'; }, 'closed now', 3000);
            document.querySelector('#party-closed-banner form button').click();
            await t.waitFor(function () { return now.textContent.trim() === 'Teraz impreza jest otwarta.'; }, 'open again', 3000);
            t.step('no page load', loads, []);
        }
    });

    S2P.scenario({
        name: 'schedule-card-reloads-after-resume',
        title: '"Godziny imprezy" with a plan: "Wznów imprezę" pressed on the settings page drops the planned open on the server — the page loads again, so the card shows what is left',
        page: 'settings-hours',
        run: async function (t) {
            const reloads = [];
            navigation.addEventListener('navigate', function (e) { reloads.push(e.navigationType); e.preventDefault(); });
            unfold();
            document.querySelector('#party-closed-banner form button').click();
            await t.waitFor(function () { return reloads.length > 0; }, 'the page loaded again', 3000);
            t.step('a reload', reloads, ['reload']);
            t.step('the words already follow', document.getElementById('partyHoursNow').textContent.trim(), 'Teraz impreza jest otwarta.');
        }
    });

})();
