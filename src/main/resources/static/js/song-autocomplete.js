/**
 * Song Autocomplete / Typeahead
 *
 * Queries the public Apple iTunes Search API (no server involvement, no YouTube quota).
 * Debounced at 300ms to avoid API spam.
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
    const ITUNES_API  = 'https://itunes.apple.com/search';
    const DEBOUNCE_MS = 300;
    const MAX_RESULTS = 8;
    const MIN_CHARS   = 2;

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

        let dropdown        = null;
        let activeIndex     = -1;
        let debounceTimer   = null;
        let currentController = null;

        // --- Build dropdown element and append into input's wrapper ---
        function buildDropdown() {
            const wrapper = input.parentElement;
            wrapper.style.position = 'relative';

            dropdown = document.createElement('div');
            dropdown.id        = dropdownId;
            dropdown.className = 'song-autocomplete-dropdown';
            dropdown.setAttribute('role', 'listbox');
            dropdown.setAttribute('aria-label', 'Song suggestions');
            wrapper.appendChild(dropdown);
        }

        // --- Debounce helper ---
        function debounce(fn, ms) {
            return function (...args) {
                clearTimeout(debounceTimer);
                debounceTimer = setTimeout(() => fn.apply(this, args), ms);
            };
        }

        // --- Fetch from iTunes ---
        async function fetchSuggestions(query) {
            if (currentController) currentController.abort();
            currentController = new AbortController();

            const url = new URL(ITUNES_API);
            url.searchParams.set('term',   query);
            url.searchParams.set('media',  'music');
            url.searchParams.set('entity', 'song');
            url.searchParams.set('limit',  String(MAX_RESULTS));

            try {
                const response = await fetch(url.toString(), { signal: currentController.signal });
                if (!response.ok) return [];
                const data = await response.json();
                return data.results || [];
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

            results.forEach((track, idx) => {
                const artist = track.artistName || '';
                const title  = track.trackName  || '';
                const label  = artist && title ? `${artist} \u2013 ${title}` : (title || artist);

                const item = document.createElement('div');
                item.className = 'song-autocomplete-item';
                item.setAttribute('role', 'option');
                item.setAttribute('data-label', label);
                item.setAttribute('data-index', String(idx));

                item.innerHTML =
                    `<span class="ac-artist">${escapeHtml(artist)}</span>` +
                    `<span class="ac-sep"> \u2013 </span>` +
                    `<span class="ac-title">${escapeHtml(title)}</span>`;

                item.addEventListener('mousedown', (e) => {
                    e.preventDefault(); // prevent blur before click
                    selectItem(label);
                });

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
            const query = input.value.trim();
            if (query.length < MIN_CHARS) { closeDropdown(); return; }
            const results = await fetchSuggestions(query);
            if (results === null) return;
            renderDropdown(results);
        }, DEBOUNCE_MS);

        // --- Close on outside click ---
        function onDocumentClick(e) {
            if (dropdown && !dropdown.contains(e.target) && e.target !== input) {
                closeDropdown();
            }
        }

        // --- Wire up ---
        buildDropdown();

        input.setAttribute('autocomplete',     'off');
        input.setAttribute('aria-autocomplete','list');
        input.setAttribute('aria-haspopup',    'listbox');
        input.setAttribute('aria-owns',        dropdownId);

        input.addEventListener('input',   onInput);
        input.addEventListener('keydown', handleKeydown);
        input.addEventListener('blur',    () => setTimeout(closeDropdown, 150));
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
