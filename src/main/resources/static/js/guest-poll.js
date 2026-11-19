// "Pytanie do gości" (V74) on the guest's page (index.html, party_ended.html; fragments/guest-poll.html): an answer goes in the
// background and the card comes back with the results — the page does not load again, the typed song stays in its field. The card is
// fetched again, as the list is, when the guest comes back to the page (no timer: a room of phones asks only when someone looks), so a
// question the DJ asked meanwhile shows up. An answer that is not the card (the session ended, a Wi-Fi login page) loads the page
// again. A file of its own, not an inline script, for the Content-Security-Policy.
(function () {
    let box = document.getElementById('guestPoll');
    if (!box) return;
    const url = box.dataset.url;
    let lastRefresh = 0;

    /** The card from the server in place of this one; true when it was the card. */
    function replace(html) {
        const template = document.createElement('template');
        template.innerHTML = html.trim();
        const next = template.content.firstElementChild;
        if (!next || next.id !== 'guestPoll') return false;
        box.replaceWith(next);
        box = next;
        return true;
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
        e.preventDefault();
        const button = e.submitter;
        if (!button || button.disabled) return;
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
                return;
            }
        } catch (err) {
            // offline for a moment: nothing was counted, the buttons can be tapped again
        }
        buttons.forEach(function (b) { b.disabled = false; });
        showFailed(form);
    });

    document.addEventListener('visibilitychange', function () {
        if (document.visibilityState === 'visible') refresh();
    });
    window.addEventListener('pageshow', function (e) { if (e.persisted) { lastRefresh = 0; refresh(); } });
})();
