"""Every rendered page at a phone's and a laptop's width: horizontal scrolling, touch targets under 44 px, console errors.

    python tools/page_audit.py                       every page, at 320, 390 and 1280 px
    python tools/page_audit.py -p guest,dashboard    pages whose name starts with one of these
    python tools/page_audit.py --widths 390 --shots /tmp/s2p/shots    also a PNG of each page at each width

The pages are the ones the unit tests rendered into <work>/target/browser-harness/ (run `python tools/check.py unit` first, same
--work); they are served by the browser tests' stand-in server under the real Content-Security-Policy and opened in a headless
Chrome (src/test/browser/run.py's pieces), one browser for all of them. Prints only what is wrong, a line a page and width:

  - "scroll": the page is wider than the screen (document.documentElement.scrollWidth > clientWidth — never window.innerWidth,
    CLAUDE.md "Mind" 2), with the elements that stick out the most;
  - "errors": an uncaught error, a console.error, a CSP violation;
  - "small" (phone widths only, < 1000 px — a mouse needs no 44 px): a link, a button, a field or a summary smaller than 44 × 44 px —
    a link inside a sentence is left out (its line is the target), a checkbox or radio is measured with its label, a `.stretched-link`
    with its card. One line a target, with the pages and widths it was seen on, after the pages. Reported, but it does not fail the
    run: some are deliberate.

Exit status 1 when a page scrolls sideways or logs an error. Standard library only.
"""

import argparse
import base64
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.dont_write_bytecode = True
sys.path.insert(0, str(REPO / 'src' / 'test' / 'browser'))
import run  # noqa: E402  (find_chrome, DevTools, kill)
import server  # noqa: E402

HEIGHT = {320: 640, 390: 844}
TARGET = 44

# Runs before the page's own scripts (CSP does not apply to it): keeps what the page reports as broken.
RECORDER = r"""
window.__audit = [];
addEventListener('error', e => __audit.push('error: ' + (e.message || (e.target && (e.target.src || e.target.href)) || e.type)), true);
addEventListener('unhandledrejection', e => __audit.push('rejection: ' + (e.reason && e.reason.message || e.reason)));
addEventListener('securitypolicyviolation', e => __audit.push('CSP: ' + e.violatedDirective + ' ' + (e.blockedURI || '')));
const original = console.error;
console.error = function (...args) { __audit.push('console.error: ' + args.map(String).join(' ')); return original.apply(this, args); };
"""

MEASURE = r"""
(() => {
  const root = document.documentElement, width = root.clientWidth;
  const name = el => el.tagName.toLowerCase() + (el.id ? '#' + el.id : '') +
      (typeof el.className === 'string' && el.className.trim() ? '.' + el.className.trim().split(/\s+/).slice(0, 2).join('.') : '');
  const shown = el => { const s = getComputedStyle(el); const r = el.getBoundingClientRect();
      return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none' && !el.closest('[hidden],[aria-hidden="true"]'); };
  const wide = [];
  if (root.scrollWidth > width) {
    for (const el of document.body.querySelectorAll('*')) {
      if (!shown(el)) continue;
      const r = el.getBoundingClientRect();
      if (r.right > width + 1 && !el.closest('.table-responsive,[data-scrolls]')) wide.push([Math.round(r.right - width), name(el)]);
    }
    wide.sort((a, b) => b[0] - a[0]);
  }
  const small = [];
  for (const el of document.querySelectorAll('a[href],button,input:not([type=hidden]),select,textarea,summary,[role=button]')) {
    if (!shown(el)) continue;
    let box = el;
    if (el.matches('input[type=checkbox],input[type=radio]')) box = el.closest('label') || (el.id && document.querySelector('label[for="' + el.id + '"]')) || el.parentElement;
    if (el.classList.contains('stretched-link')) box = el.closest('.card') || el.parentElement;
    if (el.tagName === 'A' && el.parentElement && box === el) {
      const own = (el.textContent || '').trim().length, around = (el.parentElement.textContent || '').trim().length;
      if (around > own + 15 && getComputedStyle(el).display === 'inline') continue;   // a link inside a sentence
    }
    const r = box.getBoundingClientRect();
    if (width < 1000 && Math.min(r.width, r.height) < 44 - 0.5) {
      const label = (el.getAttribute('aria-label') || el.textContent || el.value || '').trim().replace(/\s+/g, ' ').slice(0, 24);
      small.push(name(el) + (label ? ' "' + label + '"' : '') + ' ' + Math.round(Math.min(r.width, r.height)) + ' px');
    }
  }
  return JSON.stringify({scroll: root.scrollWidth - width, wide: wide.slice(0, 3), small, errors: window.__audit || []});
})()
"""


def pages(rendered: Path, prefixes):
    found = []
    for path in sorted(rendered.glob('*.html')):
        head = path.read_text(encoding='utf-8', errors='replace')[:400].lower()
        if '<html' not in head:
            continue   # a fragment (a list, a card) the real server sends into a page, not a page
        if prefixes and not any(path.stem.startswith(p) for p in prefixes):
            continue
        found.append(path.stem)
    return found


def evaluate(devtools, expression):
    result = devtools.call('Runtime.evaluate', expression=expression, returnByValue=True)
    return result['result'].get('value')


def audit(chrome, port, names, widths, no_sandbox, shots):
    profile = tempfile.mkdtemp(prefix='s2p-audit-')
    flags = ['--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check', '--disable-extensions',
             '--disable-background-networking', '--disable-component-update', '--disable-sync', '--mute-audio', '--hide-scrollbars',
             '--remote-debugging-port=0', '--user-data-dir=' + profile,
             '--host-resolver-rules=MAP www.youtube.com ~NOTFOUND, MAP i.ytimg.com ~NOTFOUND, MAP itunes.apple.com ~NOTFOUND']
    if no_sandbox:
        flags.append('--no-sandbox')
    process = subprocess.Popen([chrome] + flags + ['about:blank'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    devtools = None
    failed, checked, small = 0, 0, {}
    try:
        devtools = run.DevTools(profile, time.monotonic() + 30)
        devtools.call('Page.enable')
        devtools.call('Page.addScriptToEvaluateOnNewDocument', source=RECORDER)
        for width in widths:
            height = HEIGHT.get(width, 800)
            devtools.call('Emulation.setDeviceMetricsOverride', width=width, height=height, deviceScaleFactor=1, mobile=width < 1000)
            for name in names:
                devtools.call('Page.navigate', url='http://127.0.0.1:%d/dj/dashboard?page=%s' % (port, name))
                deadline = time.monotonic() + 15
                while evaluate(devtools, 'document.readyState') != 'complete' and time.monotonic() < deadline:
                    time.sleep(0.1)
                time.sleep(0.4)   # the page's own scripts (a list fitted, a card folded) after the load
                found = json.loads(evaluate(devtools, MEASURE))
                checked += 1
                problems = []
                if found['scroll'] > 0:
                    problems.append('scroll +%dpx: %s' % (found['scroll'], ', '.join('%s +%d' % (n, d) for d, n in found['wide']) or '?'))
                if found['errors']:
                    problems.append('errors: ' + ' | '.join(e[:160] for e in found['errors'][:3]))
                for target in found['small']:
                    small.setdefault(target, []).append('%s@%d' % (name, width))
                if problems:
                    print('x %-34s %4d  %s' % (name, width, ('\n' + ' ' * 42).join(problems)), flush=True)
                failed += bool(problems)
                if shots:
                    shot = devtools.call('Page.captureScreenshot', format='png', captureBeyondViewport=True)
                    (shots / ('%s-%d.png' % (name, width))).write_bytes(base64.b64decode(shot['data']))
    finally:
        if devtools:
            devtools.close()
        run.kill(process)
        shutil.rmtree(profile, ignore_errors=True)
    if small:
        print('small targets (< %d px, phones): %d' % (TARGET, len(small)))
        for target, seen in sorted(small.items(), key=lambda kv: -len(kv[1]))[:20]:
            print('  - %s — %d× (%s%s)' % (target, len(seen), ', '.join(seen[:3]), ', …' if len(seen) > 3 else ''))
        if len(small) > 20:
            print('  … and %d more' % (len(small) - 20))
    return checked, failed, len(small)


def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--work', default=os.path.join(tempfile.gettempdir(), 'scan2play-check'), help='the copy `check.py unit` rendered into')
    parser.add_argument('-p', '--pages', default='', help='only pages whose name starts with one of these, comma-separated')
    parser.add_argument('--widths', default='320,390,1280')
    parser.add_argument('--chrome', help='else S2P_CHROME, else looked for')
    parser.add_argument('--no-sandbox', action='store_true')
    parser.add_argument('--shots', help='a directory for a PNG of every page at every width')
    args = parser.parse_args()

    work = Path(args.work).resolve()
    rendered = work / 'target' / 'browser-harness'
    if not (rendered / 'dashboard.html').is_file():
        print('audit: no rendered pages in %s — run `python tools/check.py unit` first' % work)
        return 2
    names = pages(rendered, [p for p in args.pages.split(',') if p])
    if not names:
        print('audit: no page matches')
        return 2
    shots = Path(args.shots) if args.shots else None
    if shots:
        shots.mkdir(parents=True, exist_ok=True)
    chrome = run.find_chrome(args.chrome or os.environ.get('S2P_CHROME'))
    stand = server.Stand(str(work), rendered=str(rendered))
    stand.config({'updatesStatus': 304})   # the panel's poll answers "unchanged": the rendered page stays as it is
    httpd = server.make_server(stand)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    widths = [int(w) for w in args.widths.split(',')]
    started = time.monotonic()
    try:
        checked, failed, warned = audit(chrome, httpd.server_address[1], names, widths, args.no_sandbox, shots)
    finally:
        httpd.shutdown()
    print('audit: %d pages × %d widths — %d with scrolling or errors, %d small targets (%.0f s)%s'
          % (len(names), len(widths), failed, warned, time.monotonic() - started, ' — FAILED' if failed else ' — OK'))
    if shots:
        print('    pictures: %s' % shots)
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
