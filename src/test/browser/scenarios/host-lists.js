// The hosts' lists (V29) in the DJ's panel: the card under the profiles — the two lists, saved in the background like the panel's
// other forms, and the queue fetched at once (a song on the wish list gets its ⭐ without waiting for the 3 s poll); the hosts'
// link, made or taken away with a full page load (the new link is shown at once); its "Kopiuj" copies the hosts' link, not the
// party's.

S2P.scenario({
    name: 'host-lists',
    title: 'the hosts\' lists: "Zapisz" goes in the background and the queue comes at once; the hosts\' link reloads the page; "Kopiuj" copies it',
    setup: { queue: [{ id: 1, name: 'Golec uOrkiestra - Hej sokoły', url: 'https://www.youtube.com/results?search_query=Hej' }] },
    run: async function (t) {
        const handled = [];
        document.addEventListener('submit', function (e) {
            handled.push([e.target.getAttribute('action'), e.defaultPrevented]);
            e.preventDefault();   // the stand-in has no page to go to: stay
        });
        // t.waitFor takes a condition that answers at once; these ask the stand-in, so they wait here
        const until = async function (condition, ms) {
            const end = performance.now() + ms;
            while (performance.now() < end) {
                if (await condition()) return true;
                await t.sleep(50);
            }
            return false;
        };
        // --- the layout on a computer: in the wide column, the lists side by side, the right column not taller than the left one
        //     (2026-10-09: in the narrow column the card left an empty gap beside the limits) ---
        // the settings fold under "⚙️ Ustawienia…" on a computer too: unfolded first, so the layout is measured as the DJ sees it
        document.getElementById('settingsToggle').click();
        const card = document.getElementById('hostListsCard');
        t.check('the card shows once the settings are unfolded', card.getClientRects().length > 0);
        t.check('the card is in the wide (left) column', !!card.closest('.col-md-8'));
        t.step('the two lists side by side', document.getElementById('hostBlockedInput').getBoundingClientRect().top,
            document.getElementById('hostWantedInput').getBoundingClientRect().top);
        const left = card.closest('.col-md-8').getBoundingClientRect();
        const right = document.querySelector('.row > .col-md-4.s2p-settings').getBoundingClientRect();
        t.check('the right column (QR code, profiles) ends above the left one', right.bottom <= left.bottom + 1);

        const POLL = 'GET /dj/dashboard/updates';
        t.check('the first poll', await until(async function () { return (await t.stand.count(POLL)) > 0; }, 8000));

        // --- the lists: in the background, the queue at once (right after a poll: the next one is 3 s away) ---
        document.getElementById('hostBlockedInput').value = 'Akcent\nBaby Shark';
        document.getElementById('hostWantedInput').value = 'Perfect';
        const polls = await t.stand.count(POLL);
        document.querySelector('#hostListsForm button[type="submit"]').click();
        t.step('the lists go in the background (the page stays)', handled.shift(), ['/dj/dashboard/host-lists', true]);
        t.check('the queue is fetched at once, not with the next 3 s poll',
            await until(async function () { return (await t.stand.count(POLL)) > polls; }, 1500));
        t.step('the server got both lists', (await t.stand.requests('POST /dj/dashboard/host-lists')).map(function (r) {
            return [r.q.blocked.replace(/\r\n/g, '\n'), r.q.wanted];   // a form sends a textarea's lines as CRLF
        }), [['Akcent\nBaby Shark', 'Perfect']]);

        // --- the hosts' link: a full page load ---
        document.getElementById('hostLinkOffBtn').click();
        t.step('turning the link off is a page load (not sent in the background)', handled.shift(), ['/dj/dashboard/host-link', false]);

        // --- "Kopiuj" of the hosts' link copies that link ---
        let copied = null;
        Object.defineProperty(navigator.clipboard, 'writeText', {
            configurable: true,
            value: function (text) { copied = text; return Promise.resolve(); }
        });
        document.querySelector('button[data-copy-target="hostLinkInput"]').click();
        await t.sleep(100);
        t.step('"Kopiuj" copies the hosts\' link', copied, document.getElementById('hostLinkInput').value);
        document.getElementById('copyPartyLinkBtn').click();
        await t.sleep(100);
        t.step('the party\'s "Kopiuj" still copies the party\'s link', copied, document.getElementById('partyLinkInput').value);
    }
});
