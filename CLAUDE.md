# Scan2Play — instructions for Claude Code

@AGENTS.md

## Read first

- `PROJECT_CONTEXT.md` — architecture, domain model, endpoints, as they are now (~550 lines); Section 5.4 is the YouTube
  player, Section 10 Flyway migrations and the deploy checklist. Keep it to what is true now: the history (decisions, reports,
  how things were verified) goes to `docs/history/` — `project-context-2026-10-01.md` is the long version, word for word.
- `SESSION_HANDOFF.md` — the current state of the work, the next step and open items.

## Working agreements (the project owner's preferences)

- **Leave changes uncommitted** so they can be reviewed as a diff in IntelliJ; commit and push only when asked.
- **The app runs from IntelliJ** against `target/classes` with `spring-boot-devtools`, which restarts it whenever
  that directory changes. Do not run `mvnw` inside the repo while it is running: copy the repo without
  `target/`, `.git` and `.idea` to a scratch directory and build/test there
  (`.\mvnw.cmd -B -ntp test "-Dtest=!Scan2playApplicationTests,!*IT"`; that excluded test needs a full environment and a DB, and
  `-Dtest` replaces surefire's own name patterns, so the `*IT` database tests must be excluded by name — without it they are
  skipped anyway, outside failsafe, and counted as skipped).
- **Never touch the developer's own database `scan2play`** (read-only inspection is fine). For anything that writes,
  create a throwaway `s2p_*` database in the local PostgreSQL (user `postgres`, password `1111` — the
  defaults of `application-local.properties`, the profile `local`) and drop it afterwards.
- **Line endings:** some files are committed with CRLF (`PROJECT_CONTEXT.md`, `AGENTS.md`), `core.autocrlf=true`.
  Edit with the Edit tool; do not use `sed -i` (it converts CRLF to LF and turns the diff into a whole-file rewrite).
  After a scripted edit check `git ls-files --eol <file>` and `git diff --stat`.
- **`messages*.properties` keep every non-ASCII character as a literal backslash-u escape (four hex digits).** The
  Write/Edit/Bash tool inputs *decode* such an escape when you type it, so it never reaches the file as text. Put the real
  characters in a scratch file and let a script convert them (`chr(92) + 'u' + hex`, UTF-16 units so emoji work), encode the
  whole result *before* opening the file for writing (a failed encode after `open(path, 'w')` leaves an empty file — it
  happened once), keep the CRLF, and check `git diff`. A long Python heredoc in Bash may be rejected: write the script to a
  file with the Write tool and run it.
- **Queue SQL needs a check against a real PostgreSQL.** Mocked unit tests cannot show locking problems — a deadlock in
  `FallbackTrackCommandService` was found only by a stress test on a real database. The `*IT` tests do it (base class
  `PostgresIntegrationTest`; `FallbackQueueIT`, `FallbackQueueConcurrencyIT` — red if the advisory lock goes —, `MigrationIT`,
  `SongRequestRepositoryIT`): in the scratch copy, `.\mvnw.cmd -B -ntp verify -Pit` (only the ITs; the local PostgreSQL 18, user
  `postgres`, or `PGHOST`/`PGPORT`/`PGUSER`/`PGPASSWORD`). Each run creates and drops its own `s2p_it_*` database — never the
  developer's. GitHub runs them in `.github/workflows/db-tests.yml` (PostgreSQL 18, as on Railway). After touching that class,
  `FallbackTrackRepository` or a migration, run them, and give new queue SQL a test there. When copying the repo to the scratch
  directory, delete its `target/classes`: a copy that keeps the old file times leaves Maven's stale classes in place.
- **Browser tests** (`src/test/browser`, guide in its `README.md`): `python src/test/browser/run.py` runs the real
  `youtube-autopilot.js` / `js/dashboard/*.js` (ES modules; they talk through the `s2p:*` events of `js/dashboard/events.js`, never
  through `window`) on the real rendered dashboard in a headless Chrome. It copies the repo to a work directory
  (`%TEMP%\scan2play-browser-tests`) and runs Maven there — never in the repo — so it is safe while the app runs. `--no-render`
  skips Maven (a quick loop while editing scenarios). A new behaviour of those scripts gets a scenario, seen red before green. The
  fixture of real answers is recorded by `PlayLogFixtureRecorderTest` against a throw-away `s2p_*` database (README).
- **Secrets:** never write API keys or passwords into the repo, docs or memory. If one shows up in a chat or a
  screenshot, tell the owner to rotate or restrict it.
