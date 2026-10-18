// The language switch (LanguageSwitchAdvice, the owner 2026-10-18): "Polski · English" in the footer of every page and in the panel's
// menu "Konto" — this page with ?lang=, which WebConfig keeps in a cookie. DashboardPageRenderTest gives the rendered panel the links
// the advice gives the real one; the stand-in does not change the language, so these check what the DJ sees and taps.

S2P.scenario({
    name: 'lang-switch-in-the-account-menu',
    title: 'the panel: "Konto" opens with "Polski" (marked) and "English", each this panel with ?lang=',
    viewport: '390,844',   // a phone's screen: the footer is far under the queue there
    run: async function (t) {
        document.getElementById('accountMenuBtn').click();
        const menu = document.querySelector('[aria-labelledby="accountMenuBtn"]');
        await t.waitFor(function () { return menu.classList.contains('show'); }, 'the menu open');
        const links = Array.from(menu.querySelectorAll('a[hreflang]'));
        t.step('two languages, each this panel', links.map(function (a) { return [a.textContent.trim(), a.getAttribute('href')]; }),
            [['Polski', '/dj/dashboard?party=HARN1&lang=pl'], ['English', '/dj/dashboard?party=HARN1&lang=en']]);
        t.step('the one shown marked', links.map(function (a) { return a.getAttribute('aria-current'); }), ['true', null]);
        t.check('both shown in the open menu', links.every(function (a) { return a.getBoundingClientRect().height > 0; }));
    }
});

S2P.scenario({
    name: 'lang-switch-in-the-footer',
    title: 'the footer\'s "Polski · English": both links shown, at least 40 px high to tap on a phone, readable',
    viewport: '390,844',
    run: async function (t) {
        const nav = document.getElementById('langSwitch');
        t.check('the switch is in the footer', !!nav && !!nav.closest('footer'));
        if (!nav) {
            return;
        }
        const links = Array.from(nav.querySelectorAll('a'));
        t.step('two links', links.map(function (a) { return a.textContent.trim(); }), ['Polski', 'English']);
        t.step('each at least 40 px high (a finger)', links.filter(function (a) { return a.getBoundingClientRect().height < 40; }).length, 0);
        t.check('no scrolling sideways', document.documentElement.scrollWidth <= window.innerWidth);
    }
});
