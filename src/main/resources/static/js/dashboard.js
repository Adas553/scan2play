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
 *   - <div id="fallbackQueue">     "up next" list of the fallback playlist (YouTube only)
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
        }).then(function(response) {
            // --- Party state toggle (end/start party) ---
            if (action.includes('/end-party') || action.includes('/start-party')) {
                const isEnding = action.includes('/end-party');
                const banner  = document.getElementById('party-closed-banner');
                const endForm = document.getElementById('end-party-form');
                if (banner)  banner.classList.toggle('d-none', !isEnding);
                if (endForm) endForm.classList.toggle('d-none', isEnding);
                return; // no flash needed for these buttons
            }

            // --- Fallback playlist save ---
            if (action.includes('/fallback-playlist')) {
                // Server returns the extracted playlist/video ID in X-Fallback-Id header
                // so we don't need to duplicate the URL parsing logic client-side.
                const extractedId = response.headers.get('X-Fallback-Id') || '';
                if (typeof window.updateFallbackSource === 'function') {
                    window.updateFallbackSource();
                }
                refreshFallbackQueue();
                // Show/hide the stop button based on whether an ID was extracted
                const stopBtn = document.getElementById('fallbackStopBtn');
                if (stopBtn) {
                    stopBtn.classList.toggle('d-none', !extractedId);
                }
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

    /** The history fragment: the last page of requests, or — "Show more" — the last {@code limit} of them. */
    function fetchHistory(limit) {
        const partyCode = document.getElementById('partyCode').value;
        const csrf = getCsrf();
        let url = '/dj/history-view/fragment?partyCode=' + encodeURIComponent(partyCode);
        if (limit) url += '&limit=' + encodeURIComponent(limit);
        return fetch(url, { headers: { [csrf.header]: csrf.token } })
            .then(function(r) {
                // A redirect means the session expired (the login page would come back as 200 OK).
                if (!r.ok || r.redirected) throw new Error('HTTP ' + r.status);
                return r.text();
            });
    }

    /**
     * "Show more" of the History tab: asks for a longer history and puts it in place, keeping what the DJ had set up —
     * the search text, the Played / Rejected filter and the scroll position. (The standalone history page has no
     * such function; there the button reloads the page — see initListTools.)
     */
    window.reloadHistory = function(limit) {
        const list = historyContent.querySelector('[data-list]');
        const searchInput = list && list.querySelector('[data-list-search]');
        const activeFilter = list && list.querySelector('[data-list-filter].active');
        const state = {
            search: searchInput ? searchInput.value : '',
            filter: activeFilter ? activeFilter.getAttribute('data-list-filter') : 'all'
        };
        const box = historyContent.querySelector('.list-scroll');
        const scrollTop = box ? box.scrollTop : 0;

        return fetchHistory(limit).then(function(html) {
            historyContent.innerHTML = html;
            if (typeof window.initSortableHeaders === 'function') window.initSortableHeaders(historyContent);
            if (typeof window.restoreListState === 'function') {
                window.restoreListState(historyContent.querySelector('[data-list]'), state);
            }
            const newBox = historyContent.querySelector('.list-scroll');
            if (newBox) newBox.scrollTop = scrollTop;
        }).catch(function(err) { console.error('[Tabs] History reload error:', err); });
    };

    historyLink.addEventListener('click', function(e) {
        e.preventDefault();
        if (activeTab === 'history') return;

        fetchHistory()
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

            // The rows are new: apply the search text again, and update the number of songs in the heading
            if (typeof window.applyListFilters === 'function') {
                window.applyListFilters(document.getElementById('queueList'));
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
// FALLBACK PLAYLIST — "Up next" list
//
// The server renders the list (GET /dj/dashboard/fallback-queue returns an HTML fragment) and it is dropped
// into #fallbackQueue. It is refreshed whenever it may have changed: on page load, after the playlist is saved
// or cleared, after the shuffle switch, after the DJ moves a track, and by youtube-autopilot.js each time the
// player takes the next background track. A track is moved with the buttons in the list (data-move = TOP / UP / DOWN)
// or by dragging its row.
// ==========================================================================

let fallbackQueueRequest = 0;
let fallbackMoveInFlight = false;
let fallbackQueueDragging = false;        // a row is being dragged: the list must not be replaced under the DJ's hand
let fallbackQueueRefreshPending = false;  // a refresh asked for meanwhile is done when the drag ends

function refreshFallbackQueue() {
    const box = document.getElementById('fallbackQueue');
    const partyCode = document.getElementById('partyCode');
    if (!box || !partyCode) return Promise.resolve();
    if (fallbackQueueDragging) {
        fallbackQueueRefreshPending = true;
        return Promise.resolve();
    }

    const request = ++fallbackQueueRequest;
    let version = null;
    return fetch('/dj/dashboard/fallback-queue?partyCode=' + encodeURIComponent(partyCode.value))
        .then(function(response) {
            // A redirect means the session expired (the login page would come back as 200 OK).
            if (!response.ok || response.redirected) throw new Error('HTTP ' + response.status);
            version = response.headers.get('X-Queue-Version');
            return response.text();
        })
        .then(function(html) {
            // Only the answer of the newest request is shown — an older one may describe a state that is gone.
            if (request !== fallbackQueueRequest) return;
            if (fallbackQueueDragging) {   // a drag started while the answer was on its way
                fallbackQueueRefreshPending = true;
                return;
            }
            // The list is up to date as of this version: the player script (which learns from the server when the
            // list changes in another window) then does not fetch it again for the same state.
            if (version && typeof window.setKnownQueueVersion === 'function') window.setKnownQueueVersion(version);
            // The list scrolls: keep where the DJ has scrolled to instead of jumping back to the top.
            const oldList = box.querySelector('ol');
            const scrollTop = oldList ? oldList.scrollTop : 0;
            box.innerHTML = html;
            const newList = box.querySelector('ol');
            if (newList) newList.scrollTop = scrollTop;
        })
        .catch(function(err) {
            console.error('[Dashboard] Up-next refresh error:', err);
        });
}

/**
 * Sends a change of the queue (a move by button, or a drop) and refreshes the list afterwards — also when the server
 * refused (409: the player has just taken that track), so it shows what really is queued. Ignored while a change is
 * still on its way.
 */
function changeFallbackQueue(action, fields, trackId) {
    const partyCode = document.getElementById('partyCode');
    if (!partyCode || fallbackMoveInFlight) return Promise.resolve();
    fallbackMoveInFlight = true;

    const csrf = getCsrf();
    const fd = new FormData();
    fd.append('partyCode', partyCode.value);
    Object.keys(fields).forEach(function(name) {
        if (fields[name] !== null && fields[name] !== '') fd.append(name, fields[name]);
    });
    return fetch('/dj/dashboard/fallback-queue/' + action, {
        method: 'POST',
        headers: { [csrf.header]: csrf.token },
        body: fd
    }).catch(function(err) {
        console.error('[Dashboard] Up-next change error:', err);
    }).then(refreshFallbackQueue).then(function() {
        revealFallbackTrack(trackId);
    }).finally(function() {
        fallbackMoveInFlight = false;
    });
}

/** The DJ moves a track with a button: TOP = play it next, UP / DOWN = one place. */
function moveFallbackTrack(trackId, direction) {
    return changeFallbackQueue('move', { trackId: trackId, direction: direction }, trackId);
}

/** The DJ dropped a dragged track in front of another one (beforeTrackId), or at the end of the list (empty). */
function placeFallbackTrack(trackId, beforeTrackId) {
    // The row has already moved on screen; if the change cannot be sent now, put the list back as the server has it.
    if (fallbackMoveInFlight) return refreshFallbackQueue();
    return changeFallbackQueue('place', { trackId: trackId, beforeTrackId: beforeTrackId }, trackId);
}

/** Keeps the moved track in view inside the scrolling list (without scrolling the page) and flashes it briefly. */
function revealFallbackTrack(trackId) {
    const row = document.querySelector('#fallbackQueue li[data-track-id="' + trackId + '"]');
    if (!row) return;
    const list = row.parentElement;
    const offset = row.getBoundingClientRect().top - list.getBoundingClientRect().top;
    if (offset < 0) {
        list.scrollTop += offset;
    } else if (offset + row.offsetHeight > list.clientHeight) {
        list.scrollTop += offset + row.offsetHeight - list.clientHeight;
    }
    row.classList.add('border-success');
    setTimeout(function() { row.classList.remove('border-success'); }, 1500);
}

(function initFallbackQueueButtons() {
    const box = document.getElementById('fallbackQueue');
    if (!box) return;
    // The list is replaced on every refresh, so the clicks are caught on the container that stays.
    box.addEventListener('click', function(e) {
        const button = e.target.closest('button[data-move]');
        if (!button || button.disabled) return;
        moveFallbackTrack(button.closest('li').getAttribute('data-track-id'), button.getAttribute('data-move'));
    });
})();

/*
 * Dragging a track to a new place: press on a row and drag it (mouse), or press and hold ~0.4 s and then drag
 * (touch screen — a finger that moves earlier is scrolling). The row itself moves through the list while the pointer
 * passes the middle of the other rows, and the list scrolls when the pointer is near its top or bottom edge. On drop,
 * the server is told which track now follows the dragged one; Esc cancels. The buttons and the link to YouTube keep
 * working (a press on them does not start a drag). While a row is dragged the list is not refreshed.
 */
(function initFallbackQueueDrag() {
    const box = document.getElementById('fallbackQueue');
    if (!box) return;

    const LONG_PRESS_MS = 400;
    const MOVE_THRESHOLD = 6;   // px: further than this a mouse press becomes a drag, and a touch turns out to be a scroll
    const EDGE = 36;            // px from the top / bottom edge of the list where a drag scrolls it

    let press = null;   // a button or finger is down on a row, but the drag has not started (yet)
    let drag = null;    // the drag in progress: { row, list, nextAtStart, kind, x, y, frame, scrolling }

    function rowOf(target) {
        if (!target || !target.closest || target.closest('button, a')) return null;
        return target.closest('#fallbackQueue ol > li');
    }

    function beginDrag(row, kind, x, y) {
        drag = { row: row, list: row.parentElement, nextAtStart: row.nextElementSibling, kind: kind, x: x, y: y, frame: 0, scrolling: false };
        fallbackQueueDragging = true;
        row.style.setProperty('background-color', '#14532d', 'important');
        row.style.outline = '2px solid #198754';
        document.body.style.cursor = 'grabbing';
        document.addEventListener('keydown', onKeyDown);
        if (kind === 'touch') {
            // The row is moved through the list while it is dragged, so the finger's events are also listened for on it.
            row.addEventListener('touchmove', onTouchMoveWhileDragging, { passive: false });
            row.addEventListener('touchend', onTouchEnd);
            row.addEventListener('touchcancel', onTouchCancel);
            document.addEventListener('touchmove', onTouchMoveWhileDragging, { passive: false });
            if (navigator.vibrate) navigator.vibrate(15);
        }
    }

    function schedule() {
        if (!drag || drag.frame) return;
        drag.frame = requestAnimationFrame(function() {
            if (!drag) return;
            drag.frame = 0;
            autoScroll();
            placeRow();
            if (drag && drag.scrolling) schedule();   // keep scrolling while the pointer rests near the edge
        });
    }

    function autoScroll() {
        const rect = drag.list.getBoundingClientRect();
        let step = 0;
        if (drag.y < rect.top + EDGE) {
            step = -Math.ceil((rect.top + EDGE - drag.y) / 3);
        } else if (drag.y > rect.bottom - EDGE) {
            step = Math.ceil((drag.y - (rect.bottom - EDGE)) / 3);
        }
        const before = drag.list.scrollTop;
        if (step) drag.list.scrollTop = before + step;
        drag.scrolling = step !== 0 && drag.list.scrollTop !== before;
    }

    // The row moves past a neighbour only when the pointer crosses that neighbour's middle in the direction of travel,
    // so rows of different heights cannot make it jump back and forth.
    function placeRow() {
        const under = document.elementFromPoint(drag.x, drag.y);
        const over = under && under.closest ? under.closest('#fallbackQueue ol > li') : null;
        if (!over || over === drag.row || over.parentElement !== drag.list) return;
        const rect = over.getBoundingClientRect();
        const middle = rect.top + rect.height / 2;
        const movingDown = !!(drag.row.compareDocumentPosition(over) & Node.DOCUMENT_POSITION_FOLLOWING);
        if (movingDown && drag.y > middle) {
            drag.list.insertBefore(drag.row, over.nextElementSibling);
        } else if (!movingDown && drag.y < middle) {
            drag.list.insertBefore(drag.row, over);
        }
    }

    function endDrag(commit) {
        if (!drag) return;
        const d = drag;
        drag = null;
        if (d.frame) cancelAnimationFrame(d.frame);
        document.removeEventListener('keydown', onKeyDown);
        document.removeEventListener('touchmove', onTouchMoveWhileDragging);
        d.row.removeEventListener('touchmove', onTouchMoveWhileDragging);
        d.row.removeEventListener('touchend', onTouchEnd);
        d.row.removeEventListener('touchcancel', onTouchCancel);
        document.body.style.cursor = '';
        d.row.style.removeProperty('background-color');
        d.row.style.outline = '';
        fallbackQueueDragging = false;

        const next = d.row.nextElementSibling;
        if (commit && next !== d.nextAtStart) {
            fallbackQueueRefreshPending = false;   // the drop refreshes the list itself
            placeFallbackTrack(d.row.getAttribute('data-track-id'), next ? next.getAttribute('data-track-id') : '');
            return;
        }
        if (next !== d.nextAtStart) d.list.insertBefore(d.row, d.nextAtStart);   // cancelled: put the row back
        if (fallbackQueueRefreshPending) {
            fallbackQueueRefreshPending = false;
            refreshFallbackQueue();
        }
    }

    function onKeyDown(e) {
        if (e.key === 'Escape') endDrag(false);
    }

    function onTouchMoveWhileDragging(e) {
        if (!drag || drag.kind !== 'touch') return;
        e.preventDefault();   // the finger drags the row instead of scrolling the page
        const touch = e.touches[0];
        drag.x = touch.clientX;
        drag.y = touch.clientY;
        schedule();
    }

    function onTouchEnd() {
        cancelPress();
        if (drag && drag.kind === 'touch') endDrag(true);
    }

    function onTouchCancel() {
        cancelPress();
        if (drag && drag.kind === 'touch') endDrag(false);
    }

    function cancelPress() {
        if (press && press.kind === 'touch') clearTimeout(press.timer);
        press = null;
    }

    // ---- mouse ----
    box.addEventListener('mousedown', function(e) {
        if (e.button !== 0 || drag || press) return;
        const row = rowOf(e.target);
        if (row) press = { row: row, kind: 'mouse', x: e.clientX, y: e.clientY };
    });
    document.addEventListener('mousemove', function(e) {
        if (drag && drag.kind === 'mouse') {
            if ((e.buttons & 1) === 0) {   // the button was released outside the window
                endDrag(true);
                return;
            }
            drag.x = e.clientX;
            drag.y = e.clientY;
            e.preventDefault();
            schedule();
        } else if (press && press.kind === 'mouse'
                && Math.hypot(e.clientX - press.x, e.clientY - press.y) > MOVE_THRESHOLD) {
            const row = press.row;
            press = null;
            beginDrag(row, 'mouse', e.clientX, e.clientY);
        }
    });
    document.addEventListener('mouseup', function() {
        if (press && press.kind === 'mouse') press = null;
        if (drag && drag.kind === 'mouse') endDrag(true);
    });

    // ---- touch: press and hold, then drag ----
    box.addEventListener('touchstart', function(e) {
        if (drag || press || e.touches.length !== 1) return;
        const row = rowOf(e.target);
        if (!row) return;
        const touch = e.touches[0];
        const pending = { row: row, kind: 'touch', x: touch.clientX, y: touch.clientY, timer: 0 };
        pending.timer = setTimeout(function() {
            if (press !== pending) return;
            press = null;
            beginDrag(pending.row, 'touch', pending.x, pending.y);
        }, LONG_PRESS_MS);
        press = pending;
    }, { passive: true });
    box.addEventListener('touchmove', function(e) {
        // A finger that moves before the press is long enough is scrolling the list or the page: no drag.
        if (!press || press.kind !== 'touch') return;
        const touch = e.touches[0];
        if (Math.hypot(touch.clientX - press.x, touch.clientY - press.y) > MOVE_THRESHOLD) cancelPress();
    }, { passive: true });
    document.addEventListener('touchend', onTouchEnd);
    document.addEventListener('touchcancel', onTouchCancel);

    // No browser drag-and-drop of links or selections, and no context menu from a long press on a row.
    box.addEventListener('dragstart', function(e) { e.preventDefault(); });
    box.addEventListener('contextmenu', function(e) {
        if (press || drag) e.preventDefault();
    });
})();

if (isYouTubeProvider()) {
    refreshFallbackQueue();
}

// ==========================================================================
// FALLBACK PLAYLIST — Stop button handler
//
// Clears the fallback URL server-side, stops local playback, and resets UI.
// ==========================================================================

function stopFallbackPlaylist() {
    // Stop local playback — only after the server has dropped the playlist: Auto-Pilot asks the
    // server for the next track whenever the player is idle, so stopping first could let it start
    // another background track right away.
    const stopPlayback = function() {
        if (typeof window.stopFallback === 'function') {
            window.stopFallback();
        }
    };

    // Clear the input field
    const input = document.getElementById('fallbackInput');
    if (input) input.value = '';

    // Hide the stop button
    const stopBtn = document.getElementById('fallbackStopBtn');
    if (stopBtn) stopBtn.classList.add('d-none');

    // Clear server-side via AJAX POST
    const partyCode = document.getElementById('partyCode');
    if (partyCode) {
        const csrf = getCsrf();
        const fd = new FormData();
        fd.append('partyCode', partyCode.value);
        fd.append('fallbackPlaylistUrl', '');
        fetch('/dj/dashboard/fallback-playlist', {
            method: 'POST',
            headers: { [csrf.header]: csrf.token },
            body: fd
        }).catch(function(err) {
            console.error('[Dashboard] Fallback clear error:', err);
        }).then(stopPlayback).then(refreshFallbackQueue);
    } else {
        stopPlayback();
        refreshFallbackQueue();
    }
}

// ==========================================================================
// FALLBACK PLAYLIST — Shuffle toggle handler
//
// Toggles shuffle on/off server-side. The server re-orders the tracks still to play, so the player
// needs no update — it just takes the next one — and the "up next" list is refreshed to show the new order.
// If the DJ has moved tracks by hand (the list carries data-manual="true") the switch asks first: the new
// order would throw those moves away.
// ==========================================================================

function toggleFallbackShuffle() {
    const checkbox = document.getElementById('fallbackShuffleToggle');
    if (!checkbox) return;

    const partyCode = document.getElementById('partyCode');
    if (!partyCode) return;

    // A new order replaces whatever the DJ moved by hand — say so first, and let them back out.
    if (document.querySelector('#fallbackQueue [data-manual="true"]')
            && !window.confirm(checkbox.getAttribute('data-confirm') || '')) {
        checkbox.checked = !checkbox.checked; // the switch has already flipped: put it back
        return;
    }

    const csrf = getCsrf();
    const fd = new FormData();
    fd.append('partyCode', partyCode.value);

    fetch('/dj/dashboard/fallback-shuffle', {
        method: 'POST',
        headers: { [csrf.header]: csrf.token },
        body: fd
    }).then(function(response) {
        if (response.ok) {
            const newState = response.headers.get('X-Fallback-Shuffle') === 'true';
            checkbox.checked = newState;
            // The server re-ordered the tracks still to play — show the new order.
            refreshFallbackQueue();
        }
    }).catch(function(err) {
        console.error('[Dashboard] Fallback shuffle toggle error:', err);
        // Revert checkbox on error
        checkbox.checked = !checkbox.checked;
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

// ==========================================================================
// LONG LISTS — search, filter and "Show more" (the active queue and the history)
//
// A list is any element with data-list. Inside it: an input [data-list-search], buttons [data-list-filter="all" |
// "played" | "rejected"] (the chosen one has class "active"), a count [data-list-count] (its data-suffix is appended:
// the history's "+" says that older requests exist) and the rows tbody tr[data-song-name] (history rows also carry
// data-decision); tr[data-nomatch] is the "nothing matches" row. Filtering hides rows with class d-none. The queue's
// <tbody> is replaced by every poll, so applyListFilters(list) is called again after each refresh. The listeners are
// delegated, so a list that arrives by AJAX (the History tab) needs no set-up.
// ==========================================================================

(function initListTools() {
    'use strict';

    // Lower case and without accents, so that "zolc" finds "Żółć" (ł has no accent to strip, hence the extra replace)
    const COMBINING_MARKS = new RegExp('[' + String.fromCharCode(0x300) + '-' + String.fromCharCode(0x36f) + ']', 'g');
    function normalize(text) {
        return (text || '').toLowerCase().normalize('NFD').replace(COMBINING_MARKS, '').replace(/ł/g, 'l');
    }

    function applyFilters(list) {
        if (!list) return;
        const searchInput = list.querySelector('[data-list-search]');
        const needle = normalize(searchInput ? searchInput.value.trim() : '');
        const activeFilter = list.querySelector('[data-list-filter].active');
        const decision = activeFilter ? activeFilter.getAttribute('data-list-filter') : 'all';
        const filtering = needle !== '' || decision !== 'all';

        const rows = list.querySelectorAll('tbody tr[data-song-name]');
        let shown = 0;
        for (let i = 0; i < rows.length; i++) {
            const visible = (needle === '' || normalize(rows[i].getAttribute('data-song-name')).indexOf(needle) >= 0)
                && (decision === 'all' || rows[i].getAttribute('data-decision') === decision);
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

    window.applyListFilters = applyFilters;

    /** Puts a list back in a state saved earlier ({search, filter}) — after it was replaced by a freshly loaded one. */
    window.restoreListState = function(list, state) {
        if (!list || !state) return;
        const searchInput = list.querySelector('[data-list-search]');
        if (searchInput) searchInput.value = state.search || '';
        const buttons = list.querySelectorAll('[data-list-filter]');
        for (let i = 0; i < buttons.length; i++) {
            buttons[i].classList.toggle('active', buttons[i].getAttribute('data-list-filter') === (state.filter || 'all'));
        }
        applyFilters(list);
    };

    document.addEventListener('input', function(e) {
        const input = e.target.closest ? e.target.closest('[data-list-search]') : null;
        if (input) applyFilters(input.closest('[data-list]'));
    });

    document.addEventListener('click', function(e) {
        const filter = e.target.closest('[data-list-filter]');
        if (filter) {
            const list = filter.closest('[data-list]');
            const buttons = list.querySelectorAll('[data-list-filter]');
            for (let i = 0; i < buttons.length; i++) {
                buttons[i].classList.toggle('active', buttons[i] === filter);
            }
            applyFilters(list);
            return;
        }

        const more = e.target.closest('[data-history-more]');
        if (more) {
            const limit = more.getAttribute('data-limit');
            if (typeof window.reloadHistory === 'function') {
                more.disabled = true;               // one request at a time
                window.reloadHistory(limit).then(function() { more.disabled = false; }); // the History tab: replace in place
            } else {
                window.location.href = '/dj/history-view?limit=' + encodeURIComponent(limit); // the standalone page
            }
        }
    });
})();

