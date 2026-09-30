# Browser tests of the DJ dashboard

The real `youtube-autopilot.js` and `dashboard.js`, running on the **real rendered `dashboard.html`** in a headless Chrome, against a
small stand-in for the server and a fake YouTube player. No Node, no new dependency — Python 3 (standard library only), the Java that
`mvnw` uses, and Chrome or Edge.

```
python src/test/browser/run.py                 # every scenario
python src/test/browser/run.py boundary        # one, or several, by name
python src/test/browser/run.py --list
python src/test/browser/run.py --no-render     # a quick loop while editing scenarios: copy the repo, skip Maven
python src/test/browser/run.py --clean         # start from an empty work directory (a full build)
python src/test/browser/run.py --no-sandbox    # Chrome without its sandbox: for a CI runner that forbids it (see "In CI")
```

A run takes about a minute (most of it waiting for real timers: a lease report comes every 3 s) and ends with one line per scenario,
the failing steps with what was expected and what happened, and exit code 1 if anything failed. The `--chrome PATH` option (or
`S2P_CHROME`) names another browser; `--cdn` lets the page load Bootstrap from its CDN (see below).

**Never `mvnw` inside the repo** (`CLAUDE.md`): the app runs from IntelliJ out of `target/classes` and devtools restarts it whenever that
changes. `run.py` copies the repo — without `target/`, `.git`, `.idea` — to `%TEMP%\scan2play-browser-tests` (a second run copies only
what changed, so Maven there builds incrementally), runs `DashboardPageRenderTest` in the copy and serves everything from the copy.

## How it works

```
run.py ── copy of the repo ── mvnw test -Dtest=DashboardPageRenderTest ──► target/browser-harness/dashboard.html (+ -manual, fallback-queue)
   │
   ├─ server.py (stand-in, 127.0.0.1:<free port>) serves those pages, the real src/main/resources/static/{js,css},
   │     this directory (/harness/...), and answers the endpoints the scripts call
   └─ for each scenario: a headless Chrome with a fresh profile opens  /dj/dashboard?scenario=NAME
          the page loads fake-yt.js and harness.js (head) and scenarios/*.js (end of body); on `load` the scenario runs:
          POST /__reset, POST /__config (its `setup`), fake.start() (now the fake YouTube API "loads"), then run(t)
          clicks the real buttons, records steps, POSTs the verdict to /__result ── the stand-in writes results/NAME.json
          and run.py closes the browser
```

* **`DashboardPageRenderTest`** (`src/test/java/.../template`) is an ordinary unit test: it calls the real
  `DjDashboardController.dashboard()` with mocked services and renders the model with the real templates and message bundles. It
  writes `dashboard.html` (Polish, Auto-Pilot on, a playlist saved, two songs), `dashboard-manual.html` (a brand-new party),
  `dashboard-en.html` (the same party in English), `fallback-queue.html` (the "up next" fragment, four tracks, one skipped) and
  `history-<filter>.html` / `history-<filter>-more.html` (the History tab's fragment for each of the five filters — the first page, and
  the longer list that "Show more" asks for — built by the real `DjDashboardController.historyFragment` from ten sample entries, with the
  real `HistoryFilter` deciding which of them a filter includes). It fails when the page loses something the scripts need.
* **The stand-in** knows nothing about music. What `next-track` and `recent-tracks` answer is told by the scenario or **replayed from
  the fixture**. `POST /__config` merges a JSON object into its state (`server.py`, `default_state()`, lists the keys: `lease`,
  `commands`, `nextTracks`, `nextTrackStatus`, `recent`, `recentStatus`, `replay`, `delays`, `playbackMode`, `queue`,
  `queueActionStatus`, `historyStatus`, `fallbackSave`); a config is **merged**, so a key is set back to its default value, not left
  out (`delays: {'/path': 0}`, not `delays: {}`). One rule of the real server it does follow: a scripted `GUEST` answer whose id the
  request lists in `exclude` is dropped, as the real server never hands out an excluded song (`next-during-guest-song.js`). Every
  answer of the queue poll carries `X-Guest-Limits` from the key `guestLimits` (`guest-limits.js`). What the poll of the queue answers is a `<tbody>` of the rows the scenario names plus
  the real "nothing matches" row (taken from the rendered page: it is part of the polled fragment).
* **The lease is scripted, not simulated:** who holds the player lease is a config (`lease: {holder, free}`), so "another window took
  over" is `stand.config({ lease: { holder: false } })`. The rules of the server (who gets the lease, the timeout) are unit-tested on the
  server side; the scenarios check what the *window* does with the answers.
* **The fake player** (`fake-yt.js`) replaces the IFrame API: the real script never loads. `window.__fake`: `position`, `emit(state)`,
  `end()`, `holdState = 'UNSTARTED' | 'CUED'` + `release()` (a load that waits — the real player does it between two videos),
  `advanceClock(ms)` (moves `Date.now()`, so nothing waits for real minutes), `blockApi` (the API never loads), `loads`, `seeks`,
  `calls`. The first lease report of the page waits until the scenario has configured the stand-in.
* **Bootstrap from its CDN is not needed:** no script calls its API, it only styles the page, so `run.py` blocks the CDN and the
  tests do not depend on the network. (Checked: with the CDN blocked all scenarios behave the same.) A scenario that looked at layout
  would need it — use `--cdn`.

## Writing a scenario

A file in `scenarios/` registers scenarios with `S2P.scenario({...})`; `run.py` finds the names itself.

```js
S2P.scenario({
    name: 'my-scenario',                 // the URL (?scenario=) and the result file
    title: 'one line: what it proves',
    page: 'dashboard-manual',            // optional: which rendered page (default 'dashboard')
    fake: { blockApi: true },            // optional: properties for the fake player before it starts
    setup: { nextTracks: [ /* answers of next-track */ ], lease: { holder: true } },   // told to the stand-in first
    run: async function (t) {
        await t.waitForTrack(1);                                        // Auto-Pilot asked next-track and the fake plays it
        t.fake.position = 30;                                           // the clock of the track
        t.step('⏮ after 30 s restarts', await t.press('playerPreviousBtn'), 'restart');   // 'restart' | 'nothing' | letter of the video
        t.step('asked once', await t.stand.count(t.NEXT_TRACK), 1);
    }
});
```

`t`: `step(label, actual, expected)` (compared as JSON), `check(label, condition)`, `press(buttonId)`, `waitFor(condition, label)`,
`waitForTrack(n)`, `sleep(ms)`, `label(buttonId)`, `letter(videoId)`, `fake`, `stand.config(obj)`, `stand.requests('POST /path')`,
`stand.count('POST /path')`, `nextLeaseReport()` (the next report the page sends), `reportAfterConfig()` (the first one the stand-in
answered with the state of the last config). Video ids in the scenarios and the fixture start with a distinct letter (a…, b…, c…), so a
track is easy to tell.

* An uncaught error in the page fails the scenario; `console.error` / `console.warn` are listed in the verdict for information.
* **A control** is a scenario that must fail: `control: { mustFail: ['label prefix', ...] }` — it passes only if every step whose
  label starts with one of those fails, so it proves that the check can see the problem it exists for (`boundary-old-keys`).
* Make a new scenario **fail first**: run it against the unfixed script, see it red for the reason you expect, then fix. A scenario for
  code that already works cannot be seen red that way — **break the code instead** (in a copy, never in the repo while the app runs):
  take the line the scenario is about out of the script, and the scenario must go red for the right step. The lists, the tabs and the
  lease scenarios were checked like this, with 17 such mutations (one of them survived — removing the `scroll` listener changes nothing
  because Chrome's `scrollend` runs the same function — and a mutation that really stops the lit tab from following was killed).
* Two things that differ between machines and made an assertion wrong once: the **language of the browser** (in a Polish Chrome `ż`
  sorts after `z`, in an English one it does not — a sort test must not name the exact neighbour) and **timing** (a lease report is every
  3 s, the poll every 3 s: wait for the state with `waitFor`, do not sleep for it).
* Timing: the page reports to the lease every 3 s and polls the queue every 3 s; a scenario that waits for those needs real seconds.

## The fixture of real answers

`fixtures/play-log-boundary.json` holds the JSON bodies that the **real** `POST /dj/dashboard/next-track` and
`GET /dj/dashboard/recent-tracks` gave — through the real controllers, the ownership check, the lease check, the play log and Jackson,
on a real PostgreSQL — for a 3-track playlist A B C that loops (ten hand-outs; the 3rd one already opens round 2). The stand-in replays
them hand-out by hand-out, so the script is tested against what the server really says, not against what a test author believes it
says. `trackIdByVideo` (the ids of the queue's own tracks) is only for the control: it keys the same plays the way the code did before
the play log (`V7`).

**Record it again** when those answers change (a new field, another key scheme). Never in the repo, never on the `scan2play` database:
in a copy of the repo, with a throw-away `s2p_*` database (PostgreSQL 18 at `C:\Program Files\PostgreSQL\18\bin`, user `postgres`,
password `1111` as in `application.properties`); the recorder refuses any database whose name does not start with `s2p_`.
PowerShell does not keep environment variables between calls, so set them in the same call. `psql` inherits `PGDATABASE`, so
`CREATE` and `DROP` need `-d postgres`:

```powershell
$psql = "C:\Program Files\PostgreSQL\18\bin\psql.exe"; $env:PGPASSWORD = "1111"
python src/test/browser/run.py --no-render boundary        # refreshes the copy of the repo in $env:TEMP\scan2play-browser-tests (and runs one scenario)
try {
  & $psql -U postgres -d postgres -c "CREATE DATABASE s2p_fixture"
  $env:PGDATABASE = "s2p_fixture"
  $env:GOOGLE_AI_API_KEY = "dummy"; $env:SPOTIFY_CLIENT_ID = "dummy"; $env:SPOTIFY_CLIENT_SECRET = "dummy"
  $env:GOOGLE_CLIENT_ID = "dummy"; $env:GOOGLE_CLIENT_SECRET = "dummy"
  $env:S2P_FIXTURE_OUT = "D:\Coding\scan2play\src\test\browser\fixtures\play-log-boundary.json"   # the file to write: the repo's
  Push-Location "$env:TEMP\scan2play-browser-tests"
  & .\mvnw.cmd -B -ntp test "-Dtest=PlayLogFixtureRecorderTest"
  Pop-Location
} finally {
  & $psql -U postgres -d postgres -c "DROP DATABASE IF EXISTS s2p_fixture WITH (FORCE)"
}
```

Startup of that test runs Flyway on the empty database and Hibernate validation, so it is a check of the schema as well. Then run
`python src/test/browser/run.py` — the scenarios must still pass; the ids in the file change with every recording, nothing depends on them.

## In CI

`.github/workflows/browser-tests.yml` runs `python src/test/browser/run.py --no-sandbox` on `ubuntu-latest` (Java 21, Python 3.12, the
runner's own Chrome) for every push to `dev` / `main`, every pull request and by hand; a failed run keeps the verdicts of the scenarios
(`results/*.json`) as an artifact. It needs nothing but Maven Central: no database, no secrets, no YouTube. Two things in `run.py` are
there for it: `mvnw` is started with `sh` (it is committed without the executable bit) and a Chrome that does not start ends the scenario
after a few seconds instead of after the whole timeout. **First run on GitHub (2026-09-30): green, all 27 scenarios, about 4½ minutes.**
The unit tests have their own workflow, `.github/workflows/unit-tests.yml`.

## What this does not show

The real YouTube player (sound, the autoplay policy, the events between two videos — the fake does what the script relies on and no
more), the real Spring Security chain (a `_csrf` sent by `sendBeacon`), two real devices — the lease scenarios script the server's
answers, they do not run two windows — layout as the eye sees it (the scenarios measure positions and scrolling, not how a row looks),
the guest side. Real devices remain the owner's part.
