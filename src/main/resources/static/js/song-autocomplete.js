/**
 * Song Autocomplete / Typeahead
 *
 * Queries the public Apple iTunes Search API (no server involvement, no YouTube quota).
 * Debounced at 300ms to avoid API spam. Results are cached per query to avoid redundant calls.
 *
 * Usage: add  data-autocomplete="songs"  to any <input> that should get suggestions.
 * The script auto-discovers all matching inputs on DOMContentLoaded.
 *
 * Keyboard navigation:
 *   ArrowDown / ArrowUp  — navigate items
 *   Enter                — confirm highlighted item
 *   Escape               — close dropdown
 *
 * iTunes matches whole words only ("shakira da" finds no "Dai Dai", "zenek prz" nothing): a word being typed is completed here.
 * With it, the words before it are asked too (more of them, kept in memory while the word grows) and the songs with a word that
 * starts so come first; both asks go at once, so the list comes as fast as before. The Polish store (it has the Polish songs the
 * American one lacks, and the international ones too). A song iTunes gives several times (albums, singles, its versions: "(Radio
 * Edit)", "- Extended Mix") is shown once, by its shortest title that names no version. Of the songs found, those with every finished
 * word (in the artist, the title or the album) come — iTunes pads its answer with songs of none of them ("sweet car" → "Calm Down").
 * After iTunes refused (its limit), nothing is asked for ITUNES_PAUSE_MS.
 *
 * Only the answer for what the field says now is shown: one that comes after the guest typed on (or cleared the field) is
 * dropped. The field is an ARIA combobox: aria-expanded, aria-controls, aria-activedescendant on the chosen suggestion.
 *
 * The DJ's library (V58): a field with data-library-url (the party page and the kiosk, when the party has a library and its DJ lets
 * the suggestions be marked) asks our server which of the suggestions the DJ has, and shows those first with data-library-label
 * ("✓ DJ ma"), each part in iTunes's order. The list waits for that answer at most LIBRARY_WAIT_MS: a slow or failed one leaves the
 * suggestions as iTunes gave them — a guest is never held up by it, and nothing is refused by it.
 */

(function () {
    'use strict';

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------
    const ITUNES_API    = 'https://itunes.apple.com/search';
    const DEBOUNCE_MS   = 300;
    const MAX_RESULTS   = 8;    // suggestions shown
    const WHOLE_LIMIT   = 15;   // asked for the whole query: room for the duplicates that go
    const ARTIST_LIMIT  = 25;   // asked for the artist a single word begins
    const ARTIST_FIRST  = 5;    // of them before iTunes's own answer for the word
    const ARTIST_MIN_CHARS = 3; // "sh" begins too many
    const STEM_LIMIT    = 50;   // asked for the words before the one being typed (~10 KB gzipped, as fast as 8)
    const STORE         = 'PL';
    const MIN_CHARS     = 2;
    const CACHE_MAX_SIZE = 50;  // evict oldest entries beyond this limit
    const LIBRARY_WAIT_MS = 600;       // the longest the list waits for "which does the DJ have"
    const LIBRARY_CACHE_MAX = 500;     // songs remembered as had / not had
    const ITUNES_PAUSE_MS = 60000;     // nothing asked after iTunes refused: its limit (~20 asks a minute) is the address's, often
                                       // a venue's Wi-Fi shared by every guest, and asking again keeps it full

    /**
     * The artists a single word being typed is completed to (artistBegun): iTunes knows whole words only, so "shak" never reaches
     * Shakira by itself. Only an addition — iTunes's own answer for the word is still shown — and never the DJ's library (a guest
     * would learn it letter by letter). The first that fits wins: the likelier at a party comes earlier ("mart" → Martyniuk).
     * "Zenek Martyniuk=Zenon Martyniuk": what guests type, then the name iTunes gives the artist (asked by that); a typed word
     * iTunes does not know as theirs ("zenek") is completed even when whole, and stands for theirs ("zenon") everywhere (ALIAS_WORDS).
     * Reviewed every few months: docs/song-suggestions-artists.md.
     */
    const ARTISTS = [
        // Polish: dance, disco polo, weddings
        'Zenek Martyniuk=Zenon Martyniuk', 'Akcent', 'Boys', 'Bayer Full', 'Skolim', 'Sławomir', 'Piękni i Młodzi', 'Classic', 'Top One',
        'Łobuzy', 'Masters', 'Power Play', 'Cliver', 'Mig', 'Milano', 'Toples', 'Bracia Figo Fagot', 'Golec uOrkiestra',
        'Enej', 'Ich Troje', 'Wanda i Banda', 'Papa Dance', 'Kombi', 'Kombii', 'Big Cyc', 'Formacja Nieżywych Schabuff',
        'Exaited', 'Defis', 'After Party', 'Jorrgus', 'Diadem', 'Czadoman', 'Playboys', 'Bad Boys Blue',
        'Top Girls', 'Tomasz Niecik', 'Fanatic', 'Effect', 'Skaner', 'Long & Junior', 'Radek Liszewski', 'Mejk',
        'Ronnie Ferrari', 'Kalwi & Remi', 'Gang Albanii', 'Discoboys', 'Extazy', 'Sumptuastic', 'Cypis', 'Freaky Boys', 'Veegas',
        'Damian Holecki', 'Marcin Miller', 'Piersi', 'Bastek', 'Endriu', 'Mister Dex', 'Etna', 'Tom Swoon',
        'Gromee', 'Tribbs', 'Donatan',
        // Polish: pop, rock, rap
        'Dawid Podsiadło', 'sanah', 'Kortez', 'Daria Zawiałow', 'Krzysztof Zalewski', 'Vito Bambino', 'Ralph Kaminski', 'Kwiat Jabłoni',
        'Taco Hemingway', 'Mata', 'Bedoes', 'Young Leosia', 'Sobel', 'Smolasty', 'Kizo', 'Białas', 'Pezet', 'Kękę',
        'Doda', 'Edyta Górniak', 'Maryla Rodowicz', 'Krzysztof Krawczyk', 'Bajm', 'Lady Pank', 'Perfect', 'Budka Suflera', 'Republika',
        'Maanam', 'Kult', 'Kazik', 'Dżem', 'T.Love', 'Myslovitz', 'Hey', 'Nosowska', 'Wilki', 'Feel', 'Varius Manx', 'Kayah', 'Czerwone Gitary',
        'Trubadurzy', 'Skaldowie', 'Anna Jantar', 'Andrzej Zaucha', 'Seweryn Krajewski', 'Lombard', 'Elektryczne Gitary', 'Margaret',
        'Cleo', 'Dawid Kwiatkowski', 'Viki Gabor', 'Roksana Węgiel=Roxie Węgiel', 'Blanka', 'Brodka', 'Ania Dąbrowska', 'Sylwia Grzeszczak',
        'Andrzej Piaseczny', 'Kasia Kowalska', 'Natalia Kukulska', 'Ewelina Lisowska', 'Grzegorz Hyży', 'Mrozu',
        'Kamil Bednarek', 'Zakopower', 'Pectus', 'Edyta Bartosiewicz', 'Urszula', 'Rafał Brzozowski',
        'Natalia Szroeder', 'Organek', 'Happysad', 'Mery Spolsky', 'Męskie Granie Orkiestra',
        'Sara James', 'Oskar Cyms', 'Kaśka Sochacka', 'Ania Karwan', 'Igor Herbut', 'LemON', 'Sarsa', 'Paweł Domagała',
        'Natalia Nykiel', 'Julia Wieniawa', 'Bryska', 'PRO8L3M', 'Otsochodzi', 'Szczyl', 'Tymek',
        'Wiktoria Zwolińska', 'Kubi Producent', 'Jann', 'Zalia', 'Poparzeni Kawą Trzy',
        // Polish: the classics of weddings and dances, rock, rap
        'Zbigniew Wodecki', 'Irena Jarocka', 'Krzysztof Cugowski', 'Jerzy Połomski', 'Wojciech Gąsowski=Wojciech Gąssowski',
        'Danuta Rinn', 'Felicjan Andrzejczak', 'Ryszard Rynkowski',
        'Andrzej i Eliza', 'No To Co', 'Niebiesko-Czarni', 'Karin Stanek', 'Czesław Niemen', 'Marek Grechuta', 'Krzysztof Klenczon',
        'De Mono', 'Sztywny Pal Azji', 'Bolter', 'Oddział Zamknięty', 'Vox', '2 plus 1', 'Brathanki',
        'Raz Dwa Trzy', 'Golden Life', 'Alibabki', 'Kasia Cerekwicka', 'Ewa Farna', 'Afromental',
        'Strachy Na Lachy', 'Akurat', 'Liroy', 'Peja', 'Grubson', 'Natalia Przybysz', 'Edyta Geppert',
        // international: now
        'Shakira', 'Ed Sheeran', 'Dua Lipa', 'The Weeknd', 'Taylor Swift', 'Bruno Mars', 'Rihanna', 'Beyoncé', 'Lady Gaga', 'Ariana Grande',
        'Billie Eilish', 'Harry Styles', 'Justin Bieber', 'Justin Timberlake', 'Katy Perry', 'Miley Cyrus', 'Sabrina Carpenter',
        'Olivia Rodrigo', 'Benson Boone', 'Teddy Swims', 'Chappell Roan', 'Sia', 'Adele', 'Coldplay', 'Imagine Dragons', 'Maroon 5',
        'OneRepublic', 'Lewis Capaldi', 'Sam Smith', 'Ellie Goulding', 'Rita Ora', 'Jessie J', 'Ava Max', 'Doja Cat', 'Lizzo', 'Post Malone',
        'Drake', 'Eminem', 'Snoop Dogg', 'Black Eyed Peas', 'LMFAO', 'Usher', 'Ne-Yo', 'Chris Brown', 'Pitbull', 'Flo Rida', 'Sean Paul',
        'Jennifer Lopez', 'Enrique Iglesias', 'Ricky Martin', 'Luis Fonsi', 'Daddy Yankee', 'J Balvin', 'Bad Bunny', 'Maluma', 'Karol G',
        'Rosalía', 'Camila Cabello', 'Måneskin', 'Loreen', 'Hozier', 'Pharrell Williams', 'Mark Ronson',
        // international: dance
        'David Guetta', 'Calvin Harris', 'Avicii', 'Martin Garrix', 'Marshmello', 'Kygo', 'Tiësto', 'Alok', 'Robin Schulz',
        'Major Lazer', 'DJ Snake', 'Clean Bandit', 'Jax Jones', 'Joel Corry', 'Lost Frequencies',
        'Purple Disco Machine', 'Sophie Ellis-Bextor', 'Kylie Minogue', 'Gigi D\'Agostino', 'Scooter', 'Vengaboys', 'Aqua', 'Eiffel 65',
        'Haddaway', 'Dr. Alban', 'Ace of Base', '2 Unlimited', 'Lou Bega', 'Las Ketchup', 'Los del Río', 'Tones and I',
        'Shazza',   // after Shakira: "sha" is Shakira's more often
        // international: classics
        'Michael Jackson', 'Madonna', 'Whitney Houston', 'ABBA', 'Queen', 'Bon Jovi', 'AC/DC', 'Guns N\' Roses', 'The Beatles',
        'The Rolling Stones', 'Elton John', 'Boney M.', 'Modern Talking', 'Bee Gees', 'Earth, Wind & Fire', 'Kool & The Gang',
        'Gloria Gaynor', 'Village People', 'Daft Punk', 'Spice Girls', 'Backstreet Boys', 'Britney Spears', 'Christina Aguilera',
        'Linkin Park', 'Red Hot Chili Peppers', 'Nirvana', 'Metallica', 'Europe', 'Scorpions', 'Roxette', 'Eurythmics', 'Depeche Mode',
        'Pet Shop Boys', 'Duran Duran', 'Tears for Fears', 'a-ha', 'Toto', 'Journey', 'Survivor', 'Bonnie Tyler', 'Tina Turner', 'Cher',
        'Celine Dion', 'Mariah Carey', 'Wham!', 'George Michael', 'Phil Collins', 'Lionel Richie', 'Stevie Wonder', 'Prince',
        'Bruce Springsteen', 'Elvis Presley', 'Frank Sinatra', 'Dean Martin', 'Michael Bublé', 'Dire Straits', 'Pink Floyd',
        'Led Zeppelin', 'Deep Purple', 'Status Quo', 'Creedence Clearwater Revival', 'The Killers', 'Arctic Monkeys', 'Kings of Leon',
        'Franz Ferdinand', 'Oasis', 'Blur', 'U2', 'R.E.M.', 'Bob Marley', 'UB40', 'Zucchero', 'Eros Ramazzotti', 'Laura Pausini',
        'Andrea Bocelli', 'Rammstein', 'Helene Fischer', 'Nelly Furtado', 'Timbaland', '50 Cent',
        // after the artists that share their beginning and are asked for more often ("jus" → Justin Bieber, "mich" → Michael Jackson,
        // "que" → Queen, "hou" → Whitney Houston, "dee" → Deep Purple)
        'Quebonafide', 'Swedish House Mafia', 'Alice Deejay',
        'Justyna Steczkowska', 'Jacek Stachursky', 'Andre', 'Kordian', 'Michał Szpak', 'Michał Bajor', 'White 2115',
        'Malik Montana', 'Andrzej Rosiewicz', 'Kasia Sobczyk=Katarzyna Sobczyk', 'Halina Frąckowiak', 'Krzysztof Kiljański', 'Camasutra'
    ];

    /** Words of the artists that begin nothing: "the" is no start of The Weeknd's name, "orkiestra" none of Golec uOrkiestra's. */
    const ARTIST_SKIPPED = new Set(['the', 'and', 'band', 'orkiestra', 'orchestra', 'feat', 'dj', 'project']);

    /**
     * ARTISTS, read once: the name asked of iTunes, the words of all its names (a typed word equal to one is whole: iTunes finds it
     * by itself), and a short word joined with its neighbour ("acdc", "dagostino", "tlove") — a guest types "ac/d" or "d'ag" as one word.
     */
    const ARTIST_ENTRIES = ARTISTS.map(function (entry) {
        const names = entry.split('=');
        const all = words(names.join(' '));
        const joined = [];
        for (let i = 1; i < all.length; i++) {
            if (all[i - 1].length <= 2 || all[i].length <= 2) joined.push(all[i - 1] + all[i]);
        }
        return { name: names[names.length - 1], typed: names.length > 1 ? words(names[0]) : [], words: all, joined: joined };
    });

    /**
     * What guests type for an artist iTunes names otherwise, word for word ("zenek" → "zenon"): iTunes does not know the typed word
     * as theirs. Never a word of another artist's name as iTunes gives it ("kasia": Kasia Kowalska is Kasia there).
     */
    const ALIAS_WORDS = new Map();
    ARTIST_ENTRIES.forEach(function (entry) {
        const named = words(entry.name);
        entry.typed.forEach(function (w, i) {
            const elsewhere = ARTIST_ENTRIES.some(function (other) { return words(other.name).indexOf(w) >= 0; });
            if (named.indexOf(w) < 0 && named[i] && !elsewhere) ALIAS_WORDS.set(w, named[i]);
        });
    });

    /** The words that name a version, in a bracket or at either end of a " - " part: "(Radio Edit)", " - 2015 Remaster". */
    const VERSION_WORDS = new Set(['remix', 'rmx', 'mix', 'edit', 'version', 'wersja', 'live', 'remaster', 'remastered', 'acoustic',
        'instrumental', 'acapella', 'cappella', 'radio', 'extended', 'cover', 'karaoke']);
    /** A guest named: cut from the title like a version, but the same recording ("(feat. X)" is no remix). */
    const FEAT_WORDS = new Set(['feat', 'ft']);
    /** A part's number in brackets: another song ("Another Brick in the Wall (Part 1)" and "(Part 2)"), kept in the title. */
    const PART = /^\s*(part|pt\.?|cz\.?|część)\s*\d+\s*$/i;

    /** When iTunes may be asked again after it refused (ITUNES_PAUSE_MS). */
    let itunesQuietUntil = 0;

    // -------------------------------------------------------------------------
    // Module-level result cache  (query string → results array)
    // Shared across all instances — if two inputs ask for the same query the
    // second one gets the cached answer instantly without a network round-trip.
    // -------------------------------------------------------------------------
    const resultCache = new Map();

    /** Whether the DJ has a song, by the field's library address and the song ("…|Wilki - Baśka" → true): asked once for each. */
    const libraryCache = new Map();

    /** Lower case, without accents ("Żółta" → "zolta"; ł has none to take away): what a guest types without Polish letters. */
    function plain(text) {
        return String(text || '').toLowerCase().normalize('NFD').replace(/\p{M}/gu, '').replace(/ł/g, 'l');
    }

    function words(text) {
        return plain(text).split(/[^\p{L}\p{N}]+/u).filter(Boolean);
    }

    /** The word being typed and the words before it ("shakira da" → "shakira", "da"); null when the last word is finished. */
    function wordBegun(typed) {
        if (/\s$/.test(typed)) return null;
        const parts = typed.trim().split(/\s+/);
        const fragment = words(parts[parts.length - 1]).join('');
        const stem = parts.slice(0, -1).join(' ');
        return parts.length > 1 && fragment && stem.length >= MIN_CHARS ? { stem: stem, fragment: fragment } : null;
    }

    /**
     * The title without what names a version, and whether it named one: "(Radio Edit)", "[Live]", " - Extended Mix", " – 2015
     * Remaster" ("Ona Tańczy Dla Mnie (Davis Rmx)" → "Ona Tańczy Dla Mnie"). "(feat. X)" goes too, as no version. A " - " part goes only
     * when it begins or ends with a version's word: "Sha - La - La - Lady" and "Video - Killed the Radio Star" are titles of their own.
     * A part's number stays: "(Part 1)" and "(Part 2)" are two songs.
     */
    function titleParts(title) {
        let bare = String(title || '');
        let version = false;
        const namesAVersion = function (text) { return words(text).some(function (w) { return VERSION_WORDS.has(w); }); };
        // the innermost brackets first: "[The Official 2010 FIFA World Cup (TM) Song]" goes whole
        for (let before = ''; before !== bare;) {
            before = bare;
            bare = bare.replace(/\(([^()]*)\)|\[([^\[\]]*)\]/g, function (all, round, square) {
                const inner = round !== undefined ? round : square;
                if (PART.test(inner)) return ' ' + inner + ' ';   // without its brackets: not taken again
                if (namesAVersion(inner)) version = true;
                return ' ';
            });
        }
        const parts = bare.split(/\s+[-–]\s+/);
        while (parts.length > 1) {
            const last = words(parts[parts.length - 1]);
            const ends = [last[0], last[last.length - 1]];
            const isVersion = ends.some(function (w) { return VERSION_WORDS.has(w); });
            if (!isVersion && !ends.some(function (w) { return FEAT_WORDS.has(w); })) break;
            if (isVersion) version = true;
            parts.pop();
        }
        return { bare: parts.join(' - ').trim(), version: version };
    }

    /**
     * Each song once: the same artist and title, whatever the album, the version, the accents or the spaces — a guest asks for a
     * song, the DJ plays their own file of it. The song keeps the place of its first version and is shown by a title that names no
     * version, the shortest of them: the guest's pick is what the DJ reads, and "[K-Mix]" would ask for a remix.
     */
    function onceEach(tracks) {
        const byKey = new Map();
        tracks.forEach(function (track) {
            const title = titleParts(track.trackName);
            const key = words(track.artistName).join(' ') + '|' + words(title.bare || track.trackName).join(' ');
            const found = { track: track, version: title.version, length: String(track.trackName || '').length };
            const kept = byKey.get(key);
            if (!kept || (kept.version && !found.version) || (kept.version === found.version && found.length < kept.length)) {
                byKey.set(key, found);   // a Map keeps the place of the key's first entry
            }
        });
        return Array.from(byKey.values()).map(function (found) { return found.track; });
    }

    /** The words of a song: its artist, title and album — iTunes matches the album too ("grease" → the film's songs). */
    function wordsOfSong(track) {
        return words(track.artistName).concat(words(track.trackName), words(track.collectionName));
    }

    /** The words typed, each as iTunes names the artist it stands for ("zenek m" → "zenon m"): asked of iTunes as such. */
    function asITunesNames(text) {
        return text.trim().split(/\s+/).map(function (w) { return ALIAS_WORDS.get(words(w).join('')) || w; }).join(' ');
    }

    /**
     * Only the songs with every finished word ("sweet car": "sweet") in their artist, title or album — or the word iTunes names the
     * artist by ("zenek": "Zenon Martyniuk") —, when there are such: iTunes fills its answer with songs of none of them ("Calm Down"
     * for "sweet car"). When no song has them all — a typo iTunes corrected, "shakria w" — its answer stays as it is.
     */
    function withTheWords(tracks, finished) {
        if (finished.length === 0) return tracks;
        const having = tracks.filter(function (track) {
            const its = wordsOfSong(track);
            return finished.every(function (w) {
                const named = ALIAS_WORDS.get(w);
                return its.some(function (it) { return it.indexOf(w) === 0 || it === named; });
            });
        });
        return having.length > 0 ? having : tracks;
    }

    /**
     * The artist a single word being typed begins ("shak" → "Shakira", "podsia" → "Dawid Podsiadło", "ac/d" → "AC/DC"), from
     * ARTISTS: the first whose word starts so and is longer — a whole word ("imagine") iTunes finds by itself, except a typed word of
     * an alias ("zenek"), which iTunes knows as someone else's. Null when none, or the word is short.
     */
    function artistBegun(typed) {
        if (/\s/.test(typed.trim()) || /\s$/.test(typed)) return null;
        const fragment = words(typed).join('');
        if (fragment.length < ARTIST_MIN_CHARS) return null;
        let entry;
        if (ALIAS_WORDS.has(fragment)) {
            entry = ARTIST_ENTRIES.find(function (a) { return a.typed.indexOf(fragment) >= 0; });
        } else if (!ARTIST_ENTRIES.some(function (a) { return a.words.indexOf(fragment) >= 0; })) {
            // not a word that already is an artist's ("kombi", "queen"): iTunes finds them, Kombii is not put above Kombi
            entry = ARTIST_ENTRIES.find(function (a) {
                return a.words.some(function (w) {
                    return w.length > fragment.length && w.indexOf(fragment) === 0 && !ARTIST_SKIPPED.has(w);
                })
                    || a.joined.some(function (w) { return w.indexOf(fragment) === 0; });
            });
        }
        return entry ? { name: entry.name, fragment: fragment } : null;   // asked as iTunes names them
    }

    /**
     * The songs of the artist the word begins (those with every word of the name in their artist: "Shakira", "Disney & Shakira"),
     * iTunes's own answer after them — the artist's first ARTIST_FIRST songs, then iTunes's own with a word starting so, then the
     * rest of the artist's: what iTunes found for the word itself is never pushed out of the list. Its other songs are the hits it
     * pads an answer for a half word with ("zene" → "All I Want for Christmas Is You"), and they would push the artist's out.
     */
    function withTheArtist(artistsSongs, itsOwn, artist) {
        const named = words(artist.name);
        const theirs = onceEach(artistsSongs.filter(function (track) {
            const its = words(track.artistName);
            return named.every(function (w) { return its.indexOf(w) >= 0; });
        }));
        const startingSo = itsOwn.filter(function (track) {
            return wordsOfSong(track).some(function (w) { return w.indexOf(artist.fragment) === 0; });
        });
        return onceEach(theirs.slice(0, ARTIST_FIRST).concat(startingSo, theirs.slice(ARTIST_FIRST))).slice(0, MAX_RESULTS);
    }

    /** The words already finished: before the word being typed, or all of them after a space; none of a single word being typed. */
    function finishedWords(typed, begun) {
        if (begun) return words(begun.stem);
        return /\s$/.test(typed) ? words(typed) : [];
    }

    function cacheSet(key, value) {
        if (resultCache.size >= CACHE_MAX_SIZE) {
            // Evict the oldest (first-inserted) entry
            resultCache.delete(resultCache.keys().next().value);
        }
        resultCache.set(key, value);
    }

    function libraryCacheSet(key, value) {
        if (libraryCache.size >= LIBRARY_CACHE_MAX) {
            libraryCache.delete(libraryCache.keys().next().value);
        }
        libraryCache.set(key, value);
    }

    /** A suggestion as our server reads a song: "Artist - Title". */
    function songOf(track) {
        return (track.artistName || '') + ' - ' + (track.trackName || '');
    }

    // -------------------------------------------------------------------------
    // Module-level debounce utility — standard pattern with own timer closure.
    // -------------------------------------------------------------------------
    function debounce(fn, ms) {
        let timer = null;
        return function (...args) {
            clearTimeout(timer);
            timer = setTimeout(() => fn.apply(this, args), ms);
        };
    }

    // -------------------------------------------------------------------------
    // Utility: XSS-safe HTML escaping
    // -------------------------------------------------------------------------
    function escapeHtml(str) {
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    // -------------------------------------------------------------------------
    // Factory: one independent autocomplete instance per input element
    // -------------------------------------------------------------------------
    function createInstance(input) {
        const dropdownId = (input.id || 'ac-' + Math.random().toString(36).slice(2)) + '-dropdown';

        let dropdown          = null;
        let activeIndex       = -1;
        let currentController = null;
        let touchingDropdown  = false; // true while a touch sequence is on the dropdown
        const libraryUrl      = input.dataset.libraryUrl || null;    // the DJ's library to ask (V58), none: suggestions unmarked
        const libraryLabel    = input.dataset.libraryLabel || '';

        // --- Build dropdown element and append into input's wrapper ---
        function buildDropdown() {
            // the wrapper is positioned by the .song-autocomplete-wrapper class
            dropdown = document.createElement('div');
            dropdown.id        = dropdownId;
            dropdown.className = 'song-autocomplete-dropdown';
            dropdown.setAttribute('role', 'listbox');
            dropdown.setAttribute('aria-label', 'Song suggestions');
            input.parentElement.appendChild(dropdown);
        }

        // --- One ask of iTunes (with cache): its songs, [] on an error, null when cancelled ---
        async function ask(term, limit, signal) {
            const key = limit + '|' + term;
            // Serve from cache when available — avoids redundant API calls
            if (resultCache.has(key)) {
                return resultCache.get(key);
            }
            if (Date.now() < itunesQuietUntil) return [];   // iTunes refused a moment ago
            const url = new URL(ITUNES_API);
            url.searchParams.set('term',    term);
            url.searchParams.set('media',   'music');
            url.searchParams.set('entity',  'song');
            url.searchParams.set('country', STORE);
            url.searchParams.set('limit',   String(limit));
            try {
                const response = await fetch(url, { signal: signal });
                if (!response.ok) {
                    if (response.status === 403 || response.status === 429) itunesQuietUntil = Date.now() + ITUNES_PAUSE_MS;
                    return [];
                }
                const data = await response.json();
                const results = data.results || [];
                cacheSet(key, results);
                return results;
            } catch (err) {
                if (err.name === 'AbortError') return null; // cancelled — ignore
                console.warn('[Autocomplete] iTunes API error:', err);
                return [];
            }
        }

        // --- The suggestions for what was typed: the songs of the word begun first, then iTunes's own, each once ---
        async function fetchSuggestions(typed) {
            // a query asked before is not wanted any more, whatever answers this one
            if (currentController) currentController.abort();
            currentController = new AbortController();
            const signal = currentController.signal;
            const query = typed.trim();
            const begun = wordBegun(typed);
            // both at once: the list waits for the slower of two parallel asks, not for one after the other
            const artist = begun ? null : artistBegun(typed);
            const answers = await Promise.all([ask(query, WHOLE_LIMIT, signal),
                begun ? ask(asITunesNames(begun.stem), STEM_LIMIT, signal) : [], artist ? ask(artist.name, ARTIST_LIMIT, signal) : []]);
            if (answers[0] === null || answers[1] === null || answers[2] === null) return null;
            if (artist) return withTheDjsFirst(withTheArtist(answers[2], answers[0], artist), signal);
            // the songs whose title has a word starting so, then those whose artist has ("da" → "Dai Dai", then "David Guetta")
            const starts = function (text) {
                return words(text).some(function (w) { return w.indexOf(begun.fragment) === 0; });
            };
            const byTitle = begun ? answers[1].filter(function (track) { return starts(track.trackName); }) : [];
            const byArtist = begun ? answers[1].filter(function (track) { return !starts(track.trackName) && starts(track.artistName); }) : [];
            const found = withTheWords(byTitle.concat(byArtist, answers[0]), finishedWords(typed, begun));
            return withTheDjsFirst(onceEach(found).slice(0, MAX_RESULTS), signal);
        }

        /**
         * The suggestions with the songs the DJ has first, marked (djHas), each part in its order (V58); as they are without a library
         * to ask, or when its answer is slow or fails. Null when the query was cancelled meanwhile.
         */
        async function withTheDjsFirst(tracks, signal) {
            if (!libraryUrl || tracks.length === 0) return tracks;
            const songs = tracks.map(songOf);
            const unknown = songs.filter(function (song) { return !libraryCache.has(libraryUrl + '|' + song); });
            if (unknown.length > 0) {
                const url = new URL(libraryUrl, location.href);
                unknown.forEach(function (song) { url.searchParams.append('song', song); });
                const wait = new AbortController();
                const stop = function () { wait.abort(); };
                const timer = setTimeout(stop, LIBRARY_WAIT_MS);
                signal.addEventListener('abort', stop);
                try {
                    const response = await fetch(url, { signal: wait.signal, headers: { Accept: 'application/json' } });
                    const have = response.ok ? await response.json() : null;
                    if (Array.isArray(have)) {
                        unknown.forEach(function (song, i) { libraryCacheSet(libraryUrl + '|' + song, have[i] === true); });
                    }
                } catch (err) {
                    // too slow, cancelled or failed: the suggestions unmarked — asked again with the next query
                } finally {
                    clearTimeout(timer);
                    signal.removeEventListener('abort', stop);
                }
            }
            if (signal.aborted) return null;
            const marked = tracks.map(function (track, i) {
                return Object.assign({}, track, { djHas: libraryCache.get(libraryUrl + '|' + songs[i]) === true });
            });
            return marked.filter(function (track) { return track.djHas; })
                .concat(marked.filter(function (track) { return !track.djHas; }));
        }

        // --- Render results ---
        function renderDropdown(results) {
            dropdown.innerHTML = '';
            activeIndex = -1;

            if (!results || results.length === 0) {
                closeDropdown();
                return;
            }

            results.forEach((track, index) => {
                const artist = track.artistName || '';
                const title  = track.trackName  || '';
                const label  = artist && title ? `${artist} \u2013 ${title}` : (title || artist);

                const item = document.createElement('div');
                item.className = 'song-autocomplete-item';
                item.id = dropdownId + '-' + index;
                item.setAttribute('role',       'option');
                item.setAttribute('aria-selected', 'false');
                item.setAttribute('data-label', label);

                // "✓ DJ ma" (V58) at the start of the artist's part: on a phone the artist has a line of its own, on a wide screen a long
                // title is cut at its end — never the mark
                const djHas = track.djHas ? `<span class="ac-library">${escapeHtml(libraryLabel)}</span> ` : '';
                if (track.djHas) item.classList.add('is-djs');
                item.innerHTML =
                    `<span class="ac-artist">${djHas}${escapeHtml(artist)}</span>` +
                    `<span class="ac-sep"> \u2013 </span>` +
                    `<span class="ac-title">${escapeHtml(title)}</span>`;

                // Desktop: mousedown fires before blur — preventDefault keeps focus
                item.addEventListener('mousedown', (e) => {
                    e.preventDefault();
                    selectItem(label);
                });

                // Mobile: click fires after blur, but the touchingDropdown flag
                // (set on the dropdown container) prevents closeDropdown during
                // the touch sequence, so click still finds the dropdown open.
                item.addEventListener('click', () => selectItem(label));

                dropdown.appendChild(item);
            });

            dropdown.classList.add('is-open');
            input.setAttribute('aria-expanded', 'true');
        }

        // --- Select item ---
        function selectItem(label) {
            input.value = label;
            closeDropdown();
            input.focus();
        }

        // --- Close dropdown ---
        function closeDropdown() {
            if (dropdown) {
                dropdown.classList.remove('is-open');
                dropdown.innerHTML = '';
            }
            activeIndex = -1;
            input.setAttribute('aria-expanded', 'false');
            input.removeAttribute('aria-activedescendant');
        }

        // --- Keyboard navigation ---
        function handleKeydown(e) {
            if (!dropdown || !dropdown.classList.contains('is-open')) return;
            const items = dropdown.querySelectorAll('.song-autocomplete-item');
            if (!items.length) return;

            switch (e.key) {
                case 'ArrowDown':
                    e.preventDefault();
                    activeIndex = (activeIndex + 1) % items.length;
                    updateActiveItem(items);
                    break;
                case 'ArrowUp':
                    e.preventDefault();
                    activeIndex = (activeIndex - 1 + items.length) % items.length;
                    updateActiveItem(items);
                    break;
                case 'Enter':
                    if (activeIndex >= 0 && items[activeIndex]) {
                        e.preventDefault();
                        selectItem(items[activeIndex].getAttribute('data-label'));
                    }
                    break;
                case 'Escape':
                    closeDropdown();
                    break;
            }
        }

        function updateActiveItem(items) {
            items.forEach((item, idx) => {
                item.classList.toggle('is-active', idx === activeIndex);
                item.setAttribute('aria-selected', String(idx === activeIndex));
            });
            if (activeIndex >= 0) {
                input.setAttribute('aria-activedescendant', items[activeIndex].id);
                items[activeIndex].scrollIntoView({ block: 'nearest' });
            }
        }

        // --- Debounced input handler ---
        const onInput = debounce(async function () {
            const query = input.value.trim();
            if (query.length < MIN_CHARS) {
                if (currentController) currentController.abort();   // its answer is for a field that says something else now
                closeDropdown();
                return;
            }
            const results = await fetchSuggestions(input.value);
            if (results === null) return; // aborted request — keep current dropdown
            if (input.value.trim() !== query) return;   // the guest typed on meanwhile: an answer to an old question
            renderDropdown(results);
        }, DEBOUNCE_MS);

        // --- Close on outside click/touch ---
        // a named function, so that it can be removed when the input leaves the page
        function onDocumentClick(e) {
            if (dropdown && !dropdown.contains(e.target) && e.target !== input) {
                closeDropdown();
            }
        }

        // Cleanup: remove global listener when input is detached (MutationObserver)
        const observer = new MutationObserver(() => {
            if (!document.contains(input)) {
                document.removeEventListener('click', onDocumentClick);
                observer.disconnect();
            }
        });
        observer.observe(document.body, { childList: true, subtree: true });

        // --- Wire up ---
        buildDropdown();

        // Mobile: track whether a touch is happening inside the dropdown.
        // This prevents the blur handler from closing the dropdown before
        // the click event on the item fires.  Does NOT call preventDefault(),
        // so native scrolling inside the dropdown works normally.
        dropdown.addEventListener('touchstart', () => { touchingDropdown = true; }, { passive: true });
        dropdown.addEventListener('touchend',   () => { touchingDropdown = false; }, { passive: true });
        dropdown.addEventListener('touchcancel',() => { touchingDropdown = false; }, { passive: true });

        input.setAttribute('autocomplete',      'off');
        input.setAttribute('role',              'combobox');
        input.setAttribute('aria-autocomplete', 'list');
        input.setAttribute('aria-haspopup',     'listbox');
        input.setAttribute('aria-controls',     dropdownId);
        input.setAttribute('aria-expanded',     'false');

        input.addEventListener('input',   onInput);
        input.addEventListener('keydown', handleKeydown);
        input.addEventListener('blur',    () => {
            // Delay close so that click/mousedown on an item can fire first.
            // If a touch sequence is active on the dropdown, wait longer for
            // the click event to complete (touchend → click).
            setTimeout(closeDropdown, touchingDropdown ? 300 : 150);
        });
        document.addEventListener('click', onDocumentClick);
    }

    // -------------------------------------------------------------------------
    // Auto-discover all inputs with data-autocomplete="songs"
    // -------------------------------------------------------------------------
    function init() {
        document.querySelectorAll('input[data-autocomplete="songs"]').forEach(createInstance);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init(); // script loaded after DOM is ready (defer / bottom of body)
    }

})();
