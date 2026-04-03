/**
 * Dashboard Core JS — handles all DJ panel interactions.
 *
 * Responsibilities:
 *   - AJAX form submissions (preserves YouTube player on POST actions)
 *   - AJAX tab switching (Queue ↔ History without page reload)
 *   - Table polling (refreshes queue every 3 seconds)
 *   - Clipboard (copy party link)
 *
 * Dependencies (DOM):
 *   - <meta name="_csrf">          CSRF token
 *   - <meta name="_csrf_header">   CSRF header name
 *   - <input id="partyCode">       current party code
 *   - <div id="yt-player-card">    presence = YouTube provider
 *   - <tbody id="song-list">       queue table body
 *   - <div id="queue-content">     queue section wrapper
 *   - <div id="history-content">   AJAX-loaded history container
 */

// ==========================================================================
// HELPERS
// ==========================================================================

/** CSRF token and header — read once from <meta> tags, reused everywhere. */
const _csrf = {
    token:  document.querySelector('meta[name="_csrf"]').getAttribute('content'),
    header: document.querySelector('meta[name="_csrf_header"]').getAttribute('content')
};

/** Returns the cached CSRF object. */
function getCsrf() {
    return _csrf;
}

/** True when the YouTube embedded player is present on the page. */
function isYouTubeProvider() {
    return !!document.getElementById('yt-player-card');
}

// ==========================================================================
// AJAX FORM INTERCEPTOR (YouTube only)
//
// When YouTube provider is active, most POST forms on the dashboard
// are submitted via fetch() to avoid a full page reload that would
// destroy the YouTube IFrame player.
//
// Excluded: logout, delete-account (page reload / redirect is expected).
// ==========================================================================

(function initAjaxFormInterceptor() {
    if (!isYouTubeProvider()) return;

    document.addEventListener('submit', function(e) {
        const form = e.target.closest('form');
        if (!form || form.method.toLowerCase() !== 'post') return;
        if (!form.closest('.container')) return;

        // Respect confirm() dialogs — if onsubmit returned false, the browser
        // called preventDefault(). The event still bubbles, so we must check.
        if (e.defaultPrevented) return;

        // Allow these actions to do a full page reload
        const action = form.action || '';
        if (action.includes('/logout') || action.includes('/delete-account')) return;

        // Auto-Pilot toggle has its own AJAX handler — skip
        if (form.id === 'autoPilotForm') return;

        e.preventDefault();
        const csrf = getCsrf();

        fetch(action, {
            method: 'POST',
            headers: { [csrf.header]: csrf.token },
            body: new FormData(form),
            redirect: 'manual'
        }).then(function() {
            // --- Party state toggle (end/start party) ---
            if (action.includes('/end-party') || action.includes('/start-party')) {
                const isEnding = action.includes('/end-party');
                const banner  = document.getElementById('party-closed-banner');
                const endForm = document.getElementById('end-party-form');
                if (banner)  banner.classList.toggle('d-none', !isEnding);
                if (endForm) endForm.classList.toggle('d-none', isEnding);
                return; // no flash needed for these buttons
            }

            // Flash the submit button green briefly as confirmation
            const btn = form.querySelector('button[type="submit"]');
            if (btn) {
                const original = btn.textContent;
                btn.textContent = '✓';
                btn.classList.add('btn-success');
                setTimeout(function() {
                    btn.textContent = original;
                    btn.classList.remove('btn-success');
                }, 1500);
            }
            // Clear "add item" forms after successful submission
            if (form.classList.contains('reset-on-success')) {
                form.reset();
            }
        }).catch(function(err) {
            console.error('[Dashboard] AJAX form error:', err);
        });
    });
})();

// ==========================================================================
// AUTO-PILOT TOGGLE
//
// Called from: <input onchange="submitAutoPilotToggle(this)">
// YouTube: AJAX POST + local UI update (no reload).
// Spotify: standard form submit (page reload is fine).
// ==========================================================================

function submitAutoPilotToggle(checkbox) {
    if (!isYouTubeProvider()) {
        checkbox.form.submit();
        return;
    }

    const csrf = getCsrf();
    const formData = new FormData(checkbox.form);

    fetch('/dj/dashboard/playback-mode', {
        method: 'POST',
        headers: { [csrf.header]: csrf.token },
        body: formData,
        redirect: 'manual'
    }).then(function() {
        const tbody = document.getElementById('song-list');
        if (tbody) {
            const newMode = checkbox.checked ? 'AUTO' : 'MANUAL';
            tbody.setAttribute('data-playback-mode', newMode);
            console.log('[Auto-Pilot] Mode toggled to: ' + newMode);
            if (newMode === 'AUTO' && typeof checkYouTubeAutoPlay === 'function') {
                checkYouTubeAutoPlay();
            }
        }
    }).catch(function(err) {
        console.error('[Auto-Pilot] Toggle error:', err);
        checkbox.checked = !checkbox.checked;
    });
}

// ==========================================================================
// AJAX TAB SWITCHING (YouTube only)
//
// Loads History tab content via AJAX into #history-content,
// toggling visibility with #queue-content.
// YouTube player stays alive across tab switches.
// When Spotify is the provider, normal page navigation is used.
// ==========================================================================

(function initTabSwitching() {
    if (!isYouTubeProvider()) return;

    const historyLink = document.querySelector('a[href="/dj/history-view"]');
    const queueLink   = document.querySelector('a[href="/dj/dashboard"]');
    if (!historyLink || !queueLink) return;

    const queueContent   = document.getElementById('queue-content');
    const historyContent = document.getElementById('history-content');
    let activeTab = 'queue';

    historyLink.addEventListener('click', function(e) {
        e.preventDefault();
        if (activeTab === 'history') return;

        const partyCode = document.getElementById('partyCode').value;
        const csrf = getCsrf();

        fetch('/dj/history-view/fragment?partyCode=' + encodeURIComponent(partyCode), {
            headers: { [csrf.header]: csrf.token }
        })
        .then(function(r) { return r.text(); })
        .then(function(html) {
            historyContent.innerHTML = html;
            queueContent.style.display = 'none';
            historyContent.style.display = '';
            historyLink.classList.add('active');
            queueLink.classList.remove('active');
            activeTab = 'history';
            // Init sorting on dynamically loaded history table
            if (typeof window.initSortableHeaders === 'function') {
                window.initSortableHeaders(historyContent);
            }
        })
        .catch(function(err) { console.error('[Tabs] History load error:', err); });
    });

    queueLink.addEventListener('click', function(e) {
        e.preventDefault();
        if (activeTab === 'queue') return;
        queueContent.style.display = '';
        historyContent.style.display = 'none';
        queueLink.classList.add('active');
        historyLink.classList.remove('active');
        activeTab = 'queue';
    });
})();

// ==========================================================================
// TABLE POLLING — refreshes the queue table every 3 seconds
//
// Uses ETag / 304 Not Modified to skip DOM replacement when the queue
// hasn't changed. This preserves client-side sorting and reduces bandwidth.
// ==========================================================================

(function initPolling() {
    let currentETag = null;

    async function refreshTable() {
        try {
            const partyCodeEl = document.getElementById('partyCode');
            if (!partyCodeEl) return;

            const csrf = getCsrf();
            const headers = { [csrf.header]: csrf.token };
            if (currentETag) {
                headers['If-None-Match'] = currentETag;
            }

            const response = await fetch('/dj/dashboard/updates?partyCode=' + partyCodeEl.value, {
                method: 'GET',
                headers: headers
            });

            // 304 Not Modified — queue unchanged, skip DOM replacement
            if (response.status === 304) {
                return;
            }

            if (response.redirected) {
                window.location.reload();
                return;
            }
            if (!response.ok) throw new Error('HTTP ' + response.status);

            // Store new ETag for next poll
            const newETag = response.headers.get('ETag');
            if (newETag) {
                currentETag = newETag;
            }

            const html = await response.text();

            // Use <table> as temp container — <div>.innerHTML strips tbody/tr/td
            const tempTable = document.createElement('table');
            tempTable.innerHTML = html;
            const newTbody = tempTable.querySelector('#song-list');
            if (newTbody) {
                document.getElementById('song-list').replaceWith(newTbody);
            } else {
                document.getElementById('song-list').innerHTML = html;
            }

            if (typeof checkYouTubeAutoPlay === 'function') {
                checkYouTubeAutoPlay();
            }

            // Re-apply user's sort preference after table refresh
            if (typeof window.reapplySort === 'function') {
                window.reapplySort();
            }
        } catch (err) {
            console.error('[Polling] Refresh error:', err);
        } finally {
            setTimeout(refreshTable, 3000);
        }
    }

    setTimeout(refreshTable, 3000);
})();

// ==========================================================================
// COPY PARTY LINK
// ==========================================================================

function copyPartyLink() {
    const input = document.getElementById('partyLinkInput');
    navigator.clipboard.writeText(input.value).then(function() {
        const btn = input.nextElementSibling;
        const original = btn.innerText;
        const copied = btn.getAttribute('data-copied') || 'Copied!';
        btn.innerText = copied;
        setTimeout(function() { btn.innerText = original; }, 2000);
    }).catch(function() {
        // Fallback for non-HTTPS or denied permissions
        input.select();
        input.setSelectionRange(0, 99999);
        document.execCommand('copy');
    });
}

// ==========================================================================
// TABLE SORTING — persistent across polling refreshes
//
// How it works:
//   - Each sortable <th> has a data-sort attribute (e.g. "time", "song", "energy")
//   - Each sortable <td> has data-sort-value + data-val with the raw value
//   - Clicking a header toggles ASC → DESC → (reset to default server order)
//   - Sort state for the queue table is remembered and re-applied after
//     each polling cycle (3s) via window.reapplySort()
//   - History table (AJAX loaded) gets fresh click handlers via
//     window.initSortableHeaders()
// ==========================================================================

(function initTableSorting() {
    'use strict';

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

    /**
     * Updates the visual state of <th> sort indicators within a <thead>.
     */
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
     * Used for the initial page load and for dynamically loaded history content.
     */
    window.initSortableHeaders = function(container) {
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
    };

    /**
     * Re-applies the remembered queue sort after each polling refresh.
     * Called from the polling function after #song-list is replaced.
     */
    window.reapplySort = function() {
        if (!queueSortColumn || !queueSortDir) return;
        const tbody = document.getElementById('song-list');
        if (!tbody) return;
        sortTbody(tbody, queueSortColumn, queueSortDir);

        // Re-apply header indicators (thead is NOT replaced by polling)
        const thead = tbody.closest('table') && tbody.closest('table').querySelector('thead');
        if (thead) {
            updateHeaderIndicators(thead, queueSortColumn, queueSortDir);
        }
    };

    // --- Initialize on page load ---
    const queueContent = document.getElementById('queue-content');
    if (queueContent) {
        window.initSortableHeaders(queueContent);
    }
})();

