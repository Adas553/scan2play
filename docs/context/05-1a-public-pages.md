> Part of `PROJECT_CONTEXT.md` (the index, at the repo root: what is in each part) — Section 5, 5.1. Keep it to what is true now.

## 5. User Flows

### 5.1 DJ Flow

Landing page `/` (2026-10-17, an idea from Rekwest — its features, never its look or texts): the title, "Zbieraj prośby gości" and "Jak
działa Scan2Play", beside them the DJ's panel in a laptop and the guest's page in a phone — real pictures of the app, of a made-up
party, in the page's language — the English ones with songs known everywhere and DJ Alex (2026-10-18), the Polish ones with DJ Kuba — (`static/images/landing/{dashboard,guest}-{pl,en}.webp`, written by `src/test/browser/screenshots.py`
from the pages `GuestPageRenderTest` / `DashboardPageRenderTest` render, `theLandingPagesPicture_…`; run it again after a change of
the look; the panel shows the guests' dedications, the phone the folded "💌 Dodaj dedykację") —, then three steps, what one gets
(six cards: the vibe — with no repeats —, the guest's words, the votes, the dedications, the hosts' list, the phone and the staff; no
"PRO" on a card; "Ile to kosztuje?" names Pro and links `/pro` only while the plans are enforced — `HomeController`'s
`plansEnforced`; before, "Nic. Scan2Play jest za darmo…"; "Zobacz cennik →" to `/pro` either way, and "Cennik" beside the language at the top), for whom (wedding DJs, clubs, pubs, karaoke — the first three
cards lead to their pages "Dla kogo", below; karaoke has none yet), seven questions (`details`, no script; one of them: the tips go 100% to the DJ, Scan2Play takes no commission — the owner, 2026-10-30) and the start again; no word of Pro while the plans are not enforced.
**The pages "Dla kogo"** (`ForWhomController`, `for-whom.html`, 2026-10-21; Next 7): `/dj-weselny`, `/kluby`, `/puby` — for search
engines (in the sitemap, Polish at the address, English with `?lang=en`, canonical and `hreflang` like the other public pages) and for
a link sent to a DJ or a pub's owner. Where one is, at once (the owner: from the landing page's card the page looked the same): breadcrumbs (the
logo, "Dla kogo", this kind) and the kind with its card's icon above the heading. The landing page's hero with the same pictures
(`landing :: devices`), then four steps and four features under headings of that kind of party ("na weselu", "w klubie"…) and up to
three questions (the pubs' two; `forWhom.KEY.*` — each page its own texts, not one page with a word swapped: a
search engine counts that a doorway page; `ForWhomPagesWebTest` checks no paragraph repeats), the start again and the other two kinds
as cards that are links (the whole card, `.stretched-link`). Every link in the page's language (`?lang=en` on an English page). No
form (no session, no CSRF token) and no word of Pro while the plans are not enforced (the owner, 2026-10-21); once they are, the
steps and features of the Pro plan carry "PRO" (`ForWhomController.Page.pro`) and "Ile to kosztuje?" says what Free has and what Pro
adds (`forWhom.pub.faq2.a.plans`; `ForWhomPagesPlansWebTest`) — the pages promise nothing of Pro as free (the owner, 2026-10-24). No
karaoke page: nothing in the app is made for it yet (the owner, 2026-10-21).
