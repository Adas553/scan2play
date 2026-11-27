# Prompt do nowej sesji: review „Zagrane samo — ostrzeżenie i test przed imprezą” (2026-11-27)

Do wklejenia w nową sesję Claude Code (w chmurze albo lokalnie):

---

Przeczytaj CLAUDE.md (z AGENTS.md) i „Start here” w SESSION_HANDOFF.md. Potem przeczytaj w docs/context/05-1d-panel-heading-queue.md
akapit „"Zagrane samo" watched (V81…)” oraz to, co jest przed nim o „Program na komputerze”, i punkty 19–21 w docs/ideas.md.
Gałąź: `claude/funny-hawking-brlqnj` (od `main` = `2912c2d`), PR-y https://github.com/Adas553/scan2play/pull/315 (`main`) i https://github.com/Adas553/scan2play/pull/316 (`dev`).
Zmiany: `git diff origin/main...HEAD`.

**Co zostało zrobione** (punkt 21 w docs/ideas.md):

(1) **Ostrzeżenie.** Każdy komputer w karcie „Z programu DJ-a” pokazuje swój stan (`DeckWatchService.State`):
- `OK`: „widzi piosenki · ostatnia 21:43 (VirtualDJ)”;
- `WAITING`: nic w 12 h;
- `SILENT`: ostatnia piosenka starsza niż wybrane minuty;
- `OFFLINE`: brak wywołania przez 3 min.

Żółte ostrzeżenie (`warn`) pojawia się tylko „w trakcie imprezy”: impreza otwarta, piosenka w 12 h i prośba gościa w 60 min. Żółty wiersz zmienia przycisk nad kolejką na żółty. „Ostrzeż mnie, gdy program nie widzi piosenek: po 10 / 20 / 30 min / nigdy” (domyślnie 20; `POST /dj/devices/silence`, `party_settings.deck_silence_minutes`, celowo niezmapowane w encji). Zadanie `@Scheduled` co minutę jednym zapytaniem (`silentNow`, ≤ 100 imprez) wysyła push organizatorowi (`PushNotificationService.notifyDeckSilent`). Push idzie raz na ciszę; pamięć jest w Caffeine, więc restart może go powtórzyć. Karta odświeża listę co minutę i odrzuca odpowiedź starszą niż nowsze zapytanie.

(2) **Test przed imprezą.** Komputer w stanie `WAITING` ma w karcie podpowiedź „puść dowolny utwór na minutę”. W oknie programu (`deck-agent/`, `Window`) jest sekcja „OSTATNIA PIOSENKA” (`Agent.Heard`; tylko na komputerze).

**Pozostałe zmiany:**
- Program wysyła przy każdej piosence `program` (`Played.from`, `Json.plays`).
- Serwer zapisuje tylko godzinę najnowszej piosenki i nazwę programu (`dj_device.last_play_at` — nigdy wstecz —, `last_play_program`; V81, tylko dodaje).
- `GET /dj/devices` zwraca teraz `{computers, silenceMinutes}` zamiast tablicy.
- Polityka prywatności PL / EN, przewodnik `#deck` PL / EN i teksty w `messages*.properties` zmienione.

**Testy:**
- Jednostkowe 1568/1568, `*IT` 233/233 (z nowym `DeckWatchIT`).
- Przeglądarkowe 486/487 (`kiosk-full-fits-820x1180` czerwony też na `main` w kontenerze); nowe scenariusze `deck-computer-lines` i `deck-silence-choice`.
- deck-agent 89/89.

Zrób review tych zmian i zapisz je w `docs/history/review-2026-11-27-deck-watch.md`. Punkty P1, P2, …; każdy ma zawierać:
- co jest nie tak i gdzie (`plik:linia`);
- dlaczego to problem, z konkretnym scenariuszem;
- propozycję poprawki;
- wagę: błąd / ważne / drobne.

Oceń cztery rzeczy:

1. **Czy kod jest dobry i poprawny.**
   - `DeckWatchService`: reguły stanów i „w trakcie imprezy”, granice czasu (`isBefore` / `isAfter` na krawędziach), spójność między kartą (stan per komputer) a pushem (najnowsza piosenka całej imprezy).
   - Zapytanie `silentNow`: `HAVING` z `EXISTS`, `?::timestamptz - minutes * interval`, `LIMIT`, czy indeks częściowy V81 się przydaje (`EXPLAIN` na throw-away bazie).
   - Pamięć „raz na ciszę”: unieważnianie przy zmianie minut, kilka komputerów.
   - Kolejność kontroli planu Pro.
   - `DeviceApiController.newest` / `programOfNewest`: piosenki bez czasu, z przyszłości, ponownie wysłane po restarcie programu.
   - `DjDeviceService.notePlay`: warunkowy `UPDATE`, nazwa programu przycięta.
   - Bezpieczeństwo nowego endpointu (`QUEUE`, CSRF, Pro, wartości spoza listy).
   - W `deck-agent`: `Played` z czwartym polem (równość, `identity`, testy), `Heard` i wątki okna Swing.
   - Czy fałszywe alarmy są realne: pierwszy taniec, przemowy, tort, przerwa obiadowa, Spotify na pauzie, laptop uśpiony, impreza zostawiona otwarta.

2. **Czy działa i wygląda profesjonalnie i spójnie z projektem.**
   - Teksty PL / EN: ton jak reszta panelu, bezosobowe formy, długości, escape'y w `messages*.properties`.
   - Wygląd karty i nowego `select`: klasy Bootstrapa i tokeny `app.css` jak w innych kartach, kolory stanów, przycisk żółty / zielony.
   - Zachowanie na 320 / 390 / 1280 px: `python tools/check.py audit -t dashboard`; karta jest domyślnie zwinięta, więc obejrzyj ją rozwiniętą zrzutem z Playwrighta z danymi z `src/test/browser/server.py`.
   - Dostępność: etykieta `select`, czytniki ekranu, `aria-live`?
   - Okno programu: czy „OSTATNIA PIOSENKA” pasuje do „CZYTA” i „OZNACZONE”.
   - Push: tytuł, treść, tag, link.
   - Czy przewodnik, polityka prywatności i `docs/context` mówią to samo, co robi kod.
   - Komentarze zgodnie z AGENTS.md (dlaczego, a nie historia).

3. **Czy efektywność jest na dobrym poziomie.**
   - Koszt na serwerze: zadanie co minutę (jedno zapytanie, ile wierszy czyta przy 1000 imprez), `GET /dj/devices` co minutę z każdego otwartego panelu (3 zapytania: urządzenia, minuty, `EXISTS` na `song_requests` — czy użyje indeksu `idx_party_decision_time` / `idx_party_code`), dodatkowy `UPDATE` przy każdym `/api/device/plays` (czy nie zapisuje za często).
   - Czy coś warto połączyć w jedno zapytanie albo cache'ować.
   - Czy pamięć Caffeine jest ograniczona.
   - Koszt w przeglądarce (interwał tylko przy widocznej stronie) i w programie.

4. **Testy.**
   - Czy nowe testy naprawdę łapią błędy: zmień regułę (np. usuń warunek prośby gościa albo `p.active`) i sprawdź, że coś jest czerwone.
   - Czego brakuje: krawędzie minut, `OFFLINE` w pushu, ponowny alarm po zmianie minut, odpowiedź `/dj/devices/silence` 400.

Uruchom testy przez `python tools/check.py unit|it|browser` (w chmurze: `TMPDIR=/tmp/s2p`, `S2P_CHROME=…`, `pg_ctlcluster 16 main start`; jeśli PostgreSQL odrzuca hasło, ustaw w kontenerze hasło `postgres` na `1111`) albo przez agenta `test-runner`.

Najpierw tylko review, bez poprawek. Na końcu napisz mi po polsku krótkie podsumowanie: najważniejsze punkty, Twoją ocenę ogólną (czy można mergować) i co poprawić przed merge'em. Zapisany plik zacommituj i wypchnij na tę samą gałąź (bez nowego PR).
