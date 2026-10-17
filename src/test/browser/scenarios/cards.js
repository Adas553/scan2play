// THE FOLDED CARDS OF A PHONE (the review of 2026-10-10, https://claude.ai/artifact/5Znq2bBpZQZB2rzHYkX9v1; cards.js, app.css): a
// waiting request shows its song, ⚠ / ⭐, the votes and "▶ Zagrane / ⏭ Pomiń" — 204 px became ~106; an entry of the history is one line
// with "↩" of a skip. A tap on the card unfolds the rest, never sends anything; a tap on a button is the button's alone. The cards
// that were open stay open when the poll (every 3 s) or "Show more" replaces the rows. A wide screen is the table as before.
(function () {
    const shows = function (el) { return !!el && el.getClientRects().length > 0 && getComputedStyle(el).visibility !== 'hidden'; };
    const height = function (el) { return Math.round(el.getBoundingClientRect().height); };
    const queueRow = function (id) { return document.querySelector('#song-list tr[data-song-id="' + id + '"]'); };
    /** Waits until the stand-in has had {@code path} (waitFor takes a plain condition, not a promise). */
    const posted = async function (t, path, label) {
        const started = performance.now();
        while ((await t.stand.count(path)) === 0) {
            if (performance.now() - started > 3000) { t.step('TIMEOUT waiting for ' + label, false, true); throw new Error(label); }
            await t.sleep(50);
        }
    };
    const sent = async function (t) {
        return (await t.stand.count('POST /dj/dashboard/play')) + (await t.stand.count('POST /dj/dashboard/dismiss'))
            + (await t.stand.count('POST /dj/dashboard/tip-count')) + (await t.stand.count('POST /dj/dashboard/restore'));
    };

    // The guests' dedications (V38, the owner's screenshot 2026-10-17: three of them filled half a phone's screen): a folded card shows
    // only "💌 4" beside the song; a tap unfolds them, each on a line of its own, with "+1 więcej". A wide screen shows them as before.
    S2P.scenario({
        name: 'queue-cards-dedications-folded',
        title: 'the queue on a phone: a card folds its dedications to "💌 4", a tap shows them, each on a line of its own',
        page: 'dashboard-cards',
        viewport: '390,844',
        setup: { updatesStatus: 304 },
        run: async function (t) {
            const row = queueRow(11);
            const lines = function () { return Array.from(row.querySelectorAll('.s2p-dedication')); };
            const count = row.querySelector('.s2p-dedication-count');
            t.step('folded: no dedication, "💌 4" beside the song',
                [lines().length, lines().filter(shows).length, shows(row.querySelector('.s2p-dedication-more')), shows(count),
                    count ? count.textContent.trim() : null], [3, 0, false, true, '💌 4']);
            row.querySelector('.song-title').click();
            const tops = lines().map(function (line) { return Math.round(line.getBoundingClientRect().top); });
            t.step('unfolded: the three, "+1 więcej", no "💌 4"',
                [lines().filter(shows).length, shows(row.querySelector('.s2p-dedication-more')), shows(count)], [3, true, false]);
            t.check('each on a line of its own (tops ' + tops.join(', ') + ')', tops[0] < tops[1] && tops[1] < tops[2]);
            t.check('nothing wider than the card', lines().every(function (line) {
                return line.getBoundingClientRect().right <= row.getBoundingClientRect().right + 1; }));
        }
    });

    S2P.scenario({
        name: 'queue-dedications-wide',
        title: 'the queue on a wide screen: the dedications under the song as before, no "💌 4"',
        page: 'dashboard-cards',
        setup: { updatesStatus: 304 },
        run: async function (t) {
            const row = queueRow(11);
            t.step('the three shown, "+1 więcej", no count',
                [Array.from(row.querySelectorAll('.s2p-dedication')).filter(shows).length, shows(row.querySelector('.s2p-dedication-more')),
                    shows(row.querySelector('.s2p-dedication-count'))], [3, true, false]);
        }
    });

    S2P.scenario({
        name: 'queue-cards-folded',
        title: 'the queue on a phone: a card folded to its song, ⚠ (with the guest\'s words) / ⭐, the votes, "Zagrane" / "Pomiń" and a counted "💸 2" — a tap unfolds the rest and sends nothing, a tap on "Pomiń" skips and unfolds nothing',
        page: 'dashboard-cards',
        viewport: '390,844',
        setup: { updatesStatus: 304 },   // the rendered rows stay: the poll answers "unchanged"
        run: async function (t) {
            const tipped = queueRow(11), check = queueRow(12), wanted = queueRow(13);
            const visible = function (row, selector) { return shows(row.querySelector(selector)); };
            t.step('folded: no guest\'s words, AI\'s comment or "Podejrzyj" (but the guest\'s words of a "⚠ Sprawdź")',
                [visible(tipped, '.guest-text'), visible(tipped, '.s2p-queue-comment'), visible(tipped, '.song-cell a.btn'),
                    visible(check, '.guest-text'), visible(check, '.s2p-check-song'), visible(wanted, '.s2p-host-wanted')],
                [false, false, false, true, true, true]);
            t.step('folded: "Zagrane" and "Pomiń" in every card, 💸 only where a tip is counted ("💸 2"), never "−1"',
                [[tipped, check, wanted].every(function (row) { return visible(row, '.s2p-btn-played') && visible(row, '.s2p-btn-skip'); }),
                    visible(tipped, '.s2p-btn-tip'), tipped.querySelector('.s2p-btn-tip').textContent.trim(), visible(tipped, '.s2p-btn-untip'),
                    visible(check, '.s2p-btn-tip')],
                [true, true, '💸 2', false, false]);
            // at a real 390 px (run.py) a long title may take two lines — in a wider font (Linux's DejaVu Sans in CI) it does: the
            // card is measured without the title's further lines, which no folding takes away
            const folded = function (row) {
                const title = row.querySelector('.song-title');
                const line = parseFloat(getComputedStyle(title).lineHeight) || 24;
                return height(row) - Math.max(0, Math.round(title.getBoundingClientRect().height - line));
            };
            t.check('a plain card is low (' + folded(wanted) + ' px without its title\'s further lines; it was 204)',
                folded(tipped) <= 120 && folded(wanted) <= 140);
            // a phone's screen is 844 px (headless Chrome takes its own bars off the window, so innerHeight is less)
            t.check('the three cards are in a phone\'s first screen (the last ends at ' + Math.round(wanted.getBoundingClientRect().bottom)
                + ' of 844 px; they were 204 px each, two in it)', wanted.getBoundingClientRect().bottom <= 844);
            const toggle = check.querySelector('.s2p-card-toggle');
            t.step('its "▾": folded, 44 px high, named', [shows(toggle), toggle.getAttribute('aria-expanded'), height(toggle) >= 44,
                toggle.getAttribute('aria-label')], [true, 'false', true, 'Szczegóły']);

            check.querySelector('.song-title').click();
            t.step('a tap on the song unfolds the card: the AI\'s comment, "Podejrzyj", 💸 — and "▾" says so',
                [check.classList.contains('s2p-open'), visible(check, '.s2p-queue-comment'), visible(check, '.song-cell a.btn'),
                    visible(check, '.s2p-btn-tip'), toggle.getAttribute('aria-expanded')], [true, true, true, true, 'true']);
            const commentStyle = getComputedStyle(check.querySelector('.s2p-queue-comment'));
            t.step('the whole AI\'s comment, not cut to one line', [commentStyle.display, commentStyle.webkitLineClamp], ['block', 'none']);
            toggle.click();
            t.step('"▾" folds it again', [check.classList.contains('s2p-open'), toggle.getAttribute('aria-expanded')], [false, 'false']);
            check.click();
            check.click();
            await t.sleep(300);
            t.step('a tap on the card sends nothing', await sent(t), 0);

            tipped.querySelector('.s2p-btn-skip').click();
            await posted(t, 'POST /dj/dashboard/dismiss', 'the skip');
            t.step('a tap on "Pomiń" of a folded card skips it and unfolds nothing',
                [await t.stand.count('POST /dj/dashboard/dismiss'), tipped.classList.contains('s2p-open')], [1, false]);
            t.check('nothing wider than the screen', document.documentElement.scrollWidth <= window.innerWidth);
        }
    });

    S2P.scenario({
        name: 'queue-card-open-survives-poll',
        title: 'the queue on a phone: a card unfolded stays unfolded when the poll replaces the rows (the search too); a request gone and back is folded',
        viewport: '390,844',
        setup: { queue: [{ id: 1, name: 'Wilki - Baśka', url: 'https://www.youtube.com/results?search_query=Wilki', card: true },
                         { id: 2, name: 'sanah - Szampan', url: 'https://www.youtube.com/results?search_query=sanah', card: true }] },
        run: async function (t) {
            // the polled rows have no "Zagrane" (the rendered ones do): wait for them
            const polled = function (id) { const row = queueRow(id); return row && !row.querySelector('.s2p-btn-played') ? row : null; };
            await t.waitFor(function () { return polled(1); }, 'the first poll', 8000);
            polled(1).querySelector('.song-title').click();
            t.step('unfolded', polled(1).classList.contains('s2p-open'), true);

            const before = polled(1);
            await t.stand.config({ queue: [{ id: 1, name: 'Wilki - Baśka', url: 'https://www.youtube.com/results?search_query=Wilki', card: true },
                                           { id: 2, name: 'sanah - Szampan', url: 'https://www.youtube.com/results?search_query=sanah', card: true },
                                           { id: 3, name: 'Kombii - Pokolenie', url: 'https://www.youtube.com/results?search_query=Kombii', card: true }] });
            await t.waitFor(function () { return polled(3); }, 'a poll with a new request', 8000);
            t.check('the rows are new ones', polled(1) !== before);
            t.step('after the poll: the same card unfolded ("▾" says so), the others folded',
                [polled(1).classList.contains('s2p-open'), polled(1).querySelector('.s2p-card-toggle').getAttribute('aria-expanded'),
                    polled(2).classList.contains('s2p-open'), polled(3).classList.contains('s2p-open'), shows(polled(1).querySelector('.guest-text'))],
                [true, 'true', false, false, true]);

            const search = document.querySelector('#queueList [data-list-search]');
            search.value = 'wilki';
            search.dispatchEvent(new Event('input', { bubbles: true }));
            await t.stand.config({ queue: [{ id: 1, name: 'Wilki - Baśka', url: 'https://www.youtube.com/results?search_query=Wilki', card: true },
                                           { id: 3, name: 'Kombii - Pokolenie', url: 'https://www.youtube.com/results?search_query=Kombii', card: true }] });
            await t.waitFor(function () { return !queueRow(2); }, 'a poll without the 2nd', 8000);
            t.step('with a search: still unfolded, the search still applied',
                [polled(1).classList.contains('s2p-open'), shows(polled(1)), shows(polled(3))], [true, true, false]);
            search.value = '';
            search.dispatchEvent(new Event('input', { bubbles: true }));

            // played (gone from the list), then put back: it comes back folded
            await t.stand.config({ queue: [{ id: 3, name: 'Kombii - Pokolenie', url: 'https://www.youtube.com/results?search_query=Kombii', card: true }] });
            await t.waitFor(function () { return !queueRow(1); }, 'a poll without the 1st', 8000);
            await t.stand.config({ queue: [{ id: 1, name: 'Wilki - Baśka', url: 'https://www.youtube.com/results?search_query=Wilki', card: true },
                                           { id: 3, name: 'Kombii - Pokolenie', url: 'https://www.youtube.com/results?search_query=Kombii', card: true }] });
            await t.waitFor(function () { return polled(1); }, 'a poll with the 1st again', 8000);
            t.step('a request gone and put back comes back folded', polled(1).classList.contains('s2p-open'), false);
        }
    });

    S2P.scenario({
        name: 'queue-cards-viewer',
        title: 'the queue of the staff\'s "Podgląd" on a phone: no buttons, so a card is its song alone — no empty row under it',
        page: 'dashboard-cards-viewer',
        viewport: '390,844',
        setup: { updatesStatus: 304 },
        run: async function (t) {
            const rows = Array.from(document.querySelectorAll('#song-list tr[data-song-id]'));
            t.step('no "Zagrane", "Pomiń" or 💸', rows.some(function (row) { return row.querySelector('form'); }), false);
            t.check('a plain card is one line (' + height(rows[0]) + ' px)', height(rows[0]) <= 64);
            rows[0].querySelector('.song-title').click();
            t.step('a tap still unfolds the guest\'s words and the AI\'s comment',
                [shows(rows[0].querySelector('.guest-text')), shows(rows[0].querySelector('.s2p-queue-comment'))], [true, true]);
        }
    });

    S2P.scenario({
        name: 'queue-cards-wide-screen',
        title: 'the queue on a computer: the table as before — the guest\'s words, the AI\'s comment, "Podejrzyj", 💸 in every row, no "▾", a click on a row folds nothing',
        page: 'dashboard-cards',
        setup: { updatesStatus: 304 },
        run: async function (t) {
            t.step('the window is a computer\'s', window.innerWidth >= 768, true);
            const row = queueRow(12);
            const all = function () {
                return [shows(row.querySelector('.guest-text')), shows(row.querySelector('.s2p-queue-comment')),
                    shows(row.querySelector('.song-cell a.btn')), shows(row.querySelector('.s2p-btn-tip')), shows(row.querySelector('.s2p-card-toggle'))];
            };
            t.step('everything shows, no "▾"', all(), [true, true, true, true, false]);
            t.step('a table row', getComputedStyle(row).display, 'table-row');
            row.querySelector('.song-title').click();
            t.step('a click on the row changes nothing', [row.classList.contains('s2p-open')].concat(all()), [false, true, true, true, true, false]);
        }
    });

    S2P.scenario({
        name: 'history-lines-phone',
        title: 'the history on a phone: an entry is one line — the song, ⚠, the votes, the decision and "↩" of a skip (one tap, sends only that); a tap on the line unfolds the rest, and it stays unfolded after "Pokaż więcej"',
        viewport: '390,844',
        setup: {},
        run: async function (t) {
            document.querySelector('[data-dj-tab="history"]').click();
            await t.waitFor(function () { return document.querySelector('#history-content tr[data-decision]'); }, 'the history', 5000);
            const entry = function (name) { return document.querySelector('#history-content tr[data-song-name="' + name + '"]'); };
            const lines = Array.from(document.querySelectorAll('#history-content tr[data-decision]'));
            t.check('every entry one line, 44–60 px (' + lines.map(height).join(', ') + '; it was 191)',
                lines.every(function (row) { return height(row) >= 44 && height(row) <= 60; }));
            const skipped = entry('Rejected Beat');
            const restore = skipped.querySelector('form[action="/dj/dashboard/restore"] button');
            const box = restore.getBoundingClientRect();
            t.step('the skip\'s "↩" in its line: 44 × 44, inside the screen, its words kept for a screen reader',
                [shows(restore), Math.round(box.width) >= 44 && Math.round(box.height) >= 44, box.right <= window.innerWidth,
                    restore.textContent.trim()], [true, true, true, '↩ Przywróć']);
            t.step('folded: no AI\'s comment, guest\'s words or "Podejrzyj"; "⚠" on the song with none of the guest\'s words',
                [shows(skipped.querySelector('td.s2p-comment-cell')), shows(entry('Played Bravo').querySelector('.guest-text')),
                    shows(skipped.querySelector('td[data-sort-value="song"] a.btn')), shows(entry('Played Alpha').querySelector('.s2p-check-song'))],
                [false, false, false, true]);
            t.check('nothing wider than the screen', document.documentElement.scrollWidth <= window.innerWidth);
            // "Zagrane" by mistake (the owner, 2026-10-10): a played entry has its "↩" in the line too (a cleared one none: history-phone)
            t.step('"↩" also in the line of every played entry',
                [shows(entry('Played Bravo').querySelector('form[action="/dj/dashboard/restore"] button')),
                    entry('Żółć — piosenka').querySelectorAll('form[action="/dj/dashboard/restore"]').length], [true, 1]);

            restore.click();
            await posted(t, 'POST /dj/dashboard/restore', 'the restore');
            t.step('"↩" of a folded line sends the restore and unfolds nothing',
                [await sent(t), skipped.classList.contains('s2p-open')], [1, false]);
            await t.sleep(800);   // the restore reloads the history (s2p:history-changed)

            const line = entry('Rejected Beat');
            line.querySelector('td[data-sort-value="song"] .fw-bold').click();
            const comment = line.querySelector('td.s2p-comment-cell');
            t.step('a tap unfolds it: the AI\'s whole comment, "Podejrzyj", "↩ Przywróć" in words',
                [line.classList.contains('s2p-open'), shows(comment) && /parkiet/.test(comment.textContent),
                    shows(line.querySelector('td[data-sort-value="song"] a.btn')), line.querySelector('.s2p-card-toggle').getAttribute('aria-expanded')],
                [true, true, true, 'true']);
            const card = line.getBoundingClientRect(), song = line.querySelector('td[data-sort-value="song"]').getBoundingClientRect();
            t.check('unfolded, the song goes across the card', song.width >= card.width * 0.85);
            t.check('the restore\'s words back (' + Math.round(line.querySelector('form[action="/dj/dashboard/restore"] button').getBoundingClientRect().width) + ' px wide)',
                line.querySelector('form[action="/dj/dashboard/restore"] button').getBoundingClientRect().width > 60);
            t.step('a tap on the line sent nothing more', await sent(t), 1);

            document.querySelector('#history-content [data-history-more]').click();
            await t.waitFor(function () { return entry('Rejected Delta'); }, 'the older entries', 5000);
            t.step('after "Pokaż więcej" the same entry unfolded, the others folded',
                [entry('Rejected Beat').classList.contains('s2p-open'), entry('Played Alpha').classList.contains('s2p-open')], [true, false]);
        }
    });
})();
