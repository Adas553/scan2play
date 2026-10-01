// THE LISTS of the dashboard: the active queue (a search box, a count, a column sort, a "nothing matches" row, all of it kept across the
// poll that replaces the rows every 3 s) and the History tab (filters and "Show more" asked of the server, the search box working on what
// was loaded). dashboard.js: initListTools, initTableSorting, initTabs (fetchHistory, reloadHistory); PROJECT_CONTEXT.md Section 5.4, "Long lists".
//
// The queue's rows are the stand-in's answer to the poll (config "queue"), the History tab is the REAL fragment rendered by
// DashboardPageRenderTest through the real controller: ten entries on one timeline, a page of four, one file per filter.
(function () {
    const names = function (rows) { return Array.from(rows).map(function (row) { return row.getAttribute('data-song-name'); }); };
    const visibleNames = function (list) { return names(list.querySelectorAll('tbody tr[data-song-name]:not(.d-none)')); };
    const count = function (list) { return list.querySelector('[data-list-count]').textContent; };
    const nomatchShown = function (list) { return !list.querySelector('tr[data-nomatch]').classList.contains('d-none'); };
    const type = function (input, text) {
        input.value = text;
        input.dispatchEvent(new Event('input', { bubbles: true }));
    };
    const song = function (id, name) { return { id: id, name: name, url: 'https://www.youtube.com/watch?v=abcdefgh' + String(id).padStart(3, '0') }; };

    S2P.scenario({
        name: 'queue-list',
        title: 'the active queue: the search finds "Żółć" by "zolc", the count says "shown / all", "nothing matches" appears — and the poll keeps the search, the scroll position and the column sort',
        setup: {},
        run: async function (t) {
            const list = document.getElementById('queueList');
            const search = list.querySelector('[data-list-search]');
            const box = list.querySelector('.list-scroll');
            const rows = function () { return list.querySelectorAll('#song-list tr[data-song-id]'); };
            const zolc = ['Żółć — piosenka', 'Zolc Demo'];
            const songs = Array.from({ length: 60 }, function (_, i) {
                return song(200 + i, i === 7 ? zolc[0] : (i === 8 ? zolc[1] : 'Song ' + String(i).padStart(2, '0')));
            });
            async function queueBecomes(queue, label) {
                await t.stand.config({ queue: queue });
                await t.waitFor(function () { return rows().length === queue.length; }, label, 8000);   // the poll comes every 3 s
            }

            await queueBecomes(songs, 'the 60 songs of the queue');
            t.step('the count says how many songs are in the queue', count(list), '60');
            t.step('no filter yet: everything is shown, the "nothing matches" row is not', [visibleNames(list).length, nomatchShown(list)], [60, false]);

            type(search, 'zolc');
            t.step('"zolc" finds "Żółć — piosenka" (accents and ł do not matter) and "Zolc Demo"', visibleNames(list), zolc);
            t.step('the count says "shown / all"', count(list), '2 / 60');

            type(search, 'qqq');
            t.step('a text that nothing contains: no row, and the "nothing matches" row instead', [visibleNames(list).length, nomatchShown(list), count(list)], [0, true, '0 / 60']);

            type(search, 'SONG 1');
            t.step('upper case does not matter: "SONG 1" finds Song 10 … Song 19', visibleNames(list), Array.from({ length: 10 }, function (_, i) { return 'Song 1' + i; }));

            // the poll replaces the rows: the search must be applied to the new ones (it is a text in a box, the rows are new)
            type(search, 'zolc');
            await queueBecomes(songs.concat([song(300, 'Żółć bis')]), 'the poll to bring 61 songs');
            t.step('after a poll the search is still applied to the new rows', visibleNames(list), zolc.concat(['Żółć bis']));
            t.step('… and the count follows', count(list), '3 / 61');

            // the scroll position of the list survives a poll (the scroll box stays, only the rows are replaced)
            type(search, '');
            t.check('the list overflows its box and scrolls (60 rows in a box of fixed height)', box.scrollHeight > box.clientHeight + 300);
            box.scrollTop = 400;
            const before = box.scrollTop;
            await queueBecomes(songs.concat([song(300, 'Żółć bis'), song(301, 'Zulu last')]), 'the poll to bring 62 songs');
            t.step('after a poll the list is scrolled to where the DJ left it', box.scrollTop, before);
            t.check('(and that was not the top)', before > 0);

            // the column sort: ascending, descending — and it stays after a poll (reapplySort). The songs that begin with Z (and Ż) sort last.
            const songHeader = list.querySelector('th[data-sort="song"]');
            const nameAt = function (i) { return rows()[i].getAttribute('data-song-name'); };
            const firstName = function () { return nameAt(0); };
            const beginsWithZ = function (name) { return /^[ZŻ]/i.test(name); };
            t.step('in the order the server gave, the first song is Song 00', firstName(), 'Song 00');
            songHeader.click();
            t.step('a click on "Song" sorts ascending: Song 00 first, a song of the Z group last', [firstName(), beginsWithZ(nameAt(rows().length - 1))], ['Song 00', true]);
            songHeader.click();
            t.step('a second click sorts descending: a song of the Z group first, Song 00 last', [beginsWithZ(firstName()), nameAt(rows().length - 1)], [true, 'Song 00']);
            await queueBecomes(songs.concat([song(300, 'Żółć bis'), song(301, 'Zulu last'), song(302, 'Zulu more')]), 'the poll to bring 63 songs');
            // (Not "Zulu more first": how ż sorts against z depends on the language of the browser — Polish puts it after z, English does not)
            t.step('after a poll the descending sort is applied to the new rows: the five songs of the Z group, the new one among them, come first; Song 00 is last',
                [Array.from({ length: 5 }, function (_, i) { return beginsWithZ(nameAt(i)); }), names(Array.from(rows()).slice(0, 5)).indexOf('Zulu more') >= 0, nameAt(rows().length - 1)], [[true, true, true, true, true], true, 'Song 00']);
            t.step('and the header still shows it', songHeader.classList.contains('sort-desc'), true);
        }
    });

    // ---- the History tab ----
    S2P.scenario({
        name: 'history-tab',
        title: 'the History tab: the real fragment replaces the queue, its filter buttons and "Show more" ask the server (keeping the search text), and a failed request puts the button back',
        setup: {},
        run: async function (t) {
            const tab = document.querySelector('[data-dj-tab="history"]');
            const box = document.getElementById('history-content');
            const list = function () { return box.querySelector('[data-list]'); };
            const fetches = function () { return t.stand.requests('GET /dj/history-view/fragment'); };
            const litFilter = function () { return Array.from(list().querySelectorAll('[data-list-filter].active')).map(function (b) { return b.getAttribute('data-list-filter'); }); };
            const titles = function () { return names(list().querySelectorAll('tbody tr[data-song-name]')); };
            const waitForList = function (predicate, label) { return t.waitFor(function () { return list() && predicate(); }, label, 5000); };

            tab.click();
            await waitForList(function () { return true; }, 'the history to arrive');
            t.step('the History tab asked for the first page of the whole history (no limit, no filter)', (await fetches()).map(function (r) { return [r.q.partyCode, r.q.limit || null, r.q.filter || null]; }), [['HARN1', null, null]]);
            t.step('the history shows in place of the queue', [getComputedStyle(box).display !== 'none', getComputedStyle(document.getElementById('queue-content')).display !== 'none'], [true, false]);
            t.step('the History tab is the lit one', [tab.classList.contains('active'), tab.getAttribute('aria-current')], [true, 'page']);
            t.check('the list arrives with a heading of its own ("Historia imprezy")', /Historia imprezy/.test(list().querySelector('h4') ? list().querySelector('h4').textContent : ''));
            t.step('the newest four entries, the count says there are older ones ("4+")', [titles(), count(list())], [['Żółć — piosenka', 'Playlist Alpha', 'Rejected Beat', 'Playlist Bravo'], '4+']);
            t.step('the filter "all" is lit; a button offers more', [litFilter(), list().querySelector('[data-history-more]').getAttribute('data-limit')], [['all'], '100']);

            // the search works on what is loaded
            type(list().querySelector('[data-list-search]'), 'alpha');
            t.step('the search box narrows the loaded rows; the count keeps the "+" of older ones', [visibleNames(list()), count(list())], [['Playlist Alpha'], '1 / 4+']);

            // "Show more": a longer history of the same kind; the search text stays
            list().querySelector('[data-history-more]').click();
            await waitForList(function () { return titles().length === 10; }, 'the longer history');
            t.step('"Show more" asked for the limit that the button carries, and no filter (the chosen one is "all")', (await fetches()).slice(1).map(function (r) { return [r.q.limit, r.q.filter || null]; }), [['100', null]]);
            t.step('ten entries now, and no more button (nothing older)', [titles().length, list().querySelector('[data-history-more]')], [10, null]);
            t.step('the search text is still there and applied: one row, "1 / 10"', [list().querySelector('[data-list-search]').value, visibleNames(list()), count(list())], ['alpha', ['Playlist Alpha'], '1 / 10']);

            // a filter button: the server reads entries of that kind; the button lights at once; the search text stays
            type(list().querySelector('[data-list-search]'), 'beat');
            list().querySelector('[data-list-filter="rejected"]').click();
            t.step('the button of the filter is lit at once', litFilter(), ['rejected']);
            await waitForList(function () { return titles().length === 3; }, 'the rejected entries');
            t.step('the filter was asked of the server (first page of the kind: no limit)', (await fetches()).slice(2).map(function (r) { return [r.q.limit || null, r.q.filter]; }), [[null, 'rejected']]);
            t.step('only the rejected requests are listed', titles(), ['Rejected Beat', 'Rejected Delta', 'Rejected Golf']);
            t.step('the button that the server rendered is the lit one, and the search text was kept and applied', [litFilter(), list().querySelector('[data-list-search]').value, visibleNames(list()), count(list())], [['rejected'], 'beat', ['Rejected Beat'], '1 / 3']);

            // the sort of the History tab works on rows that arrived by AJAX
            type(list().querySelector('[data-list-search]'), '');
            const songHeader = list().querySelector('th[data-sort="song"]');
            songHeader.click();
            songHeader.click();
            t.step('a click on "Song" twice sorts the loaded history descending (the handlers were attached after the AJAX load)', titles()[0], 'Rejected Golf');

            // "Show more" of a list that a filter has narrowed: the filter goes with it (the server reads that kind, further back)
            list().querySelector('[data-list-filter="guest"]').click();
            await waitForList(function () { return litFilter()[0] === 'guest' && titles().length === 4; }, 'the guests\' entries');
            t.step('the "Guests" filter: the first four entries of that kind, and the button offers more', [titles(), list().querySelector('[data-history-more]').getAttribute('data-limit')],
                [['Żółć — piosenka', 'Rejected Beat', 'Guest Charlie', 'Rejected Delta'], '100']);
            list().querySelector('[data-history-more]').click();
            await waitForList(function () { return titles().length === 6; }, 'the longer list of guests\' entries');
            t.step('"Show more" asked for the chosen filter as well as the longer limit', (await fetches()).slice(-1).map(function (r) { return [r.q.limit, r.q.filter]; }), [['100', 'guest']]);
            t.step('and the filter is still the lit one', litFilter(), ['guest']);

            // a failed request: the button goes back to the one that was lit, the list stays
            await t.stand.config({ historyStatus: 500 });
            list().querySelector('[data-list-filter="played"]').click();
            t.step('the button of the new filter lights at once, before the answer', litFilter(), ['played']);
            await t.waitFor(function () { return litFilter()[0] === 'guest'; }, 'the button to go back', 5000).catch(function () { /* the step says what it was */ });
            t.step('the server failed: the previous button is the lit one again and the list is the one that was there', [litFilter(), titles().length], [['guest'], 6]);
        }
    });
})();
