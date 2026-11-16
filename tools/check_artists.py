"""Checks the artists the guests' song field completes a word to (ARTISTS in static/js/song-autocomplete.js).

    python tools/check_artists.py              every artist asked of iTunes (~3 s each: iTunes allows ~20 asks a minute)
    python tools/check_artists.py --offline    only the list: repeated names, the beginnings that go to an artist

For the review every few months (docs/song-suggestions-artists.md). It prints:
  - the artists iTunes gives no song for under the name asked (a wrong spelling, an artist iTunes names otherwise — then an alias
    "what guests type=what iTunes calls them" —, or one gone from the Polish store);
  - the beginnings of 3–5 letters shared by several artists, and which one wins (the first on the list): reorder when the winner is
    the less likely at a party.
Standard library only; reads nothing but the JS file and iTunes's public search.
"""
import argparse
import json
import re
import sys
import time
import unicodedata
import urllib.parse
import urllib.request
from collections import defaultdict
from pathlib import Path

JS = Path(__file__).resolve().parent.parent / 'src/main/resources/static/js/song-autocomplete.js'
SKIPPED = {'the', 'and', 'band', 'orkiestra', 'orchestra', 'feat', 'dj', 'project'}   # as ARTIST_SKIPPED in the JS


def plain(text):
    text = unicodedata.normalize('NFD', (text or '').lower())
    return ''.join(c for c in text if not unicodedata.combining(c)).replace('ł', 'l')


def words(text):
    return [w for w in re.split(r'[\W_]+', plain(text)) if w]


def artists():
    source = JS.read_text(encoding='utf-8')
    block = source[source.index('const ARTISTS = ['):]
    block = block[:block.index('];')]
    block = re.sub(r'//[^\n]*', '', block)
    return [m.group(1).replace("\\'", "'") for m in re.finditer(r"'((?:[^'\\]|\\.)*)'", block)]


def ask(name):
    query = urllib.parse.urlencode({'term': name, 'media': 'music', 'entity': 'song', 'country': 'PL', 'limit': 25})
    with urllib.request.urlopen('https://itunes.apple.com/search?' + query, timeout=15) as response:
        return json.load(response)['results']


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--offline', action='store_true', help='only the list, no iTunes')
    args = parser.parse_args()
    entries = artists()
    print(f'{len(entries)} artists in {JS.name}')

    repeated = sorted({e for e in entries if entries.count(e) > 1})
    if repeated:
        print('REPEATED:', ', '.join(repeated))

    winners = defaultdict(list)
    for entry in entries:
        for w in words(' '.join(entry.split('='))):
            if w in SKIPPED:
                continue
            for n in range(3, min(len(w), 6)):
                if entry not in winners[w[:n]]:
                    winners[w[:n]].append(entry)
    whole = {w for e in entries for w in words(' '.join(e.split('=')))}
    shared = {p: names for p, names in winners.items() if len(names) > 1 and p not in whole}
    print(f'\n{len(shared)} beginnings shared by several artists (the first wins):')
    for p in sorted(shared):
        print(f'  {p:6} → {shared[p][0]}   (also: {", ".join(shared[p][1:])})')

    if args.offline:
        return 0
    print('\niTunes, the Polish store:')
    missing = []
    for i, entry in enumerate(entries):
        name = entry.split('=')[-1]
        try:
            songs = ask(name)
        except Exception as e:   # one failed ask is reported, not the end of the run
            print(f'  ?? {name}: {e}')
            songs = None
        if songs is not None:
            named = words(name)
            theirs = [s for s in songs if all(w in words(s.get('artistName')) for w in named)]
            if not theirs:
                missing.append(name)
                print(f'  NONE {name}  (iTunes gave: {", ".join(sorted({s.get("artistName", "") for s in songs[:5]}))})')
        if i + 1 < len(entries):
            time.sleep(3)
    print(f'\n{len(entries) - len(missing)} of {len(entries)} artists found; without songs: {", ".join(missing) or "none"}')
    return 1 if missing else 0


if __name__ == '__main__':
    sys.exit(main())
