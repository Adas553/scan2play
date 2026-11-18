# Przegląd 2026-11-18 — gałąź `claude/blissful-ritchie-36scit` (poprawki P1–P14, V73, „Nie wylogowuj mnie na tym urządzeniu”)

Zakres: Adas553/scan2play#282 (do `main`) i Adas553/scan2play#283 (do `dev`, ta sama gałąź) — 8 commitów (+ merge) od `81a9802`
(baza; `main` ma już #280 jako `9eeac7a`), 42 pliki, +1677 / −225:

1. przegląd logowania hasłem (`review-2026-11-18-password-login.md`) i poprawki P1–P14 (`206294f`, `4cbc5df`, `278770b`, `484478d`)
   z migracją V73;
2. „Nie wylogowuj mnie na tym urządzeniu” (`08ab42b`, `962e721`): `StayLoggedIn`, `SecurityConfig.loginSuccess`,
   `PasswordLoginController` (`POST /login/google`, `/login/password`), `login.html`, `app.css`, teksty PL/EN, polityka prywatności,
   `docs/context` (Sekcje 6, 8, 10-1, 12).

Gałąź nie była zmieniana; wszystko poniżej sprawdzone w osobnym worktree.

## Uruchomione sprawdzenia (sesja w chmurze, worktree `70e5cf7`, `tools/check.py`)

| Krok | Wynik |
|---|---|
| `unit` | 1366 testów, 0 błędów — OK (w tym `ImpersonalPolishTextsTest`) |
| `it` | 182 testy, 0 błędów — OK (w tym `StayLoggedInIT`, `DjAccountRepositoryIT`; PostgreSQL 16 kontenera, hasło `postgres` ustawione na `1111`) |
| `browser` | 437 scenariuszy (z nowym `login-stay-logged-in`) — OK |
| `audit -t login` | `login` × 320 / 390 / 1280 px: bez przewijania w bok, bez błędów konsoli, bez celów dotyku < 44 px — OK |
| mutacja 1: `StayLoggedIn.atLogin` bez `removeAttribute(CHOSEN_FOR_GOOGLE)` i z wyborem Google ważnym przy logowaniu hasłem | **czerwone** 2 testy: `google_withTheBox_isLong_afterTheNewSessionId` („read once”), `password_afterAGooglePressWithTheBox_goesByItsOwnBox` |
| mutacja 2: `PasswordLoginController.logIn` bez `request.changeSessionId()` (session fixation) | **czerwone** 5 testów (`PasswordLoginWebTest` ×2, `PasswordLoginSessionWebTest` ×3) |
| mutacja 3: `atLogin` bez powrotu do zwykłego czasu (`setMaxInactiveInterval(usual)`) | **czerwony** `aLongSession_loggedInAgainWithoutTheBox_isUsualAgain` |
| V73 na PostgreSQL 16 (200 000 wierszy, `varchar(100)` → `varchar(255)`) | 1,8 ms, `pg_relation_filenode` przed = po (bez przepisania tabeli) |
| `JdbcSession.setAttribute(name, null)` dla nieistniejącego atrybutu (bytecode spring-session-jdbc) | `return` od razu — `removeAttribute(CHOSEN_FOR_GOOGLE)` bez atrybutu nic nie zapisuje |
| `SessionAutoConfiguration` Boot 4.1.2 (bytecode) | z wbudowanym Tomcatem serializer ciasteczka z `server.servlet.session.cookie.*` (`ServerProperties`); bez niego (`@SpringBootTest` MOCK = „war deployment”) z `SessionCookieConfig` kontekstu serwletu (`MockServletContext`: wszystko wyłączone) — opis w `StayLoggedInIT` się zgadza |
| `InetAddress.getByName("zz:1")` (JDK kontenera) | `UnknownHostException: Name or service not known` po ~10 ms — zapytanie DNS (do P8) |

## Czy poprawki P1–P14 zamykają punkty poprzedniego przeglądu

| Punkt | Ocena |
|---|---|
| P1 enumeracja | **zamknięty.** `LoginOutcome.NOT_CONFIRMED` usunięte; dobre hasło konta niepotwierdzonego = `WRONG` + licznik + link w tle (`PasswordAccountService.java:291-297`); `LOCKED` zależy tylko od liczników, nie od istnienia konta. Test `aStrangersRegistrationAndLogin_sayNothingAboutTheAddress` |
| P2 blokada cudzego konta | **zamknięty dla haseł** (10 na parę adres/klient, 50 na adres — blokada wymaga ≥ 5 klientów co 15 min, reset nadal wpuszcza). Dla maili wyjątek „pierwszy mail klienta zawsze idzie” zdejmuje sufit adresu — nowy problem, **P2 niżej** |
| P3 nadpisanie hasła | **zamknięty.** Konto czekające zachowuje hasło, ponowna rejestracja wysyła `account-pending` (link resetu, potwierdza adres); bez maila nic się nie zmienia (`:205-214`). Testy `:251`, `:280`, `:292` |
| P4 bcrypt bez limitu | **zamknięty.** `mayRegister` (10/h/klient) przed pracą, bcrypt na osobnym wątku (1 wątek, kolejka 50) |
| P5 wylogowanie przy resecie | **zamknięty.** `AccountSessions.endAllBut(…, bieżąca)` — IT `theAccountsOtherSessionsEnd_theRequestsOwnStays` na prawdziwym JDBC |
| P6 atomowość linku | **zamknięty.** `useResetToken` / `useVerifyToken` (jeden `UPDATE … WHERE id AND hash`) — IT `aLinkUsedTwiceAtOnce_goesOnce` |
| P7 wyścig rejestracji | **zamknięty.** `DataIntegrityViolationException` w tle zalogowany, formularz i tak „Sprawdź skrzynkę” |
| P8 czas odpowiedzi | **zamknięty.** „Załóż konto” / „Nie pamiętam hasła” odpowiadają przed pracą zależną od konta; logowanie — zawsze jedno porównanie bcrypt (także > 72 bajty) |
| P9 usunięcie konta | **zamknięty.** `AfterCommit.run(() -> accountSessions.endAll(ownerId))` (`AccountDeletionService.java:118`); IT `aLongSession_endsWithTheAccountsSessions` także dla długiej sesji |
| P10 Google vs hasło | **opisany** w Sekcji 8 (świadoma luka) — zgodnie z propozycją |
| P11 IPv6 | **zamknięty** (`LoginLimiter.clientKey`, /64), z drobną usterką parsowania — **P8 niżej** |
| P12 CSRF / strategia | **zamknięty** (`SecurityConfig.CSRF_TOKENS`, `SecurityContextHolderStrategy`) |
| P13 długość hasza | **zamknięty** (V73 + `length = 255`) |
| P14 jakość | **zamknięty** (NFC, komentarze bez historii, `staff.html`, testy `LoginLimiterTest`); NFC ma jeden skutek uboczny — **P9 niżej** |

## Uwagi (od najpoważniejszych)

### P1 — Długa sesja bez „wyloguj na innych urządzeniach” (konto Google) i bez górnej granicy — **ryzyko**
- `StayLoggedIn.java:32`, `AccountSessions.java:33`; jedyne wywołania `endAll*`: reset hasła i usunięcie konta.
- Po zmianie sesja DJ-a może żyć 14 dni bez użycia, a używana codziennie — bez końca (Spring Session nie ma czasu bezwzględnego;
  ciasteczko przeglądarka trzyma do ~400 dni). DJ z kontem **Google**, który zgubi telefon albo zostawi zalogowany komputer w klubie
  z zaznaczonym polem, nie ma jak zakończyć tamtej sesji — „Wyloguj” kończy tylko bieżącą, zmiana hasła Google nic tu nie robi,
  zostaje usunięcie konta. Konto z hasłem ma reset (kończy inne sesje).
- Poprawka: w Ustawieniach (karta konta) przycisk „Wyloguj na innych urządzeniach” — `POST` z CSRF →
  `accountSessions.endAllBut(principal, session.getId())` (kod już jest, IT też). Opcjonalnie granica bezwzględna: `atLogin` zapisuje
  w sesji czas logowania, a `DjSessionHelper` (albo filtr na `/dj/**`) kończy sesję starszą niż np. 90 dni. Test: dwie sesje konta
  Google, przycisk w jednej → druga znika z `SPRING_SESSION`.

### P2 — Wyjątek „pierwszy mail klienta zawsze idzie” znosi sufit maili adresu — **ryzyko**
- `LoginLimiter.java:139-141` (`firstOfThePair` omija `EMAILS_PER_ADDRESS_HOUR`).
- Scenariusz: klient = IPv4 albo **/64** IPv6; serwer z /56 ma 256 takich „klientów” (/48 — 65 536). Każdy wysyła „Nie pamiętam hasła?”
  (albo „Załóż konto”) na adres ofiary: pierwszy mail pary zawsze przechodzi, więc ofiara dostaje setki maili na godzinę (do 3 na
  klienta/h). Skutki: zalew skrzynki ofiary i — groźniejsze — zużycie dziennego limitu Brevo i reputacji nadawcy, czyli przestają
  dochodzić wszystkie maile aplikacji (potwierdzenia, raporty dnia). Przed poprawką P2 sufit 5/h na adres był twardy.
- Poprawka: zostawić wyjątek (chroni reset właściciela), ale dać mu **twardy sufit** — np. 20 maili na adres na dobę bez wyjątków —
  i dodać globalny sufit maili o kontach (np. 200/h dla całej aplikacji) chroniący limit Brevo; klucz maili dla IPv6 można też liczyć
  po /48. Test w `LoginLimiterTest`: 30 różnych klientów → do adresu idzie najwyżej sufit dzienny.

### P3 — Atrybuty ciasteczka sesji tylko z domyślnych serializera; testy MOCK ich nie widzą — **drobiazg**
- `application.properties` nie ustawia `server.servlet.session.cookie.*`. Produkcja (wbudowany Tomcat) bierze domyślne
  `DefaultCookieSerializer`: `HttpOnly`, `SameSite=Lax`, a `Secure` **z `request.isSecure()`** — czyli z `X-Forwarded-Proto` przez
  `server.forward-headers-strategy=FRAMEWORK`. Gdyby nagłówek kiedyś zniknął (zmiana reguły Cloudflare / Railway), długie
  ciasteczko — teraz ważne do ~400 dni — straciłoby `Secure` bez żadnego sygnału. `@SpringBootTest` (MOCK) bierze ustawienia z
  `MockServletContext` (wszystko wyłączone), więc ustawienie właściwości **nie** zmieni tego, co widzą testy MOCK — `StayLoggedInIT`
  słusznie tego nie sprawdza, a `PasswordLoginSessionWebTest` sprawdza serializer złożony w teście, nie ten z aplikacji.
- Poprawka (warto, bo tanie): `server.servlet.session.cookie.secure=true`, `.http-only=true`, `.same-site=lax` w
  `application.properties` (lokalnie `secure=false` w `application-local.properties`, jeśli DJ testuje w Safari po `http://`; Chrome
  przyjmuje `Secure` na `localhost`) i jeden test `@SpringBootTest(webEnvironment = RANDOM_PORT)` z `TestRestTemplate` na `/login`,
  który sprawdza `Set-Cookie` z prawdziwego Tomcata. Albo przynajmniej punkt w liście kontrolnej wydania: `curl -I` strony z
  formularzem bez ciasteczek → `Secure; HttpOnly; SameSite=Lax`.

### P4 — Polityka prywatności nie podaje, jak długo żyje samo ciasteczko — **drobiazg (RODO / ePrivacy)**
- `privacy_pl.html:89`, `privacy.html:89`; komentarz `StayLoggedIn.java:21-23`.
- Tekst mówi prawdę o sesji (14 dni bez aktywności, koniec przy wylogowaniu) i o tym, że ciasteczko przeżywa zamknięcie przeglądarki,
  ale nie podaje okresu: `Max-Age=2147483647` (`Expires` ≈ 2094), przeglądarki przycinają do 400 dni. Obowiązek informacyjny o
  ciasteczkach obejmuje czas ich przechowywania. Sesja kończy się też po nowym haśle i po usunięciu konta (warto dopisać, bo to
  działa i jest korzystne). Uzasadnienie `Integer.MAX_VALUE` w `StayLoggedIn` jest trafne (ciasteczko pisane tylko przy zmianie id;
  stała Spring Session, nie do zmiany bez własnego serializera).
- Poprawka (PL): „…jego ciasteczko (sam identyfikator sesji) zostaje w przeglądarce także po jej zamknięciu — przeglądarki trzymają
  je najwyżej około 400 dni — ale po końcu sesji (14 dni bez aktywności, wylogowanie, nowe hasło, usunięcie konta) niczego już nie
  otwiera”; EN analogicznie. W `StayLoggedIn` jedno zdanie o 400 dniach przeglądarki.

### P5 — Wybór Google zostaje w sesji po przerwanym logowaniu — **drobiazg**
- `StayLoggedIn.java:64-71` (`keepForGoogle`), `SecurityConfig.loginFailure()`.
- Pole zaznaczone → „Zaloguj się przez Google” → rezygnacja u Google (wstecz, zamknięta karta, błąd → `/?error=auth_failed`):
  `CHOSEN_FOR_GOOGLE` zostaje w anonimowej sesji do 30 min. Przycisk na `/login` zawsze go nadpisuje, logowanie hasłem go czyści
  (test), żaden link aplikacji nie prowadzi już wprost na `/oauth2/authorization/google` (grep) — zostaje tylko wejście wprost na
  ten adres (historia / podpowiedź paska adresu) na wspólnym komputerze: następna osoba dostałaby długą sesję bez zaznaczania.
- Poprawka: usuwać atrybut także w `loginFailure()` i zapisywać go z czasem (`Instant`), ważny np. 10 min; test „wybór sprzed 11
  min → zwykła sesja”.

### P6 — `remember-me` czytany przy każdym logowaniu hasłem, także po potwierdzeniu konta i resecie — **drobiazg**
- `StayLoggedIn.java:90-91` (`byPassword` → `fromTheForm` czyta parametr żądania), `PasswordLoginController.java:50-52` (Javadoc:
  potwierdzenie i nowe hasło logują „na zwykły czas”).
- Obietnica Javadoca trzyma się tylko dlatego, że formularze `account-confirm.html` / `password-reset.html` nie mają pola; własnoręcznie
  dopisany `remember-me=true` do `POST /password/reset` daje długą sesję. To nie jest dziura — dotyczy tylko własnej sesji, a obcą
  stronę zatrzymuje CSRF; spreparowany link z maila (`?token=…&remember-me=true`) nie przechodzi, bo `th:action` nie niesie
  zapytania; parametr na powrocie z Google jest ignorowany (test `google_aParameterOnTheReturn_isNoChoice`).
- Poprawka: jawnie — `loginSuccess` dostaje informację, czy to formularz logowania (np. `atLogin(…, boolean boxOnThePage)` albo
  `"/login/password".equals(request.getServletPath())`), i test: `POST /password/reset` z `remember-me=true` → 30 min.

### P7 — Enter w polu: sprawdzana kolejność przycisków, nie samo Enter; iOS Safari ręcznie — **drobiazg**
- `login.html:31` (ukryty pierwszy przycisk), scenariusz `login.js` (`first !== google`).
- Rozwiązanie jest poprawne: domyślny przycisk formularza to pierwszy przycisk submit w drzewie; `visually-hidden` (clip, nie
  `display:none`) nie wyłącza niejawnego wysłania w Chrome, Firefoksie ani Safari (także iOS — klawisz „Idź”); `tabindex="-1"` +
  `aria-hidden` nie dubluje przycisku czytnikom. Scenariusz nie naciska jednak Enter (zdarzenia z JS strony nie wysyłają formularza),
  więc regresji „Enter = Google” w innej przeglądarce nie złapie.
- Poprawka: w `run.py` / scenariuszu prawdziwy Enter przez CDP (`Input.dispatchKeyEvent`) w polu hasła → `/login/password`; raz
  sprawdzić ręcznie na iPhonie właściciela (klawisz „Idź” i podpowiedź hasła z pęku kluczy).

### P8 — `LoginLimiter.clientKey` może pytać DNS; komentarz mówi, że nigdy — **drobiazg**
- `LoginLimiter.java:96-97`.
- `InetAddress.getByName` traktuje napis jako literał tylko, gdy zaczyna się cyfrą szesnastkową albo `:`; „`zz:1`” idzie do DNS
  (sprawdzone: `Name or service not known` po ~10 ms, na wątku żądania). Na produkcji adres pochodzi z `CF-Connecting-IP` (ustawia go
  Cloudflare, a do aplikacji dochodzi tylko Cloudflare), więc to raczej obrona w głąb niż realny wektor.
- Poprawka: przed parsowaniem `client.matches("[0-9A-Fa-f:.%]+")` (inaczej klucz = napis), komentarz zgodny z kodem, przypadek w
  `LoginLimiterTest.anIpv6Client_isItsSlash64`.

### P9 — Hasła w NFC: konta sprzed tego wydania z hasłem w postaci rozłożonej przestaną pasować — **drobiazg**
- `PasswordAccountService.java:146-149`, `:319-325`.
- #280 jest już na `main` (wdrożony); hasła zapisane od tamtej pory haszowano bez normalizacji. Hasło z „ą” w postaci NFD (rzadkie:
  zwykłe klawiatury dają NFC) po tym wydaniu nie zaloguje — da się wyjść resetem.
- Poprawka: przed wydaniem sprawdzić na produkcji `SELECT count(*) FROM dj_account` (read only); przy kilku kontach nic nie robić,
  przy większej liczbie — w `matches` drugie porównanie z nienormalizowanym hasłem, gdy pierwsze zawiedzie i hasło ≠ NFC (raz, bez
  zmiany czasu odpowiedzi dla ASCII).

### P10 — Kolejka formularzy przy zalewie gubi prośby bez śladu dla osoby — **drobiazg**
- `PasswordAccountService.java:113-114`, `:185-187`.
- 1 wątek + 50 miejsc: przy zalewie z wielu klientów (każdy w swoim limicie 10 rejestracji/h) prawdziwa rejestracja albo reset
  wypada — formularz mówi „Sprawdź skrzynkę”, mail nie przychodzi. Świadomy kompromis (chroni CPU), jest WARN w logu.
- Poprawka: wystarczy licznik odrzuconych w `SiteCounter` albo alert z logu (UptimeRobot nie zobaczy); bez zmiany projektu.

### P11 — Ponowne wyświetlenie strony po złym haśle traktuje każdą wartość pola jako zaznaczoną — **drobiazg**
- `PasswordLoginController.java:113` (`getParameter(…) != null`), a `StayLoggedIn.chosen` uznaje tylko `true/on/yes/1`.
- `remember-me=false` (ręcznie) pokaże pole zaznaczone, a sesja byłaby zwykła. Poprawka: `StayLoggedIn.chosen` publiczne i użyte w
  obu miejscach.

## Co jest dobrze

- **Session fixation:** nowe id przy obu logowaniach (Google — `ChangeSessionIdAuthenticationStrategy` przed handlerem; hasło —
  `changeSessionId()`, mutacja → 5 czerwonych testów). Długi czas stosowany tylko do sesji, która właśnie dostała nowe id, w handlerze
  sukcesu — żaden parametr nie przedłuży istniejącej ani niezalogowanej sesji (`SpringSessionRememberMeServices` nie jest
  zarejestrowany jako remember-me Spring Security). Ponowne logowanie bez pola przywraca 30 min (mutacja 3).
- **CSRF:** `POST /login/google` i `/login/password` z tokenem (IT: bez tokenu 403) — obca strona nie wydłuży następnego logowania.
- **Otwarte przekierowanie:** bez zmian — `djPagesRemembered` zapamiętuje tylko `/dj/**`, brak parametru celu.
- **`formaction` + `formnovalidate`:** przycisk Google wysyła wpisany adres i hasło (jeśli są) do własnego `POST /login/google` po
  HTTPS — nieczytane, nie logowane (brak filtra logującego parametry), nie trafiają do Google (302 → GET bez treści). Akceptowalne;
  najwyżej menedżer haseł zaproponuje zapis hasła.
- **Koniec długiej sesji:** wylogowanie (`invalidate` + `deleteCookies("SESSION")`), nowe hasło (inne sesje), usunięcie konta (wszystkie,
  po commicie) — IT na prawdziwych sesjach JDBC.
- **Wydajność:** przy logowaniu żadnego dodatkowego zapisu — zmiana id i tak zapisuje wiersz jednym `UPDATE`, który niesie
  `MAX_INACTIVE_INTERVAL` i `EXPIRY_TIME`; warunek `!= usualSeconds` jest kosmetyczny; usunięcie nieistniejącego atrybutu to no-op.
  Przy kolejnych żądaniach nic nowego (ten sam `UPDATE` ostatniego dostępu co dotąd). Wierszy `SPRING_SESSION` przybywa tylko o sesje
  zalogowanych z polem; sprzątanie po `EXPIRY_TIME` jak dotąd. V73: zwiększenie limitu `varchar` bez przepisania tabeli, chwilowa
  blokada `ACCESS EXCLUSIVE` na tabeli z kilkoma wierszami — pomijalna na produkcji. bcrypt: rejestracja poza wątkiem żądania,
  logowanie — jedno porównanie zawsze.
- **Wyliczanie kont:** ten sam komunikat i kod dla braku konta, złego hasła i konta niepotwierdzonego; `LOCKED` nie zależy od istnienia
  konta; formularze maili odpowiadają przed pracą zależną od konta.
- **Teksty:** PL bez rodzaju („Nie wylogowuj mnie…”, „Zaznacz tylko na własnym…”, „zalogujesz się”), `ImpersonalPolishTextsTest`
  zielony; escape'y w `messages*.properties`; polityka PL/EN zgodna co do sesji i 14 dni (brakuje tylko okresu ciasteczka — P4).
- **Jakość:** `StayLoggedIn` mały, nazwany po tym, co robi, komentarze mówią „dlaczego” (MAX_VALUE, wybór Google czytany raz, brak
  pola na stronach z linku); testy jednostkowe odtwarzają kolejność Spring Security, IT sprawdza wiersz w bazie i nagłówek; trzy
  mutacje — wszystkie złapane. Dokumentacja (Sekcje 4, 6, 8, 10-1, 10-2, 12, 13) zgodna z kodem.
- **Strona:** pole + etykieta = jeden cel 44 px, bez skryptu (CSP), pole domyślnie puste.

## Werdykt

**Scalić po drobnych poprawkach** — albo scalić od razu i dołożyć je następnym PR-em, jeśli rejestracja hasłem ma ruszyć szybko:
błędów nie ma, poprawki P1–P14 naprawdę zamykają poprzedni przegląd, a nowa funkcja jest bezpieczna w tym, o co pytano (fixation,
CSRF, cudze sesje). Przed albo zaraz po scaleniu: **P2** (sufit maili — chroni limit Brevo całej aplikacji) i **P1** (wylogowanie
innych urządzeń — długie sesje bez tego są problemem dla kont Google). P3 i P4 — tanie, warto w tym samym PR. P5–P11 — kiedy wygodnie.
