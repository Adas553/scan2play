# Session Handoff — older "Start here" items, moved 2026-11-07

Moved word for word from `SESSION_HANDOFF.md` when it was cut down to the current state (the releases #219 … #236 and the
review of 2026-11-05 — all on production since #237 / #246).

- **2026-11-05 — the state.** `main` = `09667ea` (the release merge #236), deployed 2026-11-05 13:13 UTC, SUCCESS. Checked (Railway,
  read only): PostgreSQL 18.6, Flyway "Successfully validated 65 migrations", "E-mails on (Brevo), from contact@scan2play.com.pl",
  started in 9.2 s. Every item of "Next" below that said "waiting for a review / a release" is on production: V48 and V49 with #192
  (2026-10-28), V50–V53 with #197 (2026-10-29), V55 with #207 (2026-10-30), V56 with #214 (2026-10-31), V57 and V58 with #221
  (applied 2026-11-04 13:23 UTC, "now at version v58"), V59 and V60 with #224 (17:16 UTC, v60), V61–V63 with #228 (2026-11-05
  08:49 UTC, v63), V64 with #230 (09:57 UTC, v64).
- **Released 2026-11-04 … 05** (#219 … #236): the sessions' attributes written with `ON CONFLICT DO UPDATE` and the pages' faint cyan
  glow (#219); the library's details and the guests' suggestions "✓ DJ ma" (#221, item 7); "Motyw dla gości" and the library's
  spellings (#224, #226 the ghost of "Straszny"; item 8); "Podsumowanie wieczoru" v1–v3 and the refusals before the AI (#228, item 9);
  the automatic e-mails through Brevo (#230, item 1c), Google's login asking which account (#232), the sender `contact@` (#234), the
  welcome's words (#236).
- **The review of 2026-11-05** (`docs/history/review-2026-11-05.md`): nothing critical; three important points and a white card,
  **fixed on `claude/sharp-planck-nbkp7f`, not committed yet** (the owner reviews the diff): "Nie wysyłaj mi tego" only asks on its
  `GET` and switches off on its `POST` (mail filters open links: they switched the morning summary off unseen) — `EmailStopWebTest`;
  the welcome without that link and without `List-Unsubscribe`; the pages of the link with the dark card (`.page-email`; Bootstrap's
  white card made their words unreadable on production since #230 — scenarios `email-stop-*`); this file (commit `94ff45e`). Then its small points: all
  fixed on the same branch but 7 (secrets in the paths' logs, accepted as `/h/`, `/k/`) and 13 (`prompt=select_account`, chosen); for
  Brevo's sub-processors are now in the privacy policy (point 10, from Brevo's terms, annex 2).
