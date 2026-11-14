# Review 2026-11-14 — branch `claude/happy-dijkstra-txy1s2` (the QR print: the logo's badge, V70, the cut margin, the cards' cut marks)

Scope: `git diff origin/main...origin/claude/happy-dijkstra-txy1s2`. Merge base `8162724`, head `295e981`: the 5 commits
`4c2b402`, `2caf8fd` (V70), `cef1f5d`, `5b80516`, `295e981`. 20 files, +467 / −34. **`8162724` is not part of the diff:** PRs
Adas553/scan2play#265 and #266 were merged at 09:47 UTC with the branch at `8162724` (the first of the six commits), so `main` and
`dev` already have it. The other five commits are in no PR (see P1).

## Checks run (cloud session, git worktrees of the branch and of `origin/main`, `tools/check.py`)

| Step | Result |
|---|---|
| `unit` | branch 1306 tests, `main` 1304 — 0 failed, OK |
| `it` | 169 tests, 0 failed, OK (`MigrationIT.theQrPrint_hasALogoBadge_automaticByDefault` included). PostgreSQL **16** of the container; Railway and CI run 18 |
| `browser` | 420 scenarios, OK |
| `audit -t qr-print` | 19 pages × 320 / 390 / 1280: no sideways scrolling, no console errors, no small targets. OK |
| the new scenarios on `main`'s code (the branch's `poster-paper.js`, `poster-social.js` with `main`'s CSS and JS) | 11 of 12 red. `poster-paper-logo-size` (the poster) is green there: `8162724` already fixed the poster. `poster-cards-tight-note-none` and `poster-tight-note-not-on-the-poster` are red only because `#posterTightNote` is missing, so they guard against false alarms, not against a regression |
| mutations of the branch (each reverted afterwards) | 6 of 7 caught: `checkMargins` without the zoom correction (`scale = 1`), the `:has` rule removed (rows or code), `badge-always` without its white, `qr-social.js` badge back to the class test, the table number back at 1.5 / 2.5 mm. **Not caught:** `:scope > .table-no` removed from `checkMargins` (T1) |
| the matrix, `main` vs branch (Playwright, `server.py` on both rendered copies, 1280 px) | 3 layouts × 8 themes × the theme's paper / light own `#f3ead6` / dark own `#1d2633` × logo square / wide / tall / none (a canvas `data:` URL) = 288 per side. For each: overlaps of the parts, the smallest distance to the card's or face's edge, the logo's content box (without padding), the gap between the badge and the part under it, `#posterTightNote`, the pages of `page.pdf()`, and the screenshot compared pixel by pixel |
| stress, branch only | cards and tents × 3 logos × 0–3 profiles × 8 themes × 4 fonts × normal / large heading, the longest heading (40) and line (60), 50 tables, the tip on: 1536 combinations, every card and face measured |
| the other print pages, `main` vs branch | every `qr-print-*` page of the harness at 1280 and 390 px: a full screenshot, the PDF's pages, console errors |

### What the measurements say

- **Branch:** in the 288 combinations no overlaps, no part nearer than 3 mm to a card's or face's edge, the badge at least 1 mm
  above the code everywhere, 1 PDF page each, no console errors. The note is shown exactly when the measurement says < 3 mm. Across
  papers the logo's content box keeps its size and place on cards and tents. The poster is the exception: on the classic theme it moves
  by 1.7–2 mm between the theme's paper and an own paper. `main` behaves the same there because the classic paper refits the code, so
  this is not new. The stress run flagged nothing (1536 combinations): every part 3 mm in, no overlaps, the note in step with the
  measurement, cards 7 pages and tents 50 for 50 tables.
- **`main` (= production since #265):** 50 card combinations have a part 0.7–2.7 mm from the cut: the logo with three profiles on any
  paper, and on the classic theme with an own paper. In 102 card and tent combinations (club or own paper) the white badge **touches the
  code** (0 mm on cards, 0.5 mm on the tent). The branch fixes both.
- **No change at all, pixel for pixel:** the poster and the tent without a logo, `qr-print-poster`, `-poster-own`, `-poster-tables`,
  `-poster-own-staff` (the staff page without an editor), `-poster-own-tables-invitation` (the invitation), `-tent`, `-tent-tables`. The
  PDF page counts are as on `main` for every page (cards with tables 2, tents with tables 3, the rest 1).
- **Intended changes:** the cards (every one: the corner marks and light dashes instead of the 1 px border, which moves their texts
  by about 0.25 mm), `qr-print-cards-invitation` and `-cards-own-tables-invitation` (the same marks), and the logo's sizes (P2–P4).

## Findings (most serious first)

### P1 — five of the six commits are in no PR; production runs `8162724` alone — **high (process / production)**
- PRs #265 (`main`) and #266 (`dev`) were merged at 09:47 UTC with head `8162724`. `main` = `9f783f5`.
- `SESSION_HANDOFF.md:38` (on the branch) says the PRs carry everything: the logo's room, V70, the margin note, the cut marks.
- With `8162724` alone, the badge on the club's and own papers sits *outside* the logo (content-box, negative margins). On a card and a
  tent it touches the code (measured 0 / 0.5 mm, see above), and a wide logo's badge reaches past the card's column into the texts
  (`poster-paper-logo-size-cards` on `main`: −2.0 mm). The branch fixes this.
- Fix: new PRs `claude/happy-dijkstra-txy1s2` → `main` and → `dev` (after the review's fixes). Make a database copy first (V70).
  Correct the handoff line (new PR numbers; #265 / #266 = `8162724` only).

### P2 — the logo on a card is 2 mm smaller than on `main`, on every paper, the classic one included — **medium (look; the owner decides)**
- `qr-print.css:1324-1329` (`.card .dj-logo`: `max-height: 14mm` now *includes* `padding: 1mm 1.5mm`, border-box).
- Measured: square 14 × 14 → 12 × 12 mm, wide 40 × 10 → 37 × 9.2 mm, tall 4.7 × 14 → 4 × 12 mm, in all 24 theme × paper
  combinations. The logo keeps one size between papers, as the docs say. Compared with `main` it is smaller everywhere, and with three
  profiles the code is also 33 mm instead of 36 (`qr-print.css:1342-1350`). `docs/context/05-1b-dashboard-qr-print.md:73-81` says "the logo never changes
  size or place". That holds between papers but does not say that the logo shrank.
- This is the cost of keeping the badge's room on every paper inside a 40 mm column. The poster (45 mm) and the tent (16 mm) keep their
  sizes because their boxes grew by the padding (`max-height: 48mm`, `19mm`). The card cannot: its column is 40 mm wide and the
  column's height is already full.
- Fix: the owner decides. Either (a) accept it and say it in 05-1b ("on a card the logo is 12 mm high, within its 14 mm with the
  badge's room"), or (b) let the card's `max-height` grow to 16 mm (14 + padding) and pay with a smaller code, which is probably not
  worth it. Recommendation: (a).

### P3 — the poster: the heading 1.5 mm lower and the code ~1.6 mm smaller than on `main`, on every paper — **low (look)**
- `qr-print.css:1287-1292` (`.poster .dj-logo`: `padding-bottom` 1.5 mm plus `margin-bottom: 4mm`).
- Measured on `main` vs the branch: `h1` +1.5 mm, `img.qr` −1.6 mm (`fitPoster` gives the code what is left), the same on every paper
  and theme. The logo itself is unchanged (45 mm). Under the logo there are now 5.5 mm of room (4 mm under the badge) instead of 4 mm.
- Fix, optional: `margin-bottom: 2.5mm` keeps the heading and the code as on `main` (4 mm from the logo, 2.5 mm from the badge).
  Leaving it as it is also matches the comment ("the heading 4 mm under the badge").

### P4 — the tent: the code and the profiles 1.5 mm lower than on `main` — **info**
- `qr-print.css:1474-1477` (`.face .dj-logo` `max-height: 19mm`, 16 mm of logo plus the padding). The face has the room: the smallest
  distance to its edge is 8 mm, and 50 tents still make 50 pages. Nothing to fix.

### T1 — `checkMargins` measuring the table number has no test — **low (tests)**
- `qr-print.js:144` (`:scope > .table-no`). The mutation that drops it leaves every scenario green.
  `poster-cards-table-number-margin` (`poster-paper.js:399-420`) measures the number's position itself, not that the note covers it.
- Fix: in that scenario, move the number with the CSSOM (`no.style.top = '1mm'`), tick a box off and on, and expect the note. Then
  reset the number and expect no note.

### T2 — "the choice goes along to the other layouts" has no scenario — **low (tests)**
- `poster-paper.js:264` says it, but `poster-logo-badge-choice` only checks the class, the colour and "unsaved". The code is
  generic (`qr-print.js` `stash` / `unstash`, the radio by its name, then `changeLook` → `showBadge`), so it works. Nothing guards it.
- Fix: one step that calls `stash()` (it is triggered by switching layout: a click on the cards' layout link), reloads the
  `qr-print-cards-logo` page and expects `badge-always`. Alternatively, drop the claim from the comment.

### Q1 — `checkMargins` sets the note's text on every call — **low (performance)**
- `qr-print.js:151-152`. It runs from `fitTexts` (each keystroke in the heading, each `input` of a colour picker while dragging)
  and from `showPart`. `note.textContent = …` replaces the text node even when nothing changed. The editor column is then invalidated,
  and the next measurement (`fitPoster`, `fitPreview`) lays out again. The reads themselves come after `fitPreview`'s writes and cost
  one layout, which `fitTexts` already pays.
- Fix: `if (note.hidden !== !tight) { note.hidden = !tight; note.textContent = tight ? note.dataset.tight : ''; }`. The weak-colour
  note at `qr-print.js:82` has the same pattern; change it too or leave it.

### U1 — "Białe tło pod logo" stays when "Twoje logo" is unticked — **low (UX)**
- `qr-print.html:189-195`: the fieldset is shown whenever the party has a logo (`djLogo != null`). With the logo's box off it changes
  nothing. Other choices of the editor that apply to one part behave the same way, so this is consistent but slightly confusing.
- Fix, optional: `showPart` hides the fieldset (`hidden`) while `[data-poster-toggle="logo"]` is off. Keep it in the form so that the
  value is still sent.

### U2 — the margin note talks about a card and cutting on a tent too — **low (texts)**
- `messages_pl.properties:960`, `messages.properties:963` (`qr.edit.tight`): "Coś na kartce … przy cięciu …". `checkMargins` also
  measures `.face-lower` (the tent), which is folded as well as cut.
- Fix: "Coś jest bliżej niż 3 mm od krawędzi i może zostać ucięte lub zagięte. …" / "Something comes within 3 mm of the edge and may be
  cut off or folded. …". Or leave it: a tent is cut out of its sheet too.

### C1 — comments carrying history (AGENTS.md) — **low**
- `qr-print.css:1340-1341` "the column was 63.6 mm in the card's 59.5 — the profiles 2 mm from the cut", `qr-print.css:1214`
  "(a border was drawn over the marks and dashed them too)", `docs/context/05-1b-dashboard-qr-print.md:81` "(it reached 2 mm from the
  cut)" (`docs/context` says what is true now), `poster-paper.js:182-184`, `:302` "(… it reached 2 mm)", `:399` "(it sat 1.5 mm from it)".
- Fix: say why, without "was": e.g. "the logo and all three profiles fill the column: the code 33 mm keeps every part 3 mm in". The
  measurements of before go to the commit message (they are there already).

## Checked, nothing to fix

- **V70** (`V70__poster_logo_badge.sql`): `varchar(6) NOT NULL DEFAULT 'AUTO'` and a CHECK of the three values, named
  `party_settings_poster_logo_badge_check`. It follows V54 (`poster_size`) line for line and matches the entity (`@Enumerated(STRING)`,
  `length = 6`, `nullable = false`, `@Builder.Default AUTO`). `ddl-auto=validate` starts: the ITs boot on it. On PostgreSQL 11+ the
  `ADD COLUMN … DEFAULT` with a constant writes no rows. The CHECK reads `party_settings` once under its lock, which is a small table.
  No other of the 104 branches of `origin` has a V70 or later, and `main` ends at V69. `MigrationIT` checks the default, the three values
  and the CHECK's name. **A database copy before the release** (`docs/database-backup.md`).
- **The form:** `PosterForm.badge` → `PosterLogoBadge.of`: trimmed, case-insensitive, anything else or nothing → AUTO
  (`DjPartySettingsControllerPosterTest.theLogosBadge_isSavedByName`). Without a logo the saved value goes as a hidden field, so a save from a
  party without a logo does not reset it. The permission (VIBE) and the endpoint are unchanged. `cssClass()` uses `Locale.ROOT`. The
  template's `toLowerCase()` (the message key) runs on AUTO / ALWAYS / NEVER, none of which has an "I", so a Turkish default locale
  changes nothing.
- **CSP / XSS:** no inline style or script added. The page sets classes (`showBadge`, as `showLook` does for theme / font / size)
  and `hidden`. `data-tight` comes from a message through `th:attr` (escaped) and is written with `textContent`. The scenarios that
  set `style.maxHeight` do it through the CSSOM, which the CSP allows.
- **`:has(… span:nth-child(3))`:** Chrome 105, Safari 15.4, Firefox 121. Where it is not supported the rule is dropped: the code stays
  36 mm, the profiles reach about 2 mm from the cut, and the note says so (`checkMargins` does not depend on `:has`). That is graceful.
  The stylesheet already uses `:has` (`.poster:has(.own-line…)`).
- **`qr-social.js`:** the badge is now read from the logo's computed background, so the picture follows the print for the papers'
  rule and the DJ's choice alike (`poster-social-logo-badge`; the mutation back to the class test is caught). `layout.badge` exists
  for the tests, like `layout.logo`.
- **The cut marks:** `--cut: var(--muted)`. On the classic theme it is #555, on the club's #b9bcc8, and on an own paper it follows the
  words' colour (#ffffff on #1d2633, #000000 on #f3ead6), so the marks show on every paper. They are a background, and `body` has
  `print-color-adjust: exact`, which is inherited, so they print without "background graphics". On a theme with a frame the top marks
  are under the frame (`border-top` 1.2 mm), as the comment says.
- **`QrPrintPageTest`:** the new test writes `qr-print-cards-logo-tables`, `-poster-logo-tables` and `-poster-own-tables`, three names
  that no scenario reads and no other test writes. The `-logo` pages of the scenarios are not overwritten. The `doesNotContain("value=\"AUTO\"")`
  narrowed to `<option value="AUTO"` is needed: the hidden `badge` field has that value.
- **Texts:** both files are LF, as before. Non-ASCII only as `\uXXXX` („ ” “ ”). The editor speaks to the DJ ("Twoje logo",
  "Twoim kolorze"), as its other hints do. None of the new texts is for guests or has a gendered verb.
- **Docs:** `04-1a` (the field), `05-1b` (the choice, the note, the marks, the scenarios' names), `10-2` (the V70 row), `11-12`
  (`badge`) match the code, apart from P2 and C1. (The `10-2` table keeps V62, V61, V60, V35 at its end, as on `main`.)

## Ready to merge?

**Yes, after P1 (new PRs), and the sooner the better:** production now has `8162724` alone, with the badge touching the code on
cards and tents. The branch fixes that, adds nothing broken, and every check passes. Before the PR: the owner's choice on P2 (to
document or not). P3–C1 are small and optional, ideally done in the same PR: T1 and Q1 are a few lines each. V70 needs a database copy
first.

## The fixes (2026-11-14, `claude/pensive-rubin-rpps6k` = the branch + the fixes; the owner: "zgoda, P2 jak uważasz, popraw wszystko")

- **P1:** new PRs from `claude/pensive-rubin-rpps6k` to `main` and `dev`. `SESSION_HANDOFF.md` says that #265 / #266 took `8162724` only.
- **P2:** (a) accepted. `05-1b` and the comment at `.card .dj-logo` say that on a card the logo itself is at most 37 × 12 mm.
- **P3:** `.poster .dj-logo` `margin-bottom: 2.5mm`. The heading and the code are where they are on `main`, on every paper (the matrix
  again: on the poster only the logo's box, its padding, differs; its content does not).
- **T1:** `poster-cards-table-number-margin` moves the number 1 mm from the cut and expects the note, then moves it back and expects
  none. The mutation that dropped `.table-no` from `checkMargins` is now caught.
- **T2:** `poster-switch-badge`: kept by the switch, and the next page has it picked and on the paper.
- **Q1:** `checkMargins` leaves the note alone when its state does not change.
- **U1:** the fieldset is `data-poster-part="logo"`, hidden with the logo's box and still sent. `poster-logo-badge-choice` and
  `QrPrintPageTest` check this.
- **U2:** `qr.edit.tight` now says "Coś jest bliżej niż 3 mm od krawędzi i może zostać ucięte lub zagięte. …" (and the same in English).
- **C1:** the comments in the CSS, `05-1b` and the scenarios no longer describe the earlier state.
- Checks: `unit` 1306 OK, `browser` 421 OK, `audit -t qr-print` 19 pages OK. The matrix of 288 against `main`: the branch has no
  overlaps, nothing within 3 mm, the badge 1 mm or more off the code, 1 page each.
