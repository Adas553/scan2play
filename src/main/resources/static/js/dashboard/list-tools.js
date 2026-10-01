/**
 * The tools of the long lists — the active queue and the history, on the dashboard and on the standalone history page (which loads
 * this module alone): sorting by a column header, the search box, the filter buttons and "Show more".
 *
 * TABLE SORTING — persistent across polling refreshes
 *   - Each sortable <th> has a data-sort attribute (e.g. "time", "song", "energy")
 *   - Each sortable <td> has data-sort-value + data-val with the raw value
 *   - Clicking a header toggles ASC → DESC → (reset to default server order)
 *   - Sort state for the queue table is remembered and re-applied after each polling cycle (3 s) via reapplySort()
 *   - The history table (loaded by AJAX in the History tab) gets its click handlers via initSortableHeaders()
 *
 * LONG LISTS — search, filter and "Show more"
 * A list is any element with data-list. Inside it: an input [data-list-search], buttons [data-list-filter="all" | "guest" |
 * "background" | "played" | "rejected"] (the chosen one has class "active"; the history only), a count [data-list-count] (its
 * data-suffix is appended: the history's "+" says that older requests exist) and the rows tbody tr[data-song-name];
 * tr[data-nomatch] is the "nothing matches" row. The search hides rows (of what is loaded) with class d-none. The filter buttons
 * do not hide anything: the server reads the entries of the chosen kind, inside its bounded queries, so a filter reaches as far
 * back as the limit allows however many other entries lie between — the list is asked for again (the History tab's reloader, set
 * by tabs.js with setHistoryReloader; a page load on the standalone page). The queue's <tbody> is replaced by every poll, so
 * applyListFilters(list) is called again after each refresh. The listeners are delegated, so a list that arrives by AJAX (the
 * History tab) needs no set-up.
 */

// Sort state for the main queue table (survives polling refreshes)
let queueSortColumn = null;   // e.g. "time", "song", "energy"
let queueSortDir    = null;   // "asc" or "desc"

/**
 * Sorts the rows of a <tbody> by the given column key and direction.
 * Pre-extracts values to avoid DOM queries inside the comparator,
 * and detaches the tbody during reordering to prevent per-row reflows.
 */
function sortTbody(tbody, colKey, direction) {
    if (!tbody || !colKey || !direction) return;

    const isNumeric = (colKey === 'energy');

    // Pre-extract { row, value } pairs — O(N) DOM reads, then pure array sort
    const items = [];
    const allRows = tbody.querySelectorAll('tr');
    for (let i = 0; i < allRows.length; i++) {
        const cell = allRows[i].querySelector('td[data-sort-value="' + colKey + '"]');
        if (!cell) continue; // skip placeholder rows
        const raw = cell.getAttribute('data-val') || '';
        items.push({ row: allRows[i], val: isNumeric ? (parseFloat(raw) || 0) : raw });
    }
    if (items.length < 2) return;

    items.sort(function(a, b) {
        const result = isNumeric
            ? a.val - b.val
            : a.val.localeCompare(b.val, undefined, { sensitivity: 'base' });
        return direction === 'desc' ? -result : result;
    });

    // Detach tbody, reorder, reattach — single reflow instead of N
    const parent = tbody.parentNode;
    const next   = tbody.nextSibling;
    parent.removeChild(tbody);
    for (let j = 0; j < items.length; j++) {
        tbody.appendChild(items[j].row);
    }
    parent.insertBefore(tbody, next);
}

/** Updates the visual state of <th> sort indicators within a <thead>. */
function updateHeaderIndicators(thead, activeCol, activeDir) {
    const ths = thead.querySelectorAll('th[data-sort]');
    for (let i = 0; i < ths.length; i++) {
        ths[i].classList.remove('sort-asc', 'sort-desc');
        if (ths[i].getAttribute('data-sort') === activeCol && activeDir) {
            ths[i].classList.add('sort-' + activeDir);
        }
    }
}

/**
 * Attaches click handlers to all <th data-sort> within a container.
 * Used for the page as it loads and for dynamically loaded history content.
 */
export function initSortableHeaders(container) {
    if (!container) return;
    const ths = container.querySelectorAll('th[data-sort]');
    for (let i = 0; i < ths.length; i++) {
        // Skip if already initialized
        if (ths[i].hasAttribute('data-sort-init')) continue;
        ths[i].setAttribute('data-sort-init', '1');

        ths[i].addEventListener('click', function() {
            const colKey = this.getAttribute('data-sort');
            const table  = this.closest('table');
            const thead  = table.querySelector('thead');
            const tbody  = table.querySelector('tbody');
            const isQueueTable = tbody && tbody.id === 'song-list';

            // Determine current state for this table
            let curCol, curDir;
            if (isQueueTable) {
                curCol = queueSortColumn;
                curDir = queueSortDir;
            } else {
                curCol = thead.getAttribute('data-sort-col');
                curDir = thead.getAttribute('data-sort-dir');
            }

            // Cycle: none → asc → desc → none
            let newDir;
            if (curCol !== colKey) {
                newDir = 'asc';
            } else if (curDir === 'asc') {
                newDir = 'desc';
            } else {
                newDir = null; // reset
            }

            // Store state
            if (isQueueTable) {
                queueSortColumn = newDir ? colKey : null;
                queueSortDir    = newDir;
            } else {
                thead.setAttribute('data-sort-col', newDir ? colKey : '');
                thead.setAttribute('data-sort-dir', newDir || '');
            }

            // Apply
            updateHeaderIndicators(thead, newDir ? colKey : null, newDir);
            if (newDir) {
                sortTbody(tbody, colKey, newDir);
            }
            // When reset (newDir === null), the server order is already
            // the order from the last polling refresh — no action needed
            // (next poll will restore original order).
        });
    }
}

/**
 * Re-applies the remembered queue sort after each polling refresh.
 * Called from the polling (polling.js) after #song-list is replaced.
 */
export function reapplySort() {
    if (!queueSortColumn || !queueSortDir) return;
    const tbody = document.getElementById('song-list');
    if (!tbody) return;
    sortTbody(tbody, queueSortColumn, queueSortDir);

    // Re-apply header indicators (thead is NOT replaced by polling)
    const thead = tbody.closest('table') && tbody.closest('table').querySelector('thead');
    if (thead) {
        updateHeaderIndicators(thead, queueSortColumn, queueSortDir);
    }
}

// Lower case and without accents, so that "zolc" finds "Żółć" (ł has no accent to strip, hence the extra replace)
const COMBINING_MARKS = new RegExp('[' + String.fromCharCode(0x300) + '-' + String.fromCharCode(0x36f) + ']', 'g');
function normalize(text) {
    return (text || '').toLowerCase().normalize('NFD').replace(COMBINING_MARKS, '').replace(/ł/g, 'l');
}

/** The search box: hides the rows (of what is loaded) whose name does not contain the text. The filter buttons are the server's job. */
export function applyListFilters(list) {
    if (!list) return;
    const searchInput = list.querySelector('[data-list-search]');
    const needle = normalize(searchInput ? searchInput.value.trim() : '');
    const filtering = needle !== '';

    const rows = list.querySelectorAll('tbody tr[data-song-name]');
    let shown = 0;
    for (let i = 0; i < rows.length; i++) {
        const visible = needle === '' || normalize(rows[i].getAttribute('data-song-name')).indexOf(needle) >= 0;
        rows[i].classList.toggle('d-none', !visible);
        if (visible) shown++;
    }

    const noMatch = list.querySelector('tr[data-nomatch]');
    if (noMatch) noMatch.classList.toggle('d-none', shown > 0 || rows.length === 0);

    const count = list.querySelector('[data-list-count]');
    if (count) {
        count.textContent = (filtering ? shown + ' / ' + rows.length : String(rows.length))
            + (count.getAttribute('data-suffix') || '');
    }
}

/** Puts a list back in a state saved earlier ({search, filter}) — after it was replaced by a freshly loaded one. */
export function restoreListState(list, state) {
    if (!list || !state) return;
    const searchInput = list.querySelector('[data-list-search]');
    if (searchInput) searchInput.value = state.search || '';
    const buttons = list.querySelectorAll('[data-list-filter]');
    for (let i = 0; i < buttons.length; i++) {
        buttons[i].classList.toggle('active', buttons[i].getAttribute('data-list-filter') === (state.filter || 'all'));
    }
    applyListFilters(list);
}

/**
 * How the History tab of a YouTube dashboard asks for its list again — (limit, filter) → a promise of true (replaced), false
 * (failed) or null (a newer request took over) — set by tabs.js. Null elsewhere (a Spotify dashboard, the standalone history
 * page): there the buttons load the page again.
 */
let historyReloader = null;

export function setHistoryReloader(reload) {
    historyReloader = reload;
}

/** The button of the filter that is chosen in a list ('all' when none is marked). */
function chosenFilter(list) {
    const active = list && list.querySelector('[data-list-filter].active');
    return active ? active.getAttribute('data-list-filter') : 'all';
}

/** The standalone history page: the same list, asked for again with a longer limit and/or another filter. */
function historyPageUrl(limit, filter) {
    let url = '/dj/history-view?';
    if (limit) url += 'limit=' + encodeURIComponent(limit) + '&';
    if (filter && filter !== 'all') url += 'filter=' + encodeURIComponent(filter) + '&';
    return url.replace(/[?&]$/, '');
}

document.addEventListener('input', function(e) {
    const input = e.target.closest ? e.target.closest('[data-list-search]') : null;
    if (input) applyListFilters(input.closest('[data-list]'));
});

document.addEventListener('click', function(e) {
    // A filter button (the history): the server reads the entries of that kind, so the list is asked for again — in
    // place in the History tab (the button lights at once, and goes back if the answer does not come), by a page
    // load on the standalone page.
    const filter = e.target.closest('[data-list-filter]');
    if (filter) {
        const list = filter.closest('[data-list]');
        if (filter.classList.contains('active')) return;
        const value = filter.getAttribute('data-list-filter');
        if (historyReloader) {
            const before = list.querySelector('[data-list-filter].active');
            const buttons = list.querySelectorAll('[data-list-filter]');
            for (let i = 0; i < buttons.length; i++) {
                buttons[i].classList.toggle('active', buttons[i] === filter);
            }
            historyReloader(null, value).then(function(replaced) {
                // The request failed (null: a newer one took over, and its answer decides): if the old list is still
                // the one on the screen, its button lights again
                if (replaced === false && list.isConnected && before) {
                    for (let i = 0; i < buttons.length; i++) {
                        buttons[i].classList.toggle('active', buttons[i] === before);
                    }
                }
            });
        } else {
            window.location.href = historyPageUrl(null, value);
        }
        return;
    }

    // "Show more": a longer history of the kind that is chosen; the search text and the scroll position stay.
    const more = e.target.closest('[data-history-more]');
    if (more) {
        const limit = more.getAttribute('data-limit');
        const chosen = chosenFilter(more.closest('[data-list]'));
        if (historyReloader) {
            more.disabled = true;               // one request at a time
            historyReloader(limit, chosen).then(function() { more.disabled = false; }); // the History tab: replace in place
        } else {
            window.location.href = historyPageUrl(limit, chosen); // the standalone page
        }
    }
});

// The tables on the page as it loads: the queue on the dashboard, the history on the standalone page.
initSortableHeaders(document.body);
