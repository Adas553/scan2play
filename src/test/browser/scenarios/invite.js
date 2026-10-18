// The staff's invitation on the landing page (the owner, 2026-10-18: "czy ten przycisk jest profesjonalny"): under the last
// "Zbieraj prośby gości", not between the hero and the steps; "Masz link z zaproszeniem od DJ-a? Wklej go tutaj" unfolds the field.

S2P.scenario({
    name: 'landing-invite-under-the-final-start',
    title: 'the landing page on a phone: the invitation under the last start, a finger\'s height, unfolds to the field',
    page: 'landing-pl',
    viewport: '390,844',
    run: async function (t) {
        const box = document.getElementById('staffLogin');
        const start = document.querySelector('#landingFinal a[href="/start"]');
        t.check('under the last start', !!box && !!start && box.getBoundingClientRect().top > start.getBoundingClientRect().bottom);
        const summary = box.querySelector('summary');
        t.step('its words', summary.textContent.trim(), 'Masz link z zaproszeniem od DJ-a? Wklej go tutaj');
        t.check('a finger\'s height (at least 40 px)', summary.getBoundingClientRect().height >= 40);
        summary.click();
        await t.waitFor(function () { return box.open; }, 'unfolded');
        const field = document.getElementById('staffLinkPaste');
        t.check('the field shown', field.getBoundingClientRect().height > 0);
        t.check('no scrolling sideways', document.documentElement.scrollWidth <= window.innerWidth);
    }
});
