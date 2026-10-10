/**
 * The page "Ustawienia imprezy" (/dj/settings, settings.html; the design review, 2026-10-10): the panel's own modules for what moved
 * there from the panel's settings — its forms sent in the background with "✓" (forms.js, the party code with every form), the
 * notifications on this device (push.js), "Zainstaluj aplikację" (install.js). No queue, so no polling.
 *
 * Dependencies (DOM): <meta name="_csrf">, <meta name="_csrf_header">, <input id="partyCode">.
 */
import './forms.js';
import './install.js';
import './push.js';
