// The Content-Security-Policy (review 5.1). The stand-in sends the real server's policy, enforced, with every page, and harness.js
// adds the step "no CSP violation" to every scenario — so a script or a template that needs an inline script, an inline handler or
// a host the policy does not list fails the scenario that loads it. This control proves that the step sees a violation.

S2P.scenario({
    name: 'csp-catches-inline-code',
    title: 'control: an inline script and an inline handler are blocked, and the step "no CSP violation" fails',
    control: { mustFail: ['no CSP violation'] },
    run: async function (t) {
        const script = document.createElement('script');
        script.textContent = 'window.__inlineScriptRan = true;';
        document.body.appendChild(script);
        const button = document.createElement('button');
        button.setAttribute('onclick', 'window.__inlineHandlerRan = true;');
        document.body.appendChild(button);
        button.click();
        await t.sleep(200);   // the violation events are queued
        t.step('the inline script did not run', window.__inlineScriptRan === true, false);
        t.step('the inline handler did not run', window.__inlineHandlerRan === true, false);
    }
});

// The pages other than the dashboard, rendered by their own tests (GuestPageRenderTest, QrPrintPageTest). Each scenario uses what the
// page's scripts do, so the requests they make meet the policy too, not only the page's markup.

/** Records what the page fetches (the request still goes through the browser's own fetch, under the policy). */
function recordFetches() {
    const asked = [];
    const original = window.fetch;
    window.fetch = function (resource) {
        asked.push(String(resource && resource.url ? resource.url : resource));
        return original.apply(this, arguments);
    };
    return asked;
}

S2P.scenario({
    name: 'guest-page-under-csp',
    title: 'the guest page under the real policy: no song / mood tiles, the song suggestions (iTunes) and the refresh of the list work',
    page: 'guest',
    run: async function (t) {
        const asked = recordFetches();
        t.check('no song / mood tiles (one kind of request: a song)', !document.getElementById('modeMood') && !document.getElementById('modeSong'));
        // the DJ's profiles (V24): a button each, its icon drawn (an inline SVG, 16 px) beside the name
        const icons = Array.from(document.querySelectorAll('#djLinks a svg'));
        t.step('the DJ\'s profiles: three buttons, each with its icon drawn', [document.querySelectorAll('#djLinks a').length,
            icons.filter(function (svg) { const box = svg.getBoundingClientRect(); return box.width >= 14 && box.height >= 14; }).length], [3, 3]);

        const input = document.getElementById('songInput');
        input.value = 'abba';
        input.dispatchEvent(new Event('input', { bubbles: true }));
        await t.waitFor(function () { return asked.some(function (u) { return u.indexOf('https://itunes.apple.com/') === 0; }); },
            'the song suggestions ask iTunes');
        t.check('the song suggestions ask iTunes (song-autocomplete.js runs)', true);

        document.querySelector('[data-guest-queue-refresh]').click();
        await t.waitFor(function () { return asked.indexOf('/p/ABC12/queue') >= 0; }, 'the list is fetched again');
        t.check('"↻" fetches the list again (guest-party.js runs)', true);
        await t.sleep(300);   // the answers (a refused name, a 404 of the stand-in) arrive
    }
});

/** The QR print page: the codes (data: images) show, and "Print" opens the print window. */
async function printPageWorks(t) {
    const codes = Array.prototype.slice.call(document.querySelectorAll('img.qr'));
    await t.waitFor(function () { return codes.length > 0 && codes.every(function (img) { return img.complete; }); }, 'the codes load');
    t.check('every QR code shows', codes.every(function (img) { return img.naturalWidth > 0; }));
    const logos = Array.prototype.slice.call(document.querySelectorAll('.logo img'));
    await t.waitFor(function () { return logos.length > 0 && logos.every(function (img) { return img.complete; }); }, 'the logos load');
    t.check('our logo shows (' + logos.length + ')', logos.every(function (img) { return img.naturalWidth > 0; }));
    let printed = 0;
    window.print = function () { printed++; };   // the real one would block the page with a dialog
    document.getElementById('printBtn').click();
    t.step('"Print" opens the print window (qr-print.js runs)', printed, 1);
}

S2P.scenario({
    name: 'qr-print-poster-under-csp',
    title: 'the QR print page (an A4 poster) under the real policy: the code shows, "Print" opens the print window',
    page: 'qr-print-poster',
    run: printPageWorks
});

S2P.scenario({
    name: 'qr-print-cards-under-csp',
    title: 'the QR print page (eight cards) under the real policy: the codes show, "Print" opens the print window',
    page: 'qr-print-cards',
    run: printPageWorks
});

// The owner (2026-10-07): on a card our logo goes above the code and the DJ's profiles under it. A card is 68 mm high and clips what
// does not fit (overflow: hidden) — the page has all three profiles, the most the column must hold.
S2P.scenario({
    name: 'qr-print-cards-layout',
    title: 'the QR cards: our logo above the code, the DJ\'s three profiles under it, all inside the card; the texts beside the code',
    page: 'qr-print-cards',
    run: async function (t) {
        await t.waitFor(function () { return document.querySelector('.card .qr').complete; }, 'the code', 3000).catch(function () {});
        const card = document.querySelector('.card');
        const box = function (selector) { return card.querySelector(selector).getBoundingClientRect(); };
        const logo = box('.side .logo'), qr = box('.side .qr'), links = box('.side .dj-links'), text = box('.text'), whole = card.getBoundingClientRect();
        t.step('the code\'s column: logo, code, profiles from the top', [logo.bottom <= qr.top + 0.5, qr.bottom <= links.top + 0.5], [true, true]);
        t.step('the three profiles, one under another', Array.from(card.querySelectorAll('.dj-links span')).map(function (s) { return s.textContent; }),
            ['Instagram @dj.koko', 'TikTok @dj_koko', 'Facebook djkoko']);
        t.check('nothing cut off: the profiles end inside the card (' + Math.round(whole.bottom - links.bottom) + ' px to spare)',
            links.bottom <= whole.bottom - 2);
        t.check('the texts beside the code, not under it', text.left >= qr.right);
    }
});
