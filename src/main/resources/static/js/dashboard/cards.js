/**
 * THE CARDS OF A PHONE — the queue and the history fold their requests (app.css, narrower than 768 px; the review of 2026-10-10,
 * https://claude.ai/artifact/5Znq2bBpZQZB2rzHYkX9v1). A waiting request shows its song, ⚠ / ⭐, the votes and "▶ Zagrane / ⏭ Pomiń";
 * an entry of the history one line (the song, the votes, the decision, "↩" of a skip). A tap on the card (or its "▾",
 * .s2p-card-toggle — the one a keyboard and a screen reader use) unfolds the rest: the guest's words, the AI's comment, "Podejrzyj",
 * 💸. A tap on a button, a link or a form of the card is that control's alone: it never folds anything, and the fold never sends
 * anything. A wide screen shows everything in the table: nothing to fold there.
 *
 * The queue's <tbody> is replaced by every poll (polling.js) and the history by "Show more", a filter, a skip put back (tabs.js):
 * the cards that were open are remembered by the request's id (data-song-id / data-history-id) and opened again in the new rows
 * (reopenCards). An id whose row is gone (played, skipped) is forgotten, so a request put back comes back folded.
 */

const PHONE = '(max-width: 767.98px)';
const ROW = '#song-list > tr[data-song-id], .s2p-history-table tbody > tr[data-history-id]';
// what a tap belongs to by itself: the card's own controls (the "▾" is handled as the card)
const CONTROL = 'a, button:not(.s2p-card-toggle), form, input, select, textarea, label';

const open = { queue: new Set(), history: new Set() };

function listOf(row) {
    return row.hasAttribute('data-song-id') ? 'queue' : 'history';
}

function idOf(row) {
    return row.getAttribute(row.hasAttribute('data-song-id') ? 'data-song-id' : 'data-history-id');
}

function setOpen(row, isOpen) {
    row.classList.toggle('s2p-open', isOpen);
    const toggle = row.querySelector('.s2p-card-toggle');
    if (toggle) toggle.setAttribute('aria-expanded', isOpen ? 'true' : 'false');
}

/**
 * Opens again, in the rows now inside {@code root}, the cards that were open before the rows were replaced — and forgets the ids
 * that are no longer there (of that list only: the queue and the history are replaced apart).
 */
export function reopenCards(root) {
    if (!root) return;
    const rows = root.querySelectorAll(ROW);
    if (rows.length === 0) return;
    const ids = open[listOf(rows[0])];
    const present = new Set();
    rows.forEach(function (row) {
        const id = idOf(row);
        present.add(id);
        if (ids.has(id)) setOpen(row, true);
    });
    ids.forEach(function (id) { if (!present.has(id)) ids.delete(id); });
}

document.addEventListener('click', function (e) {
    const row = e.target.closest ? e.target.closest(ROW) : null;
    if (!row || e.target.closest(CONTROL) || !window.matchMedia(PHONE).matches) return;
    const isOpen = !row.classList.contains('s2p-open');
    setOpen(row, isOpen);
    const ids = open[listOf(row)];
    if (isOpen) ids.add(idOf(row)); else ids.delete(idOf(row));
});
