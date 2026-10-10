// The party's staff (V30, V32). The staff's panel (dashboard-staff.html: Kasia with the role "Obsługa kolejki" on the owner's party,
// no party of her own) runs the panel's real scripts without the owner's settings, lists and staff — with the QR code — and works
// the queue in the background like the owner's; every form names the party the page shows. The page loads itself again when the
// organiser takes the access away (the poll's 403) or changes it (X-Panel-Access). The owner's page "Obsługa" (staff.html): a role
// ticks its permissions, a tick changed picks the role it makes — in each of its forms: a person, "Zaproś" by e-mail (V34), the
// link's role (V33); its forms are page loads; "Kopiuj" copies the invitation link.

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

        // no "Wklej link" on another's party (the owner, 2026-10-10: she has joined already — it read as if she still had to);
        // her own panel and "no-panel" keep it, and an invitation by e-mail comes by itself
        document.getElementById('settingsToggle').click();   // under "Kod QR", with the notifications
        t.check('"To urządzenie" without the field for an invitation link', !!document.getElementById('pushToggle')
            && !document.getElementById('joinStaffForm') && !document.getElementById('joinLinkInput'));

        // "Opuść obsługę" in place of "Usuń konto" (it read as deleting the party): a page load, another panel comes whole
        t.check('no "Usuń konto" on another party\'s panel', !document.querySelector('form[action="/dj/delete-account"]'));
        document.getElementById('leaveStaffBtn').click();
        t.step('leaving the staff is a page load (not sent in the background)', handled.shift(), ['/dj/staff/leave', false]);
        t.step('…of the party the page shows', document.querySelector('form[action="/dj/staff/leave"] input[name="partyCode"]').value, 'HARN1');
    }
});

S2P.scenario({
    name: 'owner-pastes-an-invitation',
    title: 'the organiser\'s own panel keeps "Masz zaproszenie do obsługi innej imprezy? Wklej link" — a page load, the invitation asks',
    run: async function (t) {
        const handled = s2pRecordSubmits();
        document.getElementById('settingsToggle').click();
        t.check('the field shows once the settings are unfolded', document.getElementById('joinLinkInput').getClientRects().length > 0);
        document.getElementById('joinLinkInput').value = 'https://www.scan2play.com.pl/join/AbC_12-x';
        document.querySelector('#joinStaffForm button[type="submit"]').click();
        t.step('a pasted invitation is a page load (not sent in the background)', handled.shift(), ['/dj/join', false]);
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

/**
 * The submits of the owner's page "Obsługa", each with what its form sends and the question it asked first (data-confirm) — the
 * page loads are stopped (the stand-in has no page to go to).
 */
function s2pRecordStaffForms() {
    const sent = [];
    let asked = null;
    window.confirm = function (question) { asked = question; return true; };
    document.addEventListener('submit', function (e) {
        const fields = {};
        new FormData(e.target).forEach(function (value, name) {
            if (name === '_csrf') return;
            fields[name] = name in fields ? [].concat(fields[name], value) : value;
        });
        sent.push({ action: e.target.getAttribute('action'), inBackground: e.defaultPrevented, asked: asked, fields: fields });
        asked = null;
        e.preventDefault();
    });
    return sent;
}

S2P.scenario({
    name: 'staff-link-role',
    title: 'the invitation link says its role ("Podgląd"); a new link is made with the role picked beside it — "Własne" its ticks',
    page: 'staff',
    run: async function (t) {
        const sent = s2pRecordStaffForms();
        const form = document.getElementById('staffLinkForm');
        const select = form.querySelector('[data-role-select]');
        const ticked = function () {
            return Array.from(form.querySelectorAll('[data-permission-box]')).filter(function (b) { return b.checked; })
                .map(function (b) { return b.value; });
        };
        t.step('the link says its role', document.getElementById('staffLinkRole').textContent.trim(), 'Ten link: Podgląd');
        t.step('the new link starts with the same role', [select.value, ticked()], ['VIEWER', ['HISTORY']]);

        select.value = 'CO_ORGANISER';
        select.dispatchEvent(new Event('change'));
        t.step('"Współorganizator" ticks everything', ticked().length, 9);
        form.querySelector('details').open = true;   // the ticks, as a person opens them
        form.querySelector('[data-permission-box][value="LIMITS"]').click();
        t.step('a tick taken away: "Własne"', select.value, 'CUSTOM');

        document.getElementById('staffLinkNewBtn').click();
        const link = sent.shift();
        t.step('"Nowy link" is a page load, after the question (the old link stops working)',
            [link && link.action, link && link.inBackground, !!(link && link.asked)], ['/dj/staff/link', false, true]);
        t.step('…and sends the role with its ticks', link && [link.fields.link, link.fields.role, link.fields.permissions.length,
            link.fields.permissions.indexOf('LIMITS')], ['new', 'CUSTOM', 8, -1]);
    }
});

S2P.scenario({
    name: 'staff-invite-by-email',
    title: '"Zaproś" by e-mail: the address and a role, a page load; the invitation waiting is listed, "Cofnij zaproszenie" asks first',
    page: 'staff',
    viewport: '390,844',
    run: async function (t) {
        const sent = s2pRecordStaffForms();
        const ola = document.querySelector('[data-invitation-id="3"]');
        t.step('the invitation waiting is on the list, after the staff',
            [!!ola, ola && ola.querySelector('[data-invitation-status]').textContent.trim(),
                !!(ola && ola.compareDocumentPosition(document.querySelector('[data-staff-id="8"]')) & Node.DOCUMENT_POSITION_PRECEDING)],
            [true, 'zaproszono 10.10 · czeka na zalogowanie', true]);

        const form = document.getElementById('staffInviteForm');
        const select = form.querySelector('[data-role-select]');
        t.step('"Zaproś" starts with "Obsługa kolejki"', select.value, 'QUEUE');
        select.value = 'VIEWER';
        select.dispatchEvent(new Event('change'));
        t.step('…a role picked ticks its set', Array.from(form.querySelectorAll('[data-permission-box]'))
            .filter(function (b) { return b.checked; }).map(function (b) { return b.value; }), ['HISTORY']);
        t.step('…and says what it is', form.querySelector('[data-role-help]').textContent, 'Widzi kolejkę i historię, niczego nie zmienia.');

        const email = document.getElementById('staffInviteEmail');
        t.step('the field is for an e-mail address (a phone shows the "@" keyboard)', [email.type, email.required], ['email', true]);
        email.value = 'bartek.nowak@gmail.com';
        document.getElementById('staffInviteBtn').click();
        const invited = sent.shift();
        t.step('"Zaproś" is a page load with the address and the role', invited && [invited.action, invited.inBackground,
            invited.fields.email, invited.fields.role, invited.fields.partyCode], ['/dj/staff/invite', false,
            'bartek.nowak@gmail.com', 'VIEWER', 'HARN1']);

        const button = document.getElementById('staffInviteBtn').getBoundingClientRect();
        t.check('"Zaproś" is a phone\'s full-width target, 44 px high', button.height >= 44 && button.width >= 300);

        ola.querySelector('form[action="/dj/staff/invitation/cancel"] button').click();
        const cancelled = sent.shift();
        t.step('"Cofnij zaproszenie" asks first and is a page load', cancelled && [cancelled.action, cancelled.inBackground,
            cancelled.asked, cancelled.fields.id], ['/dj/staff/invitation/cancel', false,
            'Cofnąć zaproszenie: ola.kowalska@gmail.com?', '3']);
    }
});
