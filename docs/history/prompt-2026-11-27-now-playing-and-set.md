# Prompt do nowej sesji: „Teraz leci” na TV i set wieczoru (2026-11-27)

Do wklejenia w nową sesję Claude Code (w chmurze albo lokalnie):

---

Przeczytaj CLAUDE.md (z AGENTS.md) i „Start here” w SESSION_HANDOFF.md, potem w docs/ideas.md punkty 13 (co odłożono i dlaczego: „Co to było?”, „Leci Twoja piosenka!”), 18–21 i w docs/context/05-1d-panel-heading-queue.md akapity o „Program na komputerze” i „"Zagrane samo" watched (V81)”. Resztę dokumentów czytaj tylko w potrzebnych kawałkach (agent `context-finder`); testy przez agenta `test-runner` albo `python tools/check.py`.

Zacznij od gałęzi `claude/funny-hawking-brlqnj` (PR-y #315 do `main` i #316 do `dev`, jeszcze niezmergowane; V81). Utwórz swoją gałąź z niej. Jeśli w chwili startu #315 jest już w `main`, zacznij od `main`.

**Zadanie: zbuduj dwie funkcje z listy pomysłów do „Zagrane samo” (punkt 21 w docs/ideas.md, „Not built”: 3 i 4).**

**3. „Teraz leci” na ekranie TV** (`tv.html`, `tv.js`, `TvScreenController`). Gdy program DJ-a oznaczy prośbę gościa jako zagraną (`DeckHistoryService.markPlayed` → `/api/device/plays`), ekran TV pokazuje przez kilka minut pasek „🎶 Teraz: <piosenka>”, z dedykacją, jeśli jest i dedykacje są włączone. Pasek znika po następnej piosence albo po czasie. Tylko prośby oznaczone przez program, nie ręczne „Zagrane” (ręczne bywa klikane później, więc „teraz” byłoby nieprawdą). Musisz to sprawdzić w kodzie i może być potrzebna kolumna. TV i tak odświeża się sam, więc nie dokładaj nowego odpytywania ani na stronie gościa, ani na TV (ten koszt był powodem odłożenia „Leci Twoja piosenka!”). Rozważ to samo na kiosku, jeśli jest tanie i spójne.

**4. Set wieczoru zapisany** (dziś piosenka, o którą nikt nie prosił, jest od razu odrzucana). Program wysyła wszystkie piosenki, więc serwer może je trzymać na czas imprezy. Wykorzystanie:
- (a) **„Już leciało o 21:30”**: gość prosi o piosenkę, która leciała tego wieczoru, i dostaje to od razu, przed AI. Oszczędza to też wywołanie Gemini.
- (b) **„Podsumowanie wieczoru”** (`EveningSummaryService`, `summary.html`, poranny e-mail) z całym setem, nie tylko z prośbami.
- (c) **„Co to było?”** dla gości: ostatnie kilka piosenek na stronie gościa, ładowane dopiero po rozwinięciu, bez odpytywania.

Nigdy w „Trendy próśb” (punkt 16). Ograniczenia: limity jak w `DeckHistoryService` (≤ 20 na wywołanie, 600 dziennie na urządzenie), zapytania ograniczone (Top N) z indeksami, sprzątanie starych wierszy nocnym zadaniem jak `SongRequestRetentionService`, polityka prywatności PL / EN zmieniona w tym samym PR.

**Najpierw decyzje właściciela.** Zanim napiszesz kod, zadaj mi jednym `AskUserQuestion` decyzje, przy każdej z Twoją rekomendacją jako pierwszą opcją. Moje wstępne propozycje (sprawdź je w kodzie, zanim je polecisz):
- set zapisywany: domyślnie włączony w Pro z przełącznikiem w karcie programu albo tylko po włączeniu;
- jak długo trzymany: do porannego podsumowania, 7 dni albo 30 dni jak prośby;
- „Już leciało”: tylko informuje i nie dodaje prośby, albo informuje z przyciskiem „Poproś jeszcze raz”; od ilu godzin piosenka „leciała dawno”;
- czy „Co to było?” pokazuje cały set, czy tylko ostatnie N (np. 10) piosenek;
- czy „Teraz leci” pokazuje dedykację na TV.

**Jak pracować — profesjonalnie, czytelnie, efektywnie:**
- Kolejność: najpierw 3 (mniejsze, osobny commit), potem 4. Każda część działa i jest przetestowana sama.
- Trzymaj się istniejącej struktury: małe, skupione serwisy jak `DeckWatchService`, bez nowych frameworków i zbędnych warstw. Komentarze mówią „dlaczego” (AGENTS.md). Teksty PL / EN w `messages*.properties` z escape'ami `\uXXXX` przez skrypt (CLAUDE.md), bezosobowo, w tonie panelu. Wygląd przez tokeny `app.css` i klasy jak w sąsiednich kartach.
- Baza: każda zmiana schematu to nowa migracja (V82, …), tylko dodaje. SQL liczący albo zapisujący sprawdzony testem `*IT` na prawdziwym PostgreSQL (nowy `*IT` dla zapisu setu, odczytu „Już leciało” i sprzątania), indeksy dobrane do zapytań i sprawdzone przez `EXPLAIN` na throw-away bazie.
- Wydajność: policz koszt przy 1000 równoczesnych imprez. Ile zapisów na minutę daje zapis setu, ile odczytów daje „Już leciało” przy każdej prośbie gościa, czy potrzebny jest cache (Caffeine, ograniczony), czy TV nie dokłada zapytań przy każdym odświeżeniu.
- Testy: jednostkowe, `*IT`, scenariusze przeglądarkowe dla TV, strony gościa i karty. Każdy nowy scenariusz widziany najpierw czerwony. Na koniec `check.py all` i `check.py audit` dla zmienionych stron (320 / 390 / 1280 px). Obejrzyj zmienione ekrany na zrzutach (TV, strona gościa na telefonie, podsumowanie, karta).
- Dokumenty w tym samym PR: `docs/context` (właściwe pliki, `10-2-migrations.md`), przewodnik `#deck` PL / EN, punkt w `docs/ideas.md`, wpis w „Start here” w SESSION_HANDOFF.md (krótko).
- Na koniec zrób samodzielne review swoich zmian (jak `docs/history/review-*.md`: punkty P1…, `plik:linia`, waga), zapisz je w `docs/history/review-<data>-now-playing-and-set.md` i popraw, co znajdziesz.
- Zacommituj i wypchnij na swoją gałąź. Załóż PR-y do `main` i do `dev` i napisz w nich, że zależą od #315 / #316, jeśli te nie są jeszcze zmergowane. Commity i opisy PR po angielsku.

Na końcu napisz mi po polsku krótko: co zrobione, jakie decyzje przyjęto, co sprawdzić na telefonie i TV i co czeka po merge'u (Flyway, „Run workflow” deck-agent, jeśli program się zmienił).
