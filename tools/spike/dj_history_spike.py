#!/usr/bin/env python3
"""
A risk spike for docs/ideas.md item 18 ("a program on the DJ's computer that reads what really plays").

It reads the play history a DJ program writes on the DJ's computer and prints each played song as "HH:MM  Artist - Title",
the way a background program would send it to Scan2Play. It sends nothing, writes nothing and never opens a DJ program's file
for writing (SQLite in read-only mode). Not part of the app: no Maven, no tests of the build.

Readers (the formats as the open-source projects that already read them describe them — see the analysis, docs/ideas.md 18):
  vdj      VirtualDJ 8+: Documents/VirtualDJ/History/<YYYY-MM-DD>.m3u — a plain text file a line is appended to once a song
           has played `historyDelay` seconds; the song's line "#EXTVDJ:<time>21:39</time><lastplaytime>1674884385</lastplaytime>
           <artist>…</artist><title>…</title>" before its path (What's Now Playing, nowplaying/inputs/m3u.py and tests/test_m3u.py).
  serato3  Serato DJ 2.x/3.x: Music/_Serato_/History/Sessions/<n>.session — binary chunks (4-byte tag, 4-byte big-endian length),
           an "oent" a song with its "adat" fields (6 title, 7 artist, 28 start time, 31 deck, 50 played), UTF-16-BE text;
           appended on every load / eject while Serato runs (SSLScrobbler's README; WNP nowplaying/serato3/session.py).
  serato4  Serato DJ 4.x: <AppData>/Serato/Library/master.sqlite — table history_entry (artist, name, start_time, played, deck)
           of the open history_session (end_time = -1) (WNP nowplaying/serato/reader.py). Not tried on a real file here.
  (rekordbox: master.db is SQLCipher-encrypted and rekordbox 7's key is not on the disk in the open — not read here on purpose.)

Usage:
  python tools/spike/dj_history_spike.py vdj      <file.m3u | History dir>        [--follow]
  python tools/spike/dj_history_spike.py serato3  <file.session | Sessions dir>   [--follow]
  python tools/spike/dj_history_spike.py serato4  <master.sqlite>                 [--follow]
  python tools/spike/dj_history_spike.py selftest
--follow polls every 2 s and prints only what was added — what the background program would send during a set.
"""

import datetime
import html
import pathlib
import re
import sqlite3
import struct
import sys
import tempfile
import time
import unicodedata

# ---------------------------------------------------------------------------------------------------------------- the songs


class Played:
    """One played song as the program would send it: never a path (it names the DJ's computer and account)."""

    def __init__(self, at, artist, title, deck=None):
        self.at = at  # datetime, local time of the DJ's computer
        self.artist = (artist or "").strip()
        self.title = (title or "").strip()
        self.deck = deck

    def key(self):
        """A rough twin of SongNames.comparable over "artist|title" — to show which lines would match the same request."""
        def comparable(text):
            plain = unicodedata.normalize("NFD", text).replace("ł", "l").replace("Ł", "L")
            plain = "".join(c for c in plain if not unicodedata.combining(c))
            return re.sub(r"[^\w]|_", "", plain.lower())
        return comparable(self.artist) + "|" + comparable(re.sub(r"\([^)]*\)|\[[^\]]*]", " ", self.title))

    def __str__(self):
        when = self.at.strftime("%H:%M") if self.at else "--:--"
        deck = f"  [deck {self.deck}]" if self.deck else ""
        return f"{when}  {self.artist or '?'} - {self.title or '?'}{deck}   key={self.key()}"


# ---------------------------------------------------------------------------------------------------------------- VirtualDJ

VDJ_TAG = re.compile(r"<(\w+)>(.*?)</\1>")


def read_vdj(path):
    """The songs of a VirtualDJ history file (or of the newest .m3u of a History folder), oldest first."""
    path = pathlib.Path(path)
    if path.is_dir():
        files = sorted(path.glob("*.m3u"), key=lambda p: p.stat().st_mtime)
        if not files:
            return []
        path = files[-1]
    songs = []
    # VirtualDJ writes UTF-8 (a BOM on some versions); a song's tags are one line.
    for line in path.read_text(encoding="utf-8-sig", errors="replace").splitlines():
        if not line.startswith("#EXTVDJ:"):
            continue
        tags = {name: html.unescape(value) for name, value in VDJ_TAG.findall(line)}
        at = None
        if tags.get("lastplaytime", "").isdigit():
            at = datetime.datetime.fromtimestamp(int(tags["lastplaytime"]))
        songs.append(Played(at, tags.get("artist"), tags.get("title")))
    return songs


# ---------------------------------------------------------------------------------------------------------------- Serato 3

SERATO_ADAT = {6: "title", 7: "artist", 28: "starttime", 31: "deck", 50: "played"}


def _chunks(data):
    i = 0
    while i + 8 <= len(data):
        tag = data[i:i + 4].decode("latin-1")
        length = struct.unpack(">I", data[i + 4:i + 8])[0]
        yield tag, data[i + 8:i + 8 + length]
        i += 8 + length


def _adat(data):
    fields = {}
    i = 0
    while i + 8 <= len(data):
        tag, length = struct.unpack(">II", data[i:i + 8])
        value = data[i + 8:i + 8 + length]
        name = SERATO_ADAT.get(tag)
        if name in ("title", "artist"):
            fields[name] = value.decode("utf-16-be", errors="replace").rstrip("\x00")
        elif name in ("starttime", "deck") and len(value) in (4, 8):
            fields[name] = struct.unpack(">I" if len(value) == 4 else ">Q", value)[0]
        elif name == "played" and value:
            fields[name] = bool(value[0])
        i += 8 + length
    return fields


def read_serato3(path):
    """The played songs of a Serato session file (or of the newest one in a Sessions folder), by their start time."""
    path = pathlib.Path(path)
    if path.is_dir():
        files = sorted(path.glob("*.session"), key=lambda p: p.stat().st_mtime)
        if not files:
            return []
        path = files[-1]
    songs = []
    for tag, body in _chunks(path.read_bytes()):
        if tag != "oent":
            continue
        for inner, value in _chunks(body):
            if inner != "adat":
                continue
            fields = _adat(value)
            if fields.get("played") is False:
                continue  # loaded on a deck, never played
            at = datetime.datetime.fromtimestamp(fields["starttime"]) if fields.get("starttime") else None
            songs.append(Played(at, fields.get("artist"), fields.get("title"), fields.get("deck")))
    return sorted(songs, key=lambda s: s.at or datetime.datetime.min)


# ---------------------------------------------------------------------------------------------------------------- Serato 4

SERATO4_QUERY = """
    SELECT h.artist, h.name, h.start_time, h.deck
    FROM history_entry h
    WHERE h.session_id = (SELECT id FROM history_session WHERE end_time = -1 ORDER BY start_time DESC LIMIT 1)
      AND h.played = 1
    ORDER BY h.start_time
    LIMIT 2000
"""


def read_serato4(path):
    """The played songs of Serato 4's open history session, read-only (Serato keeps writing while it runs)."""
    uri = pathlib.Path(path).resolve().as_uri() + "?mode=ro"
    with sqlite3.connect(uri, uri=True, timeout=5) as conn:
        rows = conn.execute(SERATO4_QUERY).fetchall()
    return [Played(datetime.datetime.fromtimestamp(start) if start else None, artist, title, deck)
            for artist, title, start, deck in rows]


# ---------------------------------------------------------------------------------------------------------------- the loop

READERS = {"vdj": read_vdj, "serato3": read_serato3, "serato4": read_serato4}


def follow(reader, path, every=2.0):
    """What the background program would do during a set: read again, print (= send) only the songs not seen before."""
    seen = 0
    while True:
        try:
            songs = reader(path)
        except (OSError, sqlite3.Error, struct.error) as err:  # a file being written: try again next time
            print(f"(not read this time: {err})", file=sys.stderr)
            songs = None
        if songs is not None:
            if len(songs) < seen:  # a new day's file, a new session
                seen = 0
            for song in songs[seen:]:
                print("NEW", song, flush=True)
            seen = len(songs)
        time.sleep(every)


# ---------------------------------------------------------------------------------------------------------------- selftest


def _serato_chunk(tag, body):
    return tag.encode("latin-1") + struct.pack(">I", len(body)) + body


def _serato_field(tag, body):
    return struct.pack(">II", tag, len(body)) + body


def selftest():
    """Sample files in each format (as the projects above describe them), read back; no DJ program needed."""
    tmp = pathlib.Path(tempfile.mkdtemp(prefix="s2p-spike-"))
    start = int(datetime.datetime(2026, 11, 21, 21, 39).timestamp())

    vdj = tmp / "2026-11-21.m3u"
    vdj.write_text(
        "#EXTVDJ:<time>21:39</time><lastplaytime>%d</lastplaytime><artist>Sanah</artist><title>Szampan</title>\n"
        "netsearch://dz1\n"
        "#EXTVDJ:<time>21:43</time><lastplaytime>%d</lastplaytime><artist>Kayah &amp; Bregović</artist>"
        "<title>Prawy do lewego (Radio Edit)</title>\nC:\\Muzyka\\kayah.mp3\n" % (start, start + 240),
        encoding="utf-8")
    got = [str(s) for s in read_vdj(tmp)]
    assert len(got) == 2 and "Kayah & Bregović - Prawy do lewego" in got[1], got

    def oent(title, artist, at, played):
        adat = (_serato_field(6, title.encode("utf-16-be")) + _serato_field(7, artist.encode("utf-16-be"))
                + _serato_field(28, struct.pack(">I", at)) + _serato_field(31, struct.pack(">I", 1))
                + _serato_field(50, bytes([1 if played else 0])))
        return _serato_chunk("oent", _serato_chunk("adat", adat))
    session = tmp / "1.session"
    session.write_bytes(_serato_chunk("vrsn", "1.0/Serato Scratch LIVE Review".encode("utf-16-be"))
                        + oent("Szampan", "Sanah", start, True) + oent("Loaded only", "Nobody", start + 60, False)
                        + oent("Zenek", "Zenon Martyniuk", start + 200, True))
    got = [str(s) for s in read_serato3(tmp)]
    assert len(got) == 2 and "Zenon Martyniuk - Zenek" in got[1], got

    db = tmp / "master.sqlite"
    with sqlite3.connect(db) as conn:
        conn.executescript("""
            CREATE TABLE history_session (id INTEGER PRIMARY KEY, start_time INTEGER, end_time INTEGER);
            CREATE TABLE history_entry (id INTEGER PRIMARY KEY, session_id INTEGER, artist TEXT, name TEXT,
                                        start_time INTEGER, played INTEGER, deck INTEGER);""")
        conn.execute("INSERT INTO history_session VALUES (1, ?, ?)", (start - 9000, start - 3600))
        conn.execute("INSERT INTO history_session VALUES (2, ?, -1)", (start - 60,))
        conn.executemany("INSERT INTO history_entry (session_id, artist, name, start_time, played, deck) VALUES (?,?,?,?,?,?)", [
            (1, "Old", "Yesterday", start - 8000, 1, 1),
            (2, "Sanah", "Szampan", start, 1, 1),
            (2, "Preview", "Not played", start + 30, 0, 2),
            (2, "Dawid Podsiadło", "Małomiasteczkowy", start + 200, 1, 2)])
    got = [str(s) for s in read_serato4(db)]
    assert len(got) == 2 and "Dawid Podsiadło - Małomiasteczkowy" in got[1], got
    print("selftest OK:", tmp)


def main(argv):
    if len(argv) >= 1 and argv[0] == "selftest":
        selftest()
        return 0
    if len(argv) < 2 or argv[0] not in READERS:
        print(__doc__)
        return 2
    reader, path = READERS[argv[0]], argv[1]
    if "--follow" in argv:
        follow(reader, path)
    for song in reader(path):
        print(song)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
