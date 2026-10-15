// The guest's party page (index.html): the list under the form, the "sending…" state of the button. A file of its own, not an
// inline script, for the Content-Security-Policy (review 5.1); the texts come in data attributes. Loaded at the end of the body, so
// the elements are there.

// The list under the form (the guests' requests, where the guest's song waits) is fetched again — no timer — when the guest comes
// back to the page (a phone unlocked, the tab chosen again, "back" from another page) and on "↻ Odśwież". Its folded rest
// ("Pokaż pozostałe prośby") is fetched only when the guest unfolds it, and its search filters the list as it is typed. A 👍 goes
// in the background, and only that song's votes change on the page (the owner, 2026-10-08: the screen must not jump).
(function () {
    const box = document.getElementById('guestQueueBox');
    if (!box) return;
    let lastRefresh = Date.now();

    /** A song's name or the guest's search as compared: lower case, no accents ("Baśka" is found by "baska", "łza" by "lza"). */
    function plain(text) {
        return text.toLowerCase().normalize('NFD').replace(/\p{M}/gu, '').replace(/ł/g, 'l').trim();
    }
    /** Shows only the songs of the list with the search's words in their names; "no such request" when none has them. */
    function filter() {
        const input = document.getElementById('requestSearch');
        const words = input ? plain(input.value).split(/\s+/).filter(Boolean) : [];
        let shown = 0;
        box.querySelectorAll('li[data-song-id]').forEach(function (row) {
            const name = plain(row.querySelector('.s2p-song-name').textContent);
            const match = words.every(function (word) { return name.indexOf(word) >= 0; });
            row.classList.toggle('d-none', !match);
            if (match) shown++;
        });
        const none = document.getElementById('requestSearchNone');
        if (none) none.classList.toggle('d-none', words.length === 0 || shown > 0);
    }
    /**
     * Where the keyboard's focus is in the list, as a function that focuses the same place in the list put there later: "↻ Odśwież",
     * the search, or the same control of the same song (review 2026-10-14: the list is put in whole in place of the old one, and the
     * focus fell to the page's top — a screen reader started over). It answers whether it found the place; it does nothing (and
     * answers true) when the focus was not in the list.
     */
    function focusPlace() {
        const focused = document.activeElement;
        if (!focused || !box.contains(focused)) return function () { return true; };
        let find;
        const row = focused.closest('li[data-song-id]');
        if (focused.matches('[data-guest-queue-refresh]')) {
            find = function () { return box.querySelector('[data-guest-queue-refresh]'); };
        } else if (focused.id === 'requestSearch') {
            find = function () { return document.getElementById('requestSearch'); };
        } else if (row) {
            const id = row.dataset.songId;
            const at = Array.prototype.indexOf.call(row.querySelectorAll('button, a[href], input'), focused);
            find = function () {
                const again = box.querySelector('li[data-song-id="' + CSS.escape(id) + '"]');
                return again && again.querySelectorAll('button, a[href], input')[at];
            };
        } else {
            return function () { return true; };
        }
        return function () {
            const target = find();
            if (target) target.focus({ preventScroll: true });
            return !!target;
        };
    }
    /** A focus to give back once the rest of the list, fetched again, is in (its songs come after the list itself). */
    let focusAfterMore = null;
    /** The folded rest of the list, fetched when it is unfolded (with 300 waiting, every refresh carrying them all is too much). */
    async function loadMore(details) {
        const place = details.querySelector('.s2p-more-list');
        const focusBack = focusAfterMore;
        focusAfterMore = null;
        try {
            const response = await fetch(details.dataset.url, { headers: { 'Accept': 'text/html' } });
            if (response.ok && !response.redirected && details.isConnected) {
                place.innerHTML = await response.text();
                filter();
                if (focusBack) focusBack();
            }
        } catch (e) {
            // offline for a moment: unfolded, but empty — folding and unfolding it asks again
        }
    }
    // "toggle" does not bubble: caught on its way down
    box.addEventListener('toggle', function (e) {
        if (e.target.id !== 'moreRequests') return;
        if (e.target.open) {
            loadMore(e.target);
        } else {
            const input = document.getElementById('requestSearch');
            if (input && input.value) {
                input.value = '';
                filter();
            }
        }
    }, true);
    box.addEventListener('input', function (e) {
        if (e.target.id === 'requestSearch') filter();
    });

    /**
     * The list from the server in place of this one; unfolded, it is unfolded again (its rest fetched again), the search kept, and
     * the focus where it was ({@code focusBack}, from focusPlace) — in the rest, once the rest is in.
     */
    function replaceQueue(html, focusBack) {
        const more = document.getElementById('moreRequests');
        const wasOpen = !!(more && more.open);
        const search = document.getElementById('requestSearch');
        const text = search ? search.value : '';
        box.innerHTML = html;
        const again = document.getElementById('moreRequests');
        const found = focusBack();
        if (again && wasOpen) {
            document.getElementById('requestSearch').value = text;
            if (!found) focusAfterMore = focusBack;
            again.open = true;   // "toggle" fetches its rest
        }
    }
    /** A note over the page for a few seconds (it pushes nothing down: .s2p-vote-note is fixed). */
    let noteTimer = null;
    function showNote(note) {
        const old = document.getElementById('voteNote');
        if (old) old.remove();
        document.body.appendChild(note);
        clearTimeout(noteTimer);
        noteTimer = setTimeout(function () { note.remove(); }, 4000);
    }
    /**
     * A 👍 or "↻ Odśwież" that did not go through (no network, the session ran out — the form's token with it): the note says so,
     * as the server's own notes do (review 2026-10-14: nothing happened, and the guest could not tell).
     */
    function showFailed() {
        const note = document.createElement('div');
        note.id = 'voteNote';
        note.className = 'alert alert-warning text-center py-2 mb-0 shadow s2p-vote-note';
        note.setAttribute('role', 'status');
        note.textContent = box.dataset.textFailed || '';
        showNote(note);
    }
    /**
     * The answer to a 👍 (GuestController.vote): that song's row as it is now. Only its votes are put in place — the songs keep their
     * places; the new order comes with the next fetch of the list. A song no longer waiting (played or skipped meanwhile) stays,
     * dimmed, its 👍 off.
     */
    function applyVote(html, songId, hadFocus) {
        const answer = document.createElement('div');
        answer.innerHTML = html;
        const now = answer.querySelector('li[data-song-id="' + songId + '"] .s2p-vote-control');
        box.querySelectorAll('li[data-song-id="' + songId + '"]').forEach(function (row) {
            const control = row.querySelector('.s2p-vote-control');
            if (now && control) {
                const fresh = now.cloneNode(true);
                control.replaceWith(fresh);
                // the keyboard's focus on the new 👍, not on the page's top (review 2026-10-14)
                const button = hadFocus && fresh.querySelector('button');
                if (button) button.focus({ preventScroll: true });
            } else if (!now) {
                row.classList.add('s2p-song-gone');
                row.querySelectorAll('button').forEach(function (button) { button.disabled = true; });
            }
        });
        const note = answer.querySelector('#voteNote');
        if (note) showNote(note);
    }
    /**
     * Fetches the list again; true when it came. Asked by the guest ({@code force}: "↻ Odśwież", whose button lost the focus when it
     * was disabled — {@code focusBack} says where it was), or on coming back to the page.
     */
    async function refreshQueue(force, focusBack) {
        if (!force && Date.now() - lastRefresh < 5000) return true; // a quick switch back and forth asks once
        lastRefresh = Date.now();
        try {
            const response = await fetch(box.dataset.url, { headers: { 'Accept': 'text/html' } });
            if (response.ok && !response.redirected) {
                const html = await response.text();
                replaceQueue(html, focusBack || focusPlace());
                return true;
            }
        } catch (e) {
            // offline for a moment: the list stays as it was
        }
        return false;
    }
    // A 👍 (or taking it back) goes in the background (GuestController.vote). The button waits meanwhile, so a double tap sends one.
    box.addEventListener('submit', async function (e) {
        const form = e.target.closest('.s2p-vote-form');
        if (!form) return;
        e.preventDefault();
        const button = form.querySelector('button');
        if (button.disabled) return;
        // a disabled button loses the keyboard's focus: it goes back to the 👍 when the answer is in (review 2026-10-14)
        const hadFocus = document.activeElement === button;
        button.disabled = true;
        try {
            const response = await fetch(form.action, {
                method: 'POST',
                body: new URLSearchParams(new FormData(form)),
                headers: { 'Accept': 'text/html', 'X-Requested-With': 'fetch' }
            });
            if (response.ok && !response.redirected) {
                applyVote(await response.text(), form.elements.id.value, hadFocus);
                return;
            }
        } catch (err) {
            // offline for a moment: nothing was counted, the button can be tapped again
        }
        button.disabled = false;
        if (hadFocus) button.focus({ preventScroll: true });
        showFailed();
    });
    document.addEventListener('visibilitychange', function () {
        if (document.visibilityState === 'visible') refreshQueue(false);
    });
    window.addEventListener('pageshow', function (e) { if (e.persisted) refreshQueue(true); });
    box.addEventListener('click', function (e) {
        const button = e.target.closest('[data-guest-queue-refresh]');
        if (!button) return;
        const focusBack = focusPlace();   // before the button is disabled: that takes the focus away
        button.disabled = true;
        refreshQueue(true, focusBack).then(function (came) {
            // came: the list (and its button) is new; else this one works again, and the note says it did not go through
            if (!came) {
                button.disabled = false;
                focusBack();
                showFailed();
            }
        });
    });
})();

// Sending: the button is disabled (no double request) and shows a spinner with "sending…" while the AI decides (2–4 s). Back on
// the page with "Wstecz", the browser may show it from its back-forward cache as it was left — sending: the button as it was then.
(function () {
    const form = document.getElementById('requestForm');
    if (!form) return;
    const btn = document.getElementById('submitBtn');
    const btnText = document.getElementById('submitBtnText');
    const label = btnText.textContent;
    window.addEventListener('pageshow', function (e) {
        if (!e.persisted) return;
        btn.disabled = false;
        btnText.replaceChildren(document.createTextNode(label));
    });
    form.addEventListener('submit', function () {

        // Basic check for HTML5 validation
        if (form.checkValidity()) {
            btn.disabled = true;

            const spinner = document.createElement('span');
            spinner.className = 'spinner-border spinner-border-sm me-2';
            spinner.setAttribute('role', 'status');
            spinner.setAttribute('aria-hidden', 'true');
            btnText.replaceChildren(spinner, document.createTextNode(btn.dataset.textSubmitting || ''));
        }
    });
})();
