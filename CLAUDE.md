# Scan2Play — instructions for Claude Code

@AGENTS.md

## Read first

- `PROJECT_CONTEXT.md` — architecture, domain model, endpoints. **Section 14** is the "Master Queue" roadmap
  with a stage table (which stage is done, what is next); Section 10 covers Flyway migrations.
- `SESSION_HANDOFF.md` — the current state of the work, the next step and open items.

## Working agreements (the project owner's preferences)

- **Leave changes uncommitted** so they can be reviewed as a diff in IntelliJ; commit and push only when asked.
- **The app runs from IntelliJ** against `target/classes` with `spring-boot-devtools`, which restarts it whenever
  that directory changes. Do not run `mvnw` inside the repo while it is running: copy the repo without
  `target/`, `.git` and `.idea` to a scratch directory and build/test there
  (`.\mvnw.cmd -B -ntp test "-Dtest=!Scan2playApplicationTests"`; that excluded test needs a full environment and a DB).
- **Never touch the developer's own database `scan2play`** (read-only inspection is fine). For anything that writes,
  create a throwaway `s2p_*` database in the local PostgreSQL (defaults from `application.properties`: user
  `postgres`, password `1111`) and drop it afterwards.
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
  `FallbackTrackCommandService` was found only by a stress test on a real database. After touching that class or
  `FallbackTrackRepository`, run a throw-away `@SpringBootTest` in the scratch copy (not in the repo) with `PGDATABASE=s2p_...`
  and dummy `GOOGLE_AI_API_KEY`, `SPOTIFY_CLIENT_ID`, `SPOTIFY_CLIENT_SECRET`, `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`;
  startup runs Flyway and Hibernate validation. Drop the database afterwards.
- **Browser tests** (`src/test/browser`, guide in its `README.md`): `python src/test/browser/run.py` runs the real
  `youtube-autopilot.js` / `dashboard.js` on the real rendered dashboard in a headless Chrome. It copies the repo to a work directory
  (`%TEMP%\scan2play-browser-tests`) and runs Maven there — never in the repo — so it is safe while the app runs. `--no-render`
  skips Maven (a quick loop while editing scenarios). A new behaviour of those scripts gets a scenario, seen red before green. The
  fixture of real answers is recorded by `PlayLogFixtureRecorderTest` against a throw-away `s2p_*` database (README).
- **Secrets:** never write API keys or passwords into the repo, docs or memory. If one shows up in a chat or a
  screenshot, tell the owner to rotate or restrict it.
