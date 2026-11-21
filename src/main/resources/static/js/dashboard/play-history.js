/**
 * "Zagrane samo" (docs/ideas.md item 19, V78): the card of the queue pairs the DJ's program on the computer, which reads the play
 * history VirtualDJ or Serato writes and sends each new song to the server itself (/api/device/plays, DeviceApiController). Shown on a
 * screen 768 px wide or more (a computer), in any browser. "Połącz komputer" shows the code from /dj/devices/code until the program
 * takes it (the list of /dj/devices grows), the paired computers with "Odłącz" (/dj/devices/remove).
 *
 * The panel reads no folder itself: that way (variant E of item 18, the File System Access API in Chrome and Edge) is parked — two
 * ways were too much to choose from; its code is in the git history (docs/ideas.md item 18 says where).
 *
 * Dependencies (DOM): <button id="deckToggle" hidden>, <section id="deckReader" hidden> with its texts in data-* (device-code,
 * device-seen, device-remove, device-none, device-failed — "{…}" filled in here), #deviceConnect, #deviceCode, #deviceList.
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
        if (devices.length === 0) {
            const none = document.createElement('li');
            none.textContent = text('deviceNone');
            list.append(none);
        }
        devices.forEach(function (device) {
            const row = document.createElement('li');
            row.className = 'd-flex flex-wrap align-items-center gap-2 mb-1';
            row.dataset.deviceId = device.id;
            const name = document.createElement('span');
            name.className = 'text-light';
            name.textContent = device.name;
            const seen = document.createElement('span');
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
            row.append(name, seen, remove);
            list.append(row);
        });
        newest = devices.reduce(function (most, device) { return Math.max(most, device.id); }, 0);
        // the program took the code: it is used up. By the newest id, not the count: a fourth computer replaces the one seen
        // longest ago (the count stays), and an "Odłącz" meanwhile shortens the list while the code is still good
        if (watching !== null && atCode < 0) {
            atCode = newest;   // the code was made before the list came: the list as it is now is the one before the program
        } else if (watching !== null && newest > atCode) {
            clearInterval(watching);
            watching = null;
            codeLine.textContent = '';
        }
    }

    connect.addEventListener('click', async function () {
        connect.disabled = true;
        try {
            const response = await post('/dj/devices/code', {});
            if (!response.ok) throw new Error(String(response.status));
            const answer = await response.json();
            codeLine.textContent = text('deviceCode', { code: answer.code.slice(0, 3) + ' ' + answer.code.slice(3), minutes: answer.minutes });
            clearInterval(watching);
            atCode = newest;
            const until = Date.now() + Number(answer.minutes) * 60000;
            watching = setInterval(function () {
                if (Date.now() > until) {
                    clearInterval(watching);
                    watching = null;
                    codeLine.textContent = '';
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
    toggle.addEventListener('click', function () {
        const open = reader.hidden;
        reader.hidden = !open;
        toggle.setAttribute('aria-expanded', String(open));
        if (open) loadComputers();
    });
}
