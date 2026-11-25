# Przegląd 2026-11-25 — „Nowości”: karta w panelu i mail z nowościami za zgodą, PR-y #311 / #312

Zakres: Adas553/scan2play#311 (do `main`) i Adas553/scan2play#312 (do `dev`), gałąź `claude/tender-bardeen-g98av8`, commity
`29e9434` (karta „Nowości”, zgoda „Nowości mailem”, mail, strona admina, V80), `73d03ec` (karta w Ustawieniach → Ty jako DJ) i
`e0498cf` (handoff). 48 plików, +1363 / −53, migracja V80 (tylko dodaje kolumny z domyślnymi wartościami i indeks częściowy).

## Wynik w skrócie

Zmiana jest dobrze pomyślana i w głównych miejscach poprawna: zgoda jest domyślnie wyłączona, zapisuje ją tylko jedno `UPDATE` z
czasem, zwykły zapis ustawień jej nie nadpisze (kolumny `insertable = false, updatable = false` i do tego zapis pod blokadą wiersza),
cache jest czyszczony po commicie, „raz na nowość” to jedno `UPDATE … WHERE news_emails AND news_sent < :n` (dwie wysyłki naraz →
jeden mail, wycofana zgoda → nic), link wypisu `GET` niczego nie zmienia, `POST` i `List-Unsubscribe` wyłączają tylko nowości.
Endpointy panelu są tylko właściciela, strony admina tylko konta `scan2play.admin-id`.

Znalazłem jeden prawdziwy błąd (P1: przy pełnej kolejce maili strona admina na zawsze mówi „Wysyłka trwa” i nie da się wysłać do
restartu), dwie nieścisłości tekstu, które DJ odczuje (P2 „jednym kliknięciem” — link najpierw pyta; P3 tekst po zgodzie na karcie
obiecuje maile „gdy dodamy coś nowego”, a pierwszy przyszedłby o tym, co DJ właśnie czyta), dwie luki w testach, których mutacje
przeżyły (P4, P5), i drobiazgi. P1–P10 poprawione (P3 tak, jak zdecydował właściciel), każda zmiana zachowania z testem widzianym
najpierw na czerwono. P11, P12 (a) zrobione tak, jak rekomendowałem (właściciel). P13–P18 to ocena bez zmian.

Testy przed poprawkami (ta gałąź, kontener): unit 1549/1549, IT 226/226, browser — patrz „Testy”.

## Mutacje (czy testy łapią błędy)

| # | mutacja | test | wynik przed poprawkami |
|---|---|---|---|
| M1 | `claimNews` bez `AND news_emails` (mail mimo wycofanej zgody) | `NewsEmailsIT.consentTakenBackBeforeTheSend_sendsNothing` | czerwony ✓ |
| M2 | `newsEmails` bez `updatable = false` | `NewsEmailsIT.theConsent_isKeptWithItsTime…` | przeżyła — słusznie, P14 |
| M3 | `POST /email/stop?what=news` nie wyłącza nowości | `EmailStopControllerTest.theNewsPost_…` | czerwony ✓ |
| M4 | `PanelNews.shows` bez daty konta | `DashboardPageRenderTest.anOrganiserOfBefore_…` | czerwony ✓ |
| M5 | „✕” karty niczego nie zapisuje (`dismissNews` no-op) | — | **przeżyła** → P4 |
| M6 | nieudana wysyłka nie oddaje claimu | `NewsEmailServiceTest.theSend_…` | czerwony ✓ |
| M7 | `AdminNewsController.requireOwner` wyłączone (każdy DJ widzi i wysyła) | — | **przeżyła** → P5 |
| M8 | `setNewsEmails` bez czyszczenia cache | `NewsEmailsIT.theConsent_…` („the cache follows”) | czerwony ✓ |

## Uwagi

### P1 — Pełna kolejka maili zostawia „Wysyłka trwa” do restartu — **błąd, poprawione**

`service/NewsEmailService.java:62` (przed poprawką), `service/Mailer.java:114`. `sendLater()` ustawia `sending = true`, a zdejmuje je
dopiero `finally` zadania na wątku maili. `Mailer.later` przy pełnej kolejce (100 zadań: powitania, poranne podsumowania, linki logowania
hasłem) łapie `RejectedExecutionException` i tylko loguje — zadanie nigdy się nie wykona, więc `sending` zostaje `true` na zawsze:
strona `/dj/stats/news` pokazuje „Wysyłka trwa…”, a każde „Wyślij” kończy się „Nie wysłane: … wysyłka już trwa”, aż do restartu.
Strona admina mówiła przy tym „Wysyłam” (bo `sendLater` zwracał `true`).

Poprawka: `Mailer.later` zwraca, czy przyjął zadanie (`boolean`; pozostali wołający ignorują wynik jak dotąd), a `sendLater` przy
odrzuceniu zdejmuje znacznik i zwraca `false`. Test `NewsEmailServiceTest.aSendTheFullQueueDropped_isNotOnItsWay_andTheNextClickStartsOne`
— czerwony na starym kodzie („nothing on its way: expecting false but was true”), zielony po poprawce.

### P2 — „Wyłączysz to jednym kliknięciem” — link najpierw pyta — **treść, poprawione**

`messages_pl.properties` `dashboard.news.emails.help` („…Wyłączysz to jednym kliknięciem.”) i stopka maila `email/news_pl.html:22`
(„Wyłączysz je tam albo jednym kliknięciem: Nie wysyłaj mi nowości”), EN „One click switches it off” / „or with one click”. Link
celowo **pyta** („Nie wysyłać Ci nowości?” → „Tak…”), bo filtry poczty otwierają linki — to dwa kliknięcia; polityka prywatności mówi
poprawnie „link pyta, czy wyłączyć”. Obietnica przy zgodzie, która się nie sprawdza, to zły początek (i przy zgodzie RODO lepiej, żeby
informacja o wycofaniu była dokładna).

Poprawka: karta „Wyłączysz to w każdej chwili w Ustawieniach imprezy albo linkiem w mailu.” / „Switch it off any time in Party settings
or by the link in the e-mail.”; stopka maila „Wyłączysz je tam albo tym linkiem:” / „Switch it off there or with this link:”. Test
renderu karty i `NewsEmailServiceTest` pilnują nowych słów (czerwone przed zmianą).

### P3 — Po „Wysyłaj mi nowości mailem” karta mówi „Napiszemy, gdy dodamy coś nowego”, a pierwszy mail byłby o tym samym — **zrobione (decyzja właściciela)**

`messages_pl.properties` `dashboard.news.emails.done`, `docs/news.md` (było: „Whoever says yes gets the news now by the next send too —
even when they said yes on its card”). DJ, który zgodził się na karcie, dostałby za tydzień mail o trzech funkcjach, o których właśnie
czytał, choć tekst zapowiadał co innego. Właściciel: „jeśli DJ jest zalogowany i dana funkcjonalność już jest, to nie trzeba jej
wysyłać”.

Zrobione: otwarcie panelu przez właściciela imprezy albo zgoda (dawana zawsze po zalogowaniu), gdy nowość już jest, ustawia
`news_sent` na jej numer (`PartySettingsRepository.markNewsKnown`, jedno `UPDATE … WHERE news_sent < :n` — raz na nowość, nie przy
każdym wczytaniu; cache czyszczony po commicie). Mail nowości idzie więc tylko do tych, którzy zgodzili się wcześniej i od tej
nowości się nie logowali. Panel obsługi niczego nie oznacza. Tekst karty („Napiszemy, gdy dodamy coś nowego”) jest teraz prawdziwy i
zostaje. Na stronie admina „dostali te nowości” → „znają je (mail albo panel)”. **Skutek: nowość nr 1 nie ma do kogo pójść** — każda
zgoda pada po jej wydaniu; mail ma sens od nowości nr 2 (`docs/news.md` opisuje to i nowe kroki). Testy czerwone bez tych dwóch
wywołań: `DjDashboardControllerOwnerContactTest.theOrganisersPanel_marksTheNewsKnown_once_theStaffsDoesNot`,
`NewsEmailsIT.theConsent_isKeptWithItsTime_andTheNewsNowIsNotEmailedToThem`; plus
`NewsEmailsIT.anOrganiserWhoOpensTheirPanel_whileTheNewsIsThere_isSentNothingOfIt` (nigdy nie obniża, cache, nic nie idzie).

### P4 — Zamknięcie karty na serwerze nie miało testu — **test, dodane**

`controller/DjDashboardController.java:468`. Mutacja M5 (nic nie zapisuje) przeżyła: scenariusz `news-card-close` sprawdza tylko, że
`POST` poszedł. Dodane `DjDashboardControllerOwnerContactTest.closingTheNews_keepsItsNumber_once` (zapisuje `NUMBER`, karta znika na
następnym panelu) i `closingTheNewsAgain_writesNothing` — czerwony na M5.

### P5 — Strona admina `/dj/stats/news` bez testu dostępu i bez renderu dla audytu — **test, dodane**

`controller/AdminNewsController.java:131`. Mutacja M7 (każdy zalogowany DJ widzi liczby i **wysyła maile do wszystkich, którzy się
zgodzili**) przeżyła — żaden test nie wołał tych adresów, a strona nie była renderowana dla `page_audit.py`. Dodane `AdminNewsWebTest`
(jak `AdminReportWebTest`): właściciel widzi liczby, słowa i formularze z CSRF; inny DJ — 404 na `GET`, odmowa na `POST` bez tokena i
**404 z ważnym tokenem sesji** (to łapie M7); strona idzie do `target/browser-harness/stats-news.html`. Czerwony na M7.

### P6 — „Wyślij do N” obiecywał więcej, niż jedna wysyłka daje — **poprawione**

`templates/stats-news.html:43`. Przycisk mówił „Wyślij do 350”, a `sendDue` bierze najwyżej `MAX_PER_SEND = 200`. Teraz „Wyślij do 200”
i pod nim „Reszta (150) następnego dnia: Brevo za darmo wysyła 300 maili dziennie.” (`Counts.nextSend()`); formularz znika, gdy
wysyłka trwa (wcześniej był obok „Wysyłka trwa…”). `AdminNewsWebTest`.

### P7 — Strona wypisu z nowości nie miała testu przez prawdziwe zabezpieczenia — **test, dodane**; „Tak, nie wysyłajcie” → „Tak, wyłącz” — **treść, poprawione**

`EmailStopControllerTest` woła kontroler wprost — nie widzi szablonu, CSRF ani sesji. Dodane
`EmailStopWebTest.theNewsLink_asksAboutTheNews_andItsOneClick_switchesOnlyTheNewsOff`: `GET ?what=news` pyta o nowości (nie o
podsumowanie), formularz bez `action` i bez `_csrf` (więc `POST` idzie pod ten sam adres z `what=news`), link do
`#newsEmailsCard`, bez sesji; `POST` z `List-Unsubscribe=One-Click` wyłącza tylko nowości. Strona idzie do audytu
(`email-stop-news.html`). Przy okazji przycisk PL: „Tak, nie wysyłajcie” (forma 2. os. l. mn., inna niż reszta) → „Tak, wyłącz”, jak
przy porannym podsumowaniu.

### P8 — Część tekstowa maila łamie zdania w połowie — **poprawione**

`templates/email/news_pl.html:11–17`, `news_en.html`. `Mailer.plainText` zachowuje końce linii źródła, a akapity szablonu były łamane
w kodzie — w kliencie pokazującym tekst wychodziło „…prośby gości⏎jako zagrane.” (sprawdzone na wyrenderowanym mailu). Akapity są teraz
w jednej linii (jak w `welcome_*.html`); test: tekst zawiera „prośby gości jako zagrane”.

### P9 — „Jak to / działa” łamane między liniami w mailu — **wygląd, poprawione**

Na 390 px link „Jak to działa” po akapicie „Plan imprezy” łamał się na dwie linie. Linki dostały `white-space:nowrap` (styl inline, jak
reszta maila).

### P10 — Komentarz z historią w `scenarios/folds.js` — **poprawione**

„1400 px until "Nowości mailem" (V80) added a line…” — historia w kodzie (AGENTS.md). Teraz mówi, dlaczego granica jest taka: każda
zwinięta karta to jedna linia ~50 px.

### P11 — Polityka prywatności: „nigdy w celach reklamowych innych firm” — **zrobione (właściciel: „jak rekomendujesz”)**

`privacy_pl.html:43`, `privacy.html:43` (pkt 2). Było „nigdy w celach reklamowych ani do newslettera”, jest „nigdy w celach
reklamowych innych firm”. Nowe zdanie czyta się, jakby adres mógł służyć **własnej** reklamie bez zgody — a tak nie jest (mail z
nowościami jest tylko za zgodą, pkt 3). **Rekomendacja:** „…a o nowościach w Scan2Play — tylko gdy to włączy (pkt 3); nigdy w innych
celach reklamowych”. Przy okazji „gdy sam to włączy” / „gdy sam włączy” (pkt 2, 3) — rodzaj męski; bezosobowo: „gdy właściciel to
włączy”.

Zrobione: PL pkt 2 „…tylko gdy właściciel to włączy (pkt 3); nigdy w innych celach reklamowych;”, pkt 3 „gdy właściciel włączy
„Nowości mailem””; EN pkt 2 „…only once the owner switches that on (section 3); never for any other advertising;”. Test
`PrivacyNewsConsentTest` (czerwony przed zmianą).

### P12 — Zgoda na karcie bez odnośnika do zasad; dowód zgody — **(a) zrobione, (b) zostaje (właściciel: „jak rekomendujesz”; to nie jest porada prawna)**

Mail z nowościami o własnym produkcie to w praktyce marketing bezpośredni: w Polsce od 10.11.2024 wymaga uprzedniej zgody abonenta na
mail (Prawo komunikacji elektronicznej, art. 398), a zgoda RODO (art. 6 ust. 1 lit. a, art. 7) ma być dobrowolna, konkretna, świadoma i
jednoznaczna, z informacją o wycofaniu i możliwa do wykazania. Ocena: zgoda jest uprzednia (domyślnie wyłączona), osobna (nie wiąże się
z kontem ani regulaminem), aktywna (przycisk / przełącznik), konkretna („tylko o nowych funkcjach Scan2Play, bez reklam”), wycofanie
proste i opisane; polityka (pkt 3) opisuje cel, podstawę i wycofanie; mail ma `List-Unsubscribe`. Do rozważenia:
(a) obok przycisku na karcie i przełącznika w Ustawieniach odnośnik „Zasady: Polityka prywatności, pkt 3” — wtedy informacja jest w
miejscu zgody, nie tylko w polityce; (b) `news_emails_at` trzyma tylko ostatnią zmianę i nie mówi, **gdzie** i na **jaki tekst** zgoda
padła — dla wykazania zgody (art. 7 ust. 1) wystarczyłaby linia logu, ale logi Railway nie są trzymane długo; tańsze: zostawić, bo
zgoda dotyczy tylko maili o nowościach, a wycofanie jest natychmiastowe. **Rekomendacja:** (a) tak, (b) zostawić. Warto, żeby
ostateczne słowa przejrzał prawnik.

Zrobione (a): pod przyciskiem zgody na karcie i w opisie karty „Nowości mailem” w Ustawieniach link „Zasady: Polityka prywatności,
pkt 3” → `/privacy#dataUse` (nagłówek pkt 3 dostał `id="dataUse"` w PL i EN; `/privacy` wybiera język sam). Testy renderu karty i
Ustawień (czerwone przed zmianą); scenariusze `news-*` i Ustawień zielone, audyt bez przewijania w bok.

### P13 — Wyścig: zgoda wycofana w trakcie wysyłki — **ocena, bez zmian**

`claimNews` wymaga `news_emails` w tym samym `UPDATE`, więc wycofanie przed claimem → nic nie idzie (`NewsEmailsIT`, M1). Wycofanie
między claimem a odpowiedzią Brevo (milisekundy) → ten jeden mail pójdzie; tego nie da się zamknąć bez trzymania blokady wiersza na
czas wywołania HTTP, co byłoby gorsze. Dwie wysyłki naraz: `AtomicBoolean` w jednej instancji i claim w bazie — jeden mail
(`NewsEmailsIT.twoSendsAtOnce…`). Padające Brevo: `FAILED` oddaje claim, `UNKNOWN` (brak odpowiedzi w czasie) zostaje jako wysłany —
jak przy powitaniu: dwa razy ten sam mail gorszy niż jeden zgubiony.

### P14 — Zapis ustawień a zgoda i cache — **ocena, bez zmian**

`updateSettings` czyta wiersz pod blokadą i zapisuje całość — kolumny zgody i tak są poza `UPDATE` (`updatable = false`), więc nawet
zapis z encji przeczytanej przed zmianą zgody jej nie cofnie. Mutacja M2 to potwierdza od drugiej strony: zdjęcie `updatable = false`
nie robi testu czerwonym, bo blokada i tak czyta świeży wiersz — ochrona jest podwójna. `setNewsEmails`, `claimNews`, `releaseNews`
czyszczą cache po commicie (M8). Cache może być nieaktualny tylko w adresie/języku między zmianą a commitem — nie wpływa na to, kto
dostaje mail.

### P15 — V80 — **ocena, bez zmian**

`integer NOT NULL DEFAULT 0` ↔ `int`, `boolean NOT NULL DEFAULT false` ↔ `boolean`, `timestamptz` ↔ `Instant` — zgodne z `validate`
(`MigrationIT` w IT 226/226). Dodanie kolumny z domyślną stałą w PostgreSQL 11+ nie przepisuje tabeli. Indeks
`(news_sent, party_code) WHERE news_emails`: pasuje do `newsCounts` i `newsDue` (filtr), choć `ORDER BY party_code` przy zakresie na
`news_sent` sortuje osobno — przy kilkudziesięciu zgodach bez znaczenia. Nie zmieniam: migracja mogła już przejść w lokalnej bazie
właściciela. `releaseNews` ustawia `news_sent = n − 1`, nie poprzednią wartość — bez skutków, bo wysyłana jest zawsze tylko bieżąca
nowość.

### P16 — Kiedy karta się pokazuje — **ocena, bez zmian**

Tylko właściciel (`owner &&`), nigdy obok karty powitalnej, nigdy w demo (atrybut `news` nie jest tam ustawiany), `created_at` null =
konto sprzed nowości (render test, M4). Uwaga: stare konto, które nigdy nie zamknęło karty powitalnej, nie zobaczy „Nowości”, dopóki
jej nie zamknie — akceptowalne (to konta, które panelu prawie nie używają). Ten sam adres na dwóch kontach (Google i hasło, bez
łączenia) dostanie dwa maile — każde konto dało własną zgodę i ma własny link wypisu; bez zmian.

### P17 — Treść a funkcje — **ocena**

Opis trzech funkcji na karcie i w mailu zgadza się z instrukcją (`guide_pl.html` `#deck`, `#guidePolls`, `#guidePlan`) i z kodem:
program czyta VirtualDJ i Serato, sam oznacza zagrane; pytanie — głosy z telefonów, wyniki na żywo, ekran TV; plan — momenty z
piosenkami, link gospodarzy bez konta, „Plan wieczoru” nad kolejką. Teksty są bezosobowe / w 2. os. bez rodzaju (Wysyłaj mi, Zadaj,
widzisz, Zmienisz zdanie?). Nie wspominają, że to funkcje Pro — dziś wszyscy mają Pro (`PLANS_ENFORCE=false`), a nowość nr 1 będzie
zamknięta na długo przed włączeniem planów; przy następnej nowości warto to dopisać.

### P18 — Wygląd — **ocena**

Karta „Nowości” ma klasę i odstępy karty powitalnej, przyciski obrysowe (jedna akcja widoku dalej „Drukuj”), na 320 px przycisk zgody
na całą szerokość i ≥ 44 px; karta w Ustawieniach jest zwinięta do stanu jak „Poranne podsumowanie”; strona wypisu to ta sama strona z
innymi słowami. Mail: jasne tło, jedna kolumna, przycisk „Otwórz panel” w kolorze maila powitalnego, bez przewijania w bok na 390 i
1280 px. Zrzuty: patrz „Testy”.

## Testy

Przed poprawkami (kontener, PostgreSQL 16): unit 1549/1549, IT 226/226, browser 484/485 (tylko `kiosk-full-fits-820x1180`, czerwony
też na `main`), audit `dashboard-news`, `settings*`, `email-stop*` — 15 stron × 3 szerokości OK (mały cel tylko „Szczegóły” 36 px,
istniejący przycisk panelu, nie karta).

Po poprawkach: unit **1558/1558**, IT **227/227**, browser **484/485** (ten sam `kiosk-full-fits-820x1180`), audit 17 stron × 3
szerokości bez przewijania w bok i bez błędów konsoli (nowe: `stats-news`, `email-stop-news`; mały cel jeszcze pole „Próba
sprawdzona” na stronie admina, 24 px — jego etykieta jest klikalna, strona tylko dla właściciela; bez zmian). Każdy nowy test widziany
najpierw na czerwono: na mutacjach M5, M7, na starym `sendLater`, na starych tekstach i szablonach maila, i bez wywołań `markNewsKnown`.

Zrzuty (`page_audit.py --shots`, 320 / 390 / 1280 px): karta „Nowości” nad kolejką ma wygląd karty powitalnej, przycisk zgody obrysowy
na całą szerokość na telefonie, opis pod nim; strona wypisu jak strona porannego podsumowania („Tak, wyłącz”); strona admina czytelna
na 390 px („Wyślij do 200”, „Reszta (50)”). Mail wyrenderowany do HTML i obejrzany w jasnym motywie (390 i 1280 px): jedna kolumna,
bez przewijania w bok, „Jak to działa” już nie łamie się na dwie linie.

P11, P12 (po decyzji właściciela): unit **1560/1560**; browser `news-*`, `settings-folded*`, `settings-staff-groups`,
`settings-page-touch-targets`, `dashboard-touch-targets`, `phone-width-360` 12/12; audit `dashboard-news`, `settings*`, `privacy`,
`stats-news` bez przewijania w bok i błędów.
