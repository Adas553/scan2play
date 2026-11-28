Przeczytaj CLAUDE.md (z AGENTS.md) i „Start here” w SESSION_HANDOFF.md. Resztę dokumentów czytaj tylko w potrzebnych kawałkach
(agent context-finder); testy przez agenta test-runner albo `python tools/check.py`.

ZADANIE: niezależny przegląd gałęzi `claude/optimistic-hopper-20ct09` — PR-y #319 (do `main`) i #320 (do `dev`), jeszcze
niezmergowane. Zakres: `git diff 693813f...origin/claude/optimistic-hopper-20ct09` (`693813f` = `main` przed tą pracą). Zmiany:
- „🎶 Teraz: …” na ekranie TV i kiosku (`NowPlayingService`, `DeckHistoryService`, `TvScreenController`, `KioskController`, `tv.html`,
  `tv.css`, `kiosk.html`) z przełącznikiem „Pokazuj na TV i kiosku, co teraz leci” (V84);
- set wieczoru zapisany (V83, `DeckSetService`, tabela `deck_play`): „Ta piosenka już leciała o 21:30” przed AI
  (`SongEvaluationService`), „Cały set” w podsumowaniu i poranny e-mail, „Co to było?” na stronie gościa (`GuestController`,
  `guest-played.html`, `guest-party.js`), przełącznik „Zapisuj set wieczoru” w karcie programu (`DjDeviceController`,
  `play-history.js`, `dashboard.html`), sprzątanie po 30 dniach (`SongRequestRetentionService`).
Decyzje właściciela są w `docs/ideas.md` punkt 21 i w nagłówku pierwszego przeglądu
`docs/history/review-2026-11-27-now-playing-and-set.md`. Przeczytaj go, ale oceniaj sam — nie powtarzaj jego wniosków bez sprawdzenia.

Pracujesz tylko na odczyt kodu i w kopiach (`tools/check.py`). NIE poprawiaj niczego w tej sesji, chyba że o to poproszę po
przeczytaniu wyników.

SPRAWDŹ I ODPOWIEDZ NA CZTERY PYTANIA:

1. Czy kod jest dobrze zrobiony i bezpieczny?
   - Poprawność: granice czasu („teraz” 6 min, ta sama piosenka ±30 s / ±2 min, 12 h „tonight”, pierwsza prośba gościa wieczoru),
     strefa czasowa (Europe/Warsaw, wieczór 6:00–6:00), zmiana czasu, zmiana kodu imprezy (V49, FK ON UPDATE CASCADE), usunięcie
     konta, impreza bez Pro, demo, kiosk.
   - Współbieżność i transakcje: blokada doradcza imprezy w `DeckSetService` (`keep`, `setKept`), `@Transactional` z `JdbcTemplate`,
     `AfterCommit`, pamięć podręczna Caffeine (rozmiary, wygasanie, unieważnianie). Czy coś może przepuścić piosenkę po wyłączeniu
     zapisu albo zostawić stary stan na stronach?
   - Bezpieczeństwo: publiczne `GET /p/{code}/played` (bez sesji — czy nie tworzy sesji, czy nie da się nim nadużyć), `POST
     /dj/devices/set` i `/dj/devices/now-playing` (CSRF, uprawnienie QUEUE, plan Pro, IDOR — tylko własna impreza), `/api/device/plays`
     (limity: ≤ 20 na wywołanie, 600 dziennie na komputer, 30 wywołań na minutę), wstrzykiwanie SQL (tablice `unnest`, sklejane
     interwały), XSS (nazwy piosenek z programu DJ-a na TV, kiosku, stronie gościa, w podsumowaniu i e-mailu), CSP (nic inline).
   - Prywatność: dedykacja nigdy na TV; set bez danych gości; nigdy w „Trendach próśb” (`SongTrendsService`); polityka prywatności PL /
     EN zgodna z tym, co kod naprawdę zapisuje i jak długo.
   - Baza: V83 i V84 tylko dodają; indeksy pasują do zapytań — sprawdź `EXPLAIN (ANALYZE)` na throw-away bazie `s2p_*` z dużą liczbą
     wierszy (podzapytanie `min(requested_at)` w „Już leciało” na `song_requests` też); nocne sprzątanie partiami.
   - Wydajność przy 1000 imprez naraz: zapis setu, „Już leciało” przy każdej prośbie, strona gościa (znacznik „Co to było?” przy
     każdym wejściu), TV i kiosk co 10 s, `GET /dj/devices` co minutę.
   - Testy: czy naprawdę sprawdzają zachowanie? Zrób kilka własnych mutacji (inne niż M1–M23 z pierwszego przeglądu) i podaj, które
     przeżyły.

2. Czy zmiany są profesjonalne i spójne z projektem?
   - Struktura jak w sąsiednich klasach (małe serwisy, bez zbędnych warstw), konstruktor, nazwy, komentarze mówiące „dlaczego”
     (AGENTS.md: bez historii w kodzie), ograniczone zapytania, brak duplikatów logiki (np. dwa podobne przełączniki w
     `play-history.js`, `LibraryKey`).
   - Teksty `messages*.properties`: escape'y `\uXXXX`, typograficzne cudzysłowy i apostrofy jak w reszcie, PL bezosobowo i w tonie
     panelu, EN naturalny; `ImpersonalPolishTextsTest`.
   - Dokumenty: `docs/context` (05-1d, 05-1f, 05-1g, 06-1, 07-09, 10-2, 11-12) zgodne z kodem, końce linii (CRLF tam, gdzie były).

3. Czy wygląda profesjonalnie i czy jest czytelne i łatwe dla DJ-a (i gości)?
   - Obejrzyj na zrzutach (Playwright, `deviceScaleFactor: 2` dla zrzutów dla ludzi): karta programu w panelu przy 800 i 1280 px (trzy
     elementy pod komputerem: „Ostrzeż po”, „Pokazuj na TV i kiosku…”, „Zapisuj set wieczoru” — czy to nie za dużo tekstu, czy kolejność
     i opisy są jasne, czy DJ zrozumie różnicę między nimi), TV 1280×720 i 1920×1080 z paskiem (długi tytuł), kiosk 1180×820, strona
     gościa na telefonie 390 px z rozwiniętym „Co to było?”, komunikat „już leciała o …” na stronie wyniku (telefon i kiosk),
     podsumowanie z „Cały set” (telefon, 1280, wydruk), poranny e-mail.
   - Porównaj z sąsiednimi kartami i stronami: tokeny `app.css`, klasy, odstępy, wielkości przycisków (44 px na telefonie).
   - `python tools/check.py audit -t tv,kiosk,guest,summary,dashboard` (320 / 390 / 1280 px).
   - Oceń oczami DJ-a na weselu: czy wie, co się stanie po włączeniu / wyłączeniu każdego przełącznika; czy gość rozumie „już leciała
     o 21:30” i nie czuje się zbyty; czy „Co to było?” jest łatwe do znalezienia, ale nie przeszkadza w wysłaniu prośby.

4. Czy „Jak działa Scan2Play” (przewodnik `guide_pl.html` / `guide.html`, `/how-it-works`) jest zaktualizowany?
   - Sekcja `#deck`: „Teraz leci i set” i prywatność — czy opisuje wszystko (pasek na TV i kiosku z przełącznikiem, „Zapisuj set
     wieczoru”, „Już leciało”, „Co to było?”, „Cały set”, 30 dni) prawdziwie i prostym językiem, PL i EN tak samo.
   - Czy inne miejsca przewodnika (ekran TV, kiosk, strona gościa, podsumowanie wieczoru, limity „Bez powtórek”) powinny o tym
     wspomnieć, a nie wspominają; czy obrazki przewodnika są nieaktualne. `GuidePageRenderTest`.
   - Także: `/pro` (lista funkcji Pro), strona główna / landing, „Nowości” (`PanelNews`) — czy nowa funkcja powinna tam być.

URUCHOM: `python tools/check.py all` (w chmurze: `TMPDIR=/tmp/s2p`, `S2P_CHROME=/opt/pw-browsers/chromium-*/chrome-linux64/chrome`,
`pg_ctlcluster 16 main start`; gdy *IT nie mogą się zalogować: `ALTER USER postgres PASSWORD '1111'` w PostgreSQL kontenera).
`kiosk-full-fits-820x1180` był czerwony już przed tą pracą — sprawdź, czy nadal tylko on.

WYNIK: zapisz przegląd w `docs/history/review-2026-11-27-now-playing-and-set-2.md`, w stylu poprzednich `docs/history/review-*.md`
(po polsku: „Wynik w skrócie”, „Testy i sprawdzenia”, punkty P1… z plikiem:linią, wagą — błąd / ważne / drobne — dlaczego i
proponowaną poprawką; tabela mutacji; zrzuty w `docs/history/review-2026-11-27-shots-now-playing-2/`). Zacommituj i wypchnij
na gałąź tej sesji (nie na `claude/optimistic-hopper-20ct09`, nie do `main`), bez PR-a. Na końcu napisz mi po polsku krótko:
odpowiedź na każde z czterech pytań (tak / nie / częściowo i dlaczego), najważniejsze punkty do poprawy w kolejności i czy PR-y
#319 / #320 można zmergować przed poprawkami.
