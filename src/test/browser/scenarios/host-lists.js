// The hosts' lists (V29) on the page "Ustawienia imprezy" (since 2026-10-10; the panel's settings before): the two lists side by side,
// saved in the background like the panel's forms; the hosts' link, made or taken away in the background too — the link's box comes back
// from the server's page, the lists as typed (cards-in-place.js); its "Kopiuj" copies the hosts' link.

S2P.scenario({
    name: 'host-lists',
    title: 'the hosts\' lists: "Zapisz" goes in the background with both lists; the hosts\' link too; "Kopiuj" copies it',
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

        // --- the hosts' link: in the background too, its box put in place (cards-in-place.js; host-link-in-place) ---
        document.getElementById('hostLinkOffBtn').click();
        t.step('turning the link off goes in the background (the page stays)', handled.shift(), ['/dj/dashboard/host-link', true]);
        await t.waitFor(function () {   // the server's page answers with a new link (the stand-in's): its box in place
            const field = document.getElementById('hostLinkInput');
            return field && field.value.indexOf('New_456') >= 0;
        }, 'the link\'s box from the server', 3000);

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

S2P.scenario({
    name: 'host-link-in-place',
    title: 'the hosts\' link made again on a phone: asks first, sent in the background, the new link in its box, "✓" on the button — the lists as typed, the screen does not jump',
    page: 'settings',
    viewport: '390,844',
    run: async function (t) {
        const loads = [];
        navigation.addEventListener('navigate', function (e) { loads.push(e.destination.url); e.preventDefault(); });
        const asked = [];
        window.confirm = function (text) { asked.push(text); return true; };
        const card = document.getElementById('hostListsCard');
        card.querySelector('summary').click();
        document.getElementById('hostBlockedInput').value = 'Akcent';   // typed, not saved yet
        const oldLink = document.getElementById('hostLinkInput').value;
        const button = document.getElementById('hostLinkNewBtn');
        window.scrollTo({ top: window.scrollY + button.getBoundingClientRect().top - window.innerHeight / 2, behavior: 'instant' });
        const before = window.scrollY;
        const cardTop = card.getBoundingClientRect().top;
        button.click();
        await t.waitFor(function () { return document.getElementById('hostLinkInput').value !== oldLink; }, 'the new link in place', 3000);
        t.step('asked first', asked.length, 1);
        t.step('the new link', document.getElementById('hostLinkInput').value, 'http://localhost:8080/h/New_456-abcNew_456-abc');
        const fresh = document.getElementById('hostLinkNewBtn');
        t.step('"✓" on the button, as every card', [fresh.textContent.trim(), fresh.classList.contains('btn-success')], ['✓', true]);
        t.step('the lists as typed', document.getElementById('hostBlockedInput').value, 'Akcent');
        t.step('the page where it was (± 2 px), the card too', [Math.abs(window.scrollY - before) <= 2,
            Math.abs(card.getBoundingClientRect().top - cardTop) <= 2], [true, true]);
        t.check('scrolled down first (a place to keep)', before > 100);
        t.step('no page load', loads, []);
    }
});
