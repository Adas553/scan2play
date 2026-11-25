# Przegląd 2026-11-25 — instrukcja „Jak działa Scan2Play” w nowym formacie, PR-y #309 / #310

Zakres: Adas553/scan2play#309 (do `main`) i Adas553/scan2play#310 (do `dev`), gałąź `claude/sleepy-johnson-0z01t9`, commity
`a06f763` (treść: „Przed imprezą”, „🗳 Pytanie do gości”, Ustawienia w czterech grupach, poranne podsumowanie, strona gościa) i
`8e9bb67` (format: menu przyklejone u góry, „W skrócie”, „Więcej”) plus `ae593a4` (handoff). 8 plików, +603 / −134, bez migracji i
bez Javy (poza testem renderu).

## Wynik w skrócie

Zmiana jest dobra: treść zgadza się z kodem co do liczb i nazw przycisków (tabela niżej), PL i EN mówią to samo, menu przykleja się i
skacze dobrze we wszystkich mierzonych szerokościach w obu językach, strona bez JavaScriptu działa. Znalazłem dwa prawdziwe błędy
skryptu/CSS (P1, P2) i jeden dostępności (P3) — wszystkie trzy poprawione ze scenariuszami widzianymi najpierw na czerwono. P4–P8 to
drobne poprawki (dostępność, trzy zdania treści, dokumentacja, opcja harnessu). P9–P12 to decyzje wyglądu, zadane jako pytania z
rekomendacją; właściciel: „P9 i P10 zrób jak rekomendujesz, P11 zostaw, P12 zrób jak uważasz” — P9, P10, P12 zrobione.
P13–P20 to ocena bez zmian (Ctrl+F, bez JS, ruch, wydajność, testy, CSS, drobiazgi).

Testy z zadania przed poprawkami: unit `GuidePageRenderTest`, `DashboardPageRenderTest` 37/37; browser `guide-nav-phone`,
`guide-nav-computer`, `guide-page-phone`, `guide-pictures-phone`, `guide-pictures-computer` 5/5; audit `guide` 2 strony × 3 szerokości
OK. Po poprawkach: patrz „Testy po poprawkach” na końcu.

## Pomiary (headless Chrome, stand-in `src/test/browser/server.py`, `?page=guide-pl` i `guide-en`)

| widok | wysokość menu | rezerwa `--s2p-guide-jump-h` | rzędy | nagłówek sekcji po skoku pod menu |
|---|---|---|---|---|
| 320 × 640 dotyk | 61 px | 80 px | 1 (przesuwany, ostatni przycisk 716 px za krawędzią) | 36 px, wszystkie 8 sekcji, PL i EN |
| 390 × 844 dotyk | 61 px | 80 px | 1 | 36 px |
| 768 × 1024 dotyk | 61 px | 80 px | 1 | 36–38 px |
| 768 × 1024 mysz | 95 px | 104 px | 2 (PL i EN) | 26–28 px |
| 1024 / 1280 mysz | 95 px | 104 px | 2 | 26–27 px |

Menu stoi na `top: 0` po każdym skoku, strona nigdy nie przewija się w bok (`scrollX` 0, `scrollWidth` = `clientWidth`), rząd menu
na telefonie przesuwa się sam do zaznaczonego przycisku, nie ruszając strony. Zaznaczenie przy przewijaniu krokami po 97 px w dół i w
górę zgadza się z „ostatnią sekcją, której góra minęła dół menu” — różnice tylko w celowym pasie 24 px (`guide-nav.js:28`) i na końcu
strony (ostatnia sekcja). Wejście z `#deck` i `#guidePicture4` — patrz P1.

## Treść a kod

| zdanie w instrukcji | kod / `messages*.properties` | zgodne |
|---|---|---|
| do 5 piosenek na gościa | `WishController.PER_GUEST = 5` | tak |
| życzenia na 5 wydarzeń naraz | `WishService.MAX_OPEN_EVENTS = 5` | tak |
| znikają tydzień po imprezie | `WishService.KEPT_DAYS_AFTER = 7` | tak |
| „Drukuj zaproszenia”, „Utwórz link dla gości”, „Do sprawdzenia”, „⭐ Koniecznie zagrać” | `wishes.print`, `wishes.new.create`, `wishes.check.title`, `host.wanted.label` | tak |
| „📋 Plan imprezy”, „Użyj list na imprezie”, „Drukuj plan”, „📋 z planu” | `eventplan.open`, `eventplan.use`, `eventplan.print` („🖨 Drukuj plan”), `eventplan.queue.mark` | tak |
| pytanie: 2–4 odpowiedzi, puste = „Tak”/„Nie”, także przy wstrzymanych prośbach | `polls.help`, `polls.new.answers.help` | tak |
| samo kończy się po 12 godzinach | `PollService.ASKED_FOR = Duration.ofHours(12)` | tak |
| „Zadaj teraz”, „Zapisz na później”, „Dopiero gdy je pokażę”, „👀 Pokaż wyniki”, „Na TV: Pytanie / Piosenki”, „Edytuj”, „Popraw jako nowe” | `polls.new.ask`, `polls.new.save`, `polls.new.results.later`, `polls.results.show`, `polls.tv.label` + `polls.tv.to_question` / `to_songs`, `polls.edit`, `polls.copy` | tak |
| strona gościa: „Twoja: Nie”, „Wyniki pokaże DJ.”, „↻ Sprawdź wyniki” | `guest.poll.yours`, `guest.poll.hidden`, `guest.poll.check` | tak |
| poranne podsumowanie o 10:00, od 5 próśb gości, top 3, Pro | `MorningSummaryEmailService`: cron `0 0 10,12` (12:00 to ponowienie), `MIN_REQUESTS = 5`, `PlanFeature.SUMMARY` | tak |
| cztery grupy i ich karty | `settings.html:56–697` (`settings.group.*`, kolejność kart) | tak |
| Pro przy życzeniach/planie, pytaniu, kiosku, porannym | `plan.pro('WISHES' / 'POLLS' / 'KIOSK' / 'SUMMARY')` w `settings.html` | tak |
| motywy: klasyczny, weselny, świąteczny, „Konfetti”, „Straszny” | `guest.theme.*` | tak |
| „Zarezerwuj tego DJ-a” | `party.book.title` („…na swoją imprezę”), `dashboard.booking.help` używa krótkiej formy | tak |
| „Ukryj” w bibliotece | `dashboard.library.only` = „Ukryj „Nie masz”” | **nie — P6** |

PL i EN mówią to samo (zdanie po zdaniu w nowych częściach); EN dodaje przy 10:00 „Polish time” — dobrze.

## Uwagi

### P1 — Wejście z linkiem `#deck` przewija płynnie całą stronę (~6000 px, ~1,5 s) — **błąd, poprawione**

`static/css/app.css:1432` (przed poprawką: `html:has(.s2p-guide) { scroll-behavior: smooth }`), waga: średnia (link z panelu
„Zagrane samo” → `/jak-to-dziala#deck` to główna droga do tej sekcji).

Scenariusz: DJ stuka w panelu „Zagrane samo” → strona ładuje się na górze i przez półtorej sekundy jedzie w dół przez wszystkie
sekcje i obrazki; zmierzone: `scrollY` 0 → 64 → 430 → 1988 → … → 5945. Przyczyna nie leży tylko w regule z PR: Bootstrap (reboot) sam
ustawia `:root { scroll-behavior: smooth }` przy `prefers-reduced-motion: no-preference`, więc reguła PR była zbędna, a Chrome robi
skok do `#…` dopiero **po** zdarzeniu `load` — więc nawet włączenie płynności „po załadowaniu” było za wcześnie (sprawdzone).

Poprawka: `.s2p-guide-page:not(.s2p-guide-smooth) { scroll-behavior: auto }` (`app.css:1435`, klasa `s2p-guide-page` na `<html>`
obu szablonów), a `guide-nav.js:16–23` dodaje `s2p-guide-smooth` przy pierwszym `wheel` / `pointerdown` / `keydown` czytelnika — skoki
z menu po dotknięciu są dalej płynne, wejście z linkiem jest od razu na miejscu. Bez JavaScriptu skoki są natychmiastowe. Scenariusze
`guide-nav-entry-phone` (`#deck`, 390 px) i `guide-nav-entry-fold` (`#guidePicture4`, 1280 px, EN): czerwone przed (43 i 22 pozycje
przewijania), zielone po. Harness dostał opcję `hash` (P8).

### P2 — „Wybrany przycisk wygrywa” nie działał przy płynnym skoku — **błąd, poprawione**

`static/js/guide-nav.js:11–21` (przed poprawką), waga: niska–średnia.

`chosen` miał trzymać zaznaczenie klikniętej sekcji, gdy ta nie może dojść do góry ekranu (koniec strony). Ale w pierwszej klatce
płynnego skoku sekcja jest jeszcze daleko w dole (`top > 0.6 × innerHeight`), więc `chosen` kasował się od razu — kod był martwy. Przy
`prefers-reduced-motion` (skok natychmiastowy) działał, ale wtedy trzymał zaznaczenie także przy ręcznym przewinięciu w górę o 40 %
ekranu. Skutek widać na wysokim ekranie (1280 × 1800): stuknięcie „Obsługa” kończy się na końcu strony i zaznaczone jest „Co widzą
goście”.

Poprawka: kliknięty przycisk jest zaznaczony, dopóki czytelnik sam nie przewinie (`wheel`, `pointerdown`, `keydown` kasują `chosen`;
stuknięcie w przycisk to `pointerdown`, a potem `click`, więc kolejność jest dobra). Przy okazji płynny skok nie „miga” już przez
wszystkie sekcje po drodze. Scenariusz `guide-nav-chosen-tall`: czerwony przed (`#guests` zamiast `#staff`), zielony po; scenariusze
`guide-nav-phone` / `-computer` przewijają teraz „ręką” (`byHand`: `wheel` + przewinięcie) i stukają (`tap`: `pointerdown` + `click`),
więc sprawdzają też płynną ścieżkę.

### P3 — Element z fokusem chowa się pod menu (WCAG 2.4.11) — **dostępność, poprawione**

`static/css/app.css:1416` (przed poprawką: `.s2p-guide [id] { scroll-margin-top: … }`), waga: średnia dla klawiatury.

Shift+Tab w górę strony: przeglądarka przewija element z fokusem do górnej krawędzi — pod przyklejone menu (zmierzone: `summary`
„Więcej o pytaniach” i krok „W skrócie” na `top: 0` pod menu do 61 / 95 px). `scroll-margin-top` działał tylko na elementach z `id`.
Poprawka: `scroll-padding-top` na samym przewijanym elemencie (`html.s2p-guide-page`) — obejmuje skoki, linki i fokus, i zastępuje
regułę na każdym `[id]` (o którą pytało zadanie). Zmienna `--s2p-guide-jump-h` żyje teraz na `html`. Scenariusze
`guide-nav-focus-phone` (390 px) i `guide-nav-focus-computer` (1024 px, EN): czerwone przed (`0 vs 61`), zielone po.

### P4 — Czytnik ekranu: lista kroków i strzałki — **poprawione**

`templates/guide_pl.html:54`, `guide.html:43`, `app.css:1465`, `app.css:1478`. Waga: niska.

Safari (VoiceOver) odbiera rolę listy `<ol>` z `list-style: none` — czytnik nie mówi „lista, 5 pozycji”: dodane `role="list"`
(asercja w `GuidePageRenderTest`). Znaki „›” (krok) i „▸” (przycisk „Więcej”) z `content` wchodzą do nazwy dostępnej („…, pojedynczy
cudzysłów ostrokątny w prawo”): dodany tekst zastępczy `content: "›" / ""` po zwykłym `content: "›"` (przeglądarka bez tej składni
bierze pierwszą deklarację). Numer kroku z licznika zostaje czytany („1 Zaloguj się…”) — to pomaga. `<details>` czytnik zapowiada sam
(„zwinięte / rozwinięte”), `aria-current="location"` na przycisku menu — dobrze; kolejność fokusu: powrót → menu → „W skrócie” →
treść; `:focus-visible` (cyjan) na przyciskach menu, krokach i „Więcej” — widoczne.

### P5 — Notka „W skrócie” odsyła do „Więcej”, którego prawie nie ma — **treść, poprawione**

`templates/guide_pl.html:66`, `guide.html:55`. Tylko jedno z pięciu zwinięć ma słowo „Więcej” („Więcej o pytaniach…”); pozostałe to
„Co jest w każdej grupie”, „Skróty klawiszowe…”, „Jak ustawić tablet…”, „Połączenie, okno programu…”. Teraz: „Rzadziej potrzebne są
zwinięte — rozwiniesz je przyciskiem ze strzałką ▸.” / „The rarer things are folded — open them with a button with the arrow ▸.”

### P6 — Nazwy przycisków: „Ukryj” i plan wieczoru — **treść, poprawione**

`templates/guide_pl.html:106` „przyciskiem „Ukryj”” → „„Ukryj »Nie masz«”” (`dashboard.library.only`); EN `guide.html:95` „"Hide"” →
„"Hide “Not in library”"”. `guide_pl.html:133` „widać plan wieczoru” → „widać „📋 Plan wieczoru”” (`eventplan.card.title`); EN
`guide.html:122` „That evening tonight’s plan stands” → „That evening “📋 Tonight’s plan” stands”.

### P7 — `guide-nav.js` nie było w spisie plików (Section 6) — **dokumentacja, poprawione**

`docs/context/06-3-config-templates-static.md:63`: nowy wiersz obok `guide-pictures.js` (AGENTS.md: inwentarz w tej samej zmianie).

### P8 — Harness: wejście na stronę z linkiem `#…` — **test, dodane**

`src/test/browser/harness.js:131–140`, `README.md:84`: opcja scenariusza `hash: '#deck'` — strona ładowana od razu z tym adresem,
więc sprawdzany jest skok samej przeglądarki przy ładowaniu (P1).

### P9 — Rząd menu na telefonie nie zdradza, że da się go przesunąć — **zrobione (decyzja właściciela)**

`app.css:1441`. Na 390 px widać 2½ przycisku, rząd urywa się 16 px przed krawędzią ekranu (padding karty), w połowie słowa („W trakcie
imprez”), bez paska przewijania. Ucięte słowo to jakaś wskazówka, ale słaba. **Rekomendacja:** rząd do krawędzi ekranu (ujemny margines
+ `scroll-padding-inline`) i zanikanie na prawej krawędzi (`mask-image` z gradientem), gdy jest co przewinąć — tak jak listy kiosku
(`kiosk-list-short-no-fade`). Bez strzałek.

Zrobione: rząd ma teraz szerokość paska od krawędzi do krawędzi ekranu, a jego pierwszy przycisk zaczyna się tam, gdzie tekst strony
(`padding-inline` i `scroll-padding-inline` rzędu zamiast paddingu paska, `app.css`, sekcja „the jumps”). `guide-nav.js` (`edges()`)
daje rzędowi klasy `s2p-guide-more-start` / `-end`, a maska (`mask-image`, gradient na `var(--s2p-black)`) wygasza 2,5 rem po tej
stronie, gdzie są kolejne przyciski. Bez skryptu rząd tylko sięga krawędzi ekranu. Scenariusz `guide-nav-row-fades` był czerwony
przed zmianą (rząd 16…374 z 390, bez klas), po zmianie jest zielony.

Na GitHubie (fonty DejaVu / Liberation, szersze od Inter) scenariusz był czerwony w obu PR-ach: po skoku do „Co widzą goście” rząd
dalej zanikał z obu stron. Ostatni przycisk mieścił się już w pudełku rzędu, ale pod zanikaniem, więc skrypt go nie przesuwał — był
nieczytelny. Poprawka: `guide-nav.js` przesuwa zaznaczony przycisk także wtedy, gdy leży w pasie 48 px przy którejkolwiek krawędzi
rzędu, i nie zapisuje `scrollLeft` bez zmiany. Scenariusz sprawdza teraz też, że zaznaczony przycisk nie leży pod zanikaniem.
Odtworzone lokalnie bez Inter (CLAUDE.md „Mind” 4): czerwony przed poprawką, zielony po; z Inter też zielony.

### P10 — „W skrócie” na telefonie zajmuje 1½ ekranu — **zrobione (decyzja właściciela)**

Na 390 × 844 karta ma ~650 px (pięć kroków po 2–4 linie opisu); pierwsza sekcja zaczyna się dopiero na drugim ekranie. **Rekomendacja:**
na telefonie (< 576 px) opisy kroków skrócone do jednej linii (`-webkit-line-clamp: 1`) albo ukryte — same tytuły dają ~5 × 52 px;
alternatywa: krótsze opisy w obu językach (np. krok 1 „Google albo e-mail; klimat i opis w „Impreza i kod QR”.”).

Zrobione: poniżej 576 px opis kroku ma jedną linię i kończy się „…” (`white-space: nowrap; text-overflow: ellipsis`). Cały tekst
zostaje na stronie dla czytnika ekranu, a pełny opis jest też w sekcji, do której prowadzi krok. Odstępy kroków są ciaśniejsze
(`padding-block: 0.4rem`), a notka pod krokami skrócona do jednej linii („Rzadsze szczegóły są niżej zwinięte pod ▸.” / „The rarer
details are folded below, under ▸.”). Wysokość karty przy 390 × 844: PL 710 → 420 px, EN 396 px; przy 320 px PL 513, EN 489 px (tytuły
łamią się na dwie linie). Scenariusz `guide-short-phone` sprawdza jedną linię opisu, kartę do 55 % ekranu i pełny tekst w DOM. Był
czerwony przed zmianą, po zmianie jest zielony. Do rozważenia: obcięty opis kroku 1 to „Kontem Google albo e-mailem. W…”. Gdyby miał
mówić o klimacie, trzeba by przestawić zdania.

### P11 — Na komputerze menu ma dwa rzędy (95 px przyklejone) — **zostaje (decyzja właściciela)**

Kolumna ma 800 px, osiem przycisków nie mieści się w jednym rzędzie w żadnym języku. Dwa rzędy zabierają ~12 % wysokości ekranu 800 px
przez całe czytanie. **Rekomendacja:** zostawić — rezerwa 104 px wystarcza w obu językach (zmierzone wyżej); jeśli ma być jeden rząd,
to krótsze nazwy (np. „Start”, „Przed”, „W trakcie”, „Zagrane samo”, „Telefon”, „Po”, „Obsługa”, „Goście”), nie mniejsza czcionka.

### P12 — `--s2p-guide-jump-h` to stała ręczna — **zrobione (właściciel: „jak uważasz”)**

`app.css:1416`, `app.css:1449`. Działa dziś (61 / 95 px menu przy 80 / 104 px rezerwy), ale trzeci rząd (powiększony sam tekst,
dziewiąty przycisk, dłuższa nazwa w EN) schowa nagłówek pod menu. **Propozycja:** `guide-nav.js` mierzy pasek (`ResizeObserver`) i
ustawia zmienną; CSS zostaje domyślny dla strony bez skryptu.

Zrobione: `ResizeObserver` w `guide-nav.js` ustawia `--s2p-guide-jump-h` na `html` jako wysokość paska + 12 px. Stałe w CSS zostają
dla strony bez skryptu. Scenariusz `guide-nav-taller-bar` powiększa przyciski paska do 147 px i skacze do `#deck` i `#staff`. Przed
zmianą był czerwony (nagłówek na 97 px pod paskiem sięgającym 147 px), po zmianie jest zielony.

### P13 — Ctrl+F w zwiniętym „Więcej” — **ocena, bez zmian**

Chrome i Edge (od 97) same otwierają `<details>`, gdy szukanie albo skok do `#…` trafia do środka — sprawdzone: bez JavaScriptu
`#guidePicture4` otwiera „Połączenie, okno programu i prywatność”. Firefox robi to od wersji z 2025 r.; Safari — nie mam pewności, że
już to robi, i starsze wersje obu na pewno nie: tam Ctrl+F nie znajdzie tekstu w zwiniętym (pasek wyszukiwania powie „0 wyników”). Link
`#…` do środka zwinięcia skrypt otwiera sam (`openTo`, także przy `hashchange`). **Rekomendacja:** nic więcej — w zwinięciach są tylko
rzadsze szczegóły, a ich nazwy mówią, co jest w środku.

### P14 — Strona bez JavaScriptu — **ocena, bez zmian**

Menu przykleja się (sam CSS), skoki działają i trafiają pod menu (`scroll-padding-top`), zwinięcia otwierają się stuknięciem, wejście
z `#…` jest od razu na miejscu. Brakuje tylko zaznaczenia sekcji i przesuwania rzędu do zaznaczonego — jak mówi komentarz w
`guide-nav.js:1–3`.

### P15 — `prefers-reduced-motion` — **ocena, bez zmian**

Przy `reduce` przewijanie jest natychmiastowe (Bootstrap nie włącza `smooth`, a nasza reguła tylko go wyłącza) i obrót „▸” bez
animacji (`app.css:1490`); przy `no-preference` płynne skoki po pierwszym dotknięciu (P1). Zmierzone w obu trybach.

### P16 — Wydajność skryptu — **ocena, bez zmian**

`guide-nav.js:59–64`: przewijanie przez `requestAnimationFrame` (najwyżej raz na klatkę), słuchacz `passive`. Na klatkę: ~10 odczytów
`getBoundingClientRect` (menu + 8 sekcji) i `scrollHeight` — same odczyty, przed jakimkolwiek zapisem, więc bez wymuszonych układów w
pętli; zapis `aria-current` tylko przy zmianie, przesunięcie rzędu tylko, gdy zaznaczony przycisk wyszedł z widoku. Koszt pomijalny.
`IntersectionObserver` nie byłby prostszy: reguła „ostatnia sekcja, której góra minęła linię pod menu” plus koniec strony wymagają
`rootMargin` liczonego z wysokości menu i i tak odczytu pozycji przy końcu strony. **Rekomendacja:** zostawić.

### P17 — Testy — **ocena, bez zmian**

Scenariusze sprawdzają zachowanie (pozycje względem menu, `aria-current`, brak przewijania w bok), z tolerancją 48 px, nie piksel w
piksel — dobrze. `design.js:146` porównuje `top` przycisków w jednym rzędzie dokładnie — w rzędzie `nowrap` to pewne. Trochę zależne od
zapisu HTML są dwie asercje `GuidePageRenderTest`: wyrażenie `<li><a href="#…"><strong>` i dokładne `<details class="s2p-guide-more"
id="…">` (kolejność atrybutów) — szablon jest nasz, więc złamanie będzie od razu widać; nie zmieniane. Łamanie linii w HTML nie ma
znaczenia dla żadnej asercji (szukane fragmenty są w jednej linii).

### P18 — CSS — **ocena, bez zmian**

Selektory ograniczone do instrukcji (`.s2p-guide*`, `html.s2p-guide-page`), kolory tylko z tokenów (`AppCssColoursTest` zielony),
promienie `--s2p-radius` / `--s2p-radius-card` i cele 44 px jak w panelu. Dawna osobna reguła `.s2p-guide-row ol` jest połączona z `ul`
w jedną (`app.css:1422`) — bez duplikatu. Dwie podobne reguły kreski zwinięcia (`.s2p-guide-more-body` i `.s2p-guide-row
.s2p-guide-more > :not(summary)`) to dwa rodzaje zwinięć (pod sekcją i w wierszu) — w porządku. Komentarze mówią „dlaczego”.

### P19 — Wygląd ogólnie — **ocena**

Zrzuty (dSF 2, PL i EN, telefon 390 i komputer 1280: góra, „W skrócie”, menu w trakcie czytania, rozwinięte „Więcej”): hierarchia
czytelna (h1 → lede → menu → karta kroków → sekcje), zaznaczony przycisk wyraźny (cyjanowa ramka na tle akcji 12 %), numery kroków w
cyjanie logo jak akcenty panelu, zwinięcia spokojne i odróżnione od „obrazków przycisków” strzałką. Kontrast tekstu opisów
(`--s2p-text-muted` na `--s2p-bg-elevated`) jak w panelu. Uwagi: P9–P11.

### P20 — Drobiazgi bez zmian

EN: dwa różne przykładowe wesela („Anna and Tom's” przy druku, „Anna and Jake's” przed imprezą) — PL ma „Osiemnastka Dawida” i
„Wesele Ani i Kuby”, więc to nie błąd. EN miesza cudzysłowy proste i typograficzne (sekcja „deck” sprzed tej zmiany jest typograficzna).
PL: jednoliterowe „W”, „i”, „z” na końcu linii (np. „W / „Impreza i kod QR””) — strona nie używa twardych spacji nigdzie; do
rozważenia razem dla całej strony, nie w tej zmianie.

## Testy po poprawkach

Po P1–P8: unit 1536/1536; browser 476/477 — jedyny czerwony `kiosk-full-fits-820x1180`, znany, czerwony też na `main` w kontenerze
chmurowym; audit `guide` 2 strony × 3 szerokości OK. Po P9, P10, P12: unit `GuidePageRenderTest`, `DashboardPageRenderTest`,
`AppCssColoursTest` 38/38; 13 scenariuszy przewodnika plus `one-accent` i `one-font` 15/15; audit `guide` OK.
