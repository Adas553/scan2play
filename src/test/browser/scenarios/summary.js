// "Podsumowanie wieczoru" (summary.html, summary.css, summary.js; rendered by SummaryPageTest): on a phone the counts come within the
// first screen (the bar is the evening and "⋯" on one row, the version, "Drukuj"), what played is folded and unfolds to print; the hosts'
// version of a long wedding (40 played) prints on one A4 page; "Kopiuj listę" copies the library's gaps one a line.

(function () {
    // what a closed <details> holds keeps its boxes (content-visibility), so getClientRects() alone would call it shown
    function shows(element) { return !!element && element.getClientRects().length > 0 && element.checkVisibility(); }

    S2P.scenario({
        name: 'summary-phone-compact',
        title: 'the summary on a phone: the bar in three rows, the four counts in the first screen, what played folded — unfolded to print',
        page: 'summary-wedding-pl',
        viewport: '390,844',
        run: async function (t) {
            const html = document.documentElement;
            t.step('the window is a phone\'s', html.clientWidth <= 400, true);
            t.check('nothing wider than the screen', html.scrollWidth <= html.clientWidth);
            const select = document.getElementById('eveningSelect');
            const more = document.querySelector('#moreMenu > summary');
            t.step('"Pokaż" hidden (the evening shows when picked)', shows(select.form.querySelector('button[type="submit"]')), false);
            t.check('the evening and "⋯" on one row',
                Math.abs(select.getBoundingClientRect().top - more.getBoundingClientRect().top) < 8);
            const controls = [select, more, document.getElementById('viewDj'), document.getElementById('viewHosts'),
                document.getElementById('printBtn')];
            t.check('every control of the bar at least 44 px high', controls.every(function (c) { return c.getBoundingClientRect().height >= 44; }));
            const stats = document.querySelectorAll('#stats .stat');
            t.step('four counts', stats.length, 4);
            t.check('the four counts within the first screen (the last one\'s foot above 844 px)',
                stats[3].getBoundingClientRect().bottom < 844);
            t.check('the CSV and the way back under "⋯", folded', !shows(document.getElementById('csvLink')));
            more.click();
            t.check('"⋯" unfolds them', shows(document.getElementById('csvLink')));
            const fold = document.getElementById('setlistFold');
            t.step('what played is folded on a phone', [fold.open, shows(fold.querySelector('.setlist'))], [false, false]);
            window.dispatchEvent(new Event('beforeprint'));
            t.step('and unfolded to print', fold.open, true);
        }
    });

    S2P.scenario({
        name: 'summary-wide-one-row',
        title: 'the summary on a computer: the bar in one row, the four counts in one row, what played unfolded',
        page: 'summary-wedding-pl',
        run: async function (t) {
            const tops = Array.prototype.map.call(document.querySelectorAll('#stats .stat'), function (s) { return Math.round(s.getBoundingClientRect().top); });
            t.step('the four counts in one row', new Set(tops).size, 1);
            const bar = ['eveningSelect', 'viewHosts', 'printBtn'].map(function (id) { return document.getElementById(id).getBoundingClientRect().top; });
            t.check('the evening, the version and "Drukuj" in one row', Math.max.apply(null, bar) - Math.min.apply(null, bar) < 8);
            t.step('what played unfolded', document.getElementById('setlistFold').open, true);
            // one compact row: the evening as wide as its line, the version as wide as its words — not half the screen each
            t.check('the evening\'s list at most 320 px wide', document.getElementById('eveningSelect').getBoundingClientRect().width <= 320);
            t.check('the version at most 360 px wide', document.getElementById('views').getBoundingClientRect().width <= 360);
            const groups = Array.prototype.map.call(document.querySelectorAll('#eveningSelect optgroup'), function (g) { return g.label; });
            t.step('the evenings by month', groups, ['październik 2026', 'wrzesień 2026']);
        }
    });

    S2P.scenario({
        name: 'summary-hosts-panel-phone',
        title: 'the hosts\' version on a phone: the evening\'s title and its link in a panel above the page (not printed), every control 44 px, "Kopiuj" copies the link',
        page: 'summary-hosts-linked-pl',
        viewport: '390,844',
        run: async function (t) {
            const html = document.documentElement;
            t.check('nothing wider than the screen', html.scrollWidth <= html.clientWidth);
            const panel = document.getElementById('hostsPanel');
            const controls = panel.querySelectorAll('input[type="text"], button');
            t.check('every field and button of the panel at least 44 px high',
                Array.prototype.every.call(controls, function (c) { return c.getBoundingClientRect().height >= 44; }));
            t.step('the title heads the page', document.querySelector('main h1').textContent.trim(), 'Wesele Ani i Tomka');
            const written = [];
            Object.defineProperty(navigator, 'clipboard', { configurable: true, value: {
                writeText: function (text) { written.push(text); return Promise.resolve(); }
            } });
            panel.querySelector('button[data-copy-target="shareLinkInput"]').click();
            await t.waitFor(function () { return written.length > 0; }, 'the clipboard written', 2000);
            t.step('"Kopiuj" copies the link', written, [document.getElementById('shareLinkInput').value]);
            t.step('the panel is not printed', getComputedStyle(panel).getPropertyValue('display') !== 'none'
                && panel.classList.contains('no-print'), true);
        }
    });

    S2P.scenario({
        name: 'summary-shared-page',
        title: 'the hosts\' link (/s/…): the hosts\' version alone — no bar, no panel — on one A4 page, under the real policy',
        page: 'summary-shared-pl',
        pdf: 1,
        run: async function (t) {
            t.step('no bar, no panel', [!!document.querySelector('.toolbar'), !!document.getElementById('hostsPanel')], [false, false]);
            t.step('the title heads the page', document.querySelector('main h1').textContent.trim(), 'Wesele Ani i Tomka');
            t.step('what played unfolded', document.getElementById('setlistFold').open, true);
        }
    });

    S2P.scenario({
        name: 'summary-hosts-one-a4',
        title: 'the hosts\' version of a long wedding (40 played, ten most wanted, the half-hours) prints on one A4 page, nothing of the DJ\'s own',
        page: 'summary-long-hosts-pl',
        pdf: 1,
        run: async function (t) {
            t.step('the hosts\' version lit', document.getElementById('viewHosts').getAttribute('aria-current'), 'page');
            t.step('nothing of the DJ\'s own', ['missed', 'libraryGaps', 'artists', 'moreCounts', 'wait'].map(function (id) { return !!document.getElementById(id); }),
                [false, false, false, false, false]);
            t.step('40 played, unfolded', [document.querySelectorAll('#setlist li').length, document.getElementById('setlistFold').open], [40, true]);
        }
    });

    S2P.scenario({
        name: 'summary-copy-library',
        title: '"Kopiuj listę" (shown by the script) copies the songs not in the library, one a line, and says "Skopiowano"',
        page: 'summary-wedding-library-pl',
        run: async function (t) {
            const button = document.getElementById('copyLibrary');
            t.step('shown with the script', shows(button), true);
            const written = [];
            Object.defineProperty(navigator, 'clipboard', { configurable: true, value: {
                writeText: function (text) { written.push(text); return Promise.resolve(); }
            } });
            const songs = Array.prototype.map.call(document.querySelectorAll('#libraryList .song'), function (s) { return s.textContent.trim(); });
            button.click();
            await t.waitFor(function () { return written.length > 0; }, 'the clipboard written', 2000);
            t.step('the songs one a line, in the list\'s order', written, [songs.join('\n')]);
            t.step('"Skopiowano" for a moment', button.textContent.trim(), 'Skopiowano');
        }
    });
})();
