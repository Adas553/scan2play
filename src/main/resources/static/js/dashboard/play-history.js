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
 * Each computer's line says whether its program sees songs (V81, DeckWatchService's state): "widzi piosenki · ostatnia 21:43
 * (VirtualDJ)", "czeka na pierwszą piosenkę" — the DJ's test before the party: play any song —, and, during the party, "brak nowych
 * piosenek od 21:43" or "program nie łączy się od 21:40" in amber (warn: only the computer the set is played on), the queue's button
 * amber too (and said in its name: "nie widzi piosenek" or "nie łączy się"). The list is asked again every minute while the page is
 * shown and a computer is paired, so a program that stopped is seen without a reload; two asks in a row that fail (the panel's own
 * network, the session gone) say so and leave the button neither green nor amber — a line kept from an hour ago is no news. "Ostrzeż po" (#deviceSilence, shown once a computer is paired) sets after how many
 * minutes of silence the devices of the organiser and the staff get a push (/dj/devices/silence); "nigdy": neither push nor amber.
 *
 * Dependencies (DOM): <button id="deckToggle" hidden>, <section id="deckReader" hidden> with its texts in data-* (device-code,
 * device-copy, device-copied, device-seen, device-remove, device-failed, device-ok, device-waiting, device-last, device-silent,
 * device-offline, device-stale, deck-toggle-warn, deck-toggle-offline, deck-toggle-stale — "{…}" filled in here), #deviceList,
 * #deviceStale, #deviceAnother, #deviceSetup, #deviceConnect, #deviceCode, #deviceWatch with <select id="deviceSilence"> and
 * #deviceSilenceFailed (both role="status": always there, only their text changes, so a screen reader reads it), the switch
 * #deviceSetKept ("Zapisuj set wieczoru", /dj/devices/set, V83) with #deviceSetFailed, the switch #deviceNowPlaying ("Pokazuj na TV i
 * kiosku, co teraz leci", /dj/devices/now-playing, V84) with #deviceNowPlayingFailed.
 */
import { csrfHeaders, partyCode } from './common.js';

const toggle = document.getElementById('deckToggle');
const reader = document.getElementById('deckReader');

/** A narrow screen is no DJ's laptop, and the queue's tools share one line there (scenario library-tools-one-row-phone). */
const wide = window.matchMedia('(min-width: 768px)').matches;

/** The list asked again this often while the page is shown: a program that stopped is seen without a reload. */
const REFRESH_MS = 60000;

function text(name, values) {
    return (reader.dataset[name] || '').replace(/\{(\w+)\}/g, function (all, key) { return values && key in values ? values[key] : all; });
}

function clock(iso) {
    const at = new Date(iso);
    const options = at.toDateString() === new Date().toDateString()
        ? { hour: '2-digit', minute: '2-digit' } : { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' };
    return at.toLocaleString(document.documentElement.lang || undefined, options);
}

/**
 * A computer's line (V81): the glyph, its class and the words, by its state and whether it is worth the DJ's eye now (warn). Out of the
 * party a silent or gone program is said in grey — "ostatnia piosenka 23:58", "ostatnio 0:10" —, as no fault.
 */
function line(device) {
    const time = function (iso) { return clock(iso || device.lastSeenAt || device.createdAt); };
    const program = device.program ? ' (' + device.program + ')' : '';
    switch (device.state) {
        case 'OK':
            return { glyph: '✓', css: 's2p-deck-device-ok', words: text('deviceOk', { time: time(device.lastPlayAt) }) + program };
        case 'WAITING':
            return { glyph: '…', css: 's2p-deck-device-wait', words: text('deviceWaiting') };
        case 'SILENT':
            return device.warn
                ? { glyph: '!', css: 's2p-deck-device-warn', words: text('deviceSilent', { time: time(device.lastPlayAt) }) + program }
                : { glyph: '·', css: 's2p-deck-device-wait', words: text('deviceLast', { time: time(device.lastPlayAt) }) + program };
        default:   // OFFLINE
            return device.warn
                ? { glyph: '!', css: 's2p-deck-device-warn', words: text('deviceOffline', { time: time(device.lastSeenAt) }) }
                : { glyph: '·', css: 's2p-deck-device-wait', words: text('deviceSeen', { time: time(device.lastSeenAt) }) };
    }
}

// ---- the program on the DJ's computer (V78): a code to pair it, the computers paired, "Odłącz" ----

function computers() {
    const connect = document.getElementById('deviceConnect');
    const codeLine = document.getElementById('deviceCode');
    const list = document.getElementById('deviceList');
    const setup = document.getElementById('deviceSetup');
    const another = document.getElementById('deviceAnother');
    const watch = document.getElementById('deviceWatch');
    const silence = document.getElementById('deviceSilence');
    const silenceFailed = document.getElementById('deviceSilenceFailed');
    const setKept = document.getElementById('deviceSetKept');
    const setFailed = document.getElementById('deviceSetFailed');
    const nowShown = document.getElementById('deviceNowPlaying');
    const nowFailed = document.getElementById('deviceNowPlayingFailed');
    const stale = document.getElementById('deviceStale');
    const toggleLabel = toggle.getAttribute('aria-label') || '';
    const toggleTitle = toggle.title || '';
    let count = 0;        // the computers paired, as last listed
    let warning = false;  // a computer's line in amber: the queue's button amber too
    let gone = false;     // ... and that line is a program that does not connect (the button says so, not "sees no songs")
    let failed = 0;       // the lists asked in a row that did not come: from two on, the lines are said to be old
    let loadedAt = null;  // when the lines shown came
    let asked = 0;        // the lists asked for: an answer to an older one (the minute's, a tap's) is dropped when a newer was asked
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

    /** {@code force}: the select set to the server's minutes even while it has the focus (a choice that was not saved). */
    async function load(force) {
        let devices;
        let minutes;
        let kept;
        let shown;
        const mine = ++asked;
        try {
            const response = await fetch('/dj/devices?partyCode=' + encodeURIComponent(partyCode() || ''),
                { headers: { 'X-Requested-With': 'fetch' } });
            if (!response.ok) return notLoaded(mine);
            const answer = await response.json();
            if (mine !== asked) return;   // a newer list is on its way: this one may be older than what the DJ just did
            devices = answer.computers || [];
            minutes = answer.silenceMinutes;
            kept = answer.setKept;
            shown = answer.nowPlayingShown;
        } catch (e) {
            return notLoaded(mine);
        }
        failed = 0;
        loadedAt = new Date().toISOString();
        list.replaceChildren();
        warning = false;
        gone = false;
        let silent = false;   // an amber line that sees no songs: said first, the one to fix in the program
        devices.forEach(function (device) {
            const said = line(device);
            const warn = said.css === 's2p-deck-device-warn';
            warning = warning || warn;
            gone = gone || warn && device.state === 'OFFLINE';
            silent = silent || warn && device.state !== 'OFFLINE';
            const row = document.createElement('li');
            // one line where it fits; else the words break in their place, "Odłącz" stays at the row's end
            row.className = 'd-flex align-items-baseline gap-2 mb-1';
            row.dataset.deviceId = device.id;
            row.dataset.state = device.state;
            const ok = document.createElement('span');
            ok.className = said.css + ' s2p-deck-device-glyph';
            ok.setAttribute('aria-hidden', 'true');
            ok.textContent = said.glyph;
            const name = document.createElement('span');
            name.className = 'text-light fw-semibold s2p-deck-device-name';
            name.textContent = device.name;
            const seen = document.createElement('span');
            seen.className = (warn ? 'text-warning' : 'text-secondary') + ' s2p-deck-device-words';
            seen.textContent = said.words;
            const remove = document.createElement('button');
            remove.type = 'button';
            remove.className = 'btn btn-sm btn-outline-secondary py-0 flex-shrink-0';
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
        gone = gone && !silent;
        count = devices.length;
        // the select follows the server, unless the DJ is changing it right now
        if (minutes !== undefined && (force === true || document.activeElement !== silence)) silence.value = String(minutes);
        if (typeof kept === 'boolean' && !setKept.disabled) setKept.checked = kept;
        if (typeof shown === 'boolean' && !nowShown.disabled) nowShown.checked = shown;
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

    /** A list asked for did not come (no network, the session or Pro gone): the lines kept, but from the second in a row said old. */
    function notLoaded(mine) {
        if (mine !== asked) return;   // a newer ask is on its way: that one says
        failed++;
        show();
    }

    function show() {
        const old = count > 0 && failed >= 2;
        setup.hidden = count > 0 && !adding;
        another.hidden = count === 0 || adding;
        watch.hidden = count === 0;
        toggle.classList.toggle('btn-outline-warning', count > 0 && warning && !old);
        toggle.classList.toggle('btn-outline-success', count > 0 && !warning && !old);
        toggle.classList.toggle('btn-outline-light', count === 0 || old);
        stale.textContent = old ? text('deviceStale', { time: clock(loadedAt) }) : '';
        // amber is a colour only: a screen reader, or an eye that tells amber from green badly, hears it in the button's name
        const said = old ? 'deckToggleStale' : count > 0 && warning ? (gone ? 'deckToggleOffline' : 'deckToggleWarn') : '';
        const warn = said ? ' — ' + text(said) : '';
        toggle.setAttribute('aria-label', toggleLabel + warn);
        toggle.title = toggleTitle + warn;
    }

    // "Ostrzeż po": saved at once; the list asked again, so the lines follow the new minutes. Not saved (no Pro any more, no
    // network): said, and the select back at what the server keeps — else the DJ would trust a choice that is not there
    silence.addEventListener('change', async function () {
        silence.disabled = true;
        silenceFailed.textContent = '';
        let saved = false;
        try {
            saved = (await post('/dj/devices/silence', { minutes: silence.value })).ok;
        } catch (e) {
            saved = false;
        } finally {
            silence.disabled = false;
            if (!saved) silenceFailed.textContent = text('deviceFailed');
            await load(!saved);
        }
    });

    // "Zapisuj set wieczoru" and "Pokazuj na TV i kiosku, co teraz leci": saved at once; not saved, said, and the switch back as the
    // server keeps it (the next list puts it). A switch whose "off" deletes something (the sets of every evening) asks first
    // (data-confirm-off); "Anuluj" puts it back on and sends nothing.
    function savedAtOnce(box, failed, path, field) {
        box.addEventListener('change', async function () {
            if (!box.checked && box.dataset.confirmOff && !window.confirm(box.dataset.confirmOff)) {
                box.checked = true;
                return;
            }
            box.disabled = true;
            failed.textContent = '';
            let saved = false;
            try {
                saved = (await post(path, { [field]: String(box.checked) })).ok;
            } catch (e) {
                saved = false;
            } finally {
                box.disabled = false;
                if (!saved) {
                    failed.textContent = text('deviceFailed');
                    box.checked = !box.checked;
                }
                await load();
            }
        });
    }
    savedAtOnce(setKept, setFailed, '/dj/devices/set', 'kept');
    savedAtOnce(nowShown, nowFailed, '/dj/devices/now-playing', 'shown');

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

    // every minute while the page is shown and a computer is paired: a program that stops during the set turns its line (and the
    // button) amber. None paired: nothing to watch — a pairing is seen by the code's own look (above) or when the page is shown again
    setInterval(function () {
        if (document.visibilityState === 'visible' && count > 0) load();
    }, REFRESH_MS);

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
