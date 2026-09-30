#!/usr/bin/env python3
"""Runs the browser tests (see README.md): the REAL scripts of the DJ dashboard on the REAL rendered dashboard.html, in headless Chrome.

    python src/test/browser/run.py                    all scenarios
    python src/test/browser/run.py boundary           one (or several) by name
    python src/test/browser/run.py --list

What it does, in this order — and it never runs Maven inside the repo (the app runs from IntelliJ out of target/classes, and
devtools restarts it whenever that changes; CLAUDE.md):
  1. mirrors the repo, without target/ .git/ .idea/, into a work directory (default: <temp>/scan2play-browser-tests) — a second
     run only copies what changed, so Maven there builds incrementally;
  2. runs DashboardPageRenderTest there with the copy's mvnw — it renders the real dashboard.html into target/browser-harness/;
  3. starts the stand-in server (server.py) on a free port of 127.0.0.1;
  4. for every scenario opens /dj/dashboard?scenario=NAME in a headless Chrome with a fresh profile; the page runs the scenario and
     POSTs its verdict to the stand-in server; the browser is closed when it has arrived (or after --timeout seconds);
  5. prints the verdicts (the failing steps with what was expected and what happened) and exits with 1 if any scenario failed.

Needs: Python 3 (standard library only), Java (the same one mvnw uses), Chrome or Edge. No Node, no new dependency.
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
sys.dont_write_bytecode = True   # no __pycache__ next to the scripts: this is a directory of the repo
sys.path.insert(0, str(HERE))
import server  # noqa: E402  (the stand-in server, next to this file)

# What is left out of the copy: the build output (a running app may be restarting on it), git, the IDE.
NOT_COPIED = {'target', '.git', '.idea'}


def mirror(src, dst):
    """Makes dst a copy of src without the top-level NOT_COPIED. A file that has not changed (size, mtime) is not copied again,
    a file that is gone from src is removed from dst, and dst's own target/ stays — so Maven builds incrementally."""
    dst.mkdir(parents=True, exist_ok=True)
    wanted = set()
    for root, dirs, files in os.walk(src):
        root = Path(root)
        if root == src:
            dirs[:] = [d for d in dirs if d not in NOT_COPIED]
        target_dir = dst / root.relative_to(src)
        target_dir.mkdir(parents=True, exist_ok=True)
        wanted.add(target_dir)
        for name in files:
            source, target = root / name, target_dir / name
            wanted.add(target)
            if not target.exists() or target.stat().st_size != source.stat().st_size \
                    or int(target.stat().st_mtime) != int(source.stat().st_mtime):
                shutil.copy2(source, target)
    for root, dirs, files in os.walk(dst, topdown=False):
        root = Path(root)
        if root == dst:
            continue
        if root.relative_to(dst).parts[0] == 'target':
            continue
        for name in files:
            if root / name not in wanted:
                (root / name).unlink()
        if root not in wanted:
            shutil.rmtree(root, ignore_errors=True)
    # top-level files that are gone
    for entry in dst.iterdir():
        if entry.is_file() and entry not in wanted:
            entry.unlink()


def render(work):
    """DashboardPageRenderTest in the copy: writes target/browser-harness/dashboard*.html."""
    wrapper = work / ('mvnw.cmd' if os.name == 'nt' else 'mvnw')
    command = [str(wrapper), '-B', '-ntp', '-q', 'test', '-Dtest=DashboardPageRenderTest', '-Dsurefire.failIfNoSpecifiedTests=false']
    print('rendering the dashboard in the copy: ' + ' '.join(command[1:]), flush=True)
    result = subprocess.run(command, cwd=work)
    if result.returncode != 0:
        sys.exit('DashboardPageRenderTest failed (see above) — nothing to run the scenarios on')


def find_chrome(explicit):
    candidates = [explicit, os.environ.get('S2P_CHROME')]
    if os.name == 'nt':
        for base in filter(None, (os.environ.get('ProgramFiles'), os.environ.get('ProgramFiles(x86)'), os.environ.get('LOCALAPPDATA'))):
            candidates += [os.path.join(base, 'Google', 'Chrome', 'Application', 'chrome.exe'),
                           os.path.join(base, 'Microsoft', 'Edge', 'Application', 'msedge.exe')]
    else:
        candidates += [shutil.which(n) for n in ('google-chrome', 'chromium', 'chromium-browser', 'chrome', 'microsoft-edge')]
        candidates += ['/Applications/Google Chrome.app/Contents/MacOS/Google Chrome']
    for candidate in candidates:
        if candidate and os.path.isfile(candidate):
            return candidate
    sys.exit('No Chrome or Edge found: pass --chrome PATH or set S2P_CHROME')


def scenario_names():
    """The names the scenario files register (S2P.scenario({ name: '...' })), in file order."""
    names = []
    for path in sorted((HERE / 'scenarios').glob('*.js')):
        names += re.findall(r"S2P\.scenario\(\{\s*name:\s*'([^']+)'", path.read_text(encoding='utf-8'))
    return names


def kill(process):
    if process.poll() is not None:
        return
    if os.name == 'nt':
        subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'], capture_output=True)
    else:
        process.terminate()
    try:
        process.wait(15)
    except subprocess.TimeoutExpired:
        process.kill()


def run_scenario(stand, port, name, chrome, allow_cdn, timeout):
    """Opens the scenario in a fresh headless Chrome and waits for its verdict; None when none came."""
    event = stand.event(name)
    event.clear()
    stand.result_data.pop(name, None)
    profile = tempfile.mkdtemp(prefix='s2p-chrome-')
    flags = ['--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check', '--disable-extensions',
             '--disable-background-networking', '--disable-component-update', '--disable-sync', '--mute-audio',
             '--window-size=1280,900', '--user-data-dir=' + profile]
    # Nothing here needs the network: the YouTube API is faked, and Bootstrap from its CDN only styles the page (no script uses it).
    blocked = ['www.youtube.com', 'i.ytimg.com'] + ([] if allow_cdn else ['cdn.jsdelivr.net'])
    flags.append('--host-resolver-rules=' + ', '.join('MAP %s ~NOTFOUND' % host for host in blocked))
    url = 'http://127.0.0.1:%d/dj/dashboard?scenario=%s' % (port, name)
    process = subprocess.Popen([chrome] + flags + [url], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        event.wait(timeout)
    finally:
        kill(process)
        shutil.rmtree(profile, ignore_errors=True)
    return stand.result_data.get(name)


def show(name, verdict):
    """Prints one verdict; returns whether the scenario passed."""
    if verdict is None:
        print('FAIL  %s  (no verdict: the page did not finish — a script error before the scenario, or too slow)' % name)
        return False
    steps, control = verdict['steps'], verdict.get('control')
    title = verdict.get('title') or ''
    failed = [s for s in steps if not s['pass']]
    if verdict['pass']:
        if control:
            print('ok    %s  — %s\n      the control fails as it must (%d of %d steps failed: %s)'
                  % (name, title, len(failed), len(steps), '; '.join(s['label'][:60] for s in failed if any(s['label'].startswith(p) for p in control['mustFail']))))
        else:
            print('ok    %s  — %s  (%d steps)' % (name, title, len(steps)))
        return True
    print('FAIL  %s  — %s' % (name, title))
    if control:
        missed = [p for p in control['mustFail'] if not any(s['label'].startswith(p) and not s['pass'] for s in steps)]
        print('      the control should have failed on: %s — but it did not, so the check cannot see the problem' % ', '.join(missed))
    for s in failed if not control else []:
        print('      x %s' % s['label'])
        if 'expected' in s:
            print('          expected %s\n          actual   %s' % (s['expected'], s['actual']))
    for error in verdict.get('errors', []):
        print('      uncaught error in the page: %s' % error)
    if verdict.get('consoleErrors'):
        print('      console: ' + ' | '.join(verdict['consoleErrors'][:5]))
    print('      loads: %s' % ' '.join(verdict.get('loads', [])))
    return False


def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')   # the labels have ⏮ ⏭ and Polish letters; a Windows console may not
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('scenarios', nargs='*', help='names of scenarios (default: all)')
    parser.add_argument('--list', action='store_true', help='list the scenarios and exit')
    parser.add_argument('--work', default=os.path.join(tempfile.gettempdir(), 'scan2play-browser-tests'), help='the work directory (a copy of the repo)')
    parser.add_argument('--clean', action='store_true', help='delete the work directory first (a full build)')
    parser.add_argument('--no-render', action='store_true', help='copy the repo but do not run Maven: reuse the pages rendered by an earlier run (a quick loop while editing scenarios)')
    parser.add_argument('--chrome', help='path of chrome.exe / msedge.exe (else S2P_CHROME, else looked for)')
    parser.add_argument('--cdn', action='store_true', help='let the page load Bootstrap from its CDN (by default it is blocked: not needed)')
    parser.add_argument('--timeout', type=int, default=90, help='seconds to wait for one scenario')
    args = parser.parse_args()

    known = scenario_names()
    if args.list:
        print('\n'.join(known))
        return 0
    wanted = args.scenarios or known
    unknown = [n for n in wanted if n not in known]
    if unknown:
        sys.exit('unknown scenario: %s (known: %s)' % (', '.join(unknown), ', '.join(known)))

    work = Path(args.work).resolve()
    if work == REPO or REPO in work.parents:
        sys.exit('the work directory must not be inside the repo: ' + str(work))
    if args.clean and work.exists():
        shutil.rmtree(work)
    print('copying the repo to %s' % work, flush=True)
    mirror(REPO, work)
    rendered = work / 'target' / 'browser-harness'
    if args.no_render and (rendered / 'dashboard.html').is_file():
        print('reusing the pages rendered earlier')
    else:
        render(work)
    if not (rendered / 'dashboard.html').is_file():
        sys.exit('no rendered dashboard.html in ' + str(rendered))

    chrome = find_chrome(args.chrome)
    stand = server.Stand(str(work), rendered=str(rendered))
    httpd = server.make_server(stand)
    port = httpd.server_address[1]
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    print('stand-in server on 127.0.0.1:%d, browser: %s\n' % (port, chrome), flush=True)

    passed = True
    for name in wanted:
        passed = show(name, run_scenario(stand, port, name, chrome, args.cdn, args.timeout)) and passed
    httpd.shutdown()
    print('\n%s — results: %s' % ('all scenarios passed' if passed else 'SOME SCENARIOS FAILED', stand.results))
    return 0 if passed else 1


if __name__ == '__main__':
    sys.exit(main())
