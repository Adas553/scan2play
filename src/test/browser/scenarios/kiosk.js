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

    /**
     * "Następny gość" as kiosk.js sends it: in the background (a page load would leave the tablet on the browser's error page when
     * the Wi-Fi is down), once, with the form's token — then the kiosk's own page in place of this one.
     */
    async function nextSent(t, loads) {
        const sent = await t.stand.requests('POST /k/' + TOKEN + '/next');
        t.step('"Następny gość" sent once, in the background, with the token', sent.map(function (r) { return !!r.q._csrf; }), [true]);
        t.step('then the kiosk\'s page, in place of this one', loads.map(function (l) { return [l[0], l[2]]; }), [['/k/' + TOKEN, 'replace']]);
    }

    /** Waits (real time) until the stand-in has logged n requests of `match`. */
    async function requested(t, match, n, ms) {
        const started = performance.now();
        while ((await t.stand.count(match)) < n) {
            if (performance.now() - started > ms) {
                t.step('TIMEOUT waiting for ' + n + ' × ' + match, false, true);
                throw new Error('timeout: ' + match);
            }
            await t.sleep(100);
        }
    }

    /** A finger on the screen (a tap, a scroll begun). */
    function touch() {
        document.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
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
        title: 'a 👍 the server refuses (the turn\'s session ran out): the kiosk\'s own page again (its address, not a reload), never a broken list',
        page: 'kiosk',
        setup: { kioskVoteStatus: 403 },
        run: async function (t) {
            const loads = catchLoads();
            document.querySelector('#kioskList [data-song-id="8"] button').click();
            await t.waitFor(function () { return loads.length > 0; }, 'the page again', 3000);
            t.step('the kiosk\'s page, in place of this one', loads.map(function (l) { return [l[0], l[2]]; }), [['/k/' + TOKEN, 'replace']]);
            t.step('the list as it was', !!document.querySelector('#kioskList [data-song-id="8"] button.is-given'), false);
        }
    });

    S2P.scenario({
        name: 'kiosk-answer-vote-lost',
        title: 'a 👍 lost on the answer\'s page (an answer to a POST): the kiosk\'s own page — a reload would send the request again (a second AI call, or the browser\'s "Confirm resubmission" on the tablet)',
        page: 'kiosk-result',
        setup: { kioskVoteStatus: 403 },
        run: async function (t) {
            const loads = catchLoads();
            document.querySelector('#kioskList [data-song-id="8"] button').click();
            await t.waitFor(function () { return loads.length > 0; }, 'the page again', 3000);
            t.step('the kiosk\'s page by its address, no reload', loads.map(function (l) { return [l[0], l[2]]; }), [['/k/' + TOKEN, 'replace']]);
        }
    });

    S2P.scenario({
        name: 'kiosk-refused-turn-ends',
        title: 'a request refused (a mood: back with the words and the dedication) and the guest walked away: a minute without a touch, "Następny gość" by itself — the next guest never finds them',
        page: 'kiosk-refused',
        run: async function (t) {
            const loads = catchLoads();
            t.step('the words and the dedication back', [document.getElementById('songInput').value,
                document.getElementById('dedicationInput').value], ['coś skocznego', 'dla Kasi']);
            later(30000);
            await t.sleep(1500);
            t.step('half a minute: still their turn', loads, []);
            later(61000);
            await t.waitFor(function () { return loads.length > 0; }, 'the next guest', 3000);
            await nextSent(t, loads);
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
            await nextSent(t, loads);
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
            t.step('the kiosk\'s page asked for first, then loaded by its address — not "Następny gość"',
                [await t.stand.count('GET /k/' + TOKEN), await t.stand.count('POST /k/' + TOKEN + '/next'),
                    loads.map(function (l) { return [l[0], l[2]]; })], [1, 0, [['/k/' + TOKEN, 'replace']]]);
        }
    });

    S2P.scenario({
        name: 'kiosk-reload-waits-for-the-server',
        title: 'the kiosk loads itself again only when its page answers: the Wi-Fi down (or the server failing) at that moment leaves it on its page, never on the browser\'s error page — it asks again soon',
        page: 'kiosk',
        setup: { kioskPageStatus: 502 },
        run: async function (t) {
            const loads = catchLoads();
            later(10 * 60000 + 1000);
            await requested(t, 'GET /k/' + TOKEN, 1, 3000);
            await t.sleep(1500);
            t.step('the page did not answer: the kiosk stays', loads, []);
            await t.stand.config({ kioskPageStatus: null });
            later(31000);
            await t.waitFor(function () { return loads.length > 0; }, 'loaded once the page answers', 3000);
            t.step('asked again, then loaded by its address', [await t.stand.count('GET /k/' + TOKEN),
                loads.map(function (l) { return [l[0], l[2]]; })], [2, [['/k/' + TOKEN, 'replace']]]);
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
            await nextSent(t, loads);
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
            t.step('its page again, by its address', loads.map(function (l) { return [l[0], l[2]]; }), [['/k/' + TOKEN, 'replace']]);
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
        title: '"Kiosk dla gości" on the settings page, on a phone: folded to "Włączony"; the link to copy and to open; "Nowy link" asks first, is sent in the background, the server\'s card with the new link takes the old one\'s place — "✓" on the button, the screen does not jump',
        page: 'settings-kiosk',
        viewport: '390,844',
        run: async function (t) {
            const loads = catchLoads();
            const asked = [];
            window.confirm = function (text) { asked.push(text); return true; };
            const card = document.getElementById('kioskCard');
            t.step('folded to its state', [!card.open, card.querySelector('#kioskState').textContent.trim()], [true, 'Włączony']);
            card.querySelector('summary').click();
            const oldLink = document.getElementById('kioskLinkInput').value;
            t.step('the link, to copy and to open', [oldLink, shows(card.querySelector('[data-copy-target="kioskLinkInput"]')),
                document.getElementById('kioskOpen').getAttribute('target')], ['http://localhost:8080/k/' + TOKEN, true, '_blank']);

            // scrolled so the card's buttons are in the middle of the screen, as a DJ who reached them has it
            const button = document.getElementById('kioskLinkNewBtn');
            window.scrollTo({ top: window.scrollY + button.getBoundingClientRect().top - window.innerHeight / 2, behavior: 'instant' });
            const before = window.scrollY;
            const cardTop = card.getBoundingClientRect().top;
            button.click();
            await t.waitFor(function () { return document.getElementById('kioskLinkInput').value !== oldLink; }, 'the new link in place', 3000);
            t.step('asked first', asked, ['Utworzyć nowy link? Tablet ze starym linkiem przestanie działać.']);
            const sent = await t.stand.requests('POST /dj/dashboard/kiosk-link');
            t.step('sent once, in the background', sent.map(function (r) { return [r.q.partyCode, r.q.link, r.q['X-Requested-With']]; }),
                [['HARN1', 'new', 'fetch']]);
            const fresh = document.getElementById('kioskLinkNewBtn');
            t.step('"✓" on the button, as every card; the card open', [fresh.textContent.trim(), fresh.classList.contains('btn-success'),
                document.getElementById('kioskCard').open], ['✓', true, true]);
            t.step('the page where it was (± 2 px), the card too', [Math.abs(window.scrollY - before) <= 2,
                Math.abs(document.getElementById('kioskCard').getBoundingClientRect().top - cardTop) <= 2], [true, true]);
            t.check('scrolled down first (a place to keep)', before > 100);
            t.step('no page load', loads, []);
            t.step('nothing wider than the screen', fitsTheScreen(), true);
        }
    });
    S2P.scenario({
        name: 'kiosk-next-tapped',
        title: '"Następny gość" tapped: sent in the background, then the kiosk\'s page — never a form\'s page load',
        page: 'kiosk',
        run: async function (t) {
            const loads = catchLoads();
            document.getElementById('kioskNext').click();
            await t.waitFor(function () { return loads.length > 0; }, 'the kiosk\'s page', 3000);
            await nextSent(t, loads);
        }
    });

    S2P.scenario({
        name: 'kiosk-next-lost-stays',
        title: '"Następny gość" lost (the Wi-Fi down): the kiosk stays on its page, never the browser\'s error page, and tries again by itself',
        page: 'kiosk-result',
        setup: { postStatus: { ['/k/' + TOKEN + '/next']: 502 } },
        run: async function (t) {
            const loads = catchLoads();
            later(21000);
            await requested(t, 'POST /k/' + TOKEN + '/next', 1, 3000);
            await t.sleep(1500);
            t.step('no page load: the kiosk stays', loads, []);
            t.step('"Gotowe" usable again', document.getElementById('kioskDone').disabled, false);
            later(45000);
            await requested(t, 'POST /k/' + TOKEN + '/next', 2, 3000);
        }
    });

    S2P.scenario({
        name: 'kiosk-list-waits-for-the-finger',
        title: 'the list is not put in place under a finger (its rows move with the votes: a 👍 would land on another song); a few seconds after the last touch it is',
        page: 'kiosk',
        setup: { kioskList: 'kiosk-list-voted' },
        run: async function (t) {
            const list = document.getElementById('kioskList');
            for (let i = 0; i < 12; i++) {
                touch();
                await t.sleep(1000);
            }
            t.step('twelve seconds of touches: the list as it was', [document.getElementById('kioskList') === list,
                !!document.querySelector('#kioskList [data-song-id="8"] button.is-given')], [true, false]);
            later(6000);
            await t.waitFor(function () { return !!document.querySelector('#kioskList [data-song-id="8"] button.is-given'); },
                'the list fetched again', 13000);
        }
    });

    S2P.scenario({
        name: 'kiosk-vote-note-stays',
        title: 'a 👍 that did not count: its note stays to be read (the next fetch of the list does not take it away at once) and is read out',
        page: 'kiosk',
        setup: { kioskVoteAnswer: 'kiosk-list-note' },
        run: async function (t) {
            document.querySelector('#kioskList [data-song-id="8"] button').click();
            await t.waitFor(function () { return !!document.getElementById('kioskVoteNote'); }, 'the note', 3000);
            const said = document.getElementById('kioskVoteNote').textContent.trim();
            t.step('read out by the page\'s own live region', document.getElementById('kioskAnnounce').textContent.trim(), said);
            await t.sleep(11000);
            t.step('a fetch of the list later: the note still there', !!document.getElementById('kioskVoteNote'), true);
            later(16000);
            await t.waitFor(function () { return !document.getElementById('kioskVoteNote'); }, 'the list again, without the note', 13000);
        }
    });

    S2P.scenario({
        name: 'kiosk-list-stale-answer-dropped',
        title: 'a fetch of the list asked before a 👍 and answered after it: dropped — the 👍 given stays shown',
        page: 'kiosk',
        setup: { delays: { ['/k/' + TOKEN + '/list']: 3 } },
        run: async function (t) {
            await requested(t, 'GET /k/' + TOKEN + '/list', 1, 13000);
            document.querySelector('#kioskList [data-song-id="8"] button').click();
            await t.waitFor(function () { return !!document.querySelector('#kioskList [data-song-id="8"] button.is-given'); }, 'the 👍', 2000);
            await t.sleep(3500);
            t.step('the 👍 still shown', !!document.querySelector('#kioskList [data-song-id="8"] button.is-given'), true);
        }
    });

    S2P.scenario({
        name: 'kiosk-list-keeps-the-focus',
        title: 'the list fetched again keeps the keyboard\'s (a screen reader\'s) focus on the same song\'s 👍, never the page\'s top',
        page: 'kiosk',
        setup: { kioskList: 'kiosk-list-voted' },
        run: async function (t) {
            document.querySelector('#kioskList [data-song-id="7"] button').focus();
            await t.waitFor(function () { return !!document.querySelector('#kioskList [data-song-id="8"] button.is-given'); },
                'the list fetched again', 13000);
            const focused = document.activeElement;
            t.step('the focus on the new list\'s 👍 of the same song', [document.getElementById('kioskList').contains(focused),
                focused.closest('[data-song-id]') ? focused.closest('[data-song-id]').dataset.songId : null], [true, '7']);
        }
    });

    S2P.scenario({
        name: 'kiosk-closed-meanwhile',
        title: 'the party closed while the form is on the screen: the list fetched again says so, and the kiosk goes to its page (when it opens)',
        page: 'kiosk',
        setup: { kioskList: 'kiosk-list-closed' },
        run: async function (t) {
            const loads = catchLoads();
            await t.waitFor(function () { return loads.length > 0; }, 'the kiosk\'s page', 13000);
            t.step('the kiosk\'s page, in place of this one', loads.map(function (l) { return [l[0], l[2]]; }), [['/k/' + TOKEN, 'replace']]);
        }
    });

    S2P.scenario({
        name: 'kiosk-next-hangs',
        title: '"Następny gość" whose answer never comes (a Wi-Fi that drops packets): given up after a while — the button usable again, and the turn ends again by itself, never a kiosk stuck with the last guest\'s words',
        page: 'kiosk',
        setup: { delays: { ['/k/' + TOKEN + '/next']: 30 } },
        run: async function (t) {
            const loads = catchLoads();
            const input = document.getElementById('songInput');
            input.value = 'wil';
            input.dispatchEvent(new Event('input', { bubbles: true }));
            document.getElementById('kioskNext').click();
            await requested(t, 'POST /k/' + TOKEN + '/next', 1, 3000);
            await t.waitFor(function () { return !document.getElementById('kioskNext').disabled; }, 'given up, the button usable', 13000);
            t.step('no page load', loads, []);
            later(61000);
            await requested(t, 'POST /k/' + TOKEN + '/next', 2, 3000);
        }
    });

    S2P.scenario({
        name: 'kiosk-vote-hangs',
        title: 'a 👍 whose answer never comes: given up after a while — the kiosk\'s page again, the list never frozen with its button off',
        page: 'kiosk',
        setup: { delays: { ['/k/' + TOKEN + '/vote']: 30 } },
        run: async function (t) {
            const loads = catchLoads();
            document.querySelector('#kioskList [data-song-id="8"] button').click();
            await t.waitFor(function () { return loads.length > 0; }, 'the kiosk\'s page again', 13000);
            t.step('the kiosk\'s page, in place of this one', loads.map(function (l) { return [l[0], l[2]]; }), [['/k/' + TOKEN, 'replace']]);
        }
    });

    S2P.scenario({
        name: 'kiosk-no-vote-after-next',
        title: 'a 👍 tapped while "Następny gość" is on its way: not sent — it would land in the next guest\'s turn',
        page: 'kiosk',
        setup: { delays: { ['/k/' + TOKEN + '/next']: 3 } },
        run: async function (t) {
            catchLoads();
            document.getElementById('kioskNext').click();
            await requested(t, 'POST /k/' + TOKEN + '/next', 1, 3000);
            document.querySelector('#kioskList [data-song-id="8"] button').click();
            await t.sleep(1000);
            t.step('no 👍 sent', await t.stand.count('POST /k/' + TOKEN + '/vote'), 0);
        }
    });

    /** The songs' own scroller and how far down it is. */
    function songs() { return document.querySelector('#kioskList .s2p-kiosk-songs'); }

    S2P.scenario({
        name: 'kiosk-list-keeps-the-scroll',
        title: 'the list scrolled down in its card stays where it is when it is fetched again, and after a 👍 on a song down there — never back at the top, the 👍 out of sight',
        page: 'kiosk-full',
        viewport: '1180,820',
        setup: { kioskList: 'kiosk-list-full', kioskVoteAnswer: 'kiosk-list-full-voted' },
        run: async function (t) {
            songs().scrollTop = songs().scrollHeight;
            const down = songs().scrollTop;
            t.check('scrolled down (a place to keep)', down > 50);
            const old = document.getElementById('kioskList');
            await t.waitFor(function () { return document.getElementById('kioskList') !== old; }, 'the list fetched again', 13000);
            t.step('fetched again: the same place', Math.abs(songs().scrollTop - down) <= 2, true);
            document.querySelector('#kioskList [data-song-id="29"] button').click();
            await t.waitFor(function () { return !!document.querySelector('#kioskList [data-song-id="29"] button.is-given'); }, 'the 👍', 3000);
            const button = document.querySelector('#kioskList [data-song-id="29"] button').getBoundingClientRect();
            const card = songs().getBoundingClientRect();
            t.step('after the 👍: the same place, its button in sight', [Math.abs(songs().scrollTop - down) <= 2,
                button.top >= card.top - 1 && button.bottom <= card.bottom + 1], [true, true]);
        }
    });

    S2P.scenario({
        name: 'kiosk-list-says-more',
        title: 'a list longer than its card shows there is more below (it fades at the bottom — a tablet hides the scrollbar); scrolled to its end, it does not',
        page: 'kiosk-full',
        viewport: '1180,820',
        run: async function (t) {
            const fades = function () { return getComputedStyle(songs()).webkitMaskImage !== 'none'; };
            t.step('more below: it fades', fades(), true);
            songs().scrollTop = songs().scrollHeight;
            await t.waitFor(function () { return !fades(); }, 'no fade at the end', 2000);
            songs().scrollTop = 0;
            await t.waitFor(fades, 'the fade again at the top', 2000);
        }
    });

    S2P.scenario({
        name: 'kiosk-list-short-no-fade',
        title: 'a list that fits its card: no fade — nothing is below',
        page: 'kiosk',
        viewport: '1180,820',
        run: async function (t) {
            t.check('the list fits its card', songs().scrollHeight <= songs().clientHeight);
            t.step('no fade', getComputedStyle(songs()).webkitMaskImage, 'none');
        }
    });

    S2P.scenario({
        name: 'settings-kiosk-off-keeps-the-focus',
        title: '"Wyłącz" of the kiosk\'s link: the button goes with the link — the focus stays in the card (on "Utwórz link kiosku"), never the page\'s top',
        page: 'settings-kiosk',
        setup: { kioskLinkAnswer: 'settings-kiosk-answer-off' },
        run: async function (t) {
            const card = document.getElementById('kioskCard');
            card.querySelector('summary').click();
            document.getElementById('kioskLinkOffBtn').click();
            await t.waitFor(function () { return !document.getElementById('kioskLinkInput'); }, 'the card without the link', 3000);
            t.step('the focus in the card, on the button that makes a link', document.activeElement.id, 'kioskLinkNewBtn');
        }
    });
    /**
     * The kiosk with the list as full as it gets (ten songs, long names) on one screen: the page does not scroll — a guest at the bar
     * must not look for "Następny gość" (or "Gotowe") under the list —, the way on and "Wyślij" in full view, the list scrolls in its
     * own card down to its last song.
     */
    function fitsOneScreen(t, way) {
        const root = document.documentElement;
        t.step('the page does not scroll', root.scrollHeight <= root.clientHeight + 1, true);
        const within = function (el) {
            const box = el.getBoundingClientRect();
            return box.top >= 0 && box.bottom <= root.clientHeight && box.height > 0;
        };
        t.step('"' + way + '" in full view', within(document.getElementById(way)), true);
        const send = document.getElementById('submitBtn');
        if (send) t.step('"Wyślij prośbę" in full view', within(send), true);
        const songs = document.querySelector('.s2p-kiosk-songs');
        const last = songs.lastElementChild;
        songs.scrollTop = songs.scrollHeight;
        const card = document.getElementById('kioskList').getBoundingClientRect();
        t.step('the list scrolls in its card to its last song', [last.getBoundingClientRect().bottom <= card.bottom + 1,
            last.getBoundingClientRect().bottom <= root.clientHeight], [true, true]);
        songs.scrollTop = 0;
        const box = songs.getBoundingClientRect();
        const inSight = Array.from(songs.children).filter(function (row) {
            const r = row.getBoundingClientRect();
            return r.top >= box.top - 1 && r.bottom <= box.bottom + 1;
        }).length;
        t.step('at least three songs in sight at once', inSight >= 3, true);
        t.step('every target ≥ 48 px', smallTargets(48), []);
        t.step('nothing wider than the screen', fitsTheScreen(), true);
    }

    S2P.scenario({
        name: 'kiosk-full-fits-1180x820',
        title: 'ten songs on the list, a tablet held across (1180 × 820): one screen — "Wyślij" and "Następny gość" in view, the list scrolling in its card',
        page: 'kiosk-full',
        viewport: '1180,820',
        run: async function (t) { fitsOneScreen(t, 'kioskNext'); }
    });

    S2P.scenario({
        name: 'kiosk-full-fits-1024x768',
        title: 'ten songs on the list, a small tablet held across (1024 × 768): one screen — "Wyślij" and "Następny gość" in view, the list scrolling in its card',
        page: 'kiosk-full',
        viewport: '1024,768',
        run: async function (t) { fitsOneScreen(t, 'kioskNext'); }
    });

    S2P.scenario({
        name: 'kiosk-full-fits-1366x1024',
        title: 'ten songs on the list, a big tablet held across (1366 × 1024): one screen — "Wyślij" and "Następny gość" in view, the list scrolling in its card',
        page: 'kiosk-full',
        viewport: '1366,1024',
        run: async function (t) { fitsOneScreen(t, 'kioskNext'); }
    });

    S2P.scenario({
        name: 'kiosk-full-fits-820x1180',
        title: 'ten songs on the list, a tablet held upright (820 × 1180): one screen — "Wyślij" and "Następny gość" in view, the list scrolling in its card',
        page: 'kiosk-full',
        viewport: '820,1180',
        run: async function (t) { fitsOneScreen(t, 'kioskNext'); }
    });


    /**
     * A screen too small for the form and a few songs at once (a phone, upright or held across): the page scrolls as a page — the
     * form and "Następny gość" first, in view without a scroll, then the whole list at its own height, none of it hidden in a card.
     */
    function scrollsAsAPage(t) {
        const root = document.documentElement;
        const next = document.getElementById('kioskNext').getBoundingClientRect();
        t.step('"Następny gość" in view without a scroll', next.top >= 0 && next.bottom <= root.clientHeight, true);
        const songs = document.querySelector('.s2p-kiosk-songs');
        t.step('the list at its own height: every song on the page, none hidden in its card', songs.scrollHeight <= songs.clientHeight + 1, true);
        const card = document.getElementById('kioskList').getBoundingClientRect();
        t.step('the list\'s card holds its last song', songs.lastElementChild.getBoundingClientRect().bottom <= card.bottom + 1, true);
        t.step('every target ≥ 48 px', smallTargets(48), []);
        t.step('nothing wider than the screen', fitsTheScreen(), true);
    }

    S2P.scenario({
        name: 'kiosk-full-phone-390x844',
        title: 'ten songs on the list, a phone (390 × 844): the page scrolls — "Następny gość" in view, the whole list under it, never one song in a card',
        page: 'kiosk-full',
        viewport: '390,844',
        run: async function (t) { scrollsAsAPage(t); }
    });

    S2P.scenario({
        name: 'kiosk-full-tablet-768x954',
        title: 'ten songs on the list, a small tablet held upright (768 × 1024, 954 px under the browser\'s bars — under the form, room for two songs): the page scrolls — "Następny gość" in view, the whole list under it',
        page: 'kiosk-full',
        viewport: '768,954',
        run: async function (t) { scrollsAsAPage(t); }
    });

    S2P.scenario({
        name: 'kiosk-full-phone-360x640',
        title: 'ten songs on the list, a small phone (360 × 640): the page scrolls — the list on it, never a card squeezed to nothing',
        page: 'kiosk-full',
        viewport: '360,640',
        run: async function (t) { scrollsAsAPage(t); }
    });

    S2P.scenario({
        name: 'kiosk-full-phone-844x390',
        title: 'ten songs on the list, a phone held across (844 × 390): the page scrolls — the whole list on it',
        page: 'kiosk-full',
        viewport: '844,390',
        run: async function (t) {
            const songs = document.querySelector('.s2p-kiosk-songs');
            t.step('the list at its own height: every song on the page', songs.scrollHeight <= songs.clientHeight + 1, true);
            t.step('nothing wider than the screen', fitsTheScreen(), true);
        }
    });

    S2P.scenario({
        name: 'kiosk-full-answer-fits',
        title: 'an answer with ten songs on the list, a small tablet held across (1024 × 768): one screen — "Gotowe — następny gość" in view',
        page: 'kiosk-result-full',
        viewport: '1024,768',
        run: async function (t) { fitsOneScreen(t, 'kioskDone'); }
    });
})();
