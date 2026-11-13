// The long lists of "Życzenia przed imprezą" (wishes.html, [data-more-list]): the server shows the first data-shown rows, the rest
// hidden; "Pokaż więcej" shows data-step more (how many are left beside it), "Pokaż mniej" goes back to the first ones and brings its
// buttons back on the screen. How many are shown is remembered for the tab (session storage, by the page's address with its event,
// each list by its name — data-more-list): a "⭐" loads the page again, and it must come back as long as it was, or scroll-restore.js
// would put the DJ somewhere else. A plain script at the end of the body: it runs before DOMContentLoaded, when scroll-restore.js puts
// the page back where it was.
(function () {
    var lists = Array.prototype.slice.call(document.querySelectorAll('[data-more-list]'));
    if (!lists.length) return;
    var key = 'more_' + location.pathname + location.search;

    function rowsOf(list) {
        return Array.prototype.slice.call(list.querySelectorAll('ol > li, ul > li'));
    }

    function first(list) {
        return parseInt(list.getAttribute('data-shown'), 10) || 10;
    }

    /** The first {@code count} rows shown, the rest hidden; the buttons say what they would do. */
    function show(list, count) {
        var rows = rowsOf(list);
        count = Math.max(first(list), Math.min(count, rows.length));
        rows.forEach(function (row, i) { row.hidden = i >= count; });
        var more = list.querySelector('[data-more-show]');
        var fold = list.querySelector('[data-more-fold]');
        var left = rows.length - count;
        if (more) {
            more.hidden = left <= 0;
            var says = more.querySelector('[data-more-left]');
            if (says) says.textContent = '(' + left + ')';
        }
        if (fold) fold.hidden = count <= first(list);
        list.s2pShown = count;
    }

    var remembered = {};
    try {
        remembered = JSON.parse(sessionStorage.getItem(key) || '{}') || {};
    } catch (e) { /* storage blocked or a stray value: the first ones */ }

    /** A list's name in the memory: its own, so a list that appears later ("Do sprawdzenia" first, the named ones after) takes no other's. */
    function nameOf(list, i) {
        return list.getAttribute('data-more-list') || String(i);
    }

    lists.forEach(function (list, i) {
        var kept = remembered[nameOf(list, i)];
        show(list, typeof kept === 'number' ? kept : first(list));
        list.addEventListener('click', function (e) {
            if (e.target.closest('[data-more-show]')) {
                var step = parseInt(e.target.closest('[data-more-show]').getAttribute('data-step'), 10) || 10;
                show(list, list.s2pShown + step);
            } else if (e.target.closest('[data-more-fold]')) {
                show(list, first(list));
                // the list is shorter now: its buttons brought to the middle of the screen — not left far below them
                var buttons = list.querySelector('[data-more-show]').parentElement;
                buttons.scrollIntoView({ block: 'center' });
            }
        });
    });

    // "pagehide" too: Safari on an iPhone leaves a page without "beforeunload"
    function remember() {
        var shown = {};
        lists.forEach(function (list, i) { shown[nameOf(list, i)] = list.s2pShown; });
        try {
            sessionStorage.setItem(key, JSON.stringify(shown));
        } catch (e) { /* storage blocked: nothing remembered */ }
    }
    window.addEventListener('beforeunload', remember);
    window.addEventListener('pagehide', remember);
})();
