"""A stand-in for the DJ dashboard's server, for the browser tests (see README.md). Standard library only.

It serves the REAL rendered dashboard.html (target/browser-harness/dashboard.html, written by DashboardPageRenderTest) with
the REAL static js and css of the repo, and answers the few endpoints the dashboard's scripts call. It does not compute
anything about music: what next-track and recent-tracks answer is either told by the scenario (POST /__config) or replayed
from a fixture of answers that the REAL services gave (fixtures/*.json, recorded by PlayLogFixtureRecorderTest).

  /dj/dashboard                      the page (with the fake YouTube API in <head>); ?scenario=NAME also adds the runner
  /js/*, /css/*                      src/main/resources/static
  /harness/*                         this directory (fake-yt.js, harness.js, scenarios/*.js)
  POST /__reset                      the default answers, an empty request log
  POST /__config  {json}             merged into the state (see default_state for the keys)
  GET  /dj/history-view/fragment     the REAL history fragment (rendered by DashboardPageRenderTest: history-<filter>.html, and
                                     history-<filter>-more.html when the request has a limit — "Show more")
  GET  /__log                        {"requests": [{"m", "p", "q"}...], "state": {...}}
  POST /__result?name=NAME           a scenario's verdict: written to <results>/NAME.json and handed to the runner

By hand:  python server.py [--root <repo>] [--port 8765]   and open  http://127.0.0.1:8765/dj/dashboard
"""
import argparse
import copy
import html
import json
import os
import re
import sys
import threading
import time
import zlib
from email.parser import BytesParser
from email.policy import HTTP
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

HERE = os.path.dirname(os.path.abspath(__file__))
TYPES = {'.js': 'application/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
         '.html': 'text/html; charset=utf-8', '.json': 'application/json; charset=utf-8'}
PARTY_CODE_PLAYLIST = 'PLstandin000000000000000000000000000'


def default_state():
    """What the stand-in answers until a scenario says otherwise."""
    return {
        # The answer of POST player-lease. This window is the one that plays.
        'lease': {'holder': True, 'free': False, 'fallbackPlaylistId': PARTY_CODE_PLAYLIST, 'queueVersion': 'v1', 'playing': None},
        # Commands for the window that plays, handed out one per lease report of the holder: 'NEXT', 'PAUSE'...
        'commands': [],
        # The answers of POST next-track in order ({source, id, videoId, playlistId}); when they run out: 204.
        'nextTracks': [],
        'nextTrackStatus': None,       # e.g. 409: every next-track is refused
        # The answer of GET recent-tracks (a list of {key, source, id, videoId, title, secondsAgo}; without secondsAgo a reload
        # never resumes the newest entry).
        'recent': [],
        'recentStatus': None,          # e.g. 500: recent-tracks fails
        # Replaying a fixture instead: {'fixture': 'play-log-boundary', 'keys': 'play' | 'old', 'n': <hand-outs so far>}.
        # 'play' = the answers as the real services gave them; 'old' = the same plays keyed by the queue's track id
        # (what the code answered before the play log, V7) — the control that must fail.
        'replay': None,
        # Seconds to wait before answering a path, e.g. {'/dj/dashboard/player-lease': 2.5}: an answer that arrives late.
        # (The request is logged at once, and the answer says what the state was when the request came.)
        'delays': {},
        'playbackMode': 'AUTO',        # the party's Auto-Pilot setting: in every lease answer, and in the poll's <tbody> when it is sent
        # The rows of the queue table that poll answers with: [{id, name, url}] (accepted songs; url = the track link).
        # The rendered page itself has two rows, but the first poll replaces them with these.
        'queue': [],
        'historyStatus': None,         # e.g. 500: GET history-view/fragment fails (the History tab and its buttons must cope)
        'queueVersion': 'v1',          # X-Queue-Version of GET fallback-queue
        'queueActionStatus': 204,      # the answer of POST fallback-queue/move|place|skip (409: the player took the track)
        'commandStatus': 204,          # the answer of POST player-command (409: no window plays, nobody would carry it out)
        # The answer of POST fallback-playlist (the DJ pressed Save): its X-Fallback-* headers, and the party's playlist
        # afterwards (the next lease answer names it).
        'fallbackSave': {'playlistId': PARTY_CODE_PLAYLIST, 'import': 'ok', 'tracks': 3, 'reason': None},
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
        self.fixtures = {}
        self.result_events = {}
        self.result_data = {}

    def event(self, name):
        with self.lock:
            return self.result_events.setdefault(name, threading.Event())

    def fixture(self, name):
        if name not in self.fixtures:
            with open(os.path.join(self.browser, 'fixtures', name + '.json'), encoding='utf-8') as f:
                self.fixtures[name] = json.load(f)
        return self.fixtures[name]

    def page(self, which, scenario):
        with open(os.path.join(self.rendered, which + '.html'), encoding='utf-8') as f:
            text = f.read()
        head = '<script src="/harness/fake-yt.js"></script><script src="/harness/harness.js"></script>'
        text = text.replace('<head>', '<head>' + head, 1)
        if scenario:
            files = sorted(os.listdir(os.path.join(self.browser, 'scenarios')))
            tags = ''.join('<script src="/harness/scenarios/%s"></script>' % n for n in files if n.endswith('.js'))
            text = text.replace('</body>', tags + '</body>', 1)
        return text

    # ---- replaying a fixture ----

    def _old(self, fixture, track):
        """A track as the play log's predecessor keyed it: by the id of the queue's track (the same video = the same key)."""
        track = dict(track)
        old_id = fixture['trackIdByVideo'][track['videoId']]
        track['id'] = old_id
        if 'key' in track:
            track['key'] = 'B:%d' % old_id
        return track

    def replay_next(self):
        replay = self.state['replay']
        fixture = self.fixture(replay['fixture'])
        n = replay['n']
        if n >= len(fixture['steps']):
            return None
        replay['n'] = n + 1
        answer = fixture['steps'][n]['nextTrack']
        return self._old(fixture, answer) if replay['keys'] == 'old' else answer

    def replay_recent(self):
        replay = self.state['replay']
        fixture = self.fixture(replay['fixture'])
        n = replay['n']
        if n == 0:
            return []
        recent = fixture['steps'][min(n, len(fixture['steps'])) - 1]['recentTracks']
        return [self._old(fixture, e) for e in recent] if replay['keys'] == 'old' else recent

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
            replay = self.state['replay']
            if replay:
                replay.setdefault('n', 0)
                replay.setdefault('keys', 'play')
                # the lease answer names the playlist of the recorded tracks, or the script would stop them as stale
                self.state['lease']['fallbackPlaylistId'] = self.fixture(replay['fixture'])['playlistId']
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
            return self._send(200, stand.page(query.get('page', ['dashboard'])[0], 'scenario' in query), 'text/html; charset=utf-8')
        if path.startswith('/js/') or path.startswith('/css/'):
            return self._file(stand.static, path[1:])
        if path.startswith('/harness/'):
            return self._file(stand.browser, path[len('/harness/'):])
        if path == '/favicon.ico':
            return self._send(204)
        if path == '/__log':
            with stand.lock:
                return self._json(stand.state)
        self._note('GET', path, self._fields(query, b'', ''))
        state = stand.state
        if path == '/dj/dashboard/recent-tracks':
            with stand.lock:
                if state['recentStatus']:
                    return self._send(state['recentStatus'])
                return self._json(stand.replay_recent() if state['replay'] else state['recent'])
        if path == '/dj/dashboard/updates':
            with stand.lock:
                mode, queue = state['playbackMode'], list(state['queue'])
            # Like the real server: the ETag is a fingerprint of the guest queue only, so a change of the Auto-Pilot setting alone is
            # answered 304 — a window learns it from the lease answers.
            etag = '"q-%08x"' % zlib.crc32(json.dumps(queue, sort_keys=True).encode('utf-8'))
            if self.headers.get('If-None-Match') == etag:
                return self._send(304, headers={'ETag': etag})
            # a row has the song cell that the column sort reads (data-sort-value / data-val) and, after the rows, the real
            # "nothing matches" row that the search box shows and hides
            rows = ''.join('<tr data-song-id="%d" data-song-name="%s" data-track-url="%s"><td data-sort-value="song" data-val="%s">%s</td></tr>'
                           % (r['id'], html.escape(r['name'], True), html.escape(r['url'], True), html.escape(r['name'], True), html.escape(r['name']))
                           for r in queue)
            body = '<tbody id="song-list" data-playback-mode="%s" data-provider="YOUTUBE">%s%s</tbody>' % (mode, rows, stand.nomatch_row())
            return self._send(200, body, 'text/html; charset=utf-8', {'ETag': etag})
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
        if path == '/dj/dashboard/fallback-queue':
            # the REAL fragment as DashboardPageRenderTest renders it (four tracks); a bare placeholder if it was not rendered
            listing = os.path.join(stand.rendered, 'fallback-queue.html')
            body = '<div data-order="playlist"></div>'
            if os.path.isfile(listing):
                with open(listing, encoding='utf-8') as f:
                    body = f.read()
            return self._send(200, body, 'text/html; charset=utf-8', {'X-Queue-Version': state['queueVersion']})
        return self._send(404, b'not found', 'text/plain')

    # ---- POST ----

    def do_POST(self):
        stand = self.stand
        self._delay = 0
        url = urlparse(self.path)
        path, query = url.path, parse_qs(url.query)
        length = int(self.headers.get('Content-Length') or 0)
        body = self.rfile.read(length) if length else b''
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
        if path == '/dj/dashboard/player-lease':
            with stand.lock:
                answer = {'holder': state['lease']['holder'], 'free': state['lease']['free'],
                          'fallbackPlaylistId': state['lease']['fallbackPlaylistId'],
                          'queueVersion': state['lease']['queueVersion'], 'command': None,
                          'playing': state['lease']['playing'], 'playbackMode': state['playbackMode']}
                if answer['holder'] and state['commands']:
                    answer['command'] = state['commands'].pop(0)
            return self._json(answer)
        if path == '/dj/dashboard/next-track':
            with stand.lock:
                if state['nextTrackStatus']:
                    return self._send(state['nextTrackStatus'])
                if state['replay']:
                    answer = stand.replay_next()
                else:
                    answer = state['nextTracks'].pop(0) if state['nextTracks'] else None
            return self._send(204) if answer is None else self._json(answer)
        if path == '/dj/dashboard/fallback-playlist':
            with stand.lock:
                save = copy.deepcopy(state['fallbackSave'])
                cleared = not fields.get('fallbackPlaylistUrl', 'x')
                state['lease']['fallbackPlaylistId'] = None if cleared else save['playlistId']
            headers = {}
            if not cleared:
                headers['X-Fallback-Id'] = save['playlistId']
                headers['X-Fallback-Import'] = save['import']
                if save['import'] == 'ok':
                    headers['X-Fallback-Tracks'] = str(save['tracks'])
                elif save.get('reason'):
                    headers['X-Fallback-Import-Reason'] = save['reason']
            return self._send(200, b'', headers=headers)
        if path == '/dj/dashboard/playback-mode':      # the Auto-Pilot switch: sets the mode it sends (toggles without one)
            with stand.lock:
                wanted = fields.get('mode')
                state['playbackMode'] = wanted if wanted in ('AUTO', 'MANUAL') else ('MANUAL' if state['playbackMode'] == 'AUTO' else 'AUTO')
            return self._json({})
        if path in ('/dj/dashboard/play', '/dj/dashboard/fallback-shuffle'):
            return self._json({})
        if path == '/dj/dashboard/player-command':      # the DJ's command for the window that plays (204 waiting, 409 nobody plays)
            return self._send(state['commandStatus'])
        if path == '/dj/dashboard/player-lease/release':
            return self._send(204)
        if path.startswith('/dj/dashboard/fallback-queue/'):    # move, place, skip
            return self._send(state['queueActionStatus'])
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
