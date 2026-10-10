// The party's staff (V30, V32). The staff's panel (dashboard-staff.html: Kasia with the role "Obsługa kolejki" on the owner's party,
// no party of her own) runs the panel's real scripts without the owner's settings, lists and staff — with the QR code — and works
// the queue in the background like the owner's; every form names the party the page shows. The page loads itself again when the
// organiser takes the access away (the poll's 403) or changes it (X-Panel-Access). The owner's page "Obsługa" (staff.html): a role
// ticks its permissions, a tick changed picks the role it makes; its forms are page loads; "Kopiuj" copies the invitation link.

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

/**
 * A scenario across a reload: the first run arms a mark in sessionStorage and waits; the reload runs the scenario again, which finds
 * the mark — the page did load itself again. No reload within {@code ms}: the step fails.
 */
async function s2pExpectAReload(t, label, ms) {
    const key = 's2p-reload-' + label;
    if (sessionStorage.getItem(key)) {
        sessionStorage.removeItem(key);
        t.step(label, 'reloaded', 'reloaded');
        return;
    }
    sessionStorage.setItem(key, '1');
    await t.sleep(ms);
    sessionStorage.removeItem(key);
    t.step(label, 'still the same page', 'reloaded');
}

S2P.scenario({
    name: 'staff-panel',
    title: 'the staff\'s panel: the queue\'s buttons go in the background, whose panel and role in one line, no owner\'s parts',
    page: 'dashboard-staff',
    setup: { queue: [{ id: 1, name: 'Wilki - Baśka', url: 'https://www.youtube.com/results?search_query=Wilki' }] },
    run: async function (t) {
        const handled = s2pRecordSubmits();
        // the QR code is the staff's too (it is on the tables anyway); the settings, the lists and the staff are not
        t.check('no settings, but the QR code', !document.getElementById('vibeSelect') && !!document.getElementById('partyLinkInput')
            && !document.getElementById('hostListsCard') && !document.getElementById('staffCard'));
        t.step('one line says whose panel and the role', document.querySelector('#panelBar > summary').textContent.replace(/\s+/g, ' ').trim(),
            'Klub Ola Obsługa kolejki');
        const bar = document.getElementById('panelBar');
        // a closed <details> keeps its content laid out but not shown (content-visibility): checkVisibility() tells
        t.step('what she may do: folded until asked', [bar.open, document.getElementById('panelCan').checkVisibility()], [false, false]);
        bar.querySelector('summary').click();
        t.step('…and unfolded on a tap', [bar.open, document.getElementById('panelCan').checkVisibility()], [true, true]);

        // the rendered rows have the buttons (the stand-in's polled rows do not): click before the first poll replaces them
        document.querySelector('#song-list tr[data-song-id="1"] form[action="/dj/dashboard/play"] button').click();
        t.step('"Zagrane" goes in the background (the page stays)', handled.shift(), ['/dj/dashboard/play', true]);
        await t.waitFor(function () { return !document.querySelector('#song-list tr[data-song-id="1"]'); }, 'the row to go', 2500)
            .catch(function () {});
        t.step('the server was told which song, of which party', (await t.stand.requests(t.PLAY)).map(function (r) {
            return [r.q.id, r.q.partyCode];
        }), [['1', 'HARN1']]);

        // no party of her own: made on purpose from the menu — a page load, the new panel comes whole
        document.getElementById('makeOwnPartyBtn').click();
        t.step('"Załóż własną imprezę" is a page load (not sent in the background)', handled.shift(), ['/dj/panel', false]);

        // an invitation pasted in the app (its browser has a login of its own): a page load, the invitation asks
        document.getElementById('settingsToggle').click();   // under "Kod QR", with the notifications
        t.check('the field shows once the settings are unfolded', document.getElementById('joinLinkInput').getClientRects().length > 0);
        document.getElementById('joinLinkInput').value = 'https://www.scan2play.com.pl/join/AbC_12-x';
        document.querySelector('#joinStaffForm button[type="submit"]').click();
        t.step('a pasted invitation is a page load (not sent in the background)', handled.shift(), ['/dj/join', false]);

        // "Opuść obsługę" in place of "Usuń konto" (it read as deleting the party): a page load, another panel comes whole
        t.check('no "Usuń konto" on another party\'s panel', !document.querySelector('form[action="/dj/delete-account"]'));
        document.getElementById('leaveStaffBtn').click();
        t.step('leaving the staff is a page load (not sent in the background)', handled.shift(), ['/dj/staff/leave', false]);
        t.step('…of the party the page shows', document.querySelector('form[action="/dj/staff/leave"] input[name="partyCode"]').value, 'HARN1');
    }
});

S2P.scenario({
    name: 'forms-name-the-party',
    title: 'every form of the panel names the party the page shows — two tabs, two parties (V32): "Pomiń", "Cofnij", "Wyczyść kolejkę"',
    setup: { queue: [{ id: 1, name: 'Wilki - Baśka', url: 'https://www.youtube.com/results?search_query=Wilki' }] },
    run: async function (t) {
        window.confirm = function () { return true; };
        document.querySelector('#song-list tr[data-song-id="1"] form[action="/dj/dashboard/dismiss"] button').click();
        await t.waitFor(function () { return !document.getElementById('undoSkip').hidden; }, '"Cofnij"', 2500);
        document.querySelector('#undoSkip [data-undo-button]').click();
        await t.sleep(300);
        t.step('"Pomiń" names the party', (await t.stand.requests('POST /dj/dashboard/dismiss')).map(function (r) { return r.q.partyCode; }), ['HARN1']);
        t.step('"Cofnij" (built by the script) names it too', (await t.stand.requests('POST /dj/dashboard/restore')).map(function (r) {
            return r.q.partyCode;
        }), ['HARN1']);
        await t.waitFor(function () { return !!document.querySelector('#song-list tr[data-song-id="1"]'); }, 'the song back', 4000)
            .catch(function () {});
        document.getElementById('clearQueueBtn').click();
        await t.sleep(300);
        t.step('"Wyczyść kolejkę" names it', (await t.stand.requests('POST /dj/dashboard/clear-queue')).map(function (r) { return r.q.partyCode; }),
            ['HARN1']);
    }
});

S2P.scenario({
    name: 'staff-access-taken-away',
    title: 'the organiser takes the access away: the poll\'s 403 loads the page again (it says so) — before, the queue just stood still',
    page: 'dashboard-staff',
    setup: { updatesStatus: 403 },
    run: async function (t) {
        await s2pExpectAReload(t, 'the page loaded itself again', 7000);
    }
});

S2P.scenario({
    name: 'staff-access-changed',
    title: 'the organiser changes what the person may do (X-Panel-Access): the page loads itself again with the new buttons',
    page: 'dashboard-staff',
    setup: { panelAccess: 'HISTORY' },
    run: async function (t) {
        await s2pExpectAReload(t, 'the page loaded itself again', 7000);
    }
});

S2P.scenario({
    name: 'staff-access-same',
    title: 'the same permissions on the poll: no reload, the queue follows the poll as ever',
    page: 'dashboard-staff',
    setup: { panelAccess: 'QUEUE,TIPS,CLEAR_QUEUE,OPEN_CLOSE,HISTORY', queue: [{ id: 5, name: 'Sanah - Szampan', url: 'https://x.example/5' }] },
    run: async function (t) {
        if (sessionStorage.getItem('s2p-no-reload')) {
            sessionStorage.removeItem('s2p-no-reload');
            t.step('no reload', 'reloaded', 'no reload');
            return;
        }
        sessionStorage.setItem('s2p-no-reload', '1');
        await t.waitFor(function () { return !!document.querySelector('#song-list tr[data-song-id="5"]'); }, 'the polled row', 5000);
        await t.sleep(3500);   // one more poll
        sessionStorage.removeItem('s2p-no-reload');
        t.step('no reload', 'no reload', 'no reload');
    }
});

S2P.scenario({
    name: 'staff-page-roles',
    title: 'the owner\'s page "Obsługa": a role ticks its permissions, a tick changed picks the role it makes; forms are page loads',
    page: 'staff',
    run: async function (t) {
        const handled = s2pRecordSubmits();
        const kasia = document.querySelector('[data-staff-id="7"]');
        const select = kasia.querySelector('[data-role-select]');
        const ticked = function () {
            return Array.from(kasia.querySelectorAll('[data-permission-box]')).filter(function (b) { return b.checked; })
                .map(function (b) { return b.value; });
        };
        t.step('Kasia: "Obsługa kolejki" and its ticks', [select.value, ticked()],
            ['QUEUE', ['QUEUE', 'TIPS', 'CLEAR_QUEUE', 'OPEN_CLOSE', 'HISTORY']]);

        select.value = 'VIEWER';
        select.dispatchEvent(new Event('change'));
        t.step('"Podgląd" ticks the history alone', ticked(), ['HISTORY']);
        t.step('…and says what it is', kasia.querySelector('[data-role-help]').textContent, 'Widzi kolejkę i historię, niczego nie zmienia.');

        const queue = kasia.querySelector('[data-permission-box][value="QUEUE"]');
        queue.click();
        t.step('a tick more: no role\'s set — "Własne"', select.value, 'CUSTOM');
        queue.click();
        t.step('the tick taken back: "Podgląd" again', select.value, 'VIEWER');

        kasia.querySelector('form[action="/dj/staff/permissions"] button[type="submit"]').click();
        t.step('"Zapisz" is a page load ("Zapisano" comes with the page)', handled.shift(), ['/dj/staff/permissions', false]);
        kasia.querySelector('form[action="/dj/staff/remove"] button').click();
        t.step('"Usuń dostęp" is a page load', handled.shift(), ['/dj/staff/remove', false]);

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
