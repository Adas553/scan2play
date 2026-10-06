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

self.addEventListener('notificationclick', function (event) {
    event.notification.close();
    const url = (event.notification.data && event.notification.data.url) || '/dj/dashboard';
    event.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(function (windows) {
        const dashboard = windows.find(function (client) {
            return new URL(client.url).pathname.indexOf('/dj/') === 0 && 'focus' in client;
        });
        return dashboard ? dashboard.focus() : self.clients.openWindow(url);
    }));
});
