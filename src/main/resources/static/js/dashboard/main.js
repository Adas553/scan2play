/**
 * The DJ dashboard's script: loaded as a module by dashboard.html (`<script type="module">`, no bundler). Each part is a module of
 * its own with explicit imports; they and the player (youtube-autopilot.js, a YouTube party only) talk through the events of
 * events.js. A module runs once, in import order, after the page has been parsed.
 *
 *   list-tools.js      sorting, search, filters, "Show more" of the lists (the standalone history page loads it alone)
 *   fallback-queue.js  the background playlist: "up next", moves, drag, skip, Stop, shuffle, the import result
 *   forms.js           AJAX forms, the Auto-Pilot switch, the party link's "copy"
 *   tabs.js            Panel / Queue / History, the History tab loaded in place
 *   polling.js         the guest queue every 3 s, the server's guest limits
 *
 * Dependencies (DOM): <meta name="_csrf">, <meta name="_csrf_header">, <input id="partyCode">, <div id="yt-player-card"> (a YouTube
 * party), <tbody id="song-list">, <div id="queue-content">, <div id="history-content">, <div id="fallbackQueue"> (YouTube only).
 */
import './list-tools.js';
import './fallback-queue.js';
import './forms.js';
import './tabs.js';
import './polling.js';
