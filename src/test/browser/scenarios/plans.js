// THE PLANS (V35, the owner 2026-10-14, variant B of https://claude.ai/artifact/32eMmdwz9pTGLs8pRQzLBv; the look of "PRO" — a cyan tag —,
// the tidy-up and the page /pro: https://claude.ai/artifact/PPSwW3nDrRdWFcbM8hWAoN, the owner the same day): an organiser without Pro,
// the plans enforced, sees each Pro feature where it was, with "PRO" by its name and — on the page "Ustawienia imprezy" — one
// sentence and "Zobacz, co daje Pro" in place of its form; never in the queue. The pages are rendered by DashboardPageRenderTest /
// SettingsPageRenderTest with PlanService enforced (dashboard-free.html, settings-free.html). While the plans are not enforced
// (production today) nothing says "Pro" — the render tests check that.
(function () {
    const shows = function (el) { return !!el && el.getClientRects().length > 0 && getComputedStyle(el).visibility !== 'hidden'; };
    const text = function (el) { return el.textContent.replace(/\s+/g, ' ').trim(); };
    const middle = function (el) { const box = el.getBoundingClientRect(); return box.top + box.height / 2; };
    /** The visible things to tap inside {@code root} that are lower than 44 px (as design.js), named for the report. */
    const tooSmall = function (root) {
        return Array.from(root.querySelectorAll('a.btn, button, input:not([type="hidden"]):not([type="checkbox"]), select, textarea, summary, '
            + '.s2p-row, .s2p-chips a'))
            .filter(shows)
            .filter(function (el) { return el.getBoundingClientRect().height < 44 - 0.5; })
            .map(function (el) { return (el.id || text(el).slice(0, 20) || el.tagName) + ' ' + Math.round(el.getBoundingClientRect().height) + 'px'; });
    };
    /** "PRO" as the page draws it (app.css): a tag — the logo's cyan on a tint of it, a small radius, not a pill nor a button —, on
     *  the line of the name it marks. */
    const markOf = function (named) {
        const mark = named.querySelector('.s2p-pro');
        if (!shows(mark)) return 'no mark';
        const style = getComputedStyle(mark);
        const name = named.firstElementChild;
        const tinted = /^rgba\(13, 202, 240, 0\.[0-9]+\)$/.test(style.backgroundColor);
        return [text(mark), style.color === 'rgb(13, 202, 240)' && tinted ? 'cyan tag' : style.color + ' on ' + style.backgroundColor,
            parseFloat(style.borderTopLeftRadius) <= 4, Math.abs(middle(mark) - middle(name)) < 4];
    };
    const MARK = ['PRO', 'cyan tag', true, true];

    S2P.scenario({
        name: 'pro-free-panel',
        title: 'an organiser without Pro, on a phone: the rows of the Pro features say "PRO" once (the limits\' row the limits that hold, on one line), the other comment styles are not to pick, the queue has no "PRO"',
        page: 'dashboard-free',
        viewport: '390,844',
        run: async function (t) {
            document.getElementById('settingsToggle').click();
            const row = function (name) { return document.querySelector('#settingsMore [data-settings-row="' + name + '"]'); };
            t.step('the rows stay, each where it was', Array.from(document.querySelectorAll('#settingsMore [data-settings-row]'))
                .filter(shows).map(function (r) { return r.dataset.settingsRow; }), ['hosts', 'links', 'limits', 'staff', 'device']);
            t.step('"PRO" by the name of each Pro feature: a cyan tag on the name\'s line',
                ['hosts', 'links', 'limits'].map(function (name) { return markOf(row(name).querySelector('.s2p-row-name')); }), [MARK, MARK, MARK]);
            t.step('what each says', ['hosts', 'links', 'limits'].map(function (name) {
                const state = row(name).querySelector('.s2p-row-state');
                const parts = state.children.length ? Array.from(state.children).filter(shows) : [state];
                return parts.map(text).join(' ');
            }), ['', '', '2 na gościa · 3 min']);
            t.step('the limits that hold on one line', (function () {
                // at a phone's 390 px (run.py sets the width); the lines of the text are counted by a Range (one box per line)
                const range = document.createRange();
                range.selectNodeContents(row('limits').querySelector('[data-limit-calm]'));
                return new Set(Array.from(range.getClientRects()).map(function (r) { return Math.round(r.top); })).size;
            })(), 1);
            t.step('no "PRO" where nothing is Pro', [markOf(row('staff').querySelector('.s2p-row-name'))], ['no mark']);
            t.step('each row one line, a thumb\'s target, inside the screen', ['hosts', 'links', 'limits'].filter(function (name) {
                const box = row(name).getBoundingClientRect();
                return box.height < 44 - 0.5 || box.height > 60 || box.right > window.innerWidth;
            }), []);
            // the list of the comment styles (the owner's screenshot, 2026-10-14: "Zabawne · Pro" read as one more choice): "Klasyczne"
            // to pick, the others under a heading of the list itself, "W planie Pro", not to pick and in a quieter colour
            const options = Array.from(document.querySelectorAll('#commentStyleSelect option'));
            t.step('the comment styles: "Klasyczne" to pick, the others under "W planie Pro" and not', options.map(function (o) {
                return [o.textContent.trim(), o.disabled, o.parentElement.tagName === 'OPTGROUP' ? o.parentElement.label : null];
            }), [['Klasyczne', false, null], ['Zabawne', true, 'W planie Pro'], ['Sarkastyczne', true, 'W planie Pro'], ['Krótkie', true, 'W planie Pro']]);
            t.step('the ones not to pick in a quieter colour than "Klasyczne"', getComputedStyle(options[1]).color !== getComputedStyle(options[0]).color, true);
            t.step('nothing of Pro in the queue', document.querySelectorAll('#queueList .s2p-pro, #queueList [data-pro-offer]').length, 0);
            t.check('nothing wider than the screen', document.documentElement.scrollWidth <= window.innerWidth);
        }
    });

    S2P.scenario({
        name: 'pro-free-settings-phone',
        title: 'an organiser without Pro, the page "Ustawienia imprezy" on a phone: each Pro card says what waits or holds and leads to /pro, no form of it; 44 px targets',
        page: 'settings-free',
        viewport: '390,844',
        run: async function (t) {
            const cards = ['hostListsCard', 'linksCard', 'limitsCard'].map(function (id) { return document.getElementById(id); });
            t.step('"PRO" by each card\'s title', cards.map(function (card) { return markOf(card.querySelector('.s2p-card-title')); }),
                [MARK, MARK, MARK]);
            t.step('the jumps without "PRO" (the card says it, just below)', document.querySelectorAll('#settingsJump .s2p-pro').length, 0);
            t.step('in place of the form: what waits, or what holds, and the way to what Pro adds', cards.map(function (card) {
                const offer = card.querySelector('[data-pro-offer]');
                return shows(offer) && [getComputedStyle(offer).borderTopStyle, offer.querySelector('a').getAttribute('href'),
                    text(offer.querySelector('a'))];
            }), [['solid', '/pro', 'Zobacz, co daje Pro'], ['solid', '/pro', 'Zobacz, co daje Pro'], ['solid', '/pro', 'Zobacz, co daje Pro']]);
            t.step('no form of a Pro feature on the page', document.querySelectorAll('form[action="/dj/dashboard/host-lists"], '
                + 'form[action="/dj/dashboard/host-link"], form[action="/dj/dashboard/dj-links"], form[action="/dj/dashboard/tip-link"], '
                + 'form[action="/dj/dashboard/limits"]').length, 0);
            t.step('the lists wait — said, not lost', text(cards[0].querySelector('[data-pro-offer] p')).indexOf('Twoje listy są zachowane') === 0, true);
            t.step('nothing to tap is lower than 44 px', tooSmall(document.body), []);
            t.check('nothing wider than the screen', document.documentElement.scrollWidth <= window.innerWidth);
            t.step('every jump whole on the screen', Array.from(document.querySelectorAll('#settingsJump a')).filter(function (a) {
                const box = a.getBoundingClientRect();
                return box.left < 0 || box.right > window.innerWidth;
            }).map(text), []);
        }
    });

    S2P.scenario({
        name: 'pro-free-settings-wide',
        title: 'an organiser without Pro, the page "Ustawienia imprezy" on a computer: the offer inside its card, its sentence and button on one line, the title with "PRO" on one line',
        page: 'settings-free',
        viewport: '1280,900',
        run: async function (t) {
            const card = document.getElementById('hostListsCard');
            const offer = card.querySelector('[data-pro-offer]');
            const inside = function (inner, outer) {
                const a = inner.getBoundingClientRect(), b = outer.getBoundingClientRect();
                return a.left >= b.left && a.right <= b.right && a.top >= b.top && a.bottom <= b.bottom;
            };
            t.step('the offer inside its card, its button inside the offer', [inside(offer, card), inside(offer.querySelector('a'), offer)], [true, true]);
            t.step('each offer one line: the sentence, then its button beside it', ['hostListsCard', 'linksCard', 'limitsCard'].map(function (id) {
                const box = document.querySelector('#' + id + ' [data-pro-offer]');
                const words = box.querySelector('p').getBoundingClientRect(), button = box.querySelector('a').getBoundingClientRect();
                return button.left >= words.right && button.top < words.bottom && button.bottom > words.top;
            }), [true, true, true]);
            const title = card.querySelector('.s2p-card-title');
            t.check('the title and its "PRO" on one line', title.getBoundingClientRect().height < 40);
        }
    });

    // ---- the page /pro (pro_pl.html, rendered by ProPageRenderTest as pro-pl.html — a DJ logged in): a page of the product, not a list
    const proPage = async function (t, wide) {
        const actions = Array.from(document.querySelectorAll('.btn-action')).filter(shows);
        t.step('one action on the page: write that you want Pro', actions.map(function (a) { return [text(a), a.getAttribute('href').split('?')[0]]; }),
            [['Napisz, że chcesz Pro', 'mailto:scan2playcontact@gmail.com']]);
        t.step('the title, and "PRO" a cyan tag above it', [text(document.querySelector('h1')),
            markOf(document.querySelector('[data-pro-hero]'))], ['Scan2Play Pro', MARK]);
        const free = document.querySelector('[data-plan="free"]'), pro = document.querySelector('[data-plan="pro"]');
        t.step('two plans, Free and Pro, each with what it has', [free, pro].map(function (card) {
            return shows(card) && [text(card.querySelector('h2')), card.querySelectorAll('li').length > 4];
        }), [['Free', true], ['Pro', true]]);
        const a = free.getBoundingClientRect(), b = pro.getBoundingClientRect();
        t.step(wide ? 'side by side on a computer' : 'one under the other on a phone',
            wide ? [Math.abs(a.top - b.top) < 2, b.left >= a.right] : [b.top >= a.bottom, Math.abs(a.left - b.left) < 2], [true, true]);
        t.step('each Pro feature named, and what it gives under it', Array.from(pro.querySelectorAll('li')).filter(function (li) {
            return !li.querySelector('b') || !text(li).slice(text(li.querySelector('b')).length).trim();
        }).length, 0);
        t.step('the address also as text, to copy by hand', document.querySelector('[data-pro-address]') !== null
            && text(document.querySelector('[data-pro-address]')), 'scan2playcontact@gmail.com');
        // "Kopiuj adres" (dj-nav.js): the clipboard stood in for (a headless Chrome has none to read back, and may never answer), once
        // taking the address and once refusing it — then the address shown on the page is selected
        const copy = document.querySelector('button[data-copy-text]');
        const clipboard = function (answer) {
            const copied = [];
            Object.defineProperty(navigator, 'clipboard', { configurable: true,
                value: { writeText: function (words) { copied.push(words); return answer; } } });
            return copied;
        };
        const copied = clipboard(Promise.resolve());
        copy.click();
        await t.sleep(100);
        t.step('"Kopiuj adres" copies the address and says so', [copied, text(copy)], [['scan2playcontact@gmail.com'], 'Skopiowano']);
        await t.sleep(1600);
        window.getSelection().removeAllRanges();
        clipboard(Promise.reject(new Error('refused')));
        copy.click();
        await t.sleep(100);
        t.step('the clipboard refused: the address on the page selected, to copy by hand', String(window.getSelection()), 'scan2playcontact@gmail.com');
        t.step('the questions one asks before buying, answered', Array.from(document.querySelectorAll('[data-pro-faq] h3')).map(text),
            ['Co, gdy Pro się skończy?', 'Czy obsługa potrzebuje Pro?', 'A goście?']);
        t.step('nothing to tap is lower than 44 px', wide ? [] : tooSmall(document.body), []);
        t.check('nothing wider than the screen', document.documentElement.scrollWidth <= window.innerWidth);
    };

    S2P.scenario({
        name: 'pro-page-phone',
        title: 'the page /pro on a phone: one action, the plans Free and Pro one under the other, each Pro feature with what it gives, "Kopiuj adres", 44 px targets',
        page: 'pro-pl',
        viewport: '390,844',
        run: async function (t) { await proPage(t, false); }
    });

    S2P.scenario({
        name: 'pro-page-wide',
        title: 'the page /pro on a computer: the plans Free and Pro side by side, one action',
        page: 'pro-pl',
        viewport: '1280,900',
        run: async function (t) { await proPage(t, true); }
    });
})();
