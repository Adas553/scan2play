// The language the page declares (<html lang>). dashboard.html had lang="en" written into it, whatever the language of the texts:
// a Polish DJ got a page whose texts were Polish and whose lang said English — wrong for a screen reader (which pronounces by it),
// for the browser's own translation ("translate this page from English?") and for hyphenation. The page now says the language of
// the message bundle that wrote its texts (the key html.lang of each bundle), so it cannot disagree with them.
//
// The harness serves the dashboard in Polish (dashboard.html) and, for this scenario, in English (dashboard-en.html: the same party,
// rendered by DashboardPageRenderTest with the English locale).
(function () {
    const guestsLine = function () { return document.getElementById('fallbackGuestsWaiting'); };

    S2P.scenario({
        name: 'page-language',
        title: '<html lang> of the Polish page is "pl" — the language of its texts, and the one the plural forms of the guests line are picked in',
        setup: {},
        run: async function (t) {
            t.step('the page says it is Polish', document.documentElement.lang, 'pl');
            t.step('the texts really are Polish (the import result box says "Playlista zapisana")', document.getElementById('fallbackImportStatus').dataset.textOk.indexOf('Playlista zapisana') === 0, true);
            t.step('the language of the plural rules (data-lang) is the same as <html lang>', guestsLine().dataset.lang, document.documentElement.lang);
        }
    });

    S2P.scenario({
        name: 'page-language-en',
        title: '<html lang> of the English page is "en"',
        page: 'dashboard-en',
        setup: {},
        run: async function (t) {
            t.step('the page says it is English', document.documentElement.lang, 'en');
            t.step('the language of the plural rules (data-lang) is the same as <html lang>', guestsLine().dataset.lang, document.documentElement.lang);
            t.step('the texts really are English (the import result box says "Playlist saved")', document.getElementById('fallbackImportStatus').dataset.textOk.indexOf('Playlist saved') === 0, true);
        }
    });
})();
