/**
 * The service worker of the DJ's notifications (Web Push; registered by js/dashboard/push.js with the scope /dj/). It only shows
 * what the server sends (PushNotificationService) and opens the dashboard on a tap — no cache, no fetch handler: the pages work as
 * without it.
 *
 * The notifications of one party are folded into one (the same tag): the first says "🎵 Nowa prośba — <song>", the next ones while it
 * is still on the screen "🎵 Nowe prośby: 3 — <the latest song>". Every push shows a notification (Safari withdraws the permission
 * of a site whose push shows none).
 */
self.addEventListener('install', function () {
    self.skipWaiting();
});

self.addEventListener('activate', function (event) {
    event.waitUntil(self.clients.claim());
});

self.addEventListener('push', function (event) {
    let message = {};
    try {
        message = event.data ? event.data.json() : {};
    } catch (e) {
        message = {};
    }
    const tag = message.tag || 'scan2play';
    event.waitUntil(self.registration.getNotifications({ tag: tag }).then(function (shown) {
        const count = shown.reduce(function (most, notification) {
            return Math.max(most, (notification.data && notification.data.count) || 1);
        }, 0) + 1;
        const title = count > 1 && message.titleMany
            ? message.titleMany.replace('{0}', String(count))
            : (message.title || 'Scan2Play');
        return self.registration.showNotification(title, {
            body: message.body || '',
            tag: tag,
            renotify: true,
            icon: '/images/icon-192.png',
            // The small icon of the status bar: Android draws only its transparency, in white — the mark alone, no tile
            badge: '/images/badge-96.png',
            vibrate: [200, 100, 200],
            data: { count: count, url: message.url || '/dj/dashboard' }
        });
    }));
});

// A tap leads to the panel of the notification's party (the server's url: /dj/dashboard?party=CODE): a window that shows it already is
// brought forward; else a window of the DJ's pages goes there; else a new one opens. Before, any /dj/ window was brought forward —
// for a person of two staffs often another party's panel (review 2026-10-14).
self.addEventListener('notificationclick', function (event) {
    event.notification.close();
    const url = new URL((event.notification.data && event.notification.data.url) || '/dj/dashboard', self.location.origin);
    event.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(function (windows) {
        return openThePanel(windows, url, function (href) { return self.clients.openWindow(href); });
    }));
});

/**
 * Which window a tap shows, and how: {@code windows} as clients.matchAll gives them, {@code url} the notification's address (a URL),
 * {@code openWindow} opens a new one. A function of its own so that the browser tests can run it (a headless browser runs no
 * notificationclick; src/test/browser/scenarios/sw-window.js loads this file into a page).
 */
function openThePanel(windows, url, openWindow) {
    const party = url.searchParams.get('party');
    const ours = windows.filter(function (client) {
        return new URL(client.url).pathname.indexOf('/dj/') === 0 && 'focus' in client;
    });
    const there = ours.find(function (client) {
        const at = new URL(client.url);
        return at.pathname === url.pathname && (!party || at.searchParams.get('party') === party);
    });
    if (there) return there.focus();
    const other = ours.find(function (client) { return 'navigate' in client; });
    if (other) {
        return other.navigate(url.href).then(function (moved) { return (moved || other).focus(); })
            .catch(function () { return openWindow(url.href); });
    }
    return openWindow(url.href);
}
