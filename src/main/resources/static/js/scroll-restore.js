// Per-page scroll memory of the DJ pages (fragments/components.html, "scroll-restore-script"): loaded in <head> without defer, so
// it runs before the first paint. Hides the page, restores the scroll on DOMContentLoaded, then shows the page — no visible jump
// when the DJ switches between the panels. A file of its own, not an inline script, for the Content-Security-Policy (review 5.1).

// Hide page before first paint to prevent scroll restoration flash
document.documentElement.style.visibility = 'hidden';

// Safety net: reveal page even if DOMContentLoaded never fires
setTimeout(function() { document.documentElement.style.visibility = ''; }, 2000);

document.addEventListener("DOMContentLoaded", function () {
    var scrollKey = "scroll_" + window.location.pathname;
    var savedScroll = sessionStorage.getItem(scrollKey);

    // A link with an anchor (…/dj/dashboard#queue-content, #top) says where to go: the browser scrolls there.
    if (savedScroll !== null && !window.location.hash) {
        window.scrollTo({ top: parseInt(savedScroll, 10), behavior: 'instant' });
    }

    document.documentElement.style.visibility = '';

    window.addEventListener("beforeunload", function () {
        sessionStorage.setItem(scrollKey, window.scrollY);
    });
});
