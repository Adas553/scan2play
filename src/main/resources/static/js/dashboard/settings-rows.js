/**
 * The folded card "To urządzenie" of the page "Ustawienia imprezy" (settings-page.js) says whether this browser gets the
 * notifications of new requests — "Powiadomienia wł." /
 * "wył." — the way push.js finds it: a subscription of the service
 * worker of /dj/ (scope /dj/) with notifications allowed. Shown only when the server has the keys (the template renders the state's
 * element with them); a browser without push says nothing.
 *
 * Dependencies (DOM): [data-device-state] with data-on / data-off.
 */
const state = document.querySelector('[data-device-state]');

async function showDeviceState() {
    if (!state || !('serviceWorker' in navigator) || !('PushManager' in window) || !('Notification' in window)) return;
    let on = false;
    try {
        const registration = await navigator.serviceWorker.getRegistration('/dj/');
        const subscription = registration ? await registration.pushManager.getSubscription() : null;
        on = subscription !== null && Notification.permission === 'granted';
    } catch (e) {
        return;   // the browser would not tell: say nothing rather than something wrong
    }
    state.textContent = on ? state.dataset.on : state.dataset.off;
}

showDeviceState();
