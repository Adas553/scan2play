// The folded cards of "Ustawienia imprezy" and "Obsługa" (details.s2p-fold): the card the address names — #libraryCard, a row of the
// panel's "Więcej", or the page again after a form that reloads it ("Nowy link" → #hostListsCard) — is unfolded, the others stay
// folded. The browser has scrolled to the card already; its top does not move when it unfolds, so the page does not jump. A plain
// script, as dj-nav.js: both pages load it, and "Obsługa" has no modules.
// An address without a card: the cards unfolded when the page was left in this tab are unfolded again — before scroll-restore.js
// puts the page back where it was (on DOMContentLoaded; this script runs at the end of the body), so it lands on the same place.
(function () {
    var openKey = 'folds_' + location.pathname;

    function unfoldTheOnesLeftOpen() {
        var ids = [];
        try {
            ids = JSON.parse(sessionStorage.getItem(openKey) || '[]');
        } catch (e) { /* storage blocked or a stray value: nothing remembered */ }
        if (!Array.isArray(ids)) return;
        ids.forEach(function (id) {
            var fold = typeof id === 'string' ? document.getElementById(id) : null;
            if (fold && fold.matches('details.s2p-fold')) fold.open = true;
        });
    }

    window.addEventListener('beforeunload', function () {
        var open = Array.prototype.slice.call(document.querySelectorAll('details.s2p-fold[open]'))
            .map(function (fold) { return fold.id; }).filter(Boolean);
        try {
            sessionStorage.setItem(openKey, JSON.stringify(open));
        } catch (e) { /* storage blocked: nothing remembered */ }
    });

    function unfoldTheNamedCard() {
        var id = location.hash.slice(1);
        if (!id) return;
        var target;
        try {
            target = document.getElementById(decodeURIComponent(id));
        } catch (e) {
            return;   // an address typed by hand ("%zz"): nothing to unfold
        }
        var fold = target && target.closest('details.s2p-fold');
        if (fold) fold.open = true;
    }

    if (location.hash) {
        unfoldTheNamedCard();
    } else {
        unfoldTheOnesLeftOpen();
    }
    window.addEventListener('hashchange', unfoldTheNamedCard);
})();
