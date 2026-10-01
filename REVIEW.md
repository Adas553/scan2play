# Przegląd całego projektu Scan2Play — czytelność, wydajność, skalowalność

Sesja 2026-09-30 (siódma), **tylko do odczytu**: kod nie był zmieniany, nic nie zostało zacommitowane. Stan na starcie:
`dev...origin/dev` (nic do przodu), HEAD `979f881`; w drzewie roboczym były już **cudze niezacommitowane zmiany** w
`PROJECT_CONTEXT.md`, `SESSION_HANDOFF.md`, `fragments/fallback-queue.html`, `FallbackQueueFragmentTest.java` — przegląd ich nie
dotyka i ich nie ocenia.

Skala wagi: **W** — wysoka (błąd widoczny dla DJ-a/gościa, dane lub bezpieczeństwo), **Ś** — średnia (wydajność/skalowalność,
która zaboli przy większym ruchu, albo ryzyko utrzymania), **N** — niska (porządki, czytelność). Nakład: **XS** < 30 min,
**S** ≤ 2 h, **M** ≤ 1 dzień, **L** > 1 dzień. „PEWNE” = sprawdzone w kodzie, „PRAWDOPODOBNE” = wynika z kodu, nie odtworzone.

Poza zakresem (decyzja właściciela): wznawianie utworu po odświeżeniu strony — zostaje jak jest.

Obszary dopisywane kolejno: 1. Baza i zapytania · 2. Kolejka i lease · 3. Skrypty przeglądarkowe · 4. Integracje zewnętrzne ·
5. Bezpieczeństwo · 6. Testy · 7. Dokumentacja i ogólne · na końcu zbiorcza lista wg wagi.

---

## 1. Baza i zapytania

### 1.1 [Ś] Cache `partySettings` nie jest czyszczony przy usunięciu konta — PEWNE
`AccountDeletionService.java:143-174` usuwa wiersz `party_settings`, ale nie robi `@CacheEvict("partySettings")`. Przez do 24 h
(`AppConfig.java:50`) `PartySettingsQueryService.getSettings` zwraca z pamięci usuniętą imprezę — **razem z tokenami Spotify**
(encja trzyma `spotifyAccessToken` / `spotifyRefreshToken`). Skutki: gość z QR-kodem nadal może wysyłać prośby do nieistniejącej
imprezy (tabela `song_requests` nie ma klucza obcego, więc wiersze powstaną jako sieroty), a dane, które miały zniknąć „na żądanie
użytkownika” (polityka Google API), żyją jeszcze w pamięci. To samo dotyczy `qr-codes`, ale tam są tylko obrazki linku.
**Poprawka:** `@CacheEvict(value = "partySettings", key = ...)` (klucz = partyCode, więc najprościej `Cache#evict` w serwisie po
odczytaniu kodu). **Nakład:** XS (+ test).

### 1.2 [Ś] Cache trzyma i udostępnia współdzieloną, mutowalną encję JPA — PEWNE
`PartySettingsQueryService.java:24-29` (`@Cacheable`) i `PartySettingsCommandService.java:66-74` (`@CachePut`) wkładają do Caffeine
**tę samą instancję** `PartySettingsEntity` (`@Data`, settery), którą potem dostają wszystkie wątki. Każdy kod, który zrobi
`settings.setX(...)` na obiekcie z `getSettings`, zmieni stan widoczny dla wszystkich żądań bez zapisu do bazy. Dziś nikt tego nie
robi (zapis idzie przez `updateSettings`, który czyta świeżą encję), ale to pułapka dla następnej zmiany. Do tego `@Data` generuje
`toString()` z tokenami Spotify — jeden `log.debug("{}", settings)` i tokeny są w logach.
**Poprawka:** cache'ować niemutowalny `record PartySettingsView` (albo przynajmniej `@ToString.Exclude` na tokenach i `@Getter/@Setter`
zamiast `@Data`). **Nakład:** S (`@ToString.Exclude`: XS).

### 1.3 [Ś] ⏭ tuż po starcie piosenki gościa może ją podać drugi raz — PRAWDOPODOBNE
`DjService.java:113-122` szuka następnej piosenki gościa w `getDashboardQueue` — cache 3 s (`AppConfig.java:53`), którego
`markSongAsPlayed` (`DjService.java:144-156`) **nie unieważnia**. Klient potwierdza piosenkę (`POST /play`) dopiero na `PLAYING`,
fire-and-forget (`youtube-autopilot.js:147-153, 201-205`). Jeśli DJ naciśnie ⏭ w ciągu ~3 s od startu piosenki gościa (albo zanim
`/play` dojdzie), `next-track` odda **tę samą** piosenkę z nieświeżego cache'u. To samo dla ⏭ w trakcie ładowania (wtedy nawet bez
cache'u — piosenka nie jest jeszcze „played”).
**Poprawka:** `@CacheEvict("dashboardQueue")` w `markSongAsPlayed`/`pushToSpotify`/`addDjPick` oraz po stronie klienta przekazanie
bieżącej piosenki gościa w `exclude` przy ⏭ (albo serwer pomija id z `nowPlaying`). Najpierw scenariusz przeglądarkowy „⏭ po 1 s
piosenki gościa” — czerwony przed zielonym. **Nakład:** S.

### 1.4 [Ś] Import playlisty = do 500 pojedynczych INSERT-ów — PEWNE
`FallbackTrackCommandService.java:96-108`: `saveAll` na encjach z `GenerationType.IDENTITY` (`FallbackTrackEntity`) — Hibernate
nie umie wtedy batchować, a `hibernate.jdbc.batch_size` i tak nie jest ustawione (`application.properties`). 500 utworów = 500
round-tripów w jednej transakcji, pod advisory lockiem kolejki, więc w tym czasie `next-track` tej imprezy czeka. Lokalnie to
milisekundy, na Railway (sieć między usługami) to może być 0,5–2 s.
**Poprawka:** jeden natywny `INSERT … SELECT FROM unnest(?::text[], ?::text[], ?::int[])` albo sekwencja + `batch_size=50` +
`order_inserts`. **Nakład:** S (+ sprawdzenie na prawdziwym PostgreSQL wg `CLAUDE.md`).

### 1.5 [Ś] Brakujące/zbędne indeksy — PEWNE
- `fallback_track`: jedyny indeks to `(party_code, status)` (`V2`). Każde zapytanie kolejki filtruje też `playlist_id` i sortuje
  po `play_order, playlist_position`; `requeuePlayedTracks` i `findLatestFetchedAt` robią `MAX(fetched_at) WHERE party_code = ?`
  po **wszystkich** wierszach imprezy, łącznie z `CANCELLED` (każda zmiana playlisty dokłada do 500 wierszy na 30 dni).
  `findLatestFetchedAt` wykonuje się przy **każdym** podaniu utworu tła (`NextTrackService.java:223-229`). Indeks
  `(party_code, playlist_id, status, play_order, playlist_position)` + `(party_code, fetched_at)` obsłużyłby wszystko.
- Czystki nocne filtrują po `fetched_at` / `requested_at` bez indeksu: `SongRequestRetentionService` robi do **200 pętli**
  `DELETE … WHERE id IN (SELECT id … WHERE requested_at < ? LIMIT 1000)` (`SongRequestRepository.java:94-98`) — każda to pełny skan
  `song_requests`. Dziś tabela jest mała, ale przy zaległości 200 000 wierszy to 200 pełnych skanów.
- Zbędne: `idx_owner_id` dubluje UNIQUE na `owner_id`, `idx_party_code` na `song_requests` jest prefiksem `idx_party_decision_time`
  — koszt przy każdym zapisie, zero zysku.
**Poprawka:** migracja `V9` z indeksami (i `DROP INDEX` zbędnych, zsynchronizowane z `@Index` w encjach, bo Hibernate waliduje).
**Nakład:** S (+ `EXPLAIN` na bazie `s2p_*`).

### 1.6 [N] Pętla 10 prób w `takeNextTrack` jest martwa pod advisory lockiem — PEWNE
`FallbackTrackCommandService.java:317-352`: od kiedy każda operacja na kolejce bierze `pg_advisory_xact_lock` (`:318`), nikt inny nie
może zabrać utworu między `SELECT` a `claimQueuedTrack`; „lost the race” (`:349`) nie może się zdarzyć, a `MAX_TAKE_ATTEMPTS` z
komentarzem o „kilku otwartych dashboardach” (`:51-55`) wprowadza w błąd. Warunkowy UPDATE można zostawić jako pas bezpieczeństwa,
ale pętla i jej dokumentacja to dziś szum. **Nakład:** XS.

### 1.7 [N] Klucz advisory locka to 32-bitowy `String.hashCode()` — PEWNE
`FallbackTrackCommandService.java:77-79`. Kolizja kluczy dwóch imprez nie psuje danych, tylko serializuje ich kolejki; ale
`pg_advisory_xact_lock(int, int)` z własną przestrzenią (np. stała + `hashtext(party_code)`) albo po prostu `SELECT … FOR UPDATE` na
wierszu `party_settings` byłby czytelniejszy i bez kolizji. **Nakład:** XS.

### 1.8 [N] Czas lokalny bez strefy (`LocalDateTime.now()` + `timestamp without time zone`) — PEWNE
Wszystkie znaczniki (`played_at`, `fetched_at`, `requested_at`) zależą od strefy JVM. Jeśli produkcja kiedyś pobiegnie w
`Europe/Warsaw` (albo lokalny test przez noc zmiany czasu), w noc z października na listopad godzina się powtórzy i oś historii
oraz ⏮ (`ORDER BY played_at DESC`) pomieszają kolejność. Na Railway JVM jest pewnie w UTC — wtedy to tylko pułapka.
**Poprawka:** `spring.jpa.properties.hibernate.jdbc.time_zone=UTC` + `Instant`/`timestamptz` przy następnej migracji, albo choćby
wymuszenie `-Duser.timezone=UTC`. **Nakład:** XS (flaga) / M (pełna migracja typów).

### 1.9 [N] `getUpcoming` liczy to, co już ma — PEWNE
`FallbackQueueService.java:48-55`: `remaining = count(QUEUED)` zaraz po wczytaniu **wszystkich** queued (limit 500 = maksimum
importu) — to zawsze `tracks.size()`. Jedno zapytanie mniej w ścieżce, która (patrz 2.1) biegnie co 3 s na okno. **Nakład:** XS.

---

## 2. Kolejka i lease (serwer)

### 2.1 [Ś] Każdy raport lease czyta i haszuje całą kolejkę tła — PEWNE
`DjPlayerLeaseController.java:219-223` → `FallbackQueueService.getVersion` (`FallbackQueueService.java:73-75`) → `getUpcoming`:
**4 zapytania** (do 500 encji `fallback_track` materializowanych przez Hibernate, 2× `count`, `exists`) + `hashCode()` całego
rekordu z listą. Raport idzie co 3 s z **każdego** otwartego okna dashboardu (plus dodatkowy przy każdym PLAYING/PAUSED). Przy
3 oknach i playliście 500 utworów to ~4 zapytania i ~500 encji na sekundę na imprezę — tylko po to, żeby odpowiedzieć „nic się nie
zmieniło”. Dziś niewidoczne (jedna impreza), ale to największy stały koszt serwera i rośnie liniowo z długością playlisty × liczbą okien.
**Poprawka (od najtańszej):** (a) w pamięci `ConcurrentHashMap<partyCode, AtomicLong>` podbijany przez każdą metodę
`FallbackTrackCommandService` i zmianę ustawień shuffle/playlisty — aplikacja i tak jest jednoinstancyjna (lease też jest w pamięci);
(b) albo tanie zapytanie agregujące (`count`, `max(id)`, `sum(play_order)`, `bool_or(manual_move)`) po indeksie z 1.5;
(c) albo krótki cache (2 s) wyniku `getVersion`. **Nakład:** S.

### 2.2 [Ś] Brak kompresji odpowiedzi HTTP — PEWNE
Nigdzie nie ma `server.compression.enabled` (`application.properties`). Fragment „up next” to ~1 KB na wiersz
(`fragments/fallback-queue.html:38-72`: długie klasy Bootstrapa i cztery atrybuty `title` z tekstami w każdym wierszu), czyli do
~500 KB przy pełnej playliście — wysyłane przy każdej zmianie wersji (każdy podany utwór tła, każdy ruch/przeciągnięcie/✕), także na
telefon po komórce. HTML tego typu kompresuje się gzipem ~10×. Za Cloudflare kompresja może już zachodzić na brzegu, ale nie między
Railway a Cloudflare i nie lokalnie przez `dev.scan2play.com.pl`.
**Poprawka:** `server.compression.enabled=true`, `server.compression.mime-types=text/html,application/json,text/css,application/javascript`,
`server.compression.min-response-size=2KB`. Dodatkowo (opcjonalnie) przenieść stałe `title` przycisków na poziom listy
(`data-*` + jeden tooltip w JS) — wiersz spada do ~300 B. **Nakład:** XS (kompresja) / S (odchudzenie wiersza + testy fragmentu).

### 2.3 [Ś] Aplikacja jest jednoinstancyjna „z konstrukcji” — PEWNE (udokumentowane w Sekcji 13)
W pamięci procesu: lease i komendy (`PlayerLeaseService.java:59-60`), wszystkie cache Caffeine (`AppConfig.java:47-57`), limity
gości w `HttpSession` (`GuestSessionService.java`), blokada importu (`NextTrackService.java:185-189`), sesje logowania DJ-a
(Tomcat). Druga replika na Railway = dwa okna mogą grać naraz, gość obchodzi limit, DJ jest wylogowywany co drugie żądanie
(bez sticky sessions). Restart (każdy deploy) zwalnia lease i wylogowuje wszystkich DJ-ów w trakcie imprezy.
Nie jest to błąd — przy obecnym ruchu jedna instancja wystarcza — ale warto mieć decyzję na piśmie i tanie kroki pośrednie:
**Spring Session JDBC** (sesje przeżywają deploy, bez Redisa; S), lease jako wiersz w tabeli z warunkowym `UPDATE … WHERE last_seen
< now() - 10 s` (M), limity gości po stronie serwera (patrz 5.x). **Nakład:** M–L łącznie; Spring Session JDBC osobno S.

### 2.4 [N] `next-track`: sprawdzenie lease i wydanie utworu nie są atomowe; brak lease = każdy może brać — PEWNE
`DjDashboardController.java:238-243`: `mayPlay` i `findNextTrack` to dwa kroki; przejęcie lease pomiędzy nimi da jeden utwór
staremu oknu (skutek: jeden utwór tła „zagrany” w oknie, które zaraz ucichnie). Poważniejsze: gdy **nikt** nie trzyma lease
(np. tuż po restarcie serwera), `mayPlay` zwraca true dla każdego, a `next-track` lease nie bierze — dwa okna, które zapytają przed
pierwszym raportem, dostaną dwa różne utwory. Okno raportuje co 3 s, więc okno jest krótkie. **Poprawka:** `next-track` robi
`report(CLAIM)` zamiast `mayPlay` (bierze wolny lease). **Nakład:** XS (+ test kontrolera).

### 2.5 [N] Import playlisty w wątku żądania `next-track` — PEWNE
`NextTrackService.java:211-216` i `:223-229`: gdy kolejka jest pusta albo utwory mają > 29 dni, `next-track` synchronicznie woła
YouTube Data API (do 10 stron × timeout 10 s z `AppConfig.java:31-32`). Okno w tym czasie ma `tryAutoPlayInFlight` i milczy; przy
wolnym API — cisza na imprezie nawet kilkadziesiąt sekund, choć w kolejce gości mogło coś czekać (goście są sprawdzani wcześniej, więc
to dotyczy tylko tła). Odświeżanie „po 29 dniach” przy okazji **resetuje rundę i kolejność** (import = CANCELLED + nowe QUEUED) w środku
utworu. Rzadkie (impreza > 29 dni od importu), ale lepiej odświeżać w tle (`@Async`/nocny job) i zachować rundę. **Nakład:** S.

### 2.6 [N] Czytelność: jedna usługa, trzy warstwy pośrednie — PEWNE
`FallbackQueueService` → `FallbackTrackCommandService` → `FallbackTrackRepository`, a każda metoda `FallbackQueueService`
(`:88-126`) powtarza te same 4 linie (settings → `extractPlaylistId` → null-check → delegacja). Do tego `NextTrackService` sięga po
`FallbackTrackRepository` bezpośrednio. Wspólna prywatna metoda `currentPlaylist(partyCode)` zwracająca `Optional<(playlistId, shuffle)>`
usunęłaby powtórzenia; ewentualnie połączyć „Query/Command” fasady, skoro podział CQRS jest tu tylko nominalny. **Nakład:** S.

---

## 3. Skrypty przeglądarkowe (`youtube-autopilot.js`, `dashboard.js`)

### 3.1 [W] Jeden nieudany `next-track` = cisza na imprezie do czasu zmiany kolejki gości — PEWNE (w kodzie), nieodtworzone
Auto-Pilot pyta o utwór tylko w kilku chwilach: `onReady`, `ENDED`, błąd playera (5 razy), włączenie Auto-Pilota, zmiana roli lease
i `checkYouTubeAutoPlay()` z pollingu (`youtube-autopilot.js:131, 240, 252, 299, 405, 760`). Ale polling woła
`checkYouTubeAutoPlay()` **tylko gdy tabela się zmieniła** — odpowiedź 304 kończy `refreshTable` wcześniej
(`dashboard.js:415-418` vs `:443-445`). Skutek: jeśli `next-track` na końcu utworu nie powiedzie się — chwilowy błąd sieci (Wi-Fi
laptopa, telefon), 5xx przy restarcie/deployu, wygasła sesja (`fetch` idzie za przekierowaniem do strony logowania, `response.json()`
rzuca, `fetchNextTrack` zwraca `null`, `:163-183`) — player zostaje w `ENDED` i **nic już nie ponowi pytania**, dopóki gość nie
doda piosenki albo DJ nie przełączy Auto-Pilota. To samo po 5 kolejnych błędach playera (`MAX_IMMEDIATE_RETRIES`, `:88-91`) —
komentarz mówi, że „dalej tempo nadaje poll dashboardu”, ale poll przy 304 go nie woła; 6 niegrywalnych filmów z rzędu w playliście
tła (np. zablokowane osadzanie) zatrzymuje muzykę. Sekcja 5.4 dokumentacji też zakłada, że „a poll” wywołuje `tryAutoPlay`.
**Poprawka:** wołać `checkYouTubeAutoPlay()` przy każdym cyklu (także 304) — `tryAutoPlay` sam sprawdza „holder + Auto-Pilot +
idle + nic w locie”, więc to zgodne z zasadą „nie używać `next-track` do pollingu” (pyta tylko idle player, który i tak ma grać);
ewentualnie z backoffem po błędzie. Scenariusz przeglądarkowy: `nextTrackStatus: 500` raz, potem utwór — czerwony przed zielonym
(harness to już umie: `server.py:50`). **Nakład:** S.

### 3.2 [Ś] Stan playera rozproszony w ~15 zmiennych modułu, resetowany ręcznie w 6 miejscach — PEWNE
`youtube-autopilot.js:55-117`: `currentlyPlayingSongId`, `isLoadingSong`, `isBackgroundTrack`, `playingPlaylistId`,
`trackLoadedAtLeaseSeq`, `nowPlayingKey`, `playingFromHistory`, `loadStartedAt`, `trackLoads`, `lastRestart`, … Każde miejsce, które
ładuje lub zatrzymuje utwór, ustawia swój podzbiór: `playTrack` (`:266`), `replayTrack` (`:500`), `playInEmbeddedPlayer` (`:782`),
`stopBackgroundTrack` (`:280`), `stopPlaybackHere` (`:394`), `onPlayerError` (`:245`). To główne źródło błędów z historii projektu
(„retrace po zmianie playlisty”, „Wznów zamiast Pauza podczas ładowania”, `trackLoadedAtLeaseSeq`) — każda nowa flaga musi być
dopisana we wszystkich tych miejscach. **Poprawka:** jeden obiekt `current = {kind: 'GUEST'|'BACKGROUND'|'HISTORY'|'MANUAL', id, key,
playlistId, loadedAtSeq, loadNo, startedAt}` tworzony wyłącznie w `loadIntoPlayer(kind, track)` i zerowany w jednym `clearCurrent()`;
flagi stają się pochodnymi (`isBackgroundTrack = current?.kind === 'BACKGROUND'`). 27 scenariuszy przeglądarkowych jest dobrą siatką
bezpieczeństwa. **Nakład:** M.

### 3.3 [Ś] Dwa skrypty rozmawiają przez globalne `window.*` i `typeof … === 'function'` — PEWNE
`dashboard.js` woła `checkYouTubeAutoPlay`, `updateFallbackSource`, `stopFallback`, `refreshFallbackQueue`, `reloadHistory`,
`initSortableHeaders`, `reapplySort`, `applyListFilters`, `restoreListState`, `setKnownQueueVersion`, a `youtube-autopilot.js` —
część z nich w drugą stronę (`:275, 421`). Kontrakt jest niejawny (lista „Exposes” w nagłówku `:49` jest już niepełna), literówka w
nazwie cicho wyłącza funkcję, a kolejność `<script>` ma znaczenie. `dashboard.js` (1212 linii) to 12 niezależnych IIFE w jednym
pliku (formularze, zakładki, polling, kolejka tła, przeciąganie, sortowanie, filtry list).
**Poprawka:** `<script type="module">` (bez bundlera — przeglądarki to obsługują) i podział na pliki `polling.js`, `tabs.js`,
`fallback-queue.js`, `drag.js`, `list-tools.js`, `player.js` z jawnymi `import/export`; do tego jeden mały „event bus”
(`document.dispatchEvent(new CustomEvent('s2p:queue-changed'))`) zamiast wzajemnych wywołań. Scenariusze przeglądarkowe ładują
prawdziwe pliki, więc od razu pokażą zerwane powiązanie. **Nakład:** M–L (można etapami, zaczynając od `drag.js` i `list-tools.js`).

### 3.4 [N] Polling nie zwalnia w ukrytej karcie, która nie gra — PEWNE
`dashboard.js:395-466` i `youtube-autopilot.js:717-721` odpytują co 3 s niezależnie od `document.visibilityState`. Okno, które
**gra**, musi raportować (lease), ale okno podglądu (telefon DJ-a w kieszeni, druga karta) mogłoby w tle raportować `WATCH` np. co
15 s i nie odpytywać `updates` wcale, a po powrocie (`visibilitychange`) odświeżyć od razu. Mniej ruchu i baterii telefonu; w połączeniu
z 2.1 mniej zapytań do bazy. **Nakład:** S.

### 3.5 [N] Drobne — PEWNE
- `dashboard.js:409`: `partyCode` bez `encodeURIComponent` (w innych miejscach jest) — nieszkodliwe (kod to [A-Z0-9]), ale niespójne. XS.
- `youtube-autopilot.js:143-146` i `YouTubeUrls.java` — dwie implementacje wyciągania ID filmu. Dziś bezpieczne, bo serwer zapisuje
  zawsze `watch?v=` (`YouTubeMusicProvider.java:45`), ale to dwa miejsca do utrzymania; wiersz kolejki mógłby nieść gotowe
  `data-video-id` z serwera. XS–S.
- Brak obsługi nieudanego załadowania `iframe_api` — znane, czeka na decyzję właściciela („Next”, 2), tu tylko odnotowane.

---

## 4. Integracje zewnętrzne (Gemini, YouTube Data API, Spotify)

### 4.1 [W] Jeden gość (albo skrypt) może w kilka sekund zużyć dzienny limit wyszukiwań YouTube — PEWNE
Limit gości jest w `HttpSession` i jest sprawdzany **przed**, a zapisywany **po** 2–4 s ocenie AI (`GuestController.java:76-87`),
bez żadnej blokady: N równoległych żądań z jednej sesji przechodzi sprawdzenie, a żądanie bez ciasteczka dostaje nową sesję i limitu
nie ma wcale. Każda zaakceptowana prośba o nowy tytuł to jedno `search.list` (`YouTubeMusicProvider.java:147-152`), a limit tego
endpointu to **100 wywołań dziennie na cały projekt Google** (`application.properties:113-117`). Pętla `curl` z różnymi tytułami
wyczerpuje go w minutę — od tej chwili do północy czasu pacyficznego żadna nowa prośba gościa na **żadnej** imprezie nie dostanie
ID filmu (trafi do kolejki z linkiem do wyników wyszukiwania, Auto-Pilot ją pominie). Przy okazji każde takie żądanie to płatne
wywołanie Gemini. Sekcja 13 dokumentacji zna „session-based rate limiting”, ale nie ten skutek.
**Poprawka:** limiter po stronie serwera niezależny od ciasteczka — klucz `IP (CF-Connecting-IP / X-Forwarded-For przy
forward-headers) + partyCode` w Caffeine (albo Bucket4j), zapis „próby” **przed** oceną (atomowo, `asMap().compute`), plus
dzienny budżet wyszukiwań na imprezę i globalny bezpiecznik (np. stop przy 80 wywołaniach, wtedy tylko cache). **Nakład:** S–M.

### 4.2 [Ś] Klucz YouTube API może trafić do logów — PEWNE
`YouTubeMusicProvider.java:147-152` wkłada klucz w query string, a `:165-166` loguje wyjątek w całości. `ResourceAccessException`
(timeout, DNS, reset połączenia) ma w komunikacie pełny URL — `I/O error on GET request for "https://…&key=AIza…"`. Klient playlist
(`YouTubePlaylistClient.java:38-39, 180-192`) wie o tym i czyści komunikaty, provider wyszukiwania nie. Logi Railway są widoczne dla
każdego z dostępem do projektu i bywają wklejane do czatu (klucz już raz wyciekł tą drogą — „Open items”, 2).
**Poprawka:** klucz w nagłówku `X-goog-api-key` (YouTube Data API go przyjmuje) w obu klientach — wtedy żaden URL go nie zawiera;
na razie choćby `log.error("…: {}", scrub(e.getMessage()))` bez stack trace. **Nakład:** XS.

### 4.3 [Ś] Nieudane dodanie do kolejki Spotify i tak oznacza piosenkę jako zagraną — PEWNE
`SongEvaluationService.java:233-248`: `.exceptionally(ex -> {…; return null;})` „naprawia” future, więc następujące po nim
`.thenAccept(v -> markPlayed(…))` wykona się **zawsze**, także po błędzie. Piosenka dostaje dopisek „Auto-Pilot nie zadziałał”, ale
znika z kolejki DJ-a jako „played” — DJ jej nie zobaczy i nie wypchnie ręcznie. Podobnie `DjService.pushToSpotify`
(`DjService.java:177-181`) nie czeka na wynik `@Async addToQueue` i oznacza „played” od razu. Dotyczy tylko imprez Spotify.
**Poprawka:** `thenRun(markPlayed)` przed `exceptionally`, albo `handle((v, ex) -> ex == null ? markPlayed : appendComment)`;
w `pushToSpotify` — `.join()` z timeoutem albo ta sama obsługa. Test z nieudanym future. **Nakład:** XS–S.

### 4.4 [Ś] Brak timeoutu wywołań Gemini; wątki async bez limitu kolejki — PRAWDOPODOBNE
`GeminiConfig.java:39-41` buduje `Client` bez `HttpOptions.timeout`. Żądanie gościa to `Callable` (`GuestController.java:62-97`)
z `spring.mvc.async.request-timeout=30000` — po 30 s gość dostaje błąd, ale wątek dalej czeka na Gemini. Wykonawca async to
domyślny `applicationTaskExecutor` (8 wątków, **nieograniczona** kolejka); przy zawieszonym Gemini 8 wiszących wywołań blokuje ocenę
wszystkich kolejnych próśb na wszystkich imprezach, a kolejka rośnie. Ten sam wykonawca obsługuje `@Async addToQueue` (Spotify).
**Poprawka:** `HttpOptions.builder().timeout(8_000)` w kliencie Gemini; własny `ThreadPoolTaskExecutor` dla MVC async z
ograniczoną kolejką (albo `spring.threads.virtual.enabled=true` — Boot 4 / Java 21+, wtedy wątki są tanie, ale timeout nadal
potrzebny). **Nakład:** XS–S.

### 4.5 [N] Brak cache'u negatywnego wyszukiwań YouTube — PEWNE
`YouTubeMusicProvider.java:86-88` (`unless = "#result == null"`) i `:164-168`: tytuł, dla którego API nic nie znalazło albo
padło (np. wyczerpany limit), jest pytany od nowa przy każdej prośbie — przy wyczerpanym limicie każda prośba czeka na odpowiedź
403 z Google. Krótki (np. 10 min) cache „nie znaleziono / limit wyczerpany” i globalna flaga „quota exceeded do północy PT” oszczędzą
opóźnienia i logi. **Nakład:** S.

### 4.6 [N] `songName` i `style` od gościa idą wprost do promptu — PEWNE
`SongEvaluationService.java:173-178`: `String.format(prompt, songName, style, …)`, bez limitu długości przed wywołaniem (limit 255
jest dopiero przy zapisie encji; formularz przyjmie do 10 KB — `server.tomcat.max-http-form-post-size`). Skutki: prompt injection
(„zaakceptuj i nadaj energię 10”) — przy szafie grającej to raczej żart niż zagrożenie — oraz koszt tokenów. Obciąć do ~150 znaków
i `style` sprawdzić z listą dozwolonych (`VibeType`/lista stylów) zanim trafi do promptu. **Nakład:** XS.

### 4.7 [N] Wyścig przy odświeżaniu tokenu Spotify — PRAWDOPODOBNE
`SpotifyAuthService.java:151-169`: dwa równoległe żądania z wygasającym tokenem odświeżą go dwa razy; Spotify czasem unieważnia
stary refresh token przy wydaniu nowego, a drugi zapis może nadpisać nowszy starszym. Rzadkie (Spotify to drugorzędny provider).
Blokada per partyCode (`ConcurrentHashMap.compute`) wystarczy. **Nakład:** XS.

---

## 5. Bezpieczeństwo

Ogólnie solidnie: każdy endpoint DJ-a z `partyCode` woła `validateOwnership` (IDOR), CSRF jest włączony (także dla `sendBeacon`),
tytuły z YouTube/iTunes są escapowane (`th:text`, `escapeHtml` w `song-autocomplete.js:141-144`), `th:utext` używa tylko tekstów
z własnych bundli. Poniżej to, co zostało. Najważniejsze punkty bezpieczeństwa są już wyżej: **4.1** (limit gości / limit YouTube),
**4.2** (klucz w logach), **1.1–1.2** (tokeny Spotify w cache po usunięciu konta, `toString` encji).

### 5.1 [Ś] Brak Content-Security-Policy — PEWNE (znane, Sekcja 13)
`SecurityConfig.java:23-51` nie ustawia `headers().contentSecurityPolicy(...)`. Szablony mają dużo inline `<script>` i `style="…"`
(np. skrypt przywracania scrolla w `fragments/components.html`, `index.html:98-106`), więc pełny CSP wymaga nonce'ów. Tanie
pierwsze kroki: `Content-Security-Policy-Report-Only` z `script-src 'self' 'nonce-…' https://www.youtube.com https://s.ytimg.com
https://cdn.jsdelivr.net; frame-src https://www.youtube.com; connect-src 'self' https://itunes.apple.com; object-src 'none';
base-uri 'self'; frame-ancestors 'none'` — i obserwacja raportów przez tydzień przed przełączeniem na egzekwowanie.
**Nakład:** M (nonce w szablonach + przeniesienie inline `onclick`/`onsubmit`, jeśli są).

### 5.2 [Ś] `state` OAuth Spotify to jawny kod imprezy, nie losowy nonce — PEWNE
`SpotifyAuthService.java:95-104` wysyła `state=partyCode`, a `SpotifyAuthController.java:52-59` sprawdza tylko, że to impreza
zalogowanego DJ-a. Kod imprezy jest publiczny (QR, link dla gości), więc to nie chroni przed klasycznym „login CSRF”: napastnik
autoryzuje **własne** konto Spotify, przechwytuje `code` i podsuwa zalogowanemu DJ-owi link
`/dj/spotify/callback?code=…&state=<jego kod>` — impreza DJ-a zaczyna wysyłać piosenki do Spotify napastnika. Skutek mały (to nie
wyciek danych), ale poprawka trywialna. **Poprawka:** losowy `state` w sesji (i jego jednorazowe zużycie w callbacku). **Nakład:** XS.

### 5.3 [N] Tokeny Spotify jawnym tekstem w bazie i nieunieważniane przy usunięciu konta — PEWNE (pierwsze znane, Sekcja 13)
`PartySettingsEntity.java` (`spotifyAccessToken`, `spotifyRefreshToken`, 2048 znaków). Szyfrowanie `AttributeConverter` z kluczem
z env (AES-GCM) to ~50 linii. Przy `deleteAllUserData` warto też „zapomnieć” token po stronie aplikacji (patrz 1.1) — Spotify nie ma
endpointu revoke, więc DJ musi sam odłączyć aplikację na koncie Spotify; strona prywatności mogłaby to powiedzieć. **Nakład:** S.

### 5.4 [N] Ustawienia DJ-a bez górnych granic — PEWNE
`DjPartySettingsController.java:85-104`: `duplicateCheckWindow` bez maksimum — trafia do `PageRequest.of(0, n)` i do promptu
Gemini (`SongEvaluationService.java:160-169`): wartość 100 000 = każda prośba gościa czyta i wysyła do AI wszystkie tytuły. To DJ
szkodzi sam sobie (i rachunkowi za Gemini), ale limit 50 kosztuje jedną linię. `fallbackPlaylistUrl` > 500 znaków kończy się 500 z
bazy zamiast komunikatu (`:136-141`). **Nakład:** XS.

### 5.5 [N] Domyślne hasło bazy w `application.properties` — PEWNE
`application.properties:47-49`: `${PGPASSWORD:1111}`. Jeśli zmienna na produkcji zniknie, aplikacja spróbuje hasła `1111` zamiast
nie wystartować. Lepiej: bez domyślnej wartości w głównym pliku, a `1111` w `application-local.properties` (profil lokalny,
IntelliJ). To samo dotyczy `PGUSER:postgres`. **Nakład:** XS (+ zmiana konfiguracji uruchomieniowej w IntelliJ i w dokumentacji).

---

## 6. Testy i CI

Stan: 431 testów jednostkowych (Mockito + renderowanie szablonów, ~sekundy), 27 scenariuszy przeglądarkowych na prawdziwych
skryptach, dwa workflow GitHub Actions. Mocne strony: szablony renderowane z prawdziwymi bundlami, scenariusze sprawdzane
mutacjami („czerwony przed zielonym”). Luki:

### 6.1 [W] SQL kolejki, migracje i blokady nie mają ani jednego stałego testu na PostgreSQL — PEWNE
`FallbackTrackRepository` to 12 zapytań modyfikujących (w tym natywne `shuffle`, `renumberQueued`, advisory lock), a jedyny
deadlock w historii projektu wyszedł dopiero w ręcznym stress-teście (`CLAUDE.md`). Dziś każda zmiana tej klasy wymaga
jednorazowego `@SpringBootTest` pisanego od nowa w kopii repo i kasowanego po użyciu — wiedza ginie z sesją. Migracje V1–V8 na
pustej bazie też nie są sprawdzane w CI (`Scan2playApplicationTests` jest wykluczony). Tymczasem runner `ubuntu-24.04` ma Dockera.
**Poprawka:** `org.testcontainers:postgresql` + `@DataJpaTest` z Flyway (pusta baza → V1..V8 → walidacja Hibernate) i pakiet
`*RepositoryIT`: kolejność `takeNextTrack` przez dwie rundy, `placeTrack`/`moveTrack`, `requeuePlayedTracks` z nowszym importem,
purge, historia z `COALESCE` — oraz jeden test współbieżności (np. 8 wątków × 60 `takeNextTrack` + ruchy, zero deadlocków, zero
duplikatów w `fallback_play`). W CI osobny job `mvnw verify -Pit` (lokalnie, na Windows, Docker nie jest wymagany — profil
opcjonalny). To też zamyka punkt „Queue SQL needs a check against a real PostgreSQL” z `CLAUDE.md`. **Nakład:** M.

### 6.2 [Ś] Nieprzetestowane klasy z logiką zewnętrzną — PEWNE
Brak testów dla: `SongEvaluationService` (błąd 4.3 byłby złapany jednym testem), `GuestController` + `GuestSessionService` pod
kątem równoległych żądań (4.1), `YouTubeMusicProvider` (L1/L2 cache, wygasły wpis, brak klucza, 4.2), `SpotifyAuthService`,
`SpotifyMusicProvider`, `QrCodeService`, `FeedbackController`, `SecurityConfig` poza `SmokeTest`. To akurat te miejsca, w których ten
przegląd znalazł błędy. **Nakład:** M (po S na klasę).

### 6.3 [N] Testy serwisu kolejki sprawdzają wywołania, nie zachowanie — PEWNE
`FallbackTrackCommandServiceTest.java` (851 linii, 108 × `verify(`) weryfikuje, *które* metody repozytorium i w jakiej kolejności
zostały zawołane. Każda zmiana implementacji bez zmiany zachowania (np. 1.4 — batch insert, 1.6 — usunięcie pętli, 2.1 — licznik
wersji) wymusi przepisanie dziesiątek asercji. Gdy powstanie 6.1, część tych testów można zastąpić testami na prawdziwej bazie, a
w jednostkowych zostawić tylko logikę bez SQL (rotacja, `keepOutOfFirstPlace`, obliczanie `to/from`). **Nakład:** M (przy okazji 6.1).

### 6.4 [N] Porządki w `pom.xml` — PEWNE
- Repozytoria `spring-milestones` i `spring-snapshots` (`pom.xml:145-162`) przy GA Spring Boot 4.0.3 są zbędne: wydłużają rozwiązywanie
  zależności i poszerzają łańcuch dostaw. XS.
- `dependency-check-maven` 10.0.3 (`:137-141`) jest zadeklarowany, ale nie podpięty do żadnej fazy ani workflow i jest stary; albo
  zaplanowany job (raz w tygodniu, z kluczem NVD w sekretach), albo Dependabot/`dependency-review-action` od GitHuba — prościej. XS–S.
- Brak raportu pokrycia (JaCoCo) — nie jako bramka, tylko żeby widzieć luki takie jak w 6.2. XS.

---

## 7. Dokumentacja i czytelność ogólna

### 7.1 [Ś] Dokumenty dla następnej sesji mają ~295 KB i są głównie historią — PEWNE
`PROJECT_CONTEXT.md` (1560 linii, 159 KB) i `SESSION_HANDOFF.md` (1179 linii, 136 KB) to razem ~75 tys. tokenów, które każda nowa
sesja ma przeczytać, zanim zrobi cokolwiek. Punkt „Start here” to kilka ekranów o tym, które pary commitów były wypchnięte kiedy;
aktualny stan („co działa, co jest otwarte”) trzeba z tego wyłuskać. Sekcja 5.4 opisuje zachowanie playera razem z historią każdej
decyzji. Skutek: drożej, wolniej i większe ryzyko, że sesja przeoczy ważną regułę schowaną w środku akapitu.
**Poprawka:** `SESSION_HANDOFF.md` ≤ 100 linii: stan gałęzi, otwarte punkty, następny krok; historia sesji do
`docs/history/2026-09-*.md` (albo po prostu git log). `PROJECT_CONTEXT.md`: zostawić opis „jak jest” (architektura, reguły
playera jako lista, endpointy), a uzasadnienia i daty decyzji do `docs/decisions/NNN-*.md` (krótkie ADR-y). Treści nie ubywa, tylko
przestaje być obowiązkową lekturą. **Nakład:** M (redakcja, bez kodu).

### 7.2 [N] Komentarze w kodzie niosą historię zamiast powodu — PEWNE
Przykłady: nagłówek `youtube-autopilot.js:1-50` (50 linii prozy), `renderBackButtons` z cytatem i datą („the DJ's report, 2026-09-30:
‘wstecz nie jest intuicyjne’”, `:362-368`), komentarze w szablonach opisujące poprzednie wersje („on a phone they touched…”,
`fragments/fallback-queue.html:22-23`). Powód („dlaczego tak”) jest cenny i powinien zostać; „kto, kiedy i co było wcześniej” należy
do commitów. Komentarz, który opisuje poprzednią wersję, starzeje się z każdą zmianą. Do zrobienia przy okazji refaktoryzacji z 3.2/3.3,
nie osobno. **Nakład:** S (przy okazji).

### 7.3 [N] Drobne porządki — PEWNE
- `HELP.md` — pozostałość po Spring Initializr, nieaktualne linki; do usunięcia. XS.
- `.github/copilot-instructions.md` to ręczna kopia `AGENTS.md` („If you edit one, edit both”) — kopia już może się rozjechać; krok CI
  `diff <(tail -n +3 .github/copilot-instructions.md) AGENTS.md` albo generowanie jednego z drugiego. XS.
- `AppConfig.java:46-57` — metoda `cacheManager` ma przesunięte wcięcie; `DjDashboardController.java:278-280` — statyczna metoda,
  która tylko deleguje do `YouTubeUrls.extractPlaylistId` (plus 15 linii Javadoca kopiującego tamten). XS.
- `FallbackQueueView` ma trzy konstruktory, z których dwa istnieją dla testów (`FallbackQueueView.java:128-136`) — builder albo
  fabryki testowe byłyby czytelniejsze. XS.

---

## Zbiorcza lista wg wagi (do wyboru, co poprawiamy)

| # | Waga | Uwaga | Gdzie | Nakład |
|---|------|-------|-------|--------|
| 3.1 | **W** | Po jednym nieudanym `next-track` (sieć, 5xx, wygasła sesja) albo 5 błędach playera Auto-Pilot milknie do zmiany kolejki gości — poll przy 304 nie woła `tryAutoPlay` | `dashboard.js:415-445`, `youtube-autopilot.js:163-183, 252` | S |
| 4.1 | **W** | Limit gości w sesji, sprawdzany przed i zapisywany po 2–4 s — skrypt zużywa dzienny limit 100 wyszukiwań YouTube (dla wszystkich imprez) i płaci za Gemini | `GuestController.java:76-87` | S–M |
| 6.1 | **W** | Zero stałych testów SQL/migracji/blokad na prawdziwym PostgreSQL (Testcontainers w CI) | `FallbackTrackRepository`, `db/migration` | M |
| 1.3 | Ś | ⏭ tuż po starcie piosenki gościa może ją podać ponownie (cache 3 s bez eviction) | `DjService.java:113-156` | S |
| 4.3 | Ś | Nieudane dodanie do kolejki Spotify i tak oznacza „played” (`exceptionally` → `thenAccept`) | `SongEvaluationService.java:233-248`, `DjService.java:177-181` | XS–S |
| 4.2 | Ś | Klucz YouTube API w URL-u → w logach przy błędzie sieci | `YouTubeMusicProvider.java:147-166` | XS |
| 1.1 | Ś | Usunięcie konta nie czyści cache `partySettings` (tokeny Spotify w pamięci do 24 h, goście nadal mogą pisać) | `AccountDeletionService.java:143` | XS |
| 4.4 | Ś | Brak timeoutu Gemini; wykonawca async 8 wątków z nieograniczoną kolejką | `GeminiConfig.java:39-41` | XS–S |
| 2.1 | Ś | Każdy raport lease (co 3 s × okno) czyta 500 wierszy i 4 zapytania, by policzyć wersję listy | `FallbackQueueService.java:73-75` | S |
| 2.2 | Ś | Brak kompresji HTTP; fragment „up next” do ~500 KB | `application.properties`, `fragments/fallback-queue.html` | XS |
| 5.2 | Ś | `state` OAuth Spotify = publiczny kod imprezy (login CSRF) | `SpotifyAuthService.java:95-104` | XS |
| 1.2 | Ś | Cache trzyma współdzieloną mutowalną encję; `@Data.toString` z tokenami | `PartySettingsEntity.java`, `PartySettingsQueryService.java` | XS–S |
| 1.4 | Ś | Import playlisty = do 500 pojedynczych INSERT-ów pod blokadą kolejki | `FallbackTrackCommandService.java:96-108` | S |
| 1.5 | Ś | Brakujące indeksy (`fallback_track` po playlist/order, `fetched_at`, `requested_at`), zbędne duplikaty | `V2`, `V1` → nowa `V9` | S |
| 3.2 | Ś | Stan playera w ~15 zmiennych resetowanych ręcznie w 6 miejscach | `youtube-autopilot.js:55-117` | M |
| 3.3 | Ś | Skrypty rozmawiają przez globalne `window.*`; `dashboard.js` 1212 linii w jednym pliku | `dashboard.js`, `youtube-autopilot.js` | M–L |
| 5.1 | Ś | Brak CSP (znane) | `SecurityConfig.java` | M |
| 6.2 | Ś | Bez testów: `SongEvaluationService`, `GuestController`, `YouTubeMusicProvider`, Spotify | `src/test` | M |
| 2.3 | Ś | Jednoinstancyjność z konstrukcji (lease, cache, sesje, limity); deploy wylogowuje DJ-ów | wiele | S (Spring Session JDBC) / L |
| 7.1 | Ś | Dokumenty ~295 KB, głównie historia | `PROJECT_CONTEXT.md`, `SESSION_HANDOFF.md` | M |
| 2.4 | N | `next-track` nie bierze wolnego lease (dwa okna tuż po restarcie) | `DjDashboardController.java:238` | XS |
| 2.5 | N | Import YouTube w wątku `next-track`; odświeżenie po 29 dniach resetuje rundę | `NextTrackService.java:211-229` | S |
| 2.6 | N | Powtarzany kod „settings → playlistId → null-check” | `FallbackQueueService.java:88-126` | S |
| 3.4 | N | Polling nie zwalnia w ukrytej karcie, która nie gra | `dashboard.js`, `youtube-autopilot.js` | S |
| 3.5 | N | Drobne w JS (`encodeURIComponent`, dwie implementacje ID filmu) | `dashboard.js:409` | XS |
| 4.5 | N | Brak cache'u negatywnego / flagi „quota exceeded” | `YouTubeMusicProvider.java:86-168` | S |
| 4.6 | N | `songName`/`style` gościa bez limitu wprost do promptu | `SongEvaluationService.java:173-178` | XS |
| 4.7 | N | Wyścig przy odświeżaniu tokenu Spotify | `SpotifyAuthService.java:151-169` | XS |
| 5.3 | N | Tokeny Spotify jawnym tekstem (znane) | `PartySettingsEntity.java` | S |
| 5.4 | N | Brak górnych granic ustawień DJ-a (`duplicateCheckWindow`) | `DjPartySettingsController.java:85-104` | XS |
| 5.5 | N | Domyślne hasło bazy `1111` w głównym pliku konfiguracji | `application.properties:47-49` | XS |
| 1.6 | N | Martwa pętla 10 prób pod advisory lockiem | `FallbackTrackCommandService.java:317-352` | XS |
| 1.7 | N | Klucz blokady = 32-bitowy `hashCode` | `FallbackTrackCommandService.java:77-79` | XS |
| 1.8 | N | Czas bez strefy (`LocalDateTime` + `timestamp`) | encje, migracje | XS / M |
| 1.9 | N | `getUpcoming` liczy to, co już ma | `FallbackQueueService.java:54-55` | XS |
| 6.3 | N | Testy kolejki weryfikują wywołania, nie zachowanie | `FallbackTrackCommandServiceTest.java` | M |
| 6.4 | N | `pom.xml`: zbędne repozytoria snapshot/milestone, niepodpięty dependency-check, brak JaCoCo | `pom.xml` | XS–S |
| 7.2 | N | Komentarze z historią zamiast powodu | JS, szablony | S |
| 7.3 | N | `HELP.md`, kopia `AGENTS.md`, wcięcia, konstruktory testowe | różne | XS |

**Propozycja pierwszej paczki** (duży zysk, mały nakład, niskie ryzyko): 3.1, 4.3, 4.2, 1.1, 4.4 (timeout), 2.2, 5.2 — razem ok.
pół dnia z testami; potem 4.1 i 1.3; potem 6.1 jako fundament pod 1.4, 1.5, 2.1 (zmiany SQL, które bez niego wymagają ręcznego
sprawdzania na PostgreSQL).

---

## Status poprawek

**Pierwsza paczka — zrobiona 2026-09-30, zacommitowana (kod `57d3def`, potem dokumenty) i wypchnięta:**

| # | Co zmieniono | Pliki | Testy |
|---|--------------|-------|-------|
| 3.1 | Nieudane pytanie o utwór (błąd sieci, 5xx, przekierowanie do logowania) albo >5 błędów playera z rzędu ustawia `askAgain`; kolejny raport lease (co 3 s) pyta ponownie, dopóki serwer nie odpowie. Bez ciasnej pętli, bez pytania przy 204. | `youtube-autopilot.js` | nowe scenariusze `recover-after-failed-ask`, `recover-after-player-errors` (czerwone na starym kodzie, zielone teraz); 29/29 scenariuszy OK |
| 4.3 | `whenComplete` zamiast `exceptionally`+`thenAccept`; „played” tylko gdy Spotify przyjął piosenkę, inaczej zostaje w kolejce z notką. `pushToSpotify` tak samo (bez `@Transactional`, zapis w callbacku). | `SongEvaluationService.java`, `DjService.java` | nowy `SongEvaluationServiceTest` (3), `DjServiceTest` +1 |
| 4.2 | Klucz YouTube w nagłówku `X-goog-api-key` zamiast `&key=` w URL-u wyszukiwania. | `YouTubeMusicProvider.java` | nowy `YouTubeMusicProviderTest` (2) |
| 1.1 | Po usunięciu konta (po commicie transakcji) czyszczone cache `partySettings`, `dashboardQueue`, `publicQueue` dla kodu imprezy. | `AccountDeletionService.java` | `AccountDeletionServiceTest` +1 |
| 4.4 | Timeout 10 s dla wywołań Gemini (`HttpOptions.timeout`). Ograniczenie puli wątków async — nie ruszane (osobna decyzja). | `GeminiConfig.java` | — (nie da się sprawdzić bez klucza; kompiluje się, kontekst nie był uruchamiany) |
| 2.2 | `server.compression.*` (gzip dla HTML/CSS/JS/JSON od 2 KB). | `application.properties` | `SmokeTest` przechodzi |
| 5.2 | `state` logowania Spotify = losowe 32 bajty w sesji, jednorazowe; impreza brana z sesji DJ-a, nie z parametru. | `SpotifyAuthController.java`, `SpotifyAuthService.java` | nowy `SpotifyAuthControllerTest` (4) |

Testy jednostkowe: **442** (441 uruchomionych, 1 pominięty), `BUILD SUCCESS` w kopii repo. Dokumenty zaktualizowane:
`PROJECT_CONTEXT.md` (5.3, 5.4, 7.1–7.3, 9, 13) i `SESSION_HANDOFF.md` („The session of 2026-09-30, the seventh”).

**Druga paczka — 4.1 i 1.3, zrobione 2026-09-30 (sesja ósma), zacommitowane (kod `dafffac`, potem dokumenty) i wypchnięte:**

| # | Co zmieniono | Pliki | Testy |
|---|--------------|-------|-------|
| 4.1 | Trzy limity, każdy liczony **przed** oceną AI. (1) Limit gościa z sesji: sprawdzenie i zapis w jednym kroku pod mutexem sesji (`tryAcquire`). (2) IP + impreza: 30 próśb / 10 min — luźno, bo goście na Wi-Fi lokalu mają jeden adres. (3) Impreza: 300 próśb / 24 h, niezależnie od adresu. (2) i (3) w `GuestRequestLimiter` (Caffeine, `asMap().compute`). Adres: `getRemoteAddr()` albo nagłówek z `guest.client-ip-header` (`CF-Connecting-IP` za Cloudflare — ustawić na produkcji). Do tego globalny bezpiecznik `YouTubeSearchBudget`: 80 wyszukiwań API na dobę Google (północ czasu pacyficznego), 403 `quotaExceeded` wyłącza wyszukiwanie od razu; po wyczerpaniu piosenka dostaje link do wyników (decyzja właściciela), a taki link nie trafia do cache'u `youtubeSearch`. Wszystkie liczby w `application.properties` + zmienne środowiskowe, 0 = wyłączone. Nowe komunikaty `guest.error.too_many_requests`, `guest.error.party_daily_limit` (PL/EN). | `GuestController`, `GuestSessionService`, nowe `GuestRequestLimiter`, `YouTubeSearchBudget`; `YouTubeMusicProvider`, `application.properties`, `messages*.properties` | nowe `GuestRequestLimiterTest` (9, w tym równoległe żądania), `GuestControllerTest` (5), `YouTubeSearchBudgetTest` (3), `YouTubeMusicProviderCacheTest` (2, prawdziwe proxy cache); `YouTubeMusicProviderTest` +2; `GuestSessionServiceTest` przepisany (6, w tym 16 równoległych żądań jednej sesji → przechodzą 2) |
| 1.3 | ⏭ dodaje bieżącą piosenkę gościa do `exclude` zapytania `next-track`; `markSongAsPlayed` (i `pushToSpotify`) czyści `dashboardQueue` po commicie. Piosenka pominięta ⏭ jeszcze w trakcie ładowania nie była potwierdzona — zostaje w kolejce i wraca po utworze, który ⏭ włączył. | `youtube-autopilot.js`, `DjService.java`; stand-in `server.py` respektuje `exclude` jak prawdziwy serwer | nowe scenariusze `next-right-after-guest-song-started`, `next-while-guest-song-loads` — oba **czerwone na starym skrypcie** (⏭ podał `g` drugi raz), zielone teraz; `DjServiceTest` +1 |

Do 4.1 doszło potem (życzenie właściciela): DJ widzi limity serwera w panelu (linijka pod formularzem limitów) i ostrzeżenie nad kolejką,
gdy limit wyszukiwań YouTube albo limit imprezy zatrzymuje piosenki gości — nagłówek `X-Guest-Limits` każdej odpowiedzi pollu kolejki
(także 304); scenariusz `guest-limit-warnings`, `DjDashboardControllerGuestLimitsTest`. `GUEST_CLIENT_IP_HEADER=CF-Connecting-IP`
ustawione na Railway.

Testy jednostkowe: **480** (479 uruchomionych, 1 pominięty; było 450), `BUILD SUCCESS` w kopii repo. Scenariusze przeglądarkowe: **46**, wszystkie
zielone. CI na GitHubie (oba workflowy) zielone dla `32ac785` i `847c872`.

Nie zrobione z 4.1: licznik bezpiecznika i okna limitów są w pamięci (restart je zeruje; 403 jest zabezpieczeniem) — trwały licznik w bazie
to osobna decyzja, gdy będzie więcej niż jedna instancja (2.3). *(Licznik bezpiecznika YouTube — zrobiony w trzeciej paczce, niżej; okna
limitów gości nadal w pamięci.)*

**Trzecia paczka — 6.1, potem 1.5, 1.4, 2.1, 7.1, 1.2, reszta 4.4 i 4.5, trwały licznik wyszukiwań; zrobione 2026-10-01, zacommitowane
(kod, potem dokumenty) i wypchnięte:**

| # | Co zmieniono | Pliki | Testy |
|---|--------------|-------|-------|
| 6.1 | Stałe testy na prawdziwym PostgreSQL: `mvnw verify -Pit` (profil Maven, failsafe uruchamia `*IT`). Klasa bazowa zakłada i kasuje własną bazę `s2p_it_*` na serwerze z `PGHOST`… (lokalnie PG 18 — Docker niepotrzebny), cała aplikacja na niej: Flyway V1..V10 + walidacja Hibernate. Workflow `db-tests.yml` z usługą `postgres:18` (Railway: `postgres-ssl:18`). `CLAUDE.md` wskazuje te testy zamiast jednorazowego. | `pom.xml`, `PostgresIntegrationTest`, `.github/workflows/db-tests.yml`, `CLAUDE.md` | 24 IT: `MigrationIT` 4, `FallbackQueueIT` 12, `FallbackQueueConcurrencyIT` 1 (**bez blokady advisory: „deadlock detected”** — sprawdzone), `SongRequestRepositoryIT` 3, `YouTubeSearchBudgetIT` 3, `ApplicationSetupIT` 1 |
| 1.5 | `V9`: `(party_code, playlist_id, status, play_order, playlist_position)` i `(party_code, fetched_at)`; usunięte `idx_fallback_track_party_status`, `idx_owner_id`, `idx_party_code`. Indeks pod czystkę `song_requests` celowo nie (zmierzony skip scan: 1,9 ms / 300 tys. wierszy). | `V9__queue_indexes.sql`, `@Index` w encjach | `MigrationIT`: indeksy + plan zapytania o następny utwór bez `Sort` — **czerwone przed V9** |
| 1.4 | Import = jeden `INSERT … SELECT FROM unnest(…) WITH ORDINALITY`. | `FallbackTrackRepository.insertTracks`, `FallbackTrackCommandService` | IT liczy instrukcje (< 10 na 300 utworów) — **czerwone przed**; treść wierszy sprawdzona |
| 2.1 | Wersja listy = jedno zapytanie agregujące (md5 id w kolejności + flagi ręcznego ruchu + liczba pominiętych), bez encji; lista wysyła wersję czytaną przed listą. | `FallbackTrackRepository.queueFingerprint`, `FallbackQueueService`, `DjFallbackQueueController` | IT: 0 encji, ≤ 1 instrukcja — **czerwone przed**; wersja zmienia się przy każdej zmianie kolejki i tylko wtedy |
| 1.2 | `getSettings` daje kopię encji z cache; `updateSettings` czyści wpis po commicie (zamiast `@CachePut`); tokeny Spotify poza `toString`. | `PartySettingsQueryService`, `PartySettingsCommandService`, `PartySettingsEntity`, `AppConfig` | `PartySettingsQueryServiceTest` 4, `PartySettingsCommandServiceTest` +1 |
| 4.4 | Pula async ograniczona: 16 / 32 wątki, kolejka 50 (`spring.task.execution.pool.*`, env `ASYNC_POOL_*`). | `application.properties` | `ApplicationSetupIT` |
| 4.5 | Wyszukiwanie bez wyniku pamiętane 10 min (błędy nie). Flaga „quota exceeded” była już z 4.1. | `YouTubeMusicProvider` | `YouTubeMusicProviderTest` +2 — **czerwony przed** |
| — | Licznik wyszukiwań YouTube w bazie (`V10`, wiersz na dobę Google, atomowy `INSERT … ON CONFLICT … RETURNING`): restart nie oddaje budżetu, kilka instancji go nie przekroczy. | `YouTubeSearchBudget`, `V10__youtube_search_budget.sql` | `YouTubeSearchBudgetIT` 3 (restart, 40 równoległych prób na 3 instancjach = dokładnie budżet) |
| 7.1 | `SESSION_HANDOFF.md` skrócony do stanu bieżącego (~90 linii); całość przeniesiona słowo w słowo do `docs/history/session-handoff-2026-09.md`. `PROJECT_CONTEXT.md` — osobno, później. | dokumenty | — |

Testy jednostkowe: **513** (512 uruchomionych, 1 pominięty; pierwsze liczenie, 537, obejmowało 24 IT — `-Dtest` wciągał je do
surefire, co na GitHubie bez bazy dało czerwony workflow Unit tests; teraz IT poza failsafe są pomijane), `BUILD SUCCESS` w kopii repo; IT: **24**, wszystkie zielone na lokalnym
PostgreSQL 18. JS i szablony bez zmian — scenariusze przeglądarkowe nie były uruchamiane ponownie.
