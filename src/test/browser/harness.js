// The scenario runner of the browser tests (see README.md). The stand-in server injects it into <head>, after fake-yt.js, and
// then the files of scenarios/ at the end of <body> — so when the page has loaded, every scenario has registered itself here.
//
// A page opened as /dj/dashboard?scenario=NAME runs that scenario on the REAL rendered dashboard with the REAL scripts,
// clicking its buttons, and POSTs the verdict to the stand-in server, which writes it to a file. Nothing is typed by hand,
// and nothing needs a JavaScript tool of the browser: the runner script only waits for the file.
(function () {
    'use strict';

    const params = new URLSearchParams(location.search);
    const name = params.get('scenario');
    const fake = window.__fake;
    const scenarios = {};
    const steps = [];
    const errors = [];        // uncaught exceptions and unhandled rejections: none is expected
    const consoleErrors = []; // console.error / console.warn of the page, for information (some are by design)
    // what the page's Content-Security-Policy blocked (the stand-in sends the real policy, enforced): every scenario fails on one
    const cspViolations = [];

    document.addEventListener('securitypolicyviolation', function (e) {
        cspViolations.push(e.effectiveDirective + ' blocked ' + (e.blockedURI || '?') + ' @' + String(e.sourceFile || '').split('/').pop()
            + ':' + e.lineNumber);
    });

    window.addEventListener('error', function (e) { errors.push(String(e.message) + ' @' + String(e.filename).split('/').pop() + ':' + e.lineno); });
    window.addEventListener('unhandledrejection', function (e) { errors.push('unhandled rejection: ' + String(e.reason)); });
    ['error', 'warn'].forEach(function (level) {
        const original = console[level].bind(console);
        console[level] = function () {
            consoleErrors.push(level + ': ' + Array.prototype.map.call(arguments, String).join(' '));
            original.apply(null, arguments);
        };
    });

    /**
     * Registers a scenario.
     *   name     what the URL says (?scenario=NAME) and the name of the result file
     *   title    one line: what it proves
     *   page     which rendered page the scenario runs on (default 'dashboard': a YouTube party in Polish, Auto-Pilot on, a playlist
     *            saved; 'dashboard-manual': a brand-new party — Auto-Pilot off, no playlist); see DashboardPageRenderTest
     *   fake     properties for the fake YouTube player before it starts, e.g. { blockApi: true } (see fake-yt.js)
     *   session  sessionStorage entries written before the player starts — what the page before a reload left, e.g.
     *            { 'scan2play.interruptedTrack': 'B:9' }
     *   setup    what the stand-in server is told before the page starts (see server.py, POST /__config)
     *   run      async function (t): does something and records steps with t.step(...)
     *   control  optional: this scenario is a CONTROL — the same check with the problem put back, which must fail. It passes
     *            only if every step whose label starts with one of control.mustFail fails (so the check does see the problem).
     */
    window.S2P = {
        scenario: function (definition) { scenarios[definition.name] = definition; }
    };

    const sleep = function (ms) { return new Promise(function (resolve) { setTimeout(resolve, ms); }); };

    function step(label, actual, expected) {
        steps.push({ label: label, actual: actual, expected: expected, pass: JSON.stringify(actual) === JSON.stringify(expected) });
    }

    async function waitFor(condition, label, timeoutMs) {
        const started = performance.now();   // real time: Date.now() may have been moved by the scenario
        while (!condition()) {
            if (performance.now() - started > (timeoutMs || 10000)) {
                steps.push({ label: 'TIMEOUT waiting for ' + label, pass: false });
                throw new Error('timeout: ' + label);
            }
            await sleep(30);
        }
    }

    async function post(path, body) {
        const response = await fetch(path, { method: 'POST', body: body === undefined ? '' : JSON.stringify(body) });
        return response.text();
    }

    const stand = {
        // how many requests the stand-in had logged when it last took a config: the ones after it were answered with the new state
        mark: 0,
        /** Tells the stand-in server how to answer from now on (a JSON object merged into its state). */
        config: async function (config) {
            stand.mark = JSON.parse(await post('/__config', config)).mark;
        },
        /** Everything the page has asked the server since the scenario began: [{m, p, q}] (method, path, query and form fields). */
        requests: async function (match) {
            const log = await (await fetch('/__log')).json();
            return match ? log.requests.filter(function (r) { return (r.m + ' ' + r.p) === match; }) : log.requests;
        },
        count: async function (match) { return (await stand.requests(match)).length; }
    };

    const NEXT_TRACK = 'POST /dj/dashboard/next-track';
    const PLAY = 'POST /dj/dashboard/play';
    const LEASE = 'POST /dj/dashboard/player-lease';

    const t = {
        fake: fake, sleep: sleep, waitFor: waitFor, step: step, stand: stand,
        NEXT_TRACK: NEXT_TRACK, PLAY: PLAY, LEASE: LEASE,
        /** The first character of a video id: the fixtures name their videos a…, b…, c… so that a track is easy to tell. */
        letter: function (videoId) { return videoId ? videoId[0].toLowerCase() : null; },
        /** step(label, condition, true) */
        check: function (label, condition) { step(label, !!condition, true); },
        /** The label of a button of the player controls as the DJ reads it. */
        label: function (id) { return document.getElementById(id).querySelector('[data-role="label"]').textContent.trim(); },
        /**
         * Clicks a button and says what the player did: the letter of the video it loaded, 'restart' (seekTo) or 'nothing'.
         * The settle time covers the request to recent-tracks and the load.
         */
        press: async function (id, settleMs) {
            const before = { loads: fake.loads.length, seeks: fake.seeks.length };
            document.getElementById(id).click();
            await sleep(settleMs || 500);
            if (fake.loads.length > before.loads) return t.letter(fake.loads[fake.loads.length - 1]);
            if (fake.seeks.length > before.seeks) return 'restart';
            return 'nothing';
        },
        /** Waits until the player of the page has loaded its n-th video and is playing it. */
        waitForTrack: function (n, label) {
            return waitFor(function () { return fake.loads.length >= n && fake.state === 1; }, label || ('track ' + n + ' playing'));
        },
        /**
         * Waits for the first lease report that reached the stand-in after its last config (so it was answered with the state
         * that config set) and gives its fields. The page sends them every 3 s, and at once when the player starts or pauses.
         */
        reportAfterConfig: async function () {
            const started = performance.now();
            for (;;) {
                const reports = (await stand.requests()).slice(stand.mark).filter(function (r) { return (r.m + ' ' + r.p) === LEASE; });
                if (reports.length) return reports[0].q;
                if (performance.now() - started > 8000) {
                    steps.push({ label: 'TIMEOUT waiting for a lease report after the config', pass: false });
                    throw new Error('timeout: lease report after config');
                }
                await sleep(100);
            }
        },
        /**
         * Waits for the next lease report the page sends from now on and gives its fields: {partyCode, deviceId, mode, playing}.
         */
        nextLeaseReport: async function () {
            const before = await stand.count(LEASE);
            const started = performance.now();
            while ((await stand.count(LEASE)) <= before) {
                if (performance.now() - started > 8000) {
                    steps.push({ label: 'TIMEOUT waiting for a lease report', pass: false });
                    throw new Error('timeout: lease report');
                }
                await sleep(100);
            }
            const reports = await stand.requests(LEASE);
            return reports[reports.length - 1].q;
        }
    };

    async function finish(definition) {
        const recorded = steps.length;   // a scenario that recorded nothing fails, whatever the step below says
        await sleep(50);   // a violation event of the last step may still be queued
        step('no CSP violation', cspViolations, []);
        const control = definition.control || null;
        const failed = function (prefix) {
            return steps.some(function (s) { return s.label.indexOf(prefix) === 0 && !s.pass; });
        };
        let pass;
        if (control) {
            pass = control.mustFail.every(failed) && errors.length === 0;
        } else {
            pass = recorded > 0 && steps.every(function (s) { return s.pass; }) && errors.length === 0;
        }
        const log = await (await fetch('/__log')).json();
        const summary = {
            scenario: name, title: definition.title || '', control: control, pass: pass, steps: steps, errors: errors,
            consoleErrors: consoleErrors, loads: fake.loads, seeks: fake.seeks,
            requests: log.requests.filter(function (r) { return !/updates|fallback-queue$|player-lease$/.test(r.p); })
        };
        await fetch('/__result?name=' + encodeURIComponent(name), { method: 'POST', body: JSON.stringify(summary, null, 1) });
        document.title = (pass ? 'PASS ' : 'FAIL ') + name;
    }

    async function runScenario() {
        const definition = scenarios[name];
        // a scenario on another rendered page: load that page (the scenario files are served with every page)
        if (definition && definition.page && params.get('page') !== definition.page) {
            params.set('page', definition.page);
            location.replace(location.pathname + '?' + params.toString());
            return;
        }
        if (!definition) {
            steps.push({ label: 'no scenario called ' + name + ' (known: ' + Object.keys(scenarios).join(', ') + ')', pass: false });
            return finish({});
        }
        try {
            await post('/__reset');
            if (definition.setup) await stand.config(definition.setup);
            if (definition.fake) Object.assign(fake, definition.fake);
            // what an earlier page of this tab left in sessionStorage (a reload keeps it; every scenario starts with an empty one)
            Object.keys(definition.session || {}).forEach(function (key) { sessionStorage.setItem(key, definition.session[key]); });
            fake.start();   // now the script may ask for its first track
            await definition.run(t);
        } catch (e) {
            steps.push({ label: 'EXCEPTION ' + e, pass: false });
        }
        await finish(definition);
    }

    if (name) window.addEventListener('load', runScenario);
})();
