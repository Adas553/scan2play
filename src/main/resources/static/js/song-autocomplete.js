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

    // -------------------------------------------------------------------------
    // Module-level result cache  (query string → results array)
    // Shared across all instances — if two inputs ask for the same query the
    // second one gets the cached answer instantly without a network round-trip.
    // -------------------------------------------------------------------------
    const resultCache = new Map();

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
            return onceEach(byTitle.concat(byArtist, answers[0])).slice(0, MAX_RESULTS);
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

                item.innerHTML =
                    `<span class="ac-artist">${escapeHtml(artist)}</span>` +
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
