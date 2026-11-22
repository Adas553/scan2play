# Review 2026-11-22 — P3: Serato 5.x po liczniku biblioteki (`asset.dj_play_count`)

Zakres: `git diff 29b5c91..fbc4d9c` (`b939477` poprawka P3, `57f6ffb` tekst „Oznaczone” i dokumenty, `fbc4d9c` handoff) —
głównie `deck-agent/.../Serato4Database.java`, `Window.java`, `HistoryFormatsTest`, `WindowTest`, `05-1d-panel-heading-queue.md`,
`review-2026-11-22-zagrane-serato-window.md`. Poprawki na gałęzi `claude/youthful-heisenberg-3whpcr` (z
`claude/gallant-ritchie-rl80ux`), bez PR. Bazy Serato nie czytałem (w kontenerze jej nie ma) — tylko kod i tabele z testu, odtworzone
z `PRAGMA table_info` właściciela.

## Werdykt

**Scalić po poprawkach** (są na tej gałęzi: wystarczy ją scalić do #301 / #302). Pomysł P3 jest dobry — licznik biblioteki to jedyna
rzecz, która w 5.1 odróżnia zagrane od załadowanego — ale wzrost był dawany **najwcześniejszemu** wierszowi piosenki i liczony od nowa
przy każdym odczycie: piosenka przesłuchana przed prośbą gościa, a zagrana po niej, szła z czasem przesłuchania i prośba zostawała w
kolejce (P1); po 2000 wierszach w sesji wzrost wędrował na późniejsze, tylko załadowane wczytanie (fałszywe trafienie, P2). Prywatność
i tryb tylko do odczytu w porządku.

## Ustalenia (od najważniejszych)

### P1 — błąd (poprawione): wzrost licznika dany najwcześniejszemu wierszowi — czas przesłuchania, prośba nie oznaczona

`Serato4Database.java` (przed: `played(...)`, „each rise given to one row, the earliest first”). Scenariusz: 20:00 DJ ładuje „Szampan” na
deck 2 i słucha na słuchawkach (wiersz, licznik bez zmian); 20:10 gość prosi o „Szampan”, prośba przyjęta; 20:30 DJ ładuje go znowu i
puszcza → licznik +1 → wzrost trafia do wiersza z 20:00 → program wysyła `Played(20:00, …)` → serwer (`DeckHistoryService.markPlayed`:
piosenka nie wcześniej niż prośba − 2 min) uznaje ją za zagraną przed prośbą — **prośba zostaje w kolejce**. Przy Serato 5.1, gdzie
każde załadowanie to wiersz, a przesłuchanie przed puszczeniem to codzienność DJ-a, to częste. **Poprawka:** wzrost idzie do
**najnowszego** wiersza piosenki, który jeszcze nie dostał wzrostu (licznik rośnie ~3 s po Play, program czyta co 3 s — to wczytanie
właśnie puszczone). Test `serato5_aPlay_isTheNewestLoadOfTheSong_notAnEarlierPreview` (czerwony przed: czas `NIGHT` zamiast `NIGHT + 600`).

### P2 — błąd (poprawione): przypisanie liczone od nowa co odczyt — wzrost wędruje na późniejsze wczytanie (fałszywe trafienie)

Przed poprawką `playsGiven` żyło tylko w jednym odczycie, a odczyt bierze najnowsze `MAX_SONGS` = 2000 wierszy. Gdy wiersz zagrany
wypada z tych 2000, jego wzrost dostaje następny wiersz tej piosenki — także tylko załadowany — z nowym czasem, więc `Agent` (klucz
`czas|wykonawca|tytuł`) wysyła go jako nową piosenkę i oznacza nową prośbę o nią. 2000 wierszy to dużo, ale w 5.1 liczy się każde
załadowanie (i każde przesłuchanie), a sesja Serato może trwać kilka imprez, jeśli DJ go nie zamyka. **Poprawka:** `PlayCounts` pamięta
dla sesji wiersze zagrane (`history_entry.id`) i ile wzrostów każdej piosenki już rozdano; przypisanie się nie zmienia między
odczytami. Test `serato5_aPlayOutOfTheLastSongsRead_isNotGivenToALaterLoad` (czerwony przed).

### P3 — ryzyko (poprawione): licznik malejący (DJ kasuje licznik w Serato) — dalsze puszczenia pomijane

Przed: `count − atStart` ujemne aż licznik przegoni stary stan — „Szampan” z licznikiem 4 skasowany do 0 i puszczony: 1 − 4 < 0, nic.
**Poprawka:** gdy licznik spadł poniżej rozdanych wzrostów, stan początkowy schodzi do niego (spadek widać przy załadowaniu, przed
Play). Pominięcie, nie fałszywe trafienie — dlatego ryzyko. Test `serato5_aCountReset_theNextPlayStillGoes` (czerwony przed). Nie
wiem, czy Serato w ogóle pozwala skasować `dj_play_count` (pytanie 2).

### P4 — drobiazg (poprawione): wiersz bez piosenki w `asset`

`LEFT JOIN` bez dopasowania (`asset_id` = −1 — domyślne w tabeli 5.1 — albo piosenka usunięta) dawał licznik 0 przez `getLong`; po
P3 to by „skasowało” licznik. Teraz `null` = nieznany, wiersz pominięty. Bez osobnego testu (zabezpiecza P3; zachowanie przed i po dla
takiego wiersza to samo: nie idzie).

### P5 — dokumenty (poprawione): „10 minut przed startem” nie dotyczy Serato 5

`Agent` wysyła piosenki z ostatnich 10 min przed startem programu (`EARLIER_SET`) — dla Serato 5 ich nie ma: stan liczników brany przy
pierwszym odczycie sesji, wcześniejsze puszczenia nie są wzrostem. Dokument mówił tylko o „kilku sekundach”. DJ, który włączy program
w połowie seta, nie oznaczy piosenek już zagranych (pominięcie). `05-1d-panel-heading-queue.md` dopisany (też P1–P3); pytanie 1.

### P6 — drobiazg (poprawione): osierocony javadoc w `Window.java:89`

Komentarz o polskich tekstach okien Javy stał nad `NOTHING_MARKED` (dwa javadoc pod rząd), a dotyczy `polishDialogs()` —
przeniesiony. Sprzed tego zakresu, poprawione przy okazji.

### P7 — drobiazg (poprawione po pytaniu 3): trzy zapytania bez jednej migawki

`read` robi `newestSession` → (raz na sesję) liczniki → zapytanie główne z podzapytaniem o sesję, każde w osobnej migawce WAL. Jeśli
Serato założy nową sesję dokładnie między nimi, zapytanie główne czyta wiersze nowej sesji z licznikami starej: piosenka zagrana w
starej sesji i tylko załadowana w nowej przejdzie (okno milisekund przy starcie Serato). **Propozycja:** `connection.setAutoCommit(false)`
(jedna transakcja odczytu = jedna migawka) albo sesja z `newestSession` jako parametr zapytania. Bez testu (wyścig) — dlatego pytam.
**Zrobione** (właściciel: „zrób”): `setAutoCommit(false)` przed pierwszym zapytaniem, zamknięcie połączenia ją wycofuje. Bez testu w
programie (wyścigu nie da się pewnie ustawić w `read`); sprawdzone osobnym programem na `sqlite-jdbc` z projektu: połączenie tylko do
odczytu, baza WAL, zapis drugiego połączenia między dwoma `SELECT` — bez transakcji drugi widzi nowy wiersz (1 → 2), z nią nie (1 → 1).

### P8 — drobiazg (otwarte, pytanie 4): restart Serato bez nowej sesji

Stan liczników jest per (baza, sesja). Próba właściciela: restart Serato = nowa sesja i nowe `asset.id`. Gdyby Serato po restarcie
dopisywało do starej sesji, nowe `asset.id` trafiałyby na stan starych numerów (inne piosenki) → możliwe fałszywe trafienie. Nie
widziane; jeśli zdarzy się „ZAGRANE” bez Play po restarcie Serato — to tu. Odcisk biblioteki (`max(id)`) w kluczu by to zamknął, kosztem
utraty stanu przy dodaniu piosenki w trakcie seta — nie robię bez potrzeby.

## Sprawdzone i w porządku

- **Tylko do odczytu:** `SQLiteConfig.setReadOnly(true)`, `busyTimeout 3000`; żadnego zapisu, żadnego `PRAGMA` zmieniającego.
- **Nic z bazy w SQL:** nazwy kolumn z `PRAGMA table_info` tylko porównywane (`contains`), zapytania sklejane wyłącznie ze stałych;
  identyfikator sesji nie wraca do SQL (podzapytanie). Nazwy tabel stałe.
- **Do serwera:** `Played(at, artist, title)` — bez `asset_id`, `history_entry.id`, ścieżek (`file_name`, `portable_id` nieczytane).
- **Serato 4 / mniej kolumn:** bez `asset_id` lub `asset.dj_play_count` — `byPlayCount` fałsz, zapytanie i filtr `played = 1` jak
  przedtem (testy `serato4_thePlayedSongsOfTheOpenSession`, `serato_withFewerColumns_…` bez zmian, zielone).
- **Nowa sesja / restart Serato:** nowa sesja → stan od nowa (stare wiersze z `asset_id` NULL pomijane) — test z restartem zielony.
- **Dwie bazy (Local / Roaming):** inna ścieżka → stan od nowa; powrót do pierwszej → też od nowa (puszczenia w międzyczasie pominięte,
  bez fałszywych trafień); rozdane wcześniej wiersze znikają z `songs()`, `Agent` już je ma w `seen`.
- **Restart programu:** nowy `Serato4Database`, stan od zera — nic drugi raz (`seen` + brak wzrostu), jak w próbie właściciela.
- **Ta sama piosenka kilka razy:** dwa Play = dwa wiersze; trzy załadowania i jedno Play = jeden (test właściciela + P1).
- **Współbieżność:** `songs()` wołane tylko z `Agent.tick()` (`synchronized`); stan `PlayCounts` nie wychodzi poza instancję.
  Dwie instancje na tę samą bazę (zwykła i „Inny folder historii…”) liczą osobno — te same wiersze, `seen` w `Agent` je łączy.
- **Wydajność:** zapytanie główne co zmianę `-wal` (≤ co 3 s): `history_entry` po PK od końca, `LIMIT 2000`, `LEFT JOIN asset` po
  `asset.id` — w tabeli właściciela `INTEGER PRIMARY KEY` (rowid), więc wyszukanie po kluczu, nie skan. Stan liczników raz na sesję:
  skan `asset` (≤ ~50 tys. wierszy, tylko `> 0` do mapy) — dziesiątki ms raz na wieczór, mapa do kilku MB. Rozsądne, nie zmieniam.
- **Tekst „Oznaczone”** (`Window.NOTHING_MARKED`) mówi prawdę dla Serato 5 i VirtualDJ; `WindowTest` sprawdza obie nazwy.
- **Komentarze:** mówią „dlaczego” (5.1 pisze wiersz przy załadowaniu), bez historii w kodzie; nazwy jasne.
- **Testy:** program `verify` 52/52 (z 3 nowymi), w kopii repo.

## Pytania do właściciela

1. (P5 — właściciel: „jak uważasz”; zostaje tylko w dokumencie: dotyczy tylko piosenek zagranych, zanim DJ włączył program — ich
   prośby zostają w kolejce do ręcznego „Zagrane”, nic nie oznacza się fałszywie; trzecia linia „Oznaczone” poszerzałaby okno.)
2. (P3) Czy Serato DJ Lite pozwala skasować licznik odtworzeń piosenki? Jeśli nie — P3 jest tylko zabezpieczeniem.
3. (P7 — zrobione.)
4. (P8) Przy następnej próbie: zamknąć i otworzyć Serato, załadować piosenkę bez Play i sprawdzić, czy `history_session` ma nowy wiersz
   (kopia bazy, tylko `SELECT id, start_time FROM history_session ORDER BY id DESC LIMIT 2`). Przy okazji: `PRAGMA table_info(asset)` —
   czy `id` ma `pk = 1`.
5. Próba P1 na Windows: załadować piosenkę z prośby bez Play, odłożyć, po minucie załadować i puścić — prośba ma się oznaczyć.
6. Serato 4: jeśli jego baza też ma `history_entry.asset_id` i `asset.dj_play_count`, liczy się już po liczniku (oprócz `played = 1`).
   Wiadomo, jak Serato 4 podnosi licznik? Bez komputera z Serato 4 nie sprawdzone.
