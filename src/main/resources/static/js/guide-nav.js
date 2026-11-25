// The guide's jump bar (templates/guide*.html, #guideJump): marks the section being read (aria-current), keeps its chip in sight
// in the bar's row on a phone, and opens a folded "Więcej" (.s2p-guide-more) that a link points into. Without the script the bar
// still jumps (plain anchors) and the folds open by a tap; only the mark is missing.
(function () {
    'use strict';
    const nav = document.getElementById('guideJump');
    if (!nav) return;
    const row = nav.querySelector('.s2p-chips') || nav;
    const links = Array.from(nav.querySelectorAll('a[href^="#"]'));
    const sections = links.map(function (a) { return document.getElementById(a.getAttribute('href').slice(1)); });
    // a chip tapped wins while its section is still on the screen: a short last section cannot reach the top of the screen, and the
    // one above it would be marked instead
    let chosen = null;

    function current() {
        const line = nav.getBoundingClientRect().bottom + 24;
        if (chosen) {
            const top = chosen.getBoundingClientRect().top;
            if (top >= 0 && top < window.innerHeight * 0.6) return chosen;
            chosen = null;
        }
        let found = null;
        sections.forEach(function (section) {
            if (section && section.getBoundingClientRect().top <= line) found = section;
        });
        // the page's end: the last section, however short
        const end = document.documentElement.scrollHeight - window.innerHeight;
        if (found && end > 0 && window.scrollY >= end - 2) found = sections[sections.length - 1];
        return found;
    }

    function mark() {
        const section = current();
        let on = null;
        links.forEach(function (a, i) {
            if (section && sections[i] === section) {
                on = a;
                if (a.getAttribute('aria-current') !== 'location') a.setAttribute('aria-current', 'location');
            } else if (a.hasAttribute('aria-current')) {
                a.removeAttribute('aria-current');
            }
        });
        // a phone's one row: the marked chip slid into sight (the row alone moves, never the page)
        if (on && row.scrollWidth > row.clientWidth) {
            const r = row.getBoundingClientRect();
            const c = on.getBoundingClientRect();
            if (c.left < r.left || c.right > r.right) {
                row.scrollLeft += c.left - r.left - (r.width - c.width) / 2;
            }
        }
    }

    let queued = false;
    function later() {
        if (queued) return;
        queued = true;
        requestAnimationFrame(function () { queued = false; mark(); });
    }

    links.forEach(function (a, i) {
        a.addEventListener('click', function () { chosen = sections[i]; later(); });
    });
    window.addEventListener('scroll', later, {passive: true});
    window.addEventListener('resize', later);

    // a link into a fold (#guidePicture4, a jump from the panel): the fold opened, then the place shown under the bar
    function openTo() {
        const id = decodeURIComponent(location.hash.slice(1));
        const target = id && document.getElementById(id);
        if (!target) return;
        const fold = target.closest('details.s2p-guide-more');
        if (fold && !fold.open) {
            fold.open = true;
            target.scrollIntoView();
        }
    }
    window.addEventListener('hashchange', openTo);
    openTo();
    mark();
})();
