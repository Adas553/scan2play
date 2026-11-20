# Przegląd 2026-11-21 — „Trendy próśb” (wariant 0) i „Plan imprezy” (warianty A i B, V77)

Zakres: to, co jest w `dev` (`9840a8d`), a czego nie ma w `main`: `git diff 692f731 origin/dev`, bez wpisów handoffu i commitów
scalających. 70 plików, +2821 / −61:

1. `4d3d53c` — „Trendy” w raporcie właściciela: `SongTrendsService`, `AdminReportService`, `stats-report.html`,
   `email/admin-report_pl.html` (bez migracji);
2. `15793e6` — „Plan imprezy”, wariant A: `EventPlanService`, `DjEventPlanController`, `EventPlanForm`, `HostController` `/h/e/{token}`,
   `SecurityConfig`, V77, szablony `event-plan*.html`, `host-plan.html`, `fragments/event-plan.html`, `js/plan-form.js`, teksty,
   strony prawne, testy;
3. `e3b70b6` — wariant B: karta „Plan wieczoru” nad kolejką (`js/dashboard/plan-card.js`, `dashboard.html`) i „📋 z planu” w kolejce
   (`DjDashboardController`, ETag).

Kod nie był zmieniany. Wszystko poniżej sprawdzone w osobnym worktree `origin/dev` (`9840a8d`), sprawdzenia w kopii (`tools/check.py`).

## Uruchomione sprawdzenia (sesja w chmurze)

| Krok | Wynik |
|---|---|
| `unit` | 1483 testy, 0 błędów — OK (w tym `ImpersonalPolishTextsTest`, `EventPlanPageRenderTest`, `EventPlanHostWebTest`, `EventPlanServiceTest`, `SongTrendsServiceTest`, `StaffPermissionEndpointsTest`, `PlanFeatureEndpointsTest`, `DjDashboardControllerQueueCacheTest`) |
| `it` | 206 testów, 0 błędów — OK (`EventPlanIT`, `SongTrendsIT`, `MigrationIT` z V77). PostgreSQL 16 kontenera, hasło `postgres` ustawione na `1111` — pierwszy przebieg padł w całości na haśle, nie na kodzie |
| `browser` | 463 scenariusze — OK (w tym `event-plan-hosts-rows`, `event-plan-dj-rows-320`, `event-plan-print-button`, `plan-card-next-and-ticks`) |
| `audit` (wszystkie strony) | 148 stron × 320 / 390 / 1280 px — **FAILED: `event-plan-print` przy 320 px przewija się w bok o 19 px** (tabela, P9). Cele dotyku < 44 px: tylko 4 znane sprzed tej gałęzi („Szczegóły”, godziny harmonogramu) |
| zrzuty (`page_audit.py --shots`) `event-plan*`, `host-plan*`, `dashboard-plan*`, `guide*`, `pro*`, `stats-report` | obejrzane; z nich P9, P10 |
| wydruk na A4 (Playwright `page.pdf`, A4) | przykładowy plan: 1 strona. Realny plan wesela (15 momentów z krótką uwagą, listy po 25 pozycji): **2 strony**, ostatni moment sam na drugiej. Plan na granicy limitów (30 momentów, długie uwagi, listy po 100): 5 stron |

## Uwagi (od najpoważniejszych)

### P1 — Kartę „Plan wieczoru” widzi każdy z obsługi, także bez dostępu do wydarzeń — **błąd (prywatność)**

`DjDashboardController.java:222-225` (`plans` zależy tylko od planu Pro, nie od uprawnienia), `dashboard.html:261`.

Karta nad kolejką i jej „Cały wieczór” pokazują nazwy momentów, utwory i **uwagi** (te, w których według polityki prywatności „mogą
być imiona, np. rodziców pary”). Dostaje ją każdy, kto otworzy panel imprezy: barman z samym `QUEUE` też. Tymczasem
`/dj/wishes/{id}/plan` wymaga `WISHES`, a polityka (`privacy_pl.html:36`, `privacy.html:36`) mówi: „widzą je tylko DJ, jego obsługa z
dostępem do wydarzeń i każdy, kto ma link gospodarzy”. Kod i polityka się rozjeżdżają. Do tego link „Cały plan →” prowadzi
takiego barmana na 403.

**Scenariusz:** właściciel dodaje do obsługi barmana z prawem do kolejki. Wieczorem barman widzi na swoim telefonie
„Podziękowania dla rodziców — imiona: Ewa i Marek, Jola i Piotr” i notatki pary.

**Poprawka:** `plans = planService.allows(settings, WISHES) && access.may(StaffPermission.WISHES)` dla karty (`PLAN_TONIGHT`). „📋 z
planu” w kolejce można zostawić dla wszystkich (to tylko znak przy piosence, bez uwag), ale wtedy w polityce dopisać „znak przy
prośbie widzi cała obsługa”. Jeśli właściciel woli, żeby drugi DJ bez `WISHES` widział kartę, to poprawić politykę, nie kod — ale
jedno z dwojga. Test: `DashboardPageRenderTest` (karta bez `WISHES` — brak `#planCard`).

### P2 — Zapis nadpisuje cały plan bez sprawdzenia, czy ktoś zmienił go w międzyczasie — **błąd (utrata danych)**

`EventPlanService.java:102-133` (`save`, `saveByHosts`, `write`: `DELETE` wszystkich momentów i `INSERT` tego, co przyszło z
formularza), `HostController.java:112`, `DjEventPlanController.save`.

Blokada wiersza chroni przed mieszanką dwóch zapisów, ale nie przed nadpisaniem. Formularz niesie cały plan taki, jaki był przy
otwarciu strony. „Ostatni zapis wygrywa” jest w porządku dla dwóch krótkich list (`/h/`), ale plan wypełnia się długo, w kilku
miejscach naraz i przez kilka dni.

**Scenariusze:** (a) DJ otwiera plan o 18:00, para o 18:05 zapisuje 12 momentów z telefonu, DJ o 18:10 poprawia jedną godzinę i
klika „Zapisz plan” — 12 momentów pary znika bez śladu, a strona pisze „✓ Plan zapisany”. (b) Panna młoda i pan młody wypełniają
plan na dwóch telefonach — zostaje plan tego, kto kliknął później. (c) Link trafił do grupy rodzinnej i ktoś zapisał pusty
formularz — plan i obie listy wyczyszczone, bez cofnięcia.

**Poprawka:** ukryte pole `planVersion` z `plan_saved_at` (albo licznik w `wish_event`). `write` pod blokadą porównuje je z
wierszem. Gdy się różnią, nic nie zapisuje i wraca na stronę z tym, co wpisano (flash), oraz żółtym komunikatem „Plan zmienili
w międzyczasie gospodarze (19:03) — sprawdź i zapisz jeszcze raz”. Najtańsza wersja: odrzucić i pokazać aktualny plan. Do tego
warto trzymać jedną poprzednią wersję (kolumna `plan_previous jsonb`) i dać DJ-owi „Przywróć poprzedni zapis”. Test w `EventPlanIT`:
zapis ze starą wersją niczego nie zmienia.

### P3 — Listy gospodarzy z planu wieczorem nie działają, dopóki DJ nie kliknie „Użyj list na imprezie”, i nic o tym nie przypomina — **ryzyko (produkt)**

`DjEventPlanController.java:134` (`useLists`), `event-plan.html` (`#planUseCard`), `dashboard.html:261` (karta niczego o listach
nie mówi), tekst `eventplan.lists.help`.

Gospodarze wpisują „🚫 Nie grać: Disco polo” na swoim linku i są przekonani, że to zadziała. AI i kolejka używają jednak tylko
list imprezy. Jedynym mostem jest przycisk schowany na dole strony planu, w karcie „Na imprezie”.

**Scenariusz:** sobota, 21:00 — DJ ma kartę „Plan wieczoru” nad kolejką, ale nie był na stronie planu. Gość prosi o utwór z „Nie
grać” pary. AI go przepuszcza, a w kolejce nie ma „🚫”. Para widzi, że DJ „zignorował” ich listę.

**Poprawka:** gdy listy wydarzenia na dziś (`tonight`) różnią się od list imprezy, na karcie „Plan wieczoru” (dla kogoś z
`HOST_LISTS`) pokazać jedną linię: „Listy z planu nie są jeszcze włączone — Użyj” (ten sam POST z potwierdzeniem). Można też przy
pierwszym otwarciu panelu w dniu wydarzenia. Gospodarzom napisać to jaśniej: „DJ włączy je w dniu imprezy”.

### P4 — Link `/h/e/{token}` bez CSRF jest w porządku, ale nie ma limitu zapisów ani cofnięcia — **ryzyko (nadużycie)**

`SecurityConfig.java:233-234, 256`, `HostController.java:112-140`, `EventPlanService.java:112-133`.

**Wyłączenie CSRF jest uzasadnione.** Do zapisu potrzebny jest sekret w adresie (128 bitów), a kto go zna, wyśle POST i bez
przeglądarki ofiary. Atak CSRF nie daje więc nic ponad to, co daje sam link. Matcher obejmuje tylko `/h/e/*` i
`/h/e/*/template`. `/h/{token}` (listy imprezy) dalej ma token. `byHostToken` sprawdza długość, a stary token po „Nowy link” nic
nie zapisuje (`saveByHosts` szuka wydarzenia po tokenie pod blokadą). Strona gospodarzy ma `noindex` i `no-referrer`. Bez Pro
link daje 404. Rozmiar danych jest ograniczony: 30 momentów × (60 + 150 + 300) znaków i 2 × 100 linii po 150, czyli < 50 KB na
zapis, a Tomcat ogranicza liczbę parametrów.

**Czego brakuje:** limitu. Wyciekły link (forward w rodzinnej grupie, zrzut ekranu) pozwala nadpisywać plan w pętli. Każdy zapis
to `DELETE` + do 30 `INSERT` + `UPDATE` + linia `INFO` w logu, bez końca. Nadpisanie jest ciche (P2). DJ zobaczy tylko „Ostatni
zapis: gospodarze”.

**Poprawka:** limit zapisów na token (np. 20 na 10 minut, wzorem `GuestLimitStore` / `GuestRequestLimiter`), ponad limit 429 ze
stroną „Spróbuj za chwilę”. Do tego poprzednia wersja z P2. Na stronie DJ-a przy „Link dla gospodarzy” dopisać „Wyślij go tylko
parze — kto ma link, może zmienić plan”.

### P5 — Odhaczenia w karcie trzymane po numerze momentu; po zmianie planu wskazują inne momenty — **błąd**

`plan-card.js:53` (`'s2p-plan-done-' + event`, zbiór indeksów), `dashboard.html` (`data-index=${stat.index}`).

**Scenariusz:** o 20:30 odhaczone są „Wejście pary” (0) i „Pierwszy taniec” (1). DJ wstawia na stronie planu „Konkurs” jako drugi
moment, przy pozycji 1. Po przeładowaniu panelu odhaczony jest „Konkurs”, a „Pierwszy taniec” (teraz 2) wraca jako „następny —
teraz”. To samo dzieje się po usunięciu momentu: wszystkie odhaczenia za nim przesuwają się o jeden.

**Poprawka:** klucz odhaczenia z treści momentu, np. `label + '|' + time` (albo krótki hash). Ewentualnie dodać `plan_saved_at` do
klucza localStorage i przy zmianie planu odhaczenia przenosić po nazwie, a nie po pozycji. Scenariusz: odhacz 0 i 1, podmień DOM na
plan z wstawionym momentem, sprawdź, który jest „następny”. Drobiazg przy okazji: klucze `s2p-plan-done-*` nigdy nie są usuwane
(jeden na wydarzenie, mało, ale bez końca).

### P6 — Odliczanie liczone w strefie urządzenia, a „wieczór” w strefie Warszawy — **ryzyko**

`plan-card.js:36-43` (`new Date(year, month - 1, day, hours, minutes)` — czas lokalny urządzenia), `EventPlanService.tonight`
(`eveningOf` w `Times.DISPLAY_ZONE`).

**Scenariusz:** laptop DJ-a ma ustawione UTC (Linux, świeży system) albo DJ gra wesele za granicą z telefonem w innej strefie. „Tort
23:00” pokazuje „za 1 h 05 min”, kiedy zostało 5 minut. Przypadek rzadki, ale karta zrobiona jest właśnie po to, żeby nie
przegapić momentu.

**Poprawka:** serwer liczy dla każdego momentu chwilę w `DISPLAY_ZONE` (z tą samą zasadą „przed 6:00 — dzień później”) i daje ją w
`data-at` (epoch ms). JS odejmuje tylko `Date.now()`. Reguła 6:00 jest wtedy w jednym miejscu (Java), a `momentAt` znika z JS.

### P7 — Nowe zapytanie przy każdym odpytaniu kolejki (co 3 s, także przy 304), bez cache; karta się nie odświeża — **do poprawy (wydajność)**

`DjDashboardController.java:640` (`tonightSongs` przed sprawdzeniem ETag), `EventPlanService.java:182-189`.

Zapytanie samo w sobie jest tanie: `idx_wish_event_party_date`, PK `event_plan_item (event_id, position)`, `LIMIT 30`. Wykonuje się
jednak przy **każdym** odpytaniu **każdego** otwartego panelu. Dopóki `PLANS_ENFORCE=false`, Pro ma każdy, więc dotyczy to
wszystkich imprez, także tych, które nigdy nie miały wydarzenia. To dodatkowe zapytanie na najgorętszej ścieżce aplikacji, obok
`getQueueFingerprint`, które ma swój cache. AGENTS.md: „Leverage caching … on hot paths”.

Do tego: karta „Plan wieczoru” powstaje tylko przy renderze strony, a znak „📋 z planu” idzie z odpytywaniem. Gdy gospodarze
poprawią plan o 19:00, znaki w kolejce się zmienią, a karta pokaże stary plan do przeładowania. Panel otwarty w piątek i
zostawiony do soboty (PWA) nie pokaże karty wcale.

**Poprawka:** wynik `tonightSongs` w cache Caffeine na (party, wieczór), 30–60 s, unieważniany w `write`. Wtedy na 20 odpytań
przypada jedno zapytanie. Kartę albo przeładowywać, gdy zmieni się hash z ETag (`-p…` już jest), albo napisać na niej „zmieniony —
odśwież”.

### P8 — Dwa wydarzenia z planem tego samego dnia: karta pokazuje pierwsze utworzone, bez wyboru — **do poprawy**

`EventPlanService.java:168-189` (`ORDER BY id LIMIT 1`, `min(f.id)`).

**Scenariusz:** DJ gra w sobotę chrzciny o 14:00 i wesele o 19:00 (albo ma testowe wydarzenie z tą samą datą). Wieczorem karta
pokazuje plan chrzcin, a „📋 z planu” oznacza ich utwory. Wesela nie da się wybrać.

**Poprawka:** gdy dziś jest więcej wydarzeń z momentami, wybierać to, którego najbliższy moment jest najbliżej `now` (albo
ostatnie zaczęte). Na karcie dać link „Inny plan dziś (1)” z parametrem `?plan=<id>`. Test `EventPlanIT` z dwoma wydarzeniami z
momentami tego samego dnia — obecny test ma tylko jedno z momentami.

### P9 — Wydruk: przewija się w bok przy 320 px, a „jedna A4” to raczej dwie strony — **do poprawy (audyt czerwony)**

`app.css:1888-1891` (`.s2p-plan-print-time { width: 5rem; white-space: nowrap; font-size: 1.35rem }`, tabela bez `table-layout`),
`event-plan-print.html`.

`page_audit` jest czerwony: `event-plan-print@320` — `table#planPrintMoments` o 19 px szerszy od ekranu. Kolumna „Utwór” łamie się
wyraz po wyrazie. DJ, który otwiera wydruk z telefonu, żeby go obejrzeć albo wysłać do drukarki, przewija stronę w bok. Na A4 (PDF z
Chrome): plan przykładowy mieści się na jednej stronie. Realny plan wesela (15 momentów, krótkie uwagi, listy po 25 pozycji)
zajmuje 2 strony, a ostatni moment zostaje sam na drugiej, nad listami. Komentarz w szablonie i opis w `05-1i-wishes.md` mówią
„one A4”. Emoji 🚫 ⭐ drukują się w kolorze („no colour the printer must have”).

**Poprawka:** pod 400 px `.s2p-plan-print-time { width: auto; font-size: 1.1rem }` i mniejszy padding. Do tego
`overflow-wrap: anywhere` w komórce utworu. W `@media print`: `@page { size: A4; margin: 12mm }`, listy mniejszą czcionką w 2–3
kolumnach (`columns: 2`), `break-before: avoid` dla ostatniego wiersza tabeli. W opisie zamiast „one A4” pisać „zwykle jedna, dwie
strony”.

### P10 — Wygląd formularza planu: białe pola na stronie DJ-a, moment na trzy linijki na telefonie — **do poprawy (wygląd)**

`fragments/event-plan.html:44-66` (`form-control form-control-sm` bez `bg-black text-light border-secondary`), `:27` (select
szablonu ciemny), `app.css` (`@media (max-width: 399.98px)` dla `.s2p-plan-row-top`).

1. Na stronie DJ-a (`event-plan.html`) pola są białe, a cała reszta paneli DJ-a ma ciemne pola: `settings.html:219` (listy
   gospodarzy), `wishes.html:58` — `bg-black text-light border-secondary`. Na stronie gospodarzy jest odwrotnie: pola białe (jak
   `host.html`), ale select szablonu czarny. Fragment jest wspólny, więc klasy pól trzeba wybrać zależnie od strony (np. atrybut
   `planDark`), albo dać stronie DJ-a jedną regułę `.page-dj .s2p-plan-row .form-control`.
2. Na 320 **i 390** px (iPhone, `max-width: 399.98px`) godzina stoi sama w pierwszej linijce, nazwa w drugiej, ↑ ↓ ✕ w trzeciej, a
   pod nimi utwór i uwagi. Zrzuty `host-plan-390`, `event-plan-390`: jeden moment ma ~200 px wysokości. Komentarz w CSS chce
   „godzina i nazwa w jednej linii, przyciski pod nimi”, ale `flex-basis: calc(100% - 6.85rem)` plus `gap` daje dokładnie 100% i
   zaokrąglenie spycha nazwę do nowej linii. **Poprawka:** siatka (`grid-template-columns: 6.5rem 1fr` i przyciski w osobnym
   wierszu po prawej) albo `calc(100% - 6.85rem - 1px)`. Scenariusz `event-plan-dj-rows-320` niech sprawdza, że `top` godziny i
   nazwy jest równy.
3. Na stronie DJ-a z działającym JS trzy puste wiersze zapasowe niepotrzebnie wydłużają stronę (`event-plan-390` ma 3400 px).
   `plan-form.js` może zostawić jeden pusty wiersz, a resztę usunąć. „+ Dodaj moment” i tak jest.

Poza tym strony pasują do reszty aplikacji. Karta w panelu (lewa krawędź w kolorze akcji, „21:00 · teraz” w kolorze akcji) wygląda
dobrze i nie rozpycha kolejki. Tabela trendów w raporcie na 390 px też jest w porządku.

### P11 — Gdzie jest plan: trzy kroki w głąb „Życzeń”, dwa różne „Linki dla gospodarzy” — **do poprawy (UX)**

`settings.html:367-371` (wiersz „Życzenia przed imprezą”, stan „Nieaktywne”/„Otwarte: N”), `wishes.html:117` (przycisk planu
dopiero w otwartym wydarzeniu), `wishes.help` (o planie ani słowa), `dashboard.host.link.label` / `eventplan.host.title`.

Droga DJ-a, który pierwszy raz szuka planu: Ustawienia → „Życzenia przed imprezą” (nazwa nic nie mówi o planie, stan „Nieaktywne”
też nie) → lista wydarzeń → otwarcie wydarzenia → „📋 Plan imprezy”. Żeby zrobić plan, trzeba najpierw założyć „wydarzenie
życzeń” — z linkiem dla gości, którego DJ może wcale nie chcieć. Przewodnik (`guide_pl.html`) mówi o tym dobrze, ale kto go nie
czyta, ten planu nie znajdzie. W Ustawieniach jest już „Lista gospodarzy” z własnym „Linkiem dla gospodarzy” (`/h/`), a plan ma
drugi „Link dla gospodarzy” (`/h/e/`). DJ łatwo wyśle parze nie ten link. Strony mówią „listy gospodarzy”, a karta nazywa się „Lista
gospodarzy”.

**„Życzenia” to już nie jest dobra nazwa** miejsca, w którym są wydarzenia, ich życzenia i plan. **Poprawka:** wiersz w Ustawieniach
nazwać „Wydarzenia: życzenia i plan” (albo „Przed imprezą”), a stan pokazywać jako „2 wydarzenia · 1 plan”. Na liście wydarzeń przy
każdym dać „📋 6 momentów” lub „📋 Zrób plan”, żeby plan był widoczny bez otwierania wydarzenia. W `wishes.help` dodać zdanie o
planie. W karcie „Lista gospodarzy” jedna linia: „Na wesele z planem wyślij parze link z planu wydarzenia — to inny link”. W karcie
planu: „To link tylko do planu tego wydarzenia”.

### P12 — Strona gospodarzy: pola bez etykiet, nie wiadomo, czym utwór momentu różni się od „Koniecznie zagrać”, zmiany giną po zamknięciu — **do poprawy (UX)**

`fragments/event-plan.html:44-66` (etykiety tylko jako `aria-label` i placeholder), `host-plan.html`, `plan-form.js`.

Przejście jak para młoda na telefonie (`host-plan-empty-320`, `host-plan-390`): nagłówek, intro i szablon są jasne. Potem jednak
pole godziny w pustym wierszu pokazuje tylko „--:-- --” i nie widać, że to godzina. Gdy w polu jest tekst, placeholder znika i
nie wiadomo, które pole jest „uwagami”, a które „utworem”. Nigdzie nie pada, po co jest utwór przy momencie, a po co lista
„Koniecznie zagrać”: para wpisze pierwszy taniec w obu albo tylko na listę. Nie ma pytania przy wyjściu: ktoś wypełnia 10 momentów,
dostaje telefon, zamyka kartę — wszystko stracone. ✕ usuwa wypełniony moment bez cofnięcia.

**Poprawka:** nad pierwszym wierszem mała legenda „Godzina · Moment / Utwór na ten moment / Uwagi dla DJ-a” albo `small` etykiety
nad polami w każdym wierszu (tylko na stronie gospodarzy). W pomocy list jedno zdanie: „Utwór na konkretny moment wpiszcie przy
momencie. Tu — co ma zagrać w ogóle, kiedykolwiek”. W `plan-form.js` `beforeunload`, gdy formularz zmieniono od wczytania (wzór:
inne formularze ustawień). ✕ na wypełnionym wierszu z potwierdzeniem albo „Cofnij” przez kilka sekund.

### P13 — Teksty PL: do DJ-a w liczbie mnogiej, „solenizanta”, kilka niejasnych zdań — **do poprawy (teksty)**

`messages_pl.properties:1352, 1375, 1387, 1382, 1393, 1400`.

- `eventplan.help` („wypełniacie je razem z gospodarzami”) na stronie DJ-a mówi do DJ-a „wy”. Reszta panelu mówi „Ty” („możesz”,
  „Ty widzisz”). `eventplan.template.help` („uzupełnicie sami”) i `eventplan.done.template` („uzupełnijcie”) są we wspólnym
  fragmencie, więc DJ też dostaje „wy”. Poprawka: osobne klucze dla strony DJ-a i gospodarzy albo forma bezosobowa („godziny i
  utwory zostają do uzupełnienia”). `ImpersonalPolishTextsTest` tego nie łapie (nie ma tu rodzaju), ale to niespójne.
- `eventplan.template.eighteen` / `birthday`: „Wejście solenizanta” — na osiemnastce dziewczyny to błąd. Lepiej „Wejście
  solenizanta / solenizantki” albo bez rodzaju: „Wielkie wejście”.
- `eventplan.host.help`: „Link działa, dopóki wydarzenie jest zapisane” — „zapisane” brzmi jak „plan zapisany”. Lepiej: „Link działa
  do 7 dni po imprezie (albo do „Wyłącz link”)”.
- `eventplan.use.help`: „(Ustawienia imprezy → listy gospodarzy)” — karta nazywa się „Lista gospodarzy”.
- Nagłówek `# "Plan imprezy" (V77)` jest dwa razy w obu plikach (drugi powinien brzmieć „karta w panelu, wariant B”).

Poza tym teksty brzmią naturalnie i bez rodzaju wobec DJ-a i gościa („Ostatni zapis: DJ, …”, „Gospodarze nie otworzą już planu”).

### P14 — Trendy: „imprez” to w rzeczywistości DJ-e, dni 29–30 nie należą do żadnej połówki, imprezy testowe są liczone — **do poprawy**

`SongTrendsService.java:85-99`, `AdminReportService.renderTrends`, `admin-report_pl.html:102`, `docs/ideas.md` punkt 16.

1. Impreza to `party_settings`, jedna na DJ-a (`owner_id` UNIQUE, `04-1a-entities-party-requests.md:12`). Liczenie „po
   `party_code`” liczy więc **DJ-ów**, nie wieczory. Progi „3 imprezy na piosenkę” i „10 imprez w 30 dniach” znaczą naprawdę
   „3 DJ-ów” i „10 DJ-ów”. Dla anonimowości to nawet lepiej (DJ z pięcioma weselami to jeden głos), ale nagłówek „imprez 4”
   wprowadza właściciela w błąd, gdy ma ocenić, czy danych wystarczy. **Poprawka:** w raporcie „DJ-ów (imprez)” albo liczyć wieczory
   (`party_code` + `eveningOf(requested_at)`) i opisać próg wprost. Decyzja właściciela.
2. Okres ma 30 dni, a połówki 2 × 14 = 28. Prośby sprzed 29–30 dni liczą się do sumy, ale do żadnej połówki: piosenka grana tylko
   wtedy ma „=” przy 0 / 0, a piosenka z 29. dnia i z ostatniego tygodnia dostaje „nowość”. **Poprawka:** `DAYS = 28` albo połówki po
   15 dni. Test graniczny w `SongTrendsServiceTest`.
3. `docs/ideas.md` punkt 16 mówi „not … test parties'”, a kod wyklucza tylko `DemoParty.CODE` / `CODE_EN`. Impreza testowa
   właściciela (i jego próby na żywo) wchodzi do trendów i może sama przepchnąć piosenkę przez próg 3. **Poprawka:** lista
   wykluczonych kodów w konfiguracji (`scan2play.trends.exclude`) albo poprawić opis w `ideas.md`.
4. Mail pokazuje samą strzałkę. Liczby „14 dni a / b przed” są tylko w wersji tekstowej i w `title` strony (na telefonie bez
   najechania ich nie widać). Strzałkę „↓” bez liczb trudno ocenić. Dopisać „5→2” małym drukiem.

**Prywatność trendów jest w porządku:** tylko nazwy piosenek i liczby, piosenka dopiero z 3+ imprez (DJ-ów), żadnego kodu imprezy
ani gościa. Nazwa wyświetlana to wersja zapisana przez najwięcej imprez. Przy remisie (każda impreza zapisała inaczej) może to być
pisownia jednej imprezy, ale tylko piosenki, którą i tak zamówiły trzy. Wysyłka przez Brevo do właściciela niczego nowego nie
ujawnia. Zapytanie jest ograniczone (`LIMIT 50 001`, `idx_song_requests_requested_at`) i wykonuje się raz przy raporcie.

### P15 — Polityka prywatności: zgodna co do planu, z dwoma lukami — **do poprawy (dokumenty)**

`privacy_pl.html:36, 106`, `privacy.html:36, 107`.

Opis planu (momenty, utwory, uwagi z imionami, listy, kto widzi, „nie trafiają do gości, na TV ani do AI”) i czasu przechowywania
(„wydarzenie, jego linki, … i plan imprezy: 7 dni po dacie”) zgadza się z kodem: `ON DELETE CASCADE` z `wish_event`,
`WishService.purge`. Listy gospodarzy w `SongEvaluationService` są porównywane lokalnie, do promptu AI nie trafiają. Dwie luki:
(1) kto widzi — patrz P1; (2) po „Użyj list na imprezie” listy wydarzenia stają się listami imprezy i **zostają** po usunięciu
wydarzenia, aż ktoś je zmieni. Wystarczy pół zdania przy planie: „listy, które DJ włączy na imprezie, zostają listami imprezy (jak
lista gospodarzy)”. Odhaczenia z karty są tylko w localStorage urządzenia DJ-a (numery momentów, bez treści) — w polityce nie są
potrzebne.

### P16 — Drobiazgi w kodzie — **drobiazg**

- `EventPlanService.java:274` `tidy`: `substring(0, max)` może przeciąć parę surogatów (emoji na 60. / 300. znaku) i zapisać
  połówkę znaku. Ciąć po punktach kodowych (`offsetByCodePoints`).
- `EventPlanService.moments`: wiersz z samą godziną (bez nazwy, utworu i uwag) znika bez słowa. Lepiej nadać mu „Moment” jak
  wierszowi z samym utworem.
- `DjEventPlanController.template`: pełna nazwa `java.util.List` w środku metody — wystarczy import.
- Identyfikator `#planCard` oznacza na `event-plan.html` sekcję formularza, a na `dashboard.html` kartę wieczoru, której szuka
  `plan-card.js`. Dziś to nieszkodliwe (osobne strony), ale mylące przy kolejnym dołączeniu skryptu. Proponuję `#planFormCard`.
- `tonight()` robi 3 zapytania (wydarzenie, momenty, wiersz `wish_event` w `plan()`), a `tonightSongs` czwarte. Przy renderze
  panelu to akceptowalne. Po P7 `tonightSongs` z cache.
- Komentarze mówią „dlaczego” (CSRF, `getParameterValues` zamiast `List<String>`, `th:attr` zamiast `th:action`). Kilka niesie
  historię („the owner, 2026-11-20” jest tylko w `ideas.md`, w kodzie nie ma) — w porządku.

### P17 — Brakujące testy — **drobiazg**

- Karta bez uprawnienia `WISHES` (po P1).
- Link gospodarzy, gdy impreza straciła Pro: 404 na GET, POST i `template` (`EventPlanHostWebTest` tego nie sprawdza; jest tylko
  „nieznany link”).
- DJ imprezy A, który podaje id wydarzenia imprezy B ze swoim `partyCode` w `save`, `template`, `use-lists`, `print` i `GET`: 404 i
  nic nie zapisane. Kod jest dobry (`eventOf` filtruje po `party_code`, `newHostLink` / `removeHostLink` też), ale test jest tylko
  w serwisie (`newHostLink("ZZZZZ", …)`).
- Dwa wydarzenia z momentami tego samego dnia (P8), odhaczenia po zmianie planu (P5), zapis ze starą wersją (P2), dni 29–30 w
  trendach (P14).
- `page_audit` dla `event-plan-print` jest czerwony — scenariusz `event-plan-print-button` powinien sprawdzać też `fits()` na 320 px.

## Co jest dobrze

- Uprawnienia: każdy endpoint DJ-a wymaga `WISHES`. „Użyj list” wymaga też `HOST_LISTS` i obu funkcji Pro
  (`usingTheEventsLists_needsTheHostsListsToo`). Bez Pro strona jest tylko do odczytu, a „Wyłącz link” działa nadal — słusznie.
  Cudze wydarzenie: wszędzie `eventOf(partyCode, id)` / `WHERE party_code = ? AND id = ?`.
- XSS: tekst gospodarzy wszędzie przez `th:text` / `th:value` / `th:attr` (escapowane), w JS tylko `textContent`. `planDone` i
  `savedBy` pochodzą z serwera, a klucz szablonu jest sprawdzany z listą.
- Zapis pod blokadą wiersza wydarzenia. Stary token po „Nowy link” nic nie zapisuje. `startFrom` nie robi dwóch planów z dwóch
  kliknięć (`EventPlanIT`).
- V77 tylko dodaje kolumny i tabelę z PK `(event_id, position)` i `ON DELETE CASCADE`. Limity w kolumnach są zgodne z
  `maxlength` i `tidy`. Zapytania ograniczone (`LIMIT 30`, `LIMIT 50 001`), bez N+1.
- Strona gospodarzy nie tworzy sesji przy GET (`HostPageSessionWebTest`). Formularz działa bez JS. Kolejność wierszy jest
  kolejnością zapisu (scenariusze).
- ETag kolejki niesie hash piosenek planu (`DjDashboardControllerQueueCacheTest`).
- Karta w panelu: zwięzła, z dobrym priorytetem (następny moment i odliczanie), ✓ ma 44 px, ok. 200 px nad kolejką na telefonie.

## Ocena

- **A. Bezpieczeństwo i prywatność — dobrze, z jedną poprawką przed wydaniem.** Wyłączenie CSRF dla `/h/e/*` jest uzasadnione i
  niczego nie otwiera poza tym, co daje sam link. Uprawnienia i Pro są sprawdzane wszędzie, XSS brak, trendy anonimowe. Do
  poprawy: karta planu widoczna dla całej obsługi wbrew polityce (P1), brak limitu i cofnięcia przy wycieku linku (P4, P2), pół
  zdania w polityce (P15).
- **B. Jakość kodu — dobra.** Prosty SQL, blokady i ograniczone zapytania, sensowne testy (IT na blokadę, web test bez sesji,
  scenariusze wierszy). Najważniejsze: ciche nadpisywanie planu (P2), odhaczenia po indeksie (P5), zapytanie bez cache w odpytywaniu
  co 3 s (P7), strefa zegara (P6), dwa wydarzenia jednego dnia (P8). Testy pilnują tego, co opisują. Brakuje przypadków z P17.
- **C. Wygląd — w większości spójny; audyt czerwony przez wydruk (P9).** Do poprawy: białe pola na stronie DJ-a, moment na trzy
  linijki na 390 px (P10). Wydruk zwykle zajmuje dwie strony A4, nie jedną.
- **D. Łatwość — gospodarzom dość łatwo, DJ-owi trudno znaleźć.** Strona gospodarzy z szablonem jest zrozumiała, ale brakuje etykiet
  pól i ochrony przed utratą wpisanego (P12). DJ nie znajdzie planu bez przewodnika: nazwa „Życzenia”, trzy kroki, dwa „Linki dla
  gospodarzy” (P11). Najgroźniejsze dla wrażenia pary: listy z planu nie działają, dopóki DJ nie kliknie przycisku na dole strony
  (P3). Teksty naturalne, z drobnymi niespójnościami (P13).

## Poprawki (2026-11-21, gałąź `claude/affectionate-clarke-pb1e2w`, od `dev` = `9840a8d`)

Bez migracji. Decyzje produktowe wybrane tak, jak proponował przegląd.

- **P1:** karta „Plan wieczoru” i „📋 z planu” tylko dla kogoś z `WISHES` (`DjDashboardController`, w renderze i w odpytywaniu).
  Test: barman z samym `QUEUE` nie ma karty ani znaku, drugi DJ z `WISHES` ma (`DashboardPageRenderTest`).
- **P2:** zapis niesie wersję planu (`version`: moment ostatniego zapisu co do mikrosekundy). `EventPlanService.save` /
  `saveByHosts` pod blokadą porównują ją z wierszem: plan zapisany w międzyczasie nie jest nadpisywany (`Saved.CHANGED`). Strona
  mówi, kto i kiedy zapisał, i pokazuje to, co wpisano (flash `planDraft`, rekordy `Serializable` — sesja jest w bazie). Ponowne
  „Zapisz plan” świadomie zastępuje tamten zapis; `data-plan-draft` oznacza formularz jako niezapisany. Testy: `EventPlanIT`
  (`aSaveFromAnOlderVersion_…`), `EventPlanHostWebTest` (`aSaveOverAPlanChangedMeanwhile_…`, przez ciasteczko sesji).
- **P3:** na karcie, gdy listy wydarzenia różnią się od list imprezy, dla kogoś z `HOST_LISTS` (i Pro tej funkcji): „Listy „Nie
  grać” / „Koniecznie zagrać” z planu nie są jeszcze listami imprezy. — Użyj ich” (ten sam POST z potwierdzeniem, `back=panel`
  wraca do panelu). Tekst dla gospodarzy: „działają, gdy DJ włączy je na imprezie”.
- **P4:** najwyżej 30 zapisów z jednego linku gospodarzy w 10 minut (`HOST_SAVES_PER_WINDOW`, w pamięci, jak `LoginLimiter`).
  Ponad limit nic nie jest zapisane, a wpisana treść wraca. Na stronie DJ-a: „Kto ma link, może zmienić plan: wyślij go tylko
  gospodarzom”.
- **P5:** odhaczenia po nazwie i godzinie momentu (`data-key`); klik usuwa z pamięci klucze momentów, których już nie ma w planie.
- **P6:** odliczanie do `data-at` — momentu policzonego na serwerze w czasie polskim (`Tonight.at`, reguła 6:00 tylko w Javie);
  `momentAt` zniknął z JS.
- **P7:** `tonight` (a z nim `tonightSongs`) trzymany 30 s na imprezę i tylko dla chwili, w której go wybrano; zapis go
  zapomina (`UPDATE … RETURNING party_code`). Odpytywanie co 3 s to jedno zapytanie na pół minuty.
- **P8:** dwa wydarzenia z planem jednego dnia: wybierane to, które zaczęło się ostatnie (pierwsza godzina najwyżej godzinę od
  teraz), inaczej to, które zacznie się pierwsze (`EventPlanService.choose`, test jednostkowy i `EventPlanIT`).
- **P9:** wydruk na telefonie bez przewijania w bok (węższa kolumna godziny pod 400 px, scenariusz `event-plan-print-fits-320`);
  na papierze `@page` A4 z marginesem 12 mm, listy mniejsze w dwóch kolumnach.
- **P10:** pola planu ciemne na stronie DJ-a (jak inne formularze DJ-a), jasne u gospodarzy (select szablonu też). Godzina i nazwa
  w jednej linii na 320 / 390 px (siatka; scenariusz sprawdza wspólny `top`). Ze skryptem jeden pusty wiersz zapasowy.
- **P11:** wiersz w Ustawieniach „Życzenia i plan imprezy”; na liście wydarzeń „📋 plan (6)” (`momentCounts`, jedno zapytanie);
  `wishes.help` mówi o planie; karta linku gospodarzy mówi, że to inny link niż „Lista gospodarzy”; przewodnik PL/EN.
- **P12:** na obu stronach legenda pól momentu; w pomocy list zdanie o różnicy między utworem momentu a listą; pytanie przed
  wyjściem ze strony z niezapisaną zmianą (`beforeunload`, `data-plan-dirty`).
- **P13:** teksty bez „wy” wobec DJ-a (`eventplan.help`, `template.help`, `done.template`), „Wielkie wejście” zamiast „Wejście
  solenizanta”, „Działa do 7 dni po imprezie”, „Lista gospodarzy”, nagłówek drugiej sekcji w plikach tekstów.
- **P14:** trendy z 28 dni (dwie połówki po 14), „DJ-ów” zamiast „imprez”, liczby „2→5” przy strzałce w mailu i na stronie,
  `scan2play.trends.exclude-parties` (`TRENDS_EXCLUDE_PARTIES`, Section 10) obok wersji demo. Test: `SongTrendsIT` z wykluczoną
  imprezą i prośbą sprzed 29 dni.
- **P15:** polityka PL / EN: listy włączone na imprezie zostają listami imprezy.
- **P16:** `tidy` tnie po punktach kodowych (nie połowa emoji), wiersz z samą godziną dostaje nazwę „Moment”, import `List`,
  `#planFormCard` na stronie planu.
- **P17:** testy z listy powyżej oraz `HostControllerTest` (link wydarzenia bez Pro: 404 na GET, POST, szablonie) i
  `EventPlanPageRenderTest` (cudze wydarzenie: 404 w `save`, `use-lists`, `template`, `print`, nic nie zapisane).

**Zostawione:** „Przywróć poprzedni zapis” (P2 — odrzucenie zapisu ze starej wersji chroni już przed cichą utratą); link „Inny
plan dziś” na karcie (P8 — wybór według godzin wystarcza na typowy dzień); odświeżanie samej karty po zmianie planu w trakcie
wieczoru (P7 — znaki w kolejce odświeżają się same, kartę odświeża przeładowanie); potwierdzenie ✕ na wypełnionym wierszu (P12 —
przed utratą chroni pytanie przy wyjściu); kolorowe emoji 🚫 ⭐ na wydruku (P9); liczenie trendów po wieczorach zamiast po DJ-ach
(P14.1 — do decyzji właściciela, gdy przyjdzie wariant A).

### Sprawdzenia po poprawkach

| Krok | Wynik |
|---|---|
| `unit` | 1492 testy, 0 błędów — OK |
| `it` | 210 testów, 0 błędów — OK. Pierwszy przebieg złapał błąd samej poprawki: przeciążenie `save` bez wersji wołało wersję `@Transactional` od środka (z pominięciem proxy), więc blokada wiersza nie obejmowała zapisu, a `twoSavesAtOnce_…` dostał `DuplicateKeyException`. Przeciążenie jest teraz samo `@Transactional` |
| `browser` | 464 scenariusze — OK (po zmianie oczekiwanej nazwy wiersza w `settings-folded`; nowy `event-plan-print-fits-320`) |
| `audit` (wszystkie strony) | 148 stron × 320 / 390 / 1280 px, bez przewijania w bok i bez błędów — OK; po ostatnich zmianach CSS jeszcze raz `event-plan*`, `host-plan*`, `dashboard-plan*`, `guide*`, `settings*` — OK |
| zrzuty | `host-plan-390`: godzina i nazwa w jednej linii, legenda pól; `event-plan-390`: ciemne pola; `dashboard-plan-390`: linia „Użyj ich” na karcie. Pole godziny poszerzone do 7,5 rem, żeby „06:15 PM” (przeglądarka z zegarem 12-godzinnym) mieściło się w całości |
