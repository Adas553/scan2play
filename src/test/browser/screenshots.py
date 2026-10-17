#!/usr/bin/env python3
"""The pictures of the landing page: the guest's page on a phone and the DJ's panel on a laptop, of a made-up party, in both languages.

    python src/test/browser/screenshots.py

Like run.py (and with its pieces): it mirrors the repo into a work directory of its own and renders the pages there with Maven — never
in the repo —, serves them with the stand-in server under the real Content-Security-Policy and opens them in a headless Chrome at a
phone's and a laptop's size. Chrome writes each picture as WebP (Page.captureScreenshot), into the repo's
src/main/resources/static/images/landing/: guest-pl.webp, guest-en.webp, dashboard-pl.webp, dashboard-en.webp. The pages are
written by GuestPageRenderTest and DashboardPageRenderTest (theLandingPagesPicture_…): change the party there, run this again, and
look at the pictures before committing them.
"""
import argparse
import base64
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.dont_write_bytecode = True
sys.path.insert(0, str(HERE))
import run  # noqa: E402  (mirror, render, find_chrome, DevTools, kill)
import server  # noqa: E402

OUT = run.REPO / 'src' / 'main' / 'resources' / 'static' / 'images' / 'landing'

# name, the rendered page, its width and height in CSS pixels, the device's pixel ratio, a phone (touch, its own viewport meta)
SHOTS = [
    ('guest', 'landing-demo-guest', 390, 780, 2, True),
    ('dashboard', 'landing-demo-dashboard', 1280, 760, 1.5, False),
]
QUALITY = 82


def shoot(chrome, port, page, width, height, scale, phone, target):
    profile = tempfile.mkdtemp(prefix='s2p-shot-')
    flags = ['--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check', '--disable-extensions',
             '--disable-background-networking', '--disable-component-update', '--disable-sync', '--mute-audio',
             '--hide-scrollbars', '--remote-debugging-port=0', '--window-size=%d,%d' % (max(width, 800), height),
             '--user-data-dir=' + profile]
    process = subprocess.Popen([chrome] + flags + ['about:blank'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    devtools = None
    try:
        devtools = run.DevTools(profile, time.monotonic() + 30)
        devtools.call('Emulation.setDeviceMetricsOverride', width=width, height=height, deviceScaleFactor=scale, mobile=phone)
        devtools.call('Page.navigate', url='http://127.0.0.1:%d/dj/dashboard?page=%s' % (port, page))
        deadline = time.monotonic() + 20
        while devtools.call('Runtime.evaluate', expression='document.readyState')['result']['value'] != 'complete':
            if time.monotonic() > deadline:
                raise RuntimeError('the page did not load: ' + page)
            time.sleep(0.2)
        # the fonts and the scripts' first work (the queue's sort, the folded cards) — then nothing moves
        devtools.call('Runtime.evaluate', expression='document.fonts.ready', awaitPromise=True)
        time.sleep(1.0)
        shot = devtools.call('Page.captureScreenshot', format='webp', quality=QUALITY, captureBeyondViewport=False)
        data = base64.b64decode(shot['data'])
        target.write_bytes(data)
        print('%-28s %4d x %-4d @%sx  %5.1f KB' % (target.name, width, height, scale, len(data) / 1024), flush=True)
    finally:
        if devtools:
            devtools.close()
        run.kill(process)
        shutil.rmtree(profile, ignore_errors=True)


def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--work', default=os.path.join(tempfile.gettempdir(), 'scan2play-screenshots'), help='the work directory (a copy of the repo)')
    parser.add_argument('--no-render', action='store_true', help='reuse the pages rendered by an earlier run')
    parser.add_argument('--chrome', help='path of chrome.exe / msedge.exe (else S2P_CHROME, else looked for)')
    args = parser.parse_args()

    work = Path(args.work).resolve()
    if work == run.REPO or run.REPO in work.parents:
        sys.exit('the work directory must not be inside the repo: ' + str(work))
    print('copying the repo to %s' % work, flush=True)
    run.mirror(run.REPO, work)
    rendered = work / 'target' / 'browser-harness'
    if not (args.no_render and (rendered / 'landing-demo-guest-pl.html').is_file()):
        run.render(work)

    chrome = run.find_chrome(args.chrome)
    stand = server.Stand(str(work), rendered=str(rendered))
    stand.config({'updatesStatus': 304})   # the panel's poll answers "unchanged": the rendered queue stays
    httpd = server.make_server(stand)
    port = httpd.server_address[1]
    threading.Thread(target=httpd.serve_forever, daemon=True).start()

    OUT.mkdir(parents=True, exist_ok=True)
    for name, page, width, height, scale, phone in SHOTS:
        for lang in ('pl', 'en'):
            shoot(chrome, port, '%s-%s' % (page, lang), width, height, scale, phone, OUT / ('%s-%s.webp' % (name, lang)))
    httpd.shutdown()
    print('\nwritten to %s — look at them before committing' % OUT)
    return 0


if __name__ == '__main__':
    sys.exit(main())
