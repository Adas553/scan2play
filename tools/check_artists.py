"""Checks the artists the guests' song field completes a word to (ARTISTS in static/js/song-autocomplete.js).

    python tools/check_artists.py              every artist asked of iTunes (~3 s each: iTunes allows ~20 asks a minute)
    python tools/check_artists.py --offline    only the list: repeated names, the beginnings that go to an artist

For the review every few months (docs/song-suggestions-artists.md). It prints:
  - the artists iTunes gives no song for under the name asked (a wrong spelling, an artist iTunes names otherwise — then an alias
    "what guests type=what iTunes calls them" —, or one gone from the Polish store), and those it gives fewer than FEW_BELOW songs
    of among its 25 (a name iTunes matches mostly to others, "Classic": completing to it shows little of theirs);
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
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict
from pathlib import Path

JS = Path(__file__).resolve().parent.parent / 'src/main/resources/static/js/song-autocomplete.js'
FEW_BELOW = 3   # songs of the artist among iTunes's 25: fewer is reported (docs/song-suggestions-artists.md: a candidate needs 3)


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


def skipped():
    """ARTIST_SKIPPED of the JS: the artists' words that begin nothing."""
    source = JS.read_text(encoding='utf-8')
    line = source[source.index('const ARTIST_SKIPPED = new Set(['):]
    line = line[:line.index('])')]
    return set(re.findall(r"'([^']*)'", line))


def alias_words(entries):
    """ALIAS_WORDS of the JS: a typed word of an alias that iTunes does not know as theirs ("zenek" → "zenon")."""
    named = [words(e.split('=')[-1]) for e in entries]
    others = {w for n in named for w in n}
    found = {}
    for entry in entries:
        names = entry.split('=')
        if len(names) < 2:
            continue
        typed, asked = words(names[0]), words(names[-1])
        for i, w in enumerate(typed):
            if w not in asked and i < len(asked) and w not in others:
                found[w] = asked[i]
    return found


def ask(name):
    query = urllib.parse.urlencode({'term': name, 'media': 'music', 'entity': 'song', 'country': 'PL', 'limit': 25})
    for attempt in range(3):
        try:
            with urllib.request.urlopen('https://itunes.apple.com/search?' + query, timeout=15) as response:
                return json.load(response)['results']
        except urllib.error.HTTPError as e:
            if e.code != 429 or attempt == 2:
                raise
            time.sleep(60)   # iTunes's limit (~20 asks a minute) reached: a minute's pause, then again


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--offline', action='store_true', help='only the list, no iTunes')
    args = parser.parse_args()
    entries = artists()
    print(f'{len(entries)} artists in {JS.name}')

    repeated = sorted({e for e in entries if entries.count(e) > 1})
    if repeated:
        print('REPEATED:', ', '.join(repeated))

    skip = skipped()
    aliases = alias_words(entries)
    if aliases:
        print('Typed words of the aliases, completed even when whole:', ', '.join(f'{w} → {n}' for w, n in aliases.items()))
    winners = defaultdict(list)
    for entry in entries:
        all_words = words(' '.join(entry.split('=')))
        # as artistBegun: the words, and a short word joined with its neighbour ("acdc", "dagostino")
        joined = [a + b for a, b in zip(all_words, all_words[1:]) if len(a) <= 2 or len(b) <= 2]
        for w in [w for w in all_words if w not in skip] + joined:
            for n in range(3, min(len(w), 6)):
                if entry not in winners[w[:n]]:
                    winners[w[:n]].append(entry)
    whole = {w for e in entries for w in words(' '.join(e.split('=')))} - set(aliases)
    shared = {p: names for p, names in winners.items() if len(names) > 1 and p not in whole}
    print(f'\n{len(shared)} beginnings shared by several artists (the first wins):')
    for p in sorted(shared):
        print(f'  {p:6} → {shared[p][0]}   (also: {", ".join(shared[p][1:])})')

    if args.offline:
        return 0
    print('\niTunes, the Polish store:')
    missing, few, unchecked = [], [], []
    for i, entry in enumerate(entries):
        name = entry.split('=')[-1]
        try:
            songs = ask(name)
            if not songs:   # iTunes sometimes answers a known name with nothing: once more before it is reported
                time.sleep(10)
                songs = ask(name)
        except Exception as e:   # one failed ask is reported, not the end of the run
            print(f'  ?? {name}: {e}')
            unchecked.append(name)
            songs = None
        if songs is not None:
            named = words(name)
            theirs = [s for s in songs if all(w in words(s.get('artistName')) for w in named)]
            if not theirs:
                missing.append(name)
                print(f'  NONE {name}  (iTunes gave: {", ".join(sorted({s.get("artistName", "") for s in songs[:5]}))})')
            elif len(theirs) < FEW_BELOW:
                few.append(name)
                print(f'  FEW  {name}: {len(theirs)} of {len(songs)}  (iTunes gave: '
                      f'{", ".join(sorted({s.get("artistName", "") for s in songs[:5]}))})')
        if i + 1 < len(entries):
            time.sleep(3)
    found = len(entries) - len(missing) - len(unchecked)
    print(f'\n{found} of {len(entries)} artists found; without songs: {", ".join(missing) or "none"}; '
          f'with fewer than {FEW_BELOW}: {", ".join(few) or "none"}; not checked (iTunes failed): {", ".join(unchecked) or "none"}')
    return 1 if missing or few or unchecked else 0


if __name__ == '__main__':
    sys.exit(main())
