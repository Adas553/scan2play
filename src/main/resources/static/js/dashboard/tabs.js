/**
 * TABS — Panel / History of the bar that stays in view (fragment dj-nav, .dj-tabbar); its third tab, Settings, is a page of its own
 * (a plain link).
 *
 * Panel shows the queue, History the history, each brought into view from anywhere on a long page; the lit tab is the list that
 * shows. The History tab loads its content via AJAX into #history-content and swaps it with #queue-content, for every kind of
 * party: the page is not reloaded (the DJ keeps their place). While it shows, the page is the page "Historia" as the standalone one:
 * <body> has .s2p-history-shown and app.css hides the panel's "Impreza i kod QR" and its settings, which the Panel tab brings back as
 * they were. The standalone history page does not load this module: its tabs are plain links.
 */
import { EVENTS, on } from './events.js';
import { csrfHeaders, demoMode, hideRefused, partyCode, showRefused } from './common.js';
import { captureListState, initSortableHeaders, restoreListState, setHistoryReloader } from './list-tools.js';
import { reopenCards } from './cards.js';

(function initTabs() {
    const bar = document.getElementById('djTabBar');
    const links = {
        panel:   document.querySelector('[data-dj-tab="panel"]'),
        history: document.querySelector('[data-dj-tab="history"]')
    };
    const queueContent   = document.getElementById('queue-content');
    const historyContent = document.getElementById('history-content');
    if (!bar || !links.panel || !links.history || !queueContent || !historyContent) return;

    let activeList = 'queue';   // the list that shows: 'queue' or 'history' (swapped without leaving the page)

    function setLit(name) {
        Object.keys(links).forEach(function(key) {
            links[key].classList.toggle('active', key === name);
            if (key === name) links[key].setAttribute('aria-current', 'page');
            else links[key].removeAttribute('aria-current');
        });
    }

    function scrollToPosition(top) {
        const reduced = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
        // 'instant', not 'auto': Bootstrap sets scroll-behavior: smooth on the page, which 'auto' would inherit
        window.scrollTo({ top: top, behavior: reduced ? 'instant' : 'smooth' });
    }

    /**
     * After a click on a tab its list may be far away — below the unfolded settings, or scrolled past deep in a long list — so bring
     * the top of the list into view, just under the tab bar. Unless it already is in the upper part of the screen (folded, the
     * settings leave it there).
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
        let url = (demoMode() ? '/demo/history' : '/dj/history-view/fragment') + '?partyCode=' + encodeURIComponent(partyCode());
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
            reopenCards(historyContent);
            const newBox = historyContent.querySelector('.list-scroll');
            if (newBox) newBox.scrollTop = scrollTop;
            hideRefused('load');
            return true;
        }).catch(function(err) {
            console.error('[Tabs] History reload error:', err);
            showRefused('load');   // the list stays as it was: say that the new one did not come
            return false;
        });
    }
    setHistoryReloader(reloadHistory);

    // A skipped request put back in the queue ("Cofnij", "↩ Przywróć") or the history cleared: a history that has been loaded
    // shows what is left of it
    on(EVENTS.HISTORY_CHANGED, function () {
        if (historyContent.querySelector('[data-list]')) reloadHistory(null, null);
    });

    // Panel: the queue shows (and the history goes away) and the page brings it into view.
    links.panel.addEventListener('click', function(e) {
        e.preventDefault();
        if (activeList === 'history') {
            queueContent.hidden = false;
            historyContent.hidden = true;
            activeList = 'queue';
            document.body.classList.remove('s2p-history-shown');
        }
        setLit('panel');
        revealContent(queueContent);
    });

    // History: the history is loaded into the page and shown in place of the queue, and the page scrolls to it (also when
    // it shows already: the DJ may be deep in it, or looking at the settings above it).
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
            document.body.classList.add('s2p-history-shown');
            setLit('history');
            // Init sorting on dynamically loaded history table
            initSortableHeaders(historyContent);
            reopenCards(historyContent);
            revealContent(historyContent);
            hideRefused('load');
        })
        .catch(function(err) {
            console.error('[Tabs] History load error:', err);
            showRefused('load');   // the queue stays: the tab would otherwise do nothing at all (review 2026-10-14)
        });
    });
})();
