/**
 * "Zagrane samo" (docs/ideas.md item 19, V78): the card of the queue pairs the DJ's program on the computer, which reads the play
 * history VirtualDJ or Serato writes and sends each new song to the server itself (/api/device/plays, DeviceApiController). Shown on a
 * screen 768 px wide or more (a computer), in any browser. "Połącz komputer" shows the code from /dj/devices/code until the program
 * takes it (the list of /dj/devices grows), the paired computers with "Odłącz" (/dj/devices/remove).
 *
 * Two states: no computer paired — the three steps (#deviceSetup: download, start, connect); one or more — their rows only, the steps
 * behind "Połącz kolejny komputer" (#deviceAnother) and shown again while a code waits for its program. The list is asked once when
 * the page loads and again when the page is shown again, so the queue's button is green (btn-outline-success) whenever a computer
 * is paired: the DJ sees it at a glance, without opening the card.
 *
 * The panel reads no folder itself: that way (variant E of item 18, the File System Access API in Chrome and Edge) is parked — two
 * ways were too much to choose from; its code is in the git history (docs/ideas.md item 18 says where).
 *
 * Dependencies (DOM): <button id="deckToggle" hidden>, <section id="deckReader" hidden> with its texts in data-* (device-code,
 * device-copy, device-copied, device-seen, device-remove, device-failed — "{…}" filled in here), #deviceList, #deviceAnother, #deviceSetup, #deviceConnect,
 * #deviceCode.
 */
import { csrfHeaders, partyCode } from './common.js';

const toggle = document.getElementById('deckToggle');
const reader = document.getElementById('deckReader');

/** A narrow screen is no DJ's laptop, and the queue's tools share one line there (scenario library-tools-one-row-phone). */
const wide = window.matchMedia('(min-width: 768px)').matches;

function text(name, values) {
    return (reader.dataset[name] || '').replace(/\{(\w+)\}/g, function (all, key) { return values && key in values ? values[key] : all; });
}

function clock(iso) {
    const at = new Date(iso);
    const options = at.toDateString() === new Date().toDateString()
        ? { hour: '2-digit', minute: '2-digit' } : { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' };
    return at.toLocaleString(document.documentElement.lang || undefined, options);
}

// ---- the program on the DJ's computer (V78): a code to pair it, the computers paired, "Odłącz" ----

function computers() {
    const connect = document.getElementById('deviceConnect');
    const codeLine = document.getElementById('deviceCode');
    const list = document.getElementById('deviceList');
    const setup = document.getElementById('deviceSetup');
    const another = document.getElementById('deviceAnother');
    let count = 0;        // the computers paired, as last listed
    let adding = false;   // "Połącz kolejny komputer" pressed, or a code waiting: the steps shown beside the rows
    let newest = -1;      // the highest id of the list: a program that took the code is a computer newer than this
    let atCode = -1;      // the highest id when the code was made (-1: no list yet then)
    let watching = null;

    async function post(path, fields) {
        return fetch(path, {
            method: 'POST',
            headers: Object.assign({ 'Content-Type': 'application/x-www-form-urlencoded', 'X-Requested-With': 'fetch' }, csrfHeaders()),
            body: new URLSearchParams(Object.assign({ partyCode: partyCode() || '' }, fields)).toString()
        });
    }

    async function load() {
        let devices;
        try {
            const response = await fetch('/dj/devices?partyCode=' + encodeURIComponent(partyCode() || ''),
                { headers: { 'X-Requested-With': 'fetch' } });
            if (!response.ok) return;
            devices = await response.json();
        } catch (e) {
            return;
        }
        list.replaceChildren();
        devices.forEach(function (device) {
            const row = document.createElement('li');
            row.className = 'd-flex flex-wrap align-items-center gap-2 mb-1';
            row.dataset.deviceId = device.id;
            const ok = document.createElement('span');
            ok.className = 's2p-deck-device-ok';
            ok.setAttribute('aria-hidden', 'true');
            ok.textContent = '✓';
            const name = document.createElement('span');
            name.className = 'text-light fw-semibold';
            name.textContent = device.name;
            const seen = document.createElement('span');
            seen.className = 'text-secondary';
            seen.textContent = text('deviceSeen', { time: clock(device.lastSeenAt || device.createdAt) });
            const remove = document.createElement('button');
            remove.type = 'button';
            remove.className = 'btn btn-sm btn-outline-secondary py-0';
            remove.textContent = text('deviceRemove');
            remove.setAttribute('aria-label', text('deviceRemove') + ' ' + device.name);   // a screen reader hears which computer
            remove.addEventListener('click', async function () {
                remove.disabled = true;
                try {
                    await post('/dj/devices/remove', { id: device.id });
                } finally {
                    await load();
                }
            });
            row.append(ok, name, seen, remove);
            list.append(row);
        });
        count = devices.length;
        newest = devices.reduce(function (most, device) { return Math.max(most, device.id); }, 0);
        // the program took the code: it is used up. By the newest id, not the count: a fourth computer replaces the one seen
        // longest ago (the count stays), and an "Odłącz" meanwhile shortens the list while the code is still good
        if (watching !== null && atCode < 0) {
            atCode = newest;   // the code was made before the list came: the list as it is now is the one before the program
        } else if (watching !== null && newest > atCode) {
            clearInterval(watching);
            watching = null;
            codeLine.textContent = '';
            adding = false;
        }
        show();
        list.dataset.loaded = 'true';
    }

    function show() {
        setup.hidden = count > 0 && !adding;
        another.hidden = count === 0 || adding;
        toggle.classList.toggle('btn-outline-success', count > 0);
        toggle.classList.toggle('btn-outline-light', count === 0);
    }

    /**
     * "Kod do wpisania w programie: 123 456 (ważny 10 min) [Kopiuj]": the code bold, split in two for the eye; "Kopiuj" copies the
     * digits alone (the program takes them with or without the space) by dj-nav.js's one handler of every "Kopiuj" (data-copy-text,
     * "Skopiowano" for a moment) — the party link's button, the same look.
     */
    function showCode(code, minutes) {
        const [before, after] = text('deviceCode', { minutes: minutes }).split('{code}');
        const shown = document.createElement('strong');
        shown.className = 's2p-device-code';
        shown.textContent = code.slice(0, 3) + ' ' + code.slice(3);
        const copy = document.createElement('button');
        copy.type = 'button';
        copy.className = 'btn btn-sm btn-outline-secondary py-0 ms-2';
        copy.textContent = reader.dataset.deviceCopy;
        copy.setAttribute('data-copy-text', code);
        copy.setAttribute('data-copied', reader.dataset.deviceCopied);
        codeLine.replaceChildren(before, shown, after === undefined ? '' : after, copy);
    }

    another.addEventListener('click', function () {
        adding = true;
        show();
    });

    connect.addEventListener('click', async function () {
        connect.disabled = true;
        try {
            const response = await post('/dj/devices/code', {});
            if (!response.ok) throw new Error(String(response.status));
            const answer = await response.json();
            adding = true;
            showCode(answer.code, answer.minutes);
            clearInterval(watching);
            atCode = newest;
            const until = Date.now() + Number(answer.minutes) * 60000;
            watching = setInterval(function () {
                if (Date.now() > until) {
                    clearInterval(watching);
                    watching = null;
                    codeLine.textContent = '';   // the steps stay: the DJ is still pairing, a new code is one tap away
                }
                load();
            }, 5000);
        } catch (e) {
            codeLine.textContent = text('deviceFailed');
        } finally {
            connect.disabled = false;
        }
    });

    return load;
}

if (toggle && reader && wide) {
    const loadComputers = computers();
    toggle.hidden = false;
    loadComputers();
    // back to the panel from the program's window (paired just now) or from another tab: the button tells the truth again
    document.addEventListener('visibilitychange', function () {
        if (document.visibilityState === 'visible') loadComputers();
    });
    toggle.addEventListener('click', function () {
        const open = reader.hidden;
        reader.hidden = !open;
        toggle.setAttribute('aria-expanded', String(open));
        if (open) loadComputers();
    });
}
