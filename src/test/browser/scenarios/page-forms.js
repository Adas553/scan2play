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
        title: 'picking another vibe saves it at once, by fetch (the page and the player stay)',
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
