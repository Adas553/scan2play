# Przegląd 2026-11-25 — TIDAL i sesje multimediów Windows w `deck-agent`

Zakres: gałąź `claude/gracious-bell-iap7ms`, `git diff 7fdc6fc..3bb26b7` (po poprawkach z `review-2026-11-25-spotify.md`): `66b642c`
(`WindowsMedia`, `SpotifyNowPlaying` → `NowPlaying`, TIDAL), `c53ad42` (docs, próba na Windows), `3bb26b7` (bez „okno musi być
otwarte” w karcie i przewodniku). 18 plików, +510 / −257, serwer bez zmian, bez migracji.

## Wynik w skrócie

Kierunek dobry: sesje multimediów Windows to udokumentowany interfejs, wykonawca i tytuł przychodzą osobno, okno Spotify nie jest
potrzebne, a jeden PowerShell obsługuje obie aplikacje. Zachowane jest wszystko z poprzedniego review: jedna instancja na aplikację
(holder `Platform.MusicApps`, wspólny `WindowsMedia`), coraz rzadsze restarty PowerShella, koniec PowerShella ~2 s po programie,
skracanie wiersza „CZYTA”, długa pauza, BOM. Skrypt PowerShella jest poprawny (standardowy wzorzec `AsTask` + WinRT w Windows
PowerShell 5.1, `Clean` usuwa tabulatory i nowe linie, `-EncodedCommand` ~7 KB, daleko od limitu linii poleceń), a właściciel
sprawdził go na Windows (Spotify oznacza, TIDAL odczytany).

Do poprawy przed scaleniem:
- **M1** skrypt czyta tytuły **wszystkich** sesji multimediów komputera (przeglądarka, YouTube, inne odtwarzacze) i przekazuje je do
  programu — więcej, niż mówi polityka prywatności, i wolniej;
- **M2** przy kilku sesjach jeden obieg skryptu może trwać dłużej niż 8 s (`STALE`) — wtedy aplikacja „znika” na chwilę i liczenie
  30 s traktuje to jak pauzę;
- **M3** na Macu okno mówi „Spotify i Tidal zamknięte” / „ze Spotify i Tidala po 30 s”, choć Mac Tidala nie czyta; dłuższa linia stanu
  poszerza okno na stałe (647 → 691 px).

Reszta drobna. Mac nadal niesprawdzony (bez zmian od poprzedniego review).

## Testy i sprawdzenia

- `mvn -f deck-agent/pom.xml -B -ntp verify` (kopia repo): 82/82 (`WindowsMediaTest` 8 nowych, `NowPlayingTest`).
- Aplikacja: `tools/check.py unit` 1560/1560; audyt `guide`, `dashboard.html`, `dashboard-en` (320 / 390 / 1280 px) bez przewijania w
  bok i błędów.
- Okno w xvfb: nic nie czytane (szerokość 691 px — M3), Spotify pauza + TIDAL gra z długim tytułem (skrócony „…”, dobrze).
- Skryptu PowerShella nie da się uruchomić w kontenerze (brak PowerShella); oceniony z kodu i z próby właściciela.

Mutacje `WindowsMedia` (`-Dtest=WindowsMediaTest,NowPlayingTest`):

| # | mutacja | wynik |
|---|---|---|
| W1 | bez filtra `playing` (pauza = gra) | czerwony ✓ |
| W2 | Spotify bez sesji nie czytany z tytułu okna | czerwony ✓ |
| W3 | BOM nie usuwany | czerwony ✓ |
| W4 | reklama rozpoznawana tylko po tytule „Advertisement”, nie po wykonawcy „Spotify” | **przeżyła** (M6) |
| W5 | linia sesji z 3 polami przyjęta | przeżyła — nieszkodliwa |
| W6 | stara odpowiedź (> 8 s) brana jak świeża | **przeżyła** (M6) |
| W7 | aplikacja po początku id (`startsWith`) zamiast `contains` | **przeżyła** (M6) |

## Punkty

### M1 — skrypt czyta wszystkie sesje multimediów komputera, nie tylko Spotify i TIDAL · ważne

- **Gdzie:** `deck-agent/src/main/java/com/scan2play/deck/WindowsMedia.java:186-191` (pętla po `$manager.GetSessions()`), filtr
  dopiero w Javie `:116`; polityka prywatności (`privacy_pl.html:35`, `privacy.html:35`).
- **Dlaczego:** co 2 s skrypt pyta o tytuł i wykonawcę **każdej** sesji — film na YouTube w Chrome, Teams, inny odtwarzacz — i wypisuje
  je do programu. Do serwera nie idą (Java bierze tylko sesje Spotify / TIDAL), ale (a) polityka mówi, że program czyta „utwór, który
  gra w aplikacji Spotify lub Tidal”, a czyta więcej; (b) każda obca sesja to jeszcze jedno `TryGetMediaPropertiesAsync` z czekaniem do
  3 s (M2).
- **Poprawka:** w skrypcie filtrować przed pytaniem o utwór: `if ($session.SourceAppUserModelId -notmatch 'spotify|tidal') { continue }`
  (wzorzec z `APPS`, jak `$apps`). Test skryptu: `script()` zawiera filtr przed `TryGetMediaPropertiesAsync`.

### M2 — obieg skryptu dłuższy niż `STALE` = aplikacja na chwilę „zamknięta” i pauza w liczeniu · ważne

- **Gdzie:** `WindowsMedia.java:31` (`STALE` = 8 s), `:97`, `:176` (`$task.Wait(3000)` na sesję), `NowPlaying.java:120-121`.
- **Dlaczego:** obieg = `Get-Process` + dla każdej sesji do 3 s czekania + 2 s snu. Trzy wolne sesje (M1) albo jedna zawieszona dają
  > 8 s bez odpowiedzi: `fresh()` zwraca null, `running()` false — wiersz „Spotify” znika z „CZYTA”, wersja jest pusta, a w
  `NowPlaying` to pauza (`lastHeard = null`). Gdy to się powtarza, piosenka nie dobija do 30 s albo po > 2 min liczy się jak nowa.
- **Poprawka:** M1 usuwa główną przyczynę. Do tego: żywy PowerShell bez świeżej odpowiedzi to „nie wiadomo”, nie „zamknięte” — trzymać
  ostatnią odpowiedź, dopóki `shell.isAlive()` i nie jest starsza niż np. 30 s; `STALE` tylko dla martwego. Test z zegarem (dziś nie
  da się — `Instant.now()` w `fresh()`; wstrzyknąć `Clock` jak w `NowPlaying`).

### M3 — Tidal w tekstach okna na Macu; dłuższa linia stanu poszerza okno · ważne

- **Gdzie:** `Window.java:67` (`NO_HISTORY`: „Spotify i Tidal zamknięte”), `Window.java:108-109` (`NOTHING_MARKED`: „ze Spotify i
  Tidala po 30 s”), `Window.java:392` (linia stanu).
- **Dlaczego:** (a) na Macu Tidal nie jest czytany, a okno mówi „Tidal zamknięte” — DJ z otwartym Tidalem na Macu dostaje nieprawdę i
  szuka, co jest nie tak; (b) „Połączono — brak historii VirtualDJ i Serato, Spotify i Tidal zamknięte” jest szersza niż okno: zrzut
  691 px zamiast 647, i przez `grownOnly` okno już takie zostaje.
- **Poprawka:** teksty z aplikacji tego systemu (`NowPlaying.onThisComputer()` → nazwy: Windows „Spotify i Tidal”, Mac „Spotify”);
  linia stanu krótsza, szczegóły w bloku pod „CZYTA”: „Połączono — nic do odczytu” (test: szerokość linii ≤ dawnej). `WindowTest`:
  na Macu bez „Tidal”.

### M4 — „TIDAL” i „Tidal” w jednym oknie · drobne

- **Gdzie:** `WindowsMedia.java:28` (`APPS` = „TIDAL”, z tego wiersz „TIDAL (gra: …)”), teksty PL („Tidal”, „Tidala”, „Tidalu”), EN
  („TIDAL”).
- **Dlaczego:** w zrzucie wiersz „TIDAL (gra: …)” stoi nad „ze Spotify i Tidala po 30 s”. Marka pisze się „TIDAL”, ale polska odmiana
  („Tidala”) wymaga zwykłej pisowni.
- **Poprawka:** jedna pisownia w PL: nazwa wyświetlana „Tidal” (osobno od nazwy procesu / sesji, które i tak są porównywane małymi
  literami); EN zostaje „TIDAL”.

### M5 — `-EncodedCommand` bywa podejrzany dla antywirusa · drobne (ryzyko)

- **Gdzie:** `WindowsMedia.java:204-211`.
- **Dlaczego:** niepodpisany program uruchamiający `powershell -EncodedCommand` to wzór, który Defender (reguła ASR „zaciemnione
  skrypty”) i firmowe EDR-y oznaczają. U właściciela działa; na laptopie z firmowym EDR może zostać zabity (wtedy tylko coraz rzadsze
  próby, bez słowa w oknie) albo wywołać alarm.
- **Poprawka:** sprawdzić u właściciela „Ochrona przed wirusami → Historia ochrony” po imprezie próbnej. Gdyby był problem: skrypt jako
  plik `~/.scan2play/media.ps1` i `-ExecutionPolicy Bypass -File` (czytelny dla antywirusa). Osobno: gdy PowerShell umiera młodo,
  wiersz „Spotify” mógłby mówić „nie da się odczytać” zamiast znikać.

### M6 — testy: czego brakuje · drobne

- **Gdzie:** `WindowsMediaTest`.
- **Co dopisać:** reklama rozpoznawana po wykonawcy „Spotify” przy innym tytule (W4); id TIDAL-a, które nie zaczyna się od „tidal”
  (np. `com.squirrel.TIDAL.TIDAL`; W7); stara odpowiedź przy żywym / martwym PowerShellu (W6, po M2 z `Clock`). Skryptu nie uruchamia
  nic w CI (testy na ubuntu): w jobie Windows workflowu jedno `powershell -Command "[ScriptBlock]::Create(...)"` z treścią skryptu
  złapałoby błąd składni przed wydaniem (bez Spotify skrypt i tak od razu się kończy).

### M7 — przewodnik PL: odmiana i nazwa nakładki · drobne

- **Gdzie:** `guide_pl.html:296`, `guide.html` odpowiednio.
- **Dlaczego:** „wystarczy, że Spotify albo Tidal gra: program pyta Windows, co odtwarzają” — „albo … gra” (l. poj.) i „odtwarzają” (l.
  mn.); „nakładka głośności” to Windows 10, na Windows 11 utwór widać w szybkich ustawieniach.
- **Poprawka:** „…program pyta Windows, co odtwarza (to samo, co Windows pokazuje przy przyciskach multimediów)”; EN „(as Windows’ own
  media controls do)”.

### M8 — podpowiedź „okno schowane” po sesjach multimediów · drobne

- **Gdzie:** `Window.java:84-85` (`SPOTIFY_UNSEEN_TIP`), `NowPlaying.HIDDEN`.
- **Dlaczego:** „okno schowane” pokazuje się teraz tylko, gdy Spotify nie ma sesji i okno nie ma tytułu — najczęściej Spotify włączony,
  ale jeszcze nic nie puścił z okna schowanego, albo sesje niedostępne. Podpowiedź każe otworzyć okno, a sednem jest „puść piosenkę”.
- **Poprawka:** podpowiedź: „Windows: puść piosenkę w Spotify; jeśli dalej nic — otwórz okno Spotify.”

### M9 — dokumentacja i porządek · drobne

- `SESSION_HANDOFF.md` punkt gałęzi ma 5 linii (limit 4).
- `NowPlaying.java:18`: linia javadocu ~150 znaków (reszta pliku łamana ~135).
- `program.webp` w przewodniku nadal stary (z poprzedniego review, czeka na zrzut z Windows).
- `docs/context/05-1d-…`: zgadza się z kodem (sesje, okno jako zapas, PowerShell, Mac tylko Spotify, TIDAL Free 29 s w `docs/ideas.md`).

## Bez uwag (sprawdzone)

- Jedna `WindowsMedia` na program (tworzona w `forSystem`, trzymana przez `Platform.MusicApps`), jeden hook zamknięcia, `fresh()`
  `synchronized`, `last` i `shell` `volatile`; `processRuns` co 10 s tylko, gdy PowerShell nie działa; każda aplikacja ma swoją
  wersję i swoje „seen” w `Agent` (`program|…`).
- Spotify ze Sklepu (`SpotifyAB.SpotifyMusic_…!Spotify`) i zwykły (`Spotify.exe`) znajdowane; Spotify w przeglądarce (sesja „Chrome”)
  nie — zgodnie z polityką.
- Spotify przed pierwszą piosenką nie ma sesji → tytuł okna „Spotify Premium” → „nic nie gra”; sesja zostaje, gdy okno schowane —
  stąd słusznie bez „okno musi być otwarte” w karcie.
- Teksty karty i przewodnika: „Tidal tylko na Windows”, Mac tylko Spotify z pytaniem macOS, „bez logowania” — PL / EN się zgadzają;
  `messages*.properties` tylko escape'y; TIDAL nie pojawia się w „Nowościach” ani na stronach reklamowych.
