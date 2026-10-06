/**
 * NOTIFICATIONS ON THIS DEVICE — the switch "🔔 Powiadomienia na tym urządzeniu" (Web Push).
 *
 * On: the browser asks the DJ for the permission, registers the service worker (/sw.js, scope /dj/), subscribes to its push service
 * with the server's key (data-public-key) and gives the subscription to the server (POST /dj/push/subscribe); off: the server forgets
 * it (POST /dj/push/unsubscribe) and the browser drops it. The permission and the subscription belong to this browser on this
 * device — a phone and a laptop are switched on separately.
 *
 * Shown only when the server has the keys (the template renders #pushSettings with them). Where the browser cannot take
 * notifications, the switch stays off with the reason: an iPhone outside the Home Screen app (Safari gives push only to a site added
 * to the Home Screen), a blocked permission, a browser without push.
 *
 * Dependencies (DOM): <div id="pushSettings" data-public-key>, <input id="pushToggle">, its [data-push-state] notes.
 */
import { csrfHeaders } from './common.js';

const section = document.getElementById('pushSettings');
const toggle = document.getElementById('pushToggle');

/** Shows the note of one state (or none) and hides the others. */
function showState(state) {
    section.querySelectorAll('[data-push-state]').forEach(function (note) {
        note.classList.toggle('d-none', note.dataset.pushState !== state);
    });
}

function isIos() {
    return /iPhone|iPad|iPod/.test(navigator.userAgent)
        || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
}

function isHomeScreenApp() {
    return window.matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;
}

/** The VAPID public key (base64url) as the bytes PushManager.subscribe wants. */
function keyBytes(base64Url) {
    const base64 = (base64Url + '='.repeat((4 - base64Url.length % 4) % 4)).replace(/-/g, '+').replace(/_/g, '/');
    return Uint8Array.from(atob(base64), function (c) { return c.charCodeAt(0); });
}

function sameKey(subscription, key) {
    const own = subscription.options && subscription.options.applicationServerKey;
    if (!own) return true;   // a browser that does not tell: assume it is ours
    const a = new Uint8Array(own);
    return a.length === key.length && a.every(function (b, i) { return b === key[i]; });
}

function post(url, body) {
    return fetch(url, {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/json' }, csrfHeaders()),
        body: JSON.stringify(body)
    }).then(function (response) {
        if (!response.ok) throw new Error('HTTP ' + response.status);
    });
}

async function switchOn(registration, key) {
    // First thing in the click: Safari asks for the permission only inside the user's gesture
    const permission = await Notification.requestPermission();
    if (permission !== 'granted') {
        toggle.checked = false;
        showState(permission === 'denied' ? 'denied' : null);
        return;
    }
    let subscription = null;
    try {
        subscription = await registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: key });
        await post('/dj/push/subscribe', subscription.toJSON());
        showState(null);
    } catch (e) {
        if (subscription) subscription.unsubscribe().catch(function () {});
        toggle.checked = false;
        showState('error');
    }
}

async function switchOff(registration) {
    const subscription = await registration.pushManager.getSubscription();
    if (subscription) {
        await post('/dj/push/unsubscribe', { endpoint: subscription.endpoint }).catch(function () {});
        await subscription.unsubscribe().catch(function () {});
    }
    showState(null);
}

async function init() {
    section.hidden = false;
    if (!('serviceWorker' in navigator) || !('PushManager' in window) || !('Notification' in window)) {
        showState(isIos() && !isHomeScreenApp() ? 'ios' : 'unsupported');
        return;
    }
    const key = keyBytes(section.dataset.publicKey);
    const registration = await navigator.serviceWorker.register('/sw.js', { scope: '/dj/' })
        .then(function () { return navigator.serviceWorker.ready; });

    let subscription = await registration.pushManager.getSubscription();
    if (subscription && !sameKey(subscription, key)) {
        // The server's key changed: this subscription can no longer be sent to
        await subscription.unsubscribe().catch(function () {});
        subscription = null;
    }
    if (subscription && Notification.permission === 'granted') {
        // Tell the server again: it may have forgotten it (a 410 once), or another DJ logged in on this browser since
        post('/dj/push/subscribe', subscription.toJSON()).catch(function () {});
    }
    toggle.checked = Boolean(subscription) && Notification.permission === 'granted';
    toggle.disabled = false;
    showState(Notification.permission === 'denied' ? 'denied' : null);

    toggle.addEventListener('change', function () {
        toggle.disabled = true;
        (toggle.checked ? switchOn(registration, key) : switchOff(registration))
            .finally(function () { toggle.disabled = false; });
    });
}

if (section && toggle) {
    init().catch(function () {
        toggle.checked = false;
        showState('unsupported');
    });
}
