# Review 2026-11-13 (second) — branch `claude/blissful-cannon-h66za3` (PR #263 → `main`, #264 → `dev`)

Scope: `git diff origin/main...origin/claude/blissful-cannon-h66za3` (base `d033d77`, head `5823c5e`; 85 files, +3602 / −115):
"Życzenia przed imprezą" (V68, V69, `WishService`, `WishController`, `DjWishesController`, `wish.html`, `wishes.html`, the invitation
print), the fixes of the first review (`docs/history/review-2026-11-13-wishes.md`: W1–W3, D1, D3, D4, D6, S1–S3; D2 and D5 left), and
the owner's notes (no jumps — `scroll-restore.js`, `folds.js` remembered, the toast `.s2p-done-toast` —, `.s2p-back-link`, the shorter
hint for guests, an event's own guests' look V69, the list as the panel's queue, "Pokaż więcej" / "Zwiń" by `wishes-list.js`).

## Checks run (cloud session, a copy of the branch, `tools/check.py`)

| Step | Result |
|---|---|
| `unit` | 1301 tests, 0 failed — OK |
| `it` | 168 tests, 0 failed — OK (PostgreSQL **16** of the container; Railway and CI run 18) |
| `browser` | 404 scenarios — OK |
| `audit -t wish,wishes,staff,settings,qr-print` | 36 pages × 320 / 390 / 1280 — no sideways scrolling, no console errors, no small targets — OK |
| probe on a real PostgreSQL (temporary IT, not committed) | 12 × `create` at once on an empty party → exactly 5 made; 10 closed events reopened at once → exactly 5 reopened; 6 `reopen` + 6 `create` at once → 5 open — the party row's `FOR UPDATE` holds |
| screenshots | `page_audit.py -p wish,wishes,staff,settings,qr-print-cards-own-tables-invitation --widths 320,390,1280 --shots`, plus the guests' page in each of the 5 looks (the rendered `wish.html` with `data-guest-theme` set) — looked at |

## Findings (most serious first)

### I1 — the look's select in the event's row of buttons is squeezed to an empty box at 390 px — **important (UX)**
- `wishes.html:121-143` (`.s2p-btn-row`: "Drukuj zaproszenia", "Zakończ zbieranie", the form `select` + "Zmień wygląd", "Nowy link").
- Seen on the screenshot `wishes-390`: the second line is `[  ⌄ ] [Zmień wygląd] [Nowy link]` — the select is ~45 px, its value
  ("Klasyczny") not shown. The DJ cannot see which look the event has, and the row of five controls reads overloaded. At 320 px the row
  wraps differently and the select is readable; at 1280 px everything is on one line. The audit does not catch it (nothing overflows).
- Fix: the look on a line of its own under the buttons, as a labelled field ("Wygląd strony dla gości: [Klasyczny ⌄] [Zmień wygląd]"),
  the select `flex: 1 1 10rem`; "Nowy link" beside the link's field ("Kopiuj", "Otwórz") where it belongs, leaving "Drukuj zaproszenia"
  and "Zakończ zbieranie" in the row. Scenario: at 320 / 390 / 1280 the select is at least as wide as its longest option.

### I2 — the invitation's editor shows and changes the party's guests' look, not the event's — **important (V69's purpose)**
- `qr-print.html:119-121`, `DjDashboardController.qrPrint` (`GUEST_THEME` = `settings.getGuestTheme()` also with `wish`).
- The invitation's editor says "Strona gościa: Klasyczny · Zmień" (screenshot `qr-print-cards-own-tables-invitation-1280`), and "Zmień"
  goes to `/dj/settings#guestThemeCard` — the party's "Motyw dla gości", which acts on `/p/` tonight. A DJ preparing a wedding's
  invitation who wants the wedding look follows it and switches the party's page instead — exactly what V69 was made to keep apart — while
  the event's page keeps its own look. A staff member with WISHES and without VIBE is sent to a card they cannot use.
- Fix: with `wish`, `GUEST_THEME` = the event's `theme()` and "Zmień" → `/dj/wishes?party=…&event=…` (or no line at all). Test:
  `QrPrintPageTest` — the invitation names the event's look and links to the event.

### I3 — every "back where it was" hangs on `beforeunload`, which Safari on iOS often does not fire — **important (to try on an iPhone)**
- `scroll-restore.js` (old), `folds.js` and `wishes-list.js` (new) save on `beforeunload` only.
- Before this branch the forms of `/dj/wishes` and "Obsługa" came back to an anchor (`#wishEvent`, `#staffLinkCard`): if the scroll was
  not remembered, the browser still went to the card. Now the anchor is gone and the memory is the only mechanism. iOS Safari is reported
  not to fire `beforeunload` (it fires `pagehide` in most navigations; [WebKit bug 199854](https://lists.webkit.org/pipermail/webkit-unassigned/2019-July/880465.html),
  [ctrl.blog](https://www.ctrl.blog/entry/safari-beacon-issues.html)). If so, on an iPhone: a "⭐" returns the page to its top and the
  list to 10 rows; "Nowy link" in "Obsługa" returns to the top with the link's card folded. Not verified here (no iOS device).
- Fix: save on `pagehide` as well (and, for a form, on `submit` in the capture phase — the page leaves right after); one helper used by
  the three scripts. Scenario: `pagehide` alone keeps the scroll, the folds and the rows shown. The owner: try "⭐" and "Nowy link" on an
  iPhone before the release.

### D1 — "⭐ Dodaj 10 najczęstszych" adds what the server counts at the click, not the songs the question named — **minor**
- `DjWishesController.java:144` (the question's list) vs `:261` (`top=true` recomputed on POST).
- Guests write between the page's load and the click: the ten can change, and the DJ confirms one list while another is added — the D1
  fix of the first review (name what is added) holds only while nobody writes. Fix: the form posts the named songs as `song` fields
  (the same list the question shows) and `top` goes; the server keeps its "only the event's named songs" filter. Test in
  `WishesPageRenderTest`.

### D2 — the toast at the bottom never goes away and can cover the "⭐" just tapped — **minor (UX)**
- `wishes.html:36-41`, `.s2p-undo-toast` (fixed, `bottom: 1rem`, ~60 px tall on a phone).
- The page comes back where it was, so a "⭐" tapped in the lowest 80 px of the screen is under the toast after the reload; the next
  row's "⭐" too. Only ✕ removes it. Fix: a success hides itself after ~5 s (a refusal stays until ✕), or the page gets a bottom padding
  of the toast's height while it shows. Scenario: the last visible "⭐" is not under the toast.

### D3 — "Zobacz życzenia" with several events looks like it does nothing on a phone — **minor (UX, the owner's call)**
- `wishes.html:94-97` (no anchor, on purpose).
- The opened event is drawn below the whole list (up to 20 events, ~95 px each on a phone); the page comes back at the tapped row, so
  the tap only turns the button white ("Zwiń życzenia"). A jump the DJ asked for by a link is not the jump the owner disliked (after a
  form). Proposal: this link alone keeps `#wishEvent` (`scroll-restore.js` respects a hash), the forms stay without anchors. Also three
  "Zwiń" near each other mean two things: the row's "Zwiń życzenia" and the card's "Zwiń" close the event, the list's "Zwiń" goes back to
  10 rows — the list's could be "Pokaż mniej".

### D4 — `GuestThemeTest` checks the CHECK of V59 only; V69 has its own — **minor (tests)**
- `GuestThemeTest.theMigrationsCheck_isTheEnum`, `V69__wish_event_theme.sql:7`. Today both are the five looks (verified by hand, and
  `varchar(10)` fits `CHRISTMAS`). A sixth look added to the enum and to V59's style of migration, but not to `wish_event`, would make
  "Utwórz" with it a 500. Fix: the test reads every `guest_theme IN (…)` of the migrations — the newest per table — and compares each
  with the enum.

### D5 — D5 of the first review: the reason for leaving it no longer holds — **minor**
- The first review left `idx_wish_event_date` "for the next migration that touches these tables"; V69 is that migration. Dropping it
  now would change V69, which the owner may have applied locally (a checksum error in IntelliJ), so leave it — but say it in
  `10-2-migrations.md` (the index is unused: the purge's `OR created_at` scans anyway) so the next migration on `wish_event` drops it.

### D6 — the guests' page promises that the DJ plays what most want — **minor (text)**
- `messages_pl.properties:1112` `wish.privacy` "DJ zobaczy wszystkie życzenia przed imprezą i zagra to, czego chce najwięcej gości."
  (EN the same). The DJ decides, and the hosts' list may hold something else. Proposal: "DJ zobaczy wszystkie życzenia przed imprezą i
  ułoży z nich muzykę na imprezę. Nie zapisujemy…" — the owner's words.

### D7 — `page_audit.py` lets one page's session storage reach the next — **minor (tooling)**
- `tools/page_audit.py:131-136`: every page is opened as `/dj/dashboard?page=…`, so `folds_/dj/dashboard`, `more_…` and `scroll_…`
  are one for all of them. The screenshot `settings-390` shows "Godziny imprezy" unfolded — remembered from `settings-hours`. The
  measurements are unaffected today, the pictures are not what a first visit shows. Fix: `sessionStorage.clear()` (or
  `Storage.clearDataForOrigin`) before each page.

### S1 — stale or misplaced comments — **style**
- `staff.html:24` still says the link's card unfolds "after it was made or turned off (#staffLinkCard, folds.js)"; now it is the
  remembered fold.
- `WishService.java:197-203`: the Javadoc of `newLink` ("Nowy link": …) sits above `setTheme`, which has its own under it; `newLink` has none.
- `DjWishesController.java:54`: one line of 180 characters (the class Javadoc).

### S2 — leftovers of S2 (fully qualified names) — **style**
- `DjDashboardController.java:467-468` (`com.scan2play.model.PosterTheme.ELEGANT`, `com.scan2play.model.PosterFont.SCRIPT`, added by
  the branch), `WishServiceIT` (`com.scan2play.model.GuestTheme` ~20 times), `PlanFeatureEndpointsTest` / `StaffPermissionEndpointsTest`
  (`com.scan2play.service.WishService`). The older lines of those files have the same habit, so not worth a commit alone.

### S3 — `wishes-list.js` remembers a list by its index — **style**
- `wishes-list.js:42` `remembered[i]`: with no named songs the "Do sprawdzenia" list is index 0, and when the first named song arrives
  its count goes to the named list. Harmless (more rows shown, clamped), but `data-more-list="wishRows"` / `"wishCheck"` as the key is
  as short and exact.

## The first review's fixes — checked

- **W1** — right. `reopen` and `create` count under the party row's `FOR UPDATE`; the probe above (concurrent create, concurrent reopen,
  both mixed) keeps five open; 20 kept events checked by `create`; `eventsOf` lists `MAX_EVENTS` so every kept event is on the page. A
  past event reopened without counting is fine (it takes nothing).
- **W2** — right. `StaffPermissionEndpointsTest` has the ten requests (with `/theme` and both ways of `/closed`), `PlanFeatureEndpointsTest` the six Pro ones
  (with `/theme`) and `verifyNoInteractions(wishes)`, over the real `DjWishesController`.
- **W3** — right. `WishControllerTest`: no Pro → 404 for GET and POST, nothing written, the limiter not asked; the address's limit said
  and not counted; `GuestRequestLimiterTest` the 31st wish of an address at a token.
- **D1** — the question names the songs (escaped by `th:attr`, `window.confirm` shows text — no HTML), the button gone when all are on
  the list; but see D1 above (the list can change before the click).
- **D2 left** — rightly: Spring Session JDBC gives each request its own copy of the session, so a lock on it cannot serialise two posts;
  the address limit and the event's ceiling are the guards, and the comment in `WishController.write` says so.
- **D3** — right. `invitationLine`: 47 characters of name at most + " · dd.MM.yyyy" = 60; a cut at a space past the middle, never half a
  surrogate pair; `QrPrintPageTest.anInvitationsLine_fitsThePrintsLine_theDayWhole`.
- **D4** — right. `wish.note.full` impersonal; `ImpersonalPolishTextsTest.DJ_BY_GENDER` catches "DJ zebrał", "DJ będzie wiedział";
  the three older texts listed for the owner.
- **D5 left** — the efficiency argument holds (≤ 20 rows a party); the timing argument does not any more (D5 above).
- **D6** — `wishLinks` gone. **S1** — a constructor dependency, no optional setter. **S2** — mostly (S2 above). **S3** — the owner's
  words are out of `05-1i-wishes.md`.

## Reviewed and right (leave as is)

- **Authorization.** Every `/dj/wishes` endpoint calls `DjSessionHelper.require(partyCode, WISHES)` (the page via `access` + `require`);
  create, reopen, new link, `/theme` and must-play also `planService.require(…, WISHES)`; close, delete, the page and the CSV work on any
  plan. Every SQL of an event is `WHERE party_code = ? AND id = ?` — another party's id is a 404 (IT for close, reopen, new link,
  delete and `setTheme`); a foreign `event=` on the page is ignored. `qr-print?wish=` needs WISHES and Pro and looks the event up by the party.
- **`/w/{token}`.** `permitAll` for `/w/*` only; CSRF on, the expired form back to the page (`WISH_FORM` limited to the token's
  charset and 32 characters — no open redirect); the session trap has `WishPageSessionWebTest`; no Pro or an unknown token → 404;
  `X-Robots-Tag` (`NoIndexFilter` `/w/`) and the meta, `no-referrer`; the look is the event's (`event.theme()`); limits 5 a session,
  30 an address and a token (its own Caffeine window), 2 000 an event in the `UPDATE … RETURNING`.
- **XSS.** The event's name, the songs, the flash texts are `th:text`; `data-confirm` through `th:attr` (escaped) and `window.confirm`
  (text); `msgWithParams` gets values, and no new pattern mixes `'` with `{0}`.
- **CSV.** `EveningSummaryService.cell` for the song and the mark (quotes, line breaks, a leading `= + - @ TAB` prefixed with `'`).
- **`host_wanted`.** Only the event's named songs (the first 500), never another text; `SongList.tidy` under the party row's lock.
- **Session storage.** Every read and write of `folds_`, `more_`, `scroll_` is in `try`; a stray value is rejected (`Array.isArray`,
  `typeof … === 'number'`, a count clamped between 10 and the rows), an id is looked up only as a `details.s2p-fold`. Blocked storage:
  the page as the server sent it.
- **Script order.** `scroll-restore.js` in `<head>` restores on `DOMContentLoaded`; `folds.js` and `wishes-list.js` are plain scripts at
  the end of `<body>`, so they unfold and lengthen the page before it — the scroll lands on the same place. "Zwiń" brings the buttons
  back with `scrollIntoView` (smooth under Bootstrap's `scroll-behavior`, which the scenario waits for).
- **No anchors in "Obsługa".** "Zaproś" is unfolded by the server when it has an answer (`th:open`), the link's card by the remembered
  fold; nothing else read the anchors (`copy.js` and `folds.js` scenarios unfold by click or by an address with a hash, which still works).
- **V69.** `ADD COLUMN … NOT NULL DEFAULT 'CLASSIC'` (no rewrite on PostgreSQL 11+), the CHECK the same five as `GuestTheme` and
  `party_settings_guest_theme_check`; `GuestTheme.of` maps anything else to CLASSIC, so the CHECK is never hit by a form.
- **SQL and volume.** Every list bounded (events 20, wishes 500, CSV 2 000), the library match one `ANY(?)` query, the settings card one
  count on `(party_code, event_date)`; the wish one `UPDATE … RETURNING` and one `INSERT … ON CONFLICT`. Volume per party ≤ 20 events ×
  2 000 rows — far below Railway's 500 MB even with hundreds of DJs; the purge runs daily.
- **The page's cost.** 500 rows render to ~400 KB of HTML (~800 B a row with its form; ~50 KB gzipped) — acceptable for a page the DJ
  opens at home before a party; the history's server-side `limit` would only pay off past that. No change.
- **Patterns.** The toast reuses `.s2p-undo-toast` (as `formRefused`), the ✕ is a 44 px target; `.s2p-back-link` is one class for
  "Obsługa" and the wishes; `wishes-list.js` is client-side where the history's "Pokaż więcej" fetches — a different case (the rows are
  on the page already), not a copy of `list-tools.js`. No dead keys (`wishes.*`, `wish.*`, `settings.wishes.*` all read, PL = EN), no
  `data-keep-open`, `data-fold-close`, `.s2p-wish-more` (only `.s2p-wish-more-row`, used) or `FIRST` left.
- **The guests' page in the five looks** (screenshots at 320 / 390 / 1280): readable, the field and "Wyślij życzenie" on the first screen,
  nothing sideways.
- **Docs.** `05-1i`, `05-1h`, `06-1`, `06-3`, `10-2`, `11-12` and the handoff match the code (V69, `/theme`, the toast, `wishes-list.js`).

## Verdict

**Merge after fixes:** I1 (the select on a phone) and I2 (the invitation's look) — both small; I3 tried on an iPhone first (a
`pagehide` beside `beforeunload` is cheap enough to do anyway). The rest can go in the same change or later; nothing found is reachable
by a guest or another party.

## Fixed (2026-11-13, the same session, on `claude/quirky-faraday-mhc53m`)

The branch holds `claude/blissful-cannon-h66za3` (merged) with the fixes on top. Each new test and scenario was seen red before the
fix (D4's test excepted: it guards what is right today).

- **I1** — the event's link with "Nowy link" beside it (`#wishLinkRow`), "Drukuj zaproszenia" and "Zakończ zbieranie" in the row, the
  look on a line of its own, labelled ("Wygląd strony dla gości", `#wishThemeSelect`, `.s2p-wish-theme` 10–12 rem). Scenarios
  `wishes-theme-select-320 / -390 / -1280` (red: at 390 px 0 px of room for a name of 76 px).
- **I2** — with `wish`, `qrPrint` puts the event's look in the editor's line and "Zmień" leads to `/dj/wishes?party=…&event=…`; the
  party's own print as before. Test `QrPrintPageTest.anInvitation_namesTheEventsLook_andChangesItOnTheEvent_notTheParties`.
- **I3** — `scroll-restore.js`, `folds.js` and `wishes-list.js` save on `pagehide` as well as `beforeunload`. Scenarios
  `wishes-scroll-kept-on-pagehide`; `wishes-long-list`, `settings-back-where-it-was`, `staff-back-where-it-was` now leave by `pagehide`
  alone. Still to try on a real iPhone.
- **D1** — the form of "⭐ Dodaj 10 najczęstszych" (`#wishTopForm`) sends the songs its question names as `song` fields; `top` is gone
  from `mustPlay`. Test `WishesPageRenderTest.theTopButton_sendsTheSongsItsQuestionNames`.
- **D2** — a success's toast has `data-toast-hide="4000"` and goes by itself (`dj-nav.js`, after the page is parsed); a refusal stays.
  Scenario `wishes-answer-in-place`.
- **D3** — "Zobacz życzenia" leads to `…&event=N#wishEvent`; the forms keep no anchor. The list's "Zwiń" is "Pokaż mniej" / "Show
  less". Scenario `wishes-answer-in-place`, `wishes-long-list`; `WishesPageRenderTest.theEventOpened_foldsAwayAgain`.
- **D4** — `GuestThemeTest.theMigrationsCheck_isTheEnum` reads V59 and V69.
- **D5** — `10-2-migrations.md` says `idx_wish_event_date` is unused and the next migration on `wish_event` drops it (V69 left as it is).
- **D6** — `wish.privacy`: "DJ zobaczy wszystkie życzenia przed imprezą, najczęstsze na górze listy. …" (EN alike) — to reword in the
  owner's words if wanted.
- **D7** — `page_audit.py` clears the session storage at the start of every page.
- **S1** — `staff.html`'s comment, `WishService.newLink`'s Javadoc in its place, `DjWishesController`'s long line. **S2** — imports
  for `PosterTheme` / `PosterFont` and `GuestTheme` in `WishServiceIT`. **S3** — `data-more-list="wishRows"` / `"wishCheck"`, the
  memory by that name (scenario `wishes-long-list`).
- Docs: `05-1i-wishes.md`, `06-3`, `10-2`, `SESSION_HANDOFF.md`.

Checks after the fixes: `unit` 1304, `it` 168, `browser` 408 scenarios, `audit -t wish,wishes,staff,settings,qr-print` (41 pages × 3
widths) — all OK.
