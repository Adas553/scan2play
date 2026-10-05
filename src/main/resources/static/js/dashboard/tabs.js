/**
 * TABS — Panel / Queue / History, in a bar that stays in view (fragment dj-nav, .dj-tabbar).
 *
 * Panel scrolls to the top of the page (the settings, the QR code), Queue shows the queue and scrolls to it,
 * History shows the history and scrolls to it, so from anywhere on a long page the DJ can jump to any of them. The tab
 * that is lit follows the part of the page in view.
 * The History tab loads its content via AJAX into #history-content and swaps it with #queue-content, for every kind of party:
 * the page is not reloaded (the DJ keeps their place). The standalone history page does not
 * load this module: its tabs are plain links.
 */
import { EVENTS, on } from './events.js';
import { csrfHeaders, partyCode } from './common.js';
import { captureListState, initSortableHeaders, restoreListState, setHistoryReloader } from './list-tools.js';

(function initTabs() {
    const bar = document.getElementById('djTabBar');
    const links = {
        panel:   document.querySelector('[data-dj-tab="panel"]'),
        queue:   document.querySelector('[data-dj-tab="queue"]'),
        history: document.querySelector('[data-dj-tab="history"]')
    };
    const queueContent   = document.getElementById('queue-content');
    const historyContent = document.getElementById('history-content');
    if (!bar || !links.panel || !links.queue || !links.history || !queueContent || !historyContent) return;

    let activeList = 'queue';   // the list that shows: 'queue' or 'history' (swapped without leaving the page)
    let lit = 'panel';          // the tab that is lit
    // A smooth scroll started by a click passes other parts of the page on its way: they must not light their tabs meanwhile.
    let litLockedUntil = 0;
    const SMOOTH_SCROLL_MS = 900;

    function setLit(name) {
        lit = name;
        Object.keys(links).forEach(function(key) {
            links[key].classList.toggle('active', key === name);
            if (key === name) links[key].setAttribute('aria-current', 'page');
            else links[key].removeAttribute('aria-current');
        });
    }

    /** The tab of the part of the page in view: Panel above the list; the list once it has come up to the upper half of the screen. */
    function tabByPosition() {
        if (window.scrollY < 2) return 'panel';
        const list = activeList === 'history' ? historyContent : queueContent;
        // At the very bottom the list wins even when a short page could not bring it up any higher
        const atBottom = window.scrollY + window.innerHeight >= document.documentElement.scrollHeight - 2;
        return atBottom || list.getBoundingClientRect().top < window.innerHeight * 0.5 ? activeList : 'panel';
    }

    function followScroll() {
        if (Date.now() < litLockedUntil) return;
        const name = tabByPosition();
        if (name !== lit) setLit(name);
    }
    window.addEventListener('scroll', followScroll, { passive: true });
    window.addEventListener('resize', followScroll);
    // Where it exists: when a scroll has ended the lock is over (a long smooth scroll may outlast SMOOTH_SCROLL_MS) and the lit tab is checked once more
    window.addEventListener('scrollend', function() { litLockedUntil = 0; followScroll(); });

    function scrollToPosition(top) {
        const reduced = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
        litLockedUntil = Date.now() + (reduced ? 0 : SMOOTH_SCROLL_MS);
        // 'instant', not 'auto': Bootstrap sets scroll-behavior: smooth on the page, which 'auto' would inherit
        window.scrollTo({ top: top, behavior: reduced ? 'instant' : 'smooth' });
    }

    /**
     * After a click on a tab the part of the page it stands for may be far away — on a phone the lists sit below the
     * settings and the QR code — so bring the top of the list into view, just under the tab bar. Unless it
     * already is in the upper part of the screen (a wide screen shows it without any scrolling).
     */
    function revealContent(element) {
        const top = element.getBoundingClientRect().top;
        const barHeight = bar.getBoundingClientRect().height;
        if (top >= barHeight && top < window.innerHeight * 0.4) return;
        scrollToPosition(window.scrollY + top - barHeight - 8);
    }

    /**
     * The history fragment: the last page of entries, or — "Show more" — the last {@code limit} of them, of the kind
     * {@code filter} says (all / played / rejected; none or an old value = all). The server filters.
     */
    function fetchHistory(limit, filter) {
        let url = '/dj/history-view/fragment?partyCode=' + encodeURIComponent(partyCode());
        if (limit) url += '&limit=' + encodeURIComponent(limit);
        if (filter && filter !== 'all') url += '&filter=' + encodeURIComponent(filter);
        return fetch(url, { headers: csrfHeaders() })
            .then(function(r) {
                // A redirect means the session expired (the login page would come back as 200 OK).
                if (!r.ok || r.redirected) throw new Error('HTTP ' + r.status);
                return r.text();
            });
    }

    let historyRequest = 0;

    /**
     * "Show more" and the filter buttons of the History tab: ask for a longer history ({@code limit}) and/or another
     * kind of entries ({@code filter}; none = the one that is chosen) and put the answer in place, keeping what the DJ had
     * set up — the search text, the sort by a column, and the scroll position unless the filter changed (the new list starts at
     * its top).
     * Only the newest answer is shown. Resolves to true when the list was replaced, false when the request failed, and
     * null when a newer request took over (its answer decides). (The standalone history page has no such function; there
     * the buttons reload the page — see list-tools.js.)
     */
    function reloadHistory(limit, filter) {
        const list = historyContent.querySelector('[data-list]');
        const state = captureListState(list);       // the search, the filter, the sort
        const chosen = state.filter;
        state.filter = filter || chosen;
        const box = historyContent.querySelector('.list-scroll');
        const scrollTop = box && state.filter === chosen ? box.scrollTop : 0;
        const request = ++historyRequest;

        return fetchHistory(limit, state.filter).then(function(html) {
            if (request !== historyRequest) return null;        // an older answer: a newer request is on its way
            historyContent.innerHTML = html;
            initSortableHeaders(historyContent);
            restoreListState(historyContent.querySelector('[data-list]'), state);
            const newBox = historyContent.querySelector('.list-scroll');
            if (newBox) newBox.scrollTop = scrollTop;
            return true;
        }).catch(function(err) {
            console.error('[Tabs] History reload error:', err);
            return false;
        });
    }
    setHistoryReloader(reloadHistory);

    // A skipped request put back in the queue ("Cofnij", "↩ Przywróć"): a history that has been loaded shows it no longer
    on(EVENTS.HISTORY_CHANGED, function () {
        if (historyContent.querySelector('[data-list]')) reloadHistory(null, null);
    });

    // Panel: the top of the page. The list that shows stays as it is.
    links.panel.addEventListener('click', function(e) {
        e.preventDefault();
        setLit('panel');
        scrollToPosition(0);
    });

    // Queue: the queue shows (and the history goes away) and the page scrolls to it.
    links.queue.addEventListener('click', function(e) {
        e.preventDefault();
        if (activeList === 'history') {
            queueContent.hidden = false;
            historyContent.hidden = true;
            activeList = 'queue';
        }
        setLit('queue');
        revealContent(queueContent);
    });

    // History: the history is loaded into the page and shown in place of the queue, and the page scrolls to it (also when
    // it shows already: the DJ may be looking at the panel).
    links.history.addEventListener('click', function(e) {
        e.preventDefault();
        if (activeList === 'history') {
            setLit('history');
            revealContent(historyContent);
            return;
        }

        fetchHistory()
        .then(function(html) {
            historyContent.innerHTML = html;
            queueContent.hidden = true;
            historyContent.hidden = false;
            activeList = 'history';
            setLit('history');
            // Init sorting on dynamically loaded history table
            initSortableHeaders(historyContent);
            revealContent(historyContent);
        })
        .catch(function(err) { console.error('[Tabs] History load error:', err); });
    });
})();
