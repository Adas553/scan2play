/**
 * "Twoja biblioteka" on the page "Ustawienia imprezy" (settings.html, V41): the chosen file is sent as it is, as the request's body
 * (DjLibraryController — not a form's upload, which the server would read before it checks who sent it). The server answers in one
 * sentence: shown under the form; after a library was saved — or deleted ("Usuń bibliotekę", sent here too) — the card is brought
 * again from the server and put in place (refreshCard), so its state is the server's and the page does not move.
 * A rekordbox collection's playlists (V42) are read from the chosen file here, in the browser, and offered as "Co wgrać": the whole
 * collection or one playlist, named by its folders as the server names it (DjLibraryParser: "Kluby / Piątek"); a second playlist of the
 * same name is offered as "Kluby / Piątek (2)" and sent with its occurrence. A file larger than the server reads (data-max-bytes) is
 * refused here, before it is read or sent: a film picked by mistake would fill a phone's memory as text; only an .xml is read for its
 * playlists.
 *
 * Dependencies (DOM): <section id="libraryCard"> with <form id="libraryForm"> (the party code, a file input, data-sending /
 * data-refused), <p id="libraryAnswer"> and, with a library, <form id="libraryDeleteForm">;
 * <div id="libraryPlaylistField" hidden> with <select id="libraryPlaylist"> (its first option the whole collection).
 */
import { csrfHeaders } from './common.js';

/** The answers that carry the server's own sentence: saved, too large, not a library, another file being read. */
const SENTENCES = new Set([200, 413, 422, 503]);

/**
 * The server's sentence in an answer, or '' — only from a plain-text answer: a proxy's own page for a status (Cloudflare's 413 for
 * more than 100 MB) is HTML, never a sentence for the card.
 */
async function sentenceOf(response) {
    const type = response.headers.get('Content-Type') || '';
    return SENTENCES.has(response.status) && type.indexOf('text/plain') === 0 ? response.text() : '';
}

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

/**
 * The card "Twoja biblioteka" as the server has it now — after an upload or "Usuń bibliotekę" — put in place of the one on the screen,
 * its listeners bound again: the page neither loads again nor moves (a page load drew the page from the top and the browser then
 * scrolled back down to the card). The server's sentence is said in the new card. No card in the answer: the page is loaded again.
 */
async function refreshCard(sentence) {
    const card = document.getElementById('libraryCard');
    try {
        const response = await fetch(location.href, { headers: { Accept: 'text/html' } });
        const fresh = response.ok
            ? new DOMParser().parseFromString(await response.text(), 'text/html').getElementById('libraryCard') : null;
        if (!card || !fresh) {
            location.reload();
            return;
        }
        card.replaceWith(fresh);
        initLibraryCard(fresh);
        if (sentence) say(fresh, sentence, false);
    } catch (err) {
        location.reload();
    }
}

/** The card's one sentence under the form: the server's answer, or why nothing was saved. */
function say(card, text, refused) {
    const answer = card.querySelector('#libraryAnswer');
    if (!answer) return;
    answer.textContent = text;
    answer.classList.toggle('text-danger', refused);
    answer.classList.toggle('text-success', !refused);
    answer.hidden = false;
}

function initLibraryCard(card) {
    const form = card.querySelector('#libraryForm');
    const remove = card.querySelector('#libraryDeleteForm');

    // "Usuń bibliotekę": the form sent in the background (forms.js leaves it: its submit is taken here), then the card again
    if (remove) {
        remove.addEventListener('submit', async function (e) {
            e.preventDefault();
            remove.querySelector('button[type="submit"]').disabled = true;
            try {
                // the answer is a redirect to the settings page: the card is asked for on its own
                await fetch(remove.action, { method: 'POST', headers: csrfHeaders(), body: new URLSearchParams(new FormData(remove)),
                    redirect: 'manual' });
            } finally {
                await refreshCard(null);
            }
        });
    }
    if (!form) return;
    const field = card.querySelector('#libraryPlaylistField');
    const select = card.querySelector('#libraryPlaylist');
    const input = form.querySelector('input[type="file"]');
    const maxBytes = Number(form.dataset.maxBytes) || Infinity;

    // a file chosen: its playlists offered when it is a rekordbox collection, else nothing to choose
    input.addEventListener('change', async function () {
        const file = input.files[0];
        while (select.options.length > 1) select.remove(1);
        select.value = '';
        field.hidden = true;
        const answer = card.querySelector('#libraryAnswer');
        if (answer) answer.hidden = true;
        if (!file) return;
        if (file.size > maxBytes) {
            say(card, form.dataset.tooLarge, true);
            return;
        }
        if (!/\.xml$/i.test(file.name)) return;
        const names = playlistsOf(await file.text());
        if (input.files[0] !== file || names.length === 0) return;
        const seen = {};
        names.forEach(function (name) {
            const occurrence = seen[name] = (seen[name] || 0) + 1;
            const option = new Option(occurrence > 1 ? name + ' (' + occurrence + ')' : name, name);
            option.dataset.occurrence = String(occurrence);
            select.add(option);
        });
        field.hidden = false;
    });

    form.addEventListener('submit', async function (e) {
        e.preventDefault();   // forms.js leaves a form whose submit was taken here
        const file = input.files[0];
        if (!file) return;
        if (file.size > maxBytes) {
            say(card, form.dataset.tooLarge, true);
            return;
        }
        const button = form.querySelector('button[type="submit"]');
        const partyCode = form.querySelector('input[name="partyCode"]').value;
        button.disabled = true;
        say(card, form.dataset.sending, false);
        try {
            const playlist = field.hidden ? '' : select.value;
            const occurrence = playlist ? select.options[select.selectedIndex].dataset.occurrence : '1';
            const response = await fetch(form.action + '?partyCode=' + encodeURIComponent(partyCode)
                + (playlist ? '&playlist=' + encodeURIComponent(playlist) : '')
                + (occurrence && occurrence !== '1' ? '&occurrence=' + occurrence : ''), {
                method: 'POST',
                headers: Object.assign({ 'Content-Type': 'application/octet-stream' }, csrfHeaders()),
                body: file
            });
            const text = await sentenceOf(response);
            if (response.ok) {
                await refreshCard(text);
                return;
            }
            say(card, text || form.dataset.refused, true);
        } catch (err) {
            say(card, form.dataset.refused, true);
        }
        button.disabled = false;
    });
}

const libraryCard = document.getElementById('libraryCard');
if (libraryCard) initLibraryCard(libraryCard);
