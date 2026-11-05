// "Nie wysyłaj mi tego" of the morning e-mail (V64): the question ("Wyłączyć poranne podsumowanie?") and the page after it, as
// EmailStopWebTest renders them through the real controller and security, on a phone with the real app.css and Bootstrap, under the
// real policy. Its words must read: without a card style of their own, Bootstrap's white card stood behind the pages' light words
// (about 1.1 : 1). The question's answer posts to the page's own address (no action, no CSRF token) — "Tak, wyłącz" a full target.
(function () {
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

    /** The colour behind an element: its ancestors' backgrounds laid over the page's, up to the first opaque one. */
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

    function contrast(el) {
        const a = luminance(parse(getComputedStyle(el).color));
        const b = luminance(behind(el));
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    /** The card's texts under WCAG AA (4.5 : 1), named with their contrast for the report. */
    function unreadable() {
        return Array.from(document.querySelectorAll('.card h1, .card p, .card span, .card a, .card button'))
            .filter(function (el) { return el.checkVisibility() && el.textContent.trim() !== ''; })
            .filter(function (el) { return contrast(el) < 4.5; })
            .map(function (el) { return (el.id || el.tagName) + ' ' + contrast(el).toFixed(2); });
    }

    function fitsTheScreen() {
        return document.documentElement.scrollWidth <= document.documentElement.clientWidth;
    }

    S2P.scenario({
        name: 'email-stop-asks-readably',
        title: 'the morning e-mail\'s "Nie wysyłaj mi tego" on a 390 px phone: it asks, every word readable on the dark card, "Tak, wyłącz" 44 px and posting to the page\'s own address',
        page: 'email-stop',
        viewport: '390,844',
        run: async function (t) {
            t.step('the question', document.getElementById('emailStopTitle').textContent.trim(), 'Wyłączyć poranne podsumowanie?');
            t.step('every word at 4.5 : 1 or more', unreadable(), []);
            const form = document.getElementById('emailStopForm');
            t.step('a POST to the page\'s own address, no token', [form.method, form.hasAttribute('action'),
                !!form.querySelector('input[name="_csrf"]')], ['post', false, false]);
            const button = form.querySelector('button[type="submit"]');
            t.step('"Tak, wyłącz" at least 44 px high', button.getBoundingClientRect().height >= 44 - 0.5, true);
            t.step('nothing wider than the screen', fitsTheScreen(), true);
        }
    });

    S2P.scenario({
        name: 'email-stopped-readably-en',
        title: 'after it, in English on a 390 px phone: "Done — no more morning summaries", every word readable on the dark card',
        page: 'email-stopped-en',
        viewport: '390,844',
        run: async function (t) {
            t.step('the answer', document.getElementById('emailStoppedTitle').textContent.trim(), 'Done — no more morning summaries');
            t.step('every word at 4.5 : 1 or more', unreadable(), []);
            t.step('nothing wider than the screen', fitsTheScreen(), true);
        }
    });
})();
