# Przegląd 2026-11-27 — „Zagrane samo” pod obserwacją: ostrzeżenie i test przed imprezą

Zakres: gałąź `claude/funny-hawking-brlqnj` (od `main` = `2912c2d`), PR-y https://github.com/Adas553/scan2play/pull/315 (`main`) i
https://github.com/Adas553/scan2play/pull/316 (`dev`), `git diff origin/main...HEAD`: commit `2422084` (+ dwa commity handoffu),
36 plików, +1072 / −48, migracja V81 (tylko dodaje kolumny, indeks i CHECK; nie przenosi wierszy). `docs/ideas.md` punkt 21.

## Wynik w skrócie

Kod jest czysty i pasuje do reszty: zapytanie co minutę kosztuje ~1 ms, `notePlay` nigdy nie cofa czasu, endpoint `silence` ma
QUEUE + Pro + CSRF i listę dozwolonych wartości (i CHECK w bazie), teksty PL / EN są w tonie panelu, a escape'y w
`messages*.properties` są poprawne. W programie (`deck-agent`) czwarte pole `Played` niczego nie psuje, a „OSTATNIA PIOSENKA” jest
na wątku Swinga. Testy przechodzą (niżej).

**Słaba jest sama reguła „w trakcie imprezy”.** Fałszywe alarmy, których funkcja miała unikać, przychodzą w dwóch zwykłych
sytuacjach. Obie odtworzyłem testem na PostgreSQL:

- **P1 (błąd):** test przed imprezą, do którego zachęca sama karta („puść dowolny utwór na minutę”), sprawia, że później przychodzi
  push „Program DJ-a nie widzi piosenek”. Dzieje się to przy pierwszej prośbie gościa, zanim DJ cokolwiek zagrał.
- **P2 (ważne):** koniec seta przy otwartej imprezie. Push przychodzi 20 min po ostatniej piosence, jeśli goście prosili jeszcze pod
  koniec seta, a prawie zawsze proszą.
- **P3 (ważne):** zapasowy laptop, połączony i przetestowany, ale nieużywany, świeci na żółto przez całą imprezę, razem z
  przyciskiem nad kolejką. Push w tym czasie milczy, więc karta i push mówią co innego.
- **P4 (ważne):** push idzie tylko do organizatora. Powiadomienie o nowej prośbie idzie do organizatora i obsługi. DJ dodany jako
  obsługa ustawia „Ostrzeż **mnie**”, a ostrzeżenia nie dostaje.

Pozostałe punkty to drobiazgi i braki w testach (P5–P17).

**Ocena:** nie scalać przed poprawą P1–P4. P1 i P2 to jedna zmiana reguły (propozycja przy P1/P2). P3 i P4 to po kilka linii.

## Testy i sprawdzenia

- `tools/check.py unit`: **1568/1568**. `check.py it` (PostgreSQL 16 w kontenerze, baza `s2p_it_*`): **233/233**.
- `check.py browser`: **486/487**. Czerwony tylko `kiosk-full-fits-820x1180` („the page does not scroll”, „the list scrolls … to its
  last song”), ten sam co na `main` w kontenerze; karty nie dotyczy. `deck-computer-lines` i `deck-silence-choice` są zielone.
- `deck-agent` (`mvnw test` w kopii): **89/89**.
- `check.py audit -t dashboard`: 16 stron × 3 szerokości (320 / 390 / 1280), bez przewijania w bok i bez błędów konsoli. Cztery małe
  cele („Szczegóły”, „Details”, linki godzin) były już wcześniej. Karty „Z programu DJ-a” audyt nie widzi: poniżej 768 px nie ma
  przycisku (tak ma być), a powyżej karta jest zwinięta.
- Zrzuty rozwiniętej karty (Playwright, `server.py` z kopii z danymi: OK / WAITING / SILENT+warn) są w
  `docs/history/review-2026-11-27-shots/`: `karta-1280.png`, `karta-800.png`, `przycisk-800.png`. W 1280 px karta wygląda dobrze. W
  800 px rozjeżdża się wiersz WAITING (P14).
- EXPLAIN (PostgreSQL 16, baza `s2p_review`: schemat z migracji, 1000 imprez, 2500 komputerów, 300 000 próśb; po przeglądzie
  usunięta):
  - `silentNow` przy realnym rozkładzie (kilkanaście komputerów z piosenką w 12 h): `Index Scan using idx_dj_device_last_play` →
    hash join z `party_settings` (seq scan 1000 wierszy, 25 stron) → GroupAggregate. `EXISTS` w HAVING to
    `Index Only Scan using idx_party_decision_time` (party_code + requested_at w warunku indeksu). **1,2 ms**.
  - Gdy 37% komputerów ma piosenkę w 12 h, planista wybiera seq scan `dj_device` i jeden hashed SubPlan po
    `idx_song_requests_requested_at`. Wychodzi 1,8 ms, też dobrze.
  - `guestsRecently` w `GET /dj/devices`: `Index Only Scan using idx_party_decision_time`, 4 bufory, 0,04 ms.
- Mutacje (kopia `/tmp`, `-t DeckWatchServiceTest` / `-t DeckWatchIT`):

| # | mutacja | wynik |
|---|---|---|
| M1 | `watch()`: bez `party.isActive()` (karta świeci na zamkniętej imprezie) | **przeżyła** (P15) |
| M2 | `state()`: SILENT już przy dokładnie `minutes` (`!isAfter` zamiast `isBefore`) | **przeżyła** (P15) |
| M3 | `look()`: `offline` zawsze `false` (push zawsze „nie widzi”, nigdy „nie łączy się”) | **przeżyła** (P15) |
| M4 | `setSilenceMinutes`: bez `warned.invalidate` | **przeżyła** (P15) |
| M5 | `silentNow`: bez `AND p.active` | czerwony ✓ |
| M6 | `silentNow`: bez `EXISTS` prośby gościa | czerwony ✓ |
| M7 | `watch()`: bez `guestsRecently` | czerwony ✓ |

  M1–M4 sprawdziłem jednym przebiegiem (wszystkie cztery naraz, oba testy zielone). M5–M7 każdą osobno.
- Test „recenzencki” dopisany tylko w kopii (`DeckWatchIT.review_falseAlarms`), **zielony na obecnym kodzie**, czyli potwierdza błędy:
  impreza z piosenką sprzed 90 min i prośbą sprzed minuty jest w `silentNow` (P1); impreza z końcem seta 21 min temu i ostatnią
  prośbą 26 min temu też (P2); impreza z laptopem grającym 2 min temu i drugim sprzed 90 min nie dostaje pusha, ale ma w karcie
  `warn = true` (P3).

## Punkty

### P1 — test przed imprezą uruchamia alarm przed setem · błąd

- **Gdzie:** `src/main/java/com/scan2play/service/DeckWatchService.java:98` (`live` = piosenka w 12 h), `:121-123`
  (`setStarted`), `:161-164` (to samo w `silentNow`: `d.last_play_at > now − 12 h`).
- **Dlaczego:** „set się zaczął” oznacza teraz „jakakolwiek piosenka w ostatnich 12 h”. Karta sama prosi o taką piosenkę (WAITING:
  „puść dowolny utwór na minutę”, przewodnik `#deck`, okno programu). Wesele: DJ rozstawia się o 15:00, łączy program i puszcza
  utwór na próbę. Goście przy stołach skanują QR i o 16:30 przychodzi pierwsza prośba. Impreza jest otwarta, piosenka była w 12 h,
  prośba w 60 min, a ostatnia piosenka jest starsza niż 20 min. Wynik: push „⚠ Program DJ-a nie widzi piosenek — Od 20 min żadnej
  nowej piosenki” (naprawdę od 90), a karta i przycisk świecą na żółto, choć DJ jeszcze nie zaczął grać. Sprawdzone testem (wyżej).
- **Poprawka:** reguła musi odróżnić „muzyka grała i przestała” od „jedna piosenka na próbę”. Najprościej przechowywać na komputerze
  początek bieżącej serii piosenek, np. `dj_device.play_run_from` (V82 albo w V81, póki nie weszła na produkcję). W `notePlay` jest
  to jeden warunkowy `UPDATE`: gdy nowa piosenka przychodzi po przerwie dłuższej niż 30 min (albo wybrane minuty), `play_run_from =
  nowa`; inaczej bez zmian. Za „set się zaczął” uznawać `last_play_at − play_run_from ≥ 10 min`: kilka piosenek, nie jedna próba.
  Zamiast kolumny wystarczy też licznik piosenek w serii. Testy: IT z jedną piosenką sprzed 90 min i świeżą prośbą (brak), z serią
  15 min i ciszą 25 min (jest); scenariusz karty bez zmian.

### P2 — koniec seta przy otwartej imprezie: push 20 min po ostatniej piosence · ważne

- **Gdzie:** `DeckWatchService.java:125-128` (`guestsRecently`: prośba w ostatnich 60 min) i `:164` (to samo w `silentNow`).
- **Dlaczego:** okno gości (60 min) jest dłuższe niż próg ciszy (10 / 20 / 30 min). Set kończy się o 2:00, ostatnie prośby
  przychodzą o 1:50 (goście proszą do końca, część zostaje niezagrana), impreza zostaje otwarta (klub, pub, DJ się pakuje). O 2:20
  organizator dostaje push „nie widzi piosenek”, a gdy laptop jest już zamknięty, „Program DJ-a się nie łączy”. Komentarz klasy
  (`:26-27`) obiecuje, że „the end of the set at a party left open is not a failure”, ale to zachodzi dopiero 60 min po ostatniej
  prośbie, czyli długo po pushu. Sprawdzone testem (wyżej).
- **Poprawka:** przy prawdziwej awarii muzyka gra dalej (program jej nie widzi), więc goście dalej proszą. Po końcu seta przestają.
  Warunek „goście są” powinien więc dotyczyć próśb **po ostatniej piosence**: `r.requested_at > MAX(d.last_play_at) + 2 min` (w
  `silentNow`) i `requested_at > newest lastPlayAt` (w `watch()`; jedno zapytanie zamiast dwóch). Margines 2 min pomija prośbę
  wysłaną w ostatniej minucie seta. Jeśli impreza ma zaplanowane zamknięcie („Godziny imprezy”, V55), można też nie ostrzegać po nim.
  Razem z P1 daje to regułę: grała seria, ucichła, a od tego czasu goście dalej proszą. Testy: koniec seta z prośbami tylko przed
  nim (brak), cisza z prośbą po ostatniej piosence (jest).

### P3 — karta per komputer, push per impreza: zapasowy laptop świeci na żółto cały wieczór · ważne

- **Gdzie:** `DeckWatchService.java:100-103` (`warn = live && (SILENT || OFFLINE)` dla każdego komputera osobno) wobec `:158-163`
  (push: `MAX(last_play_at)` całej imprezy).
- **Dlaczego:** karta pozwala połączyć do 3 komputerów („Połącz kolejny komputer”), a DJ z zapasowym laptopem przetestuje oba (P1
  wręcz do tego zachęca). Wieczorem gra na głównym, zapasowy stoi albo jest zamknięty. Zapasowy dostaje SILENT (ostatnia piosenka
  15:00) albo OFFLINE, `live` jest prawdą dzięki głównemu, więc `warn` jest prawdą: wiersz jest żółty, a **przycisk nad kolejką żółty
  przez całą imprezę** (`play-history.js:169`). Push słusznie milczy, bo najnowsza piosenka imprezy jest świeża. DJ uczy się
  ignorować żółty, a przy prawdziwej awarii przycisk wygląda tak samo. Sprawdzone testem (wyżej). Odwrotnie: główny laptop śpi,
  zapasowy jest włączony. Push liczy `offline` z `MAX(last_seen)` wszystkich komputerów (`:158`, `:195`) i mówi „nie widzi piosenek”
  zamiast „się nie łączy”.
- **Poprawka:** jedna reguła dla karty i pusha. Impreza jest cicha, gdy najnowsza piosenka **wszystkich** komputerów jest starsza
  niż wybrane minuty. Żółty ma tylko komputer, który tę najnowszą piosenkę zagrał. Pozostałe dostają szary opis („ostatnia piosenka
  15:00”, „ostatnio 18:40”). OFFLINE głównego komputera (tego z najnowszą piosenką) może być żółty od razu po 3 min, bo wtedy karta
  wyprzedza push. W `silentNow` `offline` liczyć z `last_seen_at` komputera z najnowszą piosenką (`(ARRAY_AGG(COALESCE(last_seen_at,
  created_at) ORDER BY last_play_at DESC))[1]`). Testy: IT z dwoma komputerami (jeden gra, drugi stary): `warn` fałsz dla obu; jeden
  ucichł: `warn` tylko jego; push `offline` dla śpiącego głównego.

### P4 — push tylko do organizatora; obsługa (często sam DJ) go nie dostaje · ważne

- **Gdzie:** `DeckWatchService.java:196` (`push.notifyDeckSilent(party.ownerId(), …)`) wobec
  `SongEvaluationService.java:279-282` (nowa prośba: organizator **i** `partyStaffService.memberIds(partyCode)`). Teksty:
  `dashboard.computer.silence` („Ostrzeż **mnie**”), `dashboard.computer.silence.help` (`messages_pl.properties:569`: „na urządzenia
  z włączonymi powiadomieniami o prośbach”).
- **Dlaczego:** selecta używa każdy z uprawnieniem QUEUE (`DjDeviceController.java:50`), także obsługa. W klubie albo agencji
  organizatorem jest właściciel konta, a grający DJ to obsługa z włączonymi powiadomieniami o prośbach. Ustawia „Ostrzeż mnie po 10
  min” i nic nie dostaje. Dostaje za to właściciel, który zwykle nie stoi przy konsoli, i to o 2:20 (zwłaszcza z P2). Pomoc obiecuje
  „urządzenia z powiadomieniami o prośbach”, a te obejmują obsługę.
- **Poprawka:** wysyłać tak samo jak `notifyNewRequest`: do organizatora i do `partyStaffService.memberIds(partyCode)`. Najlepiej
  tylko do członków z QUEUE, czyli tych, którzy widzą kartę. Test w `DeckWatchIT` (albo jednostkowy z mockiem `PartyStaffService`):
  push idzie do właściciela i członka obsługi.

### P5 — treść pusha podaje wybrane minuty, nie faktyczną ciszę · drobne

- **Gdzie:** `DeckWatchService.java:196` (`party.minutes()`), `messages_pl.properties:424` („Od {0} min żadnej nowej piosenki”).
- **Dlaczego:** push przychodzi przy pierwszym przejściu przez próg, ale nie tylko wtedy. Po restarcie aplikacji (P8), po zmianie
  minut (P6), gdy impreza staje się „w trakcie” później (pierwsza prośba gościa po godzinie ciszy) albo przy P1 cisza trwa już 45 czy
  90 min, a push mówi „Od 20 min”.
- **Poprawka:** przekazać `Duration.between(lastPlayAt, now).toMinutes()` zamiast `party.minutes()`. Test: push z ciszą 47 min
  mówi „Od 47 min”.

### P6 — zmiana minut w dół wysyła drugi push o tej samej ciszy · drobne

- **Gdzie:** `DeckWatchService.java:143-144` (`warned.invalidate(partyCode)` przy każdej zmianie).
- **Dlaczego:** komentarz zakłada podniesienie progu (20 → 30: nowe ostrzeżenie przy 30 min, słusznie). Przy obniżeniu (20 → 10)
  albo przy „nigdy” → 20 trwająca cisza jest już ponad progiem, więc za minutę przychodzi drugi push o tym samym. DJ, który po pushu
  zmienia ustawienie, dostaje go jeszcze raz.
- **Poprawka:** unieważniać tylko przy podniesieniu progu (stara wartość z `silenceMinutes` przed `UPDATE`) albo trzymać w `warned`
  parę (ostatnia piosenka, minuty) i porównywać, czy cisza przekroczyła nowy próg, którego jeszcze nie zgłoszono. Test (M4 przeżyła):
  20 → 30 daje drugi push dopiero przy 30 min; 20 → 10 nie daje drugiego.

### P7 — `LIMIT 100 ORDER BY party_code`: przy ponad 100 cichych imprezach część nigdy nie dostanie pusha · drobne

- **Gdzie:** `DeckWatchService.java:165` i komentarz `:40` („the rest at the next minute”).
- **Dlaczego:** już ostrzeżone imprezy zostają w wyniku, dopóki trwa cisza i ktoś prosi (do 12 h). Ich miejsca w pierwszej setce
  zajmują stale te same kody, więc impreza o „późniejszym” kodzie nie wchodzi ani w tej minucie, ani w następnych. Przy dzisiejszej
  skali to teoria, ale komentarz obiecuje coś, czego kod nie robi.
- **Poprawka:** `ORDER BY MAX(d.last_play_at) DESC`, czyli najpierw najświeższe cisze, a te są jeszcze nieostrzeżone. Albo trzymać
  „ostrzeżono o” w bazie (P8) i odfiltrować w SQL. Poprawić komentarz.

### P8 — „raz na ciszę” tylko w pamięci: wdrożenie w sobotę wieczorem powtarza push · drobne

- **Gdzie:** `DeckWatchService.java:73-75` (Caffeine, 13 h, 10 000 wpisów; ograniczone, dobrze).
- **Dlaczego:** opisane świadomie. Każdy restart w trakcie imprez (deploy z `main`) wysyła drugi push do każdej nadal cichej
  imprezy. Gdyby kiedyś były dwie instancje, każda wyśle swój.
- **Poprawka (opcjonalna):** kolumna `party_settings.deck_warned_play_at` i warunkowy `UPDATE … SET deck_warned_play_at = ? WHERE
  party_code = ? AND deck_warned_play_at IS DISTINCT FROM ? RETURNING …`. Wtedy push idzie tylko, gdy `UPDATE` zmienił wiersz. Jeden
  zapis na ciszę, przeżywa restart i rozwiązuje P7 (filtr w SQL). Można zrobić razem z P1 (nowa kolumna i tak potrzebna).

### P9 — „nigdy” nie wyłącza żółtego w karcie, a przewodnik mówi, że wyłącza · drobne

- **Gdzie:** `DeckWatchService.java:103` (OFFLINE daje `warn` bez względu na minuty) i `guide_pl.html:270` („karta i przycisk zrobią
  się żółte … po 10, 20 albo 30 minutach … („nigdy” je wyłącza)”), EN tak samo.
- **Dlaczego:** przy „nigdy” zamknięty laptop w trakcie imprezy po 3 min i tak zabarwia wiersz i przycisk na żółto. Przy innych
  progach OFFLINE jest żółty po 3 min, a nie „po 10, 20, 30”. Przewodnik pomija też warunek „gdy goście wysyłają prośby”, który ma
  pomoc w karcie.
- **Poprawka:** zdecydować, co znaczy „nigdy”. Proponuję: ani pusha, ani żółtego, tylko szary opis (`warn` = fałsz przy 0). Zdanie w
  przewodniku: „Gdy w trakcie imprezy (goście wysyłają prośby) program przestanie widzieć piosenki albo się łączyć, wiersz i przycisk
  zrobią się żółte, a po 10, 20 albo 30 minutach — jak wybierzesz — przyjdzie powiadomienie („nigdy” wyłącza jedno i drugie)”.

### P10 — nieudany zapis „Ostrzeż po” wygląda jak udany · drobne

- **Gdzie:** `play-history.js:175-183` (`post(…)` bez sprawdzenia `response.ok`) i `:149` (select nie wraca do wartości serwera,
  póki ma fokus, a po wyborze myszą fokus zostaje na nim).
- **Dlaczego:** 403 (Pro się skończył, uprawnienia zmienione), 400 albo brak sieci: select pokazuje „po 10 min”, a w bazie jest 20.
  `load()` po `finally` nie poprawia wartości, bo `document.activeElement === silence`. DJ myśli, że ustawił 10.
- **Poprawka:** przy `!response.ok` albo wyjątku pokazać `deviceFailed` (jak „Odłącz”) i wymusić wartość z serwera (`load(true)`,
  które ignoruje fokus). Scenariusz: stand-in odpowiada 403 na `/dj/devices/silence`, select wraca do 20, widać komunikat.

### P11 — komentarz V81 nazywa klasę, której nie ma · drobne

- **Gdzie:** `src/main/resources/db/migration/V81__deck_silence.sql:2` („DeckSilenceMonitor”); klasa to `DeckWatchService`.
- **Dlaczego:** ktoś szukający kodu migracji nie znajdzie klasy. Migracja nie weszła jeszcze na produkcję. Lokalna baza właściciela
  może ją mieć, jeśli uruchamiał gałąź.
- **Poprawka:** poprawić komentarz, jeśli V81 nie została nigdzie zastosowana (zmiana zmienia sumę kontrolną Flyway). Inaczej
  zostawić.

### P12 — nowy indeks: w porządku, ale każdy `notePlay` jest teraz aktualizacją bez HOT · drobne (do decyzji)

- **Gdzie:** `V81__deck_silence.sql:16`, `DjDeviceService.java:171-176`.
- **Dlaczego:** EXPLAIN pokazuje, że indeks częściowy się przydaje (realny rozkład: index scan, 1,2 ms). Ale kolumna w indeksie
  sprawia, że każdy `UPDATE last_play_at` (jeden na wywołanie z nową piosenką, czyli co 3–4 min na komputer) zapisuje nowe wpisy we
  wszystkich czterech indeksach `dj_device`. `last_seen_at` co minutę zostaje HOT. Przy setkach komputerów to nic. Przy tysiącach
  `dj_device` dalej jest mała tabela (≤ 3 na imprezę), a seq scan kosztuje 0,3 ms.
- **Poprawka:** można zostawić. Gdyby `dj_device` urosła albo `UPDATE` był częstszy, rozważyć usunięcie indeksu. Uwaga
  informacyjna, nie warunek scalenia.

### P13 — lista komputerów odpytywana co minutę także bez żadnego komputera · drobne

- **Gdzie:** `play-history.js:243-245`.
- **Dlaczego:** każdy organizator Pro z panelem otwartym na laptopie (≥ 768 px) wysyła co minutę `GET /dj/devices` (sesja, plan,
  trzy zapytania), choć nie ma programu i nie będzie go miał. Koszt na żądanie jest mały (≤ 1 ms w bazie), ale to zbędny ruch przez
  całą imprezę. Parowanie nowego komputera i tak łapie osobne `watching` (`:220`) oraz `visibilitychange`.
- **Poprawka:** w interwale `if (document.visibilityState === 'visible' && count > 0) loadComputers();`. Przy okazji można połączyć
  `silenceMinutes` z zapytaniem o urządzenia (`LEFT JOIN party_settings` albo podzapytanie w tym samym `SELECT`): o jedno zapytanie
  mniej na odświeżenie.

### P14 — wiersz WAITING w 800 px rozbija się na trzy linie, „Odłącz” zostaje sam · drobne

- **Gdzie:** `messages_pl.properties:562` (`dashboard.computer.waiting`, 104 znaki) i układ wiersza `play-history.js` (`d-flex
  flex-wrap`, słowa jako zwykły `span`); zrzut `review-2026-11-27-shots/karta-800.png`.
- **Dlaczego:** w 1280 px wiersz mieści się w jednej linii. W 800 px (mały laptop, tablet poziomo) opis spada do osobnych dwóch
  linii pod nazwą, a „Odłącz” trafia do czwartej. Pozostałe wiersze się nie łamią.
- **Poprawka:** krótszy tekst: „czeka na pierwszą piosenkę — puść dowolny utwór na minutę”. Reszta („żeby sprawdzić…”) jest w
  przewodniku i w oknie programu. Albo słowa w `span` z `flex-grow-1` i `min-width: 0`, żeby łamały się w swoim miejscu.

### P15 — braki w testach · ważne

- **Gdzie:** `DeckWatchServiceTest.java`, `DeckWatchIT.java`, `PlanFeatureEndpointsTest.java`, `scenarios/play-history.js`.
- **Dlaczego:** cztery mutacje przeżyły (M1–M4 wyżej). Brakuje też:
  - zamkniętej imprezy w **karcie** (M1; zamknięta jest testowana tylko w `silentNow`);
  - krawędzi: dokładnie 20 min (M2), dokładnie 3 min OFFLINE, dokładnie 12 h i 60 min (testy są przy 19 / 21 i 4 min);
  - `offline` w pushu: wszędzie `anyBoolean()` (M3);
  - ponownego alarmu po zmianie minut (M4) i braku drugiego przy obniżeniu (P6);
  - `POST /dj/devices/silence` z `minutes=15` → 400 i `minutes=abc` → 400 przez prawdziwy kontroler (dziś tylko mock zwraca `true`
    dla 10);
  - w przeglądarce: odświeżania co minutę (zegar strony) i odrzucania starszej odpowiedzi (`asked`), czyli dwóch kluczowych zachowań
    nowego JS;
  - `DeviceApiController.newest` z piosenką „z przyszłości” i bez czasu (dziś tylko zwykłe i wczorajsze).
- **Poprawka:** dopisać powyższe, a przy P1–P3 testy z ich opisów. Każdy test zobaczyć najpierw czerwony (np. M1–M4 z tej tabeli).

### P16 — przycisk żółty tylko kolorem · drobne

- **Gdzie:** `play-history.js:169` (`btn-outline-warning` zamiast `btn-outline-success`).
- **Dlaczego:** stan widać tylko po kolorze. Czytnik ekranu czyta „Z programu DJ-a” bez zmian, a przy słabym rozróżnianiu
  żółtego i zielonego (deuteranopia) przyciski wyglądają podobnie. W wierszach jest dobrze: słowa niosą stan, a znak ma
  `aria-hidden`. `aria-live` nie jest potrzebne: odświeżanie co minutę czytane na głos byłoby uciążliwe.
- **Poprawka:** przy żółtym ustawić `title` / `aria-label` przycisku na „Z programu DJ-a — program nie widzi piosenek” (nowy klucz
  PL / EN), albo dodać „⚠” przed etykietą.

### P17 — otwarty panel ze starym skryptem w czasie wdrożenia gubi listę komputerów · drobne (bez poprawki)

- **Gdzie:** stary `play-history.js` (`main`) i nowa odpowiedź `GET /dj/devices` (obiekt zamiast tablicy).
- **Dlaczego:** panel otwarty przed wdrożeniem po powrocie na kartę (`visibilitychange`) dostaje `{computers, …}`. Stary kod robi
  `list.replaceChildren()` i potem `devices.forEach` → TypeError, więc lista znika do przeładowania strony. Dzieje się to raz, przy
  wdrożeniu.
- **Poprawka:** nie trzeba. Warto tylko wiedzieć, gdyby DJ zgłosił „zniknął mi komputer” w noc wdrożenia.

## Co jest dobrze (sprawdzone, bez punktu)

- **Granice czasu są spójne między kartą a pushem:** `isBefore` / `isAfter` w Javie odpowiadają `<` / `>` w SQL (cisza
  „starsza niż”, piosenka i prośba „nowsze niż”). `?::timestamptz - p.deck_silence_minutes * interval '1 minute'` działa (kolumna
  jest w GROUP BY). `EXISTS` w HAVING korzysta z `idx_party_decision_time`.
- **`notePlay`:** warunkowy `UPDATE` nie cofa czasu (`DeckWatchIT`). Program ponownie wysłany po restarcie (≤ 10 min wstecz,
  `EARLIER_SET`) niczego nie psuje. Piosenka bez czasu albo „z przyszłości” dostaje `now`. Starsza niż 12 h nie jest zapisywana. Nazwa
  programu przechodzi przez `cleanName` i jest przycinana do 30 znaków. Indeksy `programOfNewest` zgadzają się z `songs`, bo
  `songsOf` mapuje 1:1.
- **Kolejność kontroli Pro:** w `/api/device/plays` plan jest sprawdzany przed `notePlay` (bez Pro nic nie jest zapisywane). W
  `look()` `warned.put` stoi przed kontrolą planu, więc impreza bez Pro nie jest sprawdzana co minutę od nowa.
- **Bezpieczeństwo `POST /dj/devices/silence`:** QUEUE + Pro (`PlanFeatureEndpointsTest`), CSRF (scenariusz sprawdza token), tylko
  0 / 10 / 20 / 30, a do tego `CHECK 0–120` w bazie. `int` spoza liczb daje 400 Springa.
- **`deck-agent`:** `Played` z czwartym polem: `equals` porównuje tylko `NowPlaying` (oba bez programu), `seen` używa `identity()`
  (bez programu). `heard` jest zmieniane tylko w `synchronized tick()`, a okno przyjmuje niezmienny rekord w `invokeLater`. Sekcja
  „OSTATNIA PIOSENKA” ma ten sam krój co „CZYTA” i „OZNACZONE” (wersaliki, `MUTED`, pogrubienie, godzina w kolumnie
  `readingWidth`) i stoi między nimi, czyli tam, gdzie powinna.
- **Teksty:** formy bezosobowe jak w reszcie panelu („widzi piosenki”, „brak nowych piosenek od 21:43”), PL i EN mówią to samo,
  `messages*.properties` bez znaków spoza ASCII. Push: tytuł z „⚠” jak alarm AI, własny tag `s2p-deck-<impreza>` (nie zastępuje
  powiadomień o prośbach), link do panelu tej imprezy.
- **Wygląd:** `card bg-dark border-secondary`, select jak trzy inne w panelu (`form-select form-select-sm bg-black text-light
  border-secondary`), kolory z tokenów (`--s2p-green`, `--s2p-text-muted`, `--s2p-warning`), etykieta `for="deviceSilence"`.
- **Polityka prywatności PL / EN** mówi prawdę (godzina ostatniej piosenki i nazwa programu, bez wykonawcy i tytułu). `docs/context`
  (`05-1d`, `10-2`) zgadza się z kodem, z wyjątkiem P9 w przewodniku.
- **Komentarze** mówią „dlaczego”. Odwołania do „V81” i „docs/ideas.md item 21” są zgodne z konwencją projektu.
- **Koszt w przeglądarce i w programie:** interwał tylko przy widocznej stronie, poniżej 768 px nic (P13 to zbędne żądania, gdy nie ma
  żadnego komputera). W programie nowy rekord na każdą świeżą piosenkę: nic.

## Fałszywe alarmy — przegląd sytuacji z imprezy

| sytuacja | co się dzieje dziś (próg 20 min) | ocena |
|---|---|---|
| test przed imprezą, potem prośby przed setem | push i żółty przy pierwszej prośbie | **fałszywy** (P1) |
| pierwszy taniec, przemowy, tort (< 20 min) | nic | dobrze |
| przemowy / oczepiny > 20 min bez muzyki, goście proszą | push | prawdziwy w sensie reguły, uciążliwy; DJ ma 30 / „nigdy” |
| przerwa obiadowa z muzyką z VirtualDJ (automix) | nic: automix też trafia do historii | dobrze |
| przerwa obiadowa bez muzyki (cisza > 20 min) | push, gdy goście proszą | jak wyżej |
| Spotify na pauzie | push po progu | słusznie (nic nie gra) |
| laptop uśpiony w trakcie | żółty po 3 min, push „nie łączy się” po progu | słusznie |
| koniec seta, impreza otwarta | push 20 min po końcu | **fałszywy** (P2) |
| zapasowy laptop połączony | żółty przycisk cały wieczór | **fałszywy** (P3) |
| impreza otwarta na drugi dzień | nic: prośby ustają po 60 min, a piosenka sprzed 12 h się nie liczy | dobrze |

## Co poprawić przed scaleniem

1. P1 + P2: reguła „grała seria, ucichła, goście dalej proszą” (kolumna serii albo licznik, prośba po ostatniej piosence). Do tego
   testy.
2. P3: ta sama reguła ciszy dla karty i pusha; żółty tylko komputer z najnowszą piosenką; `offline` pusha z tego komputera.
3. P4: push do organizatora i obsługi z QUEUE.
4. P15: testy dla M1–M4 i powyższych, 400 kontrolera, odświeżanie co minutę w przeglądarce.

Potem, w tym samym PR albo zaraz po: P5, P6, P9 (decyzja o „nigdy” i zdanie w przewodniku), P10, P13, P14. P7, P8, P11, P12, P16
i P17 według uznania.

## Poprawki (2026-11-27, ta sama gałąź)

| punkt | stan | co zrobione |
|---|---|---|
| P1 | poprawione | V82 `dj_device.play_run_from`: początek serii piosenek. `DjDeviceService.notePlay(id, oldest, newest, program)` zaczyna nową serię po 30 min bez piosenki (`RUN_GAP`), a `DeviceApiController.span` podaje najstarszą i najnowszą piosenkę wywołania. Set to seria ≥ 10 min (`RUN_MIN`). Piosenka na próbę nie daje ani pusha, ani żółtego. |
| P2 | poprawione | Prośba gościa musi przyjść **po** ostatniej piosence (> jej start + 2 min, w ostatnich 60 min): w karcie (`guestsAfter`) i w `silentNow` (`GREATEST(…)`). |
| P3 | poprawione | Jedna reguła: komputer „prowadzący” (najnowsza piosenka z 12 h, `lead`). Żółty jest tylko on, pozostałe są szare. W `silentNow` CTE `DISTINCT ON (party_code)` bierze dane prowadzącego, także `offline` pusha z jego ostatniego wywołania. EXPLAIN: 1,3 ms, `idx_dj_device_last_play`, `idx_party_decision_time`, unikalny indeks `party_settings`. |
| P4 | poprawione | Push do organizatora i obsługi z QUEUE (`recipients`). |
| P5 | poprawione | Push podaje faktyczną ciszę (`Duration.between(lastPlayAt, now)`). |
| P6 | poprawione | `warned` unieważniane tylko przy podniesieniu progu z wartości > 0. |
| P7 | poprawione | `ORDER BY l.last_play_at DESC`, czyli najpierw najświeższe cisze; komentarz poprawiony. |
| P8 | zostawione | Pamięć w Caffeine, jak było (restart może powtórzyć push). |
| P9 | poprawione | „Nigdy” = ani push, ani żółty. Przewodnik PL / EN opisuje regułę (próba i koniec seta bez ostrzeżenia, OFFLINE po 3 min, obsługa). |
| P10 | poprawione | Odmowa zapisu jest pokazana pod selectem (`#deviceSilenceFailed`, `role="status"`), a select wraca do wartości serwera (`load(true)`). |
| P11 | zostawione | Komentarz V81. Nie wiadomo, czy V81 jest już w czyjejś bazie, a zmiana zmienia sumę kontrolną. |
| P12 | zostawione | Indeks zostaje (EXPLAIN potwierdza, że się przydaje). |
| P13 | częściowo | Odświeżanie co minutę tylko przy połączonym komputerze. Zapytania o minuty nie połączyłem z listą urządzeń. |
| P14 | poprawione | Krótszy tekst WAITING (PL / EN). W 800 px dwie linie zamiast czterech, „Odłącz” obok tekstu (`karta-800-po-poprawkach.png`). |
| P15 | poprawione | Testy niżej. |
| P16 | poprawione | Żółty przycisk ma ostrzeżenie w `aria-label` i `title` (`dashboard.deck.toggle.warn`). |
| P17 | zostawione | Bez poprawki, jak w przeglądzie. |

Testy po poprawkach:
- unit **1573/1573**: `DeckWatchServiceTest` (krawędzie 20 min / 3 min / 12 h, prowadzący, seria) i `PlanFeatureEndpointsTest` (`/dj/devices/silence`: 204, 400 dla 15, `abc` i braku);
- IT **237/237**: `DeckWatchIT` 10 testów: próba, koniec seta, zapasowy laptop, obsługa, faktyczna cisza, offline, zmiana minut, zamknięta impreza i „nigdy” w karcie;
- przeglądarka, scenariusze karty **9/9**, w tym nowe `deck-silence-not-saved` i `deck-older-answer-dropped` oraz opis żółtego przycisku w `deck-computer-lines`. Każdy nowy scenariusz widziałem najpierw czerwony: stary `play-history.js`, potem bez `if (mine !== asked)`.

Mutacje nowej reguły (każda osobno) są wszystkie czerwone:
- dawne M1–M4 (bez `isActive`, krawędź 20 min, `offline` zawsze fałsz, unieważnianie zawsze);
- karta bez testu serii;
- SQL bez serii;
- SQL bez „prośba po piosence”;
- żółty na każdym komputerze;
- obsługa bez pusha;
- „nigdy” z żółtym.
