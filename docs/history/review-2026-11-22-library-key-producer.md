# Review 2026-11-22 — klucz biblioteki: „- Prod. by X” po myślniku to wersja (V79)

Gałąź `claude/jovial-hodgkin-40e080`, jeden commit `145f3c1` (PR Adas553/scan2play#303 do `main`, Adas553/scan2play#304 do `dev`),
migracja V79 (zmienia wiersze `dj_library_track`), przed scaleniem. Diff: `git diff origin/main...origin/claude/jovial-hodgkin-40e080`
(6 plików, +202 −4). Skąd poprawka: `docs/history/review-2026-11-22-p3-serato-play-count.md` (na gałęzi
`claude/youthful-heisenberg-3whpcr`), „Próba po review P3”, punkt 2 — „Bebeshito – Toketeo” nie oznacza się, bo Serato pisze tytuł
„Toketeo - Prod. by Dj Honda x DJ Jerry”.

## Werdykt

**Nie scalać w tej postaci; zmiana w Javie dobra, V79 do poprawienia.** `LibraryKey.VERSION_AFTER_DASH` robi to, co trzeba
(„Prodigy”, „Production”, „Prodigal Son”, „- Producer” zostają), a „Zagrane samo” działa od razu po wdrożeniu, bez migracji
(`DeckHistoryService.markPlayed` liczy oba klucze w Javie). V79 ma jednak trzy wady: potrafi dać wierszowi klucz, którego Java nigdy
nie zrobi, i przez to skasować inną piosenkę (P1); skasować wiersz, którego klucz się zwalnia (P3); a jej `DELETE` rośnie
kwadratowo — 25 000 wierszy z „prod by” to 32 s, przy ~100 000 start na Railway przekroczyłby czas healthchecka (P2). Na dzisiejszej
produkcji (cała baza to zrzut 255 KB) skutki byłyby małe, ale komentarz migracji obiecuje „never given another song's key”, a to
nieprawda. Poprawki P1–P3 są proste (prototyp niżej: 0 złych kluczy w teście różnicowym, poza jednym tytułem ze znakiem sterującym).

## Sprawdzenia

W kopii gałęzi (`git worktree` w `/tmp/s2p/rev`, Maven tylko przez `tools/check.py`, który i tak kopiuje dalej), PostgreSQL 16
z kontenera (hasło `postgres` ustawione na `1111`, jak w `application-local.properties`), bazy tylko `s2p_*`, nigdy `scan2play`.

- `check.py unit`: 1536 testów, 0 błędów. `check.py it -t 'LibraryKeyProducerMigrationIT,MigrationIT,SongRequestRepositoryIT'`:
  40 testów, 0 błędów.
- **Test różnicowy Java ↔ SQL** (`s2p_review_diff`): `LibraryKey` z `main` i z gałęzi skompilowane osobno; 20 065 tytułów (65
  ręcznie: myślniki typograficzne, dwa „Prod.”, „Prodigy”, „Production”, „Øyvind”, cyrylica, „feat.” w ogonie, `--`, 200+ znaków,
  „ing”/„number” na granicy; reszta losowa z tych słów); dla 19 536 z niepustym kluczem wiersz `(stary klucz, title_name)` → V79 →
  porównanie z nowym kluczem Javy. Wynik: **16 029 zgodnych, 3 191 nieruszonych (klucz zostaje stary — piosenka nie pasuje do
  ponownego wgrania), 316 zmienionych na zły klucz.**
- **Wydajność** (`s2p_review_perf`, schemat z Flyway do V78): 50 imprez × 10 000 utworów + 250 = 500 250 wierszy (105 MB), co 20.
  utwór „Song n - Prod. by DJ k”, 250 z nich ma już klucz bez producenta, 10 000 „- Prodigy Tribute”. V79 przez Flyway: **33,0 s**.
  `EXPLAIN ANALYZE` w transakcji wycofanej na kopii bazy: tabela tymczasowa 0,69 s (pełny skan z `ILIKE`), `DELETE` **32,4 s**,
  `UPDATE` 0,20 s. Z indeksem na tabeli tymczasowej (P2): `DELETE` 0,13 s. Po dodaniu „- Prod. by DJ 1” do kolejnych ~88 000
  utworów (113 606 wierszy z „prod”): tabela 2,0 s, `DELETE` 0,75 s, `UPDATE` 0,72 s — z indeksem; bez niego nie mierzone
  (z planu: 24 750 przejść × 25 000 wierszy — kwadratowo, ~20× więcej niż przy 25 000, czyli ~10 min).
- Mutacje w kopii (każda cofnięta po przebiegu): wyniki w P5.
- Numeracja: żadna inna gałąź z `origin` nie dodaje migracji poza `main` (V77 `claude/event-plan` jest już w `main` jako
  `V77__event_plan.sql`, V78 też); #301/#302 bez migracji. `git merge-tree` z `main` i z `dev`: bez konfliktów.

## Ustalenia

### P1 — dane: V79 potrafi nadać wierszowi zły klucz i skasować przez to inną piosenkę

`V79__library_key_producer.sql:13-28`. SQL robi klucz ogona inaczej niż `SongNames.comparable` + `LibraryKey.of`:

- usuwa wszystko spoza `a-z0-9` (`'[^a-z0-9]'`), a Java zostawia każdą literę i cyfrę Unicode (`[^\p{L}\p{N}]`): „ø”, „æ”, „ß”,
  „đ”, cyrylica, greka;
- nie usuwa „feat. X” (Java: `FEATURING` przed `VERSION_AFTER_DASH`), nie skleja `--` ani `- -` (Java: `SongNames.tidy`).

Strażnik „klucz kończy się ogonem” (`:28`) łapie większość różnic (wiersz zostaje nieruszony), ale nie wtedy, gdy znaki, których
SQL nie liczy, stoją **na początku** ogona: wtedy reszta ogona jest końcówką klucza i SQL ucina tylko ją. Przykłady z testu
różnicowego (stary klucz → po V79 → nowy klucz Javy):

- „Song - Øya Prod. by X”: `songøyaprodbyx` → `songø` (Java: `song`);
- „Produced Śliwa Remix – Ничего prod by”: → `producedsliwaremixничего` (Java: `producedsliwaremix`);
- „feat. Ging – Ничего prod by Noc Prod.”: → `ничего` (Java zostawia `ничегоprodbynocprod` — „feat.” na początku tytułu zjada
  myślnik, więc Java nic nie ucina, a SQL ucina).

Scenariusz utraty danych (sprawdzony na `s2p_review_diff`): impreza ma „Ola - Ничего prod by X” (`olaничегоprodbyx`) i piosenkę
„Ola Ничего” (`olaничего`) tych samych wykonawców. V79 liczy nowy klucz `olaничего`, znajduje go w tabeli i **kasuje** pierwszy
utwór — piosenkę „Ola”, której klucz w Javie to `ola`. Bez kolizji wiersz dostaje klucz, którego żadna prośba nie zrobi, czyli
„✗ Nie masz” na zawsze (do ponownego wgrania), albo — gorzej — klucz innego tytułu, który wtedy dostaje „✓”/„?”.

Dla polskich tytułów ryzyko jest małe (ą, ę, ś, ż… rozkłada NFD, „ł” jest w `translate`), ale komentarz w `:7-9` obiecuje coś, czego
kod nie trzyma, a biblioteki DJ-ów mają też tytuły hiszpańskie, skandynawskie, rosyjskie.

**Poprawka (proponowana, prototyp sprawdzony):** liczyć w SQL **cały stary klucz** z `title_name` i zmieniać tylko wiersz, w którym
wyszedł dokładnie taki, jak zapisany `title_key` — to dowodzi, że SQL odtworzył Javę dla tego wiersza; nowy klucz liczyć z tytułu
bez ogona (nie ucinać końcówki klucza). Do tego kroki Javy w tej samej kolejności: myślniki → jeden, `--` sklejone, nawiasy,
„feat.”, ogon; litery `[^[:alnum:]]` zamiast `[^a-z0-9]` (a gdyby `[:alnum:]` bazy różnił się od `\p{L}\p{N}`, równość starego klucza
po prostu wiersz pominie). Prototyp na tych samych 19 536 wierszach: **19 421 zgodnych, 114 nieruszonych, 1 zły** (tytuł ze znakiem
sterującym `\u0001`, który Java zamienia na „ - ” — można dodać i ten krok). Przy okazji znika kłopot z „ing”/„number” na granicy
ogona („Sin - Good prod by X” dziś zostaje nieruszone) i z kluczem uciętym do 200 (pominięty, jak trzeba). Test: wiersze
„Ola - Ничего…” + „Ola Ничего”, „Song - Øya Prod. by X”, „Song - Prod. by X feat. Y”, „Song -- Prod. by X” w
`LibraryKeyProducerMigrationIT` — najpierw czerwone na obecnym V79.

### P2 — wydajność: `DELETE` V79 jest kwadratowy względem liczby wierszy z „prod by”

`V79__library_key_producer.sql:39-43`. Drugie `EXISTS` szuka w `library_producer`, tabeli tymczasowej bez indeksu i bez statystyk
(`CREATE TABLE AS` ich nie zbiera, autovacuum tabel tymczasowych nie widzi): plan to `Seq Scan on library_producer other` dla każdego
wiersza — 24 750 × 25 000. Zmierzone: 25 000 wierszy → 32,4 s (całe V79 33 s). Rośnie z kwadratem: ~100 000 wierszy z „prod by” to
~10 min.

Co to znaczy na Railway: Flyway biegnie przy starcie, zanim Tomcat odpowie na `/privacy`; stary deploy obsługuje ruch dalej (czyta
klucze bez przeszkód — MVCC), ale nowy musi zdążyć przed czasem healthchecka (domyślnie 300 s — ustawienia na Railway nie
sprawdzałem). Za długo: Railway zabija kontener, transakcja Flyway się wycofuje, deploy nieudany, i tak przy każdym kolejnym — dane
całe, ale wydanie stoi. W czasie migracji wgranie biblioteki przez stary deploy na imprezie, której wiersze V79 zmienia
(`DjLibraryService`: `DELETE … WHERE party_code = ?` + `INSERT`), czeka na koniec transakcji V79. Blokad tabeli nie ma (V79 nie
zmienia schematu), tylko blokady zmienianych wierszy.

Dziś produkcja jest mała (nocny zrzut całej bazy 255 KB), więc V79 skończy się w ułamku sekundy — ale jedna kolekcja rekordbox
z serwisu z „Prod. by” w tytułach ma tysiące takich utworów, a migracja jest na zawsze.

**Poprawka:** po `CREATE TEMPORARY TABLE` — `CREATE INDEX ON library_producer (party_code, artists, new_key); ANALYZE
library_producer;` (zmierzone: `DELETE` 0,13 s przy 25 000, 0,75 s przy 113 606). Albo zamiast drugiego `EXISTS` `row_number() OVER
(PARTITION BY party_code, artists, new_key ORDER BY title_key)` przy budowie tabeli. Test czasu nie jest potrzebny; wystarczy plan
z `Index Scan` (sprawdzony ręcznie).

### P3 — dane: utwór kasowany, choć jego nowy klucz się zwalnia (łańcuch)

`V79__library_key_producer.sql:35-38`. Pierwsze `EXISTS` patrzy na tabelę **przed** zmianą, więc za zajęty bierze też klucz wiersza,
który sam za chwilę dostaje nowy klucz. Sprawdzone: impreza ma „Noc - Prod. by A - Prod. by B” (`nocprodbyaprodbyb`, Java po
poprawce: `nocprodbya`) i „Noc - Prod. by A” (`nocprodbya` → `noc`), ci sami wykonawcy. V79 kasuje pierwszy („jest już
`nocprodbya`”), a drugi przenosi na `noc` — po migracji nie ma nikogo z kluczem `nocprodbya`, utwór z dwoma producentami zginął.
Rzadkie (dwa „Prod.” w jednym tytule i ta sama piosenka z jednym), ale to kasowanie, którego nie powinno być.

**Poprawka (najprostsza bezpieczna):** z `library_producer` wyrzucić wiersze, których `new_key` jest starym kluczem innego wiersza
tej tabeli (ta sama impreza i wykonawcy) — zostają ze starym kluczem: najwyżej nie pasują do czasu ponownego wgrania, nic nie
ginie. Przeniesienie obu naraz wymagałoby dwóch kroków (PK jest sprawdzany przy każdym wierszu `UPDATE`, więc kolejność wierszy
mogłaby wywrócić migrację). Test: te dwa wiersze w `LibraryKeyProducerMigrationIT`, najpierw czerwony.

### P4 — skutki: klucze życzeń (`guest_wish.wish_key`) też idą z `LibraryKey` i V79 ich nie zmienia

`WishService.java:298-306` (`keyOf` = `title|artists` z `LibraryKey.of`), zapis `:228` (`ON CONFLICT (event_id, wish_key)`). Gość
wydarzenia, które zbiera życzenia przez wdrożenie, pisze „X - Song - Prod. by Y”, a wcześniej ktoś napisał to samo: przed — jeden
wiersz `songprodbyy|x` z liczbą 2; po — drugi wiersz `song|x`, liczby rozdzielone, na liście DJ-a ta sama piosenka dwa razy. Goście
prawie nigdy nie piszą „Prod. by”, a życzenia żyją do dnia wydarzenia, więc proponuję przyjąć i dopisać jedno zdanie w
`04-1c` (lub `05-1i-wishes.md`); poprawianie `guest_wish` w V79 to scalanie liczników — więcej ryzyka niż pożytku. Decyzja
właściciela (pytanie 2).

### P5 — testy: część przypadków granicznych bez testu, dwa wiersze testu nie testują poprawki

Mutacje w kopii (`check.py unit -t LibraryKeyTest`, `check.py it -t LibraryKeyProducerMigrationIT`; każda cofnięta):

| Mutacja | Wynik |
|---------|-------|
| `LibraryKey`: poprawka cofnięta (bez `prod…by`) | czerwone, 4 z 30 (3 wiersze „po myślniku” w `theSameSong` + `producer()`) |
| `LibraryKey`: samo `prod` (bez „by”) | czerwone, 1 (`producer()`: „Prodigy”) |
| `LibraryKey`: „Production” też jako producent | czerwone, 1 (`producer()`: „Production Line”) |
| `LibraryKey`: bez granicy słowa po „by” (`by[a-z]*`) | **zielone** — „- Prod. Bylina” niczym nie sprawdzone |
| V79: bez strażnika „klucz kończy się ogonem” (`:28`) | **zielone** |
| V79: bez `SPELLINGS` w SQL („ing”, „number”) | **zielone** |
| V79: bez `'łŁ'` w `translate` | **zielone** |
| V79: bez drugiego `EXISTS` | czerwone (błąd PK przy „Noc - Prod. by A / B”) |

Czyli `LibraryKeyTest` jest czerwony po cofnięciu poprawki (opis PR mówi prawdę), ale dwa nowe wiersze `theSameSong` z nawiasami
(„(Prod. by …)”, „[prod. DJ Jerry]”) przechodzą też bez poprawki — nawiasy ucinało się już wcześniej; nie szkodzą, tylko nie
testują zmiany. IT sprawdza kasowanie duplikatu (`toketeo` istniejące), „mniejszy stary klucz zostaje” (Noc A/B), wiersz bez
`title_name`, „Prodigy” nieruszone i drugą imprezę osobno — to, o co pytało zadanie, jest. Brakuje: tytułu, w którym SQL nie umie
odtworzyć Javy (P1), łańcucha (P3), wiersza, który strażnik końcówki musi ominąć, „ł” i „ing”/„number” w ogonie, „feat.” w ogonie.
IT sprawdza też, że Java daje dla tych samych tytułów te same klucze (`:96-99`) — dobry pomysł, ale na czterech tytułach; test
różnicowy z tej review (20 000 tytułów) pokazał resztę. **Poprawka:** dopisać do IT wiersze z P1 i P3 oraz „Ola - Prod. by Łukasz”,
„Sing - Prod. by Number One”, a do `LibraryKeyTest` „X - Song - Prod. Bylina” → `songprodbylina`; każdy najpierw czerwony (te
z P1/P3 na obecnym V79, Bylina na mutacji).

### P6 — drobne: który wiersz zostaje z dwóch o jednym nowym kluczu

`V79__library_key_producer.sql:11-12, 39-43`. Zostaje mniejszy stary klucz („Noc - Prod. by A” przed „Noc - Prod by B”), a parser
przy ponownym wgraniu zostawia najkrótszy tytuł (`Track::shorter` w `DjLibraryParser.java:139`) — „Noc - Prod by B”. Różni się
tylko pokazywane `title_name` / tempo / tonacja do ponownego wgrania; tak samo robiło V60. Do przyjęcia; ewentualnie w SQL
`ORDER BY length(title_name), title_key` przy wyborze. Bez zmian, chyba że właściciel chce.

### P7 — dokumenty i opis PR

- Komentarz V79 `:7-9` („a name this cannot spell as the Java does … is left as it was, never given another song's key”) — nieprawda
  (P1); poprawić razem z P1.
- Opis PR odsyła do `docs/history/review-2026-11-22-p3-serato-play-count.md`, którego nie ma ani w `main`, ani na tej gałęzi (jest
  tylko na `claude/youthful-heisenberg-3whpcr`) — po scaleniu link w repo prowadzi donikąd, dopóki tamta gałąź nie wejdzie.
- Opis PR, „Before the release”: „the nightly copy of the database first”. `SESSION_HANDOFF.md` („The database's copies”) każe przed
  migracją zmieniającą istniejące wiersze zrobić kopię **ręcznie** (`docs/database-backup.md`) — V79 jest taką migracją. Pytanie 1.
- Wiersz V79 w `10-2-migrations.md` stoi po V78 — dobrze, tak jak V77, V78; sama tabela ma starszy bałagan (V69 → V77 → V78 → V79
  → V76 … V70 → V62 …), nie z tego PR.
- Komentarze w `LibraryKey.java:45-52` mówią „dlaczego” (Serato tak pisze; „prod” samo to nie wersja); „(V79 cut the keys saved
  before the same way)” to odsyłacz w stylu V60, do przyjęcia.

## Sprawdzone i w porządku

- **Wzorzec Javy:** `prod(?:uced\s+|\.\s*|\s+)by` + `\b` po „by”: „- Prod. by X”, „- Prod by X”, „- Prod.by X”, „- Produced by X”,
  „- PROD BY X” ucięte; „- Prodigy Tribute”, „- Production Line”, „- Producer”, „Prodigal Son” (bez myślnika), „- Prod. Bylina”
  (brak granicy po „by”), „- Prodby X” zostają. Tytuł z samym „Prod by Y” (bez myślnika) zostaje. Dwa „- Prod.” — ucięty tylko
  ostatni, tak jak dwie wersje („- Live”). Ryzyko odwrotne, przyjęte: podtytuł typu „- Produced by Love” zostanie ucięty.
- **Kolejność DELETE → UPDATE a klucz główny:** `UPDATE` nie może trafić na zajęty klucz — wszystko, co by kolidowało (wiersz w
  tabeli o nowym kluczu, drugi z tym samym nowym kluczem), jest wcześniej skasowane (P3 to nadgorliwość tego samego warunku, nie
  kolizja). Wykonawcy są w PK, więc „Toketeo” Serato (`bebeshito+djhonda+djjerry`) i „Toketeo” (`bebeshito`) oba zostają.
- **`ON COMMIT DROP` w Flyway:** Flyway uruchamia migrację PostgreSQL w jednej transakcji; tabela żyje do `COMMIT` po `UPDATE`
  (IT i pomiar przez Flyway to potwierdzają). `spring.flyway.*` nie wyłącza transakcji.
- **`title_name` NULL** (biblioteki sprzed V57) — `ILIKE` z NULL to NULL, wiersz pominięty; **wielkość liter** — `ILIKE` i flaga `i`;
  **myślniki typograficzne** — `translate` ma te same znaki co `SongNames.tidy` (U+2010–2015, U+2212); **klucz ucięty do 200** —
  strażnik końcówki go pomija (najwyżej nieruszony, nie zły). Wiersze `swapped` (M3U czytane odwrotnie): `title_name` to druga
  połowa nazwy bez „ - ”, więc nic do ucięcia.
- **Skutki w aplikacji:** kolejka („✓ Masz” / „? Podobny” / „✗ Nie masz”: `DjService.java:127`, `DjLibraryService.java:182`),
  podpowiedzi z biblioteki (`LibraryHintService.java:72`), podsumowanie wieczoru (`EveningSummaryService.java:509-568`), życzenia
  (`WishService`, P4) — wszystkie liczą klucz prośby tą samą `LibraryKey.of`, więc po migracji pasują do przeliczonych wierszy.
  „Zagrane samo” (`DeckHistoryService.java:60, 87`) nie używa bazy: działa od razu po wdrożeniu, także dla bibliotek sprzed V57.
  Pamięć podręczna `djLibrary` startuje pusta z nowym deployem. Otwarty panel trzyma do zmiany kolejki stare znaczki (ETag panelu to
  odcisk kolejki + `uploaded_at`, `DjDashboardController.java:666-680`) — jak po V60, do przyjęcia.
- Okno między `COMMIT` V79 a przełączeniem ruchu: stary deploy, który w tej chwili przyjmie wgranie biblioteki, zapisze stare klucze
  (z producentem) — do następnego wgrania. Sekundy; wdrożenia nie idą w czasie imprez (deploy window).

## Pytania do właściciela

1. Kopia bazy przed V79: ręczna (jak każe `SESSION_HANDOFF.md` dla migracji zmieniających wiersze), czy wystarczy nocna — V79
   kasuje wiersze bibliotek, a biblioteka wraca tylko przez ponowne wgranie przez DJ-a?
2. P4: przyjąć, że życzenie z „Prod. by” zebrane przed wdrożeniem i po nim to dwa wiersze (proponuję tak), czy przeliczać też
   `guest_wish`?
3. P1–P3 i testy z P5 — poprawiać na tej gałęzi (`claude/jovial-hodgkin-40e080`, wtedy #303/#304 dostaną nowy commit), czy na
   nowej? V79 nie jest jeszcze nigdzie zastosowana, więc można ją zmienić w miejscu (nie trzeba V80).
4. P6: zostawić „mniejszy stary klucz”, czy wybierać najkrótszy tytuł jak parser?
