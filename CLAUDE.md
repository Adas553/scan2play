# Scan2Play — instructions for Claude Code

@AGENTS.md

## Read first

- `SESSION_HANDOFF.md`, its "Start here" — the current state of the work, the next step and open items.
- `PROJECT_CONTEXT.md` (~10 KB) — the index: Sections 1–3 (product, stack, architecture) and a table of where Sections 4–14 are,
  `docs/context/*.md`, one subject a file (5–19 KB each; together ~235 KB, ~65 000 tokens): **read only the parts the task needs**
  (e.g. Section 5 the DJ's and the guest's flows `05-*`, Section 10 Flyway and the deploy checklist `10-2-migrations.md`), or ask the
  `context-finder` agent. A "Section N" in the code is that row of the table. YouTube and Spotify are gone (2026-10-04): the full app is the tag
  `full-player-2026-10-04` and the clone `D:\Coding\scan2play-full` with its own database `scan2play_full` — leave them alone. Keep it to what is true now: the history (decisions, reports,
  how things were verified) goes to `docs/history/` — `project-context-2026-10-01.md` is the long version, word for word.
  `docs/history/` (~1 MB) is searched, never read whole.

## Checks and agents (to keep the conversation small)

- **`python tools/check.py unit|it|browser|all [-t <class, pattern or scenarios>]`** — the checks below in a copy of the repo (never
  in it; the copy's stale classes deleted), printing the totals and only what failed; Maven's log stays in `<work>-logs/`. In a cloud
  session: `TMPDIR=/tmp/s2p`, `S2P_CHROME=/opt/pw-browsers/chromium-*/chrome-linux64/chrome` for `browser`, and
  `pg_ctlcluster 16 main start` when `it` says PostgreSQL refused the connection.
- Project agents (`.claude/agents/`), each with its own context — their reading and logs never enter the main conversation:
  `test-runner` (runs `tools/check.py`, reports failures; the cheap model), `context-finder` (what the documents say about one
  subject, with `file:line`), `railway-reader` (production's deployments, logs and metrics, read only, never a variable's value).
  Use them for those jobs; keep design decisions, edits and reviews in the main conversation.

## Working agreements (the project owner's preferences)

- **Leave changes uncommitted** so they can be reviewed as a diff in IntelliJ; commit and push only when asked.
- **The app runs from IntelliJ** against `target/classes` with `spring-boot-devtools`, which restarts it whenever
  that directory changes. Do not run `mvnw` inside the repo while it is running: copy the repo without
  `target/`, `.git` and `.idea` to a scratch directory and build/test there
  (`.\mvnw.cmd -B -ntp test "-Dtest=!*IT"`; `-Dtest` replaces surefire's own name patterns, so the `*IT` database tests must be
  excluded by name — without it they are
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
- **SQL that locks or counts needs a check against a real PostgreSQL.** Mocked unit tests cannot show locking problems — a
  deadlock in the old background queue was found only by a stress test on a real database. The `*IT` tests do it (base class
  `PostgresIntegrationTest`; `SongRequestVotesIT` — red if the advisory lock goes —, `MigrationIT`, `SongRequestRepositoryIT`, the
  `*MigrationIT` of a migration that moves rows): in the scratch copy, `.\mvnw.cmd -B -ntp verify -Pit` (only the ITs; the local PostgreSQL 18, user
  `postgres`, or `PGHOST`/`PGPORT`/`PGUSER`/`PGPASSWORD`). Each run creates and drops its own `s2p_it_*` database — never the
  developer's. GitHub runs them in `.github/workflows/db-tests.yml` (PostgreSQL 18, as on Railway). After touching
  `SongRequestCommandService`, `SongRequestRepository` or a migration, run them, and give new SQL of that kind a test there. When copying the repo to the scratch
  directory, delete its `target/classes`: a copy that keeps the old file times leaves Maven's stale classes in place.
- **Browser tests** (`src/test/browser`, guide in its `README.md`): `python src/test/browser/run.py` runs the real
  `js/dashboard/*.js` (ES modules; they talk through the `s2p:*` events of `js/dashboard/events.js`, never through `window`) on the
  real rendered dashboard (and the guest page, the QR print page) in a headless Chrome, under the real CSP. It copies the repo to a work directory
  (`%TEMP%\scan2play-browser-tests`) and runs Maven there — never in the repo — so it is safe while the app runs. `--no-render`
  skips Maven (a quick loop while editing scenarios). `--clean` starts from a fresh work directory. A new behaviour of those scripts
  gets a scenario, seen red before green.
- **Secrets:** never write API keys or passwords into the repo, docs or memory. If one shows up in a chat or a
  screenshot, tell the owner to rotate or restrict it.
