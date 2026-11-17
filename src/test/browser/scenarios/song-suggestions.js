// THE GUEST'S SONG SUGGESTIONS (song-autocomplete.js): iTunes asked as the guest types. An answer that comes after the guest typed on
// is not the field's any more — it is not shown (review 2026-10-14: a slow answer for "ab" showed over the field that said
// something else). And the field is a combobox for a screen reader: it says whether the list is open and which suggestion is chosen.
(function () {
    /** iTunes as the test answers it: each asked query waits until the test lets it answer. */
    const holdITunes = function () {
        const pending = [];
        const original = window.fetch;
        window.fetch = function (resource) {
            const url = new URL(String(resource && resource.url ? resource.url : resource), location.href);
            if (url.hostname !== 'itunes.apple.com') return original.apply(this, arguments);   // the harness's own
            return new Promise(function (resolve) {
                pending.push({
                    term: url.searchParams.get('term'),
                    url: url,
                    answer: function (names, status) {
                        resolve(new Response(JSON.stringify({ results: names.map(function (n) {
                            // the artist, then the title (which may hold " - " too), then " @ " and the album, if any
                            const album = n.indexOf(' @ ');
                            const song = album >= 0 ? n.slice(0, album) : n;
                            const at = song.indexOf(' - ');
                            return { artistName: song.slice(0, at), trackName: song.slice(at + 3),
                                collectionName: album >= 0 ? n.slice(album + 3) : undefined };
                        }) }), { status: status || 200, headers: { 'Content-Type': 'application/json' } }));
                    }
                });
            });
        };
        return pending;
    };
    const type = function (input, text) {
        input.value = text;
        input.dispatchEvent(new Event('input', { bubbles: true }));
    };
    const open = function () {
        const list = document.querySelector('.song-autocomplete-dropdown');
        return !!list && list.classList.contains('is-open');
    };

    S2P.scenario({
        name: 'song-suggestions-late-answer',
        title: 'a late answer of iTunes for what the guest typed before is not shown: not after the field was cleared, not over a newer query served from memory',
        page: 'guest',
        run: async function (t) {
            const pending = holdITunes();
            const input = document.getElementById('songInput');
            input.focus();

            type(input, 'despacito');
            await t.waitFor(function () { return pending.length === 1; }, 'iTunes asked for "despacito"', 3000);
            pending[0].answer(['Luis Fonsi - Despacito']);
            await t.waitFor(open, 'the suggestions for "despacito"', 3000);

            type(input, 'despacit');
            await t.waitFor(function () { return pending.length === 2; }, 'iTunes asked for "despacit"', 3000);
            type(input, 'despacito');   // "despacito" again: from memory, at once — while "despacit" is still on its way
            await t.sleep(450);
            pending[1].answer(['Despacio - Something']);
            await t.sleep(200);
            t.step('the field says "despacito": its suggestions, not the late "despacit"',
                Array.prototype.map.call(document.querySelectorAll('.song-autocomplete-item'), function (i) { return i.dataset.label; }),
                ['Luis Fonsi – Despacito']);

            type(input, 'kult');
            await t.waitFor(function () { return pending.length === 3; }, 'iTunes asked for "kult"', 3000);
            type(input, '');
            await t.sleep(450);
            pending[2].answer(['Kult - Arahja']);
            await t.sleep(200);
            t.step('the field cleared meanwhile: no list', open(), false);
        }
    });

    S2P.scenario({
        name: 'song-suggestions-combobox',
        title: 'the song field is a combobox: it says whether its list is open, points at the list, and ↓ names the chosen suggestion',
        page: 'guest',
        run: async function (t) {
            const pending = holdITunes();
            const input = document.getElementById('songInput');
            const list = document.querySelector('.song-autocomplete-dropdown');
            t.step('a combobox of a list box, closed', [input.getAttribute('role'), input.getAttribute('aria-controls'),
                input.getAttribute('aria-expanded'), list.getAttribute('role')], ['combobox', list.id, 'false', 'listbox']);

            input.focus();
            type(input, 'abba');
            await t.waitFor(function () { return pending.length === 1; }, 'iTunes asked', 3000);
            pending[0].answer(['ABBA - Dancing Queen', 'ABBA - Mamma Mia']);
            await t.waitFor(open, 'the suggestions', 3000);
            t.step('open', input.getAttribute('aria-expanded'), 'true');

            input.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, cancelable: true }));
            const items = document.querySelectorAll('.song-autocomplete-item');
            t.step('↓: the first suggestion chosen, named by the field', [input.getAttribute('aria-activedescendant'),
                items[0].getAttribute('aria-selected'), items[1].getAttribute('aria-selected')], [items[0].id, 'true', 'false']);
            t.check('each suggestion has its own id', items[0].id && items[0].id !== items[1].id);

            input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
            t.step('Esc: closed, nothing chosen', [input.getAttribute('aria-expanded'), input.hasAttribute('aria-activedescendant')],
                ['false', false]);
        }
    });

    const shown = function () {
        return Array.prototype.map.call(document.querySelectorAll('.song-autocomplete-item'), function (i) { return i.dataset.label; });
    };
    const answered = function (pending, term, names) {
        pending.filter(function (p) { return p.term === term && !p.done; }).forEach(function (p) { p.done = true; p.answer(names); });
    };

    S2P.scenario({
        name: 'song-suggestions-word-begun',
        title: 'a word begun is enough: "shakira da" suggests "Dai Dai" — iTunes knows only whole words, so the songs of "shakira" are asked too and kept where a word starts with "da"; the next letters of that word ask nothing more for it',
        page: 'guest',
        run: async function (t) {
            const pending = holdITunes();
            const input = document.getElementById('songInput');
            input.focus();
            type(input, 'shakira da');
            await t.waitFor(function () { return pending.length === 2; }, 'both queries asked at once', 3000);
            t.step('"shakira da" and "shakira" (more of it), from the Polish store', pending.map(function (p) {
                return [p.term, p.url.searchParams.get('country')];
            }).sort(), [['shakira da', 'PL'], ['shakira', 'PL']]);   // as JS sorts the pairs' text
            answered(pending, 'shakira da', ['Shakira - Tú', 'Shakira - Me Enamoré']);
            answered(pending, 'shakira', ['Shakira - Waka Waka', 'Shakira & David Guetta - Worry', 'Shakira & Burna Boy - Dai Dai',
                'Shakira - Hips Don\'t Lie']);
            await t.waitFor(open, 'the suggestions', 3000);
            t.step('"Dai Dai" first (its title starts with "da"), then "David" (the artist), then iTunes\'s own', shown(),
                ['Shakira & Burna Boy – Dai Dai', 'Shakira & David Guetta – Worry', 'Shakira – Tú', 'Shakira – Me Enamoré']);

            type(input, 'shakira dai');
            await t.waitFor(function () { return pending.length === 3; }, '"shakira dai" asked', 3000);
            await t.sleep(200);
            t.step('…"shakira" from memory: only the new whole query asked', pending.map(function (p) { return p.term; }).sort(),
                ['shakira', 'shakira da', 'shakira dai']);
            answered(pending, 'shakira dai', ['Shakira & Burna Boy - Dai Dai']);
            await t.waitFor(function () { return shown().length === 1; }, 'the suggestions for "shakira dai"', 3000);
            t.step('the same song once, not twice', shown(), ['Shakira & Burna Boy – Dai Dai']);
        }
    });

    S2P.scenario({
        name: 'song-suggestions-once-each',
        title: 'a song iTunes gives several times (albums, singles) is suggested once',
        page: 'guest',
        run: async function (t) {
            const pending = holdITunes();
            const input = document.getElementById('songInput');
            input.focus();
            type(input, 'dai dai');
            await t.waitFor(function () { return pending.length === 2; }, 'iTunes asked', 3000);
            answered(pending, 'dai dai', ['Shakira & Burna Boy - Dai Dai', 'Music Flow Vibes - Dai Dai - Shakira (Afro Latin House Mix)',
                'Shakira & Burna Boy - Dai Dai', 'Shakira & Burna Boy - Dai  Dai']);
            answered(pending, 'dai', []);
            await t.waitFor(open, 'the suggestions', 3000);
            t.step('each song once', shown(), ['Shakira & Burna Boy – Dai Dai', 'Music Flow Vibes – Dai Dai - Shakira (Afro Latin House Mix)']);
        }
    });

    S2P.scenario({
        name: 'song-suggestions-versions',
        title: 'the versions of a song (Extended, Radio Edit, a remix) are suggested once, where the first of them was, by the shortest title that names no version (not the K-Mix); a " - " that names no version is the title\'s own',
        page: 'guest',
        run: async function (t) {
            const pending = holdITunes();
            const input = document.getElementById('songInput');
            input.focus();
            type(input, 'ona tanczy');
            await t.waitFor(function () { return pending.length === 2; }, 'iTunes asked', 3000);
            answered(pending, 'ona tanczy', ['Weekend - Ona Tańczy Dla Mnie (Extended)', 'Laser - Ona Tańczy Dla Mnie',
                'Weekend - Ona Tańczy dla Mnie (Radio Edit)', 'Weekend - Ona Tańczy Dla Mnie', 'Weekend - Ona Tańczy Dla Mnie - Davis Remix',
                'Ona - Sha - La - La - Lady', 'Ona - Sha - La - La - Lady [Live]',
                'Ona - Waka Waka (This Time for Africa) [K-Mix]', 'Ona - Waka Waka [The 2010 World Cup (TM) Song] [Single]']);
            answered(pending, 'ona', []);
            await t.waitFor(open, 'the suggestions', 3000);
            t.step('Weekend once, first, plain; "Sha - La - La - Lady" kept whole', shown(),
                ['Weekend – Ona Tańczy Dla Mnie', 'Laser – Ona Tańczy Dla Mnie', 'Ona – Sha - La - La - Lady',
                    'Ona – Waka Waka [The 2010 World Cup (TM) Song] [Single]']);
        }
    });

    S2P.scenario({
        name: 'song-suggestions-finished-words',
        title: 'only the songs with every finished word are suggested ("sweet car": no "Calm Down" iTunes pads its answer with) — unless none has them: a typo iTunes corrected keeps its answer',
        page: 'guest',
        run: async function (t) {
            const pending = holdITunes();
            const input = document.getElementById('songInput');
            input.focus();
            type(input, 'sweet car');
            await t.waitFor(function () { return pending.length === 2; }, 'iTunes asked for "sweet car"', 3000);
            answered(pending, 'sweet car', ['Neil Diamond - Sweet Caroline', 'Rema & Selena Gomez - Calm Down', 'Adele - Easy On Me',
                'Lynyrd Skynyrd - Sweet Home Alabama']);
            answered(pending, 'sweet', ['Neil Diamond - Sweet Caroline', 'Ava Max - Sweet but Psycho']);
            await t.waitFor(open, 'the suggestions for "sweet car"', 3000);
            t.step('the songs with "sweet", "car…" first', shown(),
                ['Neil Diamond – Sweet Caroline', 'Lynyrd Skynyrd – Sweet Home Alabama']);

            type(input, 'shakria w');
            await t.waitFor(function () { return pending.length === 4; }, 'iTunes asked for "shakria w"', 3000);
            answered(pending, 'shakria w', ['Shakira - Whenever, Wherever', 'Shakira - She Wolf']);
            answered(pending, 'shakria', []);
            await t.waitFor(function () { return shown()[0] === 'Shakira – Whenever, Wherever'; }, 'the suggestions for "shakria w"', 3000);
            t.step('no song has "shakria": iTunes\'s corrected answer kept', shown(),
                ['Shakira – Whenever, Wherever', 'Shakira – She Wolf']);
        }
    });

    S2P.scenario({
        name: 'song-suggestions-artist-begun',
        title: 'a single word begun completed to an artist of the list ("shak" → Shakira; "zene" → iTunes\'s "Zenon Martyniuk"): five of their songs first, then iTunes\'s own for the word — never pushed out —, then the rest; a whole word or two letters ask nothing more',
        page: 'guest',
        run: async function (t) {
            const pending = holdITunes();
            const input = document.getElementById('songInput');
            input.focus();
            type(input, 'shak');
            await t.waitFor(function () { return pending.length === 2; }, 'both asked at once', 3000);
            t.step('"shak" and "Shakira"', pending.map(function (p) { return p.term; }).sort(), ['Shakira', 'shak']);
            answered(pending, 'shak', ['9 Lazy 9 - Shak', 'Hassan Abou Seoud - Shik Shak Shok']);
            answered(pending, 'Shakira', ['Shakira - Whenever, Wherever', 'Shakira - She Wolf', 'Bizarrap - Shakira: Bzrp Music Sessions',
                'Shakira & Burna Boy - Dai Dai', 'Shakira - Hips Don\'t Lie', 'Shakira - Loca', 'Shakira - Waka Waka', 'Shakira - Rabiosa']);
            await t.waitFor(open, 'the suggestions for "shak"', 3000);
            t.step('her five, iTunes\'s own, then the rest of hers — not a song only named after her', shown(),
                ['Shakira – Whenever, Wherever', 'Shakira – She Wolf', 'Shakira & Burna Boy – Dai Dai', 'Shakira – Hips Don\'t Lie',
                    'Shakira – Loca', '9 Lazy 9 – Shak', 'Hassan Abou Seoud – Shik Shak Shok', 'Shakira – Waka Waka']);

            type(input, 'zene');
            await t.waitFor(function () { return pending.length === 4; }, '"zene" asked', 3000);
            t.step('the artist asked as iTunes names them', pending.slice(2).map(function (p) { return p.term; }).sort(),
                ['Zenon Martyniuk', 'zene']);
            answered(pending, 'zene', []);
            answered(pending, 'Zenon Martyniuk', ['Akcent - Przez Twe Oczy Zielone', 'Łobuzy, Akcent & Zenon Martyniuk - Mandacik']);
            await t.waitFor(function () { return shown()[0] === 'Łobuzy, Akcent & Zenon Martyniuk – Mandacik'; }, 'the suggestions for "zene"', 3000);
            t.step('only the songs with the whole name in their artist', shown(), ['Łobuzy, Akcent & Zenon Martyniuk – Mandacik']);

            type(input, 'queen');
            await t.waitFor(function () { return pending.length === 5; }, '"queen" asked', 3000);
            type(input, 'sh');
            await t.waitFor(function () { return pending.length === 6; }, '"sh" asked', 3000);
            await t.sleep(450);
            t.step('a whole word of an artist, two letters: nothing more asked', pending.slice(4).map(function (p) { return p.term; }),
                ['queen', 'sh']);
        }
    });

    S2P.scenario({
        name: 'song-suggestions-versions-not-joined',
        title: 'songs that are not versions of each other stay apart: a part\'s number in brackets ("(Part 1)", "(Part 2)"), a " - " whose part only holds a version\'s word in its middle ("Video - Killed the Radio Star"); an en dash names a version too ("Teraz – Remix")',
        page: 'guest',
        run: async function (t) {
            const pending = holdITunes();
            const input = document.getElementById('songInput');
            input.focus();
            type(input, 'ona brick');
            await t.waitFor(function () { return pending.length === 2; }, 'iTunes asked', 3000);
            answered(pending, 'ona brick', ['Ona - Another Brick (Part 1)', 'Ona - Another Brick (Part 2)', 'Ona - Video - Killed the Radio Star',
                'Ona - Video', 'Ona - Teraz – Remix', 'Ona - Teraz']);
            answered(pending, 'ona', []);
            await t.waitFor(open, 'the suggestions', 3000);
            t.step('both parts, both videos, "Teraz" once', shown(), ['Ona – Another Brick (Part 1)', 'Ona – Another Brick (Part 2)',
                'Ona – Video - Killed the Radio Star', 'Ona – Video', 'Ona – Teraz']);
        }
    });

    S2P.scenario({
        name: 'song-suggestions-album',
        title: 'a finished word met by the album counts: "grease you" keeps "You\'re the One That I Want" of the film\'s album',
        page: 'guest',
        run: async function (t) {
            const pending = holdITunes();
            const input = document.getElementById('songInput');
            input.focus();
            type(input, 'grease you');
            await t.waitFor(function () { return pending.length === 2; }, 'iTunes asked', 3000);
            answered(pending, 'grease you', ['Frankie Valli - Grease', 'Calum Scott - You Are the Reason']);
            answered(pending, 'grease', ['John Travolta & Olivia Newton-John - You\'re the One That I Want @ Grease (Soundtrack)',
                'Frankie Valli - Grease']);
            await t.waitFor(open, 'the suggestions', 3000);
            t.step('the film\'s song first, then "Grease"; not the padding', shown(),
                ['John Travolta & Olivia Newton-John – You\'re the One That I Want', 'Frankie Valli – Grease']);
        }
    });

    S2P.scenario({
        name: 'song-suggestions-alias',
        title: 'what guests type for an artist iTunes names otherwise ("zenek" for "Zenon Martyniuk"): a finished word met by the name iTunes gives, the word completed even when whole, the words before the one begun asked by that name',
        page: 'guest',
        run: async function (t) {
            const pending = holdITunes();
            const input = document.getElementById('songInput');
            input.focus();
            type(input, 'zenek martyniuk ');
            await t.waitFor(function () { return pending.length === 1; }, 'iTunes asked', 3000);
            answered(pending, 'zenek martyniuk', ['Classic, Marcin Miller & zenek martyniuk - Kiedy spadnie śnieg',
                'Łobuzy, Akcent & Zenon Martyniuk - Mandacik', 'Rema - Calm Down']);
            await t.waitFor(open, 'the suggestions for "zenek martyniuk "', 3000);
            t.step('"zenek" met by "Zenon" too; no padding', shown(), ['Classic, Marcin Miller & zenek martyniuk – Kiedy spadnie śnieg',
                'Łobuzy, Akcent & Zenon Martyniuk – Mandacik']);

            type(input, 'zenek');
            await t.waitFor(function () { return pending.length === 3; }, '"zenek" and its artist asked', 3000);
            t.step('"zenek" completed to Zenon Martyniuk', pending.slice(1).map(function (p) { return p.term; }).sort(),
                ['Zenon Martyniuk', 'zenek']);

            type(input, 'zenek ostatni r');
            await t.waitFor(function () { return pending.length === 5; }, '"zenek ostatni r" asked', 3000);
            t.step('the words before asked as iTunes names him', pending.slice(3).map(function (p) { return p.term; }).sort(),
                ['zenek ostatni r', 'zenon ostatni']);

            type(input, 'zenek m');
            await t.waitFor(function () { return pending.length === 7; }, '"zenek m" asked', 3000);
            t.step('"zenek m" completed to Zenon Martyniuk', pending.slice(5).map(function (p) { return p.term; }).sort(),
                ['Zenon Martyniuk', 'zenek m']);
        }
    });

    S2P.scenario({
        name: 'song-suggestions-artist-words',
        title: 'the words typed begin an artist\'s name ("bad bu" → Bad Bunny: iTunes has none of his songs among its answer for "bad"): completed as a single word is, the first on the list winning ("bad b" → Bad Boys Blue); after "the" too ("rolling st"); a whole name typed or words of no artist ask the words before the one begun, as before',
        page: 'guest',
        run: async function (t) {
            const pending = holdITunes();
            const input = document.getElementById('songInput');
            input.focus();
            type(input, 'bad bu');
            await t.waitFor(function () { return pending.length === 2; }, '"bad bu" asked', 3000);
            t.step('"bad bu" and Bad Bunny', pending.map(function (p) { return p.term; }).sort(), ['Bad Bunny', 'bad bu']);
            answered(pending, 'bad bu', ['Michael Jackson - Bad', 'Bad Bunny & Jhay Cortez - Dakiti']);
            answered(pending, 'Bad Bunny', ['Bad Bunny - Tití Me Preguntó', 'Bad Bunny - Me Porto Bonito', 'Michael Jackson - Bad']);
            await t.waitFor(open, 'the suggestions for "bad bu"', 3000);
            t.step('his songs first, then iTunes\'s own with a word starting "bu"', shown(),
                ['Bad Bunny – Tití Me Preguntó', 'Bad Bunny – Me Porto Bonito', 'Bad Bunny & Jhay Cortez – Dakiti']);

            type(input, 'bad b');
            await t.waitFor(function () { return pending.length === 4; }, '"bad b" asked', 3000);
            type(input, 'lady p');
            await t.waitFor(function () { return pending.length === 6; }, '"lady p" asked', 3000);
            type(input, 'rolling st');
            await t.waitFor(function () { return pending.length === 8; }, '"rolling st" asked', 3000);
            type(input, 'bad bunny');
            await t.waitFor(function () { return pending.length === 10; }, '"bad bunny" asked', 3000);
            type(input, 'shakira wa');
            await t.waitFor(function () { return pending.length === 12; }, '"shakira wa" asked', 3000);
            t.step('each with its artist; a whole name and a title asked with the words before', pending.slice(2).map(function (p) { return p.term; }),
                ['bad b', 'Bad Boys Blue', 'lady p', 'Lady Pank', 'rolling st', 'The Rolling Stones', 'bad bunny', 'bad',
                    'shakira wa', 'shakira']);
        }
    });

    S2P.scenario({
        name: 'song-suggestions-artist-begun-padding',
        title: 'a word completed to an artist: of iTunes\'s own answer for the half word only the songs with a word starting so — the padding of unrelated hits does not push the artist\'s songs out',
        page: 'guest',
        run: async function (t) {
            const pending = holdITunes();
            const input = document.getElementById('songInput');
            input.focus();
            type(input, 'zene');
            await t.waitFor(function () { return pending.length === 2; }, 'both asked', 3000);
            answered(pending, 'zene', ['Mariah Carey - All I Want for Christmas Is You', 'Zenek Kupatasa - Zew']);
            answered(pending, 'Zenon Martyniuk', ['Zenon Martyniuk - Jola', 'Zenon Martyniuk - Ostatni raz', 'Zenon Martyniuk - Przekorny los',
                'Zenon Martyniuk - Małomiasteczkowa', 'Zenon Martyniuk - Ty jesteś dla mnie', 'Zenon Martyniuk - Za zdrowie Pań',
                'Zenon Martyniuk - Wierzę w miłość']);
            await t.waitFor(open, 'the suggestions for "zene"', 3000);
            t.step('his five, the song with "zene…", the rest of his; no Mariah Carey', shown(), ['Zenon Martyniuk – Jola',
                'Zenon Martyniuk – Ostatni raz', 'Zenon Martyniuk – Przekorny los', 'Zenon Martyniuk – Małomiasteczkowa',
                'Zenon Martyniuk – Ty jesteś dla mnie', 'Zenek Kupatasa – Zew', 'Zenon Martyniuk – Za zdrowie Pań',
                'Zenon Martyniuk – Wierzę w miłość']);
        }
    });

    S2P.scenario({
        name: 'song-suggestions-artist-joined',
        title: 'a name with a slash or an apostrophe is completed from its letters ("ac/d" → AC/DC, "d\'ag" → Gigi D\'Agostino); "que" → Queen, before Quebonafide',
        page: 'guest',
        run: async function (t) {
            const pending = holdITunes();
            const input = document.getElementById('songInput');
            input.focus();
            type(input, 'ac/d');
            await t.waitFor(function () { return pending.length === 2; }, '"ac/d" asked', 3000);
            type(input, 'd\'ag');
            await t.waitFor(function () { return pending.length === 4; }, '"d\'ag" asked', 3000);
            type(input, 'que');
            await t.waitFor(function () { return pending.length === 6; }, '"que" asked', 3000);
            t.step('each with its artist', pending.map(function (p) { return p.term; }),
                ['ac/d', 'AC/DC', 'd\'ag', 'Gigi D\'Agostino', 'que', 'Queen']);
        }
    });

    S2P.scenario({
        name: 'song-suggestions-itunes-refused',
        title: 'when iTunes refuses (its limit reached: 429) nothing is asked for a while — the guests behind one address do not keep the limit full — then it is asked again',
        page: 'guest',
        run: async function (t) {
            const pending = holdITunes();
            const input = document.getElementById('songInput');
            input.focus();
            type(input, 'despacito');
            await t.waitFor(function () { return pending.length === 1; }, '"despacito" asked', 3000);
            pending[0].answer([], 429);
            await t.sleep(200);
            type(input, 'despacit');
            await t.sleep(600);
            t.step('refused: the next pause asks nothing', pending.length, 1);
            const now = Date.now;
            Date.now = function () { return now() + 120000; };
            try {
                type(input, 'despacito');
                await t.waitFor(function () { return pending.length === 2; }, 'asked again after the pause', 3000);
            } finally {
                Date.now = now;
            }
            t.step('asked again after the pause', pending[1].term, 'despacito');
        }
    });
})();
