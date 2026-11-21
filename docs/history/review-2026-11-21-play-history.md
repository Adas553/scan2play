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
**Poprawka:** `config/DeviceBodyLimitFilter` — dla `/api/device/*` ciało powyżej 128 KB to 413, bez długości (chunked) 411, zanim
cokolwiek przeczyta. 128 KB mieści najgorszy prawdziwy przypadek (20 piosenek × 1000 znaków × 4 bajty). Test
`DeviceApiWebTest.aBodyOfMegabytes_isRefusedBeforeItIsRead_toThePairingAndToTheSongsAlike` (czerwony: 401 zamiast 413).

### P2 — błąd (poprawiony): zegar komputera DJ-a spóźniony o więcej niż 5 minut — nic się nie oznacza, po cichu

`DeckHistoryService.java:79` — prośba jest oznaczana tylko przez piosenkę, której `at` ≥ czas prośby − 5 min (`CLOCK_SLACK`, :36).
`at` to zegar komputera DJ-a (VirtualDJ `lastplaytime`, Serato start). Scenariusz: laptop bez synchronizacji czasu (wyłączona w
Windows, BIOS po wymianie baterii, ręcznie ustawiona godzina) chodzi 8 minut do tyłu. Gość prosi o 21:00, DJ gra o 21:10 według
serwera, plik zapisuje 21:02 — „zagrane przed prośbą”, nic się nie oznacza, ani DJ, ani program tego nie widzi. Zegar do przodu jest
już obsłużony (`at` z przyszłości = teraz, :66).
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
`chmod 600`; folder zostawał `rwxr-xr-x`; awaria w trakcie `store` zostawiała pusty plik (DJ musi parować od nowa). Na macOS
`/Users/<dj>` jest zwykle zamknięty dla innych, więc realne ryzyko to wspólny komputer z kilkoma kontami albo Linux.
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

- P1 `DeviceBodyLimitFilter` (413 / 411 dla `/api/device/*`) — `DeviceApiWebTest` czerwony (401), potem zielony.
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
