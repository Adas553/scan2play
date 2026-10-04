// The scenario runner of the browser tests (see README.md). The stand-in server injects it into <head>, and then the files of
// scenarios/ at the end of <body> — so when the page has loaded, every scenario has registered itself here.
//
// A page opened as /dj/dashboard?scenario=NAME runs that scenario on the REAL rendered dashboard with the REAL scripts,
// clicking its buttons, and POSTs the verdict to the stand-in server, which writes it to a file. Nothing is typed by hand,
// and nothing needs a JavaScript tool of the browser: the runner script only waits for the file.
(function () {
    'use strict';

    const params = new URLSearchParams(location.search);
    const name = params.get('scenario');
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
     *   page     which rendered page the scenario runs on (default 'dashboard': the party in Polish, two requests waiting;
     *            'dashboard-en': the same in English; 'guest', 'guest-requests', 'qr-print-poster', 'qr-print-cards'); see
     *            DashboardPageRenderTest, GuestPageRenderTest, QrPrintPageTest
     *   viewport the browser window's size, e.g. '390,844' (a phone's screen; read by run.py)
     *   setup    what the stand-in server is told before the scenario runs (see server.py, POST /__config)
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

    const PLAY = 'POST /dj/dashboard/play';

    const t = {
        sleep: sleep, waitFor: waitFor, step: step, stand: stand, PLAY: PLAY,
        /** step(label, condition, true) */
        check: function (label, condition) { step(label, !!condition, true); }
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
            consoleErrors: consoleErrors,
            requests: log.requests.filter(function (r) { return !/updates$/.test(r.p); })
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
            await definition.run(t);
        } catch (e) {
            steps.push({ label: 'EXCEPTION ' + e, pass: false });
        }
        await finish(definition);
    }

    if (name) window.addEventListener('load', runScenario);
})();
