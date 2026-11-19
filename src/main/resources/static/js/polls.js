// The DJ's page of "Pytanie do gości" (polls.html, DjPollsController): the results of the question asked now fetched again every few
// seconds while the page is shown — the guests' answers come in as the DJ watches. Put in place only when they changed; a failed fetch
// keeps them as they were. The login ended (the answer a redirect): no more asking. The question ended meanwhile (someone of the
// staff on another phone, or by itself): the page again, as it is now. A file of its own for the Content-Security-Policy.
(function () {
    const EVERY_MS = 3000;
    const live = document.querySelector('[data-poll-live]');
    if (!live) return;
    let last = null;
    let timer = null;

    async function refresh() {
        if (document.visibilityState !== 'visible' || timer === null) return;
        try {
            const response = await fetch(live.dataset.pollLive, { headers: { 'Accept': 'text/html' }, cache: 'no-store' });
            if (response.redirected) {
                clearInterval(timer);
                timer = null;
                return;
            }
            if (!response.ok) return;
            const html = (await response.text()).trim();
            const template = document.createElement('template');
            template.innerHTML = html;
            if (!template.content.querySelector('.s2p-poll-results')) return;
            if (template.content.querySelector('[data-poll-ended]')) {
                clearInterval(timer);
                timer = null;
                window.location.reload();
                return;
            }
            if (html === last) return;
            last = html;
            live.replaceChildren(template.content);
        } catch (e) {
            // offline for a moment: the results stay, the next fetch tries again
        }
    }

    timer = setInterval(refresh, EVERY_MS);
    document.addEventListener('visibilitychange', refresh);
})();
