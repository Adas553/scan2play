// THE PLAYER LEASE from the point of view of one dashboard window (youtube-autopilot.js: applyLease, takeOverPlayback, reportLease and the
// pagehide beacon; PROJECT_CONTEXT.md Section 5.4, "One window plays"). Only one window of a party plays; the others show a banner with a
// "play on this device" button. The rules of the server (who gets the lease, the 10 s timeout) are unit-tested on the server side
// (PlayerLeaseServiceTest); these scenarios are the other half — what the WINDOW does with what the server says. The stand-in is told the
// answers (POST /__config, "lease"), so "another window took over" is a config change, and a second real window is not needed.
(function () {
    const PLAYLIST = 'PLscenario0000000000000000000000000';
    const track = function (id, videoId) { return { source: 'BACKGROUND', id: id, videoId: videoId, playlistId: PLAYLIST }; };
    const banner = function () { return document.getElementById('playerLeaseBanner'); };
    const bannerShown = function () { return !banner().classList.contains('d-none'); };
    const reports = function (t, mode) {
        return t.stand.requests(t.LEASE).then(function (all) { return all.filter(function (r) { return !mode || r.q.mode === mode; }); });
    };

    S2P.scenario({
        name: 'lease-lost-and-taken-back',
        title: 'another window takes the lease: this one stops and only watches; "play here" asks first (a "no" sends nothing), a "yes" takes the lease and plays again',
        setup: { lease: { fallbackPlaylistId: PLAYLIST }, nextTracks: [track(1, 'aaaaaaaaaaA'), track(2, 'bbbbbbbbbbB')] },
        run: async function (t) {
            const asked = [];              // what confirm() was asked, and what the DJ "answers" to it
            let answer = false;
            window.confirm = function (text) { asked.push(text); return answer; };

            await t.waitForTrack(1);
            t.step('this window holds the lease and plays: no banner', bannerShown(), false);

            // ---- another window takes the lease ----
            await t.stand.config({ lease: { holder: false, free: false } });
            await t.reportAfterConfig();
            await t.waitFor(bannerShown, 'the banner');
            t.step('the banner says that playback runs on another device', banner().querySelector('[data-role="text"]').textContent, banner().dataset.textOther);
            t.step('the window stopped its player (stopVideo), it does not go on playing what it had',
                [t.fake.calls.filter(function (c) { return c[0] === 'stopVideo'; }).length, t.fake.state], [1, -1]);
            const asks = await t.stand.count(t.NEXT_TRACK);
            t.step('from now on it only WATCHES: it does not claim the lease again by itself (the next report says so)', (await t.nextLeaseReport()).mode, 'WATCH');
            await t.sleep(3500);
            t.step('… and asks for no track meanwhile', await t.stand.count(t.NEXT_TRACK), asks);

            // ---- the DJ presses "play on this device" and says no ----
            document.getElementById('playerLeaseTakeover').click();
            await t.sleep(500);
            t.step('the button asks first, in the words of the banner (a window that still plays would be stopped)', asked, [banner().dataset.confirm]);
            t.step('a "no" sends nothing: no TAKE_OVER report, the banner stays', [(await reports(t, 'TAKE_OVER')).length, bannerShown()], [0, true]);

            // ---- … and yes ----
            answer = true;
            await t.stand.config({ lease: { holder: true } });   // the server gives it to the window that asks
            document.getElementById('playerLeaseTakeover').click();
            await t.waitFor(function () { return !bannerShown(); }, 'the banner to go away');
            t.step('a "yes" sent one TAKE_OVER report (with this window\'s id and the party)',
                (await reports(t, 'TAKE_OVER')).map(function (r) { return [r.q.partyCode, r.q.deviceId === window.sessionStorage.getItem('scan2play.playerDeviceId')]; }), [['HARN1', true]]);
            t.step('it asked once more, both times in the words of the banner', asked, [banner().dataset.confirm, banner().dataset.confirm]);
            await t.waitForTrack(2, 'the next track, played by this window again');
            t.step('the window plays again, the next track of the queue', t.fake.loads.map(t.letter), ['a', 'b']);
        }
    });

    S2P.scenario({
        name: 'lease-free-takeover-asks-nothing',
        title: 'nobody plays (the lease is free): "play here" takes it at once, without a question — there is nothing to stop; the banner says that nobody plays',
        setup: { lease: { holder: false, free: true, fallbackPlaylistId: PLAYLIST }, nextTracks: [track(1, 'aaaaaaaaaaA')] },
        run: async function (t) {
            const asked = [];
            window.confirm = function (text) { asked.push(text); return true; };

            await t.waitFor(bannerShown, 'the banner');
            t.step('the banner says that no device plays', banner().querySelector('[data-role="text"]').textContent, banner().dataset.textFree);
            t.step('this window only watches and plays nothing', [t.fake.loads.length, await t.stand.count(t.NEXT_TRACK)], [0, 0]);

            await t.stand.config({ lease: { holder: true, free: false } });
            document.getElementById('playerLeaseTakeover').click();
            await t.waitFor(function () { return !bannerShown(); }, 'the banner to go away');
            t.step('no question was asked', asked, []);
            t.step('one TAKE_OVER report was sent', (await reports(t, 'TAKE_OVER')).length, 1);
            await t.waitForTrack(1);
            t.step('and the window plays', t.fake.loads.map(t.letter), ['a']);
        }
    });

    S2P.scenario({
        name: 'lease-released-on-leaving',
        title: 'the window that plays gives the lease up when the page goes away (a beacon with its id and the CSRF token); a window that only watches has nothing to give up',
        setup: { lease: { fallbackPlaylistId: PLAYLIST }, nextTracks: [track(1, 'aaaaaaaaaaA')] },
        run: async function (t) {
            const releases = function () { return t.stand.requests('POST /dj/dashboard/player-lease/release'); };
            await t.waitForTrack(1);
            const deviceId = window.sessionStorage.getItem('scan2play.playerDeviceId');
            t.check('the window has an id of its own, kept for the life of the tab (sessionStorage)', deviceId && deviceId.length > 8);
            t.step('the lease reports carry that id', (await reports(t)).every(function (r) { return r.q.deviceId === deviceId; }), true);

            window.dispatchEvent(new Event('pagehide'));   // what the browser does when the tab is closed or another page opens
            await t.sleep(600);
            t.step('the window that plays sends the release: its party, its id, and the token in the body (a beacon cannot set headers)',
                (await releases()).map(function (r) { return [r.q.partyCode, r.q.deviceId, r.q._csrf]; }), [['HARN1', deviceId, 'harness-csrf-token']]);

            // another window took over: this one only watches, and leaving must not release a lease it does not hold
            await t.stand.config({ lease: { holder: false, free: false } });
            await t.reportAfterConfig();
            await t.waitFor(bannerShown, 'the banner');
            window.dispatchEvent(new Event('pagehide'));
            await t.sleep(600);
            t.step('a window that does not play sends no release', (await releases()).length, 1);
        }
    });
})();
