/**
 * THE ROWS OF "WIĘCEJ" in the panel's settings (the design review, 2026-10-10): the row "To urządzenie" says whether this browser
 * gets the notifications of new requests — "Powiadomienia wł." / "wył." — the way push.js finds it: a subscription of the service
 * worker of /dj/ (scope /dj/) with notifications allowed. Shown only when the server has the keys (the template renders the state's
 * element with them); a browser without push says nothing.
 *
 * The state of the row of the limits is kept by polling.js (X-Guest-Limits-Use), the others are the server's.
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
