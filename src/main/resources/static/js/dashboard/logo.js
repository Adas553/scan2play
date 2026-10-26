/**
 * "Twoje logo" on the page "Ustawienia imprezy" (settings.html, V45): the chosen image is read here, in the browser — a PNG, a JPEG
 * or a WebP (the server reads no WebP) —, drawn at most data-max-side px a side on the preview's canvas and sent as that PNG, the
 * request's body (PartyLogoController); the server checks it again and makes its own PNG. A file larger than data-max-bytes, or of
 * another kind (an SVG among them), is refused here before it is read. After a save or "Usuń" the card is brought again from the
 * server and put in place, as the library's (library.js).
 *
 * Dependencies (DOM): <details id="logoCard"> with <form id="logoForm"> (the party code, a file input, <label id="logoDrop"> with
 * [data-file-name data-empty], <div id="logoPreview" hidden> with <canvas id="logoCanvas">, a submit button, <p id="logoAnswer">,
 * data-sending / data-refused / data-unreadable / data-not-image / data-max-bytes / data-max-side / data-too-large) and, with a
 * logo, <form id="logoDeleteForm">.
 */
import { csrfHeaders } from './common.js';

/** The kinds read here; anything else — an SVG, a GIF, a HEIC a browser could not draw — is not taken. */
const KINDS = /^image\/(png|jpeg|webp)$/;

/** The answers that carry the server's own sentence: saved, too large, not an image, busy. */
const SENTENCES = new Set([200, 413, 422, 503]);

async function sentenceOf(response) {
    const type = response.headers.get('Content-Type') || '';
    return SENTENCES.has(response.status) && type.indexOf('text/plain') === 0 ? response.text() : '';
}

function say(card, text, refused) {
    const answer = card.querySelector('#logoAnswer');
    if (!answer) return;
    answer.textContent = text;
    answer.classList.toggle('text-danger', refused);
    answer.classList.toggle('text-success', !refused);
    answer.hidden = false;
}

/** The card as the server has it now, put in place of the one on the screen; without one in the answer, the page again. */
async function refreshCard(sentence) {
    const card = document.getElementById('logoCard');
    try {
        const response = await fetch(location.href, { headers: { Accept: 'text/html' } });
        const fresh = response.ok
            ? new DOMParser().parseFromString(await response.text(), 'text/html').getElementById('logoCard') : null;
        if (!card || !fresh) {
            location.reload();
            return;
        }
        fresh.open = card.open;
        card.replaceWith(fresh);
        initLogoCard(fresh);
        if (sentence) say(fresh, sentence, false);
    } catch (err) {
        location.reload();
    }
}

/** The image drawn on the canvas at most maxSide px a side, its proportions kept; never enlarged. */
async function draw(file, canvas, maxSide) {
    const bitmap = await createImageBitmap(file);
    try {
        const factor = Math.min(1, maxSide / Math.max(bitmap.width, bitmap.height));
        canvas.width = Math.max(1, Math.round(bitmap.width * factor));
        canvas.height = Math.max(1, Math.round(bitmap.height * factor));
        const context = canvas.getContext('2d');
        context.imageSmoothingQuality = 'high';
        context.clearRect(0, 0, canvas.width, canvas.height);
        context.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    } finally {
        bitmap.close();
    }
}

function initLogoCard(card) {
    const remove = card.querySelector('#logoDeleteForm');
    if (remove) {
        remove.addEventListener('submit', async function (e) {
            e.preventDefault();   // asked first by data-confirm (dj-nav.js); forms.js leaves a form whose submit was taken here
            remove.querySelector('button[type="submit"]').disabled = true;
            try {
                await fetch(remove.action, { method: 'POST', headers: csrfHeaders(), body: new URLSearchParams(new FormData(remove)),
                    redirect: 'manual' });
            } finally {
                await refreshCard(null);
            }
        });
    }
    const form = card.querySelector('#logoForm');
    if (!form) return;
    const input = form.querySelector('input[type="file"]');
    const drop = card.querySelector('#logoDrop');
    const fileName = drop.querySelector('[data-file-name]');
    const preview = card.querySelector('#logoPreview');
    const canvas = card.querySelector('#logoCanvas');
    const upload = form.querySelector('button[type="submit"]');
    const maxBytes = Number(form.dataset.maxBytes) || Infinity;
    const maxSide = Number(form.dataset.maxSide) || 512;

    /** Nothing to send: the field says "Wybierz obraz" again, or the file's name with why it is refused. */
    function refuse(file, text) {
        fileName.textContent = file ? file.name : fileName.dataset.empty;
        drop.classList.toggle('is-chosen', !!file);
        preview.hidden = true;
        upload.disabled = true;
        if (text) say(card, text, true);
    }

    ['dragenter', 'dragover'].forEach(function (type) {
        drop.addEventListener(type, function (e) {
            e.preventDefault();
            drop.classList.add('is-dragover');
        });
    });
    ['dragleave', 'drop'].forEach(function (type) {
        drop.addEventListener(type, function () { drop.classList.remove('is-dragover'); });
    });
    drop.addEventListener('drop', function (e) {
        e.preventDefault();
        if (!e.dataTransfer || e.dataTransfer.files.length === 0) return;
        input.files = e.dataTransfer.files;
        input.dispatchEvent(new Event('change', { bubbles: true }));
    });

    input.addEventListener('change', async function () {
        const file = input.files[0];
        const answer = card.querySelector('#logoAnswer');
        if (answer) answer.hidden = true;
        if (!file) return refuse(null, '');
        if (!KINDS.test(file.type)) return refuse(file, form.dataset.notImage);
        if (file.size > maxBytes) return refuse(file, form.dataset.tooLarge);
        try {
            await draw(file, canvas, maxSide);
        } catch (err) {
            return refuse(file, form.dataset.unreadable);
        }
        if (input.files[0] !== file) return;   // another file chosen meanwhile
        fileName.textContent = file.name;
        drop.classList.add('is-chosen');
        preview.hidden = false;
        upload.disabled = false;
    });

    form.addEventListener('submit', async function (e) {
        e.preventDefault();
        if (preview.hidden) return;
        const partyCode = form.querySelector('input[name="partyCode"]').value;
        upload.disabled = true;
        say(card, form.dataset.sending, false);
        try {
            const png = await new Promise(function (resolve) { canvas.toBlob(resolve, 'image/png'); });
            if (!png) throw new Error('no PNG');
            const response = await fetch(form.action + '?partyCode=' + encodeURIComponent(partyCode), {
                method: 'POST',
                headers: Object.assign({ 'Content-Type': 'application/octet-stream' }, csrfHeaders()),
                body: png
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
        upload.disabled = false;
    });
}

const logoCard = document.getElementById('logoCard');
if (logoCard) initLogoCard(logoCard);
