/**
 * "Twoja biblioteka" on the page "Ustawienia imprezy" (settings.html, V41): the chosen file is sent as it is, as the request's body
 * (DjLibraryController — not a form's upload, which the server would read before it checks who sent it). The server answers in one
 * sentence: shown under the form; after a library was saved the page loads again, so its state and "Usuń bibliotekę" are the server's.
 * A rekordbox collection's playlists (V42) are read from the chosen file here, in the browser, and offered as "Co wgrać": the whole
 * collection or one playlist, named by its folders as the server names it (DjLibraryParser: "Kluby / Piątek").
 *
 * Dependencies (DOM): <form id="libraryForm"> with the party code, a file input and data-sending / data-refused; <p id="libraryAnswer">;
 * <div id="libraryPlaylistField" hidden> with <select id="libraryPlaylist"> (its first option the whole collection).
 */
import { csrfHeaders } from './common.js';

/** The answers that carry the server's own sentence: saved, too large, not a library. */
const SENTENCES = new Set([200, 413, 422]);

/** The text of an XML attribute's value: its five entities and the numbered ones. */
function unescapeXml(value) {
    return value.replace(/&(#x[0-9a-fA-F]+|#\d+|amp|lt|gt|quot|apos);/g, function (entity, name) {
        if (name[0] === '#') {
            return String.fromCodePoint(name[1] === 'x' ? parseInt(name.slice(2), 16) : parseInt(name.slice(1), 10));
        }
        return { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" }[name];
    });
}

function attribute(attributes, name) {
    const found = new RegExp('\\b' + name + '="([^"]*)"').exec(attributes);
    return found ? unescapeXml(found[1]) : null;
}

/**
 * The playlists of a rekordbox collection's text, in its order: the NODEs after <PLAYLISTS>, folders (Type="0", the tree's own ROOT
 * not named) and playlists (Type="1") — each playlist by the folders it is in, "Kluby / Piątek". None in another file.
 */
export function playlistsOf(text) {
    const start = text.indexOf('<PLAYLISTS');
    if (start < 0) return [];
    const names = [];
    const folders = [];
    const node = /<NODE\b([^>]*?)(\/?)>|<\/NODE>/g;
    node.lastIndex = start;
    let found;
    while ((found = node.exec(text)) !== null) {
        if (found[0] === '</NODE>') {
            folders.pop();
            continue;
        }
        const name = attribute(found[1], 'Name') || '';
        const playlist = attribute(found[1], 'Type') === '1';
        if (playlist) {
            names.push(folders.filter(Boolean).concat(name).join(' / '));
        }
        if (found[2] !== '/') {
            folders.push(playlist || (folders.length === 0 && name === 'ROOT') ? '' : name);
        }
    }
    return names;
}

(function initLibraryUpload() {
    const form = document.getElementById('libraryForm');
    if (!form) return;
    const answer = document.getElementById('libraryAnswer');
    const field = document.getElementById('libraryPlaylistField');
    const select = document.getElementById('libraryPlaylist');
    const input = form.querySelector('input[type="file"]');

    // a file chosen: its playlists offered when it is a rekordbox collection, else nothing to choose
    input.addEventListener('change', async function () {
        const file = input.files[0];
        while (select.options.length > 1) select.remove(1);
        select.value = '';
        field.hidden = true;
        if (!file) return;
        const names = playlistsOf(await file.text());
        if (input.files[0] !== file || names.length === 0) return;
        names.forEach(function (name) {
            select.add(new Option(name, name));
        });
        field.hidden = false;
    });

    function say(text, refused) {
        answer.textContent = text;
        answer.classList.toggle('text-danger', refused);
        answer.classList.toggle('text-success', !refused);
        answer.hidden = false;
    }

    form.addEventListener('submit', async function (e) {
        e.preventDefault();   // forms.js leaves a form whose submit was taken here
        const file = form.querySelector('input[type="file"]').files[0];
        if (!file) return;
        const button = form.querySelector('button[type="submit"]');
        const partyCode = form.querySelector('input[name="partyCode"]').value;
        button.disabled = true;
        say(form.dataset.sending, false);
        try {
            const playlist = field.hidden ? '' : select.value;
            const response = await fetch(form.action + '?partyCode=' + encodeURIComponent(partyCode)
                + (playlist ? '&playlist=' + encodeURIComponent(playlist) : ''), {
                method: 'POST',
                headers: Object.assign({ 'Content-Type': 'application/octet-stream' }, csrfHeaders()),
                body: file
            });
            const text = SENTENCES.has(response.status) ? await response.text() : '';
            if (response.ok) {
                say(text, false);
                location.reload();
                return;
            }
            say(text || form.dataset.refused, true);
        } catch (err) {
            say(form.dataset.refused, true);
        }
        button.disabled = false;
    });
})();
