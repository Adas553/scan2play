// "Motyw dla gości" (V59): the guests' pages in each theme, as GuestPageRenderTest, KioskWebTest and TvScreenWebTest render them,
// with the real app.css, tv.css and guest-themes.css. What no theme may touch, measured in the browser on the computed styles: the
// texts and the action at WCAG AA on what is behind them, the statuses' colours (the green "Twoja", the yellow 👍), every target at
// least 44 px on a phone, one action, the ornament above every text, no animation, nothing wider than the screen. And the card in
// "Ustawienia imprezy": a theme picked is sent at once, the server's card put in place, the focus kept on the theme.
(function () {
    /** The face of "🎧 Gra: …" each theme names (none: the page's own). */
    const FACES = { CHRISTMAS: 'S2P Serif', SCARY: 'S2P Scary', WEDDING: 'S2P Serif' };

    /** "rgb(r, g, b)" / "rgba(r, g, b, a)" as [r, g, b, a]. */
    function parse(colour) {
        const parts = colour.match(/[\d.]+/g).map(Number);
        return [parts[0], parts[1], parts[2], parts.length > 3 ? parts[3] : 1];
    }

    function luminance(rgb) {
        const linear = rgb.slice(0, 3).map(function (c) {
            const v = c / 255;
            return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        });
        return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2];
    }

    /** The colour behind an element: the first ancestor with an opaque enough background, laid over the page's. */
    function behind(el) {
        const layers = [];
        for (let node = el; node && node.nodeType === 1; node = node.parentElement) {
            const bg = parse(getComputedStyle(node).backgroundColor);
            if (bg[3] > 0) layers.push(bg);
            if (bg[3] >= 0.99) break;
        }
        let colour = parse(getComputedStyle(document.body).backgroundColor);
        for (let i = layers.length - 1; i >= 0; i--) {
            const top = layers[i];
            colour = [0, 1, 2].map(function (k) { return top[k] * top[3] + colour[k] * (1 - top[3]); }).concat(1);
        }
        return colour;
    }

    function ratio(first, second) {
        const a = luminance(parse(first));
        const b = luminance(parse(second));
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    function contrast(el) {
        const a = luminance(parse(getComputedStyle(el).color));
        const b = luminance(behind(el));
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    /** Whether the element's own text (not its children's) has a letter or a digit: an emoji is drawn in its own colours. */
    function ownWords(el) {
        return Array.from(el.childNodes).some(function (n) { return n.nodeType === 3 && /[\p{L}\p{N}]/u.test(n.textContent); });
    }

    /** The elements of {@code selectors} whose own words read under {@code minimum} : 1 on what is behind them, named for the report. */
    function unreadable(selectors, minimum) {
        return Array.from(document.querySelectorAll(selectors))
            .filter(function (el) { return el.checkVisibility() && ownWords(el); })
            .filter(function (el) { return contrast(el) < minimum; })
            .map(function (el) { return (el.id || el.className || el.tagName) + ' ' + contrast(el).toFixed(2); });
    }

    /** The visible things to tap lower than 44 px (a hidden radio by its label). */
    function tooSmall(root) {
        return Array.from(root.querySelectorAll('a.btn, button, input:not([type="hidden"]), select, textarea, summary'))
            .map(function (el) { return el.matches('input.visually-hidden') && el.closest('label') ? el.closest('label') : el; })
            .filter(function (el) { return el.checkVisibility({ opacityProperty: true, visibilityProperty: true }); })
            .filter(function (el) { return el.getBoundingClientRect().height < 44 - 0.5; })
            .map(function (el) { return (el.id || el.textContent.trim().slice(0, 20) || el.tagName) + ' ' + Math.round(el.getBoundingClientRect().height) + 'px'; });
    }

    /** The texts (each line of each text node) whose box starts above {@code bottom} — under the ornament's band. */
    function textsAbove(bottom) {
        const found = [];
        const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
        for (let node = walker.nextNode(); node; node = walker.nextNode()) {
            if (!node.textContent.trim() || !node.parentElement.checkVisibility()) continue;
            const range = document.createRange();
            range.selectNodeContents(node);
            Array.from(range.getClientRects()).forEach(function (box) {
                if (box.width > 0 && box.top + window.scrollY < bottom - 0.5) found.push(node.textContent.trim().slice(0, 30));
            });
        }
        return found;
    }

    function ornament() {
        const after = getComputedStyle(document.body, '::after');
        return { image: after.backgroundImage, height: parseFloat(after.height) || 0, content: after.content };
    }

    /** What every themed guest's page keeps (the party page, the result, the "party ended" page). */
    function guestPageKeeps(t, theme) {
        t.step('the page is the theme\'s', document.body.dataset.guestTheme, theme);
        const band = ornament();
        t.check('the ornament is ours, in a band at the top (' + band.image + ', ' + band.height + ' px)',
            band.image.indexOf('/images/themes/') > 0 && band.height === 56);
        t.step('no text under the ornament', textsAbove(band.height), []);
        t.step('the texts read at 4.5 : 1 on what is behind them (the card, the page)',
            unreadable('h1, h2, h6, p, a, span, label, li, .form-text, summary, .s2p-song-name, .queue-title, .btn-link, #djName', 4.5), []);
        t.step('the action\'s words read at 4.5 : 1 on it', unreadable('.btn-action', 4.5), []);
        t.step('one action', Array.from(document.querySelectorAll('.btn-action')).filter(function (b) { return b.checkVisibility(); }).length, 1);
        t.step('nothing to tap is lower than 44 px', tooSmall(document.body), []);
        t.check('nothing wider than the screen', document.documentElement.scrollWidth <= document.documentElement.clientWidth);
        t.step('no animation', document.getAnimations().length, 0);
    }

    /** The party page in {@code theme} on a phone: what each themed page keeps, and its own. */
    async function themedPage(t, theme) {
        await document.fonts.ready;
        guestPageKeeps(t, theme);
        const mine = document.querySelector('.s2p-vote-mine');
        const given = document.querySelector('.s2p-vote-btn.btn-warning') || document.querySelector('.s2p-vote-btn');
        t.step('the statuses keep their colours: the green "Twoja", the yellow 👍', [getComputedStyle(mine).backgroundColor,
            getComputedStyle(document.querySelector('.s2p-badge-mine')).backgroundColor, getComputedStyle(given).borderColor],
            ['rgb(29, 185, 84)', 'rgb(29, 185, 84)', 'rgb(255, 193, 7)']);
        t.step('the words on the green read at 4.5 : 1', unreadable('.s2p-vote-mine, .s2p-badge-mine', 4.5), []);
        const action = getComputedStyle(document.getElementById('submitBtn')).backgroundColor;
        t.check('the action is the theme\'s colour, not the classic cyan (' + action + ')', action !== 'rgb(13, 202, 240)');
        const field = document.getElementById('songInput');
        const look = getComputedStyle(field);
        const card = 'rgb(' + behind(field.closest('.card')).slice(0, 3).join(', ') + ')';
        t.check('the field is dark, not Bootstrap\'s white (' + look.backgroundColor + ')', luminance(parse(look.backgroundColor)) < 0.05);
        t.step('the field\'s words and its placeholder read at 4.5 : 1, its edge at 3 : 1 on the card', [
            ratio(look.color, look.backgroundColor) >= 4.5, ratio(getComputedStyle(field, '::placeholder').color, look.backgroundColor) >= 4.5,
            ratio(look.borderTopColor, card) >= 3], [true, true, true]);
        const face = FACES[theme];
        if (face) {
            // the serif themes set the whole line, the scary one the name alone (its capitals are wide)
            const named = theme === 'SCARY' ? document.querySelector('#djName .s2p-dj-name') : document.getElementById('djName');
            t.check('"🎧 Gra: …" in ' + face, getComputedStyle(named).fontFamily.indexOf(face) >= 0);
            t.check(face + ' loaded from our server (the Polish letters too)', Array.from(document.fonts).some(function (f) {
                return f.family.replace(/"/g, '') === face && f.status === 'loaded';
            }));
        }
        const dj = document.getElementById('djName');
        const style = getComputedStyle(dj);
        const line = parseFloat(style.lineHeight) || 1.5 * parseFloat(style.fontSize);
        t.step('"🎧 Gra: DJ Łukasz Żółć" on one line of the card', Math.round(dj.getBoundingClientRect().height / line), 1);
    }

    S2P.scenario({
        name: 'guest-theme-christmas',
        title: 'the party page in the theme CHRISTMAS on a phone: the ornament above every text, AA for the texts and the action, the statuses as in every theme, 44 px, one action, no animation, the theme\'s face loaded',
        page: 'guest-theme-christmas',
        viewport: '390,844',
        run: function (t) { return themedPage(t, 'CHRISTMAS'); }
    });

    S2P.scenario({
        name: 'guest-theme-scary',
        title: 'the party page in the theme SCARY on a phone: the ornament above every text, AA for the texts and the action, the statuses as in every theme, 44 px, one action, no animation, the theme\'s face loaded',
        page: 'guest-theme-scary',
        viewport: '390,844',
        run: function (t) { return themedPage(t, 'SCARY'); }
    });

    S2P.scenario({
        name: 'guest-theme-confetti',
        title: 'the party page in the theme CONFETTI on a phone: the ornament above every text, AA for the texts and the action, the statuses as in every theme, 44 px, one action, no animation, the theme\'s face loaded',
        page: 'guest-theme-confetti',
        viewport: '390,844',
        run: function (t) { return themedPage(t, 'CONFETTI'); }
    });

    S2P.scenario({
        name: 'guest-theme-wedding',
        title: 'the party page in the theme WEDDING on a phone: the ornament above every text, AA for the texts and the action, the statuses as in every theme, 44 px, one action, no animation, the theme\'s face loaded',
        page: 'guest-theme-wedding',
        viewport: '390,844',
        run: function (t) { return themedPage(t, 'WEDDING'); }
    });

    S2P.scenario({
        name: 'guest-theme-classic',
        title: 'the party page in the classic look: app.css alone — no themes\' stylesheet, no band, the logo\'s cyan action, the page as before',
        page: 'guest-theme-classic',
        viewport: '390,844',
        run: async function (t) {
            t.step('no theme, no themes\' stylesheet', [document.body.hasAttribute('data-guest-theme'),
                !!document.querySelector('link[href*="guest-themes"]')], [false, false]);
            t.step('no band', ornament().content, 'none');
            t.step('the action is the logo\'s cyan', getComputedStyle(document.getElementById('submitBtn')).backgroundColor, 'rgb(13, 202, 240)');
            t.step('the field Bootstrap\'s white, as before', getComputedStyle(document.getElementById('songInput')).backgroundColor, 'rgb(255, 255, 255)');
            t.step('the card where it was (the page\'s 20 px)', Math.round(document.querySelector('.card').getBoundingClientRect().top), 20);
        }
    });

    S2P.scenario({
        name: 'guest-theme-scary-long-name',
        title: 'the scary theme with a long DJ\'s name ("DJ Krzysztof Wiśniewski"): only the name in the dripping face, set smaller, never broken inside, the line inside the card',
        page: 'guest-theme-scary-long',
        viewport: '390,844',
        run: async function (t) {
            await document.fonts.ready;
            const line = document.getElementById('djName');
            const name = line.querySelector('.s2p-dj-name');
            t.step('"Gra:" in the page\'s font, the name in Nosifer', [getComputedStyle(line).fontFamily.indexOf('S2P Scary') >= 0,
                getComputedStyle(name).fontFamily.indexOf('S2P Scary') >= 0], [false, true]);
            t.step('the name is one line of its own (not broken after "DJ")',
                Math.round(name.getBoundingClientRect().height / parseFloat(getComputedStyle(name).lineHeight)), 1);
            const card = line.closest('.card').getBoundingClientRect();
            const box = name.getBoundingClientRect();
            t.check('the name inside the card', box.left >= card.left && box.right <= card.right);
            t.check('nothing wider than the screen', document.documentElement.scrollWidth <= document.documentElement.clientWidth);
        }
    });

    S2P.scenario({
        name: 'guest-theme-result',
        title: 'the result in a theme (a rejected request, the scary one): the band above every text, the verdict\'s red and the words read, one action',
        page: 'result-theme',
        viewport: '390,844',
        run: async function (t) {
            await document.fonts.ready;
            guestPageKeeps(t, 'SCARY');
            t.step('"NIE" keeps the statuses\' red', getComputedStyle(document.querySelector('.rejected')).color, 'rgb(255, 68, 68)');
        }
    });

    S2P.scenario({
        name: 'guest-theme-party-ended',
        title: 'the "party ended" page in a theme (the wedding): the band above every text, the words read, one action',
        page: 'party-ended-theme',
        viewport: '390,844',
        run: async function (t) {
            await document.fonts.ready;
            guestPageKeeps(t, 'WEDDING');
        }
    });

    S2P.scenario({
        name: 'guest-theme-kiosk',
        title: 'the kiosk in a theme on a tablet held across: the theme\'s colours, no band (one screen), the whole kiosk in one screen, the words read',
        page: 'kiosk-scary',
        viewport: '1180,820',
        run: async function (t) {
            t.step('the kiosk is the theme\'s', document.body.dataset.guestTheme, 'SCARY');
            t.step('no band: the kiosk is one screen', ornament().content, 'none');
            t.check('the whole kiosk in one screen', document.documentElement.scrollHeight <= window.innerHeight + 2);
            t.step('the action is the theme\'s orange', getComputedStyle(document.getElementById('submitBtn')).backgroundColor, 'rgb(255, 145, 66)');
            t.step('the texts and the action read at 4.5 : 1', unreadable('h1, h2, p, label, li, .btn-action, #djName', 4.5), []);
        }
    });

    S2P.scenario({
        name: 'guest-theme-tv',
        title: 'the TV in a theme (1280 × 720): the band in the top padding, above every text, the address in the theme\'s accent, the words read, nothing to scroll',
        page: 'tv-scary',
        viewport: '1280,720',
        run: async function (t) {
            await document.fonts.ready;
            const band = getComputedStyle(document.querySelector('.tv'), '::after');
            t.check('the ornament is ours (' + band.backgroundImage + ')', band.backgroundImage.indexOf('/images/themes/scary.svg') > 0);
            t.step('no text under the ornament', textsAbove(parseFloat(band.height)), []);
            t.step('the address in the theme\'s accent', getComputedStyle(document.querySelector('.tv-link')).color, 'rgb(255, 145, 66)');
            t.step('the logo\'s "2" stays ours', getComputedStyle(document.querySelector('.tv-logo .two')).color, 'rgb(13, 202, 240)');
            t.step('the texts read at 4.5 : 1', unreadable('h1, h2, p, .tv-name, .tv-votes', 4.5), []);
            t.check('nothing to scroll', document.documentElement.scrollHeight <= window.innerHeight
                && document.documentElement.scrollWidth <= document.documentElement.clientWidth);
        }
    });

    S2P.scenario({
        name: 'guest-theme-card',
        title: '"Motyw dla gości" on a phone: five themes drawn by their own colours, 44 px; a theme tapped is sent at once in the background, the server\'s card in place, "✓ Zapisane", the focus on the theme, no page load',
        page: 'settings',
        viewport: '390,844',
        run: async function (t) {
            const loads = [];
            navigation.addEventListener('navigate', function (e) { loads.push(e.destination.url); e.preventDefault(); });
            const card = document.getElementById('guestThemeCard');
            t.step('folded to the theme', card.querySelector('.s2p-fold-state').textContent.trim(), 'Klasyczny');
            card.open = true;
            const buttons = Array.from(card.querySelectorAll('.s2p-theme-thumb-button')).map(function (b) { return getComputedStyle(b).backgroundColor; });
            t.step('five themes, each its own action colour', [buttons.length, new Set(buttons).size], [5, 5]);
            t.step('nothing to tap is lower than 44 px', tooSmall(card), []);
            t.check('nothing wider than the screen', document.documentElement.scrollWidth <= document.documentElement.clientWidth);
            t.step('no "Zapisz" with the script (it saves at once)', Array.from(card.querySelectorAll('button')).filter(function (b) {
                return b.checkVisibility();
            }).length, 0);
            const wedding = document.getElementById('guestTheme-wedding');
            wedding.focus();
            wedding.closest('label').click();
            await t.waitFor(function () { return document.getElementById('guestThemeSaved'); }, 'the server\'s card', 4000);
            const sent = await t.stand.requests('POST /dj/dashboard/guest-theme');
            t.step('sent once, in the background, with the theme, the party and the token', sent.map(function (r) {
                return [r.q.theme, r.q.partyCode, r.q['X-Requested-With'], r.q['X-CSRF-TOKEN']];
            }), [['WEDDING', 'HARN1', 'fetch', 'harness-csrf-token']]);
            t.step('the card says it: the folded line and "✓ Zapisane"', [card.querySelector('.s2p-fold-state').textContent.trim(),
                document.getElementById('guestThemeSaved').textContent.trim(), card.open], ['Weselny', '✓ Zapisane', true]);
            t.step('the focus on the theme picked (the new card\'s radio)', [document.activeElement.id, document.activeElement.checked],
                ['guestTheme-wedding', true]);
            t.step('the same card element, no page load', [document.getElementById('guestThemeCard') === card, loads], [true, []]);
        }
    });
})();
