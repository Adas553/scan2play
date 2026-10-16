// A tap on a DJ's notification (sw.js, review 2026-10-14): the server's address is that party's panel, /dj/dashboard?party=CODE, and
// the service worker brings forward the window that shows it, else takes a window of the DJ's pages there, else opens a new one —
// before, any /dj/ window came forward: for a person of two staffs often the other party's panel.
//
// A headless browser does not run a service worker's notificationclick (no notification to tap), so the choice is a function of
// sw.js, openThePanel(windows, url, openWindow); the REAL /sw.js is loaded into the page as a script (its listeners on the page's
// window do nothing there) and the function is given windows that record what is done with them.
(function () {
    const ORIGIN = location.origin;

    /** A window as clients.matchAll gives it: its address; what is done with it goes into {@code done}. */
    function client(done, path, options) {
        const it = { url: ORIGIN + path };
        it.focus = function () { done.push('focus ' + path); return Promise.resolve(it); };
        if (!(options && options.noNavigate)) {
            it.navigate = function (to) {
                done.push('navigate ' + path + ' → ' + new URL(to).pathname + new URL(to).search);
                if (options && options.navigateFails) return Promise.reject(new TypeError('not controlled'));
                // null: the page went to another origin (a navigation it cannot follow) — the window is focused as it was
                return Promise.resolve(options && options.navigateNull ? null : client(done, new URL(to).pathname + new URL(to).search));
            };
        }
        return it;
    }

    /** What a tap does with these windows, for the server's address {@code path}. */
    async function tap(paths, path, options) {
        const done = [];
        const windows = paths.map(function (p) { return client(done, p, (options || {})[p]); });
        await openThePanel(windows, new URL(path, ORIGIN), function (to) {
            done.push('open ' + new URL(to).pathname + new URL(to).search);
            return Promise.resolve(null);
        });
        return done;
    }

    S2P.scenario({
        name: 'notification-opens-the-partys-panel',
        title: 'a tap on a notification: that party\'s panel comes forward; else a DJ\'s window goes there; else a new one opens',
        run: async function (t) {
            await new Promise(function (resolve, reject) {
                const script = document.createElement('script');
                script.src = '/sw.js';
                script.onload = resolve;
                script.onerror = function () { reject(new Error('/sw.js did not load')); };
                document.head.appendChild(script);
            });
            t.step('sw.js gives the choice as a function', typeof window.openThePanel, 'function');
            if (typeof window.openThePanel !== 'function') return;

            const panel = '/dj/dashboard?party=ABC12';
            t.step('the window with that party\'s panel comes forward — not the other party\'s, nothing else moves',
                await tap(['/dj/dashboard?party=OTHER', '/dj/dashboard?party=ABC12', '/dj/settings?party=ABC12'], panel),
                ['focus /dj/dashboard?party=ABC12']);
            t.step('no window with it: a DJ\'s window goes there and comes forward',
                await tap(['/p/ABC12', '/dj/dashboard?party=OTHER'], panel),
                ['navigate /dj/dashboard?party=OTHER → /dj/dashboard?party=ABC12', 'focus /dj/dashboard?party=ABC12']);
            t.step('a window the worker cannot move (not under its control): a new one opens',
                await tap(['/dj/dashboard?party=OTHER'], panel, { '/dj/dashboard?party=OTHER': { navigateFails: true } }),
                ['navigate /dj/dashboard?party=OTHER → /dj/dashboard?party=ABC12', 'open /dj/dashboard?party=ABC12']);
            t.step('moved, but the browser gives no window back: the one moved comes forward',
                await tap(['/dj/history?party=ABC12'], panel, { '/dj/history?party=ABC12': { navigateNull: true } }),
                ['navigate /dj/history?party=ABC12 → /dj/dashboard?party=ABC12', 'focus /dj/history?party=ABC12']);
            t.step('only the guests\' pages open: a new window', await tap(['/p/ABC12', '/'], panel), ['open /dj/dashboard?party=ABC12']);
            t.step('no window at all: a new window', await tap([], panel), ['open /dj/dashboard?party=ABC12']);
            t.step('an address without a party (an older notification): any panel comes forward',
                await tap(['/dj/settings', '/dj/dashboard?party=OTHER'], '/dj/dashboard'), ['focus /dj/dashboard?party=OTHER']);
        }
    });
})();
