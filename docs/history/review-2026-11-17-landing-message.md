# Review 2026-11-17 — branch `claude/wonderful-edison-4qz6fs` (the landing page and "Dla kogo" rewritten around a DJ's fears)

Scope: Adas553/scan2play#278 (to `main`) and Adas553/scan2play#279 (to `dev`), the same branch, `8ab559f`. Against `origin/main`
(`c2d6254`, #276): 10 files, +148 / −59 — `landing.html`, `for-whom.html`, `ForWhomController.PAGES`, `messages_pl.properties`,
`messages.properties`, `GuestPageRenderTest`, `ForWhomPagesWebTest`, `ForWhomPagesPlansWebTest`, `docs/context/05-1a-public-pages.md`,
`SESSION_HANDOFF.md`. Texts and the order of sections only: no migration, no endpoint, no script.
Line numbers are the branch's. Severity: **bug** (untrue or wrong now), **should fix**, **nit**.

## Checks run (cloud session, a worktree of `8ab559f`)

| Step | Result |
|---|---|
| `tools/check.py unit` | 1311 tests, 0 failed, OK (`ImpersonalPolishTextsTest` included) |
| `tools/check.py audit -t landing,for-whom` | 12 pages × 320 / 390 / 1280: no sideways scrolling, no console errors, no CSP violation. Small targets: only the demo dashboard's "Szczegóły" / "Details" (36 px, not touched here) |
| screenshots (`page_audit.py --shots`) | `/`, `/?lang=en`, `/dj-weselny` at 390 and 1280 px — looked at; notes in P9, P10 |
| `/p/DEMO` on production, Playwright, Pixel 7, cache off, CDP throttling, 2–3 runs each | see "The FAQ's numbers" |
| the merge of `main` (`e0c52a8`) | merge base = `origin/main`'s tip; `git diff origin/main..branch` = `git diff origin/main...branch` (the same 10 files): nothing of #276 lost or reverted. Merges into `dev` (`fadc6d5`) without a conflict |
| line endings, escapes | `git ls-files --eol` unchanged (`05-1a-public-pages.md` CRLF, 31/31 lines; the rest LF); no non-ASCII character in the added lines of `messages*.properties` (all `\uXXXX`, emoji as surrogate pairs) |
| security | no new `<form>`, session, `th:utext`, `<script>`, `style=` or `on*=` in the diff; the fragment `landing :: control` holds only `#{…}` texts. No private data or secret in the texts or comments |

### The FAQ's numbers (`landing.faq.signal.a`)

| Throttling | Transferred | First paint (FCP, from navigation start) | `load` |
|---|---|---|---|
| none | 86.8 KB, 6 files | 0.26–0.88 s | 0.65–0.88 s |
| 1.6 Mb/s, 150 ms | 86.8 KB, 6 files | 0.82 s | 0.81–0.83 s |
| 400 kb/s, 400 ms | 86.8 KB, 6 files | 2.71–2.74 s | 2.71–2.74 s |

The branch's 87 KB / six files / 0.8 s / 2.7 s hold. "około 90 KB", "po niecałej sekundzie", "po około 3 sekundach" are true. The field is
in the DOM after ~0.2 s / ~0.6 s, but painted only with the stylesheets, at FCP — the figure to quote, as the branch does.

### The promises, checked in the code

| Promise | Where it is true | Plan |
|---|---|---|
| profiles "pod przyjętą prośbą i po imprezie" | `result.html:59-61` (only `decision == 'accepted'`), `party_ended.html:37`; also on the guest page itself, `index.html:94-96`; the fragment `components.html:288` | Pro (`PlanFeature.java:12`) |
| logo on the prints | `qr-print.html:27`, `:178` (a switch "Twoje logo") | Pro (`PlanFeature.java:30`) |
| QR code and the most wanted songs on the TV | `tv.html:13-15`, `:27`, `:34` (ten songs with their votes) | everyone's |
| a comment with the reason for a refused request | `result.html:42` (`response.comment`, the AI's verdict saved with it, `SongEvaluationService.java:266-267`) | everyone's (the styles but "Klasyczne" are Pro) |
| requests unchecked when the AI does not answer | `SongEvaluationService.java:401-408` (`withoutTheAi`: accepted, `KIND_UNCHECKED`), `:230`; the bar `dashboard.html:330` | everyone's |
| "⚠ Sprawdź" | `dashboard.html:433`, `history.html:153` | everyone's |
| a link for the couple in the summary | `SharedSummaryController.java:27`, `:41` (`/s/{token}`), `summary.html:17`, `:63` | Pro (`PlanFeature.java:20`, SUMMARY) |
| wishes before the party, "które się powtarzają" | `WishController.java:62`, `wishes.html:92`, `:174` (the votes' column) | Pro (`PlanFeature.java:40`) |

All true today. "Pro without a badge": on `/dj-weselny` the new `feature1` (the wishes) is Pro and is in `PAGES` and in
`ForWhomPagesPlansWebTest.PRO`; `feature3` (the dedications) and `feature2` (the TV) are everyone's — right. "Ty decydujesz" has nothing of
Pro. On the landing page the profiles, the wishes, the hosts' list and the logo stand without a badge, as intended.

## Findings (most serious first)

### P1 — "Każdą prośbę widzisz przed zagraniem" is not true of the requests the AI turns down — **should fix (the very fear the section answers)**
- `messages_pl.properties:38`, `messages.properties:39` (`landing.control.decide.text`); the same in `landing.faq.ai.a` (`:196` / `:197`).
- A request the AI rejects never reaches the queue: it is saved as rejected and shows only in "Historia" under "Odrzucone"
  (`history.html:65-67`, the guest's words and the AI's comment `:160`). Refused before the AI (the hosts' "nie grać" list, a mood, a song
  the DJ skipped, one played lately) it is not saved at all — only counted for the summary (`EveningRefusals`). A DJ who reads "every
  request" and then misses a refused guest's song feels misled — and the section exists for exactly that fear.
- Proposed (PL): „Każdą przepuszczoną prośbę widzisz przed zagraniem, ze słowami gościa — grasz albo pomijasz. Odrzucone przez AI
  zostają w historii. Gdy AI mogło źle zrozumieć, piosenka ma znak „⚠ Sprawdź”.”
  (EN): "You see every request let through before you play it, with the guest’s own words — play it or skip it. The ones the AI turned
  down stay in the history. When the AI may have got it wrong, the song is marked “⚠ Check”."

### P2 — The landing page says "Ty decydujesz" twice: the section and the FAQ "Co, jeśli AI się pomyli albo nie działa?" — **should fix**
- `messages_pl.properties:196` (`landing.faq.ai.a`) is `control.decide.text` + `control.safe.text` almost word for word ("Każdą prośbę
  widzisz przed zagraniem, ze słowami gościa… prośby trafiają do Ciebie niesprawdzone — impreza ich nie traci"); also the vibe's card
  `:54` ends with "a gościowi odpowiada krótkim komentarzem" = `control.answer`. A reader who opens the FAQ gets the section again.
- Proposed: give the question what the section does not say — what to do when the AI was wrong:
  Q „Co, jeśli AI odrzuci dobrą piosenkę?” — A „Odrzucone prośby są w historii, ze słowami gościa i powodem. Piosenkę dodasz sam przez
  „+ Dodaj piosenkę”, a to, co gospodarze wpisali na listę „koniecznie zagrać”, AI przepuszcza zawsze.”
  (EN) "What if the AI turns down a good song?" — "Turned-down requests stay in the history, with the guest’s words and the reason. Add
  the song yourself with “+ Add a song”; what the hosts put on their must-play list always gets through."
  (true: `dashboard.html:288`, `SongEvaluationService.java:322-332`). The vibe's card `:54`: „Każdą prośbę AI sprawdza pod klimat i Twój
  opis imprezy i odrzuca to, co już dziś leciało.” (EN `:55`: "…and turns down what already played tonight.")

### P3 — "Życzenia przed weselem" reads as wedding congratulations — **should fix**
- `messages_pl.properties:100` (`forWhom.wedding.feature1.title`); also `landing.feature.hosts.title` `:59` „Lista gospodarzy i życzenia”.
- On a wedding page "życzenia" means first the guests' congratulations to the couple; only the card's text says it is songs. The heading
  is the first thing a DJ reads, and on `/dj-weselny` it is the first feature.
- Proposed: „Piosenki od gości przed weselem” (or, keeping the feature's name, „Muzyczne życzenia przed weselem”); the landing card:
  „Lista gospodarzy i piosenki od gości”. EN is unambiguous; "Song wishes before the wedding" if the Polish changes.

### P4 — "Ty decydujesz" on `/puby` speaks of a console — **should fix**
- `messages_pl.properties:40` (`landing.control.answer.text`: „nie musi podchodzić do konsoli”), shown on `/puby` by
  `for-whom.html:76`. The pub page is for a pub's owner — no DJ, the queue on the barman's phone (`forWhom.pub.title`: „bez stania przy
  barze”, `forWhom.pub.lede`). The template's comment `for-whom.html:74-75` assumes "a DJ's first fear is the same… in a pub".
- Proposed, one text for all four pages: „Gdy prośba nie pasuje, gość od razu widzi krótki komentarz z powodem — nie musi nikogo szukać
  ani pytać.” (EN `:41`: "…the guest sees a short comment with the reason at once — no need to find anyone and ask.")

### P5 — "Reklama dla Ciebie" reads as "an advert aimed at you" — **should fix**
- `messages_pl.properties:51`, `messages.properties:52` ("Promotion for you" — also odd in English). The first card of "Co dostajesz".
- Proposed: „Następne zlecenia” / „Reklamujesz się przy okazji”; EN "Your next bookings" / "Advertising on the side". Not „Darmowa
  reklama": profiles are Pro once the plans are enforced.

### P6 — "Pierwsza impreza" — the first party of what? — **nit**
- `messages_pl.properties:63` (`landing.first.title`). Alone it can read as a DJ's first party ever. Proposed: „Pierwsza impreza ze
  Scan2Play” or „Jak zacząć na imprezie”; EN `:64` "Your first party with Scan2Play".

### P7 — The hero's subtitle: "Ty" twice and "Decydujesz Ty" right above the heading "Ty decydujesz" — **nit**
- `messages_pl.properties:32`. „…Ty dostajesz jedną kolejkę z głosami, bez karteczek i krzyczenia przez muzykę. Decydujesz Ty i grasz ze
  swojego programu.” — inverted, and the next heading says the same.
- Proposed: „Goście skanują kod QR i wpisują piosenkę w telefonie — bez aplikacji. Ty masz jedną kolejkę z głosami, bez karteczek i bez
  przekrzykiwania muzyki, i grasz ze swojego programu.” EN `:33`: "…You get one queue with votes — no scraps of paper, no shouting over
  the music — and play from your own software."

### P8 — Smaller wording — **nit**
- `messages_pl.properties:42` „Impreza ich nie traci.” — stiff after the title „Nic nie ginie”: „Żadna nie przepada.” EN `:43` "The party
  loses none." → "None of them is lost." (or drop it: the title says it).
- `:69` „Ekran na TV pokazuje kod QR i najbardziej chciane piosenki.” — "Ekran na TV" is a calque and "chciane" colloquial: „Na
  telewizorze w sali wyświetlisz kod QR i najczęściej zamawiane piosenki.” (the same "chcianych" in `forWhom.wedding.feature2.text`, older).
- `:186` „pole na piosenkę jest po niecałej sekundzie” → „pole na piosenkę pojawia się po niecałej sekundzie”; EN `:187` "On weak LTE the
  song field is there in under a second" → "On a weak 4G connection the song field appears in under a second".
- `:107` (`forWhom.wedding.feature3.text`, moved here from feature1): „Dla Ani na 18-stkę” on the wedding page — a wedding example:
  „Dla Babci Zosi od wnuków”. EN `:108` "For Grandma Rose, from the grandkids".
- `:114` `forWhom.wedding.faq2.a` ends „Ty i tak widzisz każdą prośbę przed zagraniem” — now said by "Ty decydujesz" two screens above,
  and untrue in the same way as P1; drop the sentence.

### P9 — `/dj-weselny`, `/kluby`, `/puby`: a grid of three between two grids of four — **nit**
- `for-whom.html:76` (`landing :: control`, `s2p-landing-grid-3`, `landing.html:78`) between the four steps and the four features. At
  1280 px the cards change width twice in a row; on a phone it does not show. Either accept it, or let the fragment take the grid's class
  as a parameter. Rhythm on `/` and `/?lang=en` is even (three grids of three, then four for whom); no card is out of length — the longest
  is "Nic nie gra bez Ciebie" (4 lines against 2 of "Nic nie ginie" at 1280 px), acceptable.

### P10 — 🎤 twice on `/` — **nit**
- `landing.html:145` (the mic card of "Pierwsza impreza") and the Karaoke card in "Dla kogo" — a few hundred px apart. Proposed 🗣️ for
  the mic card.

### P11 — "Ty decydujesz" on four pages and SEO — **nit (acceptable as is)**
- The section is 69 words, the same on `/` (of 687 in `<main>`) and on the three pages "Dla kogo" (of 371 / 325 / 295: 19–23 %). A block
  repeated on every page is boilerplate to a search engine and does not make the pages doorways: each keeps its own title, lede, steps,
  features and FAQ. `ForWhomPagesWebTest.thePages_doNotRepeatEachOther` (`:124-139`) checks only `forWhomSteps` and `forWhomFaq`, so it
  stays green; it does not need the section. With P4 the one text fits all four pages; keep it the only shared block (no second one).

### P12 — "około 90 KB" is a party without the DJ's logo — **nit**
- `messages_pl.properties:186`. The demo has no logo; a Pro party with one adds the logo's PNG (up to 512 px a side, `LogoImage.java:33`,
  usually a few dozen KB) to the guest page. The sentence stays true in spirit; the comment `landing.html:181-182` could say "without a
  logo" so the next measurement compares like with like.

### P13 — Comments and docs — **nit**
- `landing.html:115` keeps "(the owner, 2026-10-18)" in a comment the branch rewrote — `AGENTS.md`: who decided and when goes to
  `docs/history/`; tidy it while the comment is touched ("No "PRO" on one card: the landing page sells the product, `/pro` the plans").
- `landing.faq.price.a` (`messages_pl.properties:190`, EN `:191`, while the plans are enforced) lists the hosts' list, profiles, tips and
  the summary; the page now leads with the wishes and the logo — „Pro dodaje m.in. listę gospodarzy, życzenia przed imprezą, profile i
  napiwki, logo oraz podsumowanie wieczoru.” (optional: "m.in." keeps it true).
- `SESSION_HANDOFF.md` "Start here", item 1 still says `main` = `e4ff4d1` and "no open PR"; `main` is `c2d6254` (#274, #276 merged).
  Not this branch's text, but the branch touches the file — the next handoff should fix it.

## Not findings

- `ImpersonalPolishTextsTest`: green; no new masculine form toward the DJ or a guest (the mic line is the DJ's own first person, „zobaczę”).
- The new texts in English are equivalent to the Polish ones, apart from P5 and the wording in P8.
- CSP: the audit saw no violation on the 12 pages; the new sections are markup and message keys only.
- The tests: `GuestPageRenderTest` checks the order (control before the steps, the first party between the features and for whom) and
  nine `<details>`; `ForWhomPagesWebTest` the control between the steps and the features; `ForWhomPagesPlansWebTest` the wishes as Pro.
