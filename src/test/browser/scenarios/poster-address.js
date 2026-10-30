// The address a guest types from paper when the code does not scan (qr-print.html, the fragment printedLink): the party's code in it is
// never broken across lines — on a card's narrow column the address broke anywhere, "…/p/P" over "RNT1" — and it is printed as typed,
// without "https://". On the poster, every card and both faces of a tent; with the DJ's logo too (the card's column is the same).
(function () {
    /** The distinct lines the characters of {@code code} take inside {@code el} (their tops, rounded). */
    function linesOf(el, code) {
        const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
        let text = '';
        const nodes = [];
        while (walker.nextNode()) { nodes.push({ node: walker.currentNode, from: text.length }); text += walker.currentNode.data; }
        const at = text.lastIndexOf(code);
        if (at < 0) return -1;
        const tops = new Set();
        for (let i = at; i < at + code.length; i++) {
            const n = nodes.filter(function (x) { return x.from <= i; }).pop();
            const range = document.createRange();
            range.setStart(n.node, i - n.from);
            range.setEnd(n.node, i - n.from + 1);
            const box = range.getBoundingClientRect();
            if (box.width) tops.add(Math.round(box.top));
        }
        return tops.size;
    }

    function check(t, selector) {
        const code = document.querySelector('#posterForm input[name="partyCode"]').value;
        const links = Array.from(document.querySelectorAll(selector)).filter(function (el) { return el.checkVisibility(); });
        t.check('the addresses shown (' + links.length + ')', links.length > 0);
        t.step('the party\'s code on one line in every address', links.map(function (el) { return linesOf(el, code); })
            .filter(function (n) { return n !== 1; }), []);
        t.step('printed as typed, without https://', links.filter(function (el) { return /https?:\/\//.test(el.textContent); }).length, 0);
        t.step('the address ends with the party\'s code', links.every(function (el) { return el.textContent.trim().endsWith('/p/' + code); }), true);
    }

    // run.py finds a scenario by its literal name: one call each
    function on(selector) {
        return async function (t) {
            await document.fonts.ready;
            check(t, selector);
        };
    }

    S2P.scenario({
        name: 'poster-address-code-whole-cards',
        page: 'qr-print-cards',
        title: 'the address on the cards: the party\'s code never broken across lines, no "https://"',
        run: on('.card .link')
    });

    S2P.scenario({
        name: 'poster-address-code-whole-cards-logo',
        page: 'qr-print-cards-logo',
        title: 'the address on the cards with the DJ\'s logo, three profiles and the tip: the party\'s code never broken, no "https://"',
        run: on('.card .link')
    });

    S2P.scenario({
        name: 'poster-address-code-whole-tent',
        page: 'qr-print-tent-logo',
        title: 'the address on both faces of a tent: the party\'s code never broken across lines, no "https://"',
        run: on('.face .link')
    });

    S2P.scenario({
        name: 'poster-address-code-whole-poster',
        page: 'qr-print-poster-logo',
        title: 'the address on the poster: the party\'s code never broken across lines, no "https://"',
        run: on('.poster .link')
    });
})();
