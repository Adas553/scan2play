// The hosts' lists (V29) on the page "Ustawienia imprezy" (since 2026-10-10; the panel's settings before): the two lists side by side,
// saved in the background like the panel's forms; the hosts' link, made or taken away with a full page load (the new link is shown at
// once); its "Kopiuj" copies the hosts' link.

S2P.scenario({
    name: 'host-lists',
    title: 'the hosts\' lists: "Zapisz" goes in the background with both lists; the hosts\' link reloads the page; "Kopiuj" copies it',
    page: 'settings',
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
        const card = document.getElementById('hostListsCard');
        t.check('the card shows', card.getClientRects().length > 0);
        t.step('the two lists side by side on a computer', document.getElementById('hostBlockedInput').getBoundingClientRect().top,
            document.getElementById('hostWantedInput').getBoundingClientRect().top);

        // --- the lists: in the background, the page stays ---
        document.getElementById('hostBlockedInput').value = 'Akcent\nBaby Shark';
        document.getElementById('hostWantedInput').value = 'Perfect';
        document.querySelector('#hostListsForm button[type="submit"]').click();
        t.step('the lists go in the background (the page stays)', handled.shift(), ['/dj/dashboard/host-lists', true]);
        t.check('sent', await until(async function () { return (await t.stand.count('POST /dj/dashboard/host-lists')) > 0; }, 2000));
        t.step('the server got both lists, with the party', (await t.stand.requests('POST /dj/dashboard/host-lists')).map(function (r) {
            return [r.q.blocked.replace(/\r\n/g, '\n'), r.q.wanted, r.q.partyCode];   // a form sends a textarea's lines as CRLF
        }), [['Akcent\nBaby Shark', 'Perfect', 'HARN1']]);

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
    }
});
