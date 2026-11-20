# Przegląd 2026-11-20 — „Ustawienia imprezy” w czterech grupach, PR-y #290 / #291

Zakres: Adas553/scan2play#290 (do `main`) i Adas553/scan2play#291 (do `dev`), gałąź `claude/admiring-cannon-fud6t3`, commit
`71e7bbd` („Settings page: cards in four groups, one rule for the folded states”) od `553390e` (scalone #288) plus dwa commity
samego handoffu (`156de59`, `339a8f5`). 14 plików, +739 / −699 (w `settings.html` prawie wyłącznie nowe wcięcia — czytane z
`git diff -w`), bez migracji.

Co zmieniono: karty strony `/dj/settings` w czterech grupach (`<section>` z nagłówkiem `h2`: Impreza / Dla gości / Ty jako DJ /
Dostęp), karty jako `h3`; „Życzenia przed imprezą” i „Pytanie do gości” to wiersze-linki „›” jak „Obsługa”; stany zwiniętych kart
według jednej zasady (`06-7-look.md`), nic nieustawione szare (`.s2p-fold-state-empty`).

## Wynik w skrócie

Zmiana jest zrobiona porządnie. Warunki grup zgadzają się z warunkami kart, fragmenty renderowane osobno działają, usuniętych kluczy
nic nie używa, a bezpieczeństwo jest bez zmian (wiersze to tylko linki, strony same sprawdzają uprawnienia). Jedyny prawdziwy błąd
zmiany to nowy kolor stanu ustawiony dla wszystkich stron (P1). Przy okazji wyszedł błąd starszy: żółty stan „blisko limitu” nigdy
nie był żółty (P2). Na żywo na telefonie było widać „Ustawione:” nad „4” (P3). Poprawione P1–P7: P1–P3 ze scenariuszami
przeglądarkowymi widzianymi najpierw na czerwono, P4 i P7 to nowe testy-strażnicy, P5–P6 to dokumenty i komentarz. P8–P10 i P13 poprawione po decyzji właściciela („zrób jak będzie najlepiej”), każde ze scenariuszem widzianym najpierw na
czerwono. P11–P12 to sprawy sprzed zmiany, poprawione na prośbę właściciela.

Testy po poprawkach (P1–P10, P13): unit 1434, w tym 2 czerwone lokalnie sprzed zmiany (P12); browser 459, wszystkie zielone
(454 i pięć nowych); audit `settings`, `staff`, `guest-poll` — 18 stron × 3 szerokości OK, bez małych celów dotyku.

## Uwagi

### P1 — Nowy kolor stanu objął też „Obsługę” i stronę gościa — **poprawione**

`static/css/app.css:1458` (`.s2p-fold-state` jasny dla wszystkich), `templates/staff.html:151`, `fragments/guest-poll.html:36`.

Scenariusz: DJ otwiera „Obsługę” bez linku zaproszenia — „Brak linku” jest jasne jak stan ustawiony, wbrew zasadzie z
`06-7-look.md`. Gość, który odpowiedział na pytanie, widzi na zwiniętej karcie „Twoja: Nie” prawie białe (`#f8f9fa`), a nie w szarości
swojego motywu (`guest-themes.css` nadpisuje `--s2p-text-muted`, ale nie `--s2p-light`). Ani jedno, ani drugie nie było celem zmiany.

**Poprawka:** domyślnie `.s2p-fold-state` jest znowu szary (strona gościa jak przed zmianą), jasne / szare stany tylko pod `.page-dj`.
„Brak linku” na „Obsłudze” dostał `.s2p-fold-state-empty`. Scenariusze `staff-fold-state-tones` i krok koloru w
`guest-poll-results-phone-320` — oba były **czerwone** przed poprawką.

### P2 — „Limity gości” blisko limitu nigdy nie były żółte — **poprawione (błąd sprzed zmiany, teraz bardziej widoczny)**

`static/css/app.css:1230` (`.s2p-row-warn`) i `:1458` (`.s2p-fold-state`); `settings.html:135`, `staff.html:117`.

Obie reguły mają tę samą wagę (jedna klasa), więc wygrywa późniejsza — kolor stanu. Od 80 % limitu serwera („Impreza 250/300”)
stan był tylko pogrubiony, nie żółty, choć komentarze i `guest-limits.js` mówią „w kolorze żółtym”. To samo na „Obsłudze”: „Miejsca
zajęte: 10 z 10”. Przed tą zmianą był szary i pogrubiony, po niej — biały, czyli jeszcze bardziej jak zwykły stan.

**Poprawka:** `.page-dj .s2p-fold-state.s2p-row-warn { color: var(--s2p-warning) }`. Scenariusze `settings-fold-state-tones`
i `staff-fold-state-tones` — **czerwone** przed poprawką.

### P3 — „Ustawione:” w jednej linii, „4” w drugiej — **poprawione**

`messages*.properties` (`settings.row.links`, `.staff`, `.library`, `settings.wishes.open`).

Na żywo na 375 px „Profile, kontakt i napiwki” zawijają się, a stan obok też: „Ustawione:” nad „4”. Stan łamie się tylko między
słowami (`min-width: min-content`), a spacja przed liczbą jest takim miejscem.

**Poprawka:** twarda spacja (U+00A0) między słowem a liczbą w czterech kluczach PL i EN. Scenariusz
`settings-fold-state-count-one-line` (360 px) — **czerwony** przed poprawką.

### P4 — Warunek grupy powtarza warunki kart, a nic tego nie pilnowało — **dodany test**

`settings.html:55`, `:323` (warunki `<section>`), `SettingsPageRenderTest`.

Dziś warunki się zgadzają, karta w kartę. Jeśli jednak ktoś zmieni `th:if` karty i zapomni o grupie, karta zniknie razem z grupą
(albo zostanie pusty nagłówek). Wspólny `th:with` na `<section>` nie wchodzi w grę: `partyHoursCard`, `guestThemeCard`, `kioskCard`
i `morningSummaryCard` są renderowane osobno przez kontrolery, bez sekcji wokół.

**Test:** `eachPermissionAlone_showsItsCard_inAGroupThatIsNeverEmpty`. Sprawdza każde uprawnienie osobno: osoba z obsługi widzi
dokładnie kartę tego uprawnienia i „To urządzenie”, a żadna grupa nie jest pusta. Do tego właściciel (cztery grupy w kolejności)
i cała obsługa bez Pro (Impreza / Dla gości / Dostęp, bez ofert). Na dziś zielony — to strażnik.

### P5 — Dokumenty i javadoc nie nadążyły — **poprawione**

`docs/context/05-1c-notifications-emails.md:5` („Powiadomienia wł. / wył.”), `05-1d-panel-heading-queue.md:23-27` (przykłady
„Instagram · napiwki”, tylko „Obsługa” jako wiersz, bez grup), `05-1i-wishes.md:32`, `05-1j-polls.md:64` („karta”),
`SettingsPageRenderTest` (javadoc: „the second card”).

### P6 — Przeniesiony komentarz „Limitów gości” niósł historię — **poprawione (drobne)**

`settings.html:125`. „(the design review: …; the owner: …)” to historia. AGENTS.md: porządkować przy okazji dotykania, a zmiana
ten komentarz przeniosła. Został sam powód, a dwa komentarze sklejone w jeden.

### P7 — Scenariusz całych słów nie obejmował wierszy „›” — **poprawione (test)**

`scenarios/folds.js` (`stateWordsWhole`). „Nieaktywne” obok „Życzenia przed imprezą” i „PRO” nie było mierzone. Teraz mierzone są też
wiersze — zielono.

### P8 — „Dostęp” dla osoby z obsługi to samo „To urządzenie” — **poprawione (właściciel: „zrób jak będzie najlepiej”)**

`settings.html:628`. Współorganizator albo „Obsługa kolejki” widzi grupę „Dostęp” z jedną kartą: powiadomienia i instalację
aplikacji. Z dostępem do imprezy nie ma ona nic wspólnego (link obsługi i kod są tylko właściciela). Do wyboru: zostawić; nazwać
grupę „Dostęp i to urządzenie”; albo dać „To urządzenie” osobno, bez nagłówka grupy, gdy reszta „Dostępu” jest ukryta.

**Poprawka:** dla obsługi nagłówek tej grupy to **„Urządzenie”** (EN „Device”), dla właściciela dalej „Dostęp”. Grupa bez
nagłówka zaburzyłaby strukturę (h2 → h3), a „Dostęp i to urządzenie” jest za długie jak na mały nagłówek. Scenariusz
`settings-staff-groups` i asercje w `aCoOrganiser_seesWhatSheWasGiven…` — **czerwone** przed poprawką.

### P9 — Formy stanów: „Kiosk dla gości — Włączone”, „3 min” — **poprawione**

`messages_pl.properties` (`settings.kiosk.on/none`, `settings.row.limits`). Zasada wybrała formę nijaką („Włączone” jak „Nie
ustawiono”), więc kiosk — rodzaj męski — nie zgadza się już z nagłówkiem (było „Włączony”). „Poranne podsumowanie — Włączone”
i „Twoje logo — Wgrane” się zgadzają. „2 na gościa · 3 min” łamie regułę „bez skrótów”. To jednak symbol jednostki, więc proponuję
dopisać ten wyjątek do `06-7-look.md`, a nie zmieniać tekstu.

**Poprawka:** kiosk znowu „Włączony / Wyłączony”, zgodnie z rodzajem nazwy karty. Reguła w `06-7-look.md` mówi teraz: przełącznik
„Wyłączone”, ale w zgodzie z nazwą karty, gdy ta jest rzeczownikiem („Kiosk dla gości: Wyłączony”); bez skrótów poza jednostką
(„3 min”). Scenariusz `settings-kiosk-card` — **czerwony** przed poprawką.

### P10 — Wiersze „›” nie są nagłówkami — **poprawione**

`settings.html:366`, `:376`, `:632`. Lista nagłówków na żywo: h2 grup, h3 kart, ale bez „Życzenia przed imprezą”, „Pytanie do
gości” i „Obsługa” (to `<span>` w `<a>`). Czytnik ekranu, który skacze po nagłówkach, je pominie. `<h3>` w `<a>` jest poprawnym
HTML. To jednak zmiana także dla „Obsługi” sprzed tej zmiany.

**Poprawka:** nazwa każdego wiersza to `<h3 class="s2p-card-title s2p-fold-name">`, jak w kartach. Wygląd się nie zmienia, bo style
są te same. Krok w `settings-folded` („every line's name a heading”) — **czerwony** przed poprawką.

### P11 — Baner „Impreza jest zamknięta” (`h3`) stoi przed `h1` — **poprawione (błąd sprzed zmiany)**

`fragments/components.html:26`. Na każdej stronie DJ-a przy zamkniętej imprezie pierwszy nagłówek to h3, przed h1. Do osobnej
drobnej poprawki (np. `<p class="h5">` albo `h2`).

**Poprawka:** `<p class="h3 fw-bold mb-3">` — wygląda tak samo, ale nie jest nagłówkiem. `h2` nie pomógłby, bo stałby dalej przed
`h1`. Krok „the page's first heading its h1” w `settings-folded` — **czerwony** przed poprawką (`H3`).

### P12 — Dwa czerwone testy jednostkowe lokalnie — **poprawione (nie ta zmiana, nie kodowanie znaków)**

`ForWhomPagesWebTest`, `SongEvaluationServiceTest`. Oba są zielone na GitHubie (Linux) dla `339a8f5` („Unit tests: success”), a nie
dotyczą żadnego pliku tej zmiany. `ForWhomPagesWebTest` oczekuje `"<h3>Na start\n"`, a `for-whom.html` jest w kopii roboczej z CRLF
(`git ls-files --eol`: `i/lf w/crlf`) — to końce linii Windowsa, nie znaki „�”. Znaki „�” w wyjściu to tylko konsola `check.py`.
`SongEvaluationServiceTest.aControlCharacterInTheAisName…` sprawdza, że w prompcie nie ma „–”. `SongEvaluationService:206` bierze
język z `LocaleContextHolder.getLocale()`, a bez żądania to domyślny język JVM: na tym Windowsie polski, na GitHubie angielski. Polski
szablon ma „jedno–dwa zdania” (`prompt-template_pl.txt:22`, od `e4baabf`), więc test łapie ten myślnik, a nie myślnik gościa.
**Poprawka** (same testy, kod bez zmian): `ForWhomPagesWebTest` sprawdza wzorcem `<h3>Na start\s*</h3>`, a nie `"\n"`.
`SongEvaluationServiceTest` sprawdza, że w prompcie nie ma słów gościa z półpauzą (`"Hulewicz – Za"`), a nie żadnej półpauzy
w ogóle — to był sens testu i teraz działa w obu językach. Wymuszenie angielskiego ukryłoby, że polski prompt też dostaje zwykły
łącznik.

### P13 — „🚫 0 · ⭐ 1 ·” nad „link” na 320 px — **poprawione**

`settings.html:201`. Na najwęższym telefonie stan listy gospodarzy łamie się po kropce, a „link” spada do drugiej linii. Nic się nie
rozjeżdża (audyt zielony), wygląda tylko trochę gorzej. Do wyboru: zostawić; twarda spacja przed „· link”; albo „🔗” zamiast słowa.

**Poprawka:** części stanu łączą twarde spacje (`settings.row.hosts`, `settings.row.hosts.link` z własnym „ · ”), więc na wąskim
telefonie zawija się nagłówek („Lista / gospodarzy”), a stan zostaje w jednej linii. Słowo „link” zostaje, bo jest jaśniejsze niż
ikona. Scenariusz `settings-hosts-state-one-line-320` — **czerwony** przed poprawką.

## Sprawdzone i w porządku

- **Warunki grup a warunki kart** (`settings.html:55`, `:323`, `:385`, `:628`): każdy `th:if` grupy to dokładnie alternatywa `th:if`
  jej kart. „Ty jako DJ” = `isOwner`, tak jak wszystkie cztery karty. „Dostęp” bez warunku, bo „To urządzenie” widzi każdy. Teraz
  pilnuje tego test z P4.
- **Fragmenty renderowane osobno** (`partyHoursCard`, `guestThemeCard`, `kioskCard`, `morningSummaryCard`): `th:fragment` siedzi na
  samym `<details>`, więc odpowiedź to sama karta bez `<section>`. Działają testy `…Answer…` w `SettingsPageRenderTest` i scenariusze
  zapisu w miejscu (`schedule`, `guest-themes`, `kiosk`, `morning-summary`).
- **`th:classappend`** na każdym stanie z tym samym warunkiem co `th:text`. Przy `th:unless` (PRO) stanu nie ma wcale.
  **SpEL** `{…}.?[#this != null].size()`: lista z wartości, kontakt liczony raz (e-mail albo telefon) — „Ustawione: 3” w teście.
- **Usunięte klucze** (`settings.row.links.contact/.tips`, `settings.wishes.help/.go`, `settings.polls.help/.go`) nie są użyte nigdzie
  w `src/` (grep). Zmienione stany (`settings.kiosk.*`, `settings.row.*`) są używane tylko na tej stronie. `ImpersonalPolishTextsTest`
  zielony.
- **Bezpieczeństwo** (V32: ukryte, nie wyszarzone): wiersze „Życzenia” i „Pytanie” mają te same warunki co dawne karty. Strony same
  pilnują dostępu: `DjWishesController:95` i `DjPollsController:58` (`sessionHelper.require(…, WISHES / POLLS)`), każdy POST tak samo,
  a zapisy Pro dodatkowo `planService.require`. Stan (`wishOpen`, `pollAsked`, `staff`) jest renderowany tylko wewnątrz `th:if` swojego
  wiersza. Wszystko przez `th:text`, bez `th:utext`. `party` w linkach przez `@{…(party=${partyCode})}` (kodowane). Żaden formularz się
  nie zmienił, więc CSRF jest bez zmian.
- **Hierarchia nagłówków** (na żywo): h1 „Ustawienia imprezy” → h2 grup → h3 kart; `<section aria-labelledby>` z nagłówkiem grupy.
  **Fokus z klawiatury**: `.s2p-fold-summary:focus-visible, .s2p-fold-link:focus-visible` (`app.css:1453`) obejmuje karty i wiersze.
  **Kontrast**: szary stan `#a3acb6` na karcie ~7 : 1 (token opisany w `app.css:33`), jasny `#f8f9fa` — różnica widoczna, oba
  powyżej AA.
- **Na żywo** (aplikacja z IntelliJ, zalogowany właściciel, 1028 px i 375 px): cztery grupy w kolejności. Stany: „Nie ustawiono” i
  „Tylko Ty” szare, reszta jasna. Strona nie przewija się w bok. Na 375 px widać było P3.
- **Zrzuty** (`page_audit.py --shots`, 320 / 390 / 1280): właściciel (`settings`), współorganizator (`settings-staff`: Impreza, Dla gości,
  Dostęp), bez Pro (`settings-free`: „PRO” przy nagłówkach, bez stanów obok, oferty w środku), „Obsługa” (`staff*`) i strona gościa
  z pytaniem.
