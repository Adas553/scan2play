# Browser tests of the DJ dashboard

The dashboard's real modules (`js/dashboard/*.js`, once one `dashboard.js` — older notes in the scenarios still name it), running on
the **real rendered `dashboard.html`** in a headless Chrome, against a small stand-in for the server; and the guest page and the QR
print page under the real Content-Security-Policy. No Node, no new dependency — Python 3 (standard library only), the Java that `mvnw`
uses, and Chrome or Edge.

```
python src/test/browser/run.py                 # every scenario
python src/test/browser/run.py tabs            # one, or several, by name
python src/test/browser/run.py --list
python src/test/browser/run.py --no-render     # a quick loop while editing scenarios: copy the repo, skip Maven
python src/test/browser/run.py --clean         # start from an empty work directory (a full build)
python src/test/browser/run.py --no-sandbox    # Chrome without its sandbox: for a CI runner that forbids it (see "In CI")
```

A run takes about a minute (most of it waiting for real timers: the queue is polled every 3 s) and ends with one line per scenario,
the failing steps with what was expected and what happened, and exit code 1 if anything failed. The `--chrome PATH` option (or
`S2P_CHROME`) names another browser.

**Never `mvnw` inside the repo** (`CLAUDE.md`): the app runs from IntelliJ out of `target/classes` and devtools restarts it whenever that
changes. `run.py` copies the repo — without `target/`, `.git`, `.idea` — to `%TEMP%\scan2play-browser-tests` (a second run copies only
what changed, so Maven there builds incrementally), runs `DashboardPageRenderTest` in the copy and serves everything from the copy.

## How it works

```
run.py ── copy of the repo ── mvnw test -Dtest=DashboardPageRenderTest,… ──► target/browser-harness/dashboard.html (+ -en, history-*, guest…)
   │
   ├─ server.py (stand-in, 127.0.0.1:<free port>) serves those pages, the real src/main/resources/static/{js,css},
   │     Bootstrap's webjar (/webjars/bootstrap/...), this directory (/harness/...), and answers the endpoints the scripts call
   └─ for each scenario: a headless Chrome with a fresh profile opens  /dj/dashboard?scenario=NAME
          the page loads harness.js (head) and scenarios/*.js (end of body); on `load` the scenario runs:
          POST /__reset, POST /__config (its `setup`), then run(t)
          clicks the real buttons, records steps, POSTs the verdict to /__result ── the stand-in writes results/NAME.json
          and run.py closes the browser
```

* **`DashboardPageRenderTest`** (`src/test/java/.../template`) is an ordinary unit test: it calls the real
  `DjDashboardController.dashboard()` with mocked services and renders the model with the real templates and message bundles. It
  writes `dashboard.html` (Polish, two requests waiting), `dashboard-en.html` (the same party in English) and
  `history-<filter>.html` / `history-<filter>-more.html` (the History tab's fragment for each of the five filters — the first page, and
  the longer list that "Show more" asks for — built by the real `DjDashboardController.historyFragment` from ten sample entries, with the
  real `HistoryFilter` deciding which of them a filter includes). It fails when the page loses something the scripts need.
* **The Content-Security-Policy:** `DashboardPageRenderTest` writes the real server's policy (`SecurityConfig.CONTENT_SECURITY_POLICY`)
  to `csp.txt`, and the stand-in sends it with every page — **enforced**, although the real server only reports until
  `CSP_ENFORCE=true`. `harness.js` listens for `securitypolicyviolation` and ends every scenario with the step `no CSP violation`, so a
  template or a script that needs an inline script, an `on…=` handler or a host the policy does not list fails whatever scenario
  loads it. `csp-catches-inline-code` is the control (an inline script and handler: blocked, and the step fails). Checked: an inline
  `<script>` put into the rendered dashboard fails an ordinary scenario; `itunes.apple.com` taken out of `connect-src` fails the guest
  page's scenario, `data:` out of `img-src` the print page's.
* **Other pages:** `GuestPageRenderTest` writes `guest.html` (the guest page with a queue) and `QrPrintPageTest` writes
  `qr-print-poster.html` / `qr-print-cards.html`, `StaffPageRenderTest` the owner's page "Obsługa" `staff.html` (V32); a scenario names them with `page:` like the dashboards. Their scenarios
  (`scenarios/csp.js`) use what the page's scripts do — the mode switch, the iTunes suggestions (the host is blocked in Chrome: the CSP
  check comes before the network), "↻", "Print" — so their requests meet the policy too.
* **The stand-in** knows nothing about music. `POST /__config` merges a JSON object into its state (`server.py`, `default_state()`,
  lists the keys: `delays`, `queue`, `guestLimits`, `guestLimitsUse`, `partyActive`, `historyStatus`); a config is **merged**, so a key
  is set back to its default value, not left out (`delays: {'/path': 0}`, not `delays: {}`). What the poll of the queue answers is a
  `<tbody>` of the rows the scenario names plus the real "nothing matches" row (taken from the rendered page: it is part of the polled
  fragment); every answer, 304 too, carries `X-Guest-Limits`, `X-Guest-Limits-Use` and `X-Party-Active` from the state.
* **Bootstrap is the real one, served like the app serves it:** the stand-in reads `/webjars/bootstrap/…` from the webjar Maven fetched
  for the pom's version (`~/.m2`, or `M2_REPO`), so the pages have the real styles and script, and the policy is checked against them
  too. Nothing needs the network.

## Writing a scenario

A file in `scenarios/` registers scenarios with `S2P.scenario({...})`; `run.py` finds the names itself.

```js
S2P.scenario({
    name: 'my-scenario',                 // the URL (?scenario=) and the result file
    title: 'one line: what it proves',
    page: 'dashboard-en',                // optional: which rendered page (default 'dashboard')
    viewport: '390,844',                 // optional: the browser window's size (default 1280,900) — a phone's screen; run.py reads it
    setup: { queue: [{ id: 1, name: 'Wilki - Baśka', url: 'https://www.youtube.com/results?search_query=Wilki' }] },   // told to the stand-in first
    run: async function (t) {
        await t.waitFor(function () { return document.querySelector('#song-list [data-song-id="1"]'); }, 'the queue poll', 8000);
        document.querySelector('#song-list tr[data-song-id="1"] form[action="/dj/dashboard/dismiss"] button').click();
        await t.sleep(500);
        t.step('skipped once', await t.stand.count('POST /dj/dashboard/dismiss'), 1);
    }
});
```

`t`: `step(label, actual, expected)` (compared as JSON), `check(label, condition)`, `waitFor(condition, label, timeoutMs)`, `sleep(ms)`,
`stand.config(obj)`, `stand.requests('POST /path')`, `stand.count('POST /path')`, `PLAY` (`'POST /dj/dashboard/play'`).

* An uncaught error in the page fails the scenario; `console.error` / `console.warn` are listed in the verdict for information.
* **A control** is a scenario that must fail: `control: { mustFail: ['label prefix', ...] }` — it passes only if every step whose
  label starts with one of those fails, so it proves that the check can see the problem it exists for (`csp-catches-inline-code`).
* Make a new scenario **fail first**: run it against the unfixed script, see it red for the reason you expect, then fix. A scenario for
  code that already works cannot be seen red that way — **break the code instead** (in a copy, never in the repo while the app runs):
  take the line the scenario is about out of the script, and the scenario must go red for the right step. The lists and the tabs
  scenarios were checked like this (one mutation survived — removing the `scroll` listener changes nothing because Chrome's `scrollend`
  runs the same function — and a mutation that really stops the lit tab from following was killed).
* Two things that differ between machines and made an assertion wrong once: the **language of the browser** (in a Polish Chrome `ż`
  sorts after `z`, in an English one it does not — a sort test must not name the exact neighbour) and **timing** (the poll is every
  3 s: wait for the state with `waitFor`, do not sleep for it).

## In CI

`.github/workflows/browser-tests.yml` runs `python src/test/browser/run.py --no-sandbox` on `ubuntu-latest` (Java 21, Python 3.12, the
runner's own Chrome) for every push to `dev` / `main`, every pull request and by hand; a failed run keeps the verdicts of the scenarios
(`results/*.json`) as an artifact. It needs nothing but Maven Central: no database, no secrets. Two things in `run.py` are there for it:
`mvnw` is started with `sh` (it is committed without the executable bit) and a Chrome that does not start ends the scenario after a few
seconds instead of after the whole timeout. The unit tests have their own workflow, `.github/workflows/unit-tests.yml`.

## What this does not show

The real Spring Security chain, two real devices, layout as the eye sees it (the scenarios measure positions and scrolling, not how a
row looks), the guest side beyond its CSP scenarios. Real devices remain the owner's part.
