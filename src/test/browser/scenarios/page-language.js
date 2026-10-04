// The language the page declares (<html lang>). dashboard.html had lang="en" written into it, whatever the language of the texts:
// a Polish DJ got a page whose texts were Polish and whose lang said English — wrong for a screen reader (which pronounces by it),
// for the browser's own translation ("translate this page from English?") and for hyphenation. The page now says the language of
// the message bundle that wrote its texts (the key html.lang of each bundle), so it cannot disagree with them.
//
// The harness serves the dashboard in Polish (dashboard.html) and, for this scenario, in English (dashboard-en.html: the same party,
// rendered by DashboardPageRenderTest with the English locale).
(function () {
    const skipButton = function () { return document.querySelector('form[action="/dj/dashboard/dismiss"] button[type="submit"]'); };

    S2P.scenario({
        name: 'page-language',
        title: '<html lang> of the Polish page is "pl" — the language of its texts',
        setup: {},
        run: async function (t) {
            t.step('the page says it is Polish', document.documentElement.lang, 'pl');
            t.step('the texts really are Polish (a waiting request\'s button says "Pomiń")', skipButton().textContent.trim(), 'Pomiń');
        }
    });

    S2P.scenario({
        name: 'page-language-en',
        title: '<html lang> of the English page is "en"',
        page: 'dashboard-en',
        setup: {},
        run: async function (t) {
            t.step('the page says it is English', document.documentElement.lang, 'en');
            t.step('the texts really are English (a waiting request\'s button says "Skip")', skipButton().textContent.trim(), 'Skip');
        }
    });
})();
