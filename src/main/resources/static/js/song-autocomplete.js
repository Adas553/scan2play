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
 */

(function () {
    'use strict';

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------
    const ITUNES_API    = 'https://itunes.apple.com/search';
    const DEBOUNCE_MS   = 300;
    const MAX_RESULTS   = 8;
    const MIN_CHARS     = 2;
    const CACHE_MAX_SIZE = 50;  // evict oldest entries beyond this limit

    // -------------------------------------------------------------------------
    // Module-level result cache  (query string → results array)
    // Shared across all instances — if two inputs ask for the same query the
    // second one gets the cached answer instantly without a network round-trip.
    // -------------------------------------------------------------------------
    const resultCache = new Map();

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

        // --- Fetch from iTunes (with cache) ---
        async function fetchSuggestions(query) {
            // Serve from cache when available — avoids redundant API calls
            if (resultCache.has(query)) {
                return resultCache.get(query);
            }

            if (currentController) currentController.abort();
            currentController = new AbortController();

            const url = new URL(ITUNES_API);
            url.searchParams.set('term',   query);
            url.searchParams.set('media',  'music');
            url.searchParams.set('entity', 'song');
            url.searchParams.set('limit',  String(MAX_RESULTS));

            try {
                const response = await fetch(url, { signal: currentController.signal });
                if (!response.ok) return [];
                const data = await response.json();
                const results = data.results || [];
                cacheSet(query, results);
                return results;
            } catch (err) {
                if (err.name === 'AbortError') return null; // cancelled — ignore
                console.warn('[Autocomplete] iTunes API error:', err);
                return [];
            }
        }

        // --- Render results ---
        function renderDropdown(results) {
            dropdown.innerHTML = '';
            activeIndex = -1;

            if (!results || results.length === 0) {
                closeDropdown();
                return;
            }

            results.forEach((track) => {
                const artist = track.artistName || '';
                const title  = track.trackName  || '';
                const label  = artist && title ? `${artist} \u2013 ${title}` : (title || artist);

                const item = document.createElement('div');
                item.className = 'song-autocomplete-item';
                item.setAttribute('role',       'option');
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
            items.forEach((item, idx) => item.classList.toggle('is-active', idx === activeIndex));
            if (activeIndex >= 0) items[activeIndex].scrollIntoView({ block: 'nearest' });
        }

        // --- Debounced input handler ---
        const onInput = debounce(async function () {
            // The page can switch the suggestions off (the guest's form in the mood mode)
            if (input.dataset.autocompleteOff === 'true') { closeDropdown(); return; }
            const query = input.value.trim();
            if (query.length < MIN_CHARS) { closeDropdown(); return; }
            const results = await fetchSuggestions(query);
            if (results === null) return; // aborted request — keep current dropdown
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
        input.setAttribute('aria-autocomplete', 'list');
        input.setAttribute('aria-haspopup',     'listbox');
        input.setAttribute('aria-owns',         dropdownId);

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
