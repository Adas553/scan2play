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
- **Secrets:** never write API keys or passwords into the repo, docs or memory. If one shows up in a chat or a
  screenshot, tell the owner to rotate or restrict it.
