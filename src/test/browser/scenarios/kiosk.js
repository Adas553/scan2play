// THE VENUE'S KIOSK (V56, Pro): a tablet at the bar where guests without a phone ask for a song and vote — the pages KioskWebTest
// renders through the real controllers and security (kiosk.html: the form and the most wanted; kiosk-result.html: an answer;
// kiosk-closed.html: the party closed). js/kiosk.js: the request sent once, the 👍 in the background, the list fetched again, the
// next guest by itself after a while without a touch. The waits of kiosk.js are long (a minute): a scenario moves Date.now on.
(function () {
    const TOKEN = 'Kiosk_123-xyzKiosk_123-x';
    const shows = function (el) { return !!el && el.checkVisibility(); };

    /** The page loads a form would make, caught: [path, the form's fields, the kind] — the scenario stays on the page. */
    function catchLoads() {
        const loads = [];
        navigation.addEventListener('navigate', function (e) {
            const fields = {};
            if (e.formData) e.formData.forEach(function (value, key) { if (key !== '_csrf') fields[key] = value; });
            loads.push([e.destination.url.replace(location.origin, ''), fields, e.navigationType]);
            e.preventDefault();
        });
        return loads;
    }

    /** kiosk.js's clock moved on by ms (its quiet time is Date.now() since the last touch). */
    function later(ms) {
        const real = Date.now.bind(Date);
        Date.now = function () { return real() + ms; };
    }

    function fitsTheScreen() {
        return document.documentElement.scrollWidth <= document.documentElement.clientWidth;
    }

    /** Every target of the kiosk at least this high and wide (a finger in a dim bar). */
    function smallTargets(min) {
        return Array.from(document.querySelectorAll('.s2p-kiosk button, .s2p-kiosk input, .s2p-kiosk summary')).filter(shows)
            .filter(function (el) {
                const box = el.getBoundingClientRect();
                return box.height < min || box.width < min;
            }).map(function (el) { return (el.id || el.className) + ' ' + Math.round(el.getBoundingClientRect().height); });
    }

    function layout(t, sideBySide) {
        const ask = document.querySelector('.s2p-kiosk-ask').getBoundingClientRect();
        const list = document.getElementById('kioskList').getBoundingClientRect();
        t.step(sideBySide ? 'the form and the most wanted side by side' : 'the most wanted under the form',
            sideBySide ? [Math.abs(ask.top - list.top) < 2, ask.right <= list.left] : [list.top >= ask.bottom], sideBySide ? [true, true] : [true]);
        t.step('every target ≥ 48 px', smallTargets(48), []);
        t.step('"Wyślij" and 👍 big (≥ 56 px)', [document.getElementById('submitBtn').getBoundingClientRect().height >= 56,
            Array.from(document.querySelectorAll('.s2p-kiosk-vote')).every(function (b) { return b.getBoundingClientRect().height >= 56; })],
            [true, true]);
        t.step('nothing leads out of the page', document.querySelectorAll('a[href]').length, 0);
        t.step('nothing wider than the screen', fitsTheScreen(), true);
    }

    S2P.scenario({
        name: 'kiosk-tablet-landscape',
        title: 'the kiosk on a tablet held across (1180 × 820): the form and the most wanted side by side, big targets, no link out, nothing too wide',
        page: 'kiosk',
        viewport: '1180,820',
        run: async function (t) {
            layout(t, true);
            t.check('the whole kiosk in one screen', document.documentElement.scrollHeight <= window.innerHeight + 2);
        }
    });

    S2P.scenario({
        name: 'kiosk-tablet-portrait',
        title: 'the kiosk on a tablet held upright (820 × 1180): the most wanted under the form, the same targets',
        page: 'kiosk',
        viewport: '820,1180',
        run: async function (t) { layout(t, false); }
    });

    S2P.scenario({
        name: 'kiosk-phone-390',
        title: 'the kiosk on a 390 px phone (a small tablet stand): one column, nothing wider than the screen',
        page: 'kiosk',
        viewport: '390,844',
        run: async function (t) { layout(t, false); }
    });

    S2P.scenario({
        name: 'kiosk-vote-in-background',
        title: 'a 👍 at the kiosk: sent in the background with the token, the server\'s list put in place (the 👍 given, pressed), the page stays',
        page: 'kiosk',
        viewport: '1180,820',
        run: async function (t) {
            const loads = catchLoads();
            const row = document.querySelector('#kioskList [data-song-id="8"]');
            row.querySelector('button').click();
            await t.waitFor(function () {
                const fresh = document.querySelector('#kioskList [data-song-id="8"] button');
                return fresh && fresh.classList.contains('is-given');
            }, 'the list with the 👍 given', 3000);
            const sent = await t.stand.requests('POST /k/' + TOKEN + '/vote');
            t.step('sent once, in the background, with the song', sent.map(function (r) { return [r.q.id, r.q.on, r.q['X-Requested-With'], !!r.q._csrf]; }),
                [['8', 'true', 'fetch', true]]);
            t.step('pressed, to take back', document.querySelector('#kioskList [data-song-id="8"] button').getAttribute('aria-pressed'), 'true');
            t.step('no page load', loads, []);
        }
    });

    S2P.scenario({
        name: 'kiosk-vote-lost-reloads',
        title: 'a 👍 the server refuses (the turn\'s session ran out): the kiosk\'s page loads again, never a broken list',
        page: 'kiosk',
        setup: { kioskVoteStatus: 403 },
        run: async function (t) {
            const loads = catchLoads();
            document.querySelector('#kioskList [data-song-id="8"] button').click();
            await t.waitFor(function () { return loads.length > 0; }, 'the page again', 3000);
            t.step('a reload', loads.map(function (l) { return l[2]; }), ['reload']);
            t.step('the list as it was', !!document.querySelector('#kioskList [data-song-id="8"] button.is-given'), false);
        }
    });

    S2P.scenario({
        name: 'kiosk-send-once',
        title: 'a request at the kiosk: sent as a page load with the song; the button then says "Wysyłanie…" and takes no second tap',
        page: 'kiosk',
        run: async function (t) {
            const loads = catchLoads();
            const button = document.getElementById('submitBtn');
            document.getElementById('songInput').value = 'Wilki - Baśka';
            button.click();
            await t.sleep(200);
            t.step('sent with the song', loads.map(function (l) { return [l[0], l[1].songName]; }), [['/k/' + TOKEN + '/request', 'Wilki - Baśka']]);
            t.step('the button waits', [button.disabled, button.textContent.trim()], [true, button.dataset.textSubmitting]);
        }
    });

    S2P.scenario({
        name: 'kiosk-next-guest-after-a-minute',
        title: 'a guest typed and walked away: a minute without a touch, the turn ends by itself — "Następny gość" sent (a new session)',
        page: 'kiosk',
        run: async function (t) {
            const loads = catchLoads();
            const input = document.getElementById('songInput');
            input.value = 'wil';
            input.dispatchEvent(new Event('input', { bubbles: true }));
            later(30000);
            await t.sleep(1500);
            t.step('half a minute: still their turn', loads, []);
            later(61000);
            await t.waitFor(function () { return loads.length > 0; }, 'the next guest', 3000);
            t.step('"Następny gość", sent once', loads.map(function (l) { return l[0]; }), ['/k/' + TOKEN + '/next']);
        }
    });

    S2P.scenario({
        name: 'kiosk-untouched-waits',
        title: 'an untouched kiosk is no guest\'s turn: a minute changes nothing; after ten it loads itself again (its session and token fresh)',
        page: 'kiosk',
        run: async function (t) {
            const loads = catchLoads();
            later(61000);
            await t.sleep(2500);
            t.step('a minute: nothing', loads, []);
            later(10 * 60000 + 1000);
            await t.waitFor(function () { return loads.length > 0; }, 'loaded again', 3000);
            t.step('a reload, not "Następny gość"', loads.map(function (l) { return [l[0].split('?')[0], l[2]]; })[0][1], 'reload');
        }
    });

    S2P.scenario({
        name: 'kiosk-answer-then-next-guest',
        title: 'an answer at the kiosk: the verdict and "Gotowe — następny gość" (≥ 64 px); twenty seconds without a touch and the next guest comes by itself',
        page: 'kiosk-result',
        viewport: '1180,820',
        run: async function (t) {
            const loads = catchLoads();
            const done = document.getElementById('kioskDone');
            t.step('the verdict and the way on', [shows(document.getElementById('kioskVerdict')), shows(done),
                done.getBoundingClientRect().height >= 64], [true, true, true]);
            t.step('nothing leads out of the page', document.querySelectorAll('a[href]').length, 0);
            later(21000);
            await t.waitFor(function () { return loads.length > 0; }, 'the next guest', 3000);
            t.step('"Następny gość" by itself', loads.map(function (l) { return l[0]; }), ['/k/' + TOKEN + '/next']);
        }
    });

    S2P.scenario({
        name: 'kiosk-closed-looks-again',
        title: 'the party closed: the kiosk says so and looks again every minute — the form comes as soon as the party opens',
        page: 'kiosk-closed',
        viewport: '1180,820',
        run: async function (t) {
            const loads = catchLoads();
            t.step('said, no form', [shows(document.getElementById('kioskClosed')), !!document.getElementById('requestForm')], [true, false]);
            later(61000);
            await t.waitFor(function () { return loads.length > 0; }, 'loaded again', 3000);
            t.step('a reload', loads[0][2], 'reload');
        }
    });

    S2P.scenario({
        name: 'kiosk-list-refreshes',
        title: 'the kiosk fetches its most wanted again by itself (the room\'s votes), the form untouched',
        page: 'kiosk',
        setup: { kioskList: 'kiosk-list-voted' },
        run: async function (t) {
            const form = document.getElementById('requestForm');
            document.getElementById('songInput').value = 'sanah';
            await t.waitFor(function () { return !!document.querySelector('#kioskList [data-song-id="8"] button.is-given'); },
                'the list fetched again', 13000);
            t.step('the form as it was', [document.getElementById('requestForm') === form, document.getElementById('songInput').value],
                [true, 'sanah']);
        }
    });

    S2P.scenario({
        name: 'settings-kiosk-card',
        title: '"Kiosk dla gości" on the settings page: folded to "Włączony"; the link to copy and to open; "Nowy link" asks first; both buttons page loads',
        page: 'settings-kiosk',
        viewport: '390,844',
        run: async function (t) {
            const loads = catchLoads();
            const asked = [];
            window.confirm = function (text) { asked.push(text); return true; };
            const card = document.getElementById('kioskCard');
            t.step('folded to its state', [!card.open, card.querySelector('#kioskState').textContent.trim()], [true, 'Włączony']);
            card.querySelector('summary').click();
            t.step('the link, to copy and to open', [document.getElementById('kioskLinkInput').value,
                shows(card.querySelector('[data-copy-target="kioskLinkInput"]')), document.getElementById('kioskOpen').getAttribute('target')],
                ['http://localhost:8080/k/' + TOKEN, true, '_blank']);
            document.getElementById('kioskLinkNewBtn').click();
            await t.sleep(300);
            t.step('"Nowy link": asked first, then a page load', [asked, loads.map(function (l) { return [l[0], l[1].link]; })],
                [['Utworzyć nowy link? Tablet ze starym linkiem przestanie działać.'], [['/dj/dashboard/kiosk-link', 'new']]]);
            t.step('nothing wider than the screen', fitsTheScreen(), true);
        }
    });
})();
