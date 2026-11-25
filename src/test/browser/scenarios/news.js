// "NOWOŚCI" (V80): the panel of an organiser of an account older than the news has a card above the queue — what is new, each line
// with its part of the guide — until they close it: "✕", closed on the server (POST /dj/dashboard/news-dismiss), not in the browser's
// storage. "Wysyłaj mi nowości mailem" is the organiser's consent to the news e-mails: sent in the background (POST
// /dj/dashboard/news-emails, on), the card then says it is saved; a refused save puts the button back. Its button an outline: the view's
// one action stays "Drukuj". The same consent as a switch in "Ustawienia imprezy", off by default.
(function () {
    const shows = function (el) { return !!el && el.checkVisibility(); };
    const DISMISS = 'POST /dj/dashboard/news-dismiss';
    const EMAILS = 'POST /dj/dashboard/news-emails';
    const card = function () { return document.getElementById('newsCard'); };
    const tooSmall = function (root) {
        return Array.from(root.querySelectorAll('a.btn, button'))
            .filter(function (el) { return el.checkVisibility(); })
            .filter(function (el) { return el.getBoundingClientRect().height < 44 - 0.5; })
            .map(function (el) { return (el.id || el.textContent.trim().slice(0, 20)) + ' ' + Math.round(el.getBoundingClientRect().height) + 'px'; });
    };
    const sent = async function (t, what, n) {
        for (let i = 0; i < 100; i++) {
            if (await t.stand.count(what) >= n) return true;
            await t.sleep(30);
        }
        return false;
    };

    S2P.scenario({
        name: 'news-card-shows',
        title: 'an organiser of before on a phone: "Nowości w Scan2Play" above the queue, three lines each leading to its part of the guide, the offer of the e-mails an outline, a thumb\'s targets, nothing wider than the screen, nothing sent',
        page: 'dashboard-news',
        viewport: '320,700',
        run: async function (t) {
            await t.waitFor(function () { return shows(card()); }, 'the card', 8000);
            t.step('its heading', card().querySelector('h2').textContent.trim(), 'Nowości w Scan2Play');
            t.check('above the queue', card().getBoundingClientRect().bottom <= document.getElementById('queueList').getBoundingClientRect().top);
            t.step('its lines lead to the guide', Array.from(card().querySelectorAll('li a')).map(function (a) { return a.getAttribute('href'); }),
                ['/jak-to-dziala#deck', '/jak-to-dziala#guidePolls', '/jak-to-dziala#guidePlan']);
            t.step('no action of its own: an outline beside the queue\'s', card().querySelectorAll('.btn-action').length, 0);
            t.step('the e-mails offered, "Zapisane" not yet', [shows(document.getElementById('newsEmailsOn')),
                shows(document.getElementById('newsEmailsDone'))], [true, false]);
            t.step('the offer\'s button the card\'s width on a phone', Math.abs(document.getElementById('newsEmailsOn').getBoundingClientRect().width
                - document.getElementById('newsEmailsAsk').getBoundingClientRect().width) < 1, true);
            t.step('a thumb\'s target, each', tooSmall(card()), []);
            t.check('nothing wider than the screen', document.documentElement.scrollWidth <= document.documentElement.clientWidth);
            t.step('nothing sent yet', [await t.stand.count(DISMISS), await t.stand.count(EMAILS)], [0, 0]);
        }
    });

    S2P.scenario({
        name: 'news-card-close',
        title: '"✕" on "Nowości" closes it at once and for good: one POST with the party and the CSRF token, the focus on the queue',
        page: 'dashboard-news',
        viewport: '390,844',
        run: async function (t) {
            await t.waitFor(function () { return shows(card()); }, 'the card', 8000);
            card().querySelector('[data-news-close]').click();
            t.step('gone at once', shows(card()), false);
            t.step('the close sent', await sent(t, DISMISS, 1), true);
            const close = (await t.stand.requests(DISMISS))[0];
            t.step('for this party, with the CSRF token', close && [close.q.partyCode, close.q['X-CSRF-TOKEN']], ['HARN1', 'harness-csrf-token']);
            t.step('the focus on the queue', document.activeElement && document.activeElement.id, 'queueList');
            t.step('sent once, no consent with it', [await t.stand.count(DISMISS), await t.stand.count(EMAILS)], [1, 0]);
        }
    });

    S2P.scenario({
        name: 'news-card-emails-on',
        title: '"Wysyłaj mi nowości mailem": the consent sent once in the background (on, the party, the fetch\'s header, the token), the card says "✓ Zapisane" in its place, the card stays, nothing closed, no page load',
        page: 'dashboard-news',
        viewport: '390,844',
        run: async function (t) {
            const loads = [];
            navigation.addEventListener('navigate', function (e) { loads.push(e.destination.url); e.preventDefault(); });
            await t.waitFor(function () { return shows(card()); }, 'the card', 8000);
            document.getElementById('newsEmailsOn').click();
            await t.waitFor(function () { return shows(document.getElementById('newsEmailsDone')); }, '"✓ Zapisane"', 4000);
            const sentNow = await t.stand.requests(EMAILS);
            t.step('sent once: on, the party, by fetch, with the token', sentNow.map(function (r) {
                return [r.q.on, r.q.partyCode, r.q['X-Requested-With'], r.q['X-CSRF-TOKEN']];
            }), [['true', 'HARN1', 'fetch', 'harness-csrf-token']]);
            t.step('the offer gone, the words in its place', [shows(document.getElementById('newsEmailsAsk')),
                document.getElementById('newsEmailsDone').textContent.trim()], [false, '✓ Zapisane. Napiszemy, gdy dodamy coś nowego.']);
            t.step('the card stays, not closed', [shows(card()), await t.stand.count(DISMISS)], [true, 0]);
            t.step('no page load', loads, []);
        }
    });

    S2P.scenario({
        name: 'news-card-emails-refused',
        title: 'the consent refused by the server (a session run out): the button back, nothing said saved',
        page: 'dashboard-news',
        viewport: '390,844',
        setup: { postStatus: { '/dj/dashboard/news-emails': 403 } },
        run: async function (t) {
            await t.waitFor(function () { return shows(card()); }, 'the card', 8000);
            const button = document.getElementById('newsEmailsOn');
            button.click();
            t.step('sent', await sent(t, EMAILS, 1), true);
            await t.waitFor(function () { return !button.disabled; }, 'the button back', 4000);
            t.step('the offer still there, nothing said saved', [shows(document.getElementById('newsEmailsAsk')),
                shows(document.getElementById('newsEmailsDone'))], [true, false]);
        }
    });

    S2P.scenario({
        name: 'news-emails-switch',
        title: '"Nowości mailem" in "Ustawienia imprezy" on a phone: folded to "Wyłączone" (off by default); switched on it is sent at once in the background, the server\'s card in place ("Włączone", "✓ Zapisane"), the focus on the switch, no page load',
        page: 'settings',
        viewport: '390,844',
        run: async function (t) {
            const loads = [];
            navigation.addEventListener('navigate', function (e) { loads.push(e.destination.url); e.preventDefault(); });
            const settingsCard = document.getElementById('newsEmailsCard');
            t.check('the organiser\'s card is there', !!settingsCard);
            if (!settingsCard) return;
            t.step('folded to its state', [settingsCard.open, settingsCard.querySelector('.s2p-fold-state').textContent.trim()], [false, 'Wyłączone']);
            settingsCard.open = true;
            const toggle = document.getElementById('newsEmailsToggle');
            t.step('the switch is off', toggle.checked, false);
            const row = toggle.closest('.s2p-switch-row') || toggle.parentElement;
            t.check('the switch\'s row is 44 px to tap (' + Math.round(row.getBoundingClientRect().height) + ')',
                row.getBoundingClientRect().height >= 44 - 0.5);

            toggle.focus();
            toggle.click();
            await t.waitFor(function () { return document.getElementById('newsEmailsSaved'); }, 'the server\'s card', 4000);
            const sentNow = await t.stand.requests(EMAILS);
            t.step('sent once, in the background, on, the party and the token', sentNow.map(function (r) {
                return [r.q.on, r.q.partyCode, r.q['X-Requested-With'], r.q['X-CSRF-TOKEN']];
            }), [['true', 'HARN1', 'fetch', 'harness-csrf-token']]);
            t.step('the card says it', [settingsCard.querySelector('.s2p-fold-state').textContent.trim(),
                document.getElementById('newsEmailsSaved').textContent.trim()], ['Włączone', '✓ Zapisane']);
            t.step('the focus on the switch (the new card\'s), on', [document.activeElement.id, document.activeElement.checked],
                ['newsEmailsToggle', true]);
            t.step('no page load', loads, []);
        }
    });
})();
