// "ZMIEŃ KOD IMPREZY" (V49): against a guest who keeps the code and spams every party — the organiser gives the party a new code on
// "Ustawienia imprezy", and the old one stops working. The organiser's alone; it asks first ("Stare plakaty i karty przestaną
// działać"), and it is a page load, not a form sent in the background: the page of the new code comes back with the card unfolded,
// the new code and the way to print it.
(function () {
    const shows = function (el) { return !!el && el.checkVisibility(); };

    S2P.scenario({
        name: 'party-code-asks-first',
        title: '"Zmień kod imprezy": the organiser\'s last card, folded to the code; asks first — "Anuluj" sends nothing —, then a page load with the party\'s code, not a fetch()',
        page: 'settings',
        run: async function (t) {
            const loads = [];
            navigation.addEventListener('navigate', function (e) {
                loads.push([e.destination.url.replace(location.origin, ''), e.formData ? e.formData.get('partyCode') : null]);
                e.preventDefault();
            });
            const asked = [];
            let answer = false;
            window.confirm = function (text) { asked.push(text); return answer; };
            const card = document.getElementById('partyCodeCard');
            t.step('folded to its heading and the code', [!!card && !card.open, card && card.querySelector('#partyCodeState').textContent.trim()],
                [true, 'HARN1']);
            t.check('the last card of the page', !!card && card === document.querySelector('#settingsCards > details:last-of-type'));
            card.querySelector('summary').click();
            const button = document.getElementById('partyCodeChangeBtn');
            t.step('unfolded: the button, an outline in the colour of what cannot be undone', [shows(button), button.classList.contains('btn-outline-danger')],
                [true, true]);

            button.click();
            await t.sleep(300);
            t.step('asked first, in these words', asked, ['Zmienić kod imprezy? Stare plakaty i karty przestaną działać.']);
            t.step('"Anuluj": nothing sent, nothing loaded', [await t.stand.count('POST /dj/dashboard/party-code'), loads], [0, []]);

            answer = true;
            button.click();
            await t.sleep(300);
            t.step('"OK": a page load of the form, with the party', loads, [['/dj/dashboard/party-code', 'HARN1']]);
            t.step('not sent in the background', await t.stand.count('POST /dj/dashboard/party-code'), 0);
        }
    });

    S2P.scenario({
        name: 'party-code-changed',
        title: 'after "Zmień kod imprezy", on a phone: the card open, the new code said, "Drukuj plakat i karty" leading to the new code\'s print',
        page: 'settings-code-changed',
        viewport: '390,844',
        run: async function (t) {
            const card = document.getElementById('partyCodeCard');
            const said = document.getElementById('partyCodeChanged');
            t.step('the card open, the news shown', [!!card && card.open, shows(said)], [true, true]);
            t.step('the new code in it', !!said && said.textContent.indexOf('Nowy kod imprezy: HARN1.') >= 0, true);
            const print = said && said.querySelector('a');
            t.step('the way to print it', print && [print.getAttribute('href'), print.getAttribute('target')], ['/dj/qr-print?party=HARN1', '_blank']);
            t.step('nothing wider than the screen', document.documentElement.scrollWidth <= document.documentElement.clientWidth, true);
        }
    });

    S2P.scenario({
        name: 'party-code-not-for-the-staff',
        title: 'a co-organiser\'s "Ustawienia imprezy": no "Kod imprezy" card',
        page: 'settings-staff',
        run: async function (t) {
            t.step('no card', !!document.getElementById('partyCodeCard'), false);
            t.step('no form', !!document.querySelector('form[action="/dj/dashboard/party-code"]'), false);
        }
    });
})();
