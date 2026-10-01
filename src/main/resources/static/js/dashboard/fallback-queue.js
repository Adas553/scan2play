/**
 * The background (fallback) playlist on the dashboard of a YouTube party: its "up next" list — refreshed, moved by buttons, dragged,
 * a track skipped for the round —, the Stop button, the shuffle switch and what came of an import.
 *
 * The server renders the list (GET /dj/dashboard/fallback-queue returns an HTML fragment) and it is dropped into #fallbackQueue. It
 * is refreshed whenever it may have changed: on page load, after the playlist is saved or cleared, after the shuffle switch, after
 * the DJ moves a track, and when the player says so (EVENTS.FALLBACK_QUEUE_STALE: it took the next background track, or a lease
 * answer brought another version of the list). A track is moved with the buttons in the list (data-move = TOP / UP / DOWN) or by
 * dragging its row, and skipped for this round with its ✕ button (data-skip).
 */
import { EVENTS, emit, on } from './events.js';
import { csrfHeaders, isYouTubeProvider, partyCode } from './common.js';

let fallbackQueueRequest = 0;
let fallbackMoveInFlight = false;
let fallbackQueueDragging = false;        // a row is being dragged: the list must not be replaced under the DJ's hand
let fallbackQueueRefreshPending = false;  // a refresh asked for meanwhile is done when the drag ends

export function refreshFallbackQueue() {
    const box = document.getElementById('fallbackQueue');
    const party = partyCode();
    if (!box || !party) return Promise.resolve();
    if (fallbackQueueDragging) {
        fallbackQueueRefreshPending = true;
        return Promise.resolve();
    }

    const request = ++fallbackQueueRequest;
    let version = null;
    return fetch('/dj/dashboard/fallback-queue?partyCode=' + encodeURIComponent(party))
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
            // The list is up to date as of this version: the player (which learns from the server when the list changes in
            // another window) then does not fetch it again for the same state.
            if (version) emit(EVENTS.FALLBACK_QUEUE_VERSION, { version: version });
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
    const party = partyCode();
    if (!party || fallbackMoveInFlight) return Promise.resolve();
    fallbackMoveInFlight = true;

    const fd = new FormData();
    fd.append('partyCode', party);
    Object.keys(fields).forEach(function(name) {
        if (fields[name] !== null && fields[name] !== '') fd.append(name, fields[name]);
    });
    return fetch('/dj/dashboard/fallback-queue/' + action, {
        method: 'POST',
        headers: csrfHeaders(),
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

/** The DJ skips a track for this round with its ✕ button: it leaves the list and comes back when the playlist starts over. */
function skipFallbackTrack(trackId) {
    return changeFallbackQueue('skip', { trackId: trackId }, trackId);
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
        const skipButton = e.target.closest('button[data-skip]');
        if (skipButton && !skipButton.disabled) {
            skipFallbackTrack(skipButton.closest('li').getAttribute('data-track-id'));
            return;
        }
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

// ---- What came of the import ----
//
// Saving the playlist imports its tracks (best effort, on the server) and the answer says how it went:
// X-Fallback-Import ok|failed, X-Fallback-Tracks (ok) or X-Fallback-Import-Reason (failed: NO_API_KEY, INVALID_PLAYLIST,
// API_ERROR, NO_PLAYABLE_TRACKS, YOUTUBE_MIX — the last one refused before saving, X-Fallback-Saved: false). Without a word about
// it a private or wrong playlist ended in an empty "up next" list. The texts travel in data attributes of #fallbackImportStatus
// (dashboard.html), so the script needs no message bundle of its own.

/**
 * Says under the playlist form how the import went. Returns true when it failed. Nothing is said when the playlist was cleared; a
 * link the server refused without saving (X-Fallback-Saved: false, extractedId null) is still reported.
 */
export function showFallbackImportResult(response, extractedId) {
    const box = document.getElementById('fallbackImportStatus');
    const status = response.headers.get('X-Fallback-Import');
    const refused = response.headers.get('X-Fallback-Saved') === 'false';
    if (!box || (!extractedId && !refused) || !status) {   // no such box, the playlist was cleared, or a server that says nothing
        hideFallbackImportResult();
        return false;
    }
    const failed = status !== 'ok';
    let text;
    if (failed) {
        const reason = (response.headers.get('X-Fallback-Import-Reason') || '').toLowerCase().replace(/_/g, '');
        text = box.dataset['text' + reason.charAt(0).toUpperCase() + reason.slice(1)] || box.dataset.textUnknown;
    } else {
        text = (box.dataset.textOk || '').replace('{0}', response.headers.get('X-Fallback-Tracks') || '0');
    }
    box.textContent = text || '';
    box.classList.toggle('text-danger', failed);
    box.classList.toggle('text-success', !failed);
    box.classList.remove('d-none');
    return failed;
}

function hideFallbackImportResult() {
    const box = document.getElementById('fallbackImportStatus');
    if (!box) return;
    box.classList.add('d-none');
    box.textContent = '';
}

// ---- The Stop button: clears the playlist on the server, stops the background track, resets the form ----

function stopFallbackPlaylist() {
    // Stop local playback — only after the server has dropped the playlist: Auto-Pilot asks the
    // server for the next track whenever the player is idle, so stopping first could let it start
    // another background track right away.
    const stopPlayback = function() {
        emit(EVENTS.FALLBACK_PLAYLIST_CLEARED);
    };

    // Clear the input field
    const input = document.getElementById('fallbackInput');
    if (input) input.value = '';

    // Hide the stop button, and what was said about the last import
    const stopBtn = document.getElementById('fallbackStopBtn');
    if (stopBtn) stopBtn.classList.add('d-none');
    hideFallbackImportResult();

    // Clear server-side via AJAX POST
    const party = partyCode();
    if (party) {
        const fd = new FormData();
        fd.append('partyCode', party);
        fd.append('fallbackPlaylistUrl', '');
        fetch('/dj/dashboard/fallback-playlist', {
            method: 'POST',
            headers: csrfHeaders(),
            body: fd
        }).catch(function(err) {
            console.error('[Dashboard] Fallback clear error:', err);
        }).then(stopPlayback).then(refreshFallbackQueue);
    } else {
        stopPlayback();
        refreshFallbackQueue();
    }
}

// ---- The shuffle switch ----
//
// Toggles shuffle on/off server-side. The server re-orders the tracks still to play, so the player
// needs no update — it just takes the next one — and the "up next" list is refreshed to show the new order.
// If the DJ has moved tracks by hand (the list carries data-manual="true") the switch asks first: the new
// order would throw those moves away.

function toggleFallbackShuffle() {
    const checkbox = document.getElementById('fallbackShuffleToggle');
    if (!checkbox) return;

    const party = partyCode();
    if (!party) return;

    // A new order replaces whatever the DJ moved by hand — say so first, and let them back out.
    if (document.querySelector('#fallbackQueue [data-manual="true"]')
            && !window.confirm(checkbox.getAttribute('data-confirm') || '')) {
        checkbox.checked = !checkbox.checked; // the switch has already flipped: put it back
        return;
    }

    const fd = new FormData();
    fd.append('partyCode', party);

    fetch('/dj/dashboard/fallback-shuffle', {
        method: 'POST',
        headers: csrfHeaders(),
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

const stopButton = document.getElementById('fallbackStopBtn');
if (stopButton) stopButton.addEventListener('click', stopFallbackPlaylist);
const shuffleToggle = document.getElementById('fallbackShuffleToggle');
if (shuffleToggle) shuffleToggle.addEventListener('change', toggleFallbackShuffle);

on(EVENTS.FALLBACK_QUEUE_STALE, refreshFallbackQueue);

if (isYouTubeProvider()) {
    refreshFallbackQueue();
}
