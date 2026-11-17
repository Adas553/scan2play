> Part of `PROJECT_CONTEXT.md` (the index, at the repo root: what is in each part) — Section 5, 5.1. Keep it to what is true now.

## 5. User Flows

### 5.1 DJ Flow

Landing page `/` (2026-10-17, an idea from Rekwest — its features, never its look or texts): the title, "Zbieraj prośby gości" and "Jak
działa Scan2Play", beside them the DJ's panel in a laptop and the guest's page in a phone — real pictures of the app, of a made-up
party, in the page's language — the English ones with songs known everywhere and DJ Alex (2026-10-18), the Polish ones with DJ Kuba — (`static/images/landing/{dashboard,guest}-{pl,en}.webp`, written by `src/test/browser/screenshots.py`
from the pages `GuestPageRenderTest` / `DashboardPageRenderTest` render, `theLandingPagesPicture_…`; run it again after a change of
the look; the panel shows the guests' dedications, the phone the folded "💌 Dodaj dedykację") —; the title the DJ's gain ("Mniej
ludzi przy konsoli. Więcej kontroli nad salą."), the AI only further down; then "Ty decydujesz" (`landing :: control`, a DJ's first
fear — an AI turning guests down in their name: every request let through seen before it is played, with the guest's words and
"⚠ Sprawdź", the turned-down ones in the history; the guest's answer at once; nothing lost when the AI is down; also on the pages
"Dla kogo", so no word of a console or a DJ — the pubs' page is for a pub's owner), three steps, what one gets (six cards,
in the order a DJ counts them in money: the guests who see the DJ's profiles, the hosts' list and the guests' songs (the wishes), the votes, the vibe —
with no repeats —, the dedications, the phone and the staff; no "PRO" on a card), "Pierwsza impreza ze Scan2Play" (the print, one line to say on
the mic — a text, no feature —, the TV screen); "Ile to kosztuje?" names Pro and links `/pro` only while the plans are enforced — `HomeController`'s
`plansEnforced`; before, "Nic. Scan2Play jest za darmo…"; "Zobacz cennik →" to `/pro` either way, and "Cennik" beside the language at the top), for whom (wedding DJs, clubs, pubs, karaoke — the first three
cards lead to their pages "Dla kogo", below; karaoke has none yet), eight questions (`details`, no script; one of them: the tips go 100% to the DJ, Scan2Play takes no commission — the owner, 2026-10-30; one the weak signal in a hall, with the guest page's weight and load times as measured on production, without a DJ's logo — measure again when that page grows; one a good song the AI turned down: the history, "+ Dodaj piosenkę", the hosts' must-play list) and the start again; no word of Pro while the plans are not enforced.
**The pages "Dla kogo"** (`ForWhomController`, `for-whom.html`, 2026-10-21; Next 7): `/dj-weselny`, `/kluby`, `/puby` — for search
engines (in the sitemap, Polish at the address, English with `?lang=en`, canonical and `hreflang` like the other public pages) and for
a link sent to a DJ or a pub's owner. Where one is, at once (the owner: from the landing page's card the page looked the same): breadcrumbs (the
logo, "Dla kogo", this kind) and the kind with its card's icon above the heading. The landing page's hero with the same pictures
(`landing :: devices`), then four steps, the landing page's "Ty decydujesz" (word for word: the same fear everywhere) and four features under headings of that kind of party ("na weselu", "w klubie"…) and up to
three questions (the pubs' two; `forWhom.KEY.*` — each page its own texts, not one page with a word swapped: a
search engine counts that a doorway page; `ForWhomPagesWebTest` checks no paragraph repeats), the start again and the other two kinds
as cards that are links (the whole card, `.stretched-link`). Every link in the page's language (`?lang=en` on an English page). No
form (no session, no CSRF token) and no word of Pro while the plans are not enforced (the owner, 2026-10-21); once they are, the
steps and features of the Pro plan carry "PRO" (`ForWhomController.Page.pro`) and "Ile to kosztuje?" says what Free has and what Pro
adds (`forWhom.pub.faq2.a.plans`; `ForWhomPagesPlansWebTest`) — the pages promise nothing of Pro as free (the owner, 2026-10-24). No
karaoke page: nothing in the app is made for it yet (the owner, 2026-10-21).
