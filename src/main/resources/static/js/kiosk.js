/**
 * The venue's kiosk (kiosk.html, KioskController, V56): a tablet many guests use one after another.
 *
 * - The request form says "Wysyłanie…" and is sent once (the AI takes seconds; a second tap would send it again).
 * - A 👍 is sent in the background ([data-kiosk-vote], X-Requested-With: fetch); the answer is the list, put in place with the new
 *   votes and a note when the 👍 did not count. Refused or lost: the page loads again (the kiosk's page is the way back).
 * - The list (#kioskList) is fetched again every LIST_MS — the room's votes and new songs, as the TV screen.
 * - The next guest: a guest who walked away leaves a typed song, a 👍 or an answer behind. After IDLE_MS without a touch (an answer:
 *   RESULT_MS) the turn ends by itself — "Następny gość" is sent ([data-kiosk-next]): a new session, an empty form. An untouched
 *   form is loaded again every REFRESH_MS, so its session and its form's token never run out on a tablet left all night; a closed
 *   party's page every CLOSED_MS, so the form comes within a minute of the party opening.
 */
(function () {
    const LIST_MS = 10000;
    const IDLE_MS = 60000;
    const RESULT_MS = 20000;
    const REFRESH_MS = 10 * 60000;
    const CLOSED_MS = 60000;
    const state = document.body.dataset.kioskState;
    // a turn begun (a typed song, a 👍, an answer on the screen), a request on its way, the last touch
    let touched = state === 'result';
    let busy = false;
    let lastTouch = Date.now();

    // ---- the request: sent once ----
    const form = document.getElementById('requestForm');
    if (form) {
        form.addEventListener('submit', function () {
            const button = document.getElementById('submitBtn');
            if (!button) return;
            // after the browser has taken the form: disabled at once, the submission would lose the button
            setTimeout(function () {
                button.disabled = true;
                button.textContent = button.dataset.textSubmitting || button.textContent;
            }, 0);
            touched = true;
            busy = true;
        });
    }

    // ---- the most wanted: 👍 in the background, the list again every few seconds ----
    let voting = false;

    function putList(html) {
        const holder = document.createElement('div');
        holder.innerHTML = html;
        const fresh = holder.querySelector('#kioskList');
        const list = document.getElementById('kioskList');
        if (!fresh || !list) return false;
        list.replaceWith(fresh);
        return true;
    }

    document.addEventListener('submit', function (e) {
        const vote = e.target.closest('form[data-kiosk-vote]');
        if (!vote) return;
        e.preventDefault();
        touched = true;
        voting = true;
        const button = vote.querySelector('button');
        if (button) button.disabled = true;
        fetch(vote.action, { method: 'POST', headers: { 'X-Requested-With': 'fetch' }, body: new FormData(vote) })
            .then(function (response) {
                if (!response.ok || response.redirected) throw new Error('HTTP ' + response.status);
                return response.text();
            })
            .then(function (html) {
                if (!putList(html)) throw new Error('an answer without the list');
            })
            .catch(function () {
                window.location.reload();   // the session ran out, or the network: the kiosk's page again
            })
            .finally(function () {
                voting = false;
            });
    });

    const list = document.getElementById('kioskList');
    if (list && list.dataset.url) {
        setInterval(function () {
            if (voting || document.hidden) return;
            const url = document.getElementById('kioskList').dataset.url;
            fetch(url, { headers: { 'X-Requested-With': 'fetch' } })
                .then(function (response) { return response.ok && !response.redirected ? response.text() : null; })
                .then(function (html) {
                    if (html && !voting) putList(html);   // a 👍's note goes with it: it was read by then
                })
                .catch(function () { /* the list stays as it was until the network is back */ });
        }, LIST_MS);
    }

    // ---- the next guest by itself ----

    // any touch keeps the turn going; typing begins one (a tap on nothing does not)
    ['pointerdown', 'keydown', 'input'].forEach(function (type) {
        document.addEventListener(type, function () {
            lastTouch = Date.now();
            if (type !== 'pointerdown') touched = true;
        }, true);
    });

    function nextGuest() {
        const next = document.querySelector('form[data-kiosk-next]');
        if (next) {
            next.submit();
        } else {
            window.location.reload();
        }
    }

    setInterval(function () {
        if (busy) return;
        const quiet = Date.now() - lastTouch;
        if (touched && quiet >= (state === 'result' ? RESULT_MS : IDLE_MS)) {
            busy = true;
            nextGuest();
        } else if (!touched && quiet >= (state === 'closed' ? CLOSED_MS : REFRESH_MS)) {
            busy = true;
            window.location.reload();
        }
    }, 1000);
})();
