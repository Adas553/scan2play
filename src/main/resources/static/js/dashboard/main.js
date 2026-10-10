/**
 * The DJ dashboard's script: loaded as a module by dashboard.html (`<script type="module">`, no bundler). Each part is a module of
 * its own with explicit imports; they talk through the events of events.js. A module runs once, in import order, after the page has
 * been parsed.
 *
 *   list-tools.js      sorting, search, filters, "Show more" of the lists (the standalone history page loads it alone)
 *   forms.js           AJAX forms, the party link's "copy"
 *   tabs.js            Panel / Queue / History, the History tab loaded in place
 *   polling.js         the guest queue every 3 s, the server's guest limits
 *   settings-toggle.js on a phone: the settings folded under one button
 *   settings-rows.js   the rows of "Więcej" in the settings: whether this browser gets the notifications
 *
 * "Zainstaluj aplikację" (install.js) and the notifications' switch (push.js) are on the page "Ustawienia imprezy" since 2026-10-10
 * (settings-page.js loads them with forms.js).
 *
 * Dependencies (DOM): <meta name="_csrf">, <meta name="_csrf_header">, <input id="partyCode">, <tbody id="song-list">,
 * <div id="queue-content">, <div id="history-content">.
 */
import './list-tools.js';
import './forms.js';
import './tabs.js';
import './polling.js';
import './settings-toggle.js';
import './settings-rows.js';
