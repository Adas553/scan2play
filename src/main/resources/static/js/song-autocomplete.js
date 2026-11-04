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
 * American one lacks, and the international ones too). A song iTunes gives several times (albums, singles) is shown once.
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
    const STEM_LIMIT    = 50;   // asked for the words before the one being typed (~10 KB gzipped, as fast as 8)
    const STORE         = 'PL';
    const MIN_CHARS     = 2;
    const CACHE_MAX_SIZE = 50;  // evict oldest entries beyond this limit
    const LIBRARY_WAIT_MS = 600;       // the longest the list waits for "which does the DJ have"
    const LIBRARY_CACHE_MAX = 500;     // songs remembered as had / not had

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

    /** Each song once: the same artist and title, whatever the album, the accents or the spaces. */
    function onceEach(tracks) {
        const seen = new Set();
        return tracks.filter(function (track) {
            const key = words(track.artistName).join(' ') + '|' + words(track.trackName).join(' ');
            if (seen.has(key)) return false;
            seen.add(key);
            return true;
        });
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
            const url = new URL(ITUNES_API);
            url.searchParams.set('term',    term);
            url.searchParams.set('media',   'music');
            url.searchParams.set('entity',  'song');
            url.searchParams.set('country', STORE);
            url.searchParams.set('limit',   String(limit));
            try {
                const response = await fetch(url, { signal: signal });
                if (!response.ok) return [];
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
            const answers = await Promise.all([ask(query, WHOLE_LIMIT, signal), begun ? ask(begun.stem, STEM_LIMIT, signal) : []]);
            if (answers[0] === null || answers[1] === null) return null;
            // the songs whose title has a word starting so, then those whose artist has ("da" → "Dai Dai", then "David Guetta")
            const starts = function (text) {
                return words(text).some(function (w) { return w.indexOf(begun.fragment) === 0; });
            };
            const byTitle = begun ? answers[1].filter(function (track) { return starts(track.trackName); }) : [];
            const byArtist = begun ? answers[1].filter(function (track) { return !starts(track.trackName) && starts(track.artistName); }) : [];
            return withTheDjsFirst(onceEach(byTitle.concat(byArtist, answers[0])).slice(0, MAX_RESULTS), signal);
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
