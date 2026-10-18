// The screen for a TV (tv.html, TvScreenController): the list of the most wanted songs fetched again every few seconds — put in
// place only when it changed — and the screen kept awake while the page is shown (the Screen Wake Lock API, where the browser has
// it). A failed fetch, or an answer that is not the list (a Wi-Fi login page), keeps the list on the screen; the next one tries again.
// A file of its own, not an inline script, for the Content-Security-Policy. Loaded at the end of the body, so the list is there.
(function () {
    const EVERY_MS = 10000;
    let list = document.getElementById('tvList');
    if (!list) return;
    const url = list.dataset.url;
    let last = null;

    async function refresh() {
        try {
            const response = await fetch(url, { headers: { 'Accept': 'text/html' }, cache: 'no-store' });
            if (!response.ok) return;
            const html = (await response.text()).trim();
            if (!html.startsWith('<section id="tvList"') || html === last) return;
            last = html;
            const template = document.createElement('template');
            template.innerHTML = html;
            const next = template.content.firstElementChild;
            list.replaceWith(next);
            list = next;
        } catch (e) {
            // offline for a moment: the list stays, the next fetch tries again
        }
    }

    async function keepAwake() {
        if (!('wakeLock' in navigator) || document.visibilityState !== 'visible') return;
        try {
            await navigator.wakeLock.request('screen');   // let go by the browser when the page is hidden: asked again on coming back
        } catch (e) {
            // not allowed here: the TV's own settings decide
        }
    }

    document.addEventListener('visibilitychange', function () {
        if (document.visibilityState === 'visible') {
            keepAwake();
            refresh();
        }
    });
    keepAwake();
    setInterval(refresh, EVERY_MS);
})();
