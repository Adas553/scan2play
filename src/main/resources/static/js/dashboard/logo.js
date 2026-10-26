/**
 * "Twoje logo" on the page "Ustawienia imprezy" (settings.html, V45): the chosen image is read here, in the browser — a PNG, a JPEG
 * or a WebP (the server reads no WebP) —, drawn at most data-max-side px a side on the preview's canvas and sent as that PNG, the
 * request's body (PartyLogoController); the server checks it again and makes its own PNG. Its empty margins are cut off first
 * (contentBox): a logo saved in the middle of a large square would show as a dot. A file larger than data-max-bytes, or of
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

/** How far a pixel's colour may be from the background's and still be background (a JPEG's noise, a faint shadow). */
const BACKGROUND_DISTANCE = 40;

/**
 * The part of the picture that is the logo: its empty margins cut off — transparent ones, or ones of the colour all four corners
 * share (a logo saved on a white or grey square) — with a margin of 4 % left.
 * The whole picture when its corners differ (a photo) or nothing stands out. Read from a copy at most 1024 px a side: enough to find
 * an edge, and fast on a phone.
 */
function contentBox(bitmap) {
    const scale = Math.min(1, 1024 / Math.max(bitmap.width, bitmap.height));
    const width = Math.max(1, Math.round(bitmap.width * scale));
    const height = Math.max(1, Math.round(bitmap.height * scale));
    const whole = { x: 0, y: 0, width: bitmap.width, height: bitmap.height };
    const sample = document.createElement('canvas');
    sample.width = width;
    sample.height = height;
    const context = sample.getContext('2d', { willReadFrequently: true });
    context.drawImage(bitmap, 0, 0, width, height);
    const data = context.getImageData(0, 0, width, height).data;
    const at = function (x, y) { const i = (y * width + x) * 4; return [data[i], data[i + 1], data[i + 2], data[i + 3]]; };
    const corners = [at(0, 0), at(width - 1, 0), at(0, height - 1), at(width - 1, height - 1)];
    const distance = function (a, b) { return Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]); };
    const transparent = corners.every(function (c) { return c[3] < 16; });
    const background = [0, 1, 2].map(function (k) { return corners.reduce(function (sum, c) { return sum + c[k]; }, 0) / 4; });
    if (!transparent && !corners.every(function (c) { return c[3] > 240 && distance(c, background) <= BACKGROUND_DISTANCE; })) {
        return whole;
    }
    let left = width, top = height, right = -1, bottom = -1;
    for (let y = 0; y < height; y++) {
        for (let x = 0; x < width; x++) {
            const i = (y * width + x) * 4;
            const content = data[i + 3] >= 16
                && (transparent || distance([data[i], data[i + 1], data[i + 2]], background) > BACKGROUND_DISTANCE);
            if (content) {
                if (x < left) left = x;
                if (x > right) right = x;
                if (y < top) top = y;
                if (y > bottom) bottom = y;
            }
        }
    }
    if (right < 0) return whole;
    const margin = Math.round(Math.max(right - left + 1, bottom - top + 1) * 0.04);
    left = Math.max(0, left - margin);
    top = Math.max(0, top - margin);
    right = Math.min(width - 1, right + margin);
    bottom = Math.min(height - 1, bottom + margin);
    return { x: left / scale, y: top / scale, width: (right - left + 1) / scale, height: (bottom - top + 1) / scale };
}

/** The logo — the picture without its empty margins — drawn on the canvas at most maxSide px a side; never enlarged. */
async function draw(file, canvas, maxSide) {
    const bitmap = await createImageBitmap(file);
    try {
        const box = contentBox(bitmap);
        const factor = Math.min(1, maxSide / Math.max(box.width, box.height));
        canvas.width = Math.max(1, Math.round(box.width * factor));
        canvas.height = Math.max(1, Math.round(box.height * factor));
        const context = canvas.getContext('2d');
        context.imageSmoothingQuality = 'high';
        context.clearRect(0, 0, canvas.width, canvas.height);
        context.drawImage(bitmap, box.x, box.y, box.width, box.height, 0, 0, canvas.width, canvas.height);
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
