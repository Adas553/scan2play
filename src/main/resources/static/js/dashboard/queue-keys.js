/**
 * "KOPIUJ" AND THE KEYBOARD — for the DJ at the laptop, whose hands are on the controller, not the mouse. "Kopiuj" puts the song on
 * the clipboard for the search box of the DJ's own software (Serato, VirtualDJ, rekordbox, Spotify / Tidal in them): the artist and
 * the title without the " - " between them ("Wilki Baśka") — those searches look for words. The queue's keys:
 *
 *   ↑ / ↓   choose a request (the first press: the first one in the list as it is shown — sorted, searched)
 *   C       copy the chosen song (none chosen: the first one, which is chosen then)
 *   Enter   "Zagrane" of the chosen request      S   "Pomiń" of it      (none chosen: the first one is chosen, nothing is sent)
 *   Esc     none chosen
 *   ⌫       "Cofnij" of the bar after a "Zagrane" / "Pomiń" (forms.js), while it is shown; the request put back is chosen again
 *
 * On a computer a click on a request chooses it too (its buttons included: "Kopiuj" chooses what it copied); on a phone a tap is the
 * card's fold (cards.js), no frame.
 *
 * Only while the Queue's list is shown, never while typing (the search box, a form's field), with Ctrl / Alt / ⌘ (Ctrl+C stays the
 * browser's) or with a window open. Enter on a focused button or link is that control's. A request sent with Enter / S is sent once:
 * a second press before the poll takes it away does nothing. The chosen request is remembered by its id when the poll replaces the
 * rows (keepChosen, polling.js); once it has left (played, skipped) the request now in its place is chosen — the next one.
 */

const ROWS = '#song-list > tr[data-song-id]';
const TYPING = 'input, textarea, select, [contenteditable]';
const COPIED_MS = 1500;
const WIDE = '(min-width: 768px)';

let chosenId = null;
let chosenIndex = 0;

/** The words a DJ software's search looks for: "Wilki - Baśka" → "Wilki Baśka". */
export function searchText(songName) {
    return (songName || '').replace(/\s+-\s+/g, ' ').replace(/\s+/g, ' ').trim();
}

function shownRows() {
    return Array.prototype.filter.call(document.querySelectorAll(ROWS), function (row) {
        return row.getClientRects().length > 0;
    });
}

function choose(row) {
    document.querySelectorAll(ROWS + '.s2p-chosen').forEach(function (other) {
        if (other !== row) {
            other.classList.remove('s2p-chosen');
            other.removeAttribute('aria-current');
        }
    });
    if (!row) {
        chosenId = null;
        return;
    }
    row.classList.add('s2p-chosen');
    row.setAttribute('aria-current', 'true');
    chosenId = row.getAttribute('data-song-id');
    chosenIndex = shownRows().indexOf(row);
}

/**
 * Scrolls the chosen row into view — below the list's sticky header, which covered a row scrolled to the top, and the first and the
 * last row to the very end of the list, so the scrollbar reaches its end (the owner, 2026-10-12). Only on a key: the poll never moves
 * the list the DJ scrolled with the mouse.
 */
function reveal(row) {
    const box = row.closest('.list-scroll');
    const head = box ? box.querySelector('thead') : null;
    row.style.scrollMarginTop = head ? head.offsetHeight + 'px' : '';
    row.scrollIntoView({ block: 'nearest' });
    if (!box) return;
    const rows = shownRows();
    if (row === rows[0]) box.scrollTop = 0;
    else if (row === rows[rows.length - 1]) box.scrollTop = box.scrollHeight;
}

/** The chosen request's row, or the one now in its place when it has left the list; null when none is chosen. */
function chosen() {
    if (chosenId === null) return null;
    const rows = shownRows();
    const row = rows.find(function (r) { return r.getAttribute('data-song-id') === chosenId; });
    if (row) return row;
    if (rows.length === 0) return null;
    return rows[Math.min(Math.max(chosenIndex, 0), rows.length - 1)];
}

/** The poll replaced the rows: the same request is chosen again, or the one in its place (it was played or skipped meanwhile). */
export function keepChosen() {
    if (chosenId === null) return;
    choose(chosen());
}

function flashCopied(button) {
    if (!button) return;
    clearTimeout(button._s2pCopied);
    if (!button.hasAttribute('data-label')) button.setAttribute('data-label', button.textContent);
    button.textContent = button.getAttribute('data-copied') || 'Copied';
    button._s2pCopied = setTimeout(function () { button.textContent = button.getAttribute('data-label'); }, COPIED_MS);
}

/** The clipboard; without it (plain HTTP, refused) a hidden field selected and copied the old way. */
function copyText(text) {
    if (navigator.clipboard && window.isSecureContext) {
        return navigator.clipboard.writeText(text).catch(function () { return copyTheOldWay(text); });
    }
    return copyTheOldWay(text);
}

function copyTheOldWay(text) {
    const field = document.createElement('textarea');
    field.value = text;
    field.setAttribute('readonly', '');
    field.style.position = 'fixed';
    field.style.opacity = '0';
    document.body.appendChild(field);
    field.select();
    const copied = document.execCommand('copy');
    field.remove();
    return copied ? Promise.resolve() : Promise.reject(new Error('copy refused'));
}

function copySong(row) {
    const button = row.querySelector('.s2p-btn-copy');
    return copyText(searchText(row.getAttribute('data-song-name')))
        .then(function () { flashCopied(button); })
        .catch(function (err) { console.warn('[Dashboard] Copy failed:', err); });
}

/** "Zagrane" / "Pomiń" of the row, once: the button's own form (forms.js sends it). The staff's "Podgląd" has neither. */
function send(row, buttonClass) {
    const button = row.querySelector(buttonClass);
    if (!button || row.hasAttribute('data-key-sent')) return;
    row.setAttribute('data-key-sent', '');
    button.click();
}

document.addEventListener('click', function (e) {
    const row = e.target.closest ? e.target.closest(ROWS) : null;
    if (!row) return;
    if (window.matchMedia(WIDE).matches) choose(row);
    if (e.target.closest('.s2p-btn-copy')) copySong(row);
});

/** ⌫: the bar's "Cofnij" while it is shown — even when the queue is empty, the last request having just left it. */
function undo(e) {
    const bar = document.getElementById('undoSkip');
    const button = bar && !bar.hidden ? bar.querySelector('[data-undo-button]') : null;
    if (!button) return;
    e.preventDefault();
    chosenId = bar.getAttribute('data-undo-id');
    button.click();
}

document.addEventListener('keydown', function (e) {
    if (e.ctrlKey || e.altKey || e.metaKey || e.defaultPrevented || e.isComposing) return;
    if (e.target.closest && e.target.closest(TYPING)) return;
    if (document.querySelector('.modal.show, dialog[open]')) return;
    const key = e.key.length === 1 ? e.key.toLowerCase() : e.key;
    if (key === 'Backspace') {
        undo(e);
        return;
    }
    if (['ArrowDown', 'ArrowUp', 'c', 'Enter', 's', 'Escape'].indexOf(key) < 0) return;
    if (key === 'Enter' && e.target.closest && e.target.closest('a, button')) return;
    const rows = shownRows();
    if (rows.length === 0) return;

    const row = chosen();
    if (key === 'Escape') {
        if (row) choose(null);
        return;
    }
    e.preventDefault();
    if (key === 'ArrowDown' || key === 'ArrowUp') {
        const at = row ? rows.indexOf(row) : -1;
        const next = at < 0 ? 0 : at + (key === 'ArrowDown' ? 1 : -1);
        choose(rows[Math.min(Math.max(next, 0), rows.length - 1)]);
        reveal(chosen());
    } else if (!row) {
        choose(rows[0]);
        reveal(rows[0]);
        if (key === 'c') copySong(rows[0]);
    } else if (key === 'c') {
        choose(row);
        reveal(row);
        copySong(row);
    } else {
        choose(row);
        reveal(row);
        send(row, key === 'Enter' ? '.s2p-btn-played' : '.s2p-btn-skip');
    }
});
