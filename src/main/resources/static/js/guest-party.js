// The guest's party page (index.html): the song / mood switch, the list under the form, the "sending…" state of the button. A file
// of its own, not an inline script, for the Content-Security-Policy (review 5.1); the texts come in data attributes. Loaded at the end
// of the body, so the elements are there.

// The mode buttons: the help text follows the mode, and song suggestions are only offered for a song. A requests-only party has no
// mode buttons (songs only): nothing to do.
(function () {
    const input = document.getElementById('songInput');
    const help = document.getElementById('songInputHelp');
    const moodRadio = document.getElementById('modeMood');
    if (!input || !help || !moodRadio) return;
    function applyMode() {
        const mood = moodRadio.checked;
        help.textContent = mood ? help.dataset.textMood : help.dataset.textSong;
        input.dataset.autocompleteOff = String(mood);
    }
    // With a mouse the field takes the cursor at once; on a phone it does not: focus opens the keyboard and the page jumps under the
    // guest's finger — the guest taps the field when ready to type.
    const typesWithAMouse = window.matchMedia && window.matchMedia('(hover: hover) and (pointer: fine)').matches;
    document.querySelectorAll('input[name="requestMode"]').forEach(function (radio) {
        radio.addEventListener('change', function () {
            applyMode();
            if (typesWithAMouse) input.focus();
        });
    });
    applyMode();
})();

// The list under the form (what plays, what comes next, where the guest's song waits) is fetched again — no timer — when the
// guest comes back to the page (a phone unlocked, the tab chosen again, "back" from another page) and on "↻ Odśwież".
(function () {
    const box = document.getElementById('guestQueueBox');
    if (!box) return;
    let lastRefresh = Date.now();
    async function refreshQueue(force) {
        if (!force && Date.now() - lastRefresh < 5000) return; // a quick switch back and forth asks once
        lastRefresh = Date.now();
        try {
            const response = await fetch(box.dataset.url, { headers: { 'Accept': 'text/html' } });
            if (response.ok && !response.redirected) box.innerHTML = await response.text();
        } catch (e) {
            // offline for a moment: the list stays as it was
        }
    }
    document.addEventListener('visibilitychange', function () {
        if (document.visibilityState === 'visible') refreshQueue(false);
    });
    window.addEventListener('pageshow', function (e) { if (e.persisted) refreshQueue(true); });
    box.addEventListener('click', function (e) {
        const button = e.target.closest('[data-guest-queue-refresh]');
        if (!button) return;
        button.disabled = true;
        refreshQueue(true);
    });
})();

// Sending: the button is disabled (no double request) and shows a spinner with "sending…" while the AI decides (2–4 s)
(function () {
    const form = document.getElementById('requestForm');
    if (!form) return;
    form.addEventListener('submit', function () {
        const btn = document.getElementById('submitBtn');
        const btnText = document.getElementById('submitBtnText');

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
