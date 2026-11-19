// The DJ's page of "Pytanie do gości" (polls.html, DjPollsController): the results of the question asked now fetched again every few
// seconds while the page is shown — the guests' answers come in as the DJ watches. Put in place only when they changed; a failed fetch,
// or an answer that is not the results (the login ended), keeps them as they were. A file of its own for the Content-Security-Policy.
(function () {
    const EVERY_MS = 3000;
    const live = document.querySelector('[data-poll-live]');
    if (!live) return;
    let last = null;

    async function refresh() {
        if (document.visibilityState !== 'visible') return;
        try {
            const response = await fetch(live.dataset.pollLive, { headers: { 'Accept': 'text/html' }, cache: 'no-store' });
            if (!response.ok || response.redirected) return;
            const html = (await response.text()).trim();
            if (!html.startsWith('<ul') || html === last) return;
            last = html;
            const template = document.createElement('template');
            template.innerHTML = html;
            live.replaceChildren(template.content);
        } catch (e) {
            // offline for a moment: the results stay, the next fetch tries again
        }
    }

    setInterval(refresh, EVERY_MS);
    document.addEventListener('visibilitychange', refresh);
})();
