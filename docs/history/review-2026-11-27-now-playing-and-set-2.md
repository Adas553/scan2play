# Przegląd 2026-11-28 (drugi, niezależny) — „Teraz leci” na TV i kiosku, set wieczoru zapisany (V83, V84)

Zakres: gałąź `claude/optimistic-hopper-20ct09` (`921d641`), `git diff 693813f...origin/claude/optimistic-hopper-20ct09` —
PR-y #319 (`main`) i #320 (`dev`). Kod czytany na kopii gałęzi (`git archive`), testy w kopiach (`tools/check.py`), nic w gałęzi nie
zmienione. Pierwszy przegląd (`review-2026-11-27-now-playing-and-set.md`) przeczytany; jego wnioski sprawdzane, nie przepisywane.

## Wynik w skrócie

Kod jest staranny i w większości poprawny: V83 i V84 tylko dodają, zapis setu jest ograniczony (≤ 20 na wywołanie, 600 dziennie na
komputer, jedno `INSERT … unnest` pod blokadą imprezy), wyłączenie zapisu jest naprawdę szczelne (blokada przed `UPDATE`/`DELETE`,
`EXISTS … deck_set_kept` w samym `INSERT`), TV i kiosk nie dokładają zapytań, XSS i CSP w porządku (wszystko przez `th:text`,
`textContent`, brak skryptów inline), dedykacja nigdy na TV, set poza „Trendami”. Polityka prywatności PL/EN zgodna z kodem.

Do poprawy przed albo zaraz po wydaniu:

1. **P1 (ważne, wydajność)** — „Już leciało” przy każdej prośbie gościa: dla imprezy z dużą liczbą próśb planista liczy
   `min(requested_at)` po **globalnym** indeksie `idx_song_requests_requested_at` — 59 ms i 81 000 wierszy odrzuconych na jedną prośbę
   (zmierzone), zamiast 0,1 ms. Dotyka najbardziej aktywnych DJ-ów.
2. **P2 (ważne, dane i UX)** — jedno stuknięcie „Zapisuj set wieczoru” (także przez obsługę z QUEUE) bez potwierdzenia usuwa sety
   **wszystkich wieczorów z 30 dni** (także „Cały set” w podsumowaniach poprzednich imprez); opis mówi tylko „zapisany set — usuwany”.
3. **P3 (ważne, testy)** — z 21 moich mutacji **14 przeżyło**: m.in. brak blokady w `keep` i w `setKept` (czyli poprawka P1 pierwszego
   przeglądu nie ma testu), „Co to było?” bez Pro, 404 dla nieznanej imprezy, oznaczanie 🙋 w „Całym secie”, impreza demo.

Reszta to drobne (P4–P15): przesunięcie czasu w nocy zmiany czasu, gdy JVM nie jest w UTC; dwa „osierocone” javadoci; „Wyczyść
historię” a przewodnik; pasek TV ucina długi tytuł do samego wykonawcy; tekst karty; luki w przewodniku, `/pro` i „Nowościach”.

## Testy i sprawdzenia

- `tools/check.py all` (kopia gałęzi; `TMPDIR=/tmp/s2p`, Chrome z `/opt/pw-browsers`, PostgreSQL 16 kontenera po
  `ALTER USER postgres PASSWORD '1111'`): **unit 1594/1594**, **it 258/258**, **browser 501/502** — czerwony tylko znany
  `kiosk-full-fits-820x1180` (strona przewija się, lista nie dochodzi do ostatniej piosenki; był czerwony przed tą pracą).
- `check.py audit -t tv,kiosk,guest,summary,dashboard`: 67 stron × 3 szerokości, bez przewijania w bok i bez błędów konsoli; 4 małe
  cele — te same co przed tą pracą („Szczegóły”/„Details” 36 px, dwa linki godzin 22 px), żaden z tej zmiany.
- **EXPLAIN (ANALYZE, BUFFERS)** na throw-away bazie `s2p_review_explain` (PostgreSQL 16; 3000 imprez, 2,7 mln próśb z 30 dni, z tego
  90 300 z ostatnich 12 h; 1,8 mln piosenek setu; jedna „gorąca” impreza z 9000 próśb i 300 piosenkami dziś), po teście baza usunięta:

  | zapytanie | plan | czas |
  |---|---|---|
  | „Już leciało”, zwykła impreza | `idx_party_decision_time` + `idx_deck_play_party_started` | 0,9 ms |
  | „Już leciało”, gorąca impreza, jej pierwsza prośba dziś 20 min temu | **`idx_song_requests_requested_at`** — wszystkie prośby wszystkich imprez z 12 h, 80 973 odrzucone, 81 000 bufory | **59 ms** |
  | to samo z indeksem `(party_code, requested_at) WHERE NOT added_by_dj` | Index Only Scan | 0,08 ms |
  | `NOT EXISTS` przy zapisie (±2 min) | `idx_deck_play_party_started` | 0,03 ms |
  | wieczór do podsumowania (≤ 1000) | Index Scan Backward | 0,13 ms |
  | sprzątanie, nic starego | `idx_deck_play_started` | 0,08 ms |
  | sprzątanie, 1/30 tabeli stara | Seq Scan z LIMIT (znajduje 1000 od razu) | 10 ms |
  | wyłączenie zapisu (900 piosenek imprezy) | `idx_deck_play_party_started` | 0,5 ms |

- **Kodowanie tablicy `timestamptz[]`** sprawdzone osobnym programem JDBC (pgjdbc 42.7.14, ten sam co w aplikacji): z JVM w UTC
  wszystko dokładnie; z JVM w `Europe/Warsaw` `2026-10-25T00:30Z` zapisuje się jako `01:30Z` (P4).
- **Zrzuty** (Playwright na atrapie serwera z `src/test/browser/server.py` i stronach wyrenderowanych przez testy tej gałęzi,
  `deviceScaleFactor: 2`, każdy bez przewijania w bok i bez błędów konsoli) w `docs/history/review-2026-11-27-shots-now-playing-2/`:
  `karta-800.png`, `karta-1280.png`, `tv-1280x720.png`, `tv-1920x1080.png`, `tv-1280x720-dlugi-tytul.png` (tytuł podmieniony w
  przeglądarce), `kiosk-1180x820.png`, `kiosk-820x1180.png`, `gosc-390-zwiniete.png`, `gosc-390-co-to-bylo.png`,
  `wynik-juz-leciala-390.png` i `kiosk-juz-leciala-1180.png` (strona wyniku z podmienionym werdyktem — wyrenderowanej odmowy
  „już leciała” w testach nie ma), `podsumowanie-390-zwiniete.png`, `podsumowanie-390.png`, `podsumowanie-1280.png`,
  `podsumowanie-wydruk.png`. Poranny e-mail oceniony z szablonu (bez wyrenderowanej kopii w testach).

## Punkty

### P1 — „Już leciało”: `min(requested_at)` po globalnym indeksie dla imprezy z wieloma prośbami · ważne

- **Gdzie:** `src/main/java/com/scan2play/service/DeckSetService.java:178-183` (`playedLately`, podzapytanie
  `SELECT min(r.requested_at) FROM song_requests r WHERE r.party_code = ? AND r.requested_at >= ? AND NOT r.added_by_dj`).
- **Dlaczego:** `idx_party_decision_time (party_code, decision, requested_at DESC)` ma `decision` w środku, więc nie daje minimum
  wprost. Dla imprezy, którą statystyki widzą jako dużą (pub, DJ grający co tydzień tym samym kodem — najczęstsze wartości
  `party_code`), planista wybiera `idx_song_requests_requested_at` i idzie po prośbach **wszystkich** imprez od 12 h wstecz, aż trafi
  na pierwszą tej imprezy. Zmierzone: 59 ms, 77–81 tys. buforów na jedną prośbę gościa (sprawdzane przed AI, czasem dwa razy). Przy
  1000 imprez w sobotę wieczorem i ~1 prośbie na minutę na imprezę to kilka sekund CPU bazy na minutę tylko na to, a im później
  pierwsza prośba danej imprezy, tym gorzej. Pierwszy przegląd mierzył tylko zwykłą imprezę (0,08 ms) — `05-1d` powtarza tę liczbę.
  `DeckSetIT.theReadsAndThePurge_useTheirIndexes` sprawdza plan z `enable_seqscan = off` i tylko pod kątem `deck_play`.
- **Poprawka:** V85 `CREATE INDEX idx_song_requests_party_guest_time ON song_requests (party_code, requested_at) WHERE NOT
  added_by_dj` (zmierzone: Index Only Scan, 0,08 ms) **albo** bez migracji: pamięć „pierwsza prośba gościa dziś” na imprezę w Caffeine
  (gdy raz znaleziona, nie zmienia się przez 12 h; pusty wynik nie trzymany). Do `DeckSetIT` asercja planu podzapytania
  (`doesNotContain("idx_song_requests_requested_at")`) na danych z jedną dużą imprezą; poprawić liczbę w `05-1d`.

### P2 — wyłączenie „Zapisuj set wieczoru” jednym stuknięciem usuwa 30 dni setów · ważne

- **Gdzie:** `DeckSetService.setKept` (`:226-234`, `DELETE FROM deck_play WHERE party_code = ?`), `DjDeviceController.set`
  (QUEUE), `play-history.js` (`savedAtOnce` — zapis od razu, bez pytania), `dashboard.computer.set.help`.
- **Dlaczego:** przełącznik wygląda jak ustawienie („czy zapisywać od teraz”), a jest też nieodwracalnym usunięciem: znika „Cały set”
  ze wszystkich podsumowań z ostatnich 30 dni (ten sam kod imprezy na wiele wesel), „Co to było?” i „Już leciało” na dziś. Może to
  zrobić każda osoba obsługi z prawem do kolejki, która „tylko sprawdza, co to robi”; po ponownym włączeniu niczego nie wraca. Opis
  „a zapisany set — usuwany” nie mówi, że chodzi o wszystkie wieczory.
- **Poprawka:** przy wyłączaniu pytanie (jak „Wyczyść kolejkę”: `data-confirm`, `dj-nav.js`) z liczbą: „Usunąć zapisany set
  (z 30 dni: 412 piosenek)? Podsumowania stracą »Cały set«.” (liczba z `GET /dj/devices`); rozważyć wyłączanie tylko przez organizatora
  (nie obsługę). Opis: „Wyłączone: nic nie jest zapisywane, a set ze wszystkich wieczorów — usuwany.”

### P3 — testy nie łapią 14 z 21 mutacji, w tym blokad (poprawka P1 pierwszego przeglądu) i Pro · ważne

- **Gdzie:** tabela mutacji niżej. Najważniejsze luki:
  - N27 / N28: bez `lock(partyCode)` w `keep` i w `setKept` wszystkie testy zielone. `twoComputersSendingTheSameSongAtOnce_keepItOnce`
    nie trafia w okno wyścigu, a wyścigu „wyłączenie w trakcie zapisu” (P1 pierwszego przeglądu) nie testuje nic. AGENTS.md / CLAUDE.md:
    „SongRequestVotesIT — red if the advisory lock goes” — tu tego odpowiednika nie ma.
  - N4: „Co to było?” (znacznik i `GET /p/{code}/played`) bez sprawdzenia planu — zielone; N5: nieznany kod → 200 z pustą listą
    zamiast 404 — zielone; nie ma też testu, że `/played` nie tworzy sesji (`GuestNoSessionWebTest` dostał tylko mock).
  - N6: `EveningSummaryService.set` oznacza 🙋 każdą prośbę (nie tylko zagraną) — zielone; samej metody `set` nie testuje żaden test
    serwisu (`SummaryPageTest` sprawdza szablon).
  - N8: „Już leciało” także dla imprezy demo — zielone; N7: drugie sprawdzenie także po odrzuceniu przez AI — zielone.
  - N2: „Teraz” pokazuje pierwszą oznaczoną prośbę wywołania zamiast najpóźniejszej — zielone.
  - N1: „pierwsza prośba gościa” liczona z 7 dni zamiast z 12 h (próba dźwięku dziś liczy się, jeśli wczoraj ktoś prosił) — zielone.
  - N10 / N15: bez unieważnienia „Co to było?” po zapisie / po wyłączeniu — zielone (po wyłączeniu goście widzą usunięty set do 30 s).
  - N11, N12, N16: limit 600 ucinany w połowie wywołania, tytuł > 200 znaków, `setKept` w `GET /dj/devices` — zielone.
- **Poprawka:** `DeckSetIT`: dwa wątki z zatrzaskiem (`CountDownLatch`; jak `SongRequestVotesIT`) — „wyłączenie podczas zapisu nie
  zostawia piosenki” i „dwa komputery naraz — jedna piosenka”, oba czerwone bez blokady; `GuestControllerTest`/web test dla
  `/played` (bez Pro pusto, nieznany kod 404, bez sesji); test `EveningSummaryService.set` (zagrana/niezagrana prośba, tylko tytuł);
  `SongEvaluationServiceTest` dla demo i dla odrzucenia przez AI; `DeckHistoryServiceTest` z dwiema prośbami w jednym wywołaniu;
  `DeckSetIT`: wczorajsza prośba nie przesuwa „pierwszej prośby”, unieważnienie pamięci po zapisie i wyłączeniu.

### P4 — tablica `timestamptz[]` przez `Timestamp.toString()`: godzina przesunięta w noc zmiany czasu, gdy JVM nie jest w UTC · drobne

- **Gdzie:** `DeckSetService.java:130` (`connection.createArrayOf("timestamptz", … Timestamp.from(…))`).
- **Dlaczego:** pgjdbc koduje elementy tablicy przez `toString()` — czas lokalny JVM bez strefy; baza czyta go w strefie sesji. W
  ostatnią niedzielę października godzina 02:00–03:00 jest dwa razy: pierwsze 02:xx (CEST) zapisuje się godzinę później (sprawdzone,
  wyżej). Produkcja ma JVM w UTC (`10-2`: „No TZ”), więc dziś tylko lokalnie (IntelliJ w `Europe/Warsaw`) — ale wystarczy ustawić
  `TZ` na Railway, by na weselu o 2:30 set i „już leciała o …” się rozjechały.
- **Poprawka:** przekazywać `Instant.toString()` jako `text[]` i rzutować `?::text[]::timestamptz[]` (ISO z `Z` jest jednoznaczne)
  albo milisekundy `bigint[]` i `to_timestamp(ms / 1000.0)`; test w `DeckSetIT` z `TimeZone.setDefault(Europe/Warsaw)`.

### P5 — dwa „osierocone” javadoci · drobne

- **Gdzie:** `PartySettingsRepository.java:126-140` — nowa metoda `setNowPlayingShown` wstawiona między javadoc „Nowości mailem
  (V80)” a jego metodę `setNewsEmails`: ten javadoc wisi teraz nad drugim javadociem, a `setNewsEmails` jest bez opisu.
  `SongEvaluationService.java:328-349` — tak samo: javadoc `blockedByTheHosts` („nie grać” gospodarzy) stoi nad javadociem
  `alreadyPlayed`.
- **Poprawka:** przenieść nowe metody nad stare javadoci (albo pod metody).

### P6 — „Wyczyść historię” a „Już leciało” · drobne

- **Gdzie:** `guide_pl.html` / `guide.html` (`#after`, „Wyczyść historię … Potem AI nie pamięta, co już grało”),
  `SongRequestRepository.deleteHistory` (nie rusza `deck_play`).
- **Dlaczego:** DJ, który czyści historię, żeby goście mogli znowu prosić o to samo, dalej dostaje odmowy „już leciała o …” z setu.
  Przewodnik jest teraz nieprawdziwy.
- **Poprawka:** decyzja właściciela: albo „Wyczyść historię” usuwa też dzisiejszy set z „Już leciało” (np. zapamiętana chwila
  wyczyszczenia jako dolna granica w `playedLately`), albo zdanie w przewodniku: „…AI nie pamięta zagranych próśb; »Już leciało« z
  zapisanego setu działa dalej (wyłączysz je, ustawiając »Bez powtórek« na 0)”.

### P7 — dwa sposoby na dwa podobne przełączniki · drobne

- **Gdzie:** `DjDeviceController.list` (`deckSet.kept(...)` — osobne zapytanie przy każdym `GET /dj/devices`, co minutę na panel),
  `PartySettingsEntity.nowPlayingShown` (w encji, z pamięci ustawień); `DjDeviceController.Card` — javadoc wymienia tylko
  „Zapisuj set wieczoru”.
- **Dlaczego:** `deck_set_kept` nie jest w encji, bo czyta go `INSERT` — to dobry powód dla zapisu, ale odczyt dla karty mógłby iść z
  encji jak drugi przełącznik (mniej zapytań, jedna droga). Przy 1000 otwartych panelach to ~17 zapytań/s, tanich — nie błąd, tylko
  niespójność. Testy używają wszędzie pełnych nazw (`org.mockito.Mockito.mock(com.scan2play.service.DeckSetService.class)`) zamiast
  importów — czytelność gorsza niż w sąsiednich testach.
- **Poprawka:** zmapować `deckSetKept` w encji (tylko odczyt, `insertable = false, updatable = false`, żeby `save` całego wiersza go
  nie nadpisał) i czytać z niej w karcie; importy w testach.

### P8 — karta programu: puste wiersze i długie opisy · drobne

- **Gdzie:** `dashboard.html` (karta „Zagrane samo”), `messages*.properties` `dashboard.computer.now.help`, `.set.help`; zrzuty
  `karta-800.png`, `karta-1280.png`.
- **Dlaczego:** puste `#deviceNowPlayingFailed` / `#deviceSetFailed` (`w-100`) zajmują w `flex-wrap` własny wiersz — pod każdym
  przełącznikiem jest pusta linia, czego nie ma pod „Ostrzeż mnie…”. Opis „Teraz” zaczyna się od cudzysłowu i emoji („„🎶 Teraz: …”
  nad ich listą…” — „ich” odnosi się do etykiety wyżej). Opis setu ma 3–4 linie i odsyła do „Bez powtórek”, którego w tej karcie nie ma.
  Karta ma teraz ~6 akapitów tekstu; DJ zrozumie różnicę (jeden przełącznik pokazuje, drugi zapisuje), ale musi czytać.
- **Poprawka:** komunikaty błędu w tym samym wierszu co przełącznik albo `:empty { display: none }`; krócej, np. „Nad listą na TV i
  kiosku: »🎶 Teraz: …« — tytuł prośby gościa, którą gra program, bez dedykacji.” i „Wszystkie piosenki wieczoru, 30 dni: »Już
  leciało« dla gości (według »Bez powtórek« w Ustawieniach), »Co to było?« i cały set w podsumowaniu. Wyłączenie usuwa zapisane sety.”

### P9 — pasek „Teraz” na TV ucina długi tytuł do samego wykonawcy · drobne

- **Gdzie:** `tv.css` `.tv-now` (jedna linia, `…`), zrzut `tv-1280x720-dlugi-tytul.png`.
- **Dlaczego:** przy dwujęzycznej etykiecie („Teraz: Now playing:”) na 1280 px zostaje ~30 znaków: „Golec uOrkiestra & Gorale z
  Po…” — sala nie widzi tytułu, czyli tego, po co pasek jest. Lista niżej ma ten sam problem, ale tam tytuły są krótsze (z AI).
- **Poprawka:** na pasku dwie linie (`-webkit-line-clamp: 2`, z zapasem dla starych przeglądarek TV) albo etykieta nad nazwą,
  angielska w mniejszym stopniu; scenariusz `tv-now-playing-fits-a-small-tv` z długą nazwą.

### P10 — odmowa „już leciała” na czerwono „NIE” · drobne (do decyzji)

- **Gdzie:** `result.html` / `kiosk.html` (werdykt `rejected`), zrzuty `wynik-juz-leciala-390.png`, `kiosk-juz-leciala-1180.png`.
- **Dlaczego:** tekst „Ta piosenka już leciała o 21:30 — wybierz inną.” jest jasny i uprzejmy, ale nad nim duże czerwone „NIE”, jak
  przy piosence niepasującej do klimatu. Gość mógł się spóźnić — to nie jego „zły wybór”. Tak samo działa istniejące „leciała przed
  chwilą”, więc spójne; do decyzji, czy oba nie powinny mieć łagodniejszego werdyktu („JUŻ GRAŁO”, kolor ostrzegawczy).

### P11 — przewodnik, `/pro`, „Nowości” · drobne

- `guide_pl.html` / `guide.html` `#guideDeckSet` opisuje wszystko prawdziwie i tak samo w PL i EN (pasek z przełącznikiem, zapis,
  30 dni, „Już leciało” z N i 0, „Co to było?” z 10, „Cały set”, usuwanie). Brakuje odsyłaczy tam, gdzie DJ szuka:
  „📺 Ekran na TV” i „🖥 Kiosk dla gości” (o pasku „Teraz” z programem), „Podsumowanie wieczoru” (o „Całym secie”), „Poranne
  podsumowanie” (liczba piosenek setu), „Wyczyść historię” (P6). Obrazki przewodnika nie są przez to błędne (TV nie ma obrazka, set
  w podsumowaniu jest zwinięty).
- `/pro` (`pro_pl.html:109`, `pro.html`) przy „Zagrane samo” mówi tylko o oznaczaniu próśb — warto dopisać „»Teraz leci« na TV,
  cały set w podsumowaniu, »Już leciało«”. Strona główna: bez potrzeby. „Nowości” (`PanelNews.NUMBER = 1`, z 2026-11-26, już o
  „Zagrane samo”): nie zmieniać numeru dla tej zmiany; ewentualnie w następnych nowościach.

### P12 — „Cały set” także na stronie podsumowania dla gospodarzy · drobne (do decyzji)

- **Gdzie:** `summary.html` (`#wholeSet` bez warunku na wersję gospodarzy); `SummaryPageTest` renderuje `wedding-set-hosts-en`.
- **Dlaczego:** set DJ-a (z próbą dźwięku i piosenkami spoza próśb) trafia do każdego, kto ma link gospodarzy. Prawdopodobnie
  pożądane (pamiątka z wesela), ale ani karta, ani przewodnik tego nie mówią. Do decyzji; jeśli tak — zdanie w przewodniku.

### P13 — „🙋” w „Całym secie” niezależnie od pory · drobne

- **Gdzie:** `EveningSummaryService.set` (`:679-697`).
- **Dlaczego:** piosenka zagrana o 19:00 (przed prośbą) i znowu o 23:00 (na prośbę) — obie z 🙋. Rzadkie; poprawka: 🙋 tylko przy
  najbliższym `startedAt` po `requestedAt` prośby (jak `DeckHistoryService`).

### P14 — dzienny limit 600 liczy dzień kalendarzowy, a wieczór trwa 6:00–6:00 · informacja

- `DeckSetService.java:111` (`LocalDate.ofInstant(now, DISPLAY_ZONE)`): po północy licznik się zeruje, więc noc może dać do 1200
  piosenek z komputera. Nieszkodliwe (limit chroni tabelę, nie wieczór); dla spójności można liczyć wieczór (`Times`/`startOf`).

### P15 — informacje

- Zmiana kodu imprezy („Nowy link”, V49): `deck_play` idzie za kodem (FK ON UPDATE CASCADE), prośby też (`PartyCodeService`);
  w pamięci zostaje stary klucz — pasek „Teraz” znika do następnej piosenki, „Co to było?” czyta bazę od nowa. W porządku.
- Usunięcie konta: `partySettingsRepository.delete` → `ON DELETE CASCADE` usuwa set (test `aPartyDeleted_takesItsSetWithIt`).
- `GET /p/{code}/played` jest publiczne jak `/p/{code}/queue`, bez osobnego limitu; koszt ograniczony pamięcią 30 s na imprezę
  i indeksem. Nie tworzy sesji (brak formularza i tokenu CSRF), ale bez testu (P3).
- Zapis całego wiersza ustawień (`updateSettings`, `SELECT … FOR UPDATE`) i jednokolumnowy `UPDATE now_playing_shown` są
  szeregowane blokadą wiersza — przełącznik nie zginie pod równoczesnym zapisem ustawień.
- `docs/context` (05-1d, 05-1f, 05-1g, 06-1, 07-09, 10-2, 11-12) zgodne z kodem poza liczbą 0,08 ms (P1); końce linii CRLF zachowane.
  `messages*.properties`: escape'y `\uXXXX`, cudzysłowy „…” i ’ jak w reszcie, PL bezosobowo; `ImpersonalPolishTextsTest` zielony.

## Mutacje

21 mutacji innych niż M1–M23 pierwszego przeglądu, każda na kopii gałęzi: `check.py unit` (całość), `it -t DeckSetIT` albo
`browser` ze scenariuszami danej funkcji. **7 czerwonych, 14 przeżyło.**

| # | mutacja | wynik |
|---|---------|-------|
| N1 | „pierwsza prośba gościa” szukana 7 dni wstecz zamiast 12 h | **przeżyła** |
| N2 | „Teraz” = pierwsza oznaczona prośba wywołania, nie najpóźniejsza | **przeżyła** |
| N4 | „Co to było?” bez sprawdzenia planu (Pro) | **przeżyła** |
| N5 | `/p/{nieznany}/played` → 200 z pustą listą zamiast 404 | **przeżyła** |
| N6 | „Cały set”: 🙋 dla każdej prośby, nie tylko zagranej | **przeżyła** |
| N7 | drugie „Już leciało” także po odrzuceniu przez AI | **przeżyła** |
| N8 | „Już leciało” także dla imprezy demo | **przeżyła** |
| N9 | zapis piosenek starszych niż 12 h | czerwona (`DeckSetIT`) |
| N10 | bez unieważnienia „Co to było?” po zapisie | **przeżyła** |
| N11 | limit 600 nie ucina wywołania w połowie | **przeżyła** |
| N12 | tytuł bez ucięcia do 200 znaków | **przeżyła** |
| N13 | poranny e-mail zawsze z linią setu | czerwona (`AutomaticEmailsTest`) |
| N15 | bez unieważnienia „Co to było?” po wyłączeniu | **przeżyła** |
| N16 | `GET /dj/devices` zawsze `setKept: true` | **przeżyła** |
| N18 | przełącznik nie wraca po błędzie zapisu (`play-history.js`) | czerwona (`deck-set-kept-not-saved`) |
| N19 | `guest-party.js` bez ochrony przed starszą odpowiedzią (`mine !== asked`) | **przeżyła** |
| N20 | `guest-party.js` wstawia stronę błędu jako listę | czerwona (`guest-played-not-loaded`) |
| N23 | „Co to było?” od najstarszej | czerwona (`DeckSetIT`) |
| N24 | „Już leciało”: pierwsze N od pierwszej prośby zamiast ostatnich N | czerwona (`DeckSetIT`) |
| N27 | `keep` bez blokady doradczej imprezy | **przeżyła** |
| N28 | `setKept` bez blokady doradczej imprezy (poprawka P1 pierwszego przeglądu) | **przeżyła** |

## Kolejność poprawek

1. P1 (indeks V85 albo pamięć pierwszej prośby) i P3 (testy blokad, Pro, 404, `set`) — przed albo razem z wydaniem.
2. P2 (potwierdzenie przy wyłączaniu, opis).
3. P5, P6, P8, P9, P11 — drobne, jednym PR-em.
4. P4, P7, P10, P12, P13, P14 — przy okazji / do decyzji właściciela.

## Poprawki (2026-11-28, na prośbę właściciela)

Na gałęzi `claude/intelligent-faraday-q6k10d`, do której scalona jest `claude/optimistic-hopper-20ct09` (PR-y #319 / #320 jej nie
mają, dopóki właściciel nie zdecyduje, którą gałąź wydać).

- **P1 — poprawione.** V85 `idx_song_requests_party_guest_time (party_code, requested_at) WHERE NOT added_by_dj` i pierwsza prośba
  gościa wieczoru trzymana w pamięci na imprezę (`DeckSetService.firstGuestRequest`, godzina, póki jest z ostatnich 12 h; brak — nie
  trzymany). Sam indeks nie wystarczał: na małej tabeli bez VACUUM planista dalej brał `idx_song_requests_requested_at`. „Już leciało”
  to teraz jedno zapytanie po `deck_play` (+ raz na godzinę na imprezę to po prośbach). `DeckSetIT` sprawdza plan podzapytania.
- **P2 — poprawione.** Wyłączenie „Zapisuj set wieczoru” pyta (`data-confirm-off`, `dashboard.computer.set.confirm`: „…sety ze
  wszystkich wieczorów z 30 dni zostaną usunięte, także »Cały set« w podsumowaniach. Tego nie da się cofnąć.”), „Anuluj” zostawia
  włączone i nic nie wysyła; opis mówi o wszystkich wieczorach. Scenariusz `deck-set-off-asks-first`. Kto może (obsługa z QUEUE czy
  tylko organizator) — bez zmian, do decyzji właściciela.
- **P3 — poprawione.** Nowe testy: `DeckSetIT` (blokada trzymana z drugiego połączenia: zapis i wyłączenie czekają — czerwone bez
  `lock`; strefa JVM `Europe/Warsaw` w noc zmiany czasu; wczorajsza prośba; limit 600 ucięty w połowie; tytuł > 200; pamięć „Co to
  było?” po zapisie i po wyłączeniu; plan V85), `GuestControllerTest.whatWasThat_isPartOfPro`,
  `GuestNoSessionWebTest.whatWasThat_isAnsweredWithoutASession_orA404`, `EveningSummaryServiceTest.theWholeSet_marksThePlayOfEachPlayedRequest`,
  `SongEvaluationServiceTest` (demo nie czyta setu; odrzucenie przez AI zostaje), `DeckHistoryServiceTest` (dwie prośby w wywołaniu),
  `PlanFeatureEndpointsTest.theCardsSwitches_areSaidAndSaved` (i odmowa bez Pro nie zapisuje przełączników).
- **P4 — poprawione.** Czasy do `unnest` jako tekst ISO z `Z` (`?::text[]::timestamptz[]`), nie `Timestamp`.
- **P5 — poprawione.** Javadoci na swoich miejscach (`PartySettingsRepository`, `SongEvaluationService`, także w `GuestNoSessionWebTest`).
- **P6 — poprawione w przewodniku** (PL/EN, „Wyczyść historię”: set zostaje, „już leciała” działa dalej, wyłącza je „Bez powtórek” = 0).
  Czy „Wyczyść historię” ma też czyścić dzisiejszy set — do decyzji.
- **P7 — częściowo.** Javadoc `Card` mówi, skąd który przełącznik. `deck_set_kept` zostaje poza encją: odczyt z encji z pamięci wymagałby
  czyszczenia pamięci ustawień w `setKept` — więcej sprzężenia za ~17 tanich zapytań/s.
- **P8 — poprawione.** Komunikaty błędu w wierszu przełącznika (bez pustych linii), krótsze opisy (`po-poprawce-karta-800.png`,
  `po-poprawce-karta-1280.png`).
- **P9 — poprawione.** Pasek „Teraz” na TV do dwóch linii (`-webkit-line-clamp: 2`, `max-height` dla starych przeglądarek); 1280 × 720,
  1366 × 768 i 1920 × 1080 bez przewijania (`po-poprawce-tv-1280x720-dlugi-tytul.png`); scenariusz
  `tv-now-playing-long-name-fits-a-small-tv`.
- **P11 — poprawione.** Przewodnik PL/EN: „📺 Ekran na TV”, „🖥 Kiosk”, „Podsumowanie wieczoru”, „Poranne podsumowanie”; `/pro` PL/EN
  przy „Zagrane samo”. „Nowości” bez zmian.
- **P13 — poprawione.** 🙋 tylko przy pierwszym zagraniu po prośbie (z zapasem zegara programu, 5 min).
- **P10, P12** — zob. „Decyzje” niżej. **P14** — bez zmian (informacja).
- Dokumenty: `05-1d` (koszt, pytanie przy wyłączaniu, testy), `10-2` (V85).

Sprawdzenia po poprawkach: `check.py unit` **1600/1600**, `it` **262/262**, `browser` **503/504** (czerwony tylko znany
`kiosk-full-fits-820x1180`). Mutacje R1–R20 (te, które wcześniej przeżyły, i nowe dla poprawek) — **wszystkie czerwone**: bez blokady
w `keep` / `setKept`, stare kodowanie czasu, bez V85, „pierwsza prośba” z 7 dni, pamięć bez sprawdzenia 12 h, bez czyszczenia pamięci
po zapisie / po wyłączeniu, limit w połowie, tytuł bez ucięcia, „Co to było?” bez Pro, 200 zamiast 404, 🙋 dla niezagranej i przed
prośbą, drugie „Już leciało” po odrzuceniu przez AI, demo, „Teraz” = pierwsza prośba, `setKept` w karcie, bez pytania przy wyłączaniu,
pasek w jednej linii. R7 przeżyła za pierwszym razem (`setKept(true)` sam czyścił pamięć) — test poprawiony, teraz czerwona.

## Decyzje i ich poprawki (2026-11-28)

Właściciel: P10 — „chyba lepszy łagodniejszy”; P12, wyłączanie setu, P6 — „jak uważasz”.

- **P10 — zrobione.** Piosenka, która dziś już leciała, nie dostaje czerwonego „NIE”, tylko pomarańczowe **„JUŻ GRAŁO”** / „ALREADY
  PLAYED” (`DjResponse.KIND_REPEAT`, `result.decision.repeat`, `.page-guest .repeat` — kolor ostrzeżenia; kiosk ma ten kolor dla
  odmów już wcześniej). Tekst: „Ta piosenka już dziś leciała — o 21:30. Poproś o coś innego!” (twarda spacja przed godziną) i „Ta
  piosenka leciała przed chwilą. Poproś o coś innego!” — to drugie, gdy AI nie można było zapytać. Odmowa samego AI (także „grało
  niedawno” w jego słowach) zostaje „NIE”: kod nie wie, czy AI odmówiło z tego powodu. Testy: `GuestPageRenderTest.aSongThatPlayedTonight_isRefusedGently`
  (pisze `result-repeat.html`), `SongEvaluationServiceTest`; zrzut `po-poprawce-wynik-juz-gralo-390.png`.
- **P12 — zostawione: gospodarze widzą „Cały set”.** To pamiątka z ich wesela, set nie ma danych gości, a DJ sam udostępnia link.
  Przewodnik PL/EN mówi to teraz wprost („także w linku dla gospodarzy”).
- **Wyłączanie setu — zostaje dla obsługi z prawem do kolejki.** Po P2 wyłączenie pyta, co usunie; organizator dał obsłudze
  kolejkę, a karta programu jest jej narzędziem. Rozdzielanie uprawnień dla jednego przełącznika — więcej niż warte.
- **P6 — „Wyczyść historię” zaczyna „Już leciało” od nowa.** V86 `party_settings.history_cleared_at` (w bazie, nie w pamięci —
  restart w trakcie wieczoru nie przywraca starych piosenek); `DjService.clearHistory` → `DeckSetService.historyCleared`, a
  „Już leciało” liczy piosenki setu od późniejszej z chwil: pierwsza prośba gościa wieczoru albo ostatnie wyczyszczenie. Set zostaje
  w podsumowaniu. Zgodnie z tym, co przewodnik zawsze mówił: „Potem AI nie pamięta, co już grało”. Test
  `DeckSetIT.afterTheHistoryCleared_onlyTheSongsPlayedLaterAreRepeats`, `DjServiceTest`.

Sprawdzenia: `check.py unit` **1601/1601**, `it` **263/263** (po `pg_ctlcluster 16 main start` — PostgreSQL kontenera stanął między
przebiegami), `browser` **503/504** (tylko znany `kiosk-full-fits-820x1180`). Mutacje S1–S6 — **wszystkie czerwone**: werdykt „NIE”
zamiast „JUŻ GRAŁO”, `KIND_TITLE` zamiast `KIND_REPEAT` (oba miejsca), „Wyczyść historię” bez zapisu chwili, granica wyczyszczenia
pominięta, pamięć pierwszej prośby nieczyszczona po wyczyszczeniu.
