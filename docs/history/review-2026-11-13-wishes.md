# Review 2026-11-13 — branch `claude/happy-carson-5odcet` ("Życzenia przed imprezą", the 👍 hint)

Scope: `git diff origin/main...origin/claude/happy-carson-5odcet` (base `d033d77`), three commits:
`ff74d1b` (the 👍 hint above the guests' list, on the kiosk and the TV), `8ccba36` (a link in the handoff), `aa7fac3`
("Życzenia przed imprezą": V68, `WishService`, `WishController`, `DjWishesController`, `wish.html`, `wishes.html`, the invitation
print `/dj/qr-print?wish=`, the settings card, texts, privacy policy, terms, `/pro`).

## Checks run (cloud session, a copy of the branch, `tools/check.py`)

| Step | Result |
|---|---|
| `unit` | 1247 tests, 0 failed — OK |
| `it` | 165 tests, 0 failed — OK (PostgreSQL **16** of the container; Railway and CI run 18) |
| `browser` | 400 scenarios — OK |
| `audit -t wish,wishes,qr-print,settings` | 33 pages × 3 widths, no sideways scrolling, no console errors, no small targets — OK |
| probe on a real PostgreSQL (temporary IT, not committed) | `create` ×5, close them, `create` ×5, reopen all → `openEvents` = **10** (finding W1 confirmed) |

## Findings (most serious first)

### W1 — "Wznów zbieranie" ignores the limit of five open events; the events of a party are unbounded — **important**
- `WishService.java:147` (`setClosed`), `DjWishesController.java:169-173`, `WishService.java:51,103` (`EVENTS_MAX` = 50 in `eventsOf`).
- `create` counts the open events under the party row's lock, but `setClosed(..., false)` reopens without any count. Close 5, make 5
  more, reopen the first 5: 10 open (confirmed on PostgreSQL). Repeating close → create makes any number of events (each up to
  `MAX_WISHES` = 2 000 wishes, kept up to two years). Past `EVENTS_MAX` = 50 the page lists no more: an event beyond it, still open
  and still taking wishes on its link, can be neither seen, closed, nor deleted by the DJ until the purge.
- Abuse: only by the DJ or staff with WISHES on a Pro party, so not an outside attack; but the documented invariant
  ("at most 5 open, counted under the party row's lock", `05-1i-wishes.md`) does not hold and the database volume per party is not
  bounded (Railway 500 MB).
- Fix: reopening takes the party's lock and the same count as `create` (`Optional`/enum result, the page says
  `wishes.done.too_many`); and a cap on all kept events of a party (e.g. 20, ≤ `EVENTS_MAX`) in `create`, so every event is on
  the page. Test: `WishServiceIT` — reopening the sixth is refused; the 21st event is refused even when all are closed.

### W2 — `/dj/wishes` and `qr-print?wish=` are not in the endpoint-wide permission and plan tests — **important (tests)**
- `StaffPermissionEndpointsTest.java:52` says "A new endpoint belongs in this list"; neither it nor `PlanFeatureEndpointsTest`
  mentions `/dj/wishes/**` or `/dj/qr-print?wish=`. The controllers do check correctly today (reviewed by hand, below), but
  `WishesPageRenderTest` stubs `DjSessionHelper.require` with a mock, so a dropped `require(…, WISHES)` or `planService.require`
  in a later change goes unnoticed.
- Fix: add the seven endpoints (GET page, create, closed, link, delete, must-play, csv) and `qr-print?wish=` to
  `StaffPermissionEndpointsTest` (refused without WISHES, let through with it alone) and the Pro-only ones (create, reopen, link,
  must-play, `qr-print?wish=`) to `PlanFeatureEndpointsTest`.

### W3 — the guests' page: "no Pro → 404" and the address limit have no test — **important (tests)**
- `WishController.java` `party()` (Pro filter) and `GuestRequestLimiter.tryAcquireWish` (30 an address and an event): `WishWebTest`
  tests a missing party, not a party without Pro; nothing calls `tryAcquireWish` in any test (no `GuestRequestLimiterTest` case,
  no web test with the limiter refusing).
- Fix: `WishWebTest` — a party whose plan lacks WISHES: GET and POST are 404 and `WishService.add` is never called; the limiter
  mocked to return a wait: the note `wish.note.too_many`, nothing added. `GuestRequestLimiterTest`: the 31st wish of one address at
  one token waits, another token or address does not.

### D1 — "⭐ Dodaj 10 najczęstszych" moves guests' free text to the list that skips the AI's check — **minor (design)**
- `DjWishesController.java:216`. Only the event's own named songs can be added (good — the form is no way to write arbitrary text,
  verified), but those songs are whatever guests typed with " - ". `host_wanted` is the list the AI lets through, and a line matches
  every song whose name contains its words (`SongList.matches`). A few guests (5 each, 30 an address) can push a crafted line into
  the top 10; one click adds it without the DJ reading each row. The DJ does see the rows above the button, so the risk is small.
- Fix (pick one): `top` adds only rows the library marks "✓ Masz" when the party has a library; or the button names the songs it
  will add in its `data-confirm`.

### D2 — the session's limit of five can be passed by parallel posts — **minor**
- `WishController.java:87,105`: read, add, then write the count; two posts at once from one session both see 4. The address limit
  (30) and the event's ceiling still hold, so it is cosmetic. Fix if wanted: `synchronized (WebUtils.getSessionMutex(session))`
  around the check-add-count, or accept it and say so in the comment.

### D3 — the invitation's line can be 93 characters while the print's line is 60 — **minor**
- `DjDashboardController.java:471`: `name` (≤ 80) + " · " + date = up to 93; `PartySettingsEntity.POSTER_LINE_MAX` = 60, the field's
  `maxlength` 60, and `QrPrintPageTest` asserts the texts fit those limits only for the presets. Not verified visually with an
  80-character name on the cards / tent (the audit's page has a short one). Fix: the date alone in the line and the name in the
  heading, or cut the name so the line stays ≤ 60; a `QrPrintPageTest` case with an 80-character name.

### D4 — a gendered Polish text — **minor**
- `messages_pl.properties:1116` `wish.note.full=DJ zebrał już wszystkie życzenia…` — "a DJ is not always a man" (review
  2026-10-21, U2). `ImpersonalPolishTextsTest` catches only "-łeś / -łaś / gotowy", so it passed. Fix: "Lista życzeń na tę imprezę jest
  już pełna. Dziękujemy!".

### D5 — the purge's index does not serve the purge — **minor (efficiency)**
- `V68__guest_wishes.sql:29` `idx_wish_event_date` "the nightly purge", but the purge is `event_date < ? OR created_at < ?` and
  `created_at` has no index, so it is a sequential scan either way. The table is small (W1 fixed: ≤ 20 events a party), so the
  scan is fine; the index costs a write per event and is unused (`idx_wish_event_party_date` serves the page and the count).
  Fix: next migration drops it (or indexes `created_at` too) — or leave it and correct the comment. Not worth a migration alone.

### D6 — dead model attribute `wishLinks` — **minor**
- `DjWishesController.java:98`: `WISH_LINKS` (a map of every event's link, up to 50) is computed on every page load and not read by
  `wishes.html`. Fix: remove it and the constant.

### S1 — `WishService` set by an optional setter in `DjDashboardController` — **style**
- `DjDashboardController.java:95-96`: `@Autowired(required = false)` only so the 15 tests that call the constructor by hand compile
  unchanged. In production the bean is always there, so `required = false` only hides a wiring mistake (the card would say "Brak"
  and the invitation would be a 404, silently). Recommendation: a constructor parameter like the other services, and the 15
  `new DjDashboardController(…)` in tests updated (most pass `mock(...)`; a small test factory would make the next one cheaper).
  Not blocking.

### S2 — fully qualified names in code — **style**
- `DjDashboardController.java:461-473` (`com.scan2play.service.WishService.Event`, `java.util.Optional`,
  `org.springframework.web.server.ResponseStatusException`, …), `WishService.toCsv` (`java.util.Map`, `com.scan2play.model.LibraryMark`,
  `org.springframework.context.MessageSource`), `DjWishesController` (`org.springframework.context.i18n.LocaleContextHolder`). Imports
  read better; the file already imports most of these packages.

### S3 — history in a context document — **style**
- `docs/context/05-1i-wishes.md:3`: "the owner, 2026-11-13: "nie tylko na wesela"; the analysis https://claude.ai/artifact/…" — per
  `CLAUDE.md`, who decided what and when belongs in `docs/history/` or the handoff; the context part keeps what is true now.

## Reviewed and right (leave as is)

- **Authorization of `/dj/wishes`.** Every endpoint goes through `DjSessionHelper.require(partyCode, WISHES)`; create, reopen, new
  link and must-play also `planService.require(…, WISHES)`; close and delete work on any plan (deliberate, documented). Every SQL of
  an event is `WHERE party_code = ? AND id = ?`, so another party's id is a 404 (IDOR covered by
  `WishServiceIT.aParty_hasAtMostFiveOpenEvents_…_andAnotherPartyCannotTouchThem`). `qr-print?wish=` requires WISHES and Pro and
  looks the event up by the party too.
- **The public `/w/{token}`.** CSRF on (Spring's default, the token added by `th:action`), the session trap of `CLAUDE.md` (1) has its
  test (`WishPageSessionWebTest`), an expired form comes back to the page (`GuestFormExpiredHandler`, the token's charset limited —
  no open redirect), `noindex` header (`NoIndexFilter` `/w/`) and meta, `no-referrer`, unknown token or no Pro → 404, a token over
  32 characters never reaches SQL.
- **XSS.** The event's name, the songs and the flash texts are all `th:text`; message arguments are values, not patterns. The CSV goes
  through `EveningSummaryService.cell` (quotes, line breaks, a leading `= + - @ TAB` prefixed with `'`).
- **Must-play cannot write arbitrary text.** Only songs of that event, named "Artist - Title", from the first 500 rows; the counts
  "added / did not fit" are computed the way `SongList.tidy` keeps the list (dedupe by words, 100 lines), under the party row's lock
  (`updateSettings`).
- **Logs.** Event ids and party codes only — never a token, a name or a song. The privacy policy's "nie trafia do … logów serwera"
  holds for the application's logs (the platform's HTTP log has the path, as for `/p/`, `/h/`, `/k/`).
- **The invitation print saves nothing.** No "Zapisz", no language select, the submit and the language change return early in
  `qr-print.js`, its own stash key; the server's `POST /dj/qr-print/settings` still requires VIBE, so a staff member with WISHES alone
  who gets the editor here cannot save through it either. Scenario `qr-print-invitation-saves-nothing` passes.
- **SQL under concurrency.** The wish is one `UPDATE … WHERE NOT closed AND event_date >= ? AND wish_count < ? RETURNING` (row lock;
  a waiting writer re-checks the condition after the first commits) followed by `INSERT … ON CONFLICT DO UPDATE` — the ceiling and the
  count hold (`WishServiceIT` with threads, green on PostgreSQL 16). Lock order is the same in every path (event row → its wishes;
  create: party row → new event), so no deadlock between add, delete, new link, "Zmień kod imprezy".
- **Order and time zone.** `eventsOf` — the coming ones nearest first, then the past ones latest first (tested); every "today" is
  `Times.DISPLAY_ZONE`, and `java.sql.Date.valueOf(LocalDate)` keeps the date as is.
- **Cascades and purge.** `ON DELETE / ON UPDATE CASCADE` to the party (the code change and the account's deletion tested), the
  wishes `ON DELETE CASCADE` to the event; the purge 7 days after the date or two years after it was made, as the policy says.
- **Migration V68.** New tables only, plus WISHES added to the whole sets exactly as V56 did with KIOSK (`KioskMigrationIT`,
  `StaffRoleTest`).
- **Bounded queries.** Every list has a `LIMIT` (events 50, wishes 500, CSV 2 000), the library match is one `ANY(?)` query, the
  settings card one indexed `count(*)`. Volume: a full event is roughly 1 MB with its index; realistic events far less.
- **Commit 1 (👍 hint).** The hint is in the fragment that the guest page refreshes, shown only when there is a song that is not the
  guest's own (`GuestQueue.votable`), tested on the page, the kiosk and the TV.
- Texts: every non-ASCII character of `messages*.properties` is a `\uXXXX` escape; the CRLF files stay CRLF; the policy and the
  terms (PL and EN) match what is stored.

## Verdict

**Release after fixes:** W1 (the limit and the bound on events) and the tests W2–W3. The rest (D1–D6, S1–S3) can go in the same
change or later; nothing found is a security hole reachable by a guest.

## Fixed (2026-11-13, the same session)

The branch `claude/blissful-cannon-h66za3` holds the reviewed commits with the fixes on top. Each test was seen red before the fix
(W2/W3 by taking the checks out of the code: five failures, then put back).

- **W1** — `WishService.reopen` counts the open events under the party row's lock as `create` does (`Reopened.TOO_MANY`, the page
  says `wishes.done.reopen_too_many`); `WishService.MAX_EVENTS` = 20 events kept a party, open, closed or past, checked by `create`
  (`wishes.done.too_many_kept`, no form on the page at 20); `eventsOf` lists `MAX_EVENTS`, so every kept event is on the page.
  `setClosed` became `close` + `reopen`. Tests: `WishServiceIT.reopening_keepsTheLimitOfOpenEvents`,
  `WishServiceIT.aParty_keepsAtMostTwentyEvents_closedOrNot_andListsEachOfThem`,
  `WishesPageRenderTest.theLimits_areSaid_twentyKept_andFiveOpenWhenReopening`.
- **W2** — `StaffPermissionEndpointsTest` (nine `/dj/wishes` and `qr-print?wish=` requests) and `PlanFeatureEndpointsTest` (five Pro
  ones, nothing touched in `WishService` when refused; four that stay everyone's), over the real `DjWishesController`
  (`WishControllers`, a test helper).
- **W3** — `WishControllerTest` (a party without Pro: the page and the form are 404, nothing written, the limiter not asked; a wish
  over the address limit is said and not counted as one of the guest's five), `GuestRequestLimiterTest
  .theWishes_haveTheirOwnLimit_anAddressAtAnEvent`.
- **D4** — `wish.note.full` = "Lista życzeń na tę imprezę jest już pełna. Dziękujemy!"; `ImpersonalPolishTextsTest` now also refuses
  "DJ" with a gendered past verb. Three older texts match it and are listed in the test to be rewritten in the owner's words:
  `guest.vote.gone` ("DJ ją zagrał"), `party.ended.subtitle` ("DJ zrobił przerwę"), `party.tip.title` ("DJ będzie wiedział").
- **D6** — `wishLinks` removed.

Checks after the fixes: `unit` 1289, `it` 167, `browser` 400 scenarios, `audit -t wish,wishes,qr-print,settings` — all OK.

### The second round (the rest of the minor and style points)

- **D1** — "⭐ Dodaj 10 najczęstszych" asks first, naming the songs it would add (the first ten named ones not on the list yet,
  `wishes.top.confirm`), and is gone when the list has them all. Tests: `WishesPageRenderTest` (the question's text,
  `theTopButton_isGone_whenTheListHasThemAll`).
- **D2 — not fixed, said in the code.** A lock on the session cannot help: Spring Session (JDBC) loads the session when a request
  starts, so a second post at once reads the count as it was before either, lock or not. A real guard would need the count outside
  the session, which the address limit (30) and the event's ceiling (2 000) already are. `WishController.write` says so where it
  checks the five.
- **D3** — `DjDashboardController.invitationLine`: the event's name and day within the print's 60 — a long name cut (at a space near
  the end when there is one, never half an emoji) and "…", the day whole. Test: `QrPrintPageTest.anInvitationsLine_fitsThePrintsLine_theDayWhole`.
- **D5 — not fixed.** Dropping `idx_wish_event_date` takes a migration of its own for a table of at most 20 rows a party; left for
  the next migration that touches these tables.
- **S1** — `WishService` is a constructor dependency of `DjDashboardController` (no optional setter); the 14 tests that build the
  controller pass a mock (or the test's own `WishService`).
- **S2** — imports in place of the fully qualified names the branch added (`DjDashboardController`, `WishService`, `DjWishesController`).
- **S3** — the owner's words and the analysis' link moved out of `docs/context/05-1i-wishes.md` (kept here: the owner, 2026-11-13,
  "nie tylko na wesela"; the analysis https://claude.ai/artifact/SWNHdiZB3q1MoK6Twkah77, variant A).

Checks after the second round: `unit` 1291, `it` 167, `browser` 400 scenarios, `audit -t wish,wishes,qr-print,settings` — all OK.
