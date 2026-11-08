# Session Handoff — older items, moved 2026-11-07 (after the release #249)

Moved word for word from `SESSION_HANDOFF.md` after the releases #246 … #249 (all on production since 2026-11-07 10:56 UTC).

- **2026-11-06 — the state.** `main` = `32af3bf` (the release merge #246; #245 the same into `dev`), deployed 2026-11-06 22:34 UTC,
  SUCCESS. Checked (Railway, read only): Spring Boot 4.1.2, Tomcat 11.0.27, "Schema is up to date" (no migration), "Origin guard on".
  It brought the full review of 2026-11-06 (`docs/history/review-2026-11-06.md`, nothing critical): the logo's upload reads its body
  only once it has its turn (a burst of uploads could fill the 512 MB heap); the guests' limit per address 30 → 60 a 10 minutes (the
  owner: a room on one Wi-Fi); "Ostatnie wieczory" whole on a phone; the pages "Dla kogo"' ways back 44 px; no "👍 0" on the TV; stack
  traces cut to 30 lines a cause; **Spring Boot 4.1.2** (4.0's free updates end 2026-12-31; 4.2 is at RC2 — 4.1 holds until ~2027-07);
  **`OriginGuardFilter`** (Railway's own address answered past Cloudflare).

- **The review's hypotheses H2–H4** — fixed on `claude/adoring-edison-dr87gy` (restarted from `main` after #246): H2 a login's return
  from Google that fails for a person logged in already (a second "Zaloguj" replaced the first) opens the panel, not "Błąd logowania"
  (`SecurityConfig.loginFailure`, `LoginReturnTest`); H3 a library's file must arrive within 5 minutes, or 408 "Wgrywanie trwało za
  długo" and the one turn of reading is free again (`DjLibraryController.READ_LIMIT`); H4 past the party's daily limit a request from a
  new address is refused without a window kept for it (`GuestRequestLimiter.tryAcquire`, an IPv6 flood no longer fills the party's map).

**State:** (1) done on `claude/compassionate-cori-g35pvj` (template tests green: `ProPageRenderTest` with the new
`everyPlanFeature_isOnThePage`, `GuidePageRenderTest`, `DashboardPageRenderTest`, `SmokeTest`; the browser tests — `design.js` has the new
address). (2) done on the same branch: `/pro` is the pricing page (variant A; `/cennik`, `/pricing` redirect to it), the footer's
"Cennik", the landing page's "Zobacz cennik →", the account menu's "Twój plan: Free · Cennik" — all only while enforced. Unit tests
1131 + the new ones green, the browser tests 381 / 381 (in a cloud session: `--no-render --work <a copy where mvn test rendered the
pages>`, `--chrome /opt/pw-browsers/…/chrome --no-sandbox`, `TMPDIR=/tmp/s2p` — the copy's mvnw cannot download Maven there; `mvn`
is installed). (3) done on the same branch: `FreeAiLimit` (PROJECT_CONTEXT 5.2, "The Free plan's AI checks") — the request past
150 goes to the DJ unchecked, the panel's line at 80 % and bar at the limit (polled), the settings' badge, the organiser's note the next
day with "Zobacz, co daje Pro"; unit tests 1141 green (`FreeAiLimitTest`, `SongEvaluationServiceTest`, `DjDashboardControllerGuestLimitsTest`,
`DashboardPageRenderTest`), the browser tests 382 / 382 (`free-ai-limit-follows-the-poll` seen red on the old `polling.js`). Off for
everyone while `PLANS_ENFORCE=false`. The next day's note: a render test (`theDayAfter_…`), no browser scenario. (4) done on the same branch, changed from
the plan: **the gift is a variable, not a migration** — `PLANS_GIFT_UNTIL` (a day): a V65 would have written the 30 days at the next
deploy, long before a switch; the panel's line "Do … masz Pro w prezencie" (`PlanServiceTest`, `DashboardPageRenderTest`); the terms'
new section 5 "Plany Free i Pro" (PL, EN; true now and after); the privacy policy needed nothing (the plan and the trial were in it).
(5) **not switched — the owner, 2026-11-05: everyone keeps Pro (`PLANS_ENFORCE=false`) until there are customers and a registered
business**; the day's steps: `docs/plans-switch-on.md`. Unit tests 1143, browser tests 382 / 382.

**The review of PR #239** (2026-11-05, `docs/history/review-2026-11-05-pr239.md`; the owner agreed to every proposal): fixed on
`claude/pensive-carson-guv7kw`, which is the PR's branch plus seven commits — to be merged into the PR or opened as its own PR to `dev`.
W1 no "Free" in the "bez oceny AI" tooltip while the plans are off; W2 `X-Guest-Limits-Use`'s third number only where the Free limit
applies (an open panel's old `polling.js` takes two); W3 the pricing page true to the code (Pro: every request checked; 300 a day for
both); W4 the terms' section 5 true before and after the switch; W5 (a) the Free card says requests past the limit are not on the
guests' list nor the TV; W6 an unchecked request for a song played lately refused by the code (`playedLately`); W7 a failed AI call
given back; W8 the next day's note only until the evening's first request, and only when some came unchecked; D1–D12 (a 301 for
`/cennik`, the table on a phone, the staff not told of the plan, the count by the party's row so "Zmień kod" keeps it, the trial during
the gift starts at its end, `PLANS_GIFT_UNTIL` badly written names itself). Unit tests and the browser tests green (the numbers: the
last commit's message). Then: the switch, on a Tuesday / Wednesday morning (`docs/plans-switch-on.md`; rollback `PLANS_ENFORCE=false`).

- Old PRs to `main`: #2 and #7 (April) — close them.
