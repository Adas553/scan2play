/**
 * The page "Ustawienia imprezy" (/dj/settings, settings.html; the design review, 2026-10-10): the panel's own modules for what moved
 * there from the panel's settings — its forms sent in the background with "✓" (forms.js, the party code with every form), the
 * notifications on this device (push.js), "Zainstaluj aplikację" (install.js), the DJ's library sent as a file (library.js), the DJ's logo (logo.js), the
 * folded card "To urządzenie" saying whether this browser gets the notifications (settings-rows.js, as the panel's row), the quick
 * choices of "Godziny imprezy" (schedule.js, V55), the cards answered by the server in place (cards-in-place.js: the party's hours, the
 * kiosk). No queue, so no polling.
 *
 * Dependencies (DOM): <meta name="_csrf">, <meta name="_csrf_header">, <input id="partyCode">.
 */
import './forms.js';
import './cards-in-place.js';
import './install.js';
import './library.js';
import './logo.js';
import './push.js';
import './schedule.js';
import './settings-rows.js';
