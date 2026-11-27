# Przegląd 2026-11-27 (drugi) — „Zagrane samo” pod obserwacją po poprawkach pierwszego review

Zakres: `git diff 2912c2d..2e332bb` (`2912c2d` = `main` przed tą pracą, `2e332bb` = merge #315; `dev`: #316 = `a757b6e`), 42 pliki,
+1844 / −49, migracje V81 i V82 (tylko dodają kolumny, indeks i CHECK). `docs/ideas.md` punkt 21, pierwsze review:
`docs/history/review-2026-11-27-deck-watch.md` (P1–P17 i „Poprawki”). Kod jest już na produkcji.

## Wynik w skrócie

Poprawki pierwszego review są zrobione porządnie. Karta i push liczą to samo: ten sam prowadzący, te same granice `<` / `>` /
`>=`, ta sama seria i to samo okno gości. Zapytanie co minutę kosztuje ~2,5 ms przy 1000 imprez. `notePlay` nie cofa czasu.
`POST /dj/devices/silence` jest zabezpieczony. Testy są dobre: 19 z 26 mutacji jest czerwonych; przeżyły krawędzie SQL, duplikat
odbiorcy, wiersz sprzed V82, przerwa w jednym wywołaniu i okno gości w karcie (P12). Na produkcji wdrożenie jest czyste.

**Reguła nadal daje fałszywe pushe w trzech zwykłych sytuacjach.** Wszystkie trzy odtworzyłem zapytaniem `silentNow` 1:1 na
throw-away bazie:

- **P1 (błąd):** próba dźwięku dłuższa niż 10 minut przed imprezą to już „seria”. Push przychodzi przy pierwszej prośbie gościa
  przy stole, zanim DJ zaczął grać („Od 80 min…”).
- **P2 (ważne):** koniec seta. Margines 2 min liczy się od *startu* ostatniej piosenki. Prośby „jeszcze jedna!” z drugiej połowy
  ostatniego utworu i z minut zaraz po nim liczą się jako „goście proszą po ciszy”. Gdy impreza zostaje otwarta, push przychodzi
  20 min po starcie ostatniej piosenki.
- **P3 (ważne):** piosenki dodane przez DJ-a w panelu (`added_by_dj`) liczą się jako prośby gości.

Jest też odwrotna strona (P4): awaria w pierwszych 10 min serii, także po przerwie dłuższej niż 30 min, jest niewidoczna przez
resztę nocy. Program niewłączony na imprezie po udanym teście daje szary wiersz i **zielony** przycisk.

**Ocena:** kod jest dobry i spójny. Na produkcji najpierw P1–P3: to zmiany warunków w dwóch miejscach (`watch()` i `silentNow`),
każda na kilka linii, z testami w `DeckWatchIT`. Do czasu poprawki ryzyko jest ograniczone: dotyczy tylko imprez Pro z połączonym
programem, a DJ może wybrać „nigdy”. Push jest jeden na ciszę, ale przychodzi w złym momencie (przed setem albo po nim).

## Testy i sprawdzenia

- `tools/check.py unit`: **1573/1573**. `check.py it` (PostgreSQL 16 w kontenerze): **237/237**. Pierwszy przebieg padł na haśle
  `postgres`. Po `ALTER USER postgres PASSWORD '1111'` wszystko przeszło.
- `check.py browser`: **488/489**. Czerwony tylko `kiosk-full-fits-820x1180`, znany. Wszystkie scenariusze `deck-*` są zielone.
- `check.py audit -t dashboard`: 16 stron × 3 szerokości, bez przewijania w bok i bez błędów konsoli. Te same cztery małe cele co
  przed tą pracą, karty nie dotyczą. Karty audyt nie widzi (jest zwinięta i pokazywana od 768 px).
- Zrzuty rozwiniętej karty (Playwright, `server.py` z danymi: SILENT+warn, WAITING, OFFLINE szary; osobno OK + SILENT szary i
  OFFLINE+warn) w PL i EN, 800 i 1280 px, bez błędów konsoli i bez przewijania w bok. Zapisane w
  `docs/history/review-2026-11-27-shots-2/`: `karta-800.png`, `karta-1280.png`, `karta-en-1280.png`, `przycisk-offline-800.png`.
  Porównanie z `review-2026-11-27-shots/karta-800-po-poprawkach.png`: tak samo (P9).
- EXPLAIN ANALYZE (PostgreSQL 16, baza `s2p_review2`: schemat z migracji V1–V82, 1000 imprez, 2500 komputerów, z nich 312 z
  piosenką w 12 h, 300 000 próśb; po przeglądzie usunięta):
  - `silentNow`: Bitmap Index Scan `idx_dj_device_last_play` (312 wierszy) → Sort/Unique (125 prowadzących) → filtr serii →
    Nested Loop Semi Join z `Index Only Scan using idx_party_decision_time` (party_code + requested_at w Index Cond, 1 heap fetch)
    → Hash Join z `party_settings` (seq scan, 25 stron). **2,5 ms, 498 buforów.** Przy 1000 imprez czyta tylko komputery z
    piosenką w 12 h i jeden krótki skan indeksu na prowadzącego.
  - `guestsSince` (karta): Index Only Scan `idx_party_decision_time`, 4 bufory, 0,05 ms. Z `AND NOT added_by_dj` (propozycja P3):
    Index Scan + Filter, nadal 4 bufory, 0,06 ms.
  - `devices(party)`: Bitmap Index Scan `idx_dj_device_party`, 5 buforów, 0,04 ms.
- Odtworzenie fałszywych alarmów (to samo zapytanie co `silentNow`, `now()` zamiast parametru): `RSND` (próba 15 min sprzed 95–80
  min, prośba sprzed 1 min) → **cisza 80 min**; `REND` (ostatnia piosenka 21 min temu, prośba 15 min temu, czyli 6 min po jej
  starcie) → **21 min**; `RDJA` (próba 20 min, potem tylko piosenka dodana przez DJ-a) → **50 min**.
- Produkcja (agent `railway-reader`, tylko odczyt): deploy `2e332bb` SUCCESS 2026-11-27 14:34 UTC, Flyway „Successfully applied 2
  migrations … now at version v82” (0,041 s, PostgreSQL 18.6), start w 5,7 s. Brak WARN / ERROR (poza zwykłym `JAVA_TOOL_OPTIONS`)
  i 0 × 5xx. Wpisów „the DJ's program saw no song” jeszcze nie ma, bo okno było krótkie (sprawdzone ~2 min po wdrożeniu).
- Mutacje: tabela przy P12.

## Punkty

### P1 — próba dźwięku ≥ 10 min przed imprezą to „seria”: push przy pierwszej prośbie, zanim DJ zaczął grać · błąd

- **Gdzie:** `src/main/java/com/scan2play/service/DeckWatchService.java:152-155` (`setPlayed`: seria ≥ `RUN_MIN` = 10 min) i
  `:212` (to samo w SQL); `DjDeviceService.java:47-52` (`RUN_GAP`). Przewodnik `guide_pl.html` / `guide.html` obiecuje: „Piosenka
  puszczona na próbę i koniec seta ostrzeżenia nie dają”.
- **Dlaczego:** V82 odróżnia *jedną* piosenkę od seta, ale nie odróżnia próby dźwięku od seta. Wesele: DJ rozstawia się o
  14:30 i przez 15 min sprawdza nagłośnienie z VirtualDJ (cztery, pięć utworów, każdy trafia do historii). Potem cisza. O 16:00
  goście wracają z kościoła, siadają, skanują QR na stołach i o 16:05 przychodzi pierwsza prośba. Wejście pary, toast, „sto lat” i
  przemowy trwają, muzyki jeszcze nie ma. Prowadzący ma serię 15 min, ostatnia piosenka jest sprzed 80 min, a prośba przyszła po
  niej. Organizator i obsługa dostają push „⚠ Program DJ-a nie widzi piosenek — Od 80 min żadnej nowej piosenki”, a przycisk nad
  kolejką świeci na żółto. To ten sam fałszywy alarm co P1 pierwszego review, tylko przy dłuższej próbie. Odtworzone zapytaniem
  (`RSND` wyżej). Seria trwa dalej, aż DJ zacznie grać, bo `RUN_GAP` dotyczy tylko nowych piosenek.
- **Poprawka:** za „set” uznawać serię, przy której byli goście: **w trakcie serii przyszła prośba gościa**
  (`requested_at BETWEEN play_run_from AND last_play_at`, `NOT added_by_dj`), **albo seria trwała ≥ 30 min**. Próba dźwięku
  odbywa się bez gości. Druga część chroni set grany przed pierwszą prośbą. W SQL to drugi `EXISTS` na tym samym indeksie, w karcie
  jedno zapytanie zamiast `guestsSince` (oba warunki w jednym `SELECT EXISTS(…), EXISTS(…)`). Testy w `DeckWatchIT`: seria 15 min
  bez próśb i świeża prośba → brak; ta sama seria z prośbą w środku → jest; seria 35 min bez próśb → jest.

### P2 — koniec seta: „jeszcze jedna!” liczy się jako prośba po ciszy · ważne

- **Gdzie:** `DeckWatchService.java:56-57` (`AFTER_LAST_SONG` = 2 min), `:162-166` (`guestsAfter`), `:214` (`GREATEST(…,
  l.last_play_at + interval '2 minutes')`).
- **Dlaczego:** `last_play_at` to *start* ostatniej piosenki, a utwór trwa 3–5 min. Prośba z drugiej połowy ostatniego utworu, a
  tym bardziej z kilku minut po końcu muzyki („jeszcze jedna!”, typowe na koniec wesela), jest „po ostatniej piosence + 2 min”.
  Klub, 3:57: start ostatniego utworu, 4:02 koniec, 4:03 dwie prośby „jeszcze jedna”, impreza zostaje otwarta (DJ się pakuje,
  zamknięcia w „Godzinach imprezy” nie ustawił). O 4:17 organizator dostaje push „nie widzi piosenek”, a gdy laptop jest już
  zamknięty, „Program DJ-a się nie łączy”. Odtworzone (`REND`: prośba 6 min po starcie ostatniej piosenki → push po 21 min). Test
  `theEndOfTheSet_isNoFault_untilAGuestAsksSince` sprawdza tylko prośbę 1 min po starcie.
- **Poprawka:** liczyć prośby dopiero od **ostatnia piosenka + 8 min** (utwór plus chwila po nim; przy prawdziwej awarii goście
  proszą dalej, więc taka prośba i tak przyjdzie), a najlepiej wymagać **dwóch** takich próśb (pojedyncza spóźniona prośba to nie
  „goście dalej proszą”). Opcjonalnie: nie ostrzegać po `next_close_at` (V55), gdy jest ustawione. W przewodniku zdanie „koniec
  seta ostrzeżenia nie daje” zastąpić czymś prawdziwym: „…a po secie zamknij imprezę (albo ustaw godzinę zamknięcia)”. Testy:
  prośby 4 i 7 min po starcie ostatniej piosenki → brak; dwie prośby po 10 i 15 min → jest.

### P3 — piosenki dodane przez DJ-a w panelu liczą się jako „goście proszą” · ważne

- **Gdzie:** `DeckWatchService.java:168-171` (`guestsSince`) i `:213-215` (`EXISTS` w `silentNow`): bez `NOT added_by_dj`.
  Komentarz klasy i dokumentacja mówią „a guest's request”.
- **Dlaczego:** „+ Dodaj piosenkę” (V44) zapisuje wiersz `song_requests` z `added_by_dj = true`. DJ po próbie dźwięku albo w
  przerwie wpisuje listę pary młodej (pierwszy taniec, „must play”). Każdy taki wpis wygląda jak gość proszący po ciszy, więc push
  przychodzi przed setem albo w przerwie. Odtworzone (`RDJA`: tylko wpis DJ-a → push po 50 min). Raport dzienny
  (`EveningSummaryRepository`) już odróżnia `NOT added_by_dj`.
- **Poprawka:** `AND NOT added_by_dj` w obu zapytaniach (EXPLAIN: nadal 4 bufory). Test: seria, cisza, potem tylko wpis DJ-a →
  brak.

### P4 — czego reguła nie widzi: awaria w pierwszych 10 min serii i program niewłączony po teście (zielony przycisk) · ważne (do decyzji)

- **Gdzie:** `DeckWatchService.java:121` (`live` wymaga `setPlayed`), `DjDeviceService.java:183-185` (CASE: nowa seria po każdej
  przerwie > 30 min), `play-history.js` `show()` (`btn-outline-success`, gdy nic nie jest żółte).
- **Dlaczego:**
  - Przerwa dłuższa niż 30 min (obiad bez muzyki, występ zespołu, oczepiny z orkiestrą) zaczyna nową serię. Jeśli program przestanie
    widzieć piosenki w jej pierwszych 10 minutach, seria zostaje krótsza niż `RUN_MIN` aż do następnej widzianej piosenki, czyli do
    końca nocy. Nie ma ani żółtego, ani pusha.
  - DJ robi test o 15:00 (jedna piosenka, WAITING → OK, tak jak prosi karta), potem restartuje laptopa i nie uruchamia programu.
    Wieczorem prowadzący ma serię zero, więc `live` jest fałszem: wiersz jest szary („ostatnio 15:05”), a przycisk nad kolejką
    **zielony**, choć program nie łączy się od pięciu godzin. Od tej pracy zielony znaczy „w porządku”, bo żółty znaczy „problem”.
- **Poprawka (do decyzji właściciela):** (a) w CASE `notePlay` nie zaczynać nowej serii, gdy poprzednia już była setem
  (`last_play_at - play_run_from >= 10 min`), bo „set grał dziś na tym komputerze” to fakt na całą noc (12 h). Test dalej działa, bo
  seria zero minut po przerwie zostaje zastąpiona. (b) OFFLINE prowadzącego w trakcie imprezy (impreza otwarta, goście proszą) jest
  w karcie żółte bez warunku serii; push dalej z serią. „Nie łączy się” nie jest dwuznaczne. Ewentualnie (c): przycisk szary
  (`btn-outline-light`), a nie zielony, gdy prowadzący jest OFFLINE. Testy: przerwa 40 min, 3 min piosenek, cisza 25 min, prośba
  → push; test 15:00, program wyłączony, prośba o 19:00 → `warn` w karcie, bez pusha.

### P5 — podniesienie progu w trakcie trwającej ciszy wysyła drugi push od razu · drobne

- **Gdzie:** `DeckWatchService.java:185-191` (`warned.invalidate` przy `minutes > before`).
- **Dlaczego:** push przy 10 min. DJ go dostaje, uznaje za przedwczesny i o 25. minucie ciszy przełącza na „po 20 min”. Cisza (25
  min) jest już ponad nowym progiem, więc minutę później przychodzi drugi push o tej samej ciszy („Od 26 min”). Komentarz mówi
  „may be warned again when it reaches them”, ale ona ich już dawno sięgnęła. `ostrzezPo_changed_warnsAgainOnlyAtMoreMinutes`
  sprawdza tylko podniesienie powyżej trwającej ciszy (25 → 30).
- **Poprawka:** unieważniać tylko, gdy trwająca cisza jest krótsza niż nowy próg (`lead.lastPlayAt` z `devices` albo `warned`:
  `now - lastPlayAt < minutes`). Test: 10 → 20 przy ciszy 25 min daje jeden push.

### P6 — „nigdy”: zielone „widzi piosenki” przy piosence sprzed godzin · drobne

- **Gdzie:** `DeckWatchService.java:139` (`minutes > 0 &&`: przy 0 nigdy SILENT), `DeckWatchServiceTest.theStates` („never warn”:
  OK przy piosence sprzed 90 min).
- **Dlaczego:** „nigdy” miało wyłączyć ostrzeżenia, ale zmienia też opis. Przy „nigdy” wiersz przez 12 h pokazuje na zielono
  „✓ widzi piosenki · ostatnia 15:00”, choć od 15:00 nie widzi żadnej. To nieprawda w karcie, której celem jest pokazać prawdę.
- **Poprawka:** stan liczyć przy 0 z progiem domyślnym (20), a `warn` dalej fałsz. Wtedy jest szare „ostatnia piosenka 15:00”.
  Zmienić asercję testu.

### P7 — przycisk przy OFFLINE mówi „program nie widzi piosenek” · drobne

- **Gdzie:** `play-history.js` `show()` (jeden `deckToggleWarn` dla obu stanów), `dashboard.deck.toggle.warn` PL / EN; zrzut
  `przycisk-offline-800.png`.
- **Dlaczego:** wiersz mówi „program nie łączy się od 21:40”, a nazwa przycisku (czytnik ekranu, `title`) „program nie widzi
  piosenek”. To drobna niespójność, ale przy OFFLINE DJ ma sprawdzić laptop i internet, a nie program.
- **Poprawka:** dwa klucze (`…toggle.warn.silent` / `…toggle.warn.offline`) albo jeden neutralny: „program wymaga uwagi”.

### P8 — polityka prywatności i karta nie mówią o `play_run_from` · drobne

- **Gdzie:** `privacy_pl.html` / `privacy.html` (sekcja 2: „godzinę ostatniej przeczytanej piosenki i nazwę programu”),
  `dashboard.deck.privacy` („Zapisujemy tylko godzinę ostatniej (bez tytułu)”), V82.
- **Dlaczego:** serwer zapisuje też początek bieżącej serii, czyli godzinę pierwszej piosenki seta. To mało wrażliwe, ale polityka
  ma wymieniać dokładnie to, co zapisujemy, a karta mówi „tylko”. Karta nie wspomina też nazwy programu.
- **Poprawka:** PL: „godzinę pierwszej i ostatniej piosenki bieżącej serii i nazwę programu (bez wykonawcy i tytułu)”; karta:
  „Zapisujemy tylko godziny (początek seta i ostatnia piosenka) i nazwę programu, bez tytułów…”. EN tak samo.

### P9 — wiersz WAITING w 800 px: „Odłącz” sam w drugiej linii; znaki stanu różnej szerokości · drobne

- **Gdzie:** `play-history.js` `load()` (`d-flex flex-wrap`, znak jako zwykły `span`); zrzut `karta-800.png`.
- **Dlaczego:** po skróceniu tekstu (P14 pierwszego review) wiersz ma dwie linie, ale w drugiej stoi sam przycisk „Odłącz”, a opis
  zostaje w pierwszej. Znaki „!”, „…”, „·”, „✓” mają różną szerokość, więc nazwy komputerów nie zaczynają się w jednej kolumnie.
  Widać to na każdym zrzucie z dwoma, trzema komputerami.
- **Poprawka:** znak w `span` o stałej szerokości (np. `.s2p-deck-device-glyph { display:inline-block; width:1em; text-align:
  center }`), a opis w `span` z `flex-grow-1` i `min-width: 0`. Wtedy łamie się opis, a „Odłącz” zostaje na końcu.

### P10 — panel bez sieci albo bez Pro zostaje zielony; komunikat odmowy może nie zostać przeczytany · drobne

- **Gdzie:** `play-history.js` `load()` (`if (!response.ok) return;` i `catch → return`), `dashboard.html`
  `#deviceSilenceFailed` (`role="status"` na elemencie odkrywanym razem z tekstem).
- **Dlaczego:** gdy odświeżanie co minutę zawodzi (laptop DJ-a stracił Wi-Fi, sesja wygasła, Pro się skończył), karta i przycisk
  zostają w ostatnim stanie, często zielonym („widzi piosenki · ostatnia 21:43”), bez śladu, że to stan sprzed godziny. Region
  `role="status"` jest najpewniejszy, gdy istnieje i zmienia tylko tekst. Ukryty region odkrywany razem z tekstem czytniki często
  pomijają.
- **Poprawka:** po dwóch nieudanych odświeżeniach z rzędu przycisk szary, a pod listą „nie udało się sprawdzić (21:43)”.
  `#deviceSilenceFailed` zostawić stale widoczny i pusty, zmieniać tylko tekst.

### P11 — drobiazgi w tekstach i komentarzach · drobne

- `dashboard.computer.silence.help` („na urządzenia z włączonymi powiadomieniami o prośbach”): push dostaje obsługa z QUEUE, a
  powiadomienia o prośbach dostaje cała obsługa (`SongEvaluationService:279-282`). Lepiej: „…na Twoje urządzenia i obsługi, która
  prowadzi kolejkę”.
- Przewodnik PL / EN: „Piosenka puszczona na próbę i koniec seta ostrzeżenia nie dają” jest za mocne (P1, P2).
- Komentarze testów odwołują się do review („(review P1)”, „(review P6)” w `DeckWatchIT`). Według `AGENTS.md` miejsce na „jak to
  znaleziono” jest w `docs/history/` i w commicie. Lepiej opisać sam przypadek („a soundcheck before the party”). Do zrobienia przy
  okazji, nie osobno.
- Push: tytuł, treść z faktycznymi minutami, tag `s2p-deck-<impreza>` (nowy zastępuje stary, restart nie mnoży powiadomień) i
  link do panelu imprezy są dobre. EN „No new song for 47 min — check that “Scan2Play Zagrane” is running…” brzmi dobrze.

### P12 — testy: brakujące przypadki · ważne

- **Gdzie:** `DeckWatchIT.java`, `DeckWatchServiceTest.java`, `scenarios/play-history.js`.
- **Wynik mutacji** (kopia repo, każda mutacja osobno; unit = `DeckWatchServiceTest`, `DeviceApiControllerTest`,
  `PushNotificationServiceTest`, `PlanFeatureEndpointsTest`; it = `DeckWatchIT`, `DjDeviceIT`):

| # | mutacja | unit | it |
|---|---|---|---|
| M1 | karta bez warunku serii | zielony | **czerwony** |
| M2 | SQL bez warunku serii | zielony | **czerwony** |
| M3 | karta: prośba gdziekolwiek w 60 min (bez „po piosence”) | zielony | **czerwony** |
| M4 | SQL: to samo | zielony | **czerwony** |
| M5 | żółty na każdym komputerze | zielony | **czerwony** |
| M6 | push tylko do organizatora | zielony | **czerwony** |
| M7 | push też do obsługi bez QUEUE | zielony | **czerwony** |
| M8 | push: bez usuwania duplikatu (organizator w obsłudze) | zielony | zielony — **przeżyła** |
| M9 | „nigdy” z żółtym | zielony | **czerwony** |
| M10 | karta: SILENT już przy dokładnie 20 min | **czerwony** | zielony |
| M11 | SQL: cisza już przy dokładnie 20 min (`<=`) | zielony | zielony — **przeżyła** |
| M12 | SQL: prośba dokładnie 2 min po piosence liczy się (`>=`) | zielony | zielony — **przeżyła** |
| M13 | unieważnianie zawsze | zielony | **czerwony** |
| M14 | unieważnianie nigdy | zielony | **czerwony** |
| M15 | `notePlay`: wiersz sprzed V82 nigdy nie dostaje serii | zielony | zielony — **przeżyła** |
| M16 | `notePlay`: przerwa liczona do najnowszej piosenki wywołania | zielony | zielony — **przeżyła** |
| M17 | karta: seria dokładnie 10 min to nie set | **czerwony** | zielony |
| M18 | SQL: to samo (`>`) | zielony | zielony — **przeżyła** |
| M19 | SQL: prowadzący = najstarsza piosenka | zielony | **czerwony** |
| M20 | push: `offline` zawsze fałsz | zielony | **czerwony** |
| M21 | karta bez `isActive` | zielony | **czerwony** |
| M22 | `span`: piosenka „z przyszłości” zostaje | **czerwony** | zielony |
| M23 | program pierwszej, nie najnowszej piosenki | **czerwony** | zielony |
| M24 | karta: OFFLINE już przy dokładnie 3 min | **czerwony** | zielony |
| M25 | karta: okno gości 180 min zamiast 60 | zielony | zielony — **przeżyła** |
| M26 | `warned.put` usunięte (push co minutę) | zielony | **czerwony** |

19 z 26 czerwonych. Przeżyły: krawędzie SQL (M11, M12, M18; ich odpowiedniki w Javie są testowane), duplikat odbiorcy (M8),
wiersz sprzed V82 (M15), przerwa w obrębie jednego wywołania (M16) i 60-minutowe okno gości w karcie (M25).

- **Brakuje:**
  - przypadków P1–P3 (próba ≥ 10 min, „jeszcze jedna” po końcu seta, wpis DJ-a). Te testy byłyby dziś czerwone, więc to one
    pokazują błąd;
  - krawędzi SQL, które przeżyły mutacje (M11, M12, M18: dokładnie 20 min, dokładnie +2 min, seria dokładnie 10 min) i okna
    gości w karcie (M25: prośba sprzed 61 min nie liczy się);
  - wiersza sprzed V82 (M15) (`last_play_at` ustawione, `play_run_from` null) w `notePlay`;
  - jednego wywołania z kilkoma piosenkami przez przerwę > 30 min (kolejka programu po utracie internetu: seria ciągła, bo
    piosenki z przerwy wypełniają lukę; M16) — i odwrotnie, wywołania, którego najstarsza piosenka jest > 30 min po ostatniej;
  - odbiorcy-duplikatu (M8) (organizator jest też w obsłudze z QUEUE): jeden push, nie dwa;
  - zmiany prowadzącego w trakcie ciszy (zapasowy laptop dostaje jedną piosenkę na próbę: cisza głównego znika; to dobrze, ale
    nieopisane);
  - scenariuszy `deck-computer-lines` i `deck-silence-not-saved` na stronie EN (`?page=dashboard-en`): dziś tylko PL. Zrzuty EN są
    w porządku, ale test sprawdza tekst PL („program nie widzi piosenek”).
- **Poprawka:** dopisać powyższe. Każdy nowy test zobaczyć najpierw czerwony (dla P1–P3 na obecnym kodzie, dla krawędzi
  mutacją z tabeli).

## Punkty pozostawione w pierwszym review — czy nadal słusznie

| punkt | ocena |
|---|---|
| P8 (pamięć „raz na ciszę” tylko w Caffeine) | **Słusznie, z jednym zastrzeżeniem.** Restart powtarza push tylko imprezom cichym w tej chwili, a tag `s2p-deck-<impreza>` zastępuje stare powiadomienie, więc telefon nie dostaje dwóch. Po P1–P3 cisza będzie rzadka. Zastrzeżenie: `main` wdraża się od razu, a wdrożenia w piątek i sobotę wieczorem się zdarzają. Kolumna `deck_warned_play_at` (V83) to jeden warunkowy `UPDATE`. Warto ją dodać przy okazji P1–P3, bo i tak potrzebna będzie migracja. |
| P11 (komentarz V81 „DeckSilenceMonitor”) | **Teraz już na pewno zostawić.** V81 jest zastosowana na produkcji (Flyway v82), a zmiana pliku zmieniłaby sumę kontrolną i zatrzymała start aplikacji. Nazwę klasy można podać w V83, jeśli powstanie. |
| P12 (indeks częściowy, UPDATE bez HOT) | **Słusznie.** EXPLAIN: indeks daje bitmap scan po 312 z 2500 wierszy. `UPDATE` przy każdym wywołaniu z piosenką (co 3–4 min na komputer) przy ≤ 3 komputerach na imprezę to nic. |
| P13 częściowo (zapytanie o minuty osobno) | **Słusznie.** Odświeżenie karty to trzy zapytania po 4–5 buforów (~0,15 ms razem) raz na minutę na otwarty panel z komputerem. Połączenie minut z listą urządzeń (`LEFT JOIN party_settings`) oszczędziłoby jedno, ale `silenceMinutes` jest potrzebne też bez urządzeń. Przy P1 i tak warto połączyć `guestsSince` z drugim `EXISTS` w jedno zapytanie. |
| P17 (stary JS w czasie wdrożenia) | **Słusznie.** Wdrożenie już się odbyło (14:34 UTC). Panele otwarte przed nim po powrocie na kartę straciły listę do przeładowania. Jednorazowe. |

## Co jest dobrze (sprawdzone, bez punktu)

- **Karta i push liczą to samo:** prowadzący (`lead` w Javie = `DISTINCT ON … ORDER BY last_play_at DESC, id DESC` w SQL; ta sama
  granica 12 h `isAfter` / `>`), seria (`>= RUN_MIN` / `>= interval`), cisza (`isBefore` / `<`), goście (`max(last+2, now-60)` /
  `GREATEST`, oba `>`), `offline` z prowadzącego (`heardAt` / `COALESCE(last_seen_at, created_at)`). Różnica jest jedna i
  zamierzona: OFFLINE prowadzącego jest żółty w karcie po 3 min, push przychodzi dopiero po wybranych minutach.
- **`notePlay`:** CASE w `SET` czyta starą wartość `last_play_at` (semantyka PostgreSQL, sprawdzona w `DeckWatchIT`). Kilka
  piosenek w jednym wywołaniu: seria od najstarszej. Piosenka ponownie wysłana po restarcie programu (≤ 10 min wstecz) nie cofa
  czasu, a jeśli przyjdzie razem z nową, nie przerywa serii. Bez czasu i „z przyszłości” dostaje `now`, starsza niż 12 h jest
  pominięta. Wiersz sprzed V82 dostaje serię od następnej piosenki: przez pierwsze 10 min bez ostrzeżeń, czyli bezpiecznie.
  Kolejka programu po utracie internetu przychodzi paczkami po 20, od najstarszej, więc seria się nie rwie.
- **Pamięć „raz na ciszę”:** Caffeine z limitem 10 000 wpisów i 13 h. `warned.put` stoi przed kontrolą planu, więc impreza bez
  Pro nie jest sprawdzana co minutę od nowa. Wartość to `lastPlayAt` prowadzącego, więc nowa piosenka (także z innego
  komputera) otwiera nową ciszę. Obniżenie progu nie daje drugiego pusha.
- **Odbiorcy:** organizator, potem obsługa z QUEUE bez duplikatów (`!people.contains`). Członek bez QUEUE nie dostaje. Plan Pro
  jest sprawdzany przed odbiorcami, a w `/api/device/plays` przed `notePlay` (bez Pro nic nie jest zapisywane).
- **`POST /dj/devices/silence`:** QUEUE + Pro (`PlanFeatureEndpointsTest`), CSRF (Spring Security dla `/dj/**`, scenariusz sprawdza
  nagłówek), tylko 0 / 10 / 20 / 30 (400 dla 15, `abc` i braku), `CHECK 0–120` w bazie. Obsługa z QUEUE może ustawić „nigdy” dla
  organizatora, ale to mieści się w jej uprawnieniu do kolejki.
- **`deck-agent`:** `Played` z czwartym polem. `equals` porównuje cztery pola tylko tam, gdzie obie strony pochodzą z tego samego
  źródła (`NowPlaying`). `seen` używa `program|identity()`, bez pola programu. `heard` jest zmieniane w `synchronized tick()`, a okno
  dostaje niezmienny `Status` przez `invokeLater`. „OSTATNIA PIOSENKA” ma krój „CZYTA” i „OZNACZONE” (wersaliki, `MUTED`, godzina w
  kolumnie `readingWidth`) i stoi między nimi.
- **Wygląd:** `card bg-dark border-secondary`, select jak inne w panelu, kolory z tokenów (`--s2p-green`, `--s2p-text-muted`,
  `--s2p-warning`), `text-warning` w opisie żółtego wiersza, przycisk `btn-outline-warning` / `-success` / `-light`. Etykieta
  `for="deviceSilence"`. `aria-label` i `title` przycisku z ostrzeżeniem (oprócz P7). Teksty PL / EN są bezosobowe, w tonie
  panelu, a escape'y w `messages*.properties` poprawne (bez znaków spoza ASCII).
- **Dokumenty:** `docs/context/05-1d`, `10-2` i `docs/ideas.md` punkt 21 opisują kod zgodnie z nim, z wyjątkiem P1–P3 (te same
  obietnice co przewodnik) i P8 (polityka).
- **Koszt:** zadanie co minutę ~2,5 ms przy 1000 imprez, plus na każdą cichą imprezę ustawienia (cache), lista obsługi i push
  asynchronicznie. `GET /dj/devices` co minutę tylko przy widocznym panelu z komputerem: trzy zapytania po indeksach (~0,15 ms).
  `UPDATE` przy piosence: jeden wiersz po kluczu głównym. W przeglądarce jeden interwał, w programie jeden rekord na piosenkę.

## Fałszywe alarmy — przegląd sytuacji z imprezy (próg 20 min)

| sytuacja | dziś | ocena |
|---|---|---|
| test jednej piosenki, potem prośby przed setem | nic | dobrze (V82) |
| próba dźwięku ≥ 10 min, potem prośby przed setem | push przy pierwszej prośbie | **fałszywy** (P1) |
| DJ wpisuje listę pary w panelu po próbie | push | **fałszywy** (P3) |
| pierwszy taniec, przemowy, tort (< 20 min) | nic | dobrze |
| przemowy / oczepiny / obiad bez muzyki > 20 min, goście proszą | push | zgodnie z regułą, uciążliwe; DJ ma 30 / „nigdy” |
| zespół na żywo gra 45 min, DJ w przerwach | push w każdym secie zespołu, gdy goście proszą | jak wyżej, warto napisać w przewodniku: „nigdy” |
| obiad z muzyką z VirtualDJ (automix) | nic | dobrze |
| Spotify na pauzie, goście proszą | push | słusznie (nic nie gra) |
| laptop z muzyką uśpiony | żółty po 3 min, push „nie łączy się” | słusznie, choć DJ i tak słyszy ciszę |
| utrata internetu, muzyka gra | żółty po 3 min, push „nie łączy się” | słusznie, to najcenniejszy przypadek |
| zapasowy laptop połączony | szary | dobrze (P3 pierwszego review) |
| koniec seta, impreza otwarta, „jeszcze jedna!” | push 20 min po starcie ostatniego utworu | **fałszywy** (P2) |
| impreza otwarta na drugi dzień | nic (prośby > 60 min, piosenka > 12 h) | dobrze |
| nowa seria po przerwie > 30 min, awaria w jej 10 min | nic do końca nocy | **brak alarmu** (P4) |
| test, a wieczorem program niewłączony | szary wiersz, zielony przycisk | **brak alarmu** (P4) |
| prawdziwa awaria, goście proszą rzadko | push przy pierwszej prośbie po progu („Od 47 min”) | dobrze: bez prośby nikt nie czeka na „Zagrane” |
| restart / wdrożenie w trakcie ciszy | drugi push (ten sam tag) | akceptowalne (P8 pierwszego review) |

## Co poprawić najpierw (kod jest na produkcji)

1. **P1 + P2 + P3 w jednej zmianie** (dwa miejsca: `watch()` i `silentNow`, bez migracji): seria to set, gdy w trakcie były
   prośby albo trwała ≥ 30 min; prośba po ciszy liczy się od ostatniej piosenki + 8 min (najlepiej dwie); `NOT added_by_dj`. Testy
   z P12 najpierw czerwone. Przewodnik PL / EN: zdanie o próbie i końcu seta.
2. **P4** po decyzji właściciela: seria nie znika po przerwie, gdy już była setem; OFFLINE prowadzącego żółty w karcie bez warunku
   serii.
3. Potem P5–P11 według uznania. Jeśli przy P1–P4 powstaje migracja, dołożyć `deck_warned_play_at` (P8 pierwszego review).

## Poprawki (2026-11-27, gałąź `claude/modest-gauss-n1936m`, bez migracji)

| punkt | stan | co zrobione |
|---|---|---|
| P1 | poprawione | Set to seria 30 min piosenek albo 10 min z prośbą gościa w trakcie (`DjDeviceService.setSql`: jedna reguła dla serii i dla obserwacji; w karcie `setPlayed(device, guestDuringRun)`). Próba dźwięku bez gości nie jest setem. |
| P2 | poprawione | Goście „dalej proszą”, gdy przyszły **dwie** prośby później niż 8 min po starcie ostatniej piosenki, w ostatnich 60 min (`AFTER_LAST_SONG`, `GUESTS_AFTER`). Przewodnik PL / EN: po secie „Zakończ imprezę” albo godzina zamknięcia, przy zespole albo długiej przerwie 30 min albo „nigdy”. |
| P3 | poprawione | `NOT added_by_dj` w serii, w karcie i w pushu. |
| P4 | poprawione (a, b) | (a) Seria, która była setem, trwa po przerwie do 4 h (`SET_BREAK_MAX`). Następnego dnia, po teście i po próbie dźwięku zaczyna się nowa. (b) OFFLINE prowadzącego jest żółty w karcie, gdy goście proszą, także bez seta (program niewłączony po teście), ale bez pusha. (c) nie było potrzebne: po (b) przycisk w tej sytuacji jest żółty. |
| P5 | poprawione | Podniesienie progu unieważnia pamięć tylko wtedy, gdy ostrzeżona cisza trwa krócej niż nowy próg (`warned` trzyma jej początek). |
| P6 | poprawione | Przy „nigdy” stan liczony z domyślnych 20 min: szare „ostatnia piosenka 15:00” zamiast zielonego „widzi piosenki”. Ani żółtego, ani pusha. |
| P7 | poprawione | Przycisk mówi „program nie łączy się” (`dashboard.deck.toggle.offline`), gdy żółty jest program bez połączenia; „nie widzi piosenek”, gdy cichy. |
| P8 | poprawione | Polityka PL / EN: „godzinę ostatniej przeczytanej piosenki i początku seta, do którego należy”. Karta: „Zapisujemy tylko godziny (początek seta i ostatnia piosenka) i nazwę programu, bez tytułów”. |
| P9 | poprawione | Znak stanu w kolumnie 1em (`.s2p-deck-device-glyph`), opis łamie się w swoim miejscu (`.s2p-deck-device-words`), „Odłącz” zostaje na końcu wiersza (`flex-shrink-0`). Scenariusz `deck-computer-row-800`. |
| P10 | poprawione | Dwa nieudane odświeżenia z rzędu: „Nie udało się sprawdzić programu — stan z 21:43” (`#deviceStale`, `role="status"`), przycisk ani zielony, ani żółty, z tym w nazwie. Wraca przy udanym. `#deviceSilenceFailed` jest stale w DOM, zmienia się tylko tekst. Stand-in: `devicesStatus`. |
| P11 | poprawione | Pomoc selecta: „…na Twoje urządzenia i obsługi, która prowadzi kolejkę”. Przewodnik bez obietnicy o końcu seta. Komentarze testów bez „(review P…)”. |
| P12 | poprawione | Testy niżej. |
| P8 pierwszego review | zostawione | Pamięć dalej w Caffeine (bez migracji). |

Testy po poprawkach:
- `DeckWatchIT` 20 testów, w tym nowe: próba dźwięku i seria z gościem (10 / 9 / 30 min), piosenki DJ-a, okno 60 min, krawędź
  20 min, program niewłączony po teście (żółty bez pusha), set po przerwie / test / próba / następny dzień, seria wiersza sprzed V82
  i przez jedno wywołanie, organizator w obsłudze (jeden push), podniesienie progu ponad ciszę, prowadzący zmieniony w trakcie ciszy.
  **Na usługach z `main` (`2e332bb`) czerwonych jest dokładnie sześć nowych:** próba dźwięku (P1), koniec seta (P2), piosenki DJ-a
  (P3), set po przerwie (P4a), program niewłączony po teście (P4b), podniesienie progu (P5).
- `DeckWatchServiceTest`: seria (10 min z gościem, 9 min, próba 25 min, dokładnie 30 min), „nigdy” mówi prawdę.
- Przeglądarka: nowe `deck-list-not-loaded`, `deck-computer-row-800`, `deck-computer-lines-en`, `deck-silence-not-saved-en`;
  `deck-computer-lines` z nazwą przycisku przy OFFLINE. Na starym `play-history.js` czerwone: `deck-computer-lines` (nazwa
  przycisku), `deck-computer-row-800`, `deck-list-not-loaded`. Dwa scenariusze EN sprawdzają tylko język.
- EXPLAIN (PostgreSQL 16, `s2p_review3`, te same dane co wyżej, jedna trzecia serii krótka): `silentNow` **2,0 ms, 487 buforów**.
  Oba podzapytania (seria, goście) to `Index Scan using idx_party_decision_time`. `notePlay`: `Index Scan using dj_device_pkey`,
  podzapytanie serii wykonywane tylko przy przerwie > 30 min, **0,3 ms**.

- Całość: unit **1573/1573**, it **247/247** (PostgreSQL 16), przeglądarka **492/493** (czerwony tylko znany
  `kiosk-full-fits-820x1180`), `audit -t dashboard` bez przewijania w bok i bez błędów konsoli.
- Mutacje poprawionego kodu (kopia repo, każda osobno; unit i it jak w P12) — **wszystkie 19 czerwone**:

| # | mutacja | czerwony test |
|---|---|---|
| N1 | karta: set bez prośby w serii | `DeckWatchServiceTest` (seria), `DeckWatchIT.aSoundcheck…` |
| N2 | SQL serii: piosenki DJ-a liczą się jako goście | `theDjsOwnSongs_areNoGuests` |
| N3 | SQL serii: bez „30 min bez prośby” | `aSetGoesOnAfterABreak…` |
| N4 | `notePlay`: set nie trwa po przerwie | `aSetGoesOnAfterABreak…` |
| N5 | `notePlay`: bez limitu 4 h | `aSetGoesOnAfterABreak…` (następny dzień) |
| N6 | wystarczy jedna prośba po ciszy | `theEndOfTheSet…` |
| N7 | margines znów 2 min | `theEndOfTheSet…` |
| N8 | push: piosenki DJ-a liczą się po ciszy | `theDjsOwnSongs_areNoGuests` |
| N9 | karta: to samo | `theDjsOwnSongs_areNoGuests` |
| N10 | karta: OFFLINE tylko po secie | `theProgramGoneAfterTheTest…` |
| N11 | podniesienie progu zawsze unieważnia | `ostrzezPo_changed…`, `ostrzezPo_raisedPastTheSilence…` |
| N12 | „nigdy” nigdy SILENT | `DeckWatchServiceTest.theStates`, `theEdges` |
| M8 | push bez usuwania duplikatu | `theOrganiserInTheStaffToo_isWarnedOnce` |
| M11 | SQL: cisza przy dokładnie 20 min | `silent_pastTheMinutes_notAtThem` |
| M12 | SQL: prośba dokładnie 8 min po piosence | `theEndOfTheSet…` |
| M15 | wiersz sprzed V82 bez serii | `theRun_fromARowBeforeV82…` |
| M16 | przerwa liczona do najnowszej piosenki | `theRun_fromARowBeforeV82…` |
| M18 | SQL: seria dokładnie 30 min to nie set | `aSongPlayedAsATest…`, `aSoundcheck…` |
| M25 | karta: okno gości 180 min | `guestsAsking_onlyInTheLastHour` |

Na produkcji ta gałąź nic nie zmienia w bazie (bez migracji). Po wdrożeniu ten sam deploy co zwykle: start bez WARN od
`DeckWatchService`, a w logach wpisy „the DJ's program saw no song” tylko przy imprezach z setem i gośćmi proszącymi po nim.
