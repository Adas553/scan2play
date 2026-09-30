// What the dashboard says after the DJ saves a playlist (dashboard.js showFallbackImportResult; PROJECT_CONTEXT.md Section 5.4,
// "DJ actions on the fallback playlist"). The server has always answered with X-Fallback-Import ok|failed, X-Fallback-Tracks and
// X-Fallback-Import-Reason — and the dashboard ignored them: the Save button flashed green whatever happened, so a private or wrong
// playlist ended in an empty "up next" list without a word.
//
// The stand-in answers POST fallback-playlist as its state `fallbackSave` says. The expected texts are read from the data attributes
// of #fallbackImportStatus (the bundle's texts, in the page's language), so the scenario does not repeat the wording.
S2P.scenario({
    name: 'import-result',
    title: 'Save says how the import went: tracks queued, or why not; the button flashes green or red; Stop clears the message',
    setup: {},
    run: async function (t) {
        const NEW = 'PLnew00000000000000000000000000000';
        const box = document.getElementById('fallbackImportStatus');
        const saveButton = document.querySelector('#fallbackForm button[type="submit"]');
        const original = saveButton.textContent;
        const shown = function () { return !box.classList.contains('d-none'); };

        async function save(answer) {
            await t.stand.config({ fallbackSave: Object.assign({ playlistId: NEW, import: 'ok', tracks: 0, reason: null }, answer) });
            document.getElementById('fallbackInput').value = 'https://www.youtube.com/playlist?list=' + NEW;
            document.getElementById('fallbackForm').requestSubmit();
            await t.sleep(400);
        }

        t.check('before any Save the message box is hidden', !shown() && box.textContent === '');

        await save({ import: 'ok', tracks: 42 });
        t.step('a good import says how many tracks are queued', box.textContent, box.dataset.textOk.replace('{0}', '42'));
        t.check('… in green', shown() && box.classList.contains('text-success') && !box.classList.contains('text-danger'));
        t.step('… and the button flashes ✓ in green', [saveButton.textContent, saveButton.classList.contains('btn-success'), saveButton.classList.contains('btn-danger')], ['✓', true, false]);
        await t.sleep(1400);
        t.step('… and goes back to what it was after 1.5 s', [saveButton.textContent, saveButton.classList.contains('btn-success')], [original, false]);

        const reasons = [['NO_API_KEY', 'textNoapikey'], ['INVALID_PLAYLIST', 'textInvalidplaylist'], ['API_ERROR', 'textApierror'],
            ['NO_PLAYABLE_TRACKS', 'textNoplayabletracks'], ['SOMETHING_THE_SERVER_LEARNS_LATER', 'textUnknown']];
        for (const [reason, key] of reasons) {
            await save({ import: 'failed', reason: reason });
            t.check('the text for ' + reason + ' exists in the bundle', !!box.dataset[key]);
            t.step('a failed import (' + reason + ') says why', box.textContent, box.dataset[key]);
            t.check('… in red', shown() && box.classList.contains('text-danger') && !box.classList.contains('text-success'));
            t.step('… and the button flashes ✗ in red', [saveButton.textContent, saveButton.classList.contains('btn-danger'), saveButton.classList.contains('btn-success')], ['✗', true, false]);
            await t.sleep(1400);
        }
        t.step('the button is itself again', [saveButton.textContent, saveButton.classList.contains('btn-danger')], [original, false]);

        await save({ import: 'ok', tracks: 3 });
        t.check('a good import after a failed one replaces the message', box.textContent === box.dataset.textOk.replace('{0}', '3') && box.classList.contains('text-success'));
        window.stopFallbackPlaylist();   // the real Stop button
        await t.sleep(400);
        t.check('Stop clears the message', !shown() && box.textContent === '');

        await save({ import: 'failed', reason: 'API_ERROR' });
        document.getElementById('fallbackInput').value = '';
        document.getElementById('fallbackForm').requestSubmit();   // Save with an empty field clears the playlist too
        await t.sleep(400);
        t.check('Save with an empty field (the playlist is cleared) clears the message', !shown() && box.textContent === '');
    }
});
