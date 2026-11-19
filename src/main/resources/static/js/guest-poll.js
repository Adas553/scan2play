// "Pytanie do gości" (V74) on the guest's page (index.html, party_ended.html; fragments/guest-poll.html): an answer goes in the
// background and the card comes back with the results — the page does not load again, the typed song stays in its field. The card is
// fetched again, as the list is, when the guest comes back to the page (no timer: a room of phones asks only when someone looks), so
// a question the DJ asked meanwhile shows up — and on "↻ Sprawdź wyniki" while the DJ keeps the results for later. An answer that is
// not the card (the session ended, a Wi-Fi login page) loads the page again. A file of its own, not an inline script, for the
// Content-Security-Policy.
(function () {
    const box = document.getElementById('guestPoll');
    if (!box) return;
    const url = box.dataset.url;
    let lastRefresh = 0;

    /**
     * The card from the server in place of this one's content; true when it was the card. The section itself stays — the live region
     * a screen reader listens to (one put in the page anew is not announced) — and the results stay unfolded if the guest opened them.
     */
    function replace(html) {
        const template = document.createElement('template');
        template.innerHTML = html.trim();
        const next = template.content.firstElementChild;
        if (!next || next.id !== 'guestPoll') return false;
        const fold = box.querySelector('#pollFold');
        const wasOpen = !!(fold && fold.open);
        box.hidden = next.hidden;
        box.replaceChildren.apply(box, Array.from(next.childNodes));
        const nextFold = box.querySelector('#pollFold');
        if (wasOpen && nextFold) nextFold.open = true;
        return true;
    }

    /** After an answer the tapped button is gone: the focus goes to the card's line (or its question), not to the page's top. */
    function focusCard() {
        const target = box.querySelector('#pollFold > summary') || box.querySelector('#pollQuestion');
        if (target) target.focus({ preventScroll: true });
    }

    function showFailed(form) {
        let note = box.querySelector('#pollNote');
        if (!note) {
            note = document.createElement('p');
            note.id = 'pollNote';
            note.className = 'small text-warning mt-2 mb-0';
            note.setAttribute('role', 'status');
            form.after(note);
        }
        note.textContent = box.dataset.textFailed || '';
    }

    async function refresh() {
        if (Date.now() - lastRefresh < 5000) return;   // a quick switch back and forth asks once
        lastRefresh = Date.now();
        try {
            const response = await fetch(url, { headers: { 'Accept': 'text/html' }, cache: 'no-store' });
            if (response.ok && !response.redirected) replace(await response.text());
        } catch (e) {
            // offline for a moment: the card stays as it was
        }
    }

    // the answer: the buttons wait meanwhile, so a double tap sends one
    document.addEventListener('submit', async function (e) {
        const form = e.target.closest('.s2p-poll-form');
        if (!form || !box.contains(form)) return;
        // a browser without SubmitEvent.submitter (Safari before 15.4): the plain form, back to the party page
        const button = e.submitter;
        if (!button) return;
        e.preventDefault();
        if (button.disabled) return;
        const body = new URLSearchParams(new FormData(form));
        body.set(button.name, button.value);
        const buttons = form.querySelectorAll('button');
        buttons.forEach(function (b) { b.disabled = true; });
        try {
            const response = await fetch(form.action, {
                method: 'POST',
                body: body,
                headers: { 'Accept': 'text/html', 'X-Requested-With': 'fetch' }
            });
            if (response.redirected) {
                window.location.reload();   // the form had expired: the page again, a new session and token
                return;
            }
            if (response.ok && replace(await response.text())) {
                lastRefresh = Date.now();
                focusCard();
                return;
            }
        } catch (err) {
            // offline for a moment: nothing was counted, the buttons can be tapped again
        }
        buttons.forEach(function (b) { b.disabled = false; });
        showFailed(form);
    });

    // "↻ Sprawdź wyniki" while the DJ keeps them for later (V75): the card again, now — the guest asks, so no wait between asks
    document.addEventListener('click', async function (e) {
        const button = e.target.closest('[data-poll-refresh]');
        if (!button || !box.contains(button) || button.disabled) return;
        button.disabled = true;
        lastRefresh = 0;
        await refresh();
        if (box.contains(button)) button.disabled = false;   // the same card came back (nothing new, or offline): it works again
    });

    document.addEventListener('visibilitychange', function () {
        if (document.visibilityState === 'visible') refresh();
    });
    window.addEventListener('pageshow', function (e) { if (e.persisted) { lastRefresh = 0; refresh(); } });
})();
