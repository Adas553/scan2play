# Session handoff — moved 2026-11-13

From `SESSION_HANDOFF.md`'s "Start here", word for word: the previous state item and the two branches released since —
`claude/confident-bell-nf6tu7` (PR #261) and `claude/blissful-fermi-izymid` (PR #262, V67). `main` = `d033d77` (#262), deployed
2026-11-13 06:11 UTC, SUCCESS: Flyway 66 → 67 ("booking contact", 0.021 s), started in 5.3 s, no WARN, 0 × 5xx of 166 requests
in the first six hours, RAM 0.57–0.62 GB (Railway, read only); curl without cookies: `/`, `/pro`, `/dj-weselny` 200 to `</html>`.

- **2026-11-07 — the state.** `main` = `58ca921` (the release merge #249; #247 the review's hypotheses H2–H4, #248 touch targets
  on a phone, `tools/page_audit.py`, `PROJECT_CONTEXT.md` split into `docs/context/`, `tools/check.py` and the project agents),
  deployed 2026-11-07 10:56 UTC, SUCCESS. Checked (Railway, read only): started in 5.3 s, no migration, no WARN, 0 × 5xx (483
  requests), RAM 0.56 GB steady; curl without cookies: `/`, `/pro`, `/dj-weselny` 200 to `</html>`. No open PR.
- **2026-11-12 — `claude/blissful-fermi-izymid`, no PR yet (V67 → a database copy first):** item 11 B0 — the DJ's e-mail and phone
  for bookings in "Zarezerwuj tego DJ-a" (Pro, `/dj/dashboard/booking-contact`) — and A0 — the refusal "not a song" points to the
  dedication. The privacy policy, the terms, `/pro`, the guide with it. To try: the card on a phone, the `mailto:` on a phone.
- **2026-11-12 — `claude/confident-bell-nf6tu7`, no PR yet:** three pictures on "Jak działa Scan2Play" (the panel on a computer — the
  landing page's —, on a phone, the evening summary; `screenshots.py --only guide`), `CLAUDE.md` (a cloud session commits to its own
  branch), `docs/ideas.md` item 10. The guide's videos wait: PR #259 a draft, not to be merged as it is (item 10).
