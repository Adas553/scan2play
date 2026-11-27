# Przegląd 2026-11-27 — „Teraz leci” na TV i kiosku, set wieczoru zapisany (V83)

Zakres: gałąź `claude/optimistic-hopper-20ct09` od `main` = `693813f` (#317), commity „Teraz leci” (`5c61ba0`), set wieczoru
(`de117c3`) i poprawki tego przeglądu. Migracja V83 (tylko dodaje: tabela `deck_play` z dwoma indeksami, kolumna
`party_settings.deck_set_kept` z domyślną wartością, szerszy CHECK `evening_refusal`). `docs/ideas.md` punkt 21 (3 i 4), szczegóły
`docs/context/05-1d-panel-heading-queue.md`.

Decyzje właściciela (2026-11-27, jedno pytanie, wszystkie rekomendowane): set domyślnie zapisywany w Pro z przełącznikiem w karcie,
trzymany 30 dni; „Już leciało” tylko informuje, okno = „Bez powtórek z ostatnich N” DJ-a (0 = wyłączone); „Co to było?” — 10
ostatnich; TV bez dedykacji (formularz gościa obiecuje „Zobaczy ją tylko DJ”); „Teraz leci” także na kiosku.

## Wynik w skrócie

Obie funkcje działają i są przetestowane osobno. Koszt przy 1000 imprez naraz jest mały i policzony (niżej). TV i kiosk nie
dokładają ani zapytania, ani odpytywania: „teraz leci” jest w pamięci, ustawiane tylko przez `DeckHistoryService`. Strona gościa
nie odpytuje: „Co to było?” pobiera listę dopiero po rozwinięciu, a lista jest trzymana 30 s w pamięci na imprezę.

Przegląd znalazł trzy rzeczy do poprawy przed wydaniem (P1–P3, poprawione w tej gałęzi) i kilka drobnych, zostawionych świadomie
(P4–P9).

## Koszt przy 1000 równoczesnych imprez

- **Zapis setu:** piosenka co ~3,5 min na imprezę → ~300 wywołań z piosenkami na minutę, każde to jedno `INSERT … SELECT FROM
  unnest(…)` (≤ 20 wierszy) pod blokadą doradczą imprezy. Puste wywołania programu („czy nadal połączony”, co minutę) nic nie
  zapisują. Limit 600 piosenek na komputer dziennie (w pamięci).
- **„Już leciało”:** przy każdej prośbie gościa jedno zapytanie (dwa, gdy nazwa z AI jest inna niż słowa gościa) po
  `idx_deck_play_party_started`, czyta ≤ N wierszy (N = „Bez powtórek”, domyślnie 15). Przy ~1 prośbie na minutę na imprezę to
  ~30 zapytań na sekundę po 0,08 ms. Cache niepotrzebny.
- **„Co to było?”:** jedno zapytanie na imprezę na 30 s najwyżej (Caffeine, 10 000 imprez), także dla znacznika na stronie gościa.
- **TV i kiosk:** zero zapytań więcej (mapa w pamięci, 10 000 imprez, 6 min).
- **Sprzątanie:** nocne, partiami po 1000 po `idx_deck_play_started`.

**EXPLAIN ANALYZE** na throw-away bazie (PostgreSQL 16 w kontenerze, 1,5 mln wierszy: 3000 imprez × 500 piosenek z 40 dni + 80
piosenek dzisiejszego seta jednej imprezy): „Już leciało” — Index Scan `idx_deck_play_party_started`, 15 wierszy, 0,08 ms; „Co to
było?” — Index Scan, 0,02 ms; wieczór (≤ 1000) — Bitmap Index Scan, 0,26 ms; „ta sama piosenka ±2 min” — Index Scan, 0,02 ms;
sprzątanie, gdy nic nie jest stare — Index Scan `idx_deck_play_started`, 0,05 ms; gdy ¼ tabeli jest stara, planista wybiera Seq Scan
z LIMIT (znajduje 1000 od razu, 3,6 ms) — dobrze. `DeckSetIT.theReadsAndThePurge_useTheirIndexes` sprawdza plany przy każdym
przebiegu.

## Testy i sprawdzenia

- `tools/check.py unit`: **1591/1591**. `check.py it` (PostgreSQL 16 w kontenerze, po `ALTER USER postgres PASSWORD '1111'`):
  **257/257**, w tym nowy `DeckSetIT` (10). `check.py browser`: **500/501** — czerwony tylko znany `kiosk-full-fits-820x1180`
  (był czerwony przed tą pracą, poprzedni przegląd). Nowe scenariusze (`tv-now-playing-*`, `kiosk-now-playing-fits-1180x820`,
  `deck-set-kept-*`, `guest-played-*`) widziane najpierw czerwone: bez paska w wyrenderowanych stronach (TV, kiosk) i bez zmian w
  `play-history.js` / `guest-party.js` (karta, strona gościa); `tv-now-playing-fits-a-small-tv` był też naprawdę czerwony — dziesiąta
  piosenka wypadała pod ekran przy 1280 × 720, stąd `.tv-list-now`.
- `check.py audit -t tv,kiosk,guest,summary-wedding-set,dashboard`: 47 stron × 3 szerokości, bez przewijania w bok i bez błędów
  konsoli; te same cztery małe cele co przed tą pracą (nie dotyczą zmian).
- Zrzuty (Playwright na atrapie serwera, bez błędów konsoli i bez przewijania w bok), w
  `docs/history/review-2026-11-27-shots-now-playing/`: `tv-teraz-1280x720.png` (pasek i dziesięć piosenek mieszczą się), `tv-teraz-1920.png`,
  `kiosk-teraz-1180.png`, `gosc-co-to-bylo-390.png` (rozwinięte), `podsumowanie-set-390.png`, `podsumowanie-set-1280.png`,
  `karta-set-800.png` (przełącznik pod „Ostrzeż po”; karta jest pokazywana od 768 px, więc zrzutu z telefonu nie ma). Na TV długa
  nazwa na pasku kończy się „…”, jak wiersze listy — świadomie, ekran nie przewija się.

## Punkty

### P1 — „Zapisuj set wieczoru” wyłączone w trakcie wywołania programu zostawiało piosenkę · ważne · poprawione

- **Gdzie:** `src/main/java/com/scan2play/service/DeckSetService.java` (`setKept`, `keep`).
- **Dlaczego:** `keep` sprawdza `deck_set_kept` w samym `INSERT`, a `setKept(false)` robi `UPDATE` i `DELETE`. Przy READ COMMITTED
  wywołanie programu, które zaczęło `INSERT` przed commitem wyłączenia, mogło wstawić piosenkę już po `DELETE`. DJ wyłączył zapis,
  a w bazie zostawała piosenka — wbrew temu, co mówią karta i polityka prywatności.
- **Poprawka:** `setKept` bierze tę samą blokadę doradczą imprezy co `keep` (`lock`). Zapis i wyłączenie idą po kolei.

### P2 — „Co to było?” unieważniane przed commitem · drobne · poprawione

- **Gdzie:** `DeckSetService.keep` i `setKept`.
- **Dlaczego:** `forGuests.invalidate` przed commitem: gość czytający w tej chwili wkładał do pamięci starą listę na 30 s.
- **Poprawka:** `AfterCommit.run(…)`, jak przy innych cache'ach.

### P3 — „Już leciało” liczyło próbę dźwięku przed przyjściem gości · ważne · poprawione

- **Gdzie:** `DeckSetService.playedLately`.
- **Dlaczego:** set to wszystkie piosenki z programu, także próba dźwięku o 14:30 (cztery, pięć utworów). Do pierwszej piosenki
  seta są one „ostatnimi N”. Gość, który przy obiedzie prosi o jeden z nich, dostaje „Ta piosenka już leciała o 14:32” — a goście
  jej nie słyszeli.
- **Poprawka:** liczą się tylko piosenki od pierwszej prośby gościa tego wieczoru (12 h, bez „+ Dodaj piosenkę” DJ-a). Pierwsza
  prośba wieczoru niczego nie sprawdza. Podzapytanie na `idx_party_decision_time`. Test w `DeckSetIT`.

### P4 — „Co to było?” pokazuje też próbę dźwięku i działa 12 h po końcu imprezy · drobne · zostawione

- **Gdzie:** `DeckSetService.lastPlayed`, `GuestController.played`.
- **Dlaczego zostawione:** to lista „co leciało” — próba przed gośćmi spada z 10 ostatnich po kilku piosenkach seta. Po końcu
  imprezy strona gościa jej nie pokazuje (tylko otwarta impreza), adres `/played` tak, ale to te same piosenki, które słyszała sala.

### P5 — wieczór z setem, ale bez próśb, nie jest w podsumowaniu · drobne · zostawione

- **Gdzie:** `EveningSummaryService.evenings` (lista wieczorów z próśb).
- **Dlaczego zostawione:** podsumowanie jest o prośbach gości. Set jest dodatkiem do wieczoru, który ma prośby.

### P6 — CSV podsumowania nie ma setu · drobne · zostawione

- Na później, jeśli DJ-e zapytają (osobny plik albo druga sekcja CSV).

### P7 — pasek „Teraz” trwa ~45 s w następną piosenkę (VirtualDJ) albo znika wcześniej (Serato 2/3) · informacja

- **Gdzie:** `NowPlayingService.heard`.
- **Dlaczego:** VirtualDJ zapisuje piosenkę po ~45 s, Serato 2/3 przy załadowaniu na deck. Bez nowych danych z programu lepiej się
  nie da. Opisane w `05-1d`.

### P8 — „teraz leci” i limit 600 dziennie są w pamięci · informacja

- Restart gubi najwyżej jeden pasek i zeruje licznik dnia. Jak inne limity w pamięci (`DjDeviceService`) zakłada jedną instancję
  aplikacji (Railway: jedna replika).

### P9 — sprawdzenie po nazwie z AI płaci już za Gemini · informacja

- „baśka wilki” trzeba zrozumieć, zanim da się porównać z setem. Sprawdzenie przed AI (słowa gościa „Wilki - Baśka”) oszczędza
  wywołanie, gdy się da.

### P10 — DJ nie mógł wyłączyć paska „Teraz” · zmiana na prośbę właściciela · zrobione

- **Gdzie:** `TvScreenController`, `KioskController`, karta programu (`dashboard.html`, `play-history.js`).
- **Co:** właściciel (2026-11-27, po przeglądzie): „czy DJ może sam ustawić, czy chce, by ludzie widzieli, co jest grane teraz?” —
  „Pokazuj na TV i kiosku, co teraz leci” w karcie programu, domyślnie włączone (V84 `party_settings.now_playing_shown`). Pole jest
  w encji: TV i kiosk czytają je z ustawień w cache, bez własnego zapytania; zapis jedną kolumną, cache czyszczony po commicie.
  Testy: `TvScreenWebTest`, `KioskWebTest`, `DeckSetIT.nowPlayingShown_…`, `PlanFeatureEndpointsTest`, scenariusz
  `deck-now-playing-switch` (najpierw czerwony); mutacje M22 (TV ignoruje przełącznik) i M23 (kiosk) — czerwone.

## Mutacje

23 mutacje kodu, każda uruchomiona na swoim teście (`check.py unit` / `it -t …`); wszystkie czerwone. M1 pierwszy raz zbudowana źle
(błąd liczby parametrów SQL — czerwień z innego powodu), powtórzona poprawnie. M9 za pierwszym razem **przeżyła**: test łączył
piosenkę DJ-a i niesprawdzoną prośbę w jednym wywołaniu, a pasek i tak znikał przez nowszą piosenkę — test rozdzielony, teraz czerwona.

| # | mutacja | wynik |
|---|---------|-------|
| M1 | zapis bez „ta sama piosenka ±2 min” w SQL | **czerwony** (`DeckSetIT`, 2 testy) |
| M2 | zapis mimo `deck_set_kept = false` | **czerwony** |
| M3 | bez limitu 600 dziennie | **czerwony** |
| M4 | „Już leciało” także dla tytułu bez wspólnego wykonawcy | **czerwony** |
| M5 | „Już leciało” bez okna N | **czerwony** |
| M6 | wyłączenie bez usuwania setu | **czerwony** |
| M7 | bez sprawdzenia przed AI | **czerwony** (`SongEvaluationServiceTest`) |
| M8 | bez sprawdzenia po nazwie z AI | **czerwony** |
| M9 | „Teraz” także dla piosenki DJ-a | **czerwony** (po rozdzieleniu testu) |
| M10 | ta sama piosenka wysłana ponownie kończy pasek | **czerwony** |
| M11 | pasek bez limitu 6 min | **czerwony** |
| M12 | nocne sprzątanie bez setu | **czerwony** |
| M13 | błąd zapisu setu psuje wywołanie programu | **czerwony** |
| M14 | podsumowanie bez znacznika 🙋 | **czerwony** |
| M15 | „Co to było?” zawsze na stronie gościa | **czerwony** |
| M16 | TV bez paska | **czerwony** |
| M17 | kiosk bez paska | **czerwony** |
| M18 | API bez zapisu setu | **czerwony** |
| M19 | e-mail bez liczby piosenek setu | **czerwony** |
| M20 | P3: pierwsza prośba wieczoru też sprawdza (bez gości — wszystko) | **czerwony** |
| M21 | P3: „+ Dodaj piosenkę” DJ-a liczy się jako gość | **czerwony** |
| M22 | P10: TV pokazuje pasek mimo wyłączenia | **czerwony** (`TvScreenWebTest`) |
| M23 | P10: kiosk pokazuje pasek mimo wyłączenia | **czerwony** (`KioskWebTest`) |
