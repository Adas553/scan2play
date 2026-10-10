// "ZAINSTALUJ APLIKACJĘ" (js/dashboard/install.js) on the real page "Ustawienia imprezy" (since 2026-10-10; on the panel before). A
// headless Chrome on the stand-in offers no install (the stand-in serves no manifest), so the browser's offer — the `beforeinstallprompt` event with its prompt() and userChoice — is made by the
// scenario; an iPhone and the installed app are set up by this file before the dashboard's modules run (classic scripts at the end
// of <body> run before the deferred modules).
(function () {
    const scenarioName = new URLSearchParams(location.search).get('scenario') || '';
    if (scenarioName.indexOf('install-') !== 0) return;

    if (scenarioName === 'install-iphone-shows-the-steps') {
        Object.defineProperty(navigator, 'userAgent', { configurable: true,
            get: function () { return 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Version/18.0 Mobile/15E148 Safari/604.1'; } });
    }
    if (scenarioName === 'install-not-in-the-installed-app') {
        const realMatchMedia = window.matchMedia.bind(window);
        window.matchMedia = function (query) {
            return query === '(display-mode: standalone)' ? { matches: true, media: query } : realMatchMedia(query);
        };
    }

    /** The browser's offer to install, as Chrome sends it; it records its prompt() and answers with {outcome}. */
    function offer(outcome) {
        const event = new Event('beforeinstallprompt', { cancelable: true });
        event.prompted = 0;
        event.prompt = function () { event.prompted++; return Promise.resolve(); };
        event.userChoice = Promise.resolve({ outcome: outcome, platform: 'web' });
        window.dispatchEvent(event);
        return event;
    }

    const section = function () { return document.getElementById('installApp'); };
    const button = function () { return document.getElementById('installAppBtn'); };
    const note = function (name) { return document.querySelector('#installApp [data-install-note="' + name + '"]'); };
    const steps = function () { return note('ios'); };

    S2P.scenario({
        name: 'install-chrome-asks-when-the-dj-wants',
        page: 'settings',   // "To urządzenie" moved there from the panel (2026-10-10)
        title: 'install the app: hidden until the browser offers it; the button opens the browser\'s install window; installed, it goes',
        run: async function (t) {
            await t.sleep(300);
            t.check('no offer of the browser: no button', section().hidden);

            const event = offer('accepted');
            t.check('the browser\'s own mini-infobar is held back (preventDefault)', event.defaultPrevented);
            t.check('offered: the button shows', !section().hidden);

            button().click();
            await t.sleep(50);
            t.step('the click opens the browser\'s install window', event.prompted, 1);
            t.check('no notes under the button', steps().classList.contains('d-none') && note('menu').classList.contains('d-none'));

            window.dispatchEvent(new Event('appinstalled'));
            t.check('installed: the button goes', section().hidden);
        }
    });

    S2P.scenario({
        name: 'install-closed-then-the-menu',
        page: 'settings',   // "To urządzenie" moved there from the panel (2026-10-10)
        title: 'install the app: the install window closed without installing ("Dowiedz się więcej") — the button stays; clicked again it says where the browser\'s menu has it, never the iPhone\'s steps',
        run: async function (t) {
            await t.sleep(300);
            const event = offer('dismissed');
            button().click();
            await t.sleep(50);
            t.step('the install window was opened once', event.prompted, 1);
            t.check('closed: the button stays', !section().hidden);

            button().click();
            t.step('the offer is used up: not opened again', event.prompted, 1);
            t.check('the second click says: the browser\'s menu', !note('menu').classList.contains('d-none'));
            t.check('… not the iPhone\'s steps on a computer', steps().classList.contains('d-none'));

            const again = offer('accepted');
            t.check('a new offer of the browser: the note goes', note('menu').classList.contains('d-none'));
            button().click();
            t.step('… and the button opens the install window again', again.prompted, 1);
        }
    });

    S2P.scenario({
        name: 'install-iphone-shows-the-steps',
        page: 'settings',   // "To urządzenie" moved there from the panel (2026-10-10)
        title: 'install the app on an iPhone: Safari has no install window — the button shows the steps (Udostępnij → Do ekranu początkowego)',
        run: async function (t) {
            await t.waitFor(function () { return !section().hidden; }, 'the button on an iPhone', 3000);
            t.check('the steps are not shown before the click', steps().classList.contains('d-none'));
            button().click();
            t.check('the click shows the steps', !steps().classList.contains('d-none'));
            t.check('… not the note of the browser\'s menu', note('menu').classList.contains('d-none'));
            t.check('… which say where to tap', steps().textContent.indexOf('Do ekranu początkowego') >= 0);
        }
    });

    S2P.scenario({
        name: 'install-not-in-the-installed-app',
        page: 'settings',   // "To urządzenie" moved there from the panel (2026-10-10)
        title: 'install the app: in the installed app itself the button never shows, even if an offer comes',
        run: async function (t) {
            await t.sleep(300);
            const event = offer('accepted');
            t.check('no button in the app', section().hidden);
            t.check('the browser\'s offer is left alone', !event.defaultPrevented);
        }
    });
})();
