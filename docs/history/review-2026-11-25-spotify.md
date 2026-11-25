# Przegląd 2026-11-25 — „Zagrane samo” ze Spotify w `deck-agent`

Zakres: gałąź `claude/gracious-bell-iap7ms` (od `main` = `0316624`), `git diff origin/main...HEAD`: commity `386f9b6`
(`SpotifyNowPlaying`, próba) … `6108fa1` (test karty w panelu), 17 plików, +602 / −32, bez migracji, serwer bez zmian.

## Wynik w skrócie

Kod jest porządny, pasuje do `Agent` i do reszty `deck-agent/`: jedna instancja na cały czas działania (holder `Platform.Spotify`,
klucz `versions` w `Agent` się nie zmienia po `useSources`), `version()` tylko z wątku agenta i `synchronized`, czytnik PowerShella
na swoim wątku z polami `volatile`, PowerShell nie zostaje sierotą dłużej niż ~2 s (sam sprawdza PID programu), dwóch naraz być nie
może (start tylko przy martwym, a `SingleInstance` pilnuje jednego programu), UTF-8 i BOM obsłużone, AppleScript nie uruchamia
Spotify, reklama `spotify:ad` pominięta, `plutil` przed `codesign`. Teksty PL / EN odpowiadają sobie, `messages*.properties` bez
znaków spoza ASCII, polityka prywatności prawdziwa. Testy `deck-agent` 61/61, audyt stron OK.

Do poprawy przed scaleniem — nic nie psuje oznaczania na Windows, ale DJ to zobaczy:
- **P1** długi tytuł ze Spotify poszerza okno na stałe (650 → 882 px w zrzucie, i nie wraca);
- **P2** długa pauza zachowuje stary początek piosenki — prośba złożona w czasie pauzy nie oznaczy się, a ta sama piosenka puszczona
  od nowa po godzinie nie liczy się wcale;
- **P3 / P4** dwa stany, w których okno mówi „Spotify (nic nie gra)”, choć muzyka gra (Mac bez zgody na sterowanie Spotify; Windows
  ze Spotify schowanym do zasobnika) — DJ nigdzie się nie dowie dlaczego;
- **P5** nowy link „Twojego programu DJ-a nie ma na liście?” zaprasza użytkownika rekordboxa / Traktora do wskazania folderu, który
  nic nie da; przewodnik i jego obrazek nazywają jeszcze stary napis.

Reszta drobna. Mac nadal niesprawdzony (P3, P12, P13 dotyczą tylko jego).

## Testy i sprawdzenia

- `mvn -f deck-agent/pom.xml -B -ntp verify` w kopii repo (`/opt/maven`): 61/61 (Agent 14, HistoryFormats 23, ServerClient 4,
  Settings 3, SingleInstance 2, SpotifyNowPlaying 9, Window 6).
- `tools/check.py unit -t DashboardPageRenderTest,…`: 148/148; `check.py audit -t guide,dashboard.html,dashboard-en`: 3 strony × 3
  szerokości, bez przewijania w bok i błędów (jeden mały cel „Details” w `dashboard-en` — był już wcześniej).
- Okno w xvfb (motyw z `Main.theme()` przez refleksję, klasa pomocnicza tylko w kopii): nic nie czytane, Spotify pauza, Spotify gra,
  VirtualDJ + Spotify, długi tytuł, potem krótki. Nowy link mieści się w 650 px i okna nie poszerza; poszerza je długi tytuł (P1).
- Przewodnik `#deck` PL / EN i karta „Zagrane samo z programu DJ-a” w 390 i 1280 px (Chromium): czytelne, nic nie wystaje.

Mutacje `SpotifyNowPlaying` (`-Dtest=SpotifyNowPlayingTest`):

| # | mutacja | wynik |
|---|---|---|
| M1 | pauza nie zeruje `lastHeard` (czas pauzy liczony jako słyszany) | czerwony ✓ |
| M2 | bez limitu `LONGEST_GAP` | czerwony ✓ |
| M3 | bez warunku `!counting.equals(lastPlayed)` | **przeżyła** (P7) |
| M4 | bez warunku `!countedOnce` | **przeżyła** (P7 — dwa warunki pilnują tego samego) |
| M5 | `>= PLAYED_AFTER` → `>` | czerwony ✓ |
| M6 | wersja niepusta przy wyłączonym Spotify | czerwony ✓ |
| M7 | czas zagrania = chwila 30. sekundy, nie pierwsze usłyszenie | czerwony ✓ |

## Punkty

### P1 — długi tytuł Spotify poszerza okno na stałe · ważne

- **Gdzie:** `deck-agent/src/main/java/com/scan2play/deck/Window.java:325` (wiersz „CZYTA” przez `text(source, …)`), razem z
  `repack()` / `grownOnly` (`Window.java:412`).
- **Dlaczego:** dotąd wiersz „CZYTA” miał krótką nazwę pliku („2026-11-25.m3u”). Teraz ma „Spotify (gra: Wykonawca - Tytuł)”, a
  tytuły w Spotify bywają długie („Queen, David Bowie - Under Pressure - Remastered 2011 / Live at …”). Okno rośnie do szerokości
  tytułu (zrzut: 650 → 882 px) i zgodnie z `grownOnly` już nie maleje — po jednym długim tytule zostaje szerokie do końca imprezy, a
  bardzo długi może wyjść poza mały ekran laptopa.
- **Poprawka:** wiersz źródła przez `longText(source, …)` (już jest: `shortened` do `LONGEST_LINE` = 60 znaków + całość w podpowiedzi),
  jak piosenki w „OZNACZONE”. Test w `WindowTest`: szerokość okna po długim tytule ≤ szerokość po krótkim (albo: etykieta skrócona).

### P2 — długa pauza: stary początek piosenki, a ta sama piosenka od nowa się nie liczy · ważne

- **Gdzie:** `SpotifyNowPlaying.java:84-107` (pauza zeruje tylko `lastHeard`, `counting` / `countingSince` / `countedOnce` zostają).
- **Dlaczego:** dwa realne przypadki DJ-a, który gra i z VirtualDJ, i ze Spotify:
  1. piosenka A gra 20 s, pauza, 45 minut z VirtualDJ, potem Play w Spotify na tej samej A → po 10 s A „zagrana” z czasem sprzed 45
     minut. Serwer (`DeckHistoryService.markPlayed`) nie oznacza prośby złożonej później niż czas zagrania + 2 min — prośby o A z tych
     45 minut zostają w kolejce, choć A właśnie poszła;
  2. A zagrana cała (policzona), pauza w środku następnej odsłony A albo DJ wraca do A po godzinie (ten sam tytuł w oknie, bez innej
     piosenki pomiędzy) → `countedOnce` / `lastPlayed` = A, drugie granie się nie liczy.
- **Poprawka:** zapamiętać chwilę pauzy; po powrocie tej samej piosenki po pauzie dłuższej niż np. 2 minuty
  (`DeckHistoryService.SLACK_CLOCK_KNOWN`) liczyć od nowa jak nową piosenkę (nowy `countingSince`, `heard` 0, `countedOnce` false,
  `lastPlayed` null). Krótka pauza (zatrzymanie na chwilę) zostaje jak teraz. Testy: oba przypadki; 05-1d opisuje regułę.

### P3 — Mac bez zgody na sterowanie Spotify: „nic nie gra” na zawsze, bez słowa dlaczego · ważne

- **Gdzie:** `SpotifyNowPlaying.java:268-282` (`MacSpotify.now()`: błąd `osascript` = pusty wynik), tekst
  `dashboard.computer.first.mac` (`messages_pl.properties:546`, EN odpowiednio) i przewodnik `#deck` („Pierwsze uruchomienie”).
- **Dlaczego:** gdy DJ kliknie „Nie pozwalaj” (albo pytanie zniknie — P12), AppleScript kończy się błędem -1743 („Not authorized to
  send Apple events to Spotify”), program pokazuje „✓ Spotify (nic nie gra)” przy grającej muzyce i nic się nie oznacza. Karta i
  przewodnik mówią o pytaniu macOS przy pierwszym uruchomieniu programu, ale nie o drugim pytaniu — „„Scan2Play Zagrane” chce sterować
  aplikacją Spotify” — więc DJ nie wie, że ma się zgodzić.
- **Poprawka:** `Player` zwraca też stan „brak zgody” (stderr `osascript` z `-1743` albo kod wyjścia ≠ 0 przy działającym Spotify) →
  `reading()` „brak zgody macOS — Ustawienia systemowe › Prywatność › Automatyzacja”, wiersz z „!” jak „nie da się odczytać”. Jedno
  zdanie w `dashboard.computer.first.mac` i w przewodniku: „Grasz ze Spotify? macOS zapyta też, czy program może sterować Spotify —
  pozwól (program tylko czyta, co gra).” Rozbiór odpowiedzi do testu jak `fromAppleScript`.

### P4 — Windows: Spotify schowany do zasobnika = „nic nie gra”, nigdzie nie powiedziane · ważne

- **Gdzie:** `SpotifyNowPlaying.java:160-163` (komentarz to wie), skrypt `:207-213`, przewodnik `guide_pl.html:269-271` / `guide.html`,
  okno (`reading()`, `:113-115`).
- **Dlaczego:** okno schowane do zasobnika (albo ukryte inaczej) nie ma `MainWindowTitle` — PowerShell pisze `T=` puste, program mówi
  „Spotify (nic nie gra)”, a muzyka gra; piosenki się nie oznaczają. Wiedza jest tylko w komentarzu i w 05-1d, DJ jej nie dostaje.
  (Okno zminimalizowane na pasek zadań tytuł ma — działa.)
- **Poprawka:** rozróżnić dwa stany, bo są rozróżnialne: pauza to tytuł „Spotify Premium” / „Spotify Free”, schowane okno to pusty
  tytuł przy działającym procesie. Dla pustego `reading()` „okno Spotify schowane — otwórz je, by program widział piosenki”. W
  przewodniku jedno zdanie przy Spotify: „Na Windows okno Spotify musi być otwarte (może być zminimalizowane).” Test rozbioru linii
  (P8).

### P5 — link „Twojego programu DJ-a nie ma na liście?” zaprasza nie tych DJ-ów; przewodnik mówi o innym napisie · ważne

- **Gdzie:** `Window.java:70-73` (`OTHER_FOLDER`, `OTHER_FOLDER_TIP`); `guide_pl.html:289` / `guide.html:279` (cytują „Wskaż folder
  historii…”); `src/main/resources/static/images/guide/program.webp` (na obrazku stary „Inny folder historii…”).
- **Dlaczego:** (a) DJ z rekordboxem, Traktorem albo djay, który ma otwarte Spotify, widzi „✓ Spotify (nic nie gra)” i pytanie „Twojego
  programu DJ-a nie ma na liście? Wskaż jego folder…” — to odpowiedź „tak, nie ma” i folder rekordboxa, a potem „W tym folderze nie ma
  historii”. Że chodzi tylko o VirtualDJ i Serato, mówi dopiero podpowiedź po najechaniu myszą (na ekranie dotykowym wcale). (b) Ze
  Spotify w „CZYTA” link pokazuje się teraz często (każdy DJ ze Spotify otwartym), a przewodnik każe szukać „Wskaż folder historii…”,
  którego wtedy nie ma; obrazek okna pokazuje trzeci napis.
- **Poprawka:** napis mówiący, kogo dotyczy: „VirtualDJ albo Serato nie na liście? Wskaż ich folder…” (mieści się jak obecny, ~430 px);
  podpowiedź bez zmian. W przewodniku oba napisy („Wskaż folder historii…” albo, gdy coś już czyta, „VirtualDJ albo Serato nie na
  liście?…”). Obrazek `program.webp` na nowo, gdy właściciel zrobi zrzut na Windows (najlepiej z wierszem Spotify).

### P6 — „Spotify wyłączony” brzmi jak opcja programu · drobne

- **Gdzie:** `Window.java:67` (`NO_HISTORY`) i `Window.java:368` (linia stanu).
- **Dlaczego:** „wyłączony” DJ może przeczytać jako „obsługa Spotify w programie wyłączona” (i szukać, gdzie ją włączyć). Chodzi o to,
  że aplikacja Spotify nie działa.
- **Poprawka:** „Spotify zamknięty” (obie linie): „Połączono — brak historii VirtualDJ i Serato, Spotify zamknięty”.

### P7 — reguła „ta sama piosenka zaraz po sobie” nietestowana, a jej uzasadnienie nieprawdziwe · drobne

- **Gdzie:** `SpotifyNowPlaying.java:20-21` (javadoc), `:52-54`, `:97-106`; test
  `SpotifyNowPlayingTest.theSameSongAgainStraightAfter_isNotASecondPlay_butAfterAnotherOneItIs` (`:95-109`).
- **Dlaczego:** pauza nie zmienia `counting`, więc „pauza dość długa, by odczytać ‘Spotify Premium’” nigdy nie robi z piosenki nowej —
  to robi już `countedOnce`. `lastPlayed` działa tylko w przypadku A → B (krótko, < 30 s) → A: drugie A się nie liczy. Test z pauzą
  przechodzi bez `lastPlayed` (M3), a bez `countedOnce` też (M4): oba warunki pilnują tego samego, a jedyny przypadek, w którym się
  różnią, nie ma testu.
- **Poprawka:** razem z P2 zdecydować regułę: albo `lastPlayed` zostaje (A → krótkie B → A = jedno granie — DJ „kliknął obok” i wrócił)
  i ma test tego przypadku, albo znika i zostaje tylko `countedOnce`. Javadoc opisuje to, co kod robi.

### P8 — czego brakuje w testach · ważne

- **Gdzie:** `SpotifyNowPlayingTest`, `Platform`, `WindowsSpotify.listen` (`SpotifyNowPlaying.java:227-239`).
- **Dlaczego / co dopisać:**
  - **jedna instancja**: `Platform.withoutAsking().sources()` i `Platform.current().sources()` mają tę samą instancję Spotify — cała
    racja holdera `Platform.Spotify`; dziś nic tego nie pilnuje (na Linuksie lista nie ma Spotify — test przez wstrzyknięcie albo
    metodę pakietową zwracającą holder);
  - **rozbiór linii PowerShella**: BOM, `T=`, `T=` puste (P4), linia bez `T=` — wydzielić `static Optional<String> titleOf(String line)`
    i testować; dziś `listen` niczym nie pokryte;
  - **agent nie czyta Spotify co 3 s**: wersja stała bez nowego zagrania (`songs()` wołane tylko przy zmianie);
  - **Spotify zamknięty i otwarty znów** w środku imprezy: nic nie wysłane drugi raz, licznik dalej;
  - **`KEPT`**: po 201 zagraniach lista 200, najstarsze odcięte;
  - **pusty wykonawca** (podcast, AppleScript „\tOdcinek”): `reading()` „gra: Odcinek”, `Played` z pustym artystą;
  - przypadki P2 i P7.

### P9 — PowerShell, który kończy się od razu, startuje co 10 s bez końca · drobne

- **Gdzie:** `SpotifyNowPlaying.java:185-199`.
- **Dlaczego:** gdy PowerShell żyje krótko (antywirus, polityka firmy, błąd skryptu), a Spotify działa, program co 10 s uruchamia nowy —
  każdy start to ~1 s CPU na laptopie DJ-a, przez całą imprezę. Mało prawdopodobne, ale niewidoczne.
- **Poprawka:** gdy poprzedni żył krócej niż np. 30 s, następna próba po minucie (podwajana do np. 10 min).

### P10 — `shell` czytane przez wątek zamknięcia bez `volatile` · drobne

- **Gdzie:** `SpotifyNowPlaying.java:170`, `:176-181`.
- **Dlaczego:** pole pisze wątek agenta, czyta hook zamknięcia; bez `volatile` hook może zobaczyć `null` i nie zamknąć PowerShella
  (który i tak skończy się sam po ~2 s — stąd drobne).
- **Poprawka:** `private volatile Process shell;`.

### P11 — BOM jako niewidoczny znak w źródle · drobne

- **Gdzie:** `SpotifyNowPlaying.java:230` (`line.replace("<U+FEFF>", "")` — znak wpisany dosłownie).
- **Dlaczego:** w edytorze wygląda jak `replace("", "")`; łatwo go zgubić przy edycji.
- **Poprawka:** `line.replace("﻿", "")` (escape w Javie) albo `strip` pierwszego znaku, z komentarzem „PowerShell's UTF-8 writes a BOM”.

### P12 — Mac: koszt i pytanie macOS w trakcie odczytu (do sprawdzenia na Macu) · drobne

- **Gdzie:** `SpotifyNowPlaying.java:262-283`.
- **Dlaczego:** (a) co 3 s `ProcessHandle.allProcesses()` (bez 10-sekundowej przerwy jak na Windows) i `osascript` (kompilacja skryptu,
  kilkadziesiąt ms CPU) — znośne, ale skrypt i tak sprawdza `application "Spotify" is running`; (b) gdy macOS pokazuje pytanie o
  sterowanie Spotify, `osascript` czeka na odpowiedź; po 5 s program go zabija i za 3 s pyta znów — wątek agenta stoi 5 s z każdych 8,
  a czy pytanie przeżyje zabicie `osascript`, nie wiadomo.
- **Poprawka:** (a) `running()` co 10 s jak na Windows (albo stan z odpowiedzi skryptu: „off” / „” / wynik); (b) sprawdzić na Macu;
  jeśli pytanie znika, `waitFor` dłuższy przy pierwszym odczycie albo pierwszy odczyt na osobnym wątku.

### P13 — `plutil -insert` i podpis ad hoc · drobne

- **Gdzie:** `.github/workflows/deck-agent.yml:100`.
- **Dlaczego:** `-insert` kończy się błędem, gdy klucz już jest (gdyby jpackage kiedyś go dodał albo krok poszedł dwa razy) —
  `-replace` działa w obu przypadkach. Osobno: podpis ad hoc zmienia się z każdym wydaniem, więc macOS może zapytać o zgodę na Spotify
  znów po każdej aktualizacji programu — warto to dopisać w 05-1d przy próbie na Macu.
- **Poprawka:** `plutil -replace NSAppleEventsUsageDescription -string …`; zdanie w 05-1d po próbie.

### P14 — komentarze: nieaktualny i historia w kodzie · drobne

- **Gdzie:** `Window.java:66` („no history of either program” — programy są trzy); `Window.java:327-328` („as "Inny folder historii…"
  alone did not” — to historia zmiany, AGENTS.md: do commita / `docs/history/`).
- **Poprawka:** „Read: no DJ program's history anywhere it is looked for, Spotify not running.”; „…a quiet link, saying when it is needed
  (only VirtualDJ or Serato elsewhere)”.

### P15 — dokumentacja: nieaktualne zdania, limity handoffu · drobne

- `docs/ideas.md:396-397`: „**Open:** the panel's card and the guide name VirtualDJ and Serato only — Spotify there once it works” —
  już nazywają; usunąć tę część.
- `docs/context/05-1d-panel-heading-queue.md:166`: „(the owner, 2026-11-25)” — kto i kiedy to historia (CLAUDE.md: kontekst = co jest
  teraz); decyzja z datą jest w `docs/ideas.md` punkt 20. Linia `:143` ma ~250 znaków (reszta pliku łamana ~135).
- `SESSION_HANDOFF.md:106-111`: punkt ma 6 linii (limit 4) i mówi jednocześnie „pushed, no PR” i „PRs to `main` and `dev`”. Cały plik
  19,0 KB (limit 15), „Start here” 14,1 KB (limit 4) — przekroczone już na `main` (18,2 / 13,4 KB): przy najbliższym wydaniu przenieść
  scalone punkty do `docs/history/session-handoff-<data>.md`.

### P16 — „Serato\Library” z ukośnikiem Windows także na Macu · drobne

- **Gdzie:** `Window.java:73` (`OTHER_FOLDER_TIP`), `guide_pl.html:289-291`, `guide.html` odpowiednio (i, wcześniej, `NOT_IN_FOLDER`).
- **Poprawka:** w oknie separator systemu (`File.separator`); w przewodniku „Serato › Library” albo „Serato/Library (Windows:
  Serato\Library)”.

### P17 — polityka prywatności: prawdziwa, można dopowiedzieć jedno · drobne (do decyzji)

- **Gdzie:** `privacy_pl.html:35`, `privacy.html:35`.
- **Ocena:** zdanie jest prawdziwe i pełne co do wysyłki (wykonawca, tytuł, godzina; nic z dysku; nie zapisujemy). Czytane lokalnie
  jest trochę więcej (tytuł okna, na Macu adres utworu do rozpoznania reklamy), ale nic z tego nie wychodzi. Można dopisać „bez
  logowania do Spotify i bez dostępu do konta Spotify” — rozwiewa pytanie, które DJ zada pierwsze. Nie jest to konieczne.

## Bez uwag (sprawdzone)

- Zgodność z `Agent`: wersja `spotify:N` zmienia się tylko przy nowym zagraniu; „seen” po `at|artist|title` (dwa granie tej samej
  piosenki mają różne `at`); pierwszy odczyt i reguła 10 minut nie odcinają niczego (czasy Spotify ≥ start programu); piosenka grająca
  przy starcie programu liczy się od startu — słusznie. Pusta wersja przy zamkniętym Spotify = brak wiersza w „CZYTA”.
- Wątek: wolny serwer (do 15 s na wywołanie) wydłuża odstęp między odczytami; `LONGEST_GAP` liczy wtedy 10 s — piosenka oznaczy się
  najwyżej trochę później, nie wcale.
- PowerShell: `Get-Process -Id <pid>` co 2 s kończy go ~2 s po zabiciu programu (także „Zakończ zadanie”, bez hooka); po zamknięciu
  Spotify kończy się sam; `-NoProfile -NonInteractive`, stderr odrzucony, stdin zamknięty. Koszt: jeden proces (~60–80 MB RAM,
  niemierzone) i dwa `Get-Process` co 2 s; `ProcessHandle.allProcesses()` co 10 s tylko, gdy PowerShell nie działa.
- Rozbiór tytułu: „Artist - Title - Remastered” → tytuł z „- Remastered” (serwer dopasowuje kluczem `LibraryKey`); pauza „Spotify
  Premium” / „Spotify Free” / „Spotify” i reklama „Advertisement” → nic. Tytuł pauzy to nazwa produktu, nie powinien zależeć od języka Windows ani
  Spotify (niesprawdzone; a gdyby był inny bez „ - ”, i tak wyjdzie „nic nie gra”).
- AppleScript: `if application "Spotify" is running` nie uruchamia Spotify; skrypt i tak wołany tylko przy działającym procesie.
- Workflow: `plutil` przed `codesign` (podpis obejmuje `Info.plist`).
- Teksty okna bezosobowe (`WindowTest.theTexts_areImpersonal` obejmuje nowe; „Twojego” / „Wskaż” to tryb jak „Zagraj”, nie program w
  pierwszej osobie); EN karty i przewodnika odpowiada PL; „ze Spotify po 30 s” jest w oknie, w karcie i w przewodniku.
- `messages*.properties`: same escape'y `\uXXXX`, LF jak było; `05-1d` zostaje CRLF.

## Poprawki (2026-11-25, ta sama gałąź; właściciel: „napraw to co możesz”)

- **P1** wiersz „CZYTA” przez `Window.readingRow` → `longText` (60 znaków, całość w podpowiedzi); zrzut: długi tytuł, okno zostaje
  647 px. Test `WindowTest.aLongSpotifyTitleInReading_isCut`.
- **P2** `LONG_PAUSE` = 2 min: po dłuższej pauzie ta sama piosenka jest nowa od chwili, gdy znów gra (nowy początek, `lastPlayed`
  wyczyszczone — drugie granie się liczy). Test `aShortPause_keepsTheSong_aLongOne_makesItANewPlayFromWhenItPlaysAgain`; test pauzy
  1-minutowej zostaje (krótka pauza liczy dalej).
- **P3** `Player.now()` zwraca `Heard` (piosenka albo słowa okna); `osascript` z `-1743` → „brak zgody macOS — nie widać, co gra”
  (`fromOsascript`, test); stderr już nie odrzucany. Karta: nowe `dashboard.computer.first.spotify` (PL / EN), przewodnik: wiersz
  „Spotify” w „Więcej”.
- **P4** pusty tytuł okna przy działającym Spotify → „okno schowane — nie widać, co gra” (`fromWindow`, test). W oknie oba stany
  żółtym „!” z podpowiedzią, co zrobić (`SPOTIFY_UNSEEN_TIP`; test `spotifyThatHearsNothing_isMarked_andSaysWhatToDo`); karta i
  przewodnik mówią o otwartym oknie.
- **P5** link „VirtualDJ albo Serato nie na liście? Wskaż ich folder…”; karta (`dashboard.computer.first.folder`) i przewodnik nazywają
  oba napisy. **Zostaje:** nowy `program.webp` — zrzut z Windows u właściciela (najlepiej z wierszem Spotify).
- **P6** „Spotify zamknięty” w obu tekstach.
- **P7** `countedOnce` usunięte (to samo co `lastPlayed`); reguła A → krótko B → A = jedno granie ma test
  (`anotherSongClickedForAFewSeconds_andBack_isOnePlay`); javadoc opisuje to, co kod robi.
- **P8** testy: rozbiór linii PowerShella z BOM (`titleOf`), wersja stała bez nowego zagrania, Spotify zamknięty i otwarty w trakcie
  (agent nic nie wysyła drugi raz), `KEPT`, pusty wykonawca, `playerFor` (Windows / Mac / nic), wspólna instancja w `sources()` (na
  Linuksie pusta lista — test ma sens na Windows / Macu).
- **P9** `WindowsSpotify.nextWait`: PowerShell, który żył < 30 s, następny po 1, 2, 4, 8, 10 min (test).
- **P10** `shell` `volatile`. **P11** BOM jako `﻿` w `titleOf`.
- **P12** (a) Mac: `running()` co 10 s jak na Windows. **(b) zostaje** do próby na Macu (czy pytanie macOS przeżywa zabicie `osascript`
  po 5 s).
- **P13** `plutil -replace`; zdanie o podpisie ad hoc w 05-1d.
- **P14** komentarze `Window` (bez historii, trzy programy). **P15** 05-1d (nowe reguły, bez „the owner, 2026-11-25”, łamanie linii),
  `docs/ideas.md` 20 bez nieaktualnego „Open”, handoff: punkt na 4 linie (porządek całego pliku — przy wydaniu).
- **P16** okno: separator ścieżek systemu (`File.separatorChar`, także `NOT_IN_FOLDER`); przewodnik: „na Macu „Serato/Library””.
- **P17** polityka PL / EN: „(bez logowania do Spotify)”.

Mutacje po poprawkach (`SpotifyNowPlayingTest`, `WindowTest`): bez `lastPlayed`, bez długiej pauzy, bez czyszczenia `lastPlayed`
po niej, bez rozpoznania `-1743`, pusty tytuł jak pauza, wiersz „CZYTA” bez skracania — wszystkie czerwone.

Testy po poprawkach: `deck-agent` 75/75; aplikacja unit 1560/1560; przeglądarka 484/485 (czerwony tylko `kiosk-full-fits-820x1180`,
czerwony też na `main`); audyt `guide`, `dashboard*` (18 stron × 3 szerokości) OK. Zrzuty okna w xvfb: nic, pauza, schowane okno, brak
zgody, długi tytuł.
