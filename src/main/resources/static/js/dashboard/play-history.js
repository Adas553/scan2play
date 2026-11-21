/**
 * "Zagrane samo" (docs/ideas.md item 18, variant E): the panel reads the play history VirtualDJ or Serato writes on this computer and
 * sends each new song — its artist, title and time, never a file's path — to the server, which marks the queue's waiting request of
 * that song played (DeckHistoryService). No program to install: the DJ picks the history folder once (the File System Access API,
 * Chrome and Edge on a computer; elsewhere the button stays hidden) and the panel's tab stays open while the set plays.
 *
 *   VirtualDJ  %LOCALAPPDATA%\VirtualDJ\History\<date>.m3u (a recent install on Windows; older ones Documents/VirtualDJ/History)
 *              — a line "#EXTVDJ:<time>…<lastplaytime>…<artist>…<title>…" appended once
 *              a song has played (its setting historyDelay)
 *   Serato     Music/_Serato_/History/Sessions/<n>.session — binary chunks (4 letters, a 4-byte length); a song an "oent" with
 *              its "adat" fields (6 title, 7 artist, 28 start, 50 played), text in UTF-16BE; appended while Serato runs
 *
 * The folder may be picked a level or two above (VirtualDJ, _Serato_, History). The newest file of the folder is read every few
 * seconds when it changed; the songs that played more than 10 minutes before the reading started are tonight's earlier set (or
 * another day's) and are not sent. The folder is remembered (IndexedDB, the handle itself): after a page load the panel reads on if
 * the browser still allows it, else it offers "Wznów czytanie".
 *
 * Dependencies (DOM): <button id="deckToggle" hidden>, <section id="deckReader" hidden> with its texts in data-* (resume, reading,
 * marked, none, denied, failed, refused — "{…}" filled in here), #deckPick, #deckResume, #deckStop, #deckStatus, #deckMarked.
 */
import { csrfHeaders, partyCode } from './common.js';
import { emit, EVENTS } from './events.js';

const READ_EVERY_MS = 5000;           // the newest file, when it changed
const SCAN_EVERY_MS = 30000;          // which file is the newest (VirtualDJ starts a new one each day, Serato each session)
const EARLIER_SET_MS = 10 * 60 * 1000;
const MAX_PER_CALL = 20;              // DeckHistoryService.MAX_PLAYS

// ---- the formats (exported for the browser scenarios) ----

const XML_ENTITIES = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" };

function unescapeXml(text) {
    return text.replace(/&(#x[0-9a-fA-F]+|#\d+|amp|lt|gt|quot|apos);/g, function (all, name) {
        if (name[0] === '#') return String.fromCodePoint(name[1] === 'x' ? parseInt(name.slice(2), 16) : parseInt(name.slice(1), 10));
        return XML_ENTITIES[name];
    });
}

/** The songs of a VirtualDJ history file, in its order: {at (seconds since 1970, or null), artist, title}. */
export function virtualDjSongs(text) {
    const songs = [];
    text.split(/\r?\n/).forEach(function (line) {
        if (line.indexOf('#EXTVDJ:') !== 0) return;
        const tags = {};
        const tag = /<(\w+)>([\s\S]*?)<\/\1>/g;
        let found;
        while ((found = tag.exec(line)) !== null) tags[found[1]] = unescapeXml(found[2]);
        const at = /^\d+$/.test(tags.lastplaytime || '') ? parseInt(tags.lastplaytime, 10) : null;
        if (tags.artist || tags.title) songs.push({ at: at, artist: tags.artist || '', title: tags.title || '' });
    });
    return songs;
}

const UTF16 = new TextDecoder('utf-16be');

function* chunks(view, from, to) {
    let i = from;
    while (i + 8 <= to) {
        const name = String.fromCharCode(view.getUint8(i), view.getUint8(i + 1), view.getUint8(i + 2), view.getUint8(i + 3));
        const length = view.getUint32(i + 4);
        const end = Math.min(i + 8 + length, to);
        yield { name: name, start: i + 8, end: end };
        i += 8 + length;
    }
}

function seratoFields(view, from, to) {
    const fields = {};
    let i = from;
    while (i + 8 <= to) {
        const field = view.getUint32(i);
        const length = view.getUint32(i + 4);
        const start = i + 8;
        const end = Math.min(start + length, to);
        if (field === 6 || field === 7) {
            fields[field === 6 ? 'title' : 'artist'] = UTF16.decode(new Uint8Array(view.buffer, view.byteOffset + start, end - start))
                .replace(/\u0000+$/, '');
        } else if (field === 28 && (end - start === 4 || end - start === 8)) {
            fields.at = end - start === 4 ? view.getUint32(start) : Number(view.getBigUint64(start));
        } else if (field === 50 && end > start) {
            fields.played = view.getUint8(start) !== 0;
        }
        i += 8 + length;
    }
    return fields;
}

/** The songs of a Serato session file by their start: {at, artist, title}; a song loaded on a deck and marked not played left out. */
export function seratoSongs(buffer) {
    const view = new DataView(buffer);
    const songs = [];
    for (const entry of chunks(view, 0, view.byteLength)) {
        if (entry.name !== 'oent') continue;
        for (const inner of chunks(view, entry.start, entry.end)) {
            if (inner.name !== 'adat') continue;
            const fields = seratoFields(view, inner.start, inner.end);
            if (fields.played === false || !(fields.artist || fields.title)) continue;
            songs.push({ at: fields.at || null, artist: fields.artist || '', title: fields.title || '' });
        }
    }
    return songs.sort(function (a, b) { return (a.at || 0) - (b.at || 0); });
}

const PROGRAMS = [
    { name: 'VirtualDJ', extension: '.m3u', read: function (file) { return file.text().then(virtualDjSongs); } },
    { name: 'Serato', extension: '.session', read: function (file) { return file.arrayBuffer().then(seratoSongs); } }
];

// ---- the folder ----

async function child(dir, name) {
    try {
        return await dir.getDirectoryHandle(name);
    } catch (e) {
        return null;
    }
}

/** The newest file of the folder with the extension, {handle, name, lastModified}, or null. */
async function newestFile(dir, extension) {
    let newest = null;
    for await (const entry of dir.values()) {
        if (entry.kind !== 'file' || !entry.name.toLowerCase().endsWith(extension)) continue;
        const file = await entry.getFile();
        if (newest === null || file.lastModified > newest.lastModified) {
            newest = { handle: entry, name: entry.name, lastModified: file.lastModified };
        }
    }
    return newest;
}

/** The folder picked, or the history folder under it: {dir, program} of the first that holds a program's files, or null. */
async function historyFolder(picked) {
    const history = await child(picked, 'History');
    const candidates = [picked, history && await child(history, 'Sessions'), await child(picked, 'Sessions'), history];
    for (const dir of candidates) {
        if (!dir) continue;
        for (const program of PROGRAMS) {
            if (await newestFile(dir, program.extension)) return { dir: dir, program: program };
        }
    }
    return null;
}

// ---- remembering the folder (a handle is stored as it is; a browser that cannot keep it just asks again next time) ----

function store(mode, action) {
    return new Promise(function (resolve) {
        try {
            const open = indexedDB.open('s2p-deck', 1);
            open.onupgradeneeded = function () { open.result.createObjectStore('folder'); };
            open.onerror = function () { resolve(null); };
            open.onsuccess = function () {
                try {
                    const request = action(open.result.transaction('folder', mode).objectStore('folder'));
                    request.onsuccess = function () { resolve(request.result || null); };
                    request.onerror = function () { resolve(null); };
                } catch (e) {
                    resolve(null);   // a handle the browser cannot clone
                }
            };
        } catch (e) {
            resolve(null);
        }
    });
}

const remember = function (handle) { return store('readwrite', function (s) { return s.put(handle, 'picked'); }); };
const forget = function () { return store('readwrite', function (s) { return s.delete('picked'); }); };
const remembered = function () { return store('readonly', function (s) { return s.get('picked'); }); };

// ---- the reader ----

const toggle = document.getElementById('deckToggle');
const reader = document.getElementById('deckReader');

/**
 * Chrome and Edge on a computer; Firefox, Safari and the phones' browsers have no folder picker. A narrow screen is no DJ's laptop
 * either, and the queue's tools share one line there (scenario library-tools-one-row-phone).
 */
function canReadFolders() {
    return typeof window.showDirectoryPicker === 'function' && window.matchMedia('(min-width: 768px)').matches;
}

if (toggle && reader && canReadFolders()) {
    const pick = document.getElementById('deckPick');
    const resume = document.getElementById('deckResume');
    const stop = document.getElementById('deckStop');
    const status = document.getElementById('deckStatus');
    const markedLine = document.getElementById('deckMarked');
    const text = function (name, values) {
        return (reader.dataset[name] || '').replace(/\{(\w+)\}/g, function (all, key) { return values && key in values ? values[key] : all; });
    };

    let picked = null;      // the folder the DJ picked
    let folder = null;      // {dir, program}
    let current = null;     // the newest file, as last seen
    let scannedAt = 0;
    let startedAt = 0;
    let firstRead = true;
    let timer = null;
    let sending = false;
    const seen = new Set();
    let waiting = [];       // read, not sent yet

    const reading = function (on) {
        stop.hidden = !on;
        resume.hidden = true;
        toggle.classList.toggle('btn-outline-success', on);
        toggle.classList.toggle('btn-outline-light', !on);
    };

    const halt = function (message) {
        clearInterval(timer);
        timer = null;
        reading(false);
        status.textContent = message || '';
    };

    async function send() {
        if (sending || waiting.length === 0) return;
        sending = true;
        const batch = waiting.slice(0, MAX_PER_CALL);
        try {
            const response = await fetch('/dj/dashboard/plays', {
                method: 'POST',
                headers: Object.assign({ 'Content-Type': 'application/json', 'X-Requested-With': 'fetch' }, csrfHeaders()),
                body: JSON.stringify({ partyCode: partyCode(), plays: batch })
            });
            if (response.status === 401 || response.status === 403) {
                halt(text('refused'));
                return;
            }
            if (response.ok || response.status === 400) waiting = waiting.slice(batch.length);   // a 400 would come again
            if (!response.ok) {
                if (response.status !== 400) status.textContent = text('failed');
                return;
            }
            const marked = (await response.json()).marked || [];
            if (marked.length > 0) {
                markedLine.textContent = marked.map(function (song) { return text('marked', { song: song }); }).join(' · ');
                emit(EVENTS.GUEST_QUEUE_CHANGED);
            }
        } catch (e) {
            status.textContent = text('failed');
        } finally {
            sending = false;
        }
    }

    async function look() {
        try {
            if (current === null || Date.now() - scannedAt > SCAN_EVERY_MS) {
                scannedAt = Date.now();
                const newest = await newestFile(folder.dir, folder.program.extension);
                if (newest && (current === null || newest.name !== current.name)) current = Object.assign(newest, { lastModified: -1 });
            }
            if (current === null) return;
            const file = await current.handle.getFile();
            if (file.lastModified !== current.lastModified) {
                current.lastModified = file.lastModified;
                const earlier = (startedAt - EARLIER_SET_MS) / 1000;
                (await folder.program.read(file)).forEach(function (song) {
                    const key = song.at + '|' + song.artist + '|' + song.title;
                    if (seen.has(key)) return;
                    seen.add(key);
                    const fresh = song.at === null ? !firstRead : song.at >= earlier;
                    if (fresh) waiting.push(song);
                });
                firstRead = false;
            }
            status.textContent = text('reading', {
                program: folder.program.name, file: current.name,
                time: new Date().toLocaleTimeString(document.documentElement.lang || undefined, { hour: '2-digit', minute: '2-digit' })
            });
            await send();
        } catch (e) {
            if (e && e.name === 'NotAllowedError') {
                halt(text('denied'));
            } else {
                current = null;   // the file went (a new day's, a session closed): look for the newest again
            }
        }
    }

    async function start(handle) {
        const found = await historyFolder(handle);
        if (!found) {
            halt(text('none'));
            return;
        }
        picked = handle;
        folder = found;
        current = null;
        firstRead = true;
        startedAt = Date.now();
        clearInterval(timer);
        reading(true);
        await look();
        timer = setInterval(look, READ_EVERY_MS);
    }

    toggle.hidden = false;
    toggle.addEventListener('click', function () {
        const open = reader.hidden;
        reader.hidden = !open;
        toggle.setAttribute('aria-expanded', String(open));
    });

    pick.addEventListener('click', async function () {
        let handle;
        try {
            handle = await window.showDirectoryPicker({ id: 's2p-deck', mode: 'read' });
        } catch (e) {
            return;   // the DJ closed the picker
        }
        await remember(handle);
        await start(handle);
    });

    stop.addEventListener('click', function () {
        picked = null;
        forget();
        halt('');
        markedLine.textContent = '';
    });

    resume.addEventListener('click', async function () {
        if (!picked) return;
        try {
            if (await picked.requestPermission({ mode: 'read' }) !== 'granted') return;
        } catch (e) {
            return;
        }
        await start(picked);
    });

    remembered().then(async function (handle) {
        if (!handle || typeof handle.queryPermission !== 'function') return;
        picked = handle;
        if (await handle.queryPermission({ mode: 'read' }) === 'granted') {
            await start(handle);
        } else {
            resume.textContent = text('resume', { folder: handle.name });
            resume.hidden = false;
        }
    });
}
