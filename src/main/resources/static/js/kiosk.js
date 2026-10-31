/**
 * The venue's kiosk (kiosk.html, KioskController, V56): a tablet many guests use one after another.
 *
 * - The request form says "Wysyłanie…" and is sent once (the AI takes seconds; a second tap would send it again).
 * - A 👍 is sent in the background ([data-kiosk-vote], X-Requested-With: fetch); the answer is the list, put in place with the new
 *   votes and a note when the 👍 did not count — read out by #kioskAnnounce (a live region new with the list is not announced) and
 *   left on the screen for NOTE_MS. Refused or lost: the kiosk's own page. Not while "Następny gość" is on its way: the 👍 would
 *   land in the next guest's turn.
 * - The list (#kioskList) is fetched again every LIST_MS — the room's votes and new songs, as the TV screen. Not under a finger (its
 *   rows move with the votes: a 👍 aimed at one song would land on another) — not within QUIET_MS of a touch —, and not while a 👍
 *   is on its way or its note is read (NOTE_MS): an answer asked for before a 👍 would show it not given, and as every fetch is
 *   given up after ANSWER_MS < NOTE_MS, such an answer always lands in that time. The list keeps its place in its card (it scrolls
 *   there) and the focus on the same song's 👍; a list longer than its card fades at the bottom (.has-more — a tablet hides the
 *   scrollbar). A list that says the party closed (data-active="false") sends the kiosk to its page, which says when it opens.
 * - The kiosk's own page (body[data-kiosk-url]) is the only page it loads, and only once that page answers (asked for first, in the
 *   background): a page load with the Wi-Fi down, or the server failing, would leave the tablet on the browser's error page, with
 *   no script to bring it back. Not answering, the kiosk stays and asks again after RETRY_MS. Never reload(): an answer is a POST's
 *   page, and a reload would send the request again.
 * - The next guest: a guest who walked away leaves a typed song, a 👍 or an answer behind. After IDLE_MS without a touch (an answer:
 *   RESULT_MS) the turn ends by itself — "Następny gość" ([data-kiosk-next]) is sent: a new session, an empty form. A turn begun
 *   before the page loaded (body[data-kiosk-turn]: a refused request back with its words, a 👍 given) is ended the same way.
 *   "Następny gość" — tapped or by itself — is sent in the background and only then is the kiosk's page loaded: a form's page load
 *   with the Wi-Fi down would leave the tablet on the browser's error page, with no script to bring it back. Lost, or no answer within
 *   ANSWER_MS, the kiosk stays and tries again after RETRY_MS. An untouched form is loaded again every REFRESH_MS, so its session and its form's
 *   token never run out on a tablet left all night; a closed party's page every CLOSED_MS, so the form comes within a minute of the
 *   party opening.
 */
(function () {
    const LIST_MS = 10000;
    const IDLE_MS = 60000;
    const RESULT_MS = 20000;
    const REFRESH_MS = 10 * 60000;
    const CLOSED_MS = 60000;
    const QUIET_MS = 5000;
    const NOTE_MS = 15000;
    const ANSWER_MS = 10000;
    const RETRY_MS = 30000;
    const state = document.body.dataset.kioskState;
    const home = document.body.dataset.kioskUrl;
    // a turn begun (a typed song, a 👍, an answer on the screen — or before this page loaded), a request on its way, the last touch,
    // and after a way out that got no answer: not before then
    let touched = state === 'result' || document.body.dataset.kioskTurn === 'begun';
    let busy = false;
    let lastTouch = Date.now();
    let retryAt = 0;

    /** A fetch's signal that gives up after ms: an answer that never comes (packets dropped) must not hold the kiosk. */
    function within(ms) {
        const giveUp = new AbortController();   // AbortSignal.timeout() is too new for older iPads
        setTimeout(function () { giveUp.abort(); }, ms);
        return giveUp.signal;
    }

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

    /**
     * The kiosk's own page in place of this one, once it answers (a 404 too — the link made new: the page says so). No answer, or
     * the server failing: the kiosk stays, and asks again after RETRY_MS.
     */
    function backHome() {
        busy = true;
        fetch(home, { cache: 'no-store', signal: within(ANSWER_MS) })
            .then(function (response) {
                if (response.status >= 500) throw new Error('HTTP ' + response.status);
                window.location.replace(home);
            })
            .catch(function () {
                busy = false;
                retryAt = Date.now() + RETRY_MS;
            });
    }

    // ---- the most wanted: 👍 in the background, the list again every few seconds ----
    let voting = false;
    // the list not fetched again until then (a 👍's note is read)
    let holdUntil = 0;

    /** Whether there is more of the list below what its card shows: it fades at the bottom. */
    function showMore(songs) {
        songs.classList.toggle('has-more', songs.scrollTop + songs.clientHeight < songs.scrollHeight - 2);
    }

    /**
     * The server's list in place of this one, at the same place in its card (it scrolls there) and with the focus on the same song's
     * 👍; a closed party: the kiosk's page.
     */
    function putList(html, songId) {
        const answer = document.createElement('template');
        answer.innerHTML = html;
        const fresh = answer.content.querySelector('#kioskList');
        const list = document.getElementById('kioskList');
        if (!fresh || !list) return false;
        if (fresh.dataset.active === 'false') {
            backHome();
            return true;
        }
        const focused = list.contains(document.activeElement) ? document.activeElement.closest('[data-song-id]') : null;
        const keep = songId || (focused ? focused.dataset.songId : null);
        const before = list.querySelector('.s2p-kiosk-songs');
        const place = before ? before.scrollTop : 0;
        list.replaceWith(fresh);
        const songs = fresh.querySelector('.s2p-kiosk-songs');
        if (songs) {
            songs.scrollTop = place;
            showMore(songs);
        }
        const again = keep ? fresh.querySelector('[data-song-id="' + keep + '"] button') : null;
        if (again) again.focus({ preventScroll: true });
        return true;
    }

    document.addEventListener('submit', function (e) {
        const vote = e.target.closest('form[data-kiosk-vote]');
        if (!vote) return;
        e.preventDefault();
        if (busy) return;   // the next guest is on its way: this 👍 would be theirs
        touched = true;
        voting = true;
        const row = vote.closest('[data-song-id]');
        const songId = row ? row.dataset.songId : null;
        const button = vote.querySelector('button');
        if (button) button.disabled = true;
        fetch(vote.action, { method: 'POST', headers: { 'X-Requested-With': 'fetch' }, body: new FormData(vote), signal: within(ANSWER_MS) })
            .then(function (response) {
                if (!response.ok || response.redirected) throw new Error('HTTP ' + response.status);
                return response.text();
            })
            .then(function (html) {
                if (!putList(html, songId)) throw new Error('an answer without the list');
                holdUntil = Date.now() + NOTE_MS;
                const note = document.getElementById('kioskVoteNote');
                const live = document.getElementById('kioskAnnounce');
                if (live) live.textContent = note ? note.textContent.trim() : '';
            })
            .catch(function () {
                backHome();   // the session ran out, or the network: the kiosk's page again
            })
            .finally(function () {
                voting = false;
            });
    });

    /** Whether the list may be put in place now: no 👍 on its way, its note read, no finger on the screen. */
    function listMayChange() {
        const now = Date.now();
        return !voting && now >= holdUntil && now - lastTouch >= QUIET_MS;
    }

    const list = document.getElementById('kioskList');
    if (list && list.dataset.url) {
        setInterval(function () {
            if (document.hidden || !listMayChange()) return;
            const url = document.getElementById('kioskList').dataset.url;
            fetch(url, { headers: { 'X-Requested-With': 'fetch' }, signal: within(ANSWER_MS) })
                .then(function (response) { return response.ok && !response.redirected ? response.text() : null; })
                .then(function (html) {
                    if (html && listMayChange()) putList(html);
                })
                .catch(function () { /* the list stays as it was until the network is back */ });
        }, LIST_MS);
    }

    // the fade follows the list's own scroll (a scroll does not bubble: caught on its way down) and the screen's size
    document.addEventListener('scroll', function (e) {
        if (e.target.classList && e.target.classList.contains('s2p-kiosk-songs')) showMore(e.target);
    }, true);
    function showMoreNow() {
        const songs = document.querySelector('#kioskList .s2p-kiosk-songs');
        if (songs) showMore(songs);
    }
    window.addEventListener('resize', showMoreNow);
    showMoreNow();

    // ---- the next guest ----

    // any touch keeps the turn going; typing begins one (a tap on nothing does not)
    ['pointerdown', 'keydown', 'input'].forEach(function (type) {
        document.addEventListener(type, function () {
            lastTouch = Date.now();
            if (type !== 'pointerdown') touched = true;
        }, true);
    });

    /**
     * "Następny gość" sent in the background, then the kiosk's page. The server answers with a redirect to it (not followed: the
     * page is loaded once, by the browser); any answer but a lost one or a server's failure leads there — a 404 too (the link made
     * new): the page says so. Lost, or no answer within ANSWER_MS: the kiosk stays, and the turn ends again after RETRY_MS.
     */
    function nextGuest(next) {
        busy = true;
        const buttons = next.querySelectorAll('button');
        buttons.forEach(function (button) { button.disabled = true; });
        fetch(next.action, { method: 'POST', body: new FormData(next), redirect: 'manual', signal: within(ANSWER_MS) })
            .then(function (response) {
                if (response.status >= 500) throw new Error('HTTP ' + response.status);
                backHome();
            })
            .catch(function () {
                buttons.forEach(function (button) { button.disabled = false; });
                busy = false;
                retryAt = Date.now() + RETRY_MS;
            });
    }

    document.addEventListener('submit', function (e) {
        const next = e.target.closest('form[data-kiosk-next]');
        if (!next) return;
        e.preventDefault();
        if (!busy) nextGuest(next);
    });

    setInterval(function () {
        if (busy || Date.now() < retryAt) return;
        const quiet = Date.now() - lastTouch;
        if (touched && quiet >= (state === 'result' ? RESULT_MS : IDLE_MS)) {
            const next = document.querySelector('form[data-kiosk-next]');
            if (next) {
                nextGuest(next);
            } else {
                backHome();
            }
        } else if (!touched && quiet >= (state === 'closed' ? CLOSED_MS : REFRESH_MS)) {
            backHome();
        }
    }, 1000);
})();
