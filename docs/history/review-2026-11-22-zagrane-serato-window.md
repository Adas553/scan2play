# Review 2026-11-22 — „Zagrane samo”: Serato 5.1, przegląd folderów, nowe okno programu (PR #301 / #302)

Zakres: `git diff origin/main...origin/claude/gallant-ritchie-rl80ux` (4 commity po scaleniu #299: `9024faf`, `64a3365`, `b56427c`,
`29b5c91`), dla kontekstu #299 (`f34a7b1`: wariant E usunięty, karta przebudowana, `SingleInstance`). Poprawki na gałęzi
`claude/friendly-maxwell-k4qu8h` (z `claude/gallant-ritchie-rl80ux`), bez PR.

## Werdykt

**Scalić po poprawkach** — poprawki z sekcji „Poprawki” są już na tej gałęzi (wystarczy ją scalić do #301 / #302 albo zamiast nich).
Bez nich: długa nazwa piosenki wypychała okno programu poza ekran (P1), a „Wskaż folder historii…” mogło zamrozić okno na do 25 s
przy słabym internecie (P2). Jedno ryzyko zostaje do decyzji po próbie na prawdziwym Serato: **P3** (Serato 5.1 bez kolumny
`played` — piosenka tylko załadowana na deck może oznaczyć prośbę).

## Ustalenia (od najważniejszych)

### P1 — błąd (poprawione): długa nazwa piosenki wypycha okno poza ekran

`deck-agent/.../Window.java` (wiersz „OZNACZONE”: `text(song.song(), …)`; też „Wskazany folder: <ścieżka>”). Okno jest pakowane do
treści, a nazwa nie miała granicy. Scenariusz (sprawdzony pod Xvfb, prawdziwy program): prośba „Zespół Pieśni i Tańca Mazowsze feat. …
(wersja rozszerzona…, extended club mix)” oznaczona → okno ma 1174 px, na ekranie 1280 px wychodzi za prawą krawędź; „Odłącz ten
komputer” i „sprawdzone 07:24:37” są poza ekranem. Nazwy próśb pochodzą od gości (do 500 znaków na pole). **Poprawka:** `Window.shortened`
— ponad 60 znaków ucięte z „…”, cała nazwa w podpowiedzi (tooltip); to samo dla ścieżki folderu. Po poprawce okno 526 px
(`program-05-dluga-nazwa.png`). Test `WindowTest.aLongSongName_isCut_soTheWindowStaysOnTheScreen` (czerwony przed).

### P2 — ryzyko (poprawione): „Wskaż folder historii…” czekało na wywołanie serwera

`Agent.java:170` (`synchronized void useFolder`), wołane z wątku okna (`Main.java:90–98`, także „Nie czytaj go”). `tick()` trzyma ten
sam zamek przez całe wywołanie `/api/device/plays` (połączenie do 10 s + odpowiedź do 15 s). Scenariusz: sala z kiepskim Wi-Fi, DJ
klika „Wskaż folder historii…” i wybiera folder → okno nie reaguje (nie maluje się) do 25 s, wygląda na zawieszone. **Poprawka:**
`picked` jest `volatile`, `useFolder` bez zamka (pętla czyta listę raz na początku). Test `AgentTest.pickingAFolder_doesNotWaitForASlowServer`
(serwer, który wisi; czerwony przed).

### P3 — ryzyko (otwarte, pytanie do właściciela): Serato 5.1 bez `played` — piosenka załadowana, nie zagrana

`Serato4Database.java:308` (`played = 1` tylko gdy kolumna jest; w 5.1 jej nie ma). To P30 z poprzedniego review, teraz ostrzej: w
Serato 4 `played = 0` odsiewało piosenki tylko załadowane; w 5.1 nie ma czym odsiać. Scenariusz: gość prosi o „Szampan”, DJ ładuje go na
drugi deck, żeby przesłuchać na słuchawkach, i rezygnuje → jeśli Serato dopisuje wiersz `history_entry` przy załadowaniu, program wysyła
piosenkę, serwer oznacza prośbę jako zagraną i znika ona z kolejki (fałszywe trafienie — gorsze niż pominięcie). **Propozycja:** próba
u właściciela (5 minut): w Serato DJ Lite 5.1 załadować piosenkę na deck **bez puszczania**, odczekać minutę i sprawdzić w oknie programu
„OZNACZONE”, czy przyszła (albo w panelu Serato „History”, czy wiersz jest). Jeśli jest: (a) piosenka z Serato bez `played` idzie
dopiero, gdy w tej samej sesji pojawi się następna (czyli tę grano przed kolejną) albo po N minutach w historii; (b) albo tylko komunikat
w karcie. Decyzja po próbie.

### P4 — ryzyko (otwarte, propozycja): przegląd folderów i PowerShell przed blokadą i oknem

`Main.java:43` (`Platform.current()`: PowerShell do 10 s + `Platform.look`) przed `onlyCopy()` (`Main.java:54`) i przed oknem.
Scenariusz: DJ klika dwa razy „Scan2Play Zagrane”; przez kilka sekund nic nie widać, klika znowu; druga kopia też uruchamia PowerShell
i cały przegląd, zanim się dowie, że jest druga. Pomiar przeglądu: drzewo 30 633 folderów, Linux, pamięć podręczna ciepła — 200–290 ms;
na Windows z zimnym dyskiem i NTFS spodziewam się kilku sekund (niezmierzone). **Propozycja:** najpierw `onlyCopy()`, potem okno, a
przegląd w tle (`Agent` dostaje znalezione źródła później, jak `useFolder`). Nie zrobione: zmiana kolejności startu bez dobrego testu i
bez Windows — do zrobienia z próbą na Windows.

### P5 — ryzyko (otwarte, propozycja): wspólny limit 30 000 folderów zużywa profil, zanim dojdzie do Muzyki

`Platform.java:260–268` (`lookIn`: profil, LOCALAPPDATA, APPDATA, Muzyka, Dokumenty, OneDrive) i `look`. Profil jest przeglądany pierwszy
i na 4 poziomy obejmuje też `AppData` (który potem jest przeglądany drugi raz jako własny korzeń); `AppData\Local\Packages` ma setki
folderów po kilka poziomów, folder programisty (`node_modules`, `.gradle`) dziesiątki tysięcy. Scenariusz: limit wyczerpany w profilu
albo w LOCALAPPDATA → Muzyka, Dokumenty i OneDrive nieprzejrzane, historia w nietypowym miejscu tam nieznaleziona (zwykłe miejsca dalej
działają). **Propozycja:** w profilu pomijać poddrzewa, które są osobnymi korzeniami (AppData) i znane ciężkie nazwy (`Packages`,
`node_modules`, `.git`); Muzykę i Dokumenty przeglądać przed AppData; albo limit na korzeń. Test `theLook_stopsAfterSoManyFolders` pokazuje
dziś to zachowanie (drugi korzeń nieosiągnięty po wyczerpaniu limitu).

### P6 — drobiazg (otwarte, wyjaśnienie): junctiony Windows

`Platform.java:286` (`walkFileTree` bez `FOLLOW_LINKS`). Java widzi junction (np. „Application Data”, „Moje dokumenty” w profilu) jako
folder, nie link, więc próbuje go otworzyć — te systemowe mają zabronione listowanie, kończą się na `visitFileFailed` i są pomijane (bez
zawieszenia). Junction założony przez użytkownika na inny dysk byłby przejrzany, ale w granicach głębokości i limitu. **Nie poprawiać**
przez `attributes.isOther()`: foldery OneDrive „na żądanie” też są punktami ponownej analizy (reparse points) — taka poprawka ukryłaby
historię w OneDrive (P24). Pliki OneDrive nie są ściągane: przegląd czyta tylko listy folderów i atrybuty (`Files.isRegularFile`), żadnego
pliku nie otwiera. Dyski sieciowe: korzenie to foldery profilu; linki symboliczne nie są śledzone (test `theLook_doesNotFollowLinks`).

### P7 — drobiazg (poprawione): teksty w 1. osobie

`Window.java`: „Nie widzę historii VirtualDJ ani Serato…”, „Połączono — czekam na historię…”, „W tym folderze nie widzę historii…”.
Aplikacja mówi bezosobowo. Teraz: „Brak historii VirtualDJ i Serato. Zagraj w programie DJ-a jedną piosenkę albo wskaż folder, w którym
trzyma historię.”, „Połączono — brak historii VirtualDJ i Serato”, „W tym folderze nie ma historii — …”. Test
`WindowTest.theTexts_areImpersonal` (czerwony przed). `docs/context/05-1d-…` poprawione.

### P8 — drobiazg (poprawione): okno wyboru folderu po angielsku

`Window.pick()`. Java nie ma polskich tekstów swoich okien: „Look In”, „Folder name”, „Files of Type”, „Cancel” obok polskiego
„Wybierz” — tak samo na polskim Windows. Teraz `Window.polishDialogs()` (wołane w `Main.theme()`): „Szukaj w:”, „Nazwa folderu:”,
„Pliki typu:”, „Anuluj”… (`program-09-okno-wyboru-folderu.png`). Test `WindowTest.theFolderChooser_speaksPolish` (czerwony przed).

### P9 — drobiazg (otwarte, pytanie): szerokość okna skacze ze stanem

`Window.repack()`. Szerokość zależy od linii stanu: 492 px („Działa”), 531 (brak historii), 589 (brak serwera), 620 (bez Pro).
Scenariusz: słabe Wi-Fi na sali — stan przełącza się „Działa” ↔ „Brak połączenia”, okno co kilkadziesiąt sekund zmienia szerokość.
**Propozycja:** okno nigdy nie węższe niż było (repack tylko rośnie) albo stała najmniejsza szerokość ~620 px. Decyzja o wyglądzie.

### P10 — testy (poprawione): cztery testy nie łapały błędu, którego dotyczyły

Sprawdzone mutacjami w kopii (zmiana kodu → czy test czerwony):
- głębokość przeglądu 4 → 6: `theHistories_areFound…` zielony („za głęboko” był na 6. poziomie). Teraz „za głęboko” = o jeden poziom
  za dużo; czerwony przy 6.
- „z dwóch baz Serato ta zapisana ostatnio” → „pierwsza z listy”: zielony (najnowsza była pierwsza). Teraz druga część testu z
  nowszą bazą w `%APPDATA%`; czerwony.
- brakował test, że folder wskazany = zwykłe miejsce nie wysyła piosenki dwa razy: `aFolderPicked_thatIsAlsoAUsualPlace_sendsEachSongOnce`
  (czerwony, gdy klucz „już widziane” jest per źródło zamiast per program). Wynik dziś: piosenka idzie raz, w „CZYTA” jedna linia.
- brakowały testy linków i limitu: `theLook_doesNotFollowLinks` (czerwony z `FOLLOW_LINKS`), `theLook_stopsAfterSoManyFolders`
  (`Platform.look(roots, max)`; czerwony bez limitu).
Pozostałe nowe testy #301 (`serato5_…`, `aFolderThePickedDj…`, `aHistoryThatCannotBeRead…`, `windows_serato_…LocalAppData`,
`theMarkedSongs_theLastFive…`) — czerwone po cofnięciu poprawki. Niezłapane i zostawione: kolejność sesji `start_time DESC, id DESC`
vs samo `id` (w praktyce zgodne).

### P11 — drobiazg (otwarte, sprzed #301): jedno za długie pole gubi całą paczkę 20 piosenek

`ServerClient.java:62` (400 traktowane jak „przyjęte”) i `DeviceApiController.validPlays` (pole > 500 znaków → 400). Scenariusz: jedna
piosenka z tytułem ponad 500 znaków (zepsuty tag) w paczce → serwer odrzuca całość, program ją porzuca — 19 innych piosenek nie oznacza
próśb. Rzadkie. **Propozycja:** program ucina pola do 500 znaków w `Json.plays`.

### P12 — drobiazg (pytanie): teksty karty i okna o Serato

Karta: „historię VirtualDJ i Serato (także Serato 4)” — program czyta teraz też Serato 5.1; nawias można usunąć. Okno, „OZNACZONE”
puste: „z VirtualDJ około 45 sekund po puszczeniu” — także wtedy, gdy czytane jest tylko Serato (dla Serato czas nieznany do P3).

## Sprawdzone i w porządku

- **Dane do serwera:** program wysyła tylko `/api/device/pair` (kod, nazwa komputera — polityka prywatności to mówi), `/plays`
  (wykonawca, tytuł, czas, `sentAt`) i `/unpair`. Ścieżki: `Played` nie ma pola ścieżki; VirtualDJ — tylko znaczniki `artist`/`title`/
  `lastplaytime`; Serato `.session` — pola tytuł/wykonawca/start/played; Serato SQLite — `artist`, `name`, `start_time` (kolumna
  `file_name` nie jest wybierana). Folder wskazany zostaje w `~/.scan2play/zagrane.properties` (plik nieotwierany w tym review). Okno
  pokazuje ścieżkę tylko lokalnie.
- **`Serato4Database`:** połączenie tylko do odczytu, `busy_timeout` 3 s; zapytanie z `PRAGMA table_info` — nazwy kolumn z bazy są tylko
  porównywane z ustalonymi nazwami (`contains`), do SQL nie trafia żaden tekst z bazy; nazwy tabel stałe. Odczyt w trybie WAL nie blokuje
  zapisów Serato. sqlite-jdbc rozpakowuje swoją bibliotekę do `%TEMP%` przy pierwszym użyciu — zwykłe zachowanie, antywirus może ją
  raz przeskanować.
- **Serato 5.1, logika sesji:** najnowsza sesja (`start_time DESC, id DESC`, `end_time` niewymagane) — po północy to ta sama sesja; dwie
  sesje jednej nocy / restart Serato: nowa sesja ma nowe wiersze, stare już wysłane są w „widzianych” (klucz program + czas + wykonawca +
  tytuł), nic nie idzie drugi raz; restart programu: pierwsze czytanie pomija piosenki starsze niż start − 10 min („wcześniejszy set”).
  Dwie bazy (Local i Roaming): czytana zapisana ostatnio, przełączenie nie wysyła duplikatów.
- **`SingleInstance`:** `tryLock` na `~/.scan2play/zagrane.lock`, zwolnienie przy końcu procesu; brak możliwości zrobienia blokady nie
  blokuje startu. (Kolejność w `main` — P4.)
- **Endpointy:** `POST /dj/dashboard/plays` usunięty — nic go nie woła (grep `src/`, `deck-agent/`: tylko test 404/405 i scenariusz
  „panel nic nie wysyła”). `/dj/devices*`: `StaffPermission.QUEUE` przez `sessionHelper.require`, `GET` i `/code` z `PlanFeature.DECK`,
  `/remove` bez planu (zamierzone); POST-y z nagłówkiem CSRF (scenariusz sprawdza token). `GET /dj/devices`: `WHERE party_code = ? …
  LIMIT 3`, indeks `idx_dj_device_party` (V78) — wczytanie + każdy powrót do karty + co 5 s przez ≤ 10 min przy kodzie: pomijalne nawet
  przy wielu otwartych panelach. `/api/device/*` bez zmian.
- **Karta w panelu:** trzy stany (bez komputera — trzy kroki; kod czeka — kod duży, „Kopiuj” kopiuje „123456” bez spacji, serwer i tak
  usuwa nie-cyfry; z komputerem — jego wiersz, przycisk zielony od wczytania). `visibilitychange` odświeża tylko przy powrocie do karty
  przeglądarki. Wygląd spójny z „Impreza” i „Kod QR”, bez przewijania w bok przy 800 i 1280 px, bez błędów JS.
- **Okno programu:** kolory `app.css`, znak Scan2Play, kropka stanu + słowo — w 2 sekundy widać, czy działa (zielone „Działa — połączono
  z imprezą”, inaczej żółte/czerwone z powodem). Listy przebudowywane tylko przy zmianie (brak migotania co 3 s); co 3 s zmienia się
  tylko godzina „sprawdzone” (cyfry o stałej szerokości — bez skakania). 5 ostatnich + licznik.
- **Efektywność:** przegląd 30 633 folderów 200–290 ms (Linux); odczyt `master.sqlite` z 200 000 wierszy historii i 1500 sesjami (bez
  indeksu na `session_id`) 15 ms — nawet odczyt przy każdym spojrzeniu (20/min) to ok. 0,3 s CPU na minutę. Pętla co 3 s: kilka małych
  list, `distinct` na ≤ 4 elementach — pomijalne.

## Poprawki (na tej gałęzi, każda z testem widzianym najpierw na czerwono)

1. P1: `Window.shortened` / `longText` — piosenka i folder > 60 znaków ucięte z „…”, całość w podpowiedzi.
2. P2: `Agent.picked` volatile, `useFolder` bez zamka.
3. P7: teksty bezosobowe (`Window.NO_HISTORY`, `NOT_IN_FOLDER`, linia stanu).
4. P8: `Window.polishDialogs()` — polskie teksty okna wyboru folderu i okien komunikatów.
5. P10: testy wzmocnione i dodane (głębokość, nowsza z dwóch baz, jedno wysłanie, linki, limit; `Platform.look(roots, max)`).

Sprawdzenia (w kopii, TMPDIR=/tmp/s2p): `check.py unit` 1530/1530, `browser` 470/470, `audit` — AUDIT_RESULT; program `verify` 42/42
(było 35). `it` nie uruchamiane: bez zmian SQL aplikacji i migracji.

## Pytania do właściciela

1. P3: próba w Serato DJ Lite 5.1 — czy piosenka tylko załadowana (niepuszczona) trafia do historii? Od tego zależy, czy Serato 5 może
   oznaczać sam.
2. P9: okno programu o stałej szerokości (nie skacze przy zmianie stanu)?
3. P12: usunąć „(także Serato 4)” z karty?
4. P4 / P5: przegląd folderów w tle i mądrzejsza kolejność — zrobić przy najbliższej próbie na Windows?

## Zrzuty ekranu

`docs/history/review-2026-11-22-shots/` — program pod Xvfb (prawdziwy program, udawany serwer, czcionki kontenera, nie Windows):
`program-01-przed-polaczeniem.png`, `-02a-pusty-kod`, `-02b-zly-kod`, `-03-polaczony-bez-historii`, `-04-polaczony-z-piosenkami`
(6 oznaczonych, widać 5 i licznik), `-05-dluga-nazwa` (po P1), `-06-nie-da-sie-odczytac`, `-07-bez-pro`, `-08-brak-serwera`,
`-09-okno-wyboru-folderu` (po P8); panel (stand-in `server.py`): `panel-800-bez-komputera.png`, `-kod-czeka`, `-z-komputerem`, to samo
`panel-1280-*`.

## Czego nie dało się sprawdzić

Windows i Mac (czcionki, okno wyboru folderu systemu, junctiony, OneDrive „na żądanie”, czas przeglądu na zimnym dysku NTFS, antywirus),
prawdziwa baza Serato 5.1 z danymi (P3).
