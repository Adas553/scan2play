// Two buttons for "back" in a window that does not play: "Wstecz" (the previous track, always) and "Od początku" (the current track
// again from the start) — youtube-autopilot.js renderBackButtons, PREVIOUS_TRACK and RESTART; PROJECT_CONTEXT.md Section 5.4, "Back ⏮".
//
// The DJ's report (2026-09-30): the single ⏮ is not intuitive. It has rules — a track that has played for more than 3 s starts again,
// a second press within 20 s goes back a track — that work at the computer, where two presses are a moment apart, and are hard to use
// from a remote: a press reaches the window that plays only with its next report, the button waits 3.5 s, and the DJ does not know
// which of the two things will happen. So a window that does not play shows two plain buttons instead, and the window that plays keeps
// the single ⏮ (its scenario is double-press-window).
(function () {
    const smart = function () { return document.getElementById('playerPreviousBtn'); };
    const back = function () { return document.getElementById('playerBackBtn'); };
    const restart = function () { return document.getElementById('playerRestartBtn'); };
    const hidden = function (element) { return element.classList.contains('d-none'); };

    /** Gives the window that plays a command (handed out with its next lease report) and says what its player did. */
    async function commandDid(t, command) {
        const before = { loads: t.fake.loads.length, seeks: t.fake.seeks.length, calls: t.fake.calls.length };
        await t.stand.config({ commands: [command] });
        await t.reportAfterConfig();     // this report is answered with the command
        await t.sleep(700);              // (the walk back asks recent-tracks first)
        if (t.fake.loads.length > before.loads) return t.letter(t.fake.loads[t.fake.loads.length - 1]);
        if (t.fake.seeks.length > before.seeks) return 'restart';
        return 'nothing';
    }

    S2P.scenario({
        name: 'back-buttons-by-role',
        title: 'the window that plays shows the single ⏮; a window that does not play shows Wstecz and Od początku instead — and back again',
        setup: {},
        run: async function (t) {
            await t.nextLeaseReport();
            await t.sleep(300);        // the first answer of the server has been applied
            t.step('the window that plays: the single ⏮ shows, the two buttons of a remote do not',
                [hidden(smart()), hidden(back()), hidden(restart())], [false, true, true]);
            t.step('pause and next are there in both cases', [hidden(document.getElementById('playerPauseBtn')), hidden(document.getElementById('playerNextBtn'))], [false, false]);

            await t.stand.config({ lease: { holder: false, free: false } });
            await t.reportAfterConfig();
            await t.sleep(300);
            t.step('told that another window plays: the single ⏮ is gone, Wstecz and Od początku show',
                [hidden(smart()), hidden(back()), hidden(restart())], [true, false, false]);
            t.step('their labels are the ones from the bundle: ⏮ Wstecz and ↺ Od początku', [t.label('playerBackBtn'), t.label('playerRestartBtn')], ['⏮ Wstecz', '↺ Od początku']);
            t.step('the banner says that playback runs on another device', document.getElementById('playerLeaseBanner').classList.contains('d-none'), false);

            await t.stand.config({ lease: { holder: true, free: false } });
            await t.reportAfterConfig();
            await t.sleep(300);
            t.step('this window plays again: the single ⏮ is back, the two buttons are hidden',
                [hidden(smart()), hidden(back()), hidden(restart())], [false, true, true]);
        }
    });

    S2P.scenario({
        name: 'remote-back-and-restart',
        title: 'in a window that does not play the two buttons send PREVIOUS_TRACK and RESTART, each waits for itself ("Wysłano…"), and neither sends the old PREVIOUS',
        setup: { lease: { holder: false, free: false } },
        run: async function (t) {
            await t.waitFor(function () { return !hidden(back()); }, 'the two buttons');
            const commands = async function () {
                return (await t.stand.requests('POST /dj/dashboard/player-command')).map(function (r) { return [r.q.command, r.q.partyCode]; });
            };

            back().click();
            await t.sleep(300);
            t.step('Wstecz sent PREVIOUS_TRACK for this party', await commands(), [['PREVIOUS_TRACK', 'HARN1']]);
            t.step('and says "Wysłano…" and waits, while the other button is free',
                [back().disabled, t.label('playerBackBtn'), restart().disabled, t.label('playerRestartBtn')], [true, back().dataset.textSent, false, '↺ Od początku']);

            restart().click();
            await t.sleep(300);
            t.step('Od początku sent RESTART', await commands(), [['PREVIOUS_TRACK', 'HARN1'], ['RESTART', 'HARN1']]);
            t.step('both wait now', [back().disabled, restart().disabled], [true, true]);

            await t.sleep(4200);
            t.step('after 3.5 s each is itself again',
                [back().disabled, t.label('playerBackBtn'), restart().disabled, t.label('playerRestartBtn')], [false, '⏮ Wstecz', false, '↺ Od początku']);
            t.step('the old PREVIOUS was never sent', (await commands()).filter(function (c) { return c[0] === 'PREVIOUS'; }).length, 0);

            // nobody plays: the server answers 409 — the banner offers to play here, and the button is free again at once
            await t.stand.config({ commandStatus: 409 });
            const sent = (await commands()).length;
            back().click();
            await t.sleep(400);
            t.step('a press when nobody plays is sent and refused (409) …', (await commands()).length, sent + 1);
            t.step('… the button is free again at once, not after 3.5 s', [back().disabled, t.label('playerBackBtn')], [false, '⏮ Wstecz']);
            const banner = document.getElementById('playerLeaseBanner');
            t.step('… and the banner says that no device is playing', banner.querySelector('[data-role="text"]').textContent, banner.dataset.textFree);
        }
    });

    S2P.scenario({
        name: 'play-window-carries-out-back-and-restart',
        title: 'the window that plays: PREVIOUS_TRACK goes back a track even after 30 s (the single ⏮ would restart), RESTART only starts again, the old PREVIOUS is unchanged',
        setup: { replay: { fixture: 'play-log-boundary', keys: 'play' } },
        run: async function (t) {
            const fake = t.fake;
            await t.waitForTrack(1, 'the first track');
            fake.end();
            await t.waitForTrack(2, 'the second track');
            fake.end();
            await t.waitForTrack(3, 'the third track');
            t.step('Auto-Pilot ran A B C', fake.loads.map(t.letter), ['a', 'b', 'c']);

            fake.position = 30;
            t.step('PREVIOUS_TRACK after 30 s of C: goes back to B (no restart first)', await commandDid(t, 'PREVIOUS_TRACK'), 'b');
            t.step('… and nothing was restarted on the way', fake.seeks.length, 0);

            fake.position = 30;
            t.step('RESTART after 30 s of B: only starts B again', await commandDid(t, 'RESTART'), 'restart');
            fake.position = 12;
            t.step('RESTART again: again only the restart (no double-press rule, no going back)', await commandDid(t, 'RESTART'), 'restart');
            t.step('the player was never loaded with another track by the restarts', fake.loads.map(t.letter), ['a', 'b', 'c', 'b']);

            fake.position = 30;
            t.step('PREVIOUS_TRACK from B: goes back to A', await commandDid(t, 'PREVIOUS_TRACK'), 'a');
            fake.position = 30;
            t.step('PREVIOUS_TRACK with nothing older: the track starts again, as the single ⏮ does', await commandDid(t, 'PREVIOUS_TRACK'), 'restart');

            // the old command (a page opened before the two buttons existed) keeps its rules: a track that has played for a while restarts
            fake.position = 30;
            t.step('the old PREVIOUS keeps its rules: after 30 s it restarts', await commandDid(t, 'PREVIOUS'), 'restart');
            t.step('next-track was asked 3 times only (A B C): going back and restarting never ask the queue', await t.stand.count(t.NEXT_TRACK), 3);
        }
    });

    S2P.scenario({
        name: 'restart-when-paused-or-ended',
        title: 'RESTART of a paused track keeps it paused; RESTART when the track has ended (Auto-Pilot off) plays that track again',
        page: 'dashboard-manual',
        setup: { playbackMode: 'MANUAL', replay: { fixture: 'play-log-boundary', keys: 'play' } },
        run: async function (t) {
            const fake = t.fake;
            t.step('a new party: Auto-Pilot is off', document.getElementById('autoToggle').checked, false);
            await t.nextLeaseReport();
            await t.sleep(300);        // this window has been told that it plays, and its player is ready
            t.step('⏭ starts a track even so (a deliberate act of the DJ)', await t.press('playerNextBtn'), 'a');
            await t.waitFor(function () { return fake.state === 1; }, 'the track to play');

            fake.position = 40;
            document.getElementById('playerPauseBtn').click();
            await t.sleep(300);
            t.step('paused', fake.state, 2);
            t.step('RESTART of the paused track: it goes to the start …', await commandDid(t, 'RESTART'), 'restart');
            t.step('… and stays paused (nothing called playVideo)', [fake.state, fake.calls.filter(function (c) { return c[0] === 'playVideo'; }).length], [2, 0]);

            document.getElementById('playerPauseBtn').click();      // resume
            await t.sleep(300);
            fake.end();                                              // the track ends; with Auto-Pilot off nothing follows
            await t.sleep(700);
            t.step('the track ended and nothing started by itself', [fake.loads.length, await t.stand.count(t.NEXT_TRACK)], [1, 1]);
            t.step('RESTART now plays the track that ended again', await commandDid(t, 'RESTART'), 'a');
            t.step('it came back from the timeline: next-track was not asked again', await t.stand.count(t.NEXT_TRACK), 1);
        }
    });
})();
