// A WINDOW IN THE BACKGROUND (review item 3.4; polling.js). A dashboard that is hidden — the DJ's phone in a pocket, a second tab, the
// dashboard behind the DJ's own software — does not poll the guest queue at all; when it is shown again it asks at once.
//
// Hidden and shown are simulated: document.visibilityState / document.hidden are redefined and a visibilitychange is sent, as the
// browser does when a tab goes to the background.
(function () {
    const UPDATES = 'GET /dj/dashboard/updates';

    const setHidden = function (hidden) {
        Object.defineProperty(document, 'visibilityState', { configurable: true, get: function () { return hidden ? 'hidden' : 'visible'; } });
        Object.defineProperty(document, 'hidden', { configurable: true, get: function () { return hidden; } });
        document.dispatchEvent(new Event('visibilitychange'));
    };

    S2P.scenario({
        name: 'background-asks-at-once-when-shown',
        title: 'the dashboard behind the DJ\'s software: no poll while hidden; shown again, the new requests come at once',
        run: async function (t) {
            setHidden(true);
            await t.sleep(3500);
            const before = await t.stand.count(UPDATES);
            await t.sleep(7000);
            t.step('hidden: no poll of the queue in 7 s', (await t.stand.count(UPDATES)) - before, 0);

            await t.stand.config({ queue: [{ id: 9, name: 'Sent while hidden', url: 'https://www.youtube.com/results?search_query=x' }] });
            setHidden(false);
            await t.sleep(1000);
            t.check('shown again: the request sent meanwhile is on the list within a second',
                document.querySelector('#song-list tr[data-song-id="9"]') !== null);
        }
    });
})();
