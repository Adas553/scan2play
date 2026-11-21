# Review 2026-11-21 — „Zagrane samo” i program na komputerze DJ-a

Gałąź `claude/loving-maxwell-sl8pkl` (PR Adas553/scan2play#297 do `main`, #298 do `dev`), migracja V78, przed scaleniem.
Diff: `git diff origin/main...origin/claude/loving-maxwell-sl8pkl` (52 pliki, +3858). Opis funkcji: `docs/ideas.md` punkty 18 i 19,
`docs/context/05-1d-panel-heading-queue.md`.

## Werdykt

**Scalić po poprawkach.** Poprawki P1–P8 są już na gałęzi (lista „Poprawki” na końcu). Nie ma błędu, który psułby kolejkę albo
dane: oznaczenie idzie przez to samo warunkowe `UPDATE` co ręczne „Zagrane”, nic z seta nie jest zapisywane, token jest w bazie
tylko jako SHA-256, „Odłącz” działa od razu, V78 tylko dodaje tabelę. Zanim program trafi do DJ-ów, właściciel musi rozstrzygnąć
P9–P11 (Mac z Intelem, skąd DJ pobiera program, Cloudflare) i odpowiedzieć na pytania z końca pliku.

## Sprawdzenia

W kopii repo, nigdy Maven w repo (CLAUDE.md):

- Przed poprawkami: `check.py unit` 1514/1514, `browser` 470/470, `audit -t dashboard` 15 stron × 3 szerokości bez przewijania
  w bok i błędów (4 małe cele dotyku, wszystkie stare: „Szczegóły”, godziny w harmonogramie). `it`: 217 błędów, wszystkie
  `password authentication failed` (hasło `postgres` w kontenerze, nie kod) — po ustawieniu hasła ITs ruszyły.
- Po poprawkach: wyniki w liście „Poprawki”.
- Program: `sh mvnw -f deck-agent/pom.xml -B -ntp verify` w kopii — 15/15 (było 12, doszły 3).
- `jdeps --print-module-deps` na jarze programu i `sqlite-jdbc`: potrzebne `java.base, java.desktop, java.net.http, java.sql`; lista
  modułów w `deck-agent.yml` je zawiera (plus `java.logging`, `java.naming`, `jdk.crypto.ec`). Manifest: `Class-Path:
  sqlite-jdbc-3.47.1.0.jar`, `Implementation-Version: 1.0.0` — wersja w User-Agent jest.
- Od końca do końca (jak w `docs/ideas.md` p. 19): aplikacja z kopii na jednorazowej bazie `s2p_e2e`, konto z hasłem, dwie prośby
  dodane ręcznie, kod z `/dj/devices/code`, prawdziwy program `--pair` i `--console` z `user.home` w katalogu tymczasowym, plik
  `Documents/VirtualDJ/History/2026-11-21.m3u` dopisywany linia po linii (ostatnia linia w dwóch kawałkach, 4 s przerwy). Wynik
  w liście „Poprawki”. Baza usunięta.
- Nie dało się sprawdzić tutaj: prawdziwy VirtualDJ, Serato 2/3/4 (formaty są z otwartych czytników i spike'a, testy na plikach
  zrobionych ręcznie), Windows i macOS (rejestr, `%LOCALAPPDATA%`, zasobnik, SmartScreen, Gatekeeper), Cloudflare przed programem.

## Ustalenia

### P1 — ryzyko (poprawione): publiczne `/api/device/*` czytały ciało dowolnej wielkości

`DeviceApiController.java` — `@RequestBody` w `pair` i `plays`. Spring czyta i parsuje cały JSON, zanim metoda zobaczy token;
Tomcat ogranicza tylko formularze (`server.tomcat.max-http-form-post-size=10KB`, `application.properties:50`). Scenariusz: ktoś bez
konta wysyła w pętli 50 MB JSON-a na `/api/device/pair` — każda prośba to megabajty pamięci i czas CPU na parsowanie, zanim padnie
400/401. `CspReportController` (inny publiczny JSON) robi to dobrze: `readNBytes(MAX_BODY)`.
**Poprawka:** `config/DeviceBodyLimitFilter` — dla `/api/device/*` ciało, które samo mówi, że ma ponad 128 KB, to 413 bez czytania;
każde inne czytane najwyżej do 128 KB + 1 bajt (dłuższe — 413) i podawane dalej z pamięci. Ciało bez długości (chunked) nie jest
odrzucane: tak może je przekazać pośrednik między programem a aplikacją (Cloudflare, krawędź Railway) — pierwsza wersja poprawki
dawała mu 411 i mogła odciąć program w całości (znalezione w drugim przejściu, niżej). 128 KB mieści najgorszy prawdziwy przypadek (20 piosenek × 1000 znaków × 4 bajty). Test
`DeviceApiWebTest.aBodyOfMegabytes_isRefusedBeforeItIsRead_toThePairingAndToTheSongsAlike` (czerwony: 401 zamiast 413).

### P2 — błąd (poprawiony): zegar komputera DJ-a spóźniony o więcej niż 5 minut — nic się nie oznacza, po cichu

`DeckHistoryService.java:79` — prośba jest oznaczana tylko przez piosenkę, której `at` ≥ czas prośby − 5 min (`CLOCK_SLACK`, :36).
`at` to zegar komputera DJ-a (VirtualDJ `lastplaytime`, Serato start). Scenariusz: laptop bez synchronizacji czasu (wyłączona w
Windows, BIOS po wymianie baterii, ręcznie ustawiona godzina) chodzi 8 minut do tyłu. Gość prosi o 21:00, DJ gra o 21:10 według
serwera, plik zapisuje 21:02 — „zagrane przed prośbą”, nic się nie oznacza, ani DJ, ani program tego nie widzi. Zegar do przodu daje
odwrotny błąd: piosenka zagrana przed prośbą wygląda na zagraną po niej i oznacza prośbę, której nikt nie zagrał (przycięcie `at`
z przyszłości do „teraz”, :66, chroni tylko przed czasem z przyszłości).
**Poprawka:** wysyłający podaje swój zegar w chwili wysłania (`sentAt`, sekundy): program (`Json.plays`, `ServerClient`) i panel
(`play-history.js`, `send`). `DjSongController.songsOf(plays, sentAt, now)` przesuwa każde `at` o różnicę `now − sentAt`; bez `sentAt`
(stary klient) — jak dotąd. Testy: `DeviceApiControllerTest.aClockEightMinutesSlow_isCorrectedBythe_sentAt_ofTheCall`,
`HistoryFormatsTest.theCall_carriesTheComputersClock_soTheServerCanCorrectIt` (czerwone: nie kompilowały się), krok `sentAt`
w scenariuszu `deck-reader-virtualdj`. API zgodne wstecz.

### P3 — ryzyko (poprawione): nieograniczona liczba ważnych kodów na imprezę

`DjDeviceService.newCode` (dawniej :171) — każde „Połącz komputer” dodawało kolejny ważny kod, poprzednie żyły dalej 10 minut;
`codes` ma `maximumSize(10_000)`. Scenariusz 1: DJ klika dwa razy, w panelu widzi drugi kod, pierwszy dalej otwiera imprezę.
Scenariusz 2: zalogowany ktokolwiek (konto z hasłem jest za darmo) woła `/dj/devices/code` w pętli — 10 000 kodów wypycha z pamięci
kody innych DJ-ów („Ten kod nie działa” u DJ-a, który właśnie przepisuje swój).
**Poprawka:** nowy kod imprezy unieważnia jej poprzedni (jeden ważny kod na imprezę). Test `DjDeviceIT.aNewCode_killsThePartysEarlierOne_andNoOtherPartys`
(czerwony). Przy okazji blokada `FOR UPDATE` przy parowaniu (`DjDeviceService.java:106`) prawie przestaje mieć co robić — dwa
programy tej samej imprezy nie mają już dwóch kodów naraz — ale zostaje jako obrona limitu 3.

### P4 — błąd (poprawiony): kod znikał z panelu po „Odłącz” innego komputera i nie znikał po zajęciu czwartego miejsca

`play-history.js` (dawniej :256) — „program wziął kod” liczone po długości listy (`devices.length !== shown`). Scenariusz 1: DJ
pokazuje kod, w międzyczasie odłącza stary laptop — lista krótsza, kod znika z panelu, choć wciąż jest ważny. Scenariusz 2: impreza
ma 3 komputery, czwarty zastępuje najdawniej widziany — lista tak samo długa, panel dalej pokazuje zużyty kod „ważny 10 min”.
**Poprawka:** po najwyższym `id` (nowy komputer ma `id` większe niż każdy na liście w chwili zrobienia kodu). Scenariusz
`deck-computers-code-kept` (czerwony).

### P5 — ryzyko (poprawione): plik z tokenem przez chwilę czytelny dla innych, folder `~/.scan2play` 755, zapis nieatomowy

`deck-agent/.../Settings.java` (dawniej :271) — `Files.newOutputStream` tworzył plik z umaską (zwykle 644), dopiero potem
`chmod 600`; folder zostawał `rwxr-xr-x`; awaria w trakcie `store` zostawiała pusty plik (DJ musi parować od nowa). Czy inne konta
komputera widzą katalog domowy, zależy od systemu i jego wersji; realne ryzyko to wspólny komputer z kilkoma kontami.
**Poprawka:** plik tymczasowy w tym samym folderze (owner-only od utworzenia), `ATOMIC_MOVE` na miejsce, folder `rwx------`.
Na Windows plik leży w profilu użytkownika (ACL profilu: tylko on, SYSTEM i administratorzy) — bez zmian. Test
`SettingsTest.theTokensFileAndFolder_areTheOwnersAlone_whateverTheyWereBefore` (czerwony: folder `rwxr-xr-x`).

### P6 — błąd (poprawiony): okno programu nadpisywało odpowiedź na wpisany kod i co 3 s zmieniało swój rozmiar

`Window.java` (dawniej :128 i :138) — po „Odłącz” w panelu każde odświeżenie (co 3 s) wpisywało „Ten komputer odłączono w panelu…”.
Scenariusz: DJ wpisuje zły kod, widzi „Ten kod nie działa — weź nowy z panelu”, po chwili ten tekst znika i wraca „odłączono” — DJ
nie wie, czy kod był zły. Do tego `frame.pack()` przy każdym odświeżeniu cofał okno rozciągnięte przez DJ-a.
**Poprawka:** komunikat raz, przy przejściu z „połączony” na „odłączony”; `pack()` tylko gdy zmienił się rozmiar treści albo widok
(kod ↔ czytanie). Swing bez ekranu — bez testu automatycznego; sprawdzić ręcznie przy próbie na Windows.

### P7 — drobiazg (poprawiony): jedno złe odwołanie znakowe zatrzymywało czytanie całego pliku w panelu

`play-history.js` `unescapeXml` — `String.fromCodePoint(0x110000)` rzuca `RangeError`; `look()` łapie wyjątek, zeruje `current`,
plik czytany od nowa co 5 s i znów pada — żadna kolejna piosenka tej nocy nie przejdzie. Java (`Xml.unescape`) łapie
`IllegalArgumentException` i zostawia tekst — panel zachowywał się inaczej niż program. VirtualDJ sam takiego odwołania nie zapisze
(zamienia `&` na `&amp;`), ale tag z ręki albo inny program mogą.
**Poprawka:** jak w Javie: nieznany znak zostaje jak w pliku. Scenariusz `deck-reader-bad-entity` (czerwony).

### P8 — drobiazg (poprawiony): przyciski „Odłącz” bez nazwy komputera dla czytnika ekranu; brak Access Bridge w paczce

`play-history.js` — przy trzech komputerach czytnik czyta trzy razy „Odłącz”. **Poprawka:** `aria-label` „Odłącz <nazwa>”
(scenariusz `deck-computers-code-kept`). `deck-agent.yml` — jlink bez `jdk.accessibility`: na Windows NVDA i Narrator nie przeczytają
okna Swing. **Poprawka:** moduł dodany (niesprawdzone na Windows — paczkę robi tylko „Run workflow”).

### P9 — ryzyko (otwarte, pytanie): paczka na macOS tylko dla Apple Silicon

`deck-agent.yml:42` — `macos-15` to runner arm64, `jpackage` dołącza Javę tej architektury. Scenariusz: DJ z MacBookiem Pro 2019
(Intel, wciąż częsty u DJ-ów) rozpakowuje zip — program się nie uruchamia (macOS mówi, że aplikacja nie jest obsługiwana na tym Macu;
Rosetta działa w drugą stronę). **Propozycja:** druga pozycja macierzy `macos-15-intel` (minuty macOS liczą się ×10) albo jasno
„tylko Mac z Apple M1 i nowsze” w panelu. Decyzja właściciela (koszt).

### P10 — ryzyko (otwarte, pytanie): DJ nie ma skąd pobrać programu

`messages_pl.properties:551` — „To wersja testowa — napisz do nas, żeby ją dostać.” Bez linku, bez adresu. Paczki są artefaktami
Actions (`deck-agent.yml`, `retention-days: 30`): do pobrania trzeba być zalogowanym na GitHubie z dostępem do repo, a po 30 dniach
ich nie ma. Na betę z kilkoma DJ-ami wystarczy, do otwarcia dla wszystkich — nie. **Propozycja:** GitHub Release (publiczne
pobieranie bez konta, stały link) albo strona `/program` z linkiem; w tekście karty — link do kontaktu (`/kontakt` albo e-mail).
Decyzja właściciela (razem z podpisem).

### P11 — ryzyko (otwarte): Cloudflare przed klientem, który nie jest przeglądarką — niesprawdzone

`ServerClient.java` wysyła `User-Agent: Scan2Play-Zagrane/1.0.0` z `java.net.http`. `OriginGuardFilter` przepuści (nagłówek dodaje
Cloudflare każdemu żądaniu), ale Bot Fight Mode / Browser Integrity Check / reguły WAF mogą zwrócić 403 z wyzwaniem JS. Wtedy
program pokazuje „Brak połączenia z serwerem” (`ServerClient` traktuje 403 jak `FAILED`) i nigdy się nie połączy, a DJ nie wie
dlaczego. Dokumentacja (`07-09`) już mówi o regule pomijania. **Propozycja:** przed pierwszym DJ-em — próba `--pair` przeciw
`dev.scan2play.com.pl` z tego samego zipa (jak w „To try” p. 19), a jeśli 403: reguła „Skip” dla `/api/device/*` w Cloudflare.

### P12 — ryzyko (otwarte): prośba złożona tuż po zagraniu piosenki może zostać oznaczona przy ponownym wysłaniu

`DeckHistoryService.java:79` (`CLOCK_SLACK` 5 min) razem z `Agent.java:25` (`EARLIER_SET` 10 min) i tym samym w panelu. Scenariusz:
o 21:00 gra „Szampan”, o 21:03 gość prosi o „Szampan” jeszcze raz, o 21:05 DJ restartuje program (albo odświeża panel z kartą
czytającą). Program wysyła ostatnie 10 minut historii ponownie; piosenka z 21:00 ≥ 21:03 − 5 min — prośba z 21:03 zostaje oznaczona,
choć nikt jej nie zagrał. Bez restartu się nie zdarza (piosenka idzie o 21:00, gdy prośby jeszcze nie ma). Rzadkie, ale „zagraj to
jeszcze raz” jest na weselach częste. **Propozycja:** po P2 zegar jest wyrównany przez `sentAt`, więc zapas może spaść z 5 do 1–2
minut dla wywołań z `sentAt` (zapas zostaje na opóźnienie zapisu historii, VirtualDJ `historyDelay`). Zmiana parametru dobranego
przy budowie — pytanie do właściciela, nie zmieniam.

### P13 — ryzyko (otwarte): globalny limit 200 złych kodów można wyczerpać z kilkudziesięciu adresów

`DjDeviceService.java:96` — 10 prób na adres i 200 złych kodów łącznie na 10 minut. Scenariusz: ktoś z 20 adresów (VPN, chmura) co
10 minut wysyła po 10 złych kodów — przez cały wieczór każdy DJ dostaje „Za dużo prób — spróbuj za kilka minut”. Zgadnięcie kodu jest
i tak beznadziejne: 1 z miliona na 10 minut, a trafiony kod daje tylko oznaczanie „Zagrane” jednej imprezy (P3 dodatkowo zostawia
jeden kod na imprezę). **Propozycja:** globalny limit wyżej (np. 2000) albo tylko na adres. Zostawione — dobór liczb to decyzja.

### P14 — drobiazg (otwarte): program bez cofania się po 429 i błędach sieci

`Agent.java:76` — z czymś do wysłania program woła co 3 s niezależnie od wyniku; po 429 (limit 30/min) dalej co 3 s. Mieści się w
limicie serwera, ale bez sieci to 20 prób na minutę z 15-sekundowym limitem czasu. **Propozycja:** po `FAILED`/`TOO_MANY` przerwa
rosnąca do 1 min. Zostawione: działa, a zmiana dotyka testu `aFailedCall_keepsTheSongsForTheNextLook`.

### P15 — drobiazg (otwarte): okno programu dla DJ-a

`Window.java`:
- pole „Serwer:” (:66) widzi każdy DJ — żargon i łatwo je zepsuć; potrzebne tylko do prób na `dev.`. Propozycja: schować pod
  `--server` / ukryte pole, gdy adres jest domyślny.
- po zamknięciu okna (`HIDE_ON_CLOSE`, :97) program znika do zasobnika bez słowa — na Windows ikonka bywa schowana pod „^”, DJ myśli,
  że zamknął program, albo uruchamia go drugi raz (dwie instancje, dwie ikonki, obie wysyłają — nieszkodliwie). Propozycja: raz
  `TrayIcon.displayMessage("Program działa dalej w zasobniku")` i blokada drugiej instancji (plik blokady w `~/.scan2play`).
- brak uruchamiania przy starcie systemu: DJ, który zapomni włączyć program, nie dostaje „Zagrane samo” i nic mu tego nie mówi —
  panel pokazuje tylko „ostatnio 21:43”, bez rozróżnienia „działa teraz” / „dawno”. Propozycja: w panelu „działa” (zielone), gdy
  `lastSeenAt` < 2 min, inaczej szare „ostatnio …”.

### P16 — drobiazg (otwarte): kod i nazwy

- `validPlays` / `songsOf` / `DeckPlay` żyją w `DjSongController` jako statyczne, a używa ich też `DeviceApiController` — kontroler
  zależy od drugiego. Naturalne miejsce: `DeckHistoryService` (walidacja i zamiana na `Play`). Zostawione: przeniesienie bez zmiany
  zachowania, do zrobienia przy następnej zmianie tych plików.
- Dwa parsery formatów (JS w panelu, Java w programie) są uzasadnione — dwa środowiska — ale różnią się: Java rozpoznaje
  milisekundy i czas od 2001 (`Played.fromEpoch`), JS nie. Dla plików VirtualDJ i Serato 2/3 to bez znaczenia (sekundy od 1970),
  ale warto mieć to zapisane przy obu (dopisane w P7 tylko dla odwołań XML).
- `deck-agent.yml:57` i `:65` — wersja `1.0.0` wpisana na sztywno obok `pom.xml`; przy podbiciu wersji w pom paczka się nie zbuduje
  (`cp` nie znajdzie jara). Propozycja: `deck-agent/target/deck-agent-*.jar` i `--app-version` z `mvn help:evaluate`.
- Kody parowania i limity są w pamięci jednej instancji (zgodnie z „Single instance by design”, `13-14-limitations.md`); restart
  (deploy) między „Połącz komputer” a wpisaniem kodu daje „Ten kod nie działa — weź nowy z panelu” — tekst prowadzi DJ-a dobrze.
  Dopisane do `13-14-limitations.md`.

### Sprawdzone i w porządku

- Dopasowanie: tytuł i wspólny wykonawca po kluczach biblioteki (`LibraryKey.matchIn`, tylko `HAVE`); sam tytuł, prośba bez
  wykonawcy, piosenka bez tytułu — nic (`DeckHistoryServiceTest`). Plik bez tagów: tytuł „Artysta - Tytuł” dzielony jak nazwy AI.
  Ta sama piosenka zamówiona dwa razy: zagranie oznacza obie czekające prośby (obie były o zagraną piosenkę) — zgodne z „Zagrane”
  w kolejce. Prośba zagrana ręcznie w międzyczasie: warunkowe `UPDATE` (`DjService.markSongAsPlayed`) zwraca `false`, nie trafia do
  „✓ Zagrane samo” (test `aRequestTheDjMarkedMeanwhile_isNotSaidToBeMarked`).
- SQL: odczyt kolejki ograniczony (`findTop400…`), `devices` z `LIMIT 3`, indeks `idx_dj_device_party`, `UNIQUE (token_hash)`
  (wyszukiwanie tokenu po indeksie), `last_seen_at` jednym warunkowym `UPDATE` co minutę najwyżej. Kaskady: usunięcie imprezy
  i zmiana kodu (`PartyCodeService.changeCode` robi `UPDATE party_settings SET party_code`) idą przez `ON DELETE / ON UPDATE
  CASCADE` (`theComputers_goWithTheParty`). Blokada wierszy w kolejności jak w `changeCode` (najpierw prośby, potem impreza) — parowanie
  bierze tylko wiersz imprezy, odwrócenia kolejności nie ma.
- Token: 32 bajty z `SecureRandom`, Base64url (43 znaki), w bazie SHA-256 (64 hex); porównanie przez wyszukanie skrótu w indeksie
  (czas nie zdradza tokenu o 256 bitach); `> 100` znaków odrzucane przed skrótem. IDOR w `/dj/devices/remove`: `DELETE … WHERE id = ?
  AND party_code = ?` z imprezy z sesji — cudzego nie usunie (`odlacz_killsTheToken_onlyForItsOwnParty`).
- Publiczne endpointy: bez sesji i bez CSRF (`DeviceApiWebTest`), nie czytają ani nie tworzą ciasteczek. Logi: kod imprezy i liczba
  oznaczonych — nigdy token, kod parowania ani nazwa komputera.
- Prywatność: program wysyła tylko wykonawcę, tytuł, czas (i po P2 swój zegar) oraz raz nazwę komputera; nigdy ścieżek (`Played` nie
  ma pola na ścieżkę, `Json.plays` je pomija). Czyta tylko foldery historii VirtualDJ i Serato i `master.sqlite` tylko do odczytu.
  Polityka prywatności PL/EN, sekcja 2, opisuje dokładnie to, z nazwą komputera i czasem połączenia.
- Program: plik czytany w trakcie zapisu — niedokończona linia nie pasuje do wzorca tagów albo daje pusty tytuł (serwer odrzuca),
  pełna przy następnym odczycie (sprawdzone od końca do końca, linia w dwóch kawałkach). Nowy plik dnia / nowa sesja Serato: najnowszy
  plik po czasie zmiany, `seen` po `czas|wykonawca|tytuł` — nic dwa razy. Restart: wysyła najwyżej ostatnie 10 minut (P12). 401 po
  „Odłącz”: token zapomniany, okno prosi o nowy kod (od końca do końca: w ciągu minuty). Brak sieci: piosenki czekają (do 200).
  400: porzucone (inaczej wracałyby w nieskończoność).
- Teksty PL bez rodzaju (`ImpersonalPolishTextsTest` przechodzi; teksty okna Swing — sprawdzone ręcznie, ten sam styl).
- Karta w panelu: `aria-expanded`, `aria-controls`, `aria-labelledby`, `role="status"` i `aria-live` dla stanu, kodu i oznaczeń;
  pokazywana od 768 px (na telefonie przycisk ukryty — świadomie: narzędzia kolejki mieszczą się w jednym wierszu); w Safari i Firefoksie
  tylko część „Program na komputerze” (scenariusz `deck-reader-hidden`).

## Pytania do właściciela (decyzje, których nie zmieniam)

Odpowiedzi i ich wykonanie: sekcja „Odpowiedzi właściciela” na końcu pliku.

1. **Free/Pro:** program i karta działają w każdym planie (`DjDeviceController` sprawdza tylko `QUEUE`). `docs/ideas.md` p. 19
   zaleca Pro (jak kiosk), wpisano „any plan (as E)”. Zostaje tak?
2. **Kto może łączyć i odłączać:** obsługa z prawem `QUEUE` może zrobić kod, połączyć swój komputer i odłączyć komputer właściciela;
   token nie ginie, gdy osobę usunie się z obsługi (działa w imieniu imprezy, nie osoby). Tylko właściciel?
3. **Podpis i dystrybucja (P9, P10):** paczka bez podpisu (SmartScreen „Uruchom mimo to”, macOS „Open Anyway”) — zgodnie z decyzją.
   Skąd DJ pobiera program (GitHub Release, strona `/program`) i czy budować Maca z Intelem?
4. **Tylko polski w programie** — zostaje (okno ma teksty na sztywno; angielskiego DJ-a nie obsłuży).
5. **Nic z seta nie jest zapisywane** — zostaje; jedyne zapisywane dane to `dj_device` (nazwa komputera, czasy).
6. **Zapas zegara (P12):** zmniejszyć `CLOCK_SLACK` do 1–2 minut dla wywołań z `sentAt`?
7. **Limit złych kodów (P13):** podnieść globalny limit albo zostawić tylko limit na adres?

## Poprawki

Zrobione na gałęzi (każda zmiana zachowania z testem albo scenariuszem widzianym najpierw na czerwono, chyba że napisano inaczej):

- P1 `DeviceBodyLimitFilter` (413 dla `/api/device/*`) — `DeviceApiWebTest` czerwony (401), potem zielony; bez długości: P17.
- P2 `sentAt` w programie i panelu, `DjSongController.songsOf(plays, sentAt, now)` — testy Java czerwone (kompilacja), krok
  `sentAt` w `deck-reader-virtualdj` dopisany razem z kodem (nie widziany osobno na czerwono).
- P3 jeden ważny kod na imprezę — `DjDeviceIT` czerwony, potem zielony.
- P4 kod w panelu po najwyższym `id` — `deck-computers-code-kept` czerwony, potem zielony.
- P5 zapis tokenu: plik tymczasowy owner-only, `ATOMIC_MOVE`, folder 700 — `SettingsTest` czerwony (folder 755), potem zielony.
- P6 okno: komunikat „odłączono” raz, `pack()` tylko przy zmianie — bez testu (Swing bez ekranu).
- P7 `unescapeXml` w panelu jak w Javie — `deck-reader-bad-entity` czerwony, potem zielony.
- P8 `aria-label` przycisków „Odłącz” (scenariusz `deck-computers-code-kept`); `jdk.accessibility` w paczce (niesprawdzone).
- Dokumentacja: `05-1d` (jeden kod, `sentAt`, limit ciała), `07-09` (limit ciała), `11-12` (pola `sentAt`, 413/411), `06-3`
  (`DeviceBodyLimitFilter`), `13-14` (kody w pamięci), `SESSION_HANDOFF.md`.

Wyniki sprawdzeń po poprawkach (w kopii): `check.py unit` 1516/1516, `it` 218/218 (PostgreSQL 16 w kontenerze), `browser` scenariusze
`deck-*` 7/7, `audit -t dashboard` bez przewijania w bok i błędów (te same 4 stare małe cele), program `verify` 15/15. Od końca do
końca 14/14: drugi kod unieważnia pierwszy; program sparowany drugim, plik tokenu 600 w folderze 700, w bazie tylko skrót; wcześniejszy
set (2 h temu) nic nie oznacza; linia „Sanah &amp; Kwiat Jabłoni — Szampan (Radio Edit)” dopisana w dwóch kawałkach oznacza prośbę
„Sanah - Szampan” w ciągu kilku sekund, druga prośba czeka; panel pokazuje komputer z „ostatnio”; 200 KB → 413, bez tokenu → 401;
„Odłącz” w panelu — program w ciągu minuty dostaje 401 i zapomina token. W logu aplikacji: kod imprezy i liczba oznaczonych, bez
tokenu, kodu parowania i nazwy komputera. Baza `s2p_e2e` usunięta.

Zostawione: P9–P16 (decyzje właściciela albo zmiany bez pilnej potrzeby — powody przy każdym punkcie).

## Drugie przejście (2026-11-21, ten sam dzień)

Ponowne sprawdzenie, przede wszystkim własnych poprawek P1–P8 (nikt ich jeszcze nie przejrzał) i twierdzeń tego pliku.

### P17 — błąd (poprawiony): moja poprawka P1 odrzucała ciało bez długości (411)

`DeviceBodyLimitFilter` w pierwszej wersji: `getContentLengthLong() < 0` → 411. Program (`java.net.http`) długość wysyła, ale między
nim a aplikacją są Cloudflare i krawędź Railway; jeśli któryś przekaże ciało w kawałkach (chunked, np. po HTTP/2 od klienta), każde
`/api/device/*` dostaje 411 — program nie połączy się nigdy i pokazuje „Brak połączenia z serwerem”. Tutaj tego nie da się sprawdzić,
więc filtr nie może na tym polegać. **Poprawka:** ciało bez długości czytane najwyżej do limitu, dłuższe 413, krótsze podane dalej
z pamięci (`ReadBody`). Test `DeviceBodyLimitFilterTest` (oba przypadki czerwone na pierwszej wersji, potem zielone).

### P18 — błąd (poprawiony): moja poprawka P4 gasiła kod, gdy „Połącz komputer” wyprzedziło listę

`play-history.js` `computers()` — `newest` zaczyna od -1. DJ otwiera kartę i od razu klika „Połącz komputer” (albo pobranie listy się
nie udało): `atCode = -1`, pierwsza lista (pusta: `newest = 0`) wygląda na „program wziął kod” i kod znika z panelu, choć jest ważny.
Stara wersja miała na to `shown >= 0`. **Poprawka:** kod zrobiony przed pierwszą listą bierze za punkt odniesienia pierwszą listę,
która przyjdzie. Scenariusz `deck-computers-code-before-list` (lista opóźniona o 1,5 s; czerwony, potem zielony).

### Poprawione w tym pliku

- P2: zegar do przodu nie był „obsłużony” — daje fałszywe trafienia; `sentAt` poprawia oba kierunki.
- P5: zdanie o katalogu domowym na macOS złagodzone (zależy od wersji systemu).

### Sprawdzone jeszcze raz, bez zmian

- `sentAt`: przesunięcie liczone na serwerze z jego `now`; niepoprawne `sentAt` (ujemne, spoza zakresu) — bez przesunięcia;
  sparowany program i tak mógł podać dowolne `at`, więc `sentAt` nie daje mu nic nowego. Wyniki po przesunięciu mieszczą się w
  zakresie `Instant`.
- Jeden kod na imprezę: `removeIf` na widoku Caffeine (≤ 10 000 wpisów) — `DjDeviceIT` zielony. Dwa jednoczesne „Połącz komputer”
  tej samej imprezy mogą zostawić dwa kody — nieszkodliwe (oba jednorazowe, 10 minut).
- `Settings`: na Windows `setPosixFilePermissions` rzuca `UnsupportedOperationException` (złapane), `ATOMIC_MOVE` z zastąpieniem
  istniejącego pliku działa (`MoveFileEx`).
- Okno: `wasPaired` przechodzi na `true` przy pierwszym odświeżeniu po sparowaniu, więc „odłączono” pojawi się po „Odłącz” w panelu,
  a nie po złym kodzie ani po własnym „Odłącz ten komputer”.
- Wyniki drugiego przejścia: `check.py unit` 1518/1518, scenariusze `deck-*` 8/8; od końca do końca 16/16 — to samo co wyżej
  i dodatkowo prawdziwe zapytanie chunked przez Tomcat (`curl -H "Transfer-Encoding: chunked"`): małe z tokenem 200, 200 KB 413.
- Dopisane do P13: zapytania z nieznanym tokenem nie mają limitu (jedno wyszukanie w indeksie na zapytanie, po P1/P17 najwyżej 128 KB
  ciała) — przed floodem chroni Cloudflare.

## Odpowiedzi właściciela i co z nich zrobione (2026-11-21)

Odpowiedzi na pytania 1–6 z sekcji „Pytania do właściciela”: „1. PRO”, „3. Zrób tak, by było wygodnie dla DJ-a”, pozostałe „jak
uważasz”.

1. **Pro — zrobione.** Nowa funkcja planu `PlanFeature.DECK` obejmuje całe „Zagrane samo”: kartę w przeglądarce
   (`/dj/dashboard/plays`) i program (`/dj/devices`, kod). Bez Pro panel nie pokazuje „🎧 Z programu DJ-a” wcale — zasada panelu:
   żadnej oferty Pro w kolejce (`aFreeOrganiser_seesProInTheCommentStyles_neverInTheQueue`); funkcję nazywa `/pro` (lista i
   porównanie, PL/EN). Sparowany program imprezy bez Pro dostaje `403 {"error": "plan"}`: token zostaje, piosenki są porzucane, okno
   mówi „„Zagrane samo” działa w planie Pro — ta impreza go teraz nie ma.” (inne 403, np. wyzwanie Cloudflare, dalej „Brak
   połączenia”). „Odłącz” działa w każdym planie, jak usuwanie logo czy biblioteki. **Uwaga:** plany na produkcji nie są jeszcze
   egzekwowane (`PLANS_ENFORCE=false`) — do tego czasu funkcja jest dla wszystkich, jak kiosk i pytanie do gości. Testy:
   `PlanFeatureEndpointsTest` (3 endpointy DECK, „Odłącz” wśród „everyone's”), `DeviceApiControllerTest` (403 plan),
   `DashboardPageRenderTest` (Free: nic; Pro: przycisk, karta, linki), `ProPageRenderTest` (czerwony bez wpisu DECK),
   `AgentTest.withoutPro_theSongsAreDropped_andTheTokenStays`, `ServerClientTest` (403 plan / inne 403).
2. **Obsługa z prawem `QUEUE` — zostaje, jak jest.** Powód: tą osobą bywa drugi DJ grający ze swojego laptopa — to on musi połączyć
   swój komputer, a „Zagrane samo” robi tylko to, co jego „Zagrane”. Odłączenie cudzego komputera jest odwracalne (nowy kod) i widać
   je w panelu. Token działa w imieniu imprezy, nie osoby; po usunięciu kogoś z obsługi jego komputer zostaje na liście, a właściciel
   może go odłączyć jednym kliknięciem.
3. **Pobieranie — zrobione.** `deck-agent.yml`: „Run workflow” z `main` wgrywa paczki do GitHub Release `zagrane` (repozytorium
   publiczne — bez konta, te same adresy dla każdej wersji, nie znikają po 30 dniach); z innej gałęzi tylko artefakty, do prób. Karta
   „Program na komputerze”: „Pobierz program: Windows · Mac (Apple M1 i nowsze) · Mac z procesorem Intel” i rozwijane „Pierwsze
   uruchomienie” (SmartScreen: „Więcej informacji” › „Uruchom mimo to”; macOS: Ustawienia systemowe › Prywatność i ochrona › „Otwórz
   mimo to”; jak sprawdzić, który Mac). Wersja paczki z `pom.xml` (już nie wpisana na sztywno — P16). **Przed scaleniem do `main`
   linki prowadzą donikąd, dopóki „Run workflow” z `main` nie zrobi wydania** — zrobić zaraz po scaleniu, przed powiedzeniem DJ-om.
4. **Mac z Intelem — dodany** (`macos-15-intel` w macierzy, paczka `Scan2Play-Zagrane-macos-intel.zip`). Koszt: jeden przebieg to
   trzy paczki, dwie na macOS (minuty ×10), tylko przy „Run workflow”. Niesprawdzone tutaj — workflow ruszy dopiero na GitHubie.
5. **Zapas zegara (P12) — 2 minuty, gdy wysyłający podał swój zegar** (`DeckHistoryService.SLACK_CLOCK_KNOWN`; bez `sentAt` dalej 5).
   Zostają 2, nie 0: piosenka zaczęta tuż przed prośbą to zwykle gość, który prosi o to, co właśnie startuje. Scenariusz z P12
   (zagrane 21:00, prośba 21:03, ponowne wysłanie) już nie oznacza. Test `withTheSendersClockKnown_aSongThreeMinutesBeforeTheRequest_marksNothing`,
   `DeviceApiControllerTest.theSendersClock_givesTheShorterSlack`.
6. **Limit złych kodów (P13) — 1000 na 10 minut łącznie** (10 na adres bez zmian) i jedno ostrzeżenie w logu, gdy zostanie osiągnięty.
   Zablokowanie parowania wszystkim wymaga teraz ~100 adresów zamiast 20. Wyższy limit to więcej prób zgadywania, ale zgadnięty kod daje
   tylko oznaczanie „Zagrane” jednej przypadkowej imprezy, którą DJ widzi na liście komputerów i odłącza — blokada parowania w trakcie
   imprez boli bardziej. Test `DjDeviceIT.wrongCodesFromTwentyFiveAddresses_doNotStopAGoodOne` (czerwony przy 200).

Sprawdzenia po tych zmianach (w kopii): `check.py unit` 1531/1531, `it` 219/219, `browser` 473/473, `audit -t dashboard,pro`
19 stron × 3 szerokości bez przewijania w bok i błędów (te same 4 stare małe cele), program `verify` 18/18. Od końca do końca 16/16
(plany wyłączone, jak na produkcji), a z `scan2play.plans.enforce=true` na koncie Free: brak „🎧 Z programu DJ-a” w panelu,
`/dj/devices/code` → 403, `/pro` z `data-feature="DECK"`. Workflow (Mac z Intelem, wydanie `zagrane`) — niesprawdzony; ruszy dopiero
po „Run workflow” na GitHubie.

## Trzecie przejście (niezależne, 2026-11-21)

Drugi, niezależny review całej gałęzi i poprawek pierwszego (P1–P18, odpowiedzi właściciela) — traktowanych jak cudzy kod.

### Werdykt

**Scalić po poprawkach** — poprawki P19–P23 i P26–P28 są już na gałęzi (lista „Poprawki” niżej). Nie znalazłem błędu, który psułby
kolejkę, dane ani bezpieczeństwo: oznaczenie idzie przez to samo warunkowe `UPDATE`, token jest tylko skrótem, publiczne endpointy
nie robią sesji i mają limit ciała (sprawdzone na prawdziwym Tomcacie, także chunked i UTF-16). Najważniejsze było to, czego testy
w kontenerze nie pokażą: wydanie paczek (P19, P20), komputery DJ-ów z innym układem folderów (P22, P23) i sprzeczny tekst w karcie
(P21). Przed powiedzeniem DJ-om nadal: „Run workflow” z `main`, próba zipa na Windows 11 i na Macu z M1 (P20, P24, P29), pytania niżej.

### Sprawdzenia (w kopii; nigdy Maven w repo)

- Przed poprawkami: `check.py unit` 1531/1531, `it` 219/219 (PostgreSQL 16 w kontenerze), `browser` 473/473, `audit -t dashboard,pro`
  19 stron × 3 szerokości bez przewijania w bok i błędów (te same 4 stare małe cele: „Szczegóły”, godziny w harmonogramie).
- Program: `sh mvnw -f deck-agent/pom.xml -B -ntp verify` — 18/18 przed, 21/21 po (3 nowe testy, każdy najpierw czerwony).
- Od końca do końca: aplikacja z kopii (`java -jar`, profil `local`) na jednorazowej bazie `s2p_e2e` (Flyway do V78), konto z hasłem
  (link potwierdzenia z logu), dwie prośby („Sanah - Szampan”, „Dawid Podsiadło - Małomiasteczkowy”), kod z `/dj/devices/code`,
  prawdziwy program `--pair <kod> --server http://localhost:8099` z `user.home` w katalogu tymczasowym — po „Połączono” sam przechodzi
  w czytanie. Plik `Documents/VirtualDJ/History/<dziś>.m3u` z linią sprzed 2 h (nic nie oznacza), potem linia „Sanah &amp; Kwiat
  Jab|łoni — Szampan (Radio Edit)” dopisana w dwóch kawałkach z 4 s przerwy → prośba „Zagrane” po 6 s, druga czeka. Plik tokenu 600,
  folder 700, w bazie 64 znaki skrótu. `curl` chunked z tokenem: małe 200, 200 KB 413; 200 KB z długością na `/pair` 413; bez tokenu
  401; ciało w UTF-16LE z `charset` — oznacza drugą prośbę (filtr podaje bajty, Jackson rozpoznaje kodowanie); `ISO-8859-2` 200; żadnego
  `Set-Cookie`. „Odłącz” w panelu → program słyszy 401 po 36 s i usuwa token z pliku. Potem aplikacja z
  `--scan2play.plans.enforce=true` (konto Free, program sparowany wcześniej): w panelu brak „🎧 Z programu DJ-a” i linków,
  `/dj/devices/code` 403, `GET /dj/devices` 403, `/dj/dashboard/plays` 403, program pokazuje `NO_PLAN` (token zostaje), `/pro` z
  `data-feature="DECK"`. W logach: kod imprezy i liczba oznaczonych — nigdy token, kod parowania ani nazwa komputera. Baza usunięta.
- Nie dało się sprawdzić tutaj: Windows, macOS, prawdziwy VirtualDJ / Serato, Cloudflare przed programem, sam workflow (rusza na GitHubie).

### Ustalenia

#### P19 — ryzyko (poprawione): wydanie paczek mogło zostać w połowie — nowa paczka obok starych albo brak pliku

`.github/workflows/deck-agent.yml` (przed poprawką: krok „Release” w każdym zadaniu macierzy, `cancel-in-progress: true` dla całego
`github.ref`). Scenariusz 1: „Run workflow” z `main`, paczka Windows już wgrana, a zadanie `macos-15-intel` pada (brak runnera, błąd
`jpackage`) — `fail-fast` przerywa pozostałe: w wydaniu Windows 1.0.1 obok Maców 1.0.0, tytuł wydania mówi już „1.0.1”. Scenariusz 2:
w trakcie „Run workflow” ktoś scala do `main` zmianę `deck-agent/` — `push` z tej samej grupy anuluje uruchomienie; `gh release upload
--clobber` usuwa stary plik przed wgraniem nowego, więc przerwanie między nimi zostawia link z karty bez pliku (404 u DJ-a). Do tego
`contents: write` miały zadania, które uruchamiają wtyczki Mavena pobrane z sieci — wystarczyłaby jedna zła wtyczka, by pisała do repo.
**Poprawka:** budowanie z tokenem tylko do odczytu; osobne zadanie `release` (`needs: package`, tylko z `main`) pobiera trzy artefakty,
sprawdza, że są wszystkie, i wgrywa je razem; grupa `concurrency` dla „Run workflow” jest jego własna i bez anulowania; wersja jako
wyjście zadania (`tr -d '\r'` na Windows). Bez testu — workflow rusza tylko na GitHubie (YAML sprawdzony parserem).

#### P20 — ryzyko (poprawione, niesprawdzone): Mac z M1 może zobaczyć „aplikacja jest uszkodzona” zamiast „Otwórz mimo to”

`deck-agent.yml` „Zip (macOS)”. Na procesorach Apple kod bez ważnego podpisu w ogóle się nie uruchamia (wystarcza podpis ad hoc).
`jpackage` bez `--mac-sign` nie gwarantuje spójnego podpisu całej paczki (Java Temurin ma swoje podpisy, `jlink` składa z niej nową);
paczka z niespójnym podpisem pobrana przez Safari (kwarantanna) daje macOS-owe „„Scan2Play Zagrane” jest uszkodzony i nie może zostać
otwarty” — bez przycisku „Otwórz mimo to”, więc instrukcja z karty nie działa. **Poprawka:** `codesign --force --deep --sign -` przed
spakowaniem (podpis ad hoc: bez konta Apple, bez nazwy dewelopera — Gatekeeper dalej pyta, zgodnie z decyzją „bez podpisu”).
Sprawdzić przy pierwszej próbie na Macu z M1: pobrać zip przeglądarką, rozpakować, dwuklik.

#### P21 — błąd (poprawiony): karta mówiła „ta karta musi zostać otwarta” nad programem, który działa bez karty

`templates/dashboard.html` (`dashboard.deck.privacy` na górze karty) i `dashboard.computer.help` („w tle — bez otwartej karty”).
Scenariusz: DJ w Safari (widzi tylko część o programie) czyta najpierw „Ta karta panelu musi zostać otwarta”, dwie linijki niżej „bez
otwartej karty” — nie wie, czy po połączeniu programu może zamknąć panel. **Poprawka:** „co trafia do Scan2Play” raz, nad obiema
częściami; „Ta karta panelu musi zostać otwarta.” (`dashboard.deck.browser.open`, PL/EN) tylko w części „W tej przeglądarce”. Test
`DashboardPageRenderTest.aProOrganiser_hasThePanelAsToday` (czerwony: zdanie przed `deckBrowser`).

#### P22 — ryzyko (poprawione): program czytał najnowszy plik pierwszego folderu, w którym był jakikolwiek — także sprzed lat

`deck-agent/.../NewestFile.java` (przed poprawką: `return` przy pierwszym folderze z plikiem), kolejność z `Platform.virtualDjFolders`:
`HomeFolder` z rejestru, `%LOCALAPPDATA%`, Dokumenty. Scenariusz: laptop, na którym kiedyś był starszy VirtualDJ (historia w
`Dokumenty\VirtualDJ\History`, a w rejestrze `HomeFolder` na ten folder), nowy VirtualDJ zapisuje w `%LOCALAPPDATA%`. Program czyta w
kółko `2024-05-01.m3u`, okno pokazuje „Czyta: VirtualDJ (2024-05-01.m3u)”, nic się nie oznacza, a DJ nie zauważy daty. **Poprawka:**
najnowszy plik ze wszystkich folderów. Test `HistoryFormatsTest.theNewestFile_ofAllTheFolders_notTheFirstOneWithAFile` (czerwony).

#### P23 — ryzyko (poprawione częściowo): Windows z kopią folderów w OneDrive — program „nie widzi historii”

`Platform.java`. Nowe laptopy z Windows 11 przy pierwszym uruchomieniu proponują kopię Dokumentów (i od 2023 Muzyki) w OneDrive —
foldery przenoszą się do `%OneDrive%\Documents`, `%OneDrive%\Music`; Serato pisze `_Serato_` do folderu Muzyka, starszy VirtualDJ do
Dokumentów. Program szukał tylko w `%USERPROFILE%\Music` / `Documents` → „Nie widać historii VirtualDJ ani Serato”. **Poprawka:** do
list dochodzą `%OneDrive%\Music\_Serato_\History\Sessions` i `%OneDrive%\Documents\VirtualDJ\History` (zmienna środowiska, poprawna
także dla nazwy użytkownika z polską literą). Test `windows_withOneDrive_looksInItsDocumentsAndMusicToo` (czerwony). **Zostaje:** gdy
Windows nazywa te foldery w OneDrive po polsku („Dokumenty”, „Muzyka” — nie wiem, czy tak robi), dalej nie widać; pewne byłoby
odczytanie znanego folderu z rejestru (`Shell Folders`) — razem z P24, po próbie na polskim Windows.

#### P24 — ryzyko (otwarte): `reg query` czytane jako UTF-8

`Platform.registryHomeFolder` — `new String(..., UTF_8)` z wyjścia `reg.exe`. Skierowane do potoku, `reg` pisze (o ile wiem) w stronie
kodowej konsoli — na polskim Windows CP852, nie UTF-8. Scenariusz: `HomeFolder` = `C:\Users\Michał\Documents\VirtualDJ\` → ścieżka
zniekształcona, folder nie istnieje. Zapasowe foldery (`%LOCALAPPDATA%`, `user.home`) są poprawne, więc dotyczy tylko `HomeFolder`
z polską literą. **Propozycja:** przy próbie na Windows użytkownik z polską literą; jeśli ścieżka jest zła — `cmd /c chcp 65001 >nul &
reg query …` albo PowerShell `Get-ItemProperty` z wyjściem UTF-8. Nie zmieniam na ślepo kodu, którego nie da się tu sprawdzić.

#### P25 — ryzyko (otwarte, pytanie): „Odłącz w każdym planie” jest tylko na serwerze — DJ bez Pro nie ma gdzie kliknąć

`DjDeviceController.list` wymaga `DECK`, karta jest ukryta bez Pro (`dashboard.html`), a `remove` planu nie wymaga. Od końca do końca
(Free): `GET /dj/devices` 403, karty nie ma — komputer zostaje na liście, której DJ nie zobaczy, dopóki nie wróci Pro. Bezpieczeństwu
to nie szkodzi (wywołania programu dostają 403), ale „Odłącz w każdym planie” z odpowiedzi właściciela nie jest wykonane w interfejsie.
Do tego „Odłącz ten komputer” w oknie programu tylko zapomina token u siebie — wiersz w `dj_device` zostaje, panel dalej pokazuje
komputer „ostatnio …” i liczy go do limitu 3. **Propozycja:** przycisk w programie woła `POST /api/device/unpair` (usuwa własny wiersz
po tokenie — działa w każdym planie, bez panelu). Zapisane w `05-1d` tak, jak jest. Pytanie do właściciela niżej.

#### P26 — drobiazg (poprawiony): po nowym sparowaniu program czekał do minuty na pierwsze wywołanie

`Agent.tick` — „żyje” liczone od ostatniego wywołania starego tokenu. Scenariusz: DJ paruje program z imprezą bez Pro — przez minutę
okno nie mówi nic, potem „działa w planie Pro”; albo zaraz po „odłączono w panelu” paruje na nowo i w ciągu minuty klika własne „Odłącz
ten komputer” — okno pokazuje znowu „odłączono w panelu” (stary wynik `DISCONNECTED`). **Poprawka:** nowy token = wywołanie od razu
i wynik od zera. Test `AgentTest.aNewPairing_isAskedAboutAtOnce` (czerwony).

#### P27 — drobiazg (poprawiony, bez testu): Mac — klik w ikonę w Docku nie pokazywał zamkniętego okna

`Window.java` — `HIDE_ON_CLOSE` chowa okno do paska menu; macOS przy kliknięciu ikony w Docku (albo ponownym otwarciu aplikacji, które
drugiej kopii nie uruchamia) wysyła zdarzenie „reopen”, którego nikt nie obsługiwał — program wygląda na zawieszony. **Poprawka:**
`AppReopenedListener` → pokaż okno. Swing bez ekranu — sprawdzić przy próbie na Macu.

#### P28 — drobiazg (poprawiony): emoji w tekście okna Swing

`Window.java` — „„🎧 Z programu DJ-a””. Czcionki Swinga na Windows (Tahoma / Segoe UI) mogą nie mieć tego znaku — DJ zobaczyłby kwadrat.
Bez emoji tekst prowadzi tak samo. (Na Windows niesprawdzone w żadną stronę.)

#### P29 — ryzyko (otwarte): Windows 11 może nie dać „Uruchom mimo to”

`dashboard.computer.first.windows`. Na świeżo zainstalowanym Windows 11 bywa włączona „Inteligentna kontrola aplikacji” (Smart App
Control) — blokuje program bez podpisu bez żadnego „Uruchom mimo to”; instrukcja z karty wtedy nie ma ścieżki. Tytuł okna SmartScreen
po polsku sprawdzić na Windows 11 (w karcie „System Windows ochronił komputer”; przyciski „Więcej informacji” › „Uruchom mimo to” są
dobre). Na macOS 15 po „Otwórz mimo to” w Ustawieniach jest jeszcze jedno okno i hasło — tekst mógłby to dodać. **Propozycja:** po
próbie jedno zdanie „Jeśli nie ma „Uruchom mimo to”, ten Windows nie uruchamia programów bez podpisu — napisz do nas”. Decyzja (beta).

#### P30 — ryzyko (otwarte, bez zmiany): zapas 2 min a Serato, które zapisuje piosenkę przy załadowaniu

`DeckHistoryService.SLACK_CLOCK_KNOWN`. Serato 2/3 dopisuje piosenkę przy załadowaniu na deck (jej start może być czasem załadowania).
Scenariusz: DJ ładuje „Szampan” o 21:00, gość prosi o 21:03, DJ gra o 21:05 — start 21:00 < 21:03 − 2 min, prośba czeka (DJ klika
„Zagrane” sam). Pominięcie jest bezpieczne, fałszywe trafienie nie; VirtualDJ zapisuje po `historyDelay` od startu, więc tam problemu
nie ma. Ta sama piosenka dwa razy (P12) po zmianie na 2 min nie oznacza się przy ponownym wysłaniu — sprawdzone testem z pierwszego
review i od końca do końca (linia sprzed 2 h). Zostawiam: zmienić dopiero po prawdziwej historii Serato.

#### P31 — drobiazg (otwarte): `FOR UPDATE` przy parowaniu wstrzymuje na chwilę dodawanie próśb tej imprezy

`DjDeviceService.java:111`. `FOR UPDATE` na wierszu `party_settings` koliduje z `FOR KEY SHARE`, które bierze klucz obcy przy `INSERT`
prośby — gość czeka przez czas transakcji parowania (milisekundy). `FOR NO KEY UPDATE` tak samo szereguje dwa parowania i
`changeCode`, a próśb nie wstrzymuje. Do zrobienia przy następnej zmianie (zysk znikomy, potrzebny test IT na dwóch połączeniach).

#### P32 — drobiazg (otwarte): dwie karty (właściciel i obsługa) — jedna pokazuje martwy kod

`DjDeviceService.newCode` (jeden kod na imprezę, P3) i `play-history.js`. Obsługa klika „Połącz komputer” po właścicielu: kod
właściciela umiera, a jego panel pokazuje go dalej „ważny 10 min”. DJ wpisuje go — „Ten kod nie działa — weź nowy z panelu”: tekst
prowadzi dobrze. Rzadkie (dwie osoby parują w tej samej chwili). Zostaje.

#### P33 — drobiazg (otwarte): wydanie `zagrane` zostaje na pierwszym commicie

`gh release create --target` ustawia tag raz; kolejne „Run workflow” wymieniają pliki, tag i „Source code (zip)” na stronie wydania
zostają z pierwszego razu. Nie szkodzi (linki w karcie prowadzą do plików), myli tylko na stronie GitHuba.

### Sprawdzone i w porządku (niezależnie)

- `DeviceBodyLimitFilter`: długość ponad limit — 413 bez czytania; bez długości — `readNBytes(MAX + 1)`; `ReadBody` podaje te same bajty
  (`getInputStream` i `getReader` z kodowaniem z nagłówka, domyślnie UTF-8); Jackson czyta z `getInputStream` i sam rozpoznaje UTF-16
  (od końca do końca). Filtr działa po Spring Security, ale ta nie czyta ciała tych ścieżek (CSRF wyłączone) — kolejność bez znaczenia.
- `sentAt`: przesunięcie z `now` serwera; `sentAt` spoza zakresu (ujemne, ponad 10^11) — bez przesunięcia i dłuższy zapas; `at` po
  przesunięciu w przyszłości przycięte do „teraz”, starsze niż 12 h pominięte; ponowne wysłanie (restart, odświeżenie) poprawione
  zapasem 2 min. Program i panel liczą `sentAt` z tego samego zegara co czasy w plikach.
- Kod w panelu po najwyższym `id` (P4, P18): przy liście, która nie przyszła, punktem odniesienia jest pierwsza lista — teoretycznie
  lista wysłana przed kliknięciem, a odebrana po sparowaniu, zostawi kod na ekranie do 10 min (nieszkodliwe; DJ widzi komputer na liście).
- `Settings`: `createTempFile` na POSIX tworzy plik 600 od razu, folder 700, `ATOMIC_MOVE` z zastąpieniem (sprawdzone: 600/700).
  Na Windows plik w profilu użytkownika.
- Okno: `wasPaired` i `pack()` (P6) — poprawne; po P26 stary wynik nie przechodzi na nowy token.
- Pro: każda ścieżka bez Pro — od końca do końca (wyżej); kod zrobiony tuż przed końcem Pro paruje (parowanie planu nie sprawdza),
  pierwsze wywołanie dostaje 403 planu i okno mówi o Pro (po P26 od razu). Koniec Pro w trakcie: karta w przeglądarce dostaje 403 →
  „Panel nie może teraz wysyłać — odśwież stronę.”, po odświeżeniu karty nie ma; program pokazuje tekst o Pro.
- Bezpieczeństwo: token 32 bajty `SecureRandom`, w bazie SHA-256, wyszukanie po unikalnym indeksie; kod 6 cyfr: przy limicie 1000
  złych kodów na 10 min i (zwykle) 1–2 żywych kodach naraz trafienie to ~0,1–0,2 % na okno — przy ciągłym ataku z ~100 adresów kilka
  dziesiątych procent na godzinę; zysk: oznaczanie „Zagrane” jednej przypadkowej imprezy, widoczne na liście komputerów (świadoma
  decyzja właściciela, P13). IDOR w `/dj/devices/remove` — `WHERE id = ? AND party_code = ?` z sesji. Program wysyła tylko wykonawcę,
  tytuł, czas, `sentAt` i raz nazwę komputera (`COMPUTERNAME`, bywa imieniem — polityka prywatności ją wymienia); nigdy ścieżek.
  Workflow: PR z forka dostaje tylko testy z tokenem do odczytu; „Run workflow” wymaga prawa zapisu, a publikuje tylko z `main` — ktoś
  z prawem zapisu mógłby zmienić workflow na swojej gałęzi, ale to prawo i tak daje mu repozytorium.
- Kod: komentarze mówią „dlaczego”; zapytania ograniczone (`findTop400`, `LIMIT 3`, indeksy); parsery JS i Java różnią się tylko
  rozpoznawaniem milisekund i czasu od 2001 (P16, opisane). `validPlays` / `songsOf` / `clockKnown` w `DjSongController` używane też
  przez `DeviceApiController` (P16) — nadal do przeniesienia do `DeckHistoryService` przy następnej zmianie.

### Błędne lub niepełne w poprzednich przejściach

- Odpowiedź właściciela 1: „„Odłącz” działa w każdym planie” — tylko na serwerze; w interfejsie bez Pro nie ma listy ani przycisku (P25).
- „Sprawdzone i w porządku” pierwszego review: „Nowy plik dnia / nowa sesja Serato: najnowszy plik po czasie zmiany” — tylko w pierwszym
  folderze, który miał jakikolwiek plik (P22).
- Odpowiedź właściciela 3: „Run workflow z `main` wgrywa paczki do wydania” — tak, ale każda paczka osobno, z możliwym stanem pół na pół
  (P19); i bez podpisu ad hoc paczka dla M1 mogła nie dać „Otwórz mimo to” (P20).
- Testy robią to, co mówią nazwy (sprawdzone: `DeviceBodyLimitFilterTest` na atrapie, potwierdzone prawdziwym `curl`;
  `wrongCodesFromTwentyFiveAddresses…` — 250 złych kodów, czerwony przy limicie 200; `SettingsTest` — uprawnienia po zapisie).

### Pytania do właściciela

1. **P25:** czy „Odłącz ten komputer” w programie ma usuwać komputer także z panelu (nowy `POST /api/device/unpair`)? I czy bez Pro panel
   ma pokazywać choćby listę komputerów z „Odłącz” (to byłoby coś z „Zagrane samo” w kolejce bez Pro, wbrew zasadzie „żadnej oferty”)?
2. **P29:** dopisać do „Pierwsze uruchomienie” zdanie o Windows, który nie daje „Uruchom mimo to”, czy poczekać na próbę?
3. **P23/P24:** czy przy próbie na Windows sprawdzić użytkownika z polską literą i kopię folderów w OneDrive (wtedy dokończę odczyt
   znanych folderów z rejestru)?

### Poprawki

Zrobione na gałęzi (każda zmiana zachowania z testem widzianym najpierw na czerwono, chyba że napisano inaczej):

- P19 workflow: budowanie bez prawa zapisu, zadanie `release` po wszystkich trzech paczkach, „Run workflow” bez anulowania — bez testu
  (GitHub), YAML sprawdzony parserem; `actions/download-artifact` przypięty do commita v7.0.0.
- P20 podpis ad hoc paczki Mac (`codesign --sign -`) — bez testu, do sprawdzenia na Macu z M1.
- P21 tekst „karta musi zostać otwarta” tylko w części przeglądarki — `DashboardPageRenderTest` czerwony, potem zielony.
- P22 najnowszy plik ze wszystkich folderów — `HistoryFormatsTest` czerwony, potem zielony.
- P23 foldery OneDrive na Windows — `HistoryFormatsTest` czerwony, potem zielony (polskie nazwy folderów zostają, P24).
- P26 nowe sparowanie pytane od razu — `AgentTest` czerwony, potem zielony.
- P27 Dock na Macu, P28 emoji w oknie — bez testu (Swing bez ekranu).
- Dokumentacja: `05-1d` (tekst karty, foldery programu, wywołanie po sparowaniu, Dock, workflow, „Odłącz” bez Pro), `docs/ideas.md`
  p. 19, `SESSION_HANDOFF.md`.

Wyniki po poprawkach (w kopii): `check.py unit` 1531/1531, `browser` 473/473, `audit -t dashboard,pro` 19 stron × 3 szerokości bez
przewijania w bok i błędów (te same 4 stare małe cele), program `verify` 21/21; `it` 219/219 bez zmian SQL od pomiaru wyżej.

Zostawione: P24 (niesprawdzalne tutaj), P25 i P29 (pytania), P30 (bez prawdziwej historii Serato), P31–P33 (drobne, przy następnej
zmianie); z wcześniejszych P11 (Cloudflare), P14, P15, P16 bez zmian.

### Odpowiedzi właściciela na pytania trzeciego przejścia i co z nich zrobione (2026-11-21)

Na wszystkie trzy: „jak uważasz”.

1. **P25 — zrobione tak:** „Odłącz ten komputer” w oknie programu woła nowe `POST /api/device/unpair` (token w nagłówku, bez ciała;
   204, nieznany token 401; w każdym planie; publiczne i bez CSRF jak `/pair` i `/plays`) — komputer znika z listy w panelu i jego
   token umiera; bez sieci program i tak zapomina token u siebie (wiersz zostaje do „Odłącz” w panelu albo czwartego komputera).
   Panel bez Pro dalej nie pokazuje listy — zasada „żadnej oferty w kolejce” zostaje; komputer odłącza się z programu. Testy
   (czerwone przed kodem — nie kompilowały się): `DeviceApiControllerTest.theProgramsOwnOdlacz_removesItsComputer_byItsToken_inAnyPlan`,
   `DeviceApiWebTest` (bez logowania, CSRF i sesji), `DjDeviceIT.theProgramsOwnOdlacz_removesOnlyItsOwnComputer`,
   `AgentTest.theWindowsOdlacz_tellsTheServer_andForgetsTheToken`, `ServerClientTest.unpair_sendsTheToken_andIsDoneOn204Or401`.
2. **P29 — zrobione:** „Pierwsze uruchomienie” mówi, że gdy „Uruchom mimo to” się nie pojawia, ten Windows nie uruchamia programów bez
   podpisu i zostaje czytanie w Chrome lub Edge; na Macu „na dole „Otwórz mimo to”, i potwierdź hasłem”. Bez nazwy funkcji Windows (jej
   polskiej nazwy nie sprawdziłem). Test `DashboardPageRenderTest.aProOrganiser_hasThePanelAsToday` (czerwony).
3. **P23/P24 — zrobione bez czekania na próbę, ale tak, by nic nie psuło:** na Windows program pyta raz przy starcie PowerShell (wyjście
   UTF-8), gdzie są Muzyka i Dokumenty (`[Environment]::GetFolderPath` — OneDrive i polskie nazwy folderów) i gdzie wskazuje `HomeFolder`
   VirtualDJ (polska litera w nazwie użytkownika cała). To dodatkowe foldery obok dotychczasowych — przy braku PowerShell albo po 10 s
   bez odpowiedzi zostają zwykłe, a `reg query` dalej jest zapasem dla `HomeFolder`; od P22 wygrywa najnowszy plik wszystkich folderów.
   Test `HistoryFormatsTest.windows_theKnownFoldersOfWindowsItself_areLookedInToo` (czerwony; ścieżki z „Michał” — surefire programu
   dostał `LC_ALL=C.UTF-8`, bo JVM w lokalu POSIX nie zbuduje takiej ścieżki na Linuksie). Na Windows niesprawdzone: przy próbie
   zobaczyć w oknie „Czyta: …” dla użytkownika z polską literą.

Sprawdzenia po tych zmianach (w kopii): `check.py unit` 1532/1532, `it` 220/220, `browser` 473/473, `audit -t dashboard,pro` 19 stron × 3
szerokości bez przewijania w bok i błędów (te same 4 stare małe cele), program `verify` 24/24.

## Próby na prawdziwych komputerach (2026-11-21, godziny lokalne)

Właściciel, Windows 11 z VirtualDJ 8.5, produkcja (po scaleniu #297 / #298).

**Działa:**
- SmartScreen pokazuje „Uruchom mimo to” po „Więcej informacji” — **P29 odpada** (ten Windows nie ma Smart App Control). Tytuł okna
  to „System Windows ochronił ten komputer” — karta mówiła „…ochronił komputer”; poprawione.
- Okno czytelne; program sam znalazł `%LOCALAPPDATA%\VirtualDJ\History` („Czyta: VirtualDJ (2026-11-21.m3u)”).
- Parowanie kodem z panelu.
- Prośba dodana ręcznie oznacza się sama ok. **47 s** po puszczeniu w VirtualDJ: VirtualDJ dopisuje piosenkę do historii po
  `historyDelay` (ok. 45 s), program patrzy co 3 s. Druga prośba, niegrana, zostaje w kolejce.
- Restart programu nie oznacza niczego drugi raz.
- „Odłącz ten komputer” w programie usuwa komputer z listy w panelu (`/api/device/unpair`).
- „Odłącz” w panelu o **17:35:40**: po ok. minucie (wywołanie „żyję” raz na minutę) program pyta o nowy kod: „Ten komputer odłączono w
  panelu — połącz go nowym kodem.”
- Zły kod: „Ten kod nie działa — weź nowy z panelu (kod jest ważny 10 minut).”
- `POST /api/device/plays` bez tokenu z produkcji: 401 od aplikacji — Cloudflare nie blokuje programu, reguła „Skip” niepotrzebna
  (**P11 zamknięte**).
- Wdrożenie po scaleniu: SUCCESS, Flyway 78, bez WARN. Wydanie `zagrane` ma trzy pliki; „Run workflow” z `main` zadziałał.

**Znalezione i poprawione po próbie** (gałąź `claude/gallant-ritchie-rl80ux`, każde z testem widzianym najpierw na czerwono):
- **Dwie ikony w zasobniku naraz** — druga kopia programu (wysyłałaby każdą piosenkę dwa razy). `SingleInstance`: blokada
  `FileChannel.tryLock` na `~/.scan2play/zagrane.lock` przez całe życie programu (system zdejmuje ją, gdy program się kończy, także
  po awarii); druga kopia mówi „Program już działa — kliknij jego ikonę w zasobniku (obok zegara) i wybierz „Pokaż”.” i kończy się (w
  konsoli wiersz i kod 1). `SingleInstanceTest`: ta sama JVM i drugi proces.
- Pusty kod: „Wpisz kod z panelu.” zamiast „Ten kod nie działa” (i bez wywołania serwera). `WindowTest`.
- Okno pokazywało „✓ Zagrane samo: …” dopiero po pierwszym oznaczeniu i tylko piosenki z ostatniej odpowiedzi (zwykle jedną) —
  wyglądało, jakby listy nie było (dokumentacja obiecywała „the songs marked”). Teraz trzy ostatnie od uruchomienia, a przed pierwszą
  szary wiersz „Zagrane samo: jeszcze nic od uruchomienia programu.” `AgentTest.theMarkedSongs_add_upToTheLastThree`.
- Karta: „około 45 sekund po puszczeniu” (czas VirtualDJ), poprawny tytuł SmartScreen.
- **Wariant E zaparkowany** (decyzja właściciela: DJ miałby za dużo do wyboru): z panelu zniknęło „W tej przeglądarce (Chrome, Edge)”,
  jego JS, scenariusze `deck-reader-*` i `POST /dj/dashboard/plays` (służył tylko jemu; wspólne `DeckHistoryService` i sprawdzanie
  piosenek przeniesione do `DeviceApiController`). Zdanie „wtedy zostaje czytanie w Chrome lub Edge” → „napisz do nas przez „Pomoc i
  kontakt””. Kod wariantu E: commit `6c25e0a` (scalenie #297 na `main`).

- `/pro` („albo karta panelu w Chrome”) i polityka prywatności („przeglądarka albo program”) mówią już tylko o programie
  (`ProPageRenderTest`, `SmokeTest.privacy_saysOnlyTheProgramReadsThePlayHistory`, czerwone przed zmianą).

Sprawdzenia (w kopii): `check.py unit` 1530/1530, `browser` 469/469, `audit` 148 stron × 3 szerokości bez przewijania w bok i błędów
(te same 4 stare małe cele), program `verify` 28/28. `it` nie uruchamiane: bez zmian SQL i migracji.

**Obserwacja bez zmiany:** „Odłącz ten komputer” w programie nie pyta o potwierdzenie. Skutek pomyłki: trzeba wziąć nowy kod z panelu
(minuta). Zostawione — do decyzji, jeśli DJ-e będą klikać przez pomyłkę.

**Nieprzetestowane (brak sprzętu):** Mac z M1 i z Intelem — „Otwórz mimo to”, podpis ad hoc, klik w Dock (**P20, P27**); konto
Windows z polską literą w nazwie (**P23**); Dokumenty w OneDrive z historią tam (**P24**; Dokumenty właściciela są w OneDrive, ale
historia VirtualDJ jest w AppData, więc ta droga nie była użyta); prawdziwa historia Serato (P30).

**610 odpowiedzi 4xx w ciągu 24 h przed wydaniem — wyjaśnione** (Railway, tylko odczyt, agent `railway-reader`): jeden skaner szukający
haseł z jednego adresu (`93.123.109.x`), 2026-11-21 14:15–14:19:26 UTC, w szczycie ok. 80 zapytań na sekundę; same GET po ścieżkach
typu `/.env`, `/.aws/credentials`, `/.git/config`, `/config/openai.json`, `/phpinfo.php`, ze zmienianym nagłówkiem przeglądarki.
Odpowiedzi: 404 (z jednej próbki 501 wierszy: 469) i 400 (32 — dziwne ścieżki, np. `//`); żadna nie trafiła w ścieżki aplikacji ani
`/api/device/*`, nic nie zostało wydane. Pozostałe ok. 70 × 4xx dnia rozproszone, wielkość zwykłego szumu skanerów. W aplikacji nic do
zrobienia; zablokowanie adresu w Cloudflare — do decyzji właściciela.
