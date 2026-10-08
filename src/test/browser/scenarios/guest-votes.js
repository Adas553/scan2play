// A guest's 👍 on the party page (the owner, 2026-10-08: one vote per song). One list — the most votes first, five shown, the rest
// under "Pokaż pozostałe prośby", fetched only when unfolded (with 300 waiting, every refresh carrying them all is too much), with a
// search. A 👍 goes in the background (guest-party.js); the answer is that song's row, of which only the votes are put in place:
// nothing moves under the guest's finger (the owner: "ekran nie może skakać"), the new order comes with the next fetch of the list.
// The button waits for the answer: a double tap is one vote.
(function () {
    const rowOf = function (name) {
        return Array.from(document.querySelectorAll('#guestQueueBox li[data-song-id]')).find(function (li) {
            return li.textContent.indexOf(name) >= 0;
        });
    };
    const visibleSongs = function () {
        return Array.from(document.querySelectorAll('#guestQueueBox li[data-song-id]')).filter(function (li) {
            return li.checkVisibility();   // not getClientRects: a folded <details> hides its rows by content-visibility
        }).map(function (li) { return li.querySelector('.s2p-song-name').textContent.trim(); });
    };
    /** Where every song of the list is on the screen, and how far the page is scrolled: what must not change. */
    const layout = function () {
        return [Math.round(window.scrollY)].concat(Array.from(document.querySelectorAll('#guestQueueBox li[data-song-id]')).map(function (li) {
            return li.dataset.songId + '@' + Math.round(li.getBoundingClientRect().top);
        }));
    };
    /** Unfolds the rest, waits for it, and scrolls down to the list (at once: Bootstrap's smooth scrolling would still be moving). */
    const unfold = async function (t) {
        document.querySelector('#moreRequests summary').click();
        await t.waitFor(function () { return rowOf('Older 5'); }, 'the rest of the list', 5000);
        window.scrollTo({ top: document.body.scrollHeight, behavior: 'instant' });
        await t.sleep(100);
    };

    // The owner (2026-10-08): the badges were untidy — a big 👍 button, a small 👍 badge with "Twoja" beside it, "Twoja" alone. Now one
    // pill of one size on every row, right-aligned (the guest's own a green one), and "Twoja" beside the song's name.
    S2P.scenario({
        name: 'guest-list-tidy',
        title: 'the guest page: every song has one pill of one size, in one column on the right — the guest\'s own too —, "Twoja" beside its name',
        page: 'guest',
        viewport: '390,844',
        run: async function (t) {
            const rows = Array.from(document.querySelectorAll('#guestQueueBox li[data-song-id]'));
            const pills = rows.map(function (li) { return li.querySelectorAll('.s2p-vote-pill'); });
            t.step('one pill on every row', pills.map(function (p) { return p.length; }), rows.map(function () { return 1; }));
            const boxes = pills.map(function (p) { return p[0].getBoundingClientRect(); });
            t.step('one size, one right edge', [new Set(boxes.map(function (b) { return Math.round(b.width); })).size,
                new Set(boxes.map(function (b) { return Math.round(b.right); })).size], [1, 1]);
            const mine = rowOf('Mine');
            const label = Array.from(mine.querySelectorAll('.badge')).find(function (b) { return b.textContent.trim() === 'Twoja'; });
            t.check('"Twoja" beside the name, left of the pill',
                !!label && label.getBoundingClientRect().right < mine.querySelector('.s2p-vote-pill').getBoundingClientRect().left
                && label.getBoundingClientRect().left > mine.querySelector('.s2p-song-name').getBoundingClientRect().left);
        }
    });

    // The installed app takes every address of the site: a DJ testing their QR code landed on the guest page in it, with no address
    // bar and no "back" (the owner, 2026-10-08). "← Twój panel DJ-a" is there for the app only — a browser has its own way back.
    S2P.scenario({
        name: 'guest-back-to-dashboard-in-the-app',
        title: 'the guest page: "← Twój panel DJ-a" hidden in a browser, shown by the stylesheet in the installed app (display-mode: standalone)',
        page: 'guest',
        viewport: '390,844',
        run: async function (t) {
            const link = document.getElementById('backToDashboard');
            t.step('there, leading to the dashboard', [!!link, link && link.getAttribute('href')], [true, '/dj/dashboard']);
            t.step('hidden in a browser', link.checkVisibility(), false);
            // headless Chrome cannot be an installed app: the rule that shows it there, read from the real stylesheet
            const rules = [];
            Array.from(document.styleSheets).forEach(function (sheet) {
                let list = [];
                try { list = Array.from(sheet.cssRules); } catch (e) { /* another origin's sheet */ }
                list.forEach(function (rule) {
                    if (rule.media && /display-mode:\s*standalone/.test(rule.media.mediaText)) {
                        Array.from(rule.cssRules).forEach(function (inner) {
                            if (inner.selectorText === '.s2p-standalone-only') rules.push(inner.style.display);
                        });
                    }
                });
            });
            t.step('shown in the app (the stylesheet\'s rule for display-mode: standalone)', rules, ['block']);
        }
    });

    S2P.scenario({
        name: 'guest-more-and-search',
        title: 'the guest page: the rest of the list is fetched only when unfolded; the search filters the list (no accents needed), says when nothing matches, and goes with folding',
        page: 'guest-many',
        viewport: '390,844',
        run: async function (t) {
            t.step('one list: no "Najwięcej głosów", no "Ostatnio wysłane"; the rest neither in the page nor asked for',
                [!!document.getElementById('mostWanted'), !!document.getElementById('upNext'), !!rowOf('Older 5'),
                    await t.stand.count('GET /p/ABC12/queue/more')], [false, false, false, 0]);
            await unfold(t);
            t.step('unfolded: the rest asked for once, and there', [await t.stand.count('GET /p/ABC12/queue/more'), visibleSongs()],
                [1, ['Top song', 'Recent 1', 'Recent 2', 'Recent 3', 'Recent 4', 'Older 5', 'Wilki - Baśka']]);

            const search = document.getElementById('requestSearch');
            const type = function (text) { search.value = text; search.dispatchEvent(new Event('input', { bubbles: true })); };
            type('baska wilki');
            t.step('"baska wilki" finds "Wilki - Baśka" (any order of the words, no accents)', visibleSongs(), ['Wilki - Baśka']);
            t.step('…and says nothing about "no such request"', document.getElementById('requestSearchNone').checkVisibility(), false);
            type('zzz');
            t.step('nothing matches: no song, "Nie ma takiej prośby"', [visibleSongs(),
                /Nie ma takiej prośby/.test(document.getElementById('requestSearchNone').innerText)], [[], true]);

            document.querySelector('#moreRequests summary').click();
            await t.sleep(50);
            t.step('folded: the search goes, the five shown again', [search.value, visibleSongs()],
                ['', ['Top song', 'Recent 1', 'Recent 2', 'Recent 3', 'Recent 4']]);
        }
    });

    S2P.scenario({
        name: 'guest-vote',
        title: 'the guest page: a 👍 goes in the background — once, however fast the taps — and comes back given, with nothing on the screen moving',
        page: 'guest-many',
        viewport: '390,844',
        setup: { delays: { '/p/ABC12/vote': 0.4 } },
        run: async function (t) {
            await unfold(t);
            const before = rowOf('Recent 4').querySelector('.s2p-vote-btn');
            t.step('"Recent 4": the 👍 not given yet, with its count', [before.getAttribute('aria-pressed'), before.textContent.trim()],
                ['false', '👍 1']);
            const where = layout();
            before.click();
            before.click();   // a double tap while the answer is on its way
            await t.waitFor(function () { return rowOf('Recent 4').querySelector('.s2p-vote-btn') !== before; }, 'the 👍 comes back', 5000);

            const sent = await t.stand.requests('POST /p/ABC12/vote');
            t.step('one vote sent, in the background, for that song',
                sent.map(function (r) { return [r.q.id, r.q.on, r.q['X-Requested-With']]; }), [['4', 'true', 'fetch']]);
            const after = rowOf('Recent 4').querySelector('.s2p-vote-btn');
            t.step('the 👍 given, the new count', [after.getAttribute('aria-pressed'), after.textContent.trim()], ['true', '👍 2']);
            t.step('nothing moved: every song where it was, the page scrolled as it was', layout(), where);
            t.step('the rest still unfolded', document.getElementById('moreRequests').open, true);
            t.check('the page stayed (no navigation)', location.pathname === '/dj/dashboard');
        }
    });

    S2P.scenario({
        name: 'guest-vote-song-gone',
        title: 'the guest page: a 👍 on a song the DJ played meanwhile — the song stays in its place, dimmed, its 👍 off; the note floats over the page and goes',
        page: 'guest-many',
        viewport: '390,844',
        setup: { voteAnswer: 'guest-vote-gone' },
        run: async function (t) {
            await unfold(t);
            const row = rowOf('Wilki - Baśka');
            const where = layout();
            row.querySelector('.s2p-vote-btn').click();
            await t.waitFor(function () { return document.getElementById('voteNote'); }, 'the note', 5000);

            t.step('the song stays, dimmed, its 👍 off', [rowOf('Wilki - Baśka') === row, row.classList.contains('s2p-song-gone'),
                row.querySelector('.s2p-vote-btn').disabled], [true, true, true]);
            t.step('the other songs untouched', document.querySelectorAll('#guestQueueBox .s2p-song-gone').length, 1);
            const note = document.getElementById('voteNote');
            t.step('the note says it, over the page (not in the list)', [/nie ma już w kolejce/.test(note.textContent),
                getComputedStyle(note).position, document.getElementById('guestQueueBox').contains(note)], [true, 'fixed', false]);
            t.step('nothing moved', layout(), where);
            await t.waitFor(function () { return !document.getElementById('voteNote'); }, 'the note goes by itself', 6000);
            t.check('the note went by itself', true);
        }
    });
})();
