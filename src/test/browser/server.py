"""A stand-in for the DJ dashboard's server, for the browser tests (see README.md). Standard library only.

It serves the REAL rendered dashboard.html (target/browser-harness/dashboard.html, written by DashboardPageRenderTest) with
the REAL static js and css of the repo, and answers the few endpoints the dashboard's scripts call.

  /dj/dashboard                      the page; ?scenario=NAME also adds the runner; sent with the real Content-Security-Policy
                                     (csp.txt), enforced
  POST /csp-report                   204 (the page's violations are caught by harness.js)
  /js/*, /css/*                      src/main/resources/static
  /webjars/bootstrap/*               Bootstrap from its webjar in the local Maven repository (the version the pom names), as
                                     the real server serves it — so the policy is checked against Bootstrap's CSS and script too
  /harness/*                         this directory (harness.js, scenarios/*.js)
  POST /__reset                      the default answers, an empty request log
  POST /__config  {json}             merged into the state (see default_state for the keys)
  GET  /dj/history-view/fragment     the REAL history fragment (rendered by DashboardPageRenderTest: history-<filter>.html, and
                                     history-<filter>-more.html when the request has a limit — "Show more")
  GET  /__log                        {"requests": [{"m", "p", "q"}...], "state": {...}}
  POST /__result?name=NAME           a scenario's verdict: written to <results>/NAME.json and handed to the runner

By hand:  python server.py [--root <repo>] [--port 8765]   and open  http://127.0.0.1:8765/dj/dashboard
"""
import argparse
import html
import json
import os
import re
import sys
import threading
import time
import zipfile
import zlib
from email.parser import BytesParser
from email.policy import HTTP
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

HERE = os.path.dirname(os.path.abspath(__file__))
TYPES = {'.js': 'application/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
         '.html': 'text/html; charset=utf-8', '.json': 'application/json; charset=utf-8'}


def default_state():
    """What the stand-in answers until a scenario says otherwise."""
    return {
        # Seconds to wait before answering a path, e.g. {'/dj/dashboard/updates': 2.5}: an answer that arrives late.
        # (The request is logged at once, and the answer says what the state was when the request came.)
        'delays': {},
        # The rows of the queue table that poll answers with: [{id, name, url}] (accepted songs; url = the song's link).
        # The rendered page itself has two rows, but the first poll replaces them with these.
        'queue': [],
        # The X-Guest-Limits header of every poll answer: 'none', or the server limits that stop guest songs now, e.g.
        # 'party-full' (the dashboard shows a warning for each)
        'guestLimits': 'none',
        # The X-Guest-Limits-Use header of every poll answer: '<busiest network>,<party>' — the requests used of each server limit
        'guestLimitsUse': '0,0',
        # The X-Party-Active header of every poll answer: false = the DJ ended the party (in any window)
        'partyActive': True,
        'historyStatus': None,         # e.g. 500: GET history-view/fragment fails (the History tab and its buttons must cope)
        'requests': [],
    }


def merge(target, patch):
    for key, value in patch.items():
        if isinstance(value, dict) and isinstance(target.get(key), dict):
            merge(target[key], value)
        else:
            target[key] = value


class Stand:
    """The state and the pieces of the server that both the handler and the runner use."""

    def __init__(self, root, rendered=None, results=None):
        self.root = os.path.abspath(root)
        self.static = os.path.join(self.root, 'src', 'main', 'resources', 'static')
        self.browser = os.path.join(self.root, 'src', 'test', 'browser')
        self.rendered = rendered or os.path.join(self.root, 'target', 'browser-harness')
        self.results = results or os.path.join(self.rendered, 'results')
        os.makedirs(self.results, exist_ok=True)
        self.lock = threading.Lock()
        self.state = default_state()
        self.result_events = {}
        self.result_data = {}
        self._webjar = None

    def event(self, name):
        with self.lock:
            return self.result_events.setdefault(name, threading.Event())

    def webjar_file(self, rel):
        """A file of the Bootstrap webjar ('css/bootstrap.min.css'), or None. The jar is the one Maven fetched for the pom's version
        (~/.m2, or M2_REPO); the real server leaves the version out of the URL the same way (webjars-locator-lite)."""
        if self._webjar is None:
            with open(os.path.join(self.root, 'pom.xml'), encoding='utf-8') as f:
                found = re.search(r'<groupId>org\.webjars</groupId>\s*<artifactId>bootstrap</artifactId>\s*<version>([^<]+)</version>', f.read())
            repo = os.environ.get('M2_REPO') or os.path.join(os.path.expanduser('~'), '.m2', 'repository')
            version = found.group(1) if found else '?'
            self._webjar = (os.path.join(repo, 'org', 'webjars', 'bootstrap', version, 'bootstrap-%s.jar' % version), version)
        jar, version = self._webjar
        if not os.path.isfile(jar):
            return None
        with zipfile.ZipFile(jar) as z:
            try:
                return z.read('META-INF/resources/webjars/bootstrap/%s/%s' % (version, rel))
            except KeyError:
                return None

    def csp(self):
        """The real server's Content-Security-Policy (csp.txt, written by DashboardPageRenderTest), or None before a render."""
        path = os.path.join(self.rendered, 'csp.txt')
        if not os.path.isfile(path):
            return None
        with open(path, encoding='utf-8') as f:
            return f.read().strip()

    def page(self, which, scenario):
        with open(os.path.join(self.rendered, which + '.html'), encoding='utf-8') as f:
            text = f.read()
        text = text.replace('<head>', '<head><script src="/harness/harness.js"></script>', 1)
        if scenario:
            files = sorted(os.listdir(os.path.join(self.browser, 'scenarios')))
            tags = ''.join('<script src="/harness/scenarios/%s"></script>' % n for n in files if n.endswith('.js'))
            text = text.replace('</body>', tags + '</body>', 1)
        return text

    def nomatch_row(self):
        """The "nothing matches" row of the queue table, as the REAL template renders it. It is part of the polled <tbody> (the
        fragment dashboard :: songTableBody), so an answer to the poll that lacked it would make the search box look different
        from the real page after the first poll. Taken from the rendered page, so its markup and text are not copied by hand."""
        if not hasattr(self, '_nomatch'):
            with open(os.path.join(self.rendered, 'dashboard.html'), encoding='utf-8') as f:
                found = re.search(r'<tr data-nomatch[^>]*>.*?</tr>', f.read(), re.S)
            self._nomatch = found.group(0) if found else ''
        return self._nomatch

    def config(self, patch):
        with self.lock:
            merge(self.state, patch)
            return len(self.state['requests'])


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    stand = None   # set by make_server
    _delay = 0     # seconds _send waits first; set per request by _note (a connection is reused, so it is reset in do_GET / do_POST)

    def log_message(self, fmt, *args):
        pass

    # ---- helpers ----

    def _send(self, status, body=b'', ctype='application/json; charset=utf-8', headers=None):
        if isinstance(body, str):
            body = body.encode('utf-8')
        if self._delay:
            time.sleep(self._delay)
            self._delay = 0
        no_body = status in (204, 304)
        self.send_response(status)
        if not no_body:
            self.send_header('Content-Type', ctype)
        self.send_header('Content-Length', '0' if no_body else str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if not no_body:
            self.wfile.write(body)

    def _json(self, value, status=200, headers=None):
        self._send(status, json.dumps(value), headers=headers)

    def _file(self, root, rel):
        full = os.path.normpath(os.path.join(root, rel))
        if not full.startswith(os.path.normpath(root)) or not os.path.isfile(full):
            return self._send(404, b'not found', 'text/plain')
        with open(full, 'rb') as f:
            data = f.read()
        self._send(200, data, TYPES.get(os.path.splitext(full)[1], 'application/octet-stream'))

    def _note(self, method, path, fields):
        """Logs a request to one of the DJ endpoints (and says whether its answer is to be delayed)."""
        with self.stand.lock:
            self.stand.state['requests'].append({'m': method, 'p': path, 'q': fields})
            self._delay = self.stand.state['delays'].get(path, 0)

    @staticmethod
    def _fields(query, body, content_type):
        """Query and form fields (urlencoded, or multipart as FormData sends them) as one dict; the last value wins."""
        fields = {k: v[-1] for k, v in query.items()}
        content_type = content_type or ''
        if body and 'application/x-www-form-urlencoded' in content_type:
            fields.update({k: v[-1] for k, v in parse_qs(body.decode('utf-8')).items()})
        elif body and 'multipart/form-data' in content_type:
            message = BytesParser(policy=HTTP).parsebytes(b'Content-Type: ' + content_type.encode('ascii') + b'\r\n\r\n' + body)
            for part in message.iter_parts():
                field = part.get_param('name', header='content-disposition')
                if field and part.get_filename() is None:
                    fields[field] = part.get_content()
        return fields

    # ---- GET ----

    def do_GET(self):
        stand = self.stand
        self._delay = 0
        url = urlparse(self.path)
        path, query = url.path, parse_qs(url.query)
        if path in ('/', '/dj/dashboard'):
            # the real policy, ENFORCED (the real server only reports until CSP_ENFORCE=true): harness.js fails a scenario on a violation
            policy = stand.csp()
            return self._send(200, stand.page(query.get('page', ['dashboard'])[0], 'scenario' in query), 'text/html; charset=utf-8',
                              {'Content-Security-Policy': policy} if policy else None)
        if path.startswith('/js/') or path.startswith('/css/'):
            return self._file(stand.static, path[1:])
        if path.startswith('/harness/'):
            return self._file(stand.browser, path[len('/harness/'):])
        if path.startswith('/webjars/bootstrap/'):
            data = stand.webjar_file(path[len('/webjars/bootstrap/'):])
            if data is None:
                return self._send(404, b'not found', 'text/plain')
            return self._send(200, data, TYPES.get(os.path.splitext(path)[1], 'application/octet-stream'))
        if path == '/favicon.ico':
            return self._send(204)
        if path == '/__log':
            with stand.lock:
                return self._json(stand.state)
        self._note('GET', path, self._fields(query, b'', ''))
        state = stand.state
        if path == '/dj/dashboard/updates':
            with stand.lock:
                queue = list(state['queue'])
                limits = {'X-Guest-Limits': state['guestLimits'], 'X-Guest-Limits-Use': state['guestLimitsUse'],
                          'X-Party-Active': 'true' if state['partyActive'] else 'false'}
            # Like the real server: the ETag is a fingerprint of the guest queue only. X-Guest-Limits(-Use) is on every answer, 304 too.
            etag = '"q-%08x"' % zlib.crc32(json.dumps(queue, sort_keys=True).encode('utf-8'))
            if self.headers.get('If-None-Match') == etag:
                return self._send(304, headers=dict(limits, ETag=etag))
            # a row has the song cell that the column sort reads (data-sort-value / data-val) and, after the rows, the real
            # "nothing matches" row that the search box shows and hides
            rows = ''.join('<tr data-song-id="%d" data-song-name="%s" data-track-url="%s"><td class="song-cell" data-sort-value="song" data-val="%s"><span class="song-title">%s</span></td></tr>'
                           % (r['id'], html.escape(r['name'], True), html.escape(r['url'], True),
                              html.escape(r['name'], True), html.escape(r['name']))
                           for r in queue)
            body = '<tbody id="song-list">%s%s</tbody>' % (rows, stand.nomatch_row())
            return self._send(200, body, 'text/html; charset=utf-8', dict(limits, ETag=etag))
        if path == '/dj/history-view/fragment':
            # the REAL history fragment as DashboardPageRenderTest renders it through the real controller: one file per filter
            # (all, guest, background, played, rejected) — the first page — and a "-more" one for "Show more" (a request with a limit)
            with stand.lock:
                if state['historyStatus']:
                    return self._send(state['historyStatus'])
            name = 'history-%s%s.html' % (re.sub(r'[^a-z]', '', query.get('filter', ['all'])[0].lower()) or 'all', '-more' if 'limit' in query else '')
            listing = os.path.join(stand.rendered, name)
            if not os.path.isfile(listing):
                return self._send(404, ('no rendered ' + name).encode('utf-8'), 'text/plain')
            with open(listing, encoding='utf-8') as f:
                return self._send(200, f.read(), 'text/html; charset=utf-8')
        return self._send(404, b'not found', 'text/plain')

    # ---- POST ----

    def do_POST(self):
        stand = self.stand
        self._delay = 0
        url = urlparse(self.path)
        path, query = url.path, parse_qs(url.query)
        length = int(self.headers.get('Content-Length') or 0)
        body = self.rfile.read(length) if length else b''
        if path == '/csp-report':        # the browser's report of a violation (harness.js has seen it already)
            return self._send(204)
        if path == '/__reset':
            with stand.lock:
                stand.state = default_state()
            return self._json({})
        if path == '/__config':
            # 'mark' = how many requests had been logged when this took effect: the ones after it were answered with the new state
            return self._json({'mark': stand.config(json.loads(body.decode('utf-8') or '{}'))})
        if path == '/__result':
            name = query.get('name', ['unnamed'])[0]
            with open(os.path.join(stand.results, name + '.json'), 'wb') as f:
                f.write(body)
            stand.result_data[name] = json.loads(body.decode('utf-8'))
            stand.event(name).set()
            return self._json({})
        fields = self._fields(query, body, self.headers.get('Content-Type'))
        self._note('POST', path, fields)
        state = stand.state
        if path in ('/dj/dashboard/play', '/dj/dashboard/dismiss'):   # the song leaves the queue, as on the real server
            with stand.lock:
                state['queue'] = [r for r in state['queue'] if str(r['id']) != str(fields.get('id'))]
            return self._json({})
        if path == '/dj/dashboard/clear-queue':       # every waiting request leaves the queue (as rejected, on the real server)
            with stand.lock:
                state['queue'] = []
            return self._json({})
        if path in ('/dj/dashboard/limits', '/dj/dashboard/vibe', '/dj/dashboard/vibe-note', '/dj/dashboard/dj-name',
                    '/dj/end-party', '/dj/start-party'):
            return self._json({})
        return self._send(404, b'not found', 'text/plain')


class QuietServer(ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request, client_address):
        # the browser is closed while a keep-alive connection is open: a reset is not worth a traceback
        if isinstance(sys.exc_info()[1], (ConnectionError, TimeoutError)):
            return
        super().handle_error(request, client_address)


def make_server(stand, port=0):
    """A server on 127.0.0.1 (port 0 = any free one) — start it with serve_forever() in a thread."""
    handler = type('BoundHandler', (Handler,), {'stand': stand})
    return QuietServer(('127.0.0.1', port), handler)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--root', default=os.path.abspath(os.path.join(HERE, '..', '..', '..')), help='the repo (or a copy of it)')
    parser.add_argument('--port', type=int, default=8765)
    args = parser.parse_args()
    server = make_server(Stand(args.root), args.port)
    print('stand-in server on http://127.0.0.1:%d/dj/dashboard  (Ctrl+C to stop)' % server.server_address[1])
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
