// A YouTube Mix pasted as the playlist (the owner's report, 2026-09-30: "ta playlista nie może się zapisać, nie wiem czemu"). A link
// with list=RD… is a Mix that YouTube makes up for one viewer; the Data API does not give it out, so the import failed with the
// general "could not fetch the playlist" and the link stayed saved (the server kept trying to import it). Now the server refuses it
// before saving (X-Fallback-Saved: false, reason YOUTUBE_MIX): the dashboard says what a Mix is, and the party's playlist — and the
// track that plays from it — carry on as if Save had not been pressed.
(function () {
    const PLAYLIST = 'PLscenario0000000000000000000000000';
    const background = function (id, videoId) { return { source: 'BACKGROUND', id: id, videoId: videoId, playlistId: PLAYLIST }; };

    S2P.scenario({
        name: 'youtube-mix-refused',
        title: 'a YouTube Mix is refused with its own message; the playlist that plays is not touched',
        setup: { lease: { fallbackPlaylistId: PLAYLIST }, nextTracks: [background(1, 'aaaaaaaaaaA'), background(2, 'bbbbbbbbbbB')] },
        run: async function (t) {
            const fake = t.fake;
            const box = document.getElementById('fallbackImportStatus');
            const stopButton = document.getElementById('fallbackStopBtn');
            await t.waitForTrack(1, 'a track of the playlist');
            const stopsBefore = fake.calls.filter(function (c) { return c[0] === 'stopVideo'; }).length;
            const stopShownBefore = !stopButton.classList.contains('d-none');

            await t.stand.config({ fallbackSave: { saved: false, import: 'failed', reason: 'YOUTUBE_MIX' } });
            document.getElementById('fallbackInput').value = 'https://www.youtube.com/watch?v=3z-jNRAwSHk&list=RD3z-jNRAwSHk';
            document.getElementById('fallbackForm').requestSubmit();
            await t.sleep(600);

            t.check('the bundle has a text for a Mix', !!box.dataset.textYoutubemix);
            t.step('the dashboard says what a Mix is', box.textContent, box.dataset.textYoutubemix);
            t.check('… in red', !box.classList.contains('d-none') && box.classList.contains('text-danger'));
            t.step('the track of the playlist plays on: nothing stopped, nothing else loaded',
                [fake.calls.filter(function (c) { return c[0] === 'stopVideo'; }).length - stopsBefore, fake.loads.length, fake.state],
                [0, 1, 1]);
            t.step('the Stop button of the playlist stays as it was', !stopButton.classList.contains('d-none'), stopShownBefore);
        }
    });
})();
