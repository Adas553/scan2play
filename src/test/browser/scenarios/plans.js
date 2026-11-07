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
            // drawn: not in a folded card (laid out, not drawn) nor transparent (the file input under its label, which is the target)
            .filter(function (el) { return el.checkVisibility({ opacityProperty: true, visibilityProperty: true }); })
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
        title: 'an organiser without Pro, on a phone: the other comment styles are not to pick, the queue has no "PRO"',
        page: 'dashboard-free',
        viewport: '390,844',
        run: async function (t) {
            document.getElementById('settingsToggle').click();
            t.step('no rows of "Więcej": the Pro features\' "PRO" is on the page "Ustawienia imprezy"', document.querySelectorAll('[data-settings-row]').length, 0);
            // the list of the comment styles (the owner's screenshot, 2026-10-14: "Zabawne · Pro" read as one more choice): "Klasyczne"
            // to pick, the others under a heading of the list itself, "W planie Pro", not to pick and in a quieter colour
            const options = Array.from(document.querySelectorAll('#commentStyleSelect option'));
            t.step('the comment styles: "Klasyczne" to pick, the others under "W planie Pro" and not', options.map(function (o) {
                return [o.textContent.trim(), o.disabled, o.parentElement.tagName === 'OPTGROUP' ? o.parentElement.label : null];
            }), [['Klasyczne', false, null], ['Zabawne', true, 'W planie Pro'], ['Sarkastyczne', true, 'W planie Pro'], ['Ciekawostki', true, 'W planie Pro'], ['Krótkie', true, 'W planie Pro']]);
            t.step('the ones not to pick in a quieter colour than "Klasyczne"', getComputedStyle(options[1]).color !== getComputedStyle(options[0]).color, true);
            t.step('nothing of Pro in the queue', document.querySelectorAll('#queueList .s2p-pro, #queueList [data-pro-offer]').length, 0);
            t.check('nothing wider than the screen', document.documentElement.scrollWidth <= document.documentElement.clientWidth);
        }
    });

    /** Waits until two more polls of the queue have been answered (the stand's config). */
    const afterPolls = async function (t) {
        const before = await t.stand.count('GET /dj/dashboard/updates');
        const started = performance.now();
        while ((await t.stand.count('GET /dj/dashboard/updates')) < before + 2) {
            if (performance.now() - started > 10000) throw new Error('timeout: two more polls of the queue');
            await t.sleep(100);
        }
        await t.sleep(200);
    };

    // The organiser's note the day after the Free plan's AI checks ran out (FreeAiLimit; dashboard-free-ai-yesterday.html: 212 asked,
    // 150 checked, the next day at noon): above the queue, the way to Pro a finger's target — and the polls (a new evening, nothing
    // asked: no flag, a use of 0) leave it as it is. The queue is empty here: "above the queue" is above its "no requests yet"
    S2P.scenario({
        name: 'free-ai-yesterday-note-phone',
        title: 'the day after the Free plan\'s AI checks ran out, on a phone: the organiser\'s note above the queue, "Zobacz, co daje Pro" a 44 px target; the polls leave it',
        page: 'dashboard-free-ai-yesterday',
        viewport: '390,844',
        setup: { guestLimits: 'none', guestLimitsUse: '0,0,0' },
        run: async function (t) {
            const note = document.querySelector('[data-free-ai-yesterday]');
            const state = function () {
                const link = note.querySelector('a[href="/pro"]');
                return [shows(note), text(note).indexOf('Wczoraj goście wysłali 212 próśb do oceny, AI oceniło 150'), shows(link) && text(link),
                    note.getBoundingClientRect().bottom <= document.getElementById('queueEmpty').getBoundingClientRect().top];
            };
            t.step('the note, its words, the way to Pro, above the queue', state(), [true, 0, 'Zobacz, co daje Pro', true]);
            t.step('nothing to tap in it lower than 44 px', tooSmall(note), []);
            await afterPolls(t);
            t.step('after the polls: as it was', state(), [true, 0, 'Zobacz, co daje Pro', true]);
            t.step('a new evening: no bar "wykorzystane"', shows(document.querySelector('[data-guest-limit="ai-limit"]')), false);
            t.check('nothing wider than the screen', document.documentElement.scrollWidth <= document.documentElement.clientWidth);
        }
    });

    // The gift of Pro when the plans are switched on (PlanService.giftEndsFor; dashboard-plan-gift.html): one quiet line to the
    // organiser above the queue (empty here: above its "no requests yet") — until when ("08.11"), and the pricing page — that the polls leave
    S2P.scenario({
        name: 'plan-gift-line-phone',
        title: 'the gift of Pro, on a phone: one quiet line above the queue — until when, "Zobacz cennik →" —, nothing wider than the screen; the polls leave it',
        page: 'dashboard-plan-gift',
        viewport: '390,844',
        setup: { guestLimits: 'none', guestLimitsUse: '0,0' },
        run: async function (t) {
            const line = document.querySelector('[data-plan-gift]');
            const state = function () {
                const link = line.querySelector('a[href="/pro"]');
                return [shows(line), /^Scan2Play ma teraz plany Free i Pro\. Do \d{2}\.\d{2}(\.\d{4})? masz Pro w prezencie, potem plan Free\. Zobacz cennik →$/.test(text(line)),
                    shows(link) && text(link), line.getBoundingClientRect().bottom <= document.getElementById('queueEmpty').getBoundingClientRect().top];
            };
            t.step('the line, its words, the pricing page, above the queue', state(), [true, true, 'Zobacz cennik →', true]);
            t.step('quiet: the size of the small print, no box', [parseFloat(getComputedStyle(line).fontSize) < 16,
                getComputedStyle(line).borderTopStyle], [true, 'none']);
            await afterPolls(t);
            t.step('after the polls: as it was', state(), [true, true, 'Zobacz cennik →', true]);
            t.check('nothing wider than the screen', document.documentElement.scrollWidth <= document.documentElement.clientWidth);
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
            t.step('folded: "PRO" said once — no state beside it (the profiles\', the lists\'); the limits say what holds',
                cards.map(function (card) { return card.querySelector('summary .s2p-fold-state') ? text(card.querySelector('summary .s2p-fold-state')) : null; }),
                [null, null, '2 na gościa · 3 min']);
            cards.forEach(function (card) { card.querySelector('summary').click(); });   // unfolded, as the DJ does
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
            t.check('nothing wider than the screen', document.documentElement.scrollWidth <= document.documentElement.clientWidth);
        }
    });

    S2P.scenario({
        name: 'pro-free-settings-wide',
        title: 'an organiser without Pro, the page "Ustawienia imprezy" on a computer: the offer inside its card, its sentence and button on one line, the title with "PRO" on one line',
        page: 'settings-free',
        viewport: '1280,900',
        run: async function (t) {
            document.querySelectorAll('details.s2p-fold').forEach(function (fold) { fold.querySelector('summary').click(); });
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
            [['Napisz, że chcesz Pro', 'mailto:contact@scan2play.com.pl']]);
        const free = document.querySelector('[data-plan="free"]'), pro = document.querySelector('[data-plan="pro"]');
        t.step('the title "Cennik", Pro marked "WCZESNY DOSTĘP" as a cyan tag by its name', [text(document.querySelector('h1')),
            markOf(pro.querySelector('h2'))], ['Cennik', ['WCZESNY DOSTĘP', 'cyan tag', true, true]]);
        t.step('Free costs 0 zł; Pro has no price, only "Napisz do nas"', [free, pro].map(function (card) {
            return text(card.querySelector('[data-plan-price]'));
        }), ['0 zł zawsze', 'Napisz do nas']);
        t.step('the action at the foot of the Pro card', pro.contains(actions[0]), true);
        t.step('two plans, Free and Pro, each with what it has', [free, pro].map(function (card) {
            return shows(card) && [text(card.querySelector('h2')), card.querySelectorAll('li').length > 4];
        }), [['Free', true], ['Pro WCZESNY DOSTĘP', true]]);
        const a = free.getBoundingClientRect(), b = pro.getBoundingClientRect();
        t.step(wide ? 'side by side on a computer' : 'one under the other on a phone',
            wide ? [Math.abs(a.top - b.top) < 2, b.left >= a.right] : [b.top >= a.bottom, Math.abs(a.left - b.left) < 2], [true, true]);
        t.step('each Pro feature named, and what it gives under it', Array.from(pro.querySelectorAll('li')).filter(function (li) {
            return !li.querySelector('b') || !text(li).slice(text(li.querySelector('b')).length).trim();
        }).length, 0);
        t.step('the address also as text, to copy by hand', document.querySelector('[data-pro-address]') !== null
            && text(document.querySelector('[data-pro-address]')), 'contact@scan2play.com.pl');
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
        t.step('"Kopiuj adres" copies the address and says so', [copied, text(copy)], [['contact@scan2play.com.pl'], 'Skopiowano']);
        await t.sleep(1600);
        window.getSelection().removeAllRanges();
        clipboard(Promise.reject(new Error('refused')));
        copy.click();
        await t.sleep(100);
        t.step('the clipboard refused: the address on the page selected, to copy by hand', String(window.getSelection()), 'contact@scan2play.com.pl');
        t.step('the questions one asks before buying, answered', Array.from(document.querySelectorAll('[data-pro-faq] h3')).map(text),
            ['Ile będzie kosztować Pro?', 'Co, gdy Pro się skończy?', 'Czy obsługa potrzebuje Pro?', 'A goście?']);
        const table = document.querySelector('[data-pro-compare]');
        t.step('the comparison: Free and Pro side by side, the AI\'s checks — Free\'s evening in numbers, Pro every request', [shows(table),
            Array.from(table.querySelectorAll('thead th')).slice(1).map(text),
            Array.from(table.querySelectorAll('tbody tr:nth-child(2) td')).map(text)],
            [true, ['Free', 'Pro'], ['150 na wieczór', 'każda prośba']]);
        // a 390 px phone: both plans' columns in view, the table not scrolled in its box (it was 9 px too wide)
        t.step('the comparison fits its box', table.scrollWidth <= table.parentElement.clientWidth, true);
        t.step('nothing to tap is lower than 44 px', wide ? [] : tooSmall(document.body), []);
        t.check('nothing wider than the screen', document.documentElement.scrollWidth <= document.documentElement.clientWidth);
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
    // ---- "Cennik" for a visitor, the plans enforced or not: beside the language at the top of the landing page and the pages "Dla
    // kogo"; /pro before the switch (pro_pl.html rendered by ProPageRenderTest as pro-free-pl.html — a visitor, the plans off) is one
    // card, everything free, and no word of Pro
    const pricingAtTheTop = function (t) {
        const link = document.getElementById('headerPricing'), nav = document.getElementById('langSwitchTop');
        t.step('"Cennik" at the top, to the pricing page', link && [text(link), link.getAttribute('href')], ['Cennik', '/pro']);
        if (!link || !nav) return;
        const a = link.getBoundingClientRect(), b = nav.getBoundingClientRect();
        t.step('"Cennik" before the language, on its line', [a.right <= b.left + 0.5, Math.abs(middle(link) - middle(nav)) < 4], [true, true]);
        t.step('as tall as the language\'s links, 44 px to tap', a.height >= 44 - 0.5, true);
        t.check('on the first screen, within the screen', a.left >= 0 && b.right <= document.documentElement.clientWidth && a.bottom <= window.innerHeight);
        t.check('nothing wider than the screen', document.documentElement.scrollWidth <= document.documentElement.clientWidth);
    };

    S2P.scenario({
        name: 'pricing-at-the-top-landing-narrow-phone',
        title: 'the landing page on a narrow phone (320 px): "Cennik" beside "Polski · English" at the top, 44 px, nothing sticks out',
        page: 'landing-pl',
        viewport: '320,700',
        run: async function (t) { pricingAtTheTop(t); }
    });

    S2P.scenario({
        name: 'pricing-at-the-top-for-whom-narrow-phone',
        title: 'a page "Dla kogo" on a narrow phone (320 px): "Cennik" beside "Polski · English", on the right under the breadcrumbs',
        page: 'for-whom-dj-weselny-pl',
        viewport: '320,700',
        run: async function (t) {
            pricingAtTheTop(t);
            const nav = document.getElementById('langSwitchTop');
            t.check('on the right of the page\'s column', nav.getBoundingClientRect().right
                >= document.querySelector('.s2p-landing-hero').getBoundingClientRect().right - 40);
        }
    });

    const proPageOff = function (t, wide) {
        const actions = Array.from(document.querySelectorAll('.btn-action')).filter(shows);
        t.step('one action: start for free', actions.map(function (a) { return [text(a), a.getAttribute('href')]; }), [['Zacznij za darmo', '/start']]);
        const cards = Array.from(document.querySelectorAll('.s2p-plan')).filter(shows);
        t.step('one card, "Za darmo", 0 zł', cards.map(function (c) { return [text(c.querySelector('h2')), text(c.querySelector('[data-plan-price]'))]; }),
            [['Za darmo', '0 zł']]);
        // the page's own words (the harness's report on the page names this scenario)
        const words = document.querySelector('body > .container').innerText + document.querySelector('footer').innerText;
        // a whole word (JavaScript's \b ends a word at "ś": "Prośby" would match it)
        t.step('no word of Pro on the page', words.match(/.{0,40}(?<!\p{L})Pro(?!\p{L}).{0,40}/gu) || [], []);
        t.step('the questions: will it stay so, a card, the guests', Array.from(document.querySelectorAll('[data-pro-faq] h3')).map(text),
            ['Czy tak zostanie?', 'Czy potrzebuję karty?', 'A goście?']);
        t.step('the terms\' section 5 linked', document.getElementById('freeTerms').getAttribute('href'), '/terms#plans');
        const card = cards[0].getBoundingClientRect();
        t.check('the card within the screen', card.left >= 0 && card.right <= document.documentElement.clientWidth);
        const items = Array.from(cards[0].querySelectorAll('li'));
        t.step(wide ? 'the list in two columns, not half a page with nothing beside it' : 'the list in one column',
            new Set(items.map(function (li) { return Math.round(li.getBoundingClientRect().left); })).size, wide ? 2 : 1);
        const terms = document.getElementById('freeTerms');
        t.step('the terms\' link in the accent, as the landing page\'s', getComputedStyle(terms).color, 'rgb(13, 202, 240)');
        t.step('nothing to tap is lower than 44 px (a finger: a phone)', wide ? [] : tooSmall(document.body), []);
        t.check('nothing wider than the screen', document.documentElement.scrollWidth <= document.documentElement.clientWidth);
    };

    S2P.scenario({
        name: 'pro-page-plans-off-narrow-phone',
        title: 'the page /pro before the plans are enforced, on a 320 px phone: one card "Za darmo", one action, no word of Pro',
        page: 'pro-free-pl',
        viewport: '320,700',
        run: async function (t) { proPageOff(t, false); }
    });

    S2P.scenario({
        name: 'pro-page-plans-off-wide',
        title: 'the page /pro before the plans are enforced, on a computer: one card the page\'s width, its list in two columns',
        page: 'pro-free-pl',
        viewport: '1280,900',
        run: async function (t) { proPageOff(t, true); }
    });
})();
