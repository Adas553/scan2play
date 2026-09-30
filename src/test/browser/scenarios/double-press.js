// ⏮ pressed twice: the second press goes back a track if it comes within DOUBLE_PRESS_MS (20 s) of a restart that ⏮ caused
// (youtube-autopilot.js; PROJECT_CONTEXT.md Section 5.4, "Back ⏮"). The owner asked for 20 s on 2026-09-29: from another window two
// presses are always more than 3.5 s apart, and 10 s left too little time on a phone.
//
// The fake player's clock is moved by hand (fake.advanceClock), so nothing waits for real seconds; the presses are 19 s and
// 21 s after the restart — either side of the 20 s the tooltip of the button promises.
S2P.scenario({
    name: 'double-press-window',
    title: '⏮ twice goes back a track within 20 s of the restart, and restarts again after that',
    setup: { replay: { fixture: 'play-log-boundary', keys: 'play' } },
    run: async function (t) {
        const fake = t.fake;
        await t.waitForTrack(1, 'the first track');
        fake.end();
        await t.waitForTrack(2, 'the second track');
        fake.end();
        await t.waitForTrack(3, 'the third track');
        t.step('Auto-Pilot ran A B C', fake.loads.map(t.letter), ['a', 'b', 'c']);

        const tooltip = document.getElementById('playerPreviousBtn').title;
        t.check('the tooltip of ⏮ promises 20 seconds (DOUBLE_PRESS_MS): ' + tooltip.slice(0, 90) + '…', tooltip.indexOf('20 sekund') >= 0);

        fake.position = 30;
        t.step('⏮ after 30 s of C restarts it', await t.press('playerPreviousBtn'), 'restart');
        fake.advanceClock(19000);
        fake.position = 49;
        t.step('⏮ 19 s after that restart is a second press: it goes back to B', await t.press('playerPreviousBtn'), 'b');

        fake.position = 30;
        t.step('⏮ after 30 s of B restarts it', await t.press('playerPreviousBtn'), 'restart');
        fake.advanceClock(21000);
        fake.position = 51;
        t.step('⏮ 21 s after that restart is a new first press: it restarts B again', await t.press('playerPreviousBtn'), 'restart');
        t.step('and a ⏮ at once after that is the second press: it goes back to A', await t.press('playerPreviousBtn'), 'a');
    }
});
