// The forms that lost their inline handlers for the Content-Security-Policy (review 5.1, 2026-10-01): the vibe select saved itself
// with onchange="this.form.requestSubmit()", the account buttons asked with onsubmit="return confirm(…)". Now forms.js listens to
// select[data-auto-submit] and dj-nav.js to form[data-confirm] (in the capture phase, before forms.js sends the form by fetch).
// The same behaviour as before — these scenarios guard it.
(function () {
    const posts = function (t, path) { return t.stand.count('POST ' + path); };
    /** Waits (up to 5 s) until the stand-in has had n POSTs to path; t.waitFor takes only a condition that answers at once. */
    const waitForPosts = async function (t, path, n, label) {
        const started = performance.now();
        while ((await posts(t, path)) < n) {
            if (performance.now() - started > 5000) {
                t.step('TIMEOUT waiting for ' + label, false, true);
                throw new Error('timeout: ' + label);
            }
            await t.sleep(50);
        }
    };
    const submitButton = function (form) { return form.querySelector('button[type="submit"]'); };

    S2P.scenario({
        name: 'vibe-select-saves-on-change',
        title: 'picking another vibe saves it at once, by fetch (the page stays)',
        run: async function (t) {
            const select = document.getElementById('vibeSelect');
            const other = Array.from(select.options).find(function (o) { return !o.selected; });
            select.value = other.value;
            select.dispatchEvent(new Event('change', { bubbles: true }));
            await waitForPosts(t, '/dj/dashboard/vibe', 1, 'the vibe saved');
            const saved = (await t.stand.requests('POST /dj/dashboard/vibe'))[0].q;
            t.step('the chosen vibe is sent', saved.newVibe, other.value);
            t.check('the page did not reload (the select is the same element)', document.getElementById('vibeSelect') === select);
        }
    });

    S2P.scenario({
        name: 'comment-style-saves-on-change',
        title: 'picking the AI\'s comment style saves it at once, by fetch (the page stays)',
        run: async function (t) {
            const select = document.getElementById('commentStyleSelect');
            t.step('at first the classic style', select.value, 'CLASSIC');
            select.value = 'SARCASTIC';
            select.dispatchEvent(new Event('change', { bubbles: true }));
            await waitForPosts(t, '/dj/dashboard/comment-style', 1, 'the style saved');
            const saved = (await t.stand.requests('POST /dj/dashboard/comment-style'))[0].q;
            t.step('the style is sent with the party', [saved.commentStyle, !!saved.partyCode], ['SARCASTIC', true]);
            t.check('the page did not reload (the select is the same element)', document.getElementById('commentStyleSelect') === select);
        }
    });

    // The DJ's profiles (V24): the server refuses one that is not a profile on its site (400) — the form says so, the button ✗, not
    // a ✓ for something not saved; saved again, the note goes.
    S2P.scenario({
        name: 'dj-links-refused-then-saved',
        page: 'settings',   // the page "Ustawienia imprezy" since 2026-10-10 (the panel before): its forms go in the background too
        title: 'the DJ\'s profiles: a refused one shows the note and ✗ (nothing saved); saved, the note goes and the button shows ✓',
        setup: { djLinksStatus: 400 },
        run: async function (t) {
            const form = document.getElementById('djLinksForm');
            const note = form.querySelector('[data-form-error]');
            const button = form.querySelector('button[type="submit"]');
            t.step('at first no note, the saved profile in its field', [note.hidden, document.getElementById('instagramInput').value],
                [true, 'https://www.instagram.com/dj.koko/']);
            document.getElementById('tiktokInput').value = 'https://evil.example/@dj';
            button.click();
            await waitForPosts(t, '/dj/dashboard/dj-links', 1, 'the profiles sent');
            await t.waitFor(function () { return !note.hidden; }, 'the note', 2000).catch(function () {});
            t.step('refused: the note shows, the button says ✗', [note.hidden, button.textContent], [false, '✗']);
            t.step('all three fields are sent', Object.keys((await t.stand.requests('POST /dj/dashboard/dj-links'))[0].q)
                .filter(function (k) { return k !== 'partyCode' && k !== '_csrf'; }).sort(), ['facebook', 'instagram', 'tiktok']);

            await t.stand.config({ djLinksStatus: 302 });
            document.getElementById('tiktokInput').value = '@dj_koko';
            await t.sleep(1700);   // the ✗ goes back to the label first
            button.click();
            await waitForPosts(t, '/dj/dashboard/dj-links', 2, 'the profiles sent again');
            await t.waitFor(function () { return note.hidden; }, 'the note gone', 2000).catch(function () {});
            t.step('saved: no note, the button says ✓', [note.hidden, button.textContent], [true, '✓']);
            t.check('the page did not reload (the form is the same element)', document.getElementById('djLinksForm') === form);
        }
    });

    // The owner (2026-10-01): "End party" changed only the window it was pressed in; the phone kept showing the party open until a
    // reload. Every answer of the queue poll now says whether the party is open (X-Party-Active), and every window follows it.
    S2P.scenario({
        name: 'party-closed-elsewhere',
        title: 'the party ended (or resumed) in another window: this one shows the "closed" banner and hides "end party" within a poll — and back',
        run: async function (t) {
            const banner = document.getElementById('party-closed-banner');
            const endForm = document.getElementById('end-party-form');
            const shown = function (el) { return !el.classList.contains('d-none'); };
            t.step('loaded open: no banner, "end party" shown', [shown(banner), shown(endForm)], [false, true]);

            await t.stand.config({ partyActive: false });   // ended on the phone
            await t.waitFor(function () { return shown(banner); }, 'the banner', 8000);
            t.step('closed elsewhere: the banner, no "end party"', [shown(banner), shown(endForm)], [true, false]);

            await t.stand.config({ partyActive: true });    // resumed on the phone
            await t.waitFor(function () { return !shown(banner); }, 'the banner gone', 8000);
            t.step('resumed elsewhere: back as it was', [shown(banner), shown(endForm)], [false, true]);
        }
    });

    S2P.scenario({
        name: 'account-buttons-ask-first',
        title: 'ending the party and logging out ask first; "cancel" sends nothing, "OK" ends the party',
        run: async function (t) {
            const asked = [];
            const endForm = document.getElementById('end-party-form');
            const logoutForm = document.querySelector('form[action$="/dj/logout"]');

            window.confirm = function (text) { asked.push(text); return false; };
            submitButton(endForm).click();
            submitButton(logoutForm).click();
            await t.sleep(400);
            t.step('both asked, with their own question', [asked.length, asked[0] === asked[1], !!asked[0]], [2, false, true]);
            t.step('"cancel" sent nothing', [await posts(t, '/dj/end-party'), await posts(t, '/dj/logout')], [0, 0]);

            window.confirm = function (text) { asked.push(text); return true; };
            submitButton(endForm).click();
            await waitForPosts(t, '/dj/end-party', 1, 'the party ended');
            t.step('"OK" ends the party once, by fetch (still on the page)', [asked.length, !!document.getElementById('end-party-form')], [3, true]);
        }
    });
})();
