// The party's staff (V30). The staff's panel (dashboard-staff.html: a bartender on the owner's party) runs the panel's real scripts
// without the owner's parts — no settings, no QR code — and works the queue in the background like the owner's; its panel switcher
// is a full page load (the other party's panel comes whole). The owner's staff card: "Usuń dostęp" and the invitation link are full
// page loads too (the list and the link are shown at once), and its "Kopiuj" copies the invitation link.

/** Records whether each submit was taken by the panel's scripts (sent in the background) or left to the browser; stays on the page. */
function s2pRecordSubmits() {
    const handled = [];
    window.confirm = function () { return true; };   // the forms that ask first (data-confirm, dj-nav.js)
    document.addEventListener('submit', function (e) {
        handled.push([e.target.getAttribute('action'), e.defaultPrevented]);
        e.preventDefault();   // the stand-in has no page to go to: stay
    });
    return handled;
}

S2P.scenario({
    name: 'staff-panel',
    title: 'the staff\'s panel: the queue\'s buttons go in the background, the panel switcher is a page load, no owner\'s parts',
    page: 'dashboard-staff',
    setup: { queue: [{ id: 1, name: 'Wilki - Baśka', url: 'https://www.youtube.com/results?search_query=Wilki' }] },
    run: async function (t) {
        const handled = s2pRecordSubmits();
        t.check('no settings, no QR code', !document.getElementById('vibeSelect') && !document.getElementById('partyLinkInput')
            && !document.getElementById('hostListsCard') && !document.getElementById('staffCard'));
        t.check('the banner says whose party it is', !!document.getElementById('staffBanner'));

        // the rendered rows have the buttons (the stand-in's polled rows do not): click before the first poll replaces them
        document.querySelector('#song-list tr[data-song-id="1"] form[action="/dj/dashboard/play"] button').click();
        t.step('"Zagrane" goes in the background (the page stays)', handled.shift(), ['/dj/dashboard/play', true]);
        await t.waitFor(function () { return !document.querySelector('#song-list tr[data-song-id="1"]'); }, 'the row to go', 2500)
            .catch(function () {});
        t.step('the server was told which song', (await t.stand.requests(t.PLAY)).map(function (r) { return r.q.id; }), ['1']);

        const own = Array.from(document.querySelectorAll('#panelSwitcher form')).find(function (f) { return !f.querySelector('input[name="party"]'); });
        own.querySelector('button').click();
        t.step('"Mój panel" is a page load (not sent in the background)', handled.shift(), ['/dj/panel', false]);

        // an invitation pasted in the app (its browser has a login of its own): a page load, the panel of the other party comes whole
        document.getElementById('settingsToggle').click();   // under "⚙️ Ustawienia…", with the notifications
        t.check('the field shows once the settings are unfolded', document.getElementById('joinLinkInput').getClientRects().length > 0);
        document.getElementById('joinLinkInput').value = 'https://www.scan2play.com.pl/join/AbC_12-x';
        document.querySelector('#joinStaffForm button[type="submit"]').click();
        t.step('a pasted invitation is a page load (not sent in the background)', handled.shift(), ['/dj/join', false]);
    }
});

S2P.scenario({
    name: 'staff-card',
    title: 'the owner\'s staff card: "Usuń dostęp" and the invitation link are page loads, "Kopiuj" copies the invitation link',
    run: async function (t) {
        const handled = s2pRecordSubmits();

        document.querySelector('#staffList form[action="/dj/dashboard/staff-remove"] button').click();
        t.step('"Usuń dostęp" is a page load (the list is shown at once)', handled.shift(), ['/dj/dashboard/staff-remove', false]);
        document.getElementById('staffLinkOffBtn').click();
        t.step('turning the invitation link off is a page load', handled.shift(), ['/dj/dashboard/staff-link', false]);

        let copied = null;
        Object.defineProperty(navigator.clipboard, 'writeText', {
            configurable: true,
            value: function (text) { copied = text; return Promise.resolve(); }
        });
        document.querySelector('button[data-copy-target="staffLinkInput"]').click();
        await t.sleep(100);
        t.step('"Kopiuj" copies the invitation link', copied, document.getElementById('staffLinkInput').value);
    }
});
