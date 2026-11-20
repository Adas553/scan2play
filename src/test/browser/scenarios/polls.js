// "Pytanie do gości" (V74). The guest's page: the DJ's question above the form, an answer in the background (guest-poll.js) — the card
// comes back with the results, the page does not load again, so a song typed in the field stays —, a double tap sends one answer, a
// failed one says so and can be tapped again, and the card is fetched again when the guest comes back to the page (a question asked or
// ended meanwhile). The DJ's page: the results of the question asked fetched again every few seconds (polls.js). The TV: the question
// and its bars on one screen.
(function () {
    /** Nothing wider than the screen: measured on the document, not window.innerWidth (CLAUDE.md, "Mind" 2). */
    const fits = function () {
        return document.documentElement.scrollWidth <= document.documentElement.clientWidth;
    };
    const answerButton = function (text) {
        return Array.from(document.querySelectorAll('#guestPoll button[name="choice"]')).find(function (b) {
            return b.textContent.trim() === text;
        });
    };

    S2P.scenario({
        name: 'guest-poll-answer',
        title: 'the guest\'s answer goes in the background: the card comes back with the results and their answer marked, the typed song stays',
        page: 'guest-poll',
        viewport: '390,844',
        run: async function (t) {
            const field = document.getElementById('songInput');
            field.value = 'Wilki - Baśka';
            t.check('no results before the answer', !document.querySelector('#guestPoll .s2p-poll-bar'));
            answerButton('Tylko jedna').click();
            await t.waitFor(function () { return document.querySelector('#guestPoll .s2p-poll-bar'); }, 'the results', 5000);
            const sent = await t.stand.requests('POST /p/ABC12/poll/5');
            t.step('one answer sent, in the background, with its choice and the form\'s token',
                sent.map(function (r) { return [r.q.choice, r.q['X-Requested-With'], !!r.q._csrf]; }), [['2', 'fetch', true]]);
            t.check('the guest\'s answer marked', document.querySelector('#guestPoll .s2p-poll-mine').textContent.indexOf('Tylko jedna') >= 0);
            t.check('unfolded right after the answer: the results seen', document.getElementById('pollFold').open
                && document.querySelector('#guestPoll .s2p-poll-bar').getBoundingClientRect().height > 0);
            t.check('the focus on the card\'s line, not lost with the tapped button',
                document.activeElement === document.querySelector('#pollFold > summary'));
            t.check('the same live region (put in place, not a new section)', document.querySelectorAll('#guestPoll').length === 1);
            t.step('the typed song stayed (no page load)', [field.value, document.getElementById('songInput') === field], ['Wilki - Baśka', true]);
            t.check('nothing sideways', fits());
        }
    });

    S2P.scenario({
        name: 'guest-poll-double-tap',
        title: 'a double tap on an answer sends it once: the buttons wait for the answer',
        page: 'guest-poll',
        run: async function (t) {
            const button = answerButton('Tak');
            button.click();
            button.click();
            answerButton('Nie').click();
            await t.waitFor(function () { return document.querySelector('#guestPoll .s2p-poll-bar'); }, 'the results', 5000);
            await t.sleep(200);
            t.step('one answer sent', (await t.stand.requests('POST /p/ABC12/poll/5')).map(function (r) { return r.q.choice; }), ['0']);
        }
    });

    S2P.scenario({
        name: 'guest-poll-failed',
        title: 'an answer that did not go through: the card stays, its buttons work again, a line says so',
        page: 'guest-poll',
        setup: { postStatus: { '/p/ABC12/poll/5': 500 } },
        run: async function (t) {
            answerButton('Nie').click();
            await t.waitFor(function () { return document.getElementById('pollNote'); }, 'the note', 5000);
            t.check('the note says it did not go through', document.getElementById('pollNote').textContent.indexOf('Nie udało się') === 0);
            t.step('the buttons work again', Array.from(document.querySelectorAll('#guestPoll button[name="choice"]'))
                .map(function (b) { return b.disabled; }), [false, false, false]);
            t.check('no results', !document.querySelector('#guestPoll .s2p-poll-bar'));
        }
    });

    S2P.scenario({
        name: 'guest-poll-comes-back',
        title: 'back on the page, the guest sees the card as it is now: the question ended meanwhile, with its results',
        page: 'guest-poll',
        run: async function (t) {
            t.check('asked at first', !!answerButton('Tak'));
            document.dispatchEvent(new Event('visibilitychange'));
            await t.waitFor(function () { return document.querySelector('#guestPoll .s2p-poll-bar'); }, 'the card fetched again', 5000);
            t.step('fetched once', await t.stand.count('GET /p/ABC12/poll'), 1);
            t.check('the results, no answers to tap', !document.querySelector('#guestPoll form'));
            document.dispatchEvent(new Event('visibilitychange'));
            await t.sleep(300);
            t.step('a quick switch back asks no more', await t.stand.count('GET /p/ABC12/poll'), 1);
        }
    });

    /** The question on a phone: above the request field, its answers tappable, nothing sideways. */
    const phone = async function (t) {
        const card = document.getElementById('guestPoll');
        t.check('nothing sideways', fits());
        t.check('above the request form', card.getBoundingClientRect().bottom <= document.getElementById('requestForm').getBoundingClientRect().top);
        const buttons = Array.from(card.querySelectorAll('button[name="choice"]'));
        t.step('every answer at least 44 px high and inside the screen', buttons.filter(function (b) {
            const box = b.getBoundingClientRect();
            return box.height < 44 || box.left < 0 || box.right > document.documentElement.clientWidth;
        }).length, 0);
        t.check('the first answer on the first screen', buttons[0].getBoundingClientRect().bottom <= window.innerHeight);
    };

    S2P.scenario({ name: 'guest-poll-phone-390', title: 'the question on a phone (390 × 844): above the field, tappable, nothing sideways',
        page: 'guest-poll', viewport: '390,844', run: phone });
    S2P.scenario({ name: 'guest-poll-phone-320', title: 'the question on a small phone (320 × 640): the same',
        page: 'guest-poll', viewport: '320,640', run: phone });

    S2P.scenario({
        name: 'guest-poll-results-phone-320',
        title: 'the results on a small phone (320 × 640): every bar inside the screen, the shares beside the answers, nothing sideways',
        page: 'guest-poll-answered',
        viewport: '320,640',
        run: async function (t) {
            t.check('nothing sideways', fits());
            const fold = document.getElementById('pollFold');
            t.check('answered, the card is one line: folded', !fold.open);
            t.check('folded, the request field is on the first screen',
                document.getElementById('songInput').getBoundingClientRect().bottom <= window.innerHeight);
            const line = fold.querySelector('summary');
            t.check('its line at least 44 px high, with the guest\'s answer', line.getBoundingClientRect().height >= 44
                && line.textContent.indexOf('Twoja: Nie') >= 0);
            line.click();
            t.check('a tap unfolds it', fold.open);
            const bars = Array.from(document.querySelectorAll('#guestPoll .s2p-poll-bar'));
            t.step('a bar for each answer, inside the screen', [bars.length, bars.filter(function (b) {
                return b.getBoundingClientRect().right > document.documentElement.clientWidth;
            }).length], [3, 0]);
            t.check('the bar is drawn (not a hairline)', bars[0].getBoundingClientRect().height >= 6);
        }
    });

    S2P.scenario({
        name: 'guest-poll-closed-party',
        title: 'the DJ closed the requests to ask alone: the question on the page "nie przyjmuje", and an answer goes in the background',
        page: 'guest-poll-ended',
        viewport: '390,844',
        setup: { pollAnswer: 'guest-poll-answer' },
        run: async function (t) {
            t.check('nothing sideways', fits());
            t.check('the question above the farewell', document.getElementById('guestPoll').getBoundingClientRect().bottom
                <= document.getElementById('partyEndedTitle').getBoundingClientRect().top);
            const button = Array.from(document.querySelectorAll('#guestPoll button[name="choice"]'))[0];
            button.click();
            await t.waitFor(function () { return document.querySelector('#guestPoll .s2p-poll-bar'); }, 'the results', 5000);
            t.step('sent once, in the background', (await t.stand.requests('POST /p/END12/poll/6')).map(function (r) {
                return r.q['X-Requested-With'];
            }), ['fetch']);
        }
    });

    // The DJ keeps the results for a game's end (V75): the guest sees their answer and "Wyniki pokaże DJ"; "↻ Sprawdź wyniki" asks
    // again at once — and the results are there once the DJ showed them.
    S2P.scenario({
        name: 'guest-poll-hidden-check',
        title: 'results kept for later: "↻ Sprawdź wyniki" fetches the card at once and shows the results the DJ showed meanwhile',
        page: 'guest-poll-hidden',
        viewport: '390,844',
        run: async function (t) {
            t.check('their answer and "Wyniki pokaże DJ", no bars', !!document.getElementById('pollHidden')
                && !!document.getElementById('pollMineHidden') && !document.querySelector('#guestPoll .s2p-poll-bar'));
            document.querySelector('#pollFold > summary').click();
            const check = document.querySelector('#guestPoll [data-poll-refresh]');
            t.check('"↻ Sprawdź wyniki" tappable', check.getBoundingClientRect().height >= 44);
            check.click();
            await t.waitFor(function () { return document.querySelector('#guestPoll .s2p-poll-bar'); }, 'the results', 5000);
            t.step('asked once', await t.stand.count('GET /p/ABC12/poll'), 1);
            t.check('the card stays unfolded as the guest left it', document.getElementById('pollFold').open);
            t.check('nothing sideways', fits());
        }
    });

    S2P.scenario({
        name: 'polls-live-results',
        title: 'the DJ\'s page: the results of the question asked are fetched again and put in place — the new numbers, nothing else moves',
        page: 'polls',
        run: async function (t) {
            const live = document.querySelector('[data-poll-live]');
            t.check('one question live', document.querySelectorAll('[data-poll-live]').length === 1);
            t.check('its first numbers', live.textContent.indexOf('Głosy: 12') >= 0);
            const form = document.getElementById('pollNewForm');
            await t.stand.config({ pollResults: 'polls-results-next' });
            await t.waitFor(function () { return live.textContent.indexOf('Głosy: 20') >= 0; }, 'the new results', 8000);
            t.check('the leader moved to "Nie"', live.querySelector('.s2p-poll-lead').textContent.indexOf('Nie') >= 0);
            t.check('the form stayed (no page load)', document.getElementById('pollNewForm') === form);
            t.check('asked for the question asked', (await t.stand.requests('GET /dj/polls/3/results')).length >= 1);
        }
    });

    S2P.scenario({
        name: 'polls-shares-readable',
        title: 'the DJ\'s page: every share is readable — its colour is not the card\'s background',
        page: 'polls',
        viewport: '390,844',
        run: async function (t) {
            const shares = Array.from(document.querySelectorAll('.s2p-poll-card .s2p-poll-share .fw-bold'));
            const background = function (el) {
                for (let node = el; node; node = node.parentElement) {
                    const color = getComputedStyle(node).backgroundColor;
                    if (color !== 'rgba(0, 0, 0, 0)' && color !== 'transparent') return color;
                }
                return getComputedStyle(document.body).backgroundColor;
            };
            t.check('shares on the page', shares.length >= 2);
            t.step('a share drawn in its card\'s background colour', shares.filter(function (s) {
                return getComputedStyle(s).color === background(s);
            }).length, 0);
        }
    });

    // "Edytuj" / "Popraw jako nowe": the form opens in the question's own card — no page load, nothing on the screen moves above it;
    // "Anuluj" folds it back, the typed words as they were.
    S2P.scenario({
        name: 'polls-edit-in-place',
        title: 'the DJ\'s page: "Edytuj" opens the form in the card, the screen does not jump; "Anuluj" folds it back',
        page: 'polls',
        viewport: '390,844',
        run: async function (t) {
            const card = document.querySelector('.s2p-poll-card[data-poll-id="2"]');
            card.scrollIntoView({ block: 'center', behavior: 'instant' });
            await t.sleep(100);
            const before = [window.scrollY, Math.round(card.getBoundingClientRect().top)];
            const edit = Array.from(card.querySelectorAll('summary, a, button')).find(function (el) {
                return el.textContent.trim() === 'Edytuj';
            });
            const marker = {};
            window.__pollsPage = marker;
            edit.click();
            await t.sleep(400);
            t.check('the same page (not loaded again)', window.__pollsPage === marker);
            t.step('nothing moved: the scroll and the card where they were',
                [window.scrollY, Math.round(card.getBoundingClientRect().top)], before);
            const form = card.querySelector('form[action="/dj/polls/2/edit"]');
            t.check('the form in the card, filled with its words', !!form && form.querySelector('[name="question"]').value
                === 'Kto pierwszy na parkiet?' && form.getBoundingClientRect().height > 0);
            const question = form.querySelector('[name="question"]');
            question.value = 'Coś innego?';
            Array.from(form.querySelectorAll('button')).find(function (b) { return b.textContent.trim() === 'Anuluj'; }).click();
            await t.sleep(100);
            t.check('"Anuluj" folds it back', !form.closest('details').open);
            t.check('the focus on "Edytuj"', document.activeElement === form.closest('details').querySelector('summary'));
            t.step('the words as they were', question.value, 'Kto pierwszy na parkiet?');
            t.check('nothing sideways', fits());
        }
    });

    S2P.scenario({
        name: 'polls-copy-in-place',
        title: 'the DJ\'s page: "Popraw jako nowe" on the question asked opens a new question\'s form with its words, in the card',
        page: 'polls',
        viewport: '390,844',
        run: async function (t) {
            const card = document.querySelector('.s2p-poll-card[data-poll-id="3"]');
            card.scrollIntoView({ block: 'center', behavior: 'instant' });
            await t.sleep(100);
            const before = window.scrollY;
            Array.from(card.querySelectorAll('summary, a, button')).find(function (el) {
                return el.textContent.trim() === 'Popraw jako nowe';
            }).click();
            await t.sleep(400);
            t.step('nothing moved', window.scrollY, before);
            const form = card.querySelector('form[action="/dj/polls/create"]');
            t.check('a new question\'s form with its words', !!form && form.querySelector('[name="question"]').value
                === 'Disco polo — tak czy nie?' && form.getBoundingClientRect().height > 0);
            t.check('the page\'s own new question form left as it was',
                document.getElementById('pollQuestionInput').value === '');
        }
    });

    const pollsPhone = async function (t) {
        t.check('nothing sideways', fits());
        const buttons = Array.from(document.querySelectorAll('.s2p-poll-card button, #pollNewForm button'));
        t.step('every button inside the screen', buttons.filter(function (b) {
            return b.getBoundingClientRect().right > document.documentElement.clientWidth;
        }).length, 0);
    };
    S2P.scenario({ name: 'polls-page-phone-390', title: 'the DJ\'s page of questions on a phone (390 × 844): nothing sideways',
        page: 'polls', viewport: '390,844', run: pollsPhone });
    S2P.scenario({ name: 'polls-page-phone-320', title: 'the DJ\'s page of questions on a small phone (320 × 640): nothing sideways',
        page: 'polls', viewport: '320,640', run: pollsPhone });

    S2P.scenario({
        name: 'tv-poll',
        title: 'the TV with a question asked (1280 × 720): the question, the hint and every bar on the one screen, nothing to scroll',
        page: 'tv-poll',
        viewport: '1280,720',
        run: async function (t) {
            const list = document.getElementById('tvList');
            t.check('the question in place of the list', !!document.getElementById('tvPollQuestion') && !list.querySelector('.tv-songs'));
            const rows = Array.from(document.querySelectorAll('.tv-poll-row'));
            t.step('three answers, every one on the screen', [rows.length, rows.filter(function (r) {
                return r.getBoundingClientRect().bottom > window.innerHeight;
            }).length], [3, 0]);
            t.check('the total on the screen', document.getElementById('tvPollTotal').getBoundingClientRect().bottom <= window.innerHeight);
            t.check('nothing sideways', fits());
        }
    });

    S2P.scenario({
        name: 'tv-poll-longest',
        title: 'the TV (1280 × 720) with the longest question and four answers of the longest: all on the one screen, nothing sideways',
        page: 'tv-poll-long',
        viewport: '1280,720',
        run: async function (t) {
            const rows = Array.from(document.querySelectorAll('.tv-poll-row'));
            t.step('four answers, every one on the screen', [rows.length, rows.filter(function (r) {
                return r.getBoundingClientRect().bottom > window.innerHeight;
            }).length], [4, 0]);
            t.check('the total on the screen', document.getElementById('tvPollTotal').getBoundingClientRect().bottom <= window.innerHeight);
            t.check('nothing sideways', fits());
        }
    });
})();
