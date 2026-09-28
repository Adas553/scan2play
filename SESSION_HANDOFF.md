# Session Handoff — 2026-09-28

Notes from a Claude Code session (returning to Scan2Play after ~5 months away). Written so
a local Claude Code session (or a person) picking this up doesn't have to reconstruct
context from scratch. Session link: https://claude.ai/code/session_016RfaHddpN1HTTF2tTtNDCN

## What happened, in order

1. **Attached the repo, compared local vs. GitHub.** `PROJECT_CONTEXT.md`, `AGENTS.md`, and
   `.github/copilot-instructions.md` existed only on the local machine
   (`D:\Coding\scan2play`) — never committed, in any branch, ever. `AGENTS.md` and
   `copilot-instructions.md` were identical. `pom.xml` differed only by line endings
   (CRLF), not content.
2. **Found `PROJECT_CONTEXT.md` was stale.** Its Section 5.4 described a 500ms polling
   watcher in `youtube-autopilot.js` that had actually been removed by commit `68e84c9`
   (2026-04-06) — a day *after* the doc's own "last updated" date. Fixed it to match the
   real code.
3. **Created the `dev` branch**, pushed the three local-only docs to it (not `main`) —
   see the "why on both branches" reasoning below, they've since been pushed to both.
4. **Read the Gemini conversation the user pasted in** (about moving YouTube Auto-Pilot
   logic from frontend to backend — a "Master Queue" / "Dumb Client, Smart Server"
   design). Wrote it up as **Section 14** of `PROJECT_CONTEXT.md`, as a roadmap.
5. **User asked to start the refactor.** Before writing code, re-read the actual
   `DjService`/`SongEvaluationService`/`youtube-autopilot.js` and found the Gemini plan's
   central assumption — a `PENDING`/`APPROVED` guest-song approval gate tied to Auto-Pilot
   — **doesn't exist in the real code**. Auto-Pilot ON/OFF has only ever controlled
   whether the client auto-advances vs. the DJ clicking songs manually; every
   Gemini-accepted song already goes straight to `accepted`. Scoped the refactor down
   accordingly (see Section 14 "Phase 1" for the full reasoning) instead of building
   toward a workflow that was never real.
6. **Shipped Phase 1**: moved the "which guest song plays next" decision server-side
   (new `GET /dj/dashboard/next-guest-track`, backed by `DjService.findNextPlayableGuestTrack`,
   reusing the existing 3s-TTL dashboard-queue cache). Rewrote the relevant parts of
   `youtube-autopilot.js` to call it instead of scanning the queue table's DOM, dropping
   the old `markedAsPlayedIds`/`skippedSongIds` client-side bookkeeping (the server's
   query now covers both). Added a `stateVersion` counter + an in-flight guard so the
   now-`async` player event handlers can't act on a stale answer.
   **Not build-verified** — this sandbox's network policy blocks Maven
   Central/`repo.spring.io`, so `mvn compile` couldn't run. Reviewed every changed file by
   hand for compile-correctness, but **run a real build/test before merging to `main`.**
7. **User tried "Start Party with YouTube" locally, hit a Railway "Not Found" page.**
   Root cause: `spring.security.oauth2.client.registration.google.redirect-uri` (and the
   Spotify equivalent) were hardcoded to `https://www.scan2play.com.pl/...`, so Google
   always sent the browser back to production regardless of where login started — and
   production (Railway) is currently paused/unpaid, hence the Railway edge 404.
   **Fixed**: both now use Spring's own `{baseUrl}/login/oauth2/code/{registrationId}`
   template, which resolves to whichever host actually started the login.

## Current repo state

- `main`: unchanged this session, matches what was on GitHub before (production/stable —
  not actually deployed right now, Railway is paused).
- `dev`: 4 commits ahead of `main` —
  1. Add AI-agent context docs (`PROJECT_CONTEXT.md` fix + `AGENTS.md`)
  2. Mirror to `.github/copilot-instructions.md`
  3. Master Queue Phase 1 (server-side next-guest-track)
  4. OAuth2 redirect-uri fix
- Both branches are pushed to GitHub (`Adas553/scan2play`).

## Open items / what's NOT done

1. **Build/test the Phase 1 change for real** (`mvnw compile`, then actually run
   Auto-Pilot end to end: guest song → next, fallback → guest handover, Auto-Pilot OFF
   manual play, a broken/removed video getting skipped). This session could only do a
   manual code review, not a real build.
2. **Google Cloud Console (and Spotify Developer Dashboard, if testing Spotify locally)
   need a new Authorized Redirect URI added by hand**: `http://localhost:8080/login/oauth2/code/google`
   (`.../spotify` for Spotify) — alongside the existing production one, not replacing it.
   Claude can't do this — it's the user's Google/Spotify account.
3. **Section 14 Phase 2 not started**: background/fallback tracks are still 100%
   client-side (YouTube's own `loadPlaylist()`, zero quota cost). Moving them server-side
   (fetch 50 items via the Data API once, store, unify with the guest-track query) is a
   real feature add, not just a refactor — see Section 14 for the tradeoff. Pre-fetching
   (~15s before track-end) also deliberately skipped — see Section 14 for why.
4. **The Spotify *playback-connect* flow** (`spotify.oauth.redirect-uri`, a custom
   property read by `SpotifyAuthService` — separate from Spring Security's OAuth2 login)
   has the same hardcoded-to-production problem as the login redirect-uris did, but it's
   a custom property/code path, not a Spring default — needs an actual code change, not
   just flipping a property. Not touched this session since it wasn't blocking anything.
5. **GitHub wasn't linked to Claude at the start of this session** — it is now (the user
   connected it partway through, under Settings → Connectors), so a future Claude session
   should be able to push directly without that back-and-forth.

## Where to look

- `PROJECT_CONTEXT.md` — architecture, domain model, endpoints; Section 14 has the full
  Master Queue plan (Phase 1 done / Phase 2 not) and the caveat about the invented
  PENDING/APPROVED workflow that isn't real.
- `AGENTS.md` (mirrored to `.github/copilot-instructions.md`) — coding guidelines +
  branch policy (`main` stable, `dev` for free experimentation).
