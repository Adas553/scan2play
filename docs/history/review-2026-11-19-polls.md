# Przegląd 2026-11-19 — gałąź `claude/upbeat-franklin-3aa8i3` („Pytanie do gości”, V74–V76)

Zakres: Adas553/scan2play#286 (do `main`) i Adas553/scan2play#287 (do `dev`, ta sama gałąź). 4 commity od `fae1f1b`, czyli
obecnego `main` (bez konfliktu numerów migracji: `main` kończy się na V73). 69 plików, +2781 / −49:

1. `0c8f529` — „Pytanie do gości”: `PollService`, `GuestPollController`, `DjPollsController`, `PollView`, V74, szablony, teksty,
   strony prawne, testy;
2. `4cb8644` — wyniki ukryte do czasu, aż DJ je pokaże (V75);
3. `acd5524` — na TV pytanie albo lista piosenek (V76);
4. `31bf8c8` — wpis w handoffie.

Gałąź nie była zmieniana. Wszystko poniżej sprawdzone w osobnym worktree (`31bf8c8`).

## Uruchomione sprawdzenia (sesja w chmurze, `tools/check.py`)

| Krok | Wynik |
|---|---|
| `unit` | 1425 testów, 0 błędów — OK (w tym `ImpersonalPolishTextsTest`, `GuestPollWebTest`, `PollsPageRenderTest`, `StaffPermissionEndpointsTest`, `PlanFeatureEndpointsTest`) |
| `it` | 195 testów, 0 błędów — OK (w tym `PollServiceIT`, `KioskMigrationIT`; PostgreSQL 16 kontenera, hasło `postgres` ustawione na `1111` — pierwszy przebieg padł w całości na haśle, nie na kodzie) |
| `browser` | 450 scenariuszy, w tym 13 nowych w `polls.js` — OK |
| `audit -t "guest-poll,polls,tv-poll,settings,dashboard"` | 34 strony × 320 / 390 / 1280 px: bez przewijania w bok i bez błędów konsoli — OK. Cele dotyku < 44 px: tylko 4 znane sprzed tej gałęzi („Szczegóły” i godziny harmonogramu na panelu). W stronach pytania żadnego |
| zrzuty (`page_audit.py --shots`) `guest-poll*`, `polls*`, `tv-poll` | obejrzane. Tak powstał P1 |
| kolor procentu na `/dj/polls` (Playwright, prawdziwy Bootstrap 5.3.8 z webjara) | `rgb(33, 37, 41)` na `rgb(33, 37, 41)`, czyli tekst w kolorze tła (P1) |

## Uwagi (od najpoważniejszych)

### P1 — Na stronie DJ-a nie widać procentów: tekst ma kolor tła — **błąd**

`templates/polls.html:76` (karta `card bg-dark` bez `text-white`), `templates/fragments/guest-poll.html:62`.

Wiersz wyniku pochodzi ze wspólnego fragmentu `pollRows`. Procent (`<span class="fw-bold">58%</span>`) nie ma własnego koloru i
dziedziczy go z karty. U gościa karta ma `text-white`, więc wszystko jest dobrze. Na `/dj/polls` karta to `card bg-dark` bez koloru
tekstu, więc dziedziczy `--bs-card-color` (#212529) na tle #212529. Zmierzone w przeglądarce: `rgb(33,37,41)` na `rgb(33,37,41)`.
DJ widzi tylko „(7)” i pasek, a liczba, która najbardziej go interesuje, zniknęła. Widać to też na zrzucie `polls-390.png`. Testy
tego nie łapią, bo sprawdzają tekst, a nie kolor.

**Poprawka:** nadać kolor w samym fragmencie, żeby nie zależał od karty, np. `.s2p-poll-share { color: var(--s2p-light); }`.
Można też dodać `text-white` do `section.s2p-poll-card`. Do tego scenariusz w `polls.js`, który porównuje `getComputedStyle(...).color`
procentu z tłem karty i jest czerwony przed poprawką.

### P2 — Zadane pytanie nigdy nie kończy się samo i przechodzi na następną imprezę — **błąd**

`PollService.java:129-143` (`asked`, `shown`), `PollService.java:205-217`. Brak jakiegokolwiek powiązania z `PartyScheduleService.setActive`
ani z `applyDue`.

Impreza DJ-a jest trwała: ten sam kod i te same `party_settings` z tygodnia na tydzień, a „Zakończ imprezę” / „Wznów” tylko
przełączają `active`. Pytanie żyje w stanie „zadane”, dopóki DJ nie kliknie „Zakończ pytanie”. Nic go nie kończy:
- ani zamknięcie imprezy (celowo, bo „DJ, który wstrzymał prośby, pyta salę”),
- ani ponowne otwarcie,
- ani upływ czasu (nocne czyszczenie zadziała dopiero po 90 dniach od utworzenia),
- ani utrata Pro (wtedy pytanie jest niewidoczne, ale po powrocie Pro wraca jako zadane).

Skutki, gdy DJ zapomni zakończyć pytanie w sobotę:
- w następną sobotę goście nowego wesela widzą nad formularzem pytanie z poprzedniego, razem z jego głosami;
- TV pokazuje to pytanie zamiast listy piosenek;
- karta w ustawieniach mówi „zadane teraz”;
- przez cały tydzień każde wejście na stronę „DJ nie przyjmuje teraz próśb” (np. ze starego plakatu) tworzy sesję w PostgreSQL.

To typowa sytuacja: DJ pakuje sprzęt i nie wraca do `/dj/polls`.

**Poprawka (proponuję obie części):**
- (a) „Wznów imprezę” i otwarcie z harmonogramu (`setActive(true)` / `applyDue`) kończą pytanie zadane przed otwarciem. To jedno
  `UPDATE party_poll SET closed_at = now() WHERE party_code = ? AND opened_at IS NOT NULL AND closed_at IS NULL` w tej samej transakcji.
  Nowa impreza zaczyna się wtedy bez starego pytania.
- (b) Górna granica: pytanie zadane dłużej niż np. 12 h liczy się jako zakończone, czyli warunek
  `opened_at > now() - interval '12 hours'` w `asked`, `shown` i `vote`, albo kolumna `closes_at` ustawiana przy „Zadaj”.
  `05-1j-polls.md` powinien to opisać.

Do tego test IT: pytanie zadane, impreza zamknięta i otwarta ponownie → `shown` jest puste.

### P3 — „📺 TV: pokaż pytanie” pokazuje zakończone pytanie z powrotem także na telefonach — **ryzyko**

`PollService.java:159-162` (`setOnTv` ustawia `revealed_at = now()`), `PollService.java:138-143` (`shown` liczy 15 minut od
`revealed_at` dla wszystkich ekranów), `PollView.java:149` (filtr `onTv` tylko dla TV).

Etykieta i dokumentacja mówią: „the phones are not changed by it”. Tymczasem przełączenie zakończonego pytania na TV ustawia
`revealed_at`, a `shown()` dla gościa nie rozróżnia, kto je ustawił. Przykład: DJ godzinę po zakończeniu pytania „Kto pierwszy na
parkiet?” klika „📺 TV: pokaż pytanie”, bo chce je pokazać na ekranie. Wtedy każdy gość na telefonie znów dostaje kartę z wynikami
nad formularzem, na 15 minut. Każde kolejne przełączenie przedłuża ten czas. Przycisk TV jest też na pytaniu „na później”, gdzie
nic nie robi (`opened_at` jest NULL), ale DJ może sądzić, że coś ustawił.

**Poprawka:** rozdzielić „pokazane na ekranach”:
- `setOnTv` nie rusza `revealed_at`;
- jeśli przywracanie zakończonego pytania na sam TV ma zostać, to osobna kolumna `tv_shown_at`, liczona tylko w `addForScreen`
  (np. `shown(partyCode, screen)` z `greatest(closed_at, revealed_at, CASE WHEN screen THEN tv_shown_at END)`).

Prostsza wersja: przełącznik TV tylko przy pytaniu zadanym, a przy zakończonym go nie ma. Do tego test, że po
`setOnTv(true)` na zakończonym pytaniu karta gościa jest pusta.

### P4 — Karta pytania na stronie DJ-a: pięć przycisków, dwa z 📺, trzy linijki stanu — **do poprawy (UX)**

`templates/polls.html:89-130`; zrzut `polls-390.png`.

Przy zadanym pytaniu na telefonie DJ ma kolejno:
- „Zakończ pytanie”,
- „📺 Otwórz ekran TV”,
- „Ukryj wyniki przed gośćmi”,
- „📺 TV: pokaż listę piosenek”,
- „Usuń”,

a nad nimi trzy szare zdania („Goście widzą je nad polem prośby…”, „Goście i ekran TV widzą wyniki.”, „Ekran TV: pytanie.”).

Dwa przyciski z 📺 robią zupełnie różne rzeczy: pierwszy otwiera stronę TV w nowej karcie, drugi zmienia, co ta strona pokazuje.
Łatwo je pomylić w ciemnej sali. „📺 TV: pokaż listę piosenek” jest też przy pytaniu „na później” (zob. P3). Na tle „Życzeń przed
imprezą” i kiosku, gdzie przy pozycji są 1–2 działania, karta wygląda na przeładowaną.

**Propozycja:**
- jedna linijka stanu: „Wyniki: widoczne / ukryte · TV: pytanie / lista piosenek”;
- przełącznik TV jako dwa przyciski w grupie („Na TV: [Pytanie] [Piosenki]”, wybrany wyróżniony), tylko przy pytaniu zadanym;
- „📺 Otwórz ekran TV” jako link bez 📺 („Otwórz ekran TV ↗”) albo przeniesiony obok „← Ustawienia imprezy” (dashboard już go ma);
- „Usuń” bez pełnej szerokości.

### P5 — Teksty: kilka etykiet mówi mniej, niż robi przycisk — **do poprawy**

`messages_pl.properties` / `messages.properties` (klucze poniżej), `GuestPollController.java:71`.

- `polls.results.show` „👀 Pokaż wyniki gościom” zmienia też TV (komunikat po kliknięciu już to mówi: „goście i ekran TV je widzą”).
  Proponuję „👀 Pokaż wyniki (goście i TV)” / „Ukryj wyniki (goście i TV)”. Teraz emoji ma tylko jedna z dwóch wersji przycisku.
- `polls.new.results.after_vote` „Od razu po ich głosie” brzmi nienaturalnie → „Od razu po oddaniu głosu”. Etykieta
  `polls.new.results` „Wyniki dla gości” jest jasna.
- `polls.asked.help` „Nowe pytanie zakończy to.” → „Zadanie nowego pytania zakończy to.”
- `polls.new.results.later` EN „Only when I show them (a game, say)” → „Only when I show them (e.g. a game)”.
- `INVALID` (zła odpowiedź) daje `guest.poll.note.ended` „To pytanie jest już zakończone.” To nieprawda, choć zdarza się tylko
  przy ręcznie zmienionym żądaniu → osobny `guest.poll.note.invalid` albo po prostu karta bez notki.
- Ton i bezosobowość są w porządku: „Pytanie od DJ-a”, „Wyniki pokaże DJ.”, nic w rodzaju „DJ zrobił”
  (`ImpersonalPolishTextsTest` zielony). „DJ nie przyjmuje… / DJ zrobił przerwę” pod kartą na `party_ended` to stary tekst, już na
  liście właściciela.

### P6 — Strona gościa: pytanie nad nagłówkiem „kto gra”, a po odpowiedzi pełna karta zostaje nad formularzem — **do poprawy (produkt)**

`templates/index.html:18-20` (karta przed `div.card` z logo i `h1`); zrzuty `guest-poll-390.png`, `guest-poll-answered-390.png`.

Kolejność „pytanie nad polem prośby” jest dobra, dopóki gość nie odpowiedział: DJ powiedział „zeskanujcie kod” właśnie po to.
Dwie rzeczy jednak zagłuszają główną funkcję:
- karta stoi nad całą kartą strony, więc przed logo DJ-a i `h1` („kto gra”). Pierwsze, co gość widzi, to pytanie, a nie impreza.
  Na stronie jest też `h2` przed `h1`;
- po odpowiedzi pełne wyniki (3–4 paski) i „Zmień odpowiedź:” zostają na górze przez cały czas trwania pytania i jeszcze 15 minut
  po nim. Pole prośby schodzi o ~330 px. Na 320 × 640 przy 4 odpowiedziach dobrze widać już tylko przycisk „Wyślij prośbę”.

**Propozycja:**
- po odpowiedzi zwinąć kartę do jednej linijki („🗳 Disco polo — Twoja: Nie · 58% Tak ▸”) jako `<details>`, z wynikami i zmianą
  odpowiedzi w środku. Wzorzec zwijanych kart już jest (`s2p-fold`);
- przed odpowiedzią zostawić jak jest;
- zakończone pytanie (15 min) też w wersji zwiniętej.

Do decyzji właściciela, czy karta ma iść pod nagłówek z logo, ale nad pole (wymaga podzielenia głównej karty).

### P7 — Dodatkowe zapytania na każdym wejściu gościa i każdym odświeżeniu TV, także gdy imprezy nie dotyczą — **ryzyko (wydajność)**

`PollView.java:143-157`, `GuestController.java:78`, `TvScreenController.java:73-76`.

`PollView.add` odpytuje bazę przy każdym `GET /p/{code}` imprezy z Pro. To najgorętsza strona aplikacji, a dotyczy to każdej imprezy
z Pro, choć zdecydowana większość nigdy nie zada pytania:
- `shown()`, z indeksem `(party_code, id)`, czyli tanio, ale to zawsze dodatkowa podróż do bazy;
- przy pytaniu do tego `choiceOf` i `results` (wyniki są w cache).

TV co 10 s robi `shown()`, a gdy pokazuje pytanie, dalej liczy też `mostWanted`, którego nie wyświetla.

**Poprawka:**
- `shown(partyCode)` w Caffeine na 2 s (jak wyniki), czyszczone po commicie każdego działania DJ-a (`create`, `ask`, `end`,
  `setResultsHidden`, `setOnTv`, `delete`). Przy imprezie bez pytań to zero zapytań na prawie każdym wejściu;
- w `TvScreenController.addList` nie liczyć `TV_SONGS`, gdy model ma `POLL`.

### P8 — Strona DJ-a: do 30 osobnych `GROUP BY` przy każdym wejściu — **do poprawy**

`DjPollsController.java:63`.

`pollsOf(...).stream().map(pollService::results)` to N+1: jedno zapytanie na pytanie, do `MAX_POLLS` = 30. Pytania „na później”
z definicji nie mają głosów (`vote_count = 0`). Każde przekierowanie po formularzu powtarza całość.

**Poprawka:**
- jedno `SELECT poll_id, choice, count(*) FROM party_poll_vote WHERE poll_id = ANY(?) GROUP BY poll_id, choice`;
- albo przynajmniej pomijać pytania z `vote_count = 0` (wyniki z samych zer bez zapytania).

### P9 — Odpowiedź na pytanie zużywa ten sam limit adresu co 👍 — sala na jednym Wi-Fi — **ryzyko**

`GuestPollController.java:66` → `GuestRequestLimiter.tryAcquireVote` (klucz `ip|party`, 300 na 10 min, wspólny z 👍).

W sali na Wi-Fi lokalu wszyscy goście mają jeden adres. Ich 👍 i odpowiedzi na pytanie (każda zmiana zdania też) idą z jednej puli
300 na 10 minut. Po „głosujcie teraz!” DJ-a, kiedy goście chwilę wcześniej klikali 👍, część sali dostanie „Za dużo głosów z tej
sieci” akurat przy pytaniu, na które DJ czeka na scenie. Limit adresu to sensowna obrona przed skryptem, a sufit `MAX_VOTES` = 5000
i tak ogranicza liczbę wierszy.

**Poprawka:** osobny worek dla odpowiedzi, np. `tryAcquirePollAnswer` z kluczem `poll|ip|party` i tym samym limitem albo wyższym, bo
skrypt zmieniający odpowiedź niczego nie mnoży: zmiana to `UPDATE` jednego wiersza. Do tego test, że 300 kliknięć 👍 nie blokuje
odpowiedzi.

### P10 — JS gościa i DJ-a: Safari bez `submitter`, fokus i `aria-live` po podmianie karty, odpytywanie po wylogowaniu — **drobiazg**

`static/js/guest-poll.js:52-53`, `:19`, `templates/fragments/guest-poll.html:12`, `static/js/polls.js:17-30`.

- Bez `SubmitEvent.submitter` (Safari < 15.4) skrypt wywołuje `preventDefault()`, potem `return`, i stuknięcie nic nie robi, nawet
  zwykłego wysłania formularza. Poprawka: przy braku `submitter` nie przerywać zdarzenia (formularz bez JS działa i wraca na stronę).
  Drugie wyjście: pobrać `submitter` przed `preventDefault()`.
- `box.replaceWith(next)` zastępuje cały region `aria-live`. Czytniki ekranu zwykle nie ogłaszają regionu, który dopiero pojawił
  się w DOM. Kliknięty przycisk znika, więc fokus spada na `body`. Poprawka: zostawić `section#guestPoll` i podmieniać jego zawartość
  (`replaceChildren`), a po odpowiedzi przenieść fokus na `#pollQuestion` (`tabindex="-1"`).
- `polls.js` po wygaśnięciu logowania (`response.redirected`) dalej pyta co 3 s, bez końca. Po zakończeniu pytania przez kogoś z
  obsługi na innym telefonie nadal pokazuje „zadane teraz”. Poprawka: po przekierowaniu `clearInterval`. Gdy fragment wyników ma
  `data-state` inny niż `ASKED`, przeładować stronę.
- Podwójne stuknięcie jest dobrze obsłużone (przyciski `disabled`, test `guest-poll-double-tap`), błąd sieci też („Nie udało się…”).

### P11 — Kod: rekord `Poll` z konstruktorami tylko dla testów, pełne nazwy klas w kodzie — **drobiazg**

`PollService.java:67-77`, `:255`, `:322-329`, `DjDashboardController.java:101`.

- Dwa dodatkowe konstruktory `Poll` (8 i 10 argumentów) używają tylko testy (`GuestPollWebTest`, `PollServiceTest`,
  `PollsPageRenderTest`, `PollControllers`). Każda kolejna kolumna dołoży następny. Poprawka: jeden kanoniczny konstruktor plus w
  testach mała fabryka (`PollControllers.poll(...)` z „wither”-ami albo builderem testowym).
- `com.scan2play.util.AfterCommit`, `java.security.MessageDigest`, `java.util.HexFormat`, `jakarta.servlet.http.HttpSession` w
  `PollService` i `com.scan2play.service.PollService` w polu `DjDashboardController` zapisane pełnymi nazwami, a nie przez `import`.
  Reszta plików ma importy.
- `end()` robi drugie zapytanie (`poll(...)`) tylko po to, by odróżnić 404. Wystarczy `UPDATE … RETURNING` albo
  `update == 1 || poll(...)`. To drobiazg.
- Procenty zaokrąglane osobno mogą dać sumę 99 albo 101 (np. 3 × 33%). Na TV raczej nikt tego nie zauważy, ale można zostawić
  z komentarzem albo zastosować metodę największej reszty.

### P12 — Nocne czyszczenie liczy 90 dni od utworzenia, nie od użycia — **drobiazg**

`PollService.java:306-308`.

Pytanie zapisane „na później” 89 dni temu i zadane dziś zniknie w nocy, razem z głosami, w środku wesela. To rzadkie, ale możliwe
przy DJ-u, który ma „stałe” pytania. Poprawka: `WHERE greatest(created_at, coalesce(closed_at, opened_at, created_at)) < ?`
(indeks na wyrażeniu albo zwykły skan: tabela jest mała) i zdanie w polityce „90 dni od ostatniego użycia pytania”. Druga
możliwość: zostawić tak jak teraz i przy zadawaniu ustawiać `created_at = now()`, ale wtedy zmienia się znaczenie kolumny.

### P13 — Trzy migracje jednej niewydanej funkcji; komentarz V74 mówi „losowy token” — **drobiazg**

`V74__guest_polls.sql:3`, `V75__…`, `V76__…`.

- V74 mówi „a random token of their session”, a to SHA-256 identyfikatora sesji (`voterOf`), nie losowy token. Poprawić komentarz
  przed wydaniem, bo potem migracji już się nie edytuje.
- V75 i V76 dodają tylko kolumny do tabeli z V74. Skoro nic nie jest wydane, można je wcisnąć w V74 (jedna migracja = jedna
  funkcja), **ale tylko jeśli** żadna wspólna baza (`dev`, jeśli ma wdrożenie, ani lokalna `scan2play` właściciela) nie ma jeszcze
  V74–V76. Inaczej zostawić; to kosmetyka.

### P14 — Brakujące testy — **drobiazg**

- Kontrast procentu na `/dj/polls` (P1): scenariusz z `getComputedStyle`.
- Pytanie zadane i zapomniane (P2): IT „zamknięta i otwarta impreza → `shown` puste”.
- `setOnTv(true)` na zakończonym pytaniu nie wraca na telefony (P3).
- `vote()` z id pytania innej imprezy na prawdziwej bazie (`PollServiceIT`). Teraz IDOR jest sprawdzony w IT tylko dla `ask` i
  `setResultsHidden`, a dla głosu tylko na mockach. Kod jest dobry (`WHERE party_code = ? AND id = ?`), brakuje straży.
- TV 1280 × 720 z najdłuższym pytaniem (120 znaków) i czterema odpowiedziami po 40 znaków. Zrzut `tv-poll` ma 1280 × 800 i krótkie
  teksty. Z rachunku `vh` mieści się na styk, ale nikt tego nie widział.
- `MigrationIT.theIndexesAreTheOnesTheQueriesNeed` nie wymienia `idx_party_poll_party`, `uk_party_poll_asked`,
  `idx_party_poll_created`.

### P15 — Dokumentacja: drobne rozjazdy z kodem — **drobiazg**

- `docs/context/05-1j-polls.md`, akapit „The DJ's page”: „📺 Pokaż na TV”, a w aplikacji jest „📺 Otwórz ekran TV” (`polls.tv`).
- `PROJECT_CONTEXT.md`, tabela: `05-1j-polls.md` „5 KB”, a plik ma 6,3 KB.
- `05-1j` nie mówi, że pytanie nie kończy się samo (P2) i że przełącznik TV na zakończonym pytaniu wraca na telefony (P3). Do
  poprawienia razem z kodem.
- Pozostałe miejsca są zgodne z kodem: `10-2-migrations.md` (V74–V76), `11-12-dependencies-endpoints.md` (wszystkie 9 endpointów,
  CSRF, sesja), `06-1`, `06-3` (szablony i skrypty), `04-1d` (`POLLS` w planie i uprawnieniach, V74 dla pełnych zestawów).
  `06-7-look.md` mógłby dostać jedno zdanie o paskach `<progress>` jako wzorcu wyników (zamiast stylu inline).

## Co jest dobrze

- **CSRF i sesja.** Pułapka z CLAUDE.md „Mind” (1) jest obsłużona: `PollView.addForGuest` tworzy sesję przed rysowaniem strony, gdy
  pytanie ma formularz, i tylko wtedy. Gdy nic nie jest zadane, strona żadnej nie tworzy (`GuestPollWebTest` ×2, także dla zamkniętej
  imprezy i TV). Wygasły formularz wraca na stronę imprezy (`GuestFormExpiredHandler`, JS przeładowuje).
- **Ukryte wyniki (V75) naprawdę nie trafiają do HTML-u.** `PollView` nie wkłada ich do modelu. Test sprawdza brak `%`, pasków i
  liczby głosów odpowiedzi w karcie i w publicznym `/p/{code}/tv/list` oraz `verify(pollService, never()).results(any())`.
- **Uprawnienia i plan.** Każdy z 7 endpointów DJ-a wymaga `POLLS` (`StaffPermissionEndpointsTest`). `create` i `ask` wymagają Pro
  (`PlanFeatureEndpointsTest`, `verifyNoInteractions`). Kończenie i usuwanie działa bez planu, jak przy życzeniach.
- **IDOR.** Każde zapytanie ma `party_code = ? AND id = ?`, a pytanie innej imprezy daje 404 albo `NOT_ASKED`.
- **XSS i CSP.** Tylko `th:text` i `th:attr` (escapowane), żadnego `style=`. Paski to `<progress>`, a JS wstawia wyłącznie HTML z
  serwera i sprawdza `id` fragmentu.
- **Współbieżność na prawdziwym PostgreSQL** (`PollServiceIT`, zielone):
  - głos pod `FOR UPDATE` wiersza pytania;
  - „Zadaj” pod blokadą wiersza imprezy;
  - częściowy unikalny indeks jako druga linia obrony;
  - podwójne stuknięcie, 50 głosów naraz, sufit pod obciążeniem, dwa „Zadaj” naraz.

  Kolejność blokad nie tworzy cyklu: głos blokuje tylko pytanie, a FK do imprezy nie jest sprawdzany przy `UPDATE vote_count`.
  `greatest()` w `shown` poprawnie pomija NULL.
- **Cache wyników** jest czyszczony po commicie (`AfterCommit`), a nie w środku transakcji.
- **Kaskady** (usunięcie imprezy, zmiana kodu, usunięcie pytania) i nocne czyszczenie mają testy.
- **Prywatność.** Polityka mówi prawdę: SHA-256 identyfikatora sesji, bez IP w bazie, głos nie trafia do logów (logowane są tylko
  id pytania i działania DJ-a), 90 dni.
- **Wygląd.** Karta gościa, przyciski (48 px i 44 px), toasty (`s2p-done-toast` jak w życzeniach), karta w ustawieniach (`s2p-fold`
  z „Pro”) i TV używają tokenów i wzorców aplikacji. Audyt nie znalazł przewijania w bok ani małych celów. Wyjątkiem jest P1.
- **Dopasowanie do produktu.** Pytanie to karta, a nie podmieniona strona. Nie ma go w kiosku. Odrzucone pomysły (własne odpowiedzi
  gości, quizy z imionami) są zapisane w `docs/ideas.md` z powodami.

## Werdykt

Funkcja jest przemyślana i dobrze zabezpieczona. Bezpieczeństwo, współbieżność i prywatność są w porządku, a testy obejmują to, co
najważniejsze. **Do wydania jeszcze nie**, bo trzy rzeczy psują ją w normalnym użyciu:

- **P1** — DJ nie widzi procentów na swojej stronie (poprawka w CSS i scenariusz);
- **P2** — zapomniane pytanie przechodzi na następną imprezę i zajmuje TV;
- **P3** — przełącznik TV wbrew opisowi wraca pytanie na telefony.

Przed wydaniem zalecam też **P4** i **P5** (czytelność przycisków i etykiet DJ-a), **P9** (osobny limit odpowiedzi, sala na jednym
Wi-Fi) i decyzję właściciela w **P6** (karta zwinięta po odpowiedzi). P7 i P8 (zapytania) są tanie i warto je zrobić od razu. P10–P15
mogą iść w tej samej poprawce albo później. Przy P13 trzeba zdecydować przed wydaniem, czy łączyć migracje.
