// NOTIFICATIONS ON THIS DEVICE (js/dashboard/push.js): the switch "Powiadomienia o prośbach" on the real page "Ustawienia imprezy"
// (since 2026-10-10; on the panel before), rendered with a VAPID public key (SettingsPageRenderTest).
//
// A headless Chrome has no push service to subscribe to, so for these scenarios the browser's side is a stand-in, put in place by
// this file before the dashboard's modules run (classic scripts at the end of <body> run before the deferred modules): the
// permission (Notification.permission / requestPermission), the service worker's registration and its PushManager. What is real:
// the page, push.js and its requests to the server (the stand-in server answers /dj/push/subscribe and /unsubscribe).
(function () {
    const scenarioName = new URLSearchParams(location.search).get('scenario') || '';
    if (scenarioName.indexOf('push-') !== 0) return;

    const SUBSCRIBE = 'POST /dj/push/subscribe';
    const UNSUBSCRIBE = 'POST /dj/push/unsubscribe';
    const ENDPOINT = 'https://fcm.googleapis.com/fcm/send/scenario-device';

    // ---- the browser's side, as the scenario sets it ----
    const fake = {
        permission: 'default',      // what Notification.permission says
        answer: 'granted',          // what requestPermission() answers
        permissionAsked: 0,
        subscribedWith: null,       // the applicationServerKey given to subscribe()
        subscription: null,         // what getSubscription() gives
        unsubscribed: 0
    };
    window.S2P_PUSH = fake;

    // The page's own requests to /dj/push/*, counted here: push.js may send one while the dashboard loads — before the harness
    // resets the stand-in's log for the scenario
    fake.posts = [];
    const realFetch = window.fetch;
    window.fetch = function (url, init) {
        if (String(url).indexOf('/dj/push/') === 0) fake.posts.push(String(url));
        return realFetch.apply(this, arguments);
    };

    function subscription(key) {
        return {
            endpoint: ENDPOINT,
            options: { applicationServerKey: key ? key.buffer : null },
            toJSON: function () { return { endpoint: ENDPOINT, expirationTime: null, keys: { p256dh: 'BKey', auth: 'auth1' } }; },
            unsubscribe: function () { fake.unsubscribed++; fake.subscription = null; return Promise.resolve(true); }
        };
    }
    fake.makeSubscription = subscription;

    const registration = {
        pushManager: {
            subscribe: function (options) {
                fake.subscribedWith = new Uint8Array(options.applicationServerKey);
                fake.subscription = subscription(fake.subscribedWith);
                return Promise.resolve(fake.subscription);
            },
            getSubscription: function () { return Promise.resolve(fake.subscription); }
        }
    };

    if (scenarioName === 'push-iphone-outside-the-home-screen') {
        // Safari on an iPhone, in a tab: no PushManager (it is given only to a site opened from the Home Screen)
        Object.defineProperty(navigator, 'userAgent', { configurable: true,
            get: function () { return 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Version/18.0 Mobile/15E148 Safari/604.1'; } });
        delete window.PushManager;
    } else {
        Object.defineProperty(navigator.serviceWorker, 'register', { configurable: true,
            value: function (url, options) { fake.registered = { url: url, scope: options && options.scope }; return Promise.resolve(registration); } });
        Object.defineProperty(navigator.serviceWorker, 'ready', { configurable: true, get: function () { return Promise.resolve(registration); } });
        Object.defineProperty(Notification, 'permission', { configurable: true, get: function () { return fake.permission; } });
        Object.defineProperty(Notification, 'requestPermission', { configurable: true,
            value: function () { fake.permissionAsked++; fake.permission = fake.answer; return Promise.resolve(fake.answer); } });
    }
    // (on the page "Ustawienia imprezy": the harness opens the panel first and loads that page from it — nothing to do there yet)
    if (scenarioName === 'push-already-on-here' && document.getElementById('pushSettings')) {
        // This browser switched them on before: the permission is there, and the subscription with the server's key
        // (#pushSettings is above this script: parsed already)
        fake.permission = 'granted';
        fake.subscription = subscription(pageKey());
    }

    /** The page's key as bytes, as push.js turns it (base64url → bytes). */
    function pageKey() {
        const b64url = document.getElementById('pushSettings').dataset.publicKey;
        const b64 = (b64url + '='.repeat((4 - b64url.length % 4) % 4)).replace(/-/g, '+').replace(/_/g, '/');
        return Uint8Array.from(atob(b64), function (c) { return c.charCodeAt(0); });
    }

    const toggle = function () { return document.getElementById('pushToggle'); };
    const visibleNote = function () {
        const shown = Array.from(document.querySelectorAll('#pushSettings [data-push-state]'))
            .filter(function (note) { return !note.classList.contains('d-none'); });
        return shown.map(function (note) { return note.dataset.pushState; });
    };
    const ready = function (t) {
        return t.waitFor(function () { return !document.getElementById('pushSettings').hidden && !toggle().disabled; },
            'the switch to be ready', 4000);
    };

    S2P.scenario({
        name: 'push-switch-on-and-off',
        page: 'settings',   // "To urządzenie" moved there from the panel (2026-10-10)
        title: 'notifications: the switch asks for the permission, subscribes with the server\'s key, tells the server; off forgets it',
        run: async function (t) {
            await ready(t);
            t.check('the switch shows, off', !toggle().checked);
            t.step('the service worker is registered for the dashboard', fake.registered, { url: '/sw.js', scope: '/dj/' });

            toggle().click();
            await t.waitFor(function () { return !toggle().disabled; }, 'switching on', 3000);
            t.step('the permission is asked once', fake.permissionAsked, 1);
            const key = pageKey();
            t.check('subscribed with the server\'s key (65 bytes, uncompressed point)',
                fake.subscribedWith && fake.subscribedWith.length === 65 && fake.subscribedWith[0] === 4
                && fake.subscribedWith.every(function (b, i) { return b === key[i]; }));
            const sent = await t.stand.requests(SUBSCRIBE);
            t.step('the server is given the subscription', sent.length === 1 && sent[0].q.endpoint, ENDPOINT);
            t.step('… with its keys', sent.length === 1 && sent[0].q.keys, { p256dh: 'BKey', auth: 'auth1' });
            t.check('the switch is on', toggle().checked);
            t.step('no note', visibleNote(), []);

            toggle().click();
            await t.waitFor(function () { return !toggle().disabled; }, 'switching off', 3000);
            const forgotten = await t.stand.requests(UNSUBSCRIBE);
            t.step('off: the server forgets this device', forgotten.length === 1 && forgotten[0].q.endpoint, ENDPOINT);
            t.step('… and the browser drops its subscription', fake.unsubscribed, 1);
            t.check('the switch is off', !toggle().checked);
        }
    });

    S2P.scenario({
        name: 'push-permission-denied',
        page: 'settings',   // "To urządzenie" moved there from the panel (2026-10-10)
        title: 'notifications: the DJ blocks them — the switch goes back off with the note how to unblock, nothing is sent',
        run: async function (t) {
            fake.answer = 'denied';
            await ready(t);
            toggle().click();
            await t.waitFor(function () { return !toggle().disabled; }, 'the answer', 3000);
            t.check('the switch is off again', !toggle().checked);
            t.step('the note says they are blocked', visibleNote(), ['denied']);
            t.step('nothing is subscribed or sent', [fake.subscribedWith, await t.stand.count(SUBSCRIBE)], [null, 0]);
        }
    });

    S2P.scenario({
        name: 'push-server-refuses',
        page: 'settings',   // "To urządzenie" moved there from the panel (2026-10-10)
        title: 'notifications: the server refuses the subscription — the switch goes back off, the browser drops it, an error note',
        setup: { pushStatus: 400 },
        run: async function (t) {
            await ready(t);
            toggle().click();
            await t.waitFor(function () { return !toggle().disabled; }, 'the answer', 3000);
            t.step('the server was asked', await t.stand.count(SUBSCRIBE), 1);
            t.check('the switch is off again', !toggle().checked);
            t.step('the browser drops the subscription the server did not take', fake.unsubscribed, 1);
            t.step('the error note shows', visibleNote(), ['error']);
        }
    });

    S2P.scenario({
        name: 'push-iphone-outside-the-home-screen',
        page: 'settings',   // "To urządzenie" moved there from the panel (2026-10-10)
        title: 'notifications on an iPhone in a Safari tab: no switch to turn on, the note how to add Scan2Play to the Home Screen',
        run: async function (t) {
            await t.waitFor(function () { return !document.getElementById('pushSettings').hidden; }, 'the section', 4000);
            t.check('the switch stays off and cannot be used', !toggle().checked && toggle().disabled);
            t.step('the note says how (Udostępnij → Do ekranu początkowego)', visibleNote(), ['ios']);
        }
    });

    S2P.scenario({
        name: 'push-already-on-here',
        page: 'settings',   // "To urządzenie" moved there from the panel (2026-10-10)
        title: 'notifications switched on before in this browser: the switch shows on, and the server is told again (it may have forgotten)',
        run: async function (t) {
            await ready(t);
            t.check('the switch shows on', toggle().checked);
            t.step('the server is told again', fake.posts, ['/dj/push/subscribe']);
            t.step('nothing is asked or subscribed anew', [fake.permissionAsked, fake.subscribedWith], [0, null]);
        }
    });
})();
