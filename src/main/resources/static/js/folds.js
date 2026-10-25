// The folded cards of "Ustawienia imprezy" and "Obsługa" (details.s2p-fold): the card the address names — #libraryCard, a row of the
// panel's "Więcej", or the page again after a form that reloads it ("Nowy link" → #hostListsCard) — is unfolded, the others stay
// folded. The browser has scrolled to the card already; its top does not move when it unfolds, so the page does not jump. A plain
// script, as dj-nav.js: both pages load it, and "Obsługa" has no modules.
(function () {
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

    unfoldTheNamedCard();
    window.addEventListener('hashchange', unfoldTheNamedCard);
})();
