# Przegląd 2026-11-18 — gałąź `claude/funny-wozniak-sxv682` (logowanie adresem e-mail i hasłem, V72)

Zakres: Adas553/scan2play#280 (do `main`) i Adas553/scan2play#281 (do `dev`, ta sama gałąź) — 6 commitów `7c33caa` … `81a9802`
względem `origin/main`, 61 plików, +2332 / −85: `PasswordLoginController`, `PasswordAccountService`, `LoginLimiter`,
`DjAccountEntity` / `DjAccountRepository`, migracja `V72__password_accounts.sql`, `SecurityConfig`, `GuestFormExpiredHandler`,
`PlanService` (okres próbny po adresie), `AccountDeletionService`, szablony stron i maili, teksty PL/EN, polityka prywatności i
regulamin, CSS stanu kart ustawień, testy, dokumentacja. Decyzje właściciela z Sekcji 8 („The login with a password”) przyjęte jako
dane — oceniane jest wykonanie.

## Uruchomione sprawdzenia (sesja w chmurze, worktree `81a9802`, `tools/check.py`)

| Krok | Wynik |
|---|---|
| `unit` | 1340 testów, 0 błędów — OK (w tym `ImpersonalPolishTextsTest`) |
| `it` | 174 testy, 0 błędów — OK (PostgreSQL 16 kontenera; pierwszy przebieg padł w całości na haśle użytkownika `postgres`, nie na kodzie) |
| `browser` | 436 scenariuszy (z nowym `folds.js`) — OK |
| `audit -t login,register,password,account,settings` | 18 stron × 320 / 390 / 1280 px: bez przewijania w bok, bez błędów konsoli, bez małych celów dotyku — OK |
| plan zapytania `googleAccountHas` (PostgreSQL 16, 20 000 wierszy, plan ogólny jak z JDBC) | `Index Scan using idx_party_settings_owner_email_lower` — indeks częściowy V72 jest używany |
| test roboczy na prawdziwym `JdbcIndexedSessionRepository` (usunięty po próbie) | `ObjectProvider<FindByIndexNameSessionRepository<? extends Session>>` wstrzykuje repozytorium JDBC, `findByPrincipalName("local:…")` znajduje sesję; sesja usunięta, potem `changeSessionId()` + `save()` — **bez wyjątku, sesji brak** (dowód do P5) |

## Uwagi (od najpoważniejszych)

### P1 — Rejestracja + logowanie zdradzają, czy adres ma konto — **PEWNE, wysoka (łamie regułę „nic nie mówi, czy adres ma konto”)**
- `PasswordAccountService.java:130-147` (`register`) i `:191-193` (`logIn`), `PasswordLoginController.java:103-107`.
- Scenariusz: napastnik wysyła `POST /register` z adresem ofiary i hasłem `P`, potem `POST /login/password` z tym adresem i `P`:
  - adres bez konta → powstaje konto niepotwierdzone z hasłem `P` → logowanie: **„Najpierw potwierdź adres e-mail…”** (`NOT_CONFIRMED`);
  - adres z potwierdzonym kontem z hasłem → rejestracja wysłała tylko mail „masz już konto”, `P` nie zapisane → **„Zły adres e-mail albo hasło.”**;
  - adres konta Google (z własną imprezą) → mail „logujesz się przez Google”, konta brak → **„Zły adres…”**.
  Różne odpowiedzi = enumeracja kont (hasłowych i Google). Działa także „po cichu”: zapis konta (`:143`) dzieje się przed limitem
  maili (`mail()` → `limiter.mayEmail`), więc po wyczerpaniu limitu ofiara nie dostaje nawet maila.
- Poprawka: logowanie poprawnym hasłem konta niepotwierdzonego odpowiada **tak samo jak złe hasło** (ten sam komunikat, ten sam kod),
  z tekstem obejmującym oba przypadki, np. „Zły adres e-mail albo hasło. Konto założone niedawno? Najpierw potwierdź adres linkiem z
  maila.” — a link potwierdzający idzie w tle jak teraz (gdy limit pozwala). Wariant `NOT_CONFIRMED` może zostać w serwisie, ale kontroler
  mapuje go na `account.login.wrong`. Test: scenariusz napastnika wyżej (rejestracja adresu z kontem / bez konta → identyczna
  odpowiedź logowania). Przy okazji tekst `account.login.not_confirmed` („link został wysłany jeszcze raz”) bywa nieprawdziwy, gdy
  limit 1/min zablokował wysyłkę.

### P2 — Cudze konto da się zablokować na stałe (hasła) i odciąć od maili (reset) — **PEWNE, średnia**
- `LoginLimiter.java:68-92`, `PasswordAccountService.java:181-183` (blokada sprawdzana przed szukaniem konta).
- Scenariusz: 10 złych haseł na adres ofiary co 15 minut (z jednego IP — limit klienta to 50) → `LOCKED` także dla prawdziwego hasła,
  bez końca. Ratunkiem jest „Nie pamiętasz hasła?” (reset loguje z pominięciem blokady), ale napastnik wysyła też 5 × `POST
  /password/forgot` na godzinę (limit klienta 20/h) → limit maili adresu wyczerpany, ofiara nie dostaje linku. Jedno IP wystarcza na
  ~4–5 ofiar naraz; koszt: ~45 żądań/h na ofiarę. Wiadomość „Odczekaj 15 minut albo ustaw nowe hasło” nie pomaga.
- Poprawka (do wyboru, bez zmiany decyzji właściciela): (a) licznik złych haseł per **(adres, klient)** jako główny + wyższy próg
  globalny adresu (np. 50/15 min) zamiast twardego 10; (b) limit maili adresu per (adres, klient) z wyższym wspólnym sufitem i
  wyjątkiem: maila resetu nie blokuje sam fakt, że inny klient wyczerpał limit; (c) alternatywnie Cloudflare Turnstile po N błędach
  (Cloudflare i tak stoi przed aplikacją). Test: dwa różne IP — blokada jednego nie blokuje drugiego.

### P3 — Ponowna rejestracja niepotwierdzonego adresu nadpisuje hasło bez żadnego limitu — **PEWNE, średnia**
- `PasswordAccountService.java:140-147`.
- Scenariusz: ofiara zakłada konto i dostaje link. Napastnik rejestruje ten sam adres ze swoim hasłem: hasło ofiary nadpisane
  (`:141-143`) **zanim** limit maili cokolwiek sprawdzi; jeśli limit pozwolił — nowy link unieważnia stary. Ofiara klika link, wpisuje
  swoje hasło → „Złe hasło” (i licznik błędów rośnie → P2). Powtarzane co minutę uniemożliwia potwierdzenie; jedyna droga to reset —
  który P2 też potrafi zablokować. Napastnik nie przejmie konta (nie ma skrzynki), ale skutecznie je psuje.
- Poprawka: sprawdzać limit (`mayEmail`) **przed** jakimkolwiek zapisem i nie zmieniać hasła niepotwierdzonego konta, gdy maila nie
  wysłano; lepiej: trzymać hasło przy tokenie (np. nie nadpisywać hasła konta, którego link jest jeszcze ważny i wysłany < X min temu,
  albo przy potwierdzeniu akceptować hasło związane z danym linkiem). Najprościej: limit przed zapisem + test „druga rejestracja w
  ciągu minuty nie zmienia hasła”.

### P4 — `POST /register` liczy bcrypt bez żadnego limitu — **PRAWDOPODOBNE, średnia (CPU produkcji)**
- `PasswordAccountService.java:130` — `encoder.encode(password)` przy każdym żądaniu, przed jakimkolwiek limitem; kontroler
  (`PasswordLoginController.java:122-136`) też nic nie ogranicza. Logowanie ma limit (po 50 błędach z IP nie liczy bcrypt), rejestracja nie.
- Scenariusz: zalew `POST /register` (token CSRF z jednego GET wystarczy na wiele POST w tej samej sesji) — każdy to ~50–100 ms CPU
  (bcrypt 10) na wątku Tomcata; kilka równoległych klientów zajmuje rdzenie Railway i wątki, panel DJ-ów zwalnia.
- Poprawka: limit na klienta przed haszowaniem (np. 20 rejestracji/h z IP — ten sam `LoginLimiter`), a dla jednakowego czasu
  odpowiedzi — przy odmowie limitu porównanie z `dummyHash` zamiast `encode` (ten sam koszt) albo po prostu ta sama odpowiedź bez
  haszowania (limit klienta nie zdradza adresu).

### P5 — Zalogowany DJ, który ustawia nowe hasło linkiem, zostaje wylogowany — **PEWNE (sprawdzone na repozytorium JDBC), średnio-niska**
- `PasswordLoginController.java:247-248` (`endSessions` przed `logIn`), `:253-258`.
- Dokumentacja (Sekcja 8) mówi, że zmiana hasła z panelu = link „nowe hasło”. Gdy DJ jest zalogowany w tej przeglądarce,
  `findByPrincipalName("local:<id>")` zwraca też **bieżącą** sesję i `deleteById` ją kasuje; potem `changeSessionId()` + zapis
  Spring Session robi `UPDATE … WHERE PRIMARY_ID = ?` na nieistniejącym wierszu — 0 wierszy, bez błędu. Ciasteczko wskazuje
  nieistniejącą sesję: przekierowanie na panel → strona logowania. Test roboczy powyżej: `save ok`, `findById(newId) = null`.
- Poprawka: najpierw `request.changeSessionId()`, potem usunąć sesje konta **poza** `request.getSession().getId()` (albo pominąć id
  bieżącej sesji przed usuwaniem). Test z prawdziwym repozytorium (IT) albo z `MapSessionRepository` + indeks principal.

### P6 — „Jednorazowość” linku resetu i potwierdzenia nie jest atomowa — **PRAWDOPODOBNE, niska**
- `PasswordAccountService.java:241-255` (`resetPassword`), `:161-176` (`confirm`) — odczyt po tokenie, potem zapis; brak `@Version`
  ani blokady wiersza.
- Scenariusz: dwa równoczesne `POST /password/reset` z tym samym tokenem (podwójne kliknięcie, albo ktoś, kto zna link z logów
  pośrednich) — oba przechodzą, wygrywa ostatnie hasło, obie przeglądarki zalogowane.
- Poprawka: zużycie tokenu jednym `UPDATE dj_account SET reset_token_hash = NULL … WHERE reset_token_hash = :h AND reset_expires_at > now()`
  (liczba wierszy = 1 → dalej), albo `@Lock(PESSIMISTIC_WRITE)` na `findByResetTokenHash` / `findByVerifyTokenHash`.

### P7 — Dwie równoczesne rejestracje nowego adresu kończą się błędem 500 — **PEWNE (rzadkie), niska**
- `PasswordAccountService.java:131-143` — `findByEmail` puste w obu, oba `save` → `uk_dj_account_email` →
  `DataIntegrityViolationException` → strona błędu zamiast „Sprawdź skrzynkę” (inna odpowiedź niż zwykle; drobna niespójność z P1).
- Poprawka: złapać naruszenie unikalności w kontrolerze/serwisie i odpowiedzieć „Sprawdź skrzynkę” (drugi wątek nic nie wysyła), albo
  `INSERT … ON CONFLICT DO NOTHING`. Test IT z dwoma wątkami.

### P8 — Różnice czasu „Nie pamiętasz hasła?” (i w mniejszym stopniu rejestracji) — **PRAWDOPODOBNE, niska**
- `PasswordAccountService.java:224-231`: adres z kontem → `UPDATE` tokenu + commit; bez konta → dwa `SELECT`; konto Google → `SELECT`
  bez zapisu. Różnica rzędu 1–3 ms, mierzalna statystycznie przy pierwszych próbach (po wyczerpaniu limitu maili adresu znika).
  Logowanie i rejestracja wyrównują bcrypt poprawnie (`dummyHash`, `encode` zawsze).
- Poprawka (tania): dla `forgot` i `register` wykonywać pracę zależną od konta po commicie w wątku maili (`Mailer.later`) — formularz
  odpowiada od razu po walidacji, niezależnie od adresu.

### P9 — Usunięcie konta nie kończy innych sesji konta — **PEWNE, niska**
- `DjPartySettingsController.java:538-545` — `session.invalidate()` tylko bieżącej. Druga przeglądarka zostaje zalogowana jako
  `local:<id>` usuniętego konta; otwarcie panelu tworzy mu nową, pustą imprezę (`DjSessionHelper.getOrCreateParty`) pod id, którego
  już nie ma w `dj_account` (sierota bez możliwości zalogowania i usunięcia). Dla Google było tak samo, ale tam to samo konto wraca.
- Poprawka: przy usuwaniu wywołać to samo co po resecie (koniec wszystkich sesji principal’a — z poprawką P5), dla obu rodzajów kont.

### P10 — „Jeden adres, jeden sposób logowania” sprawdzany tylko po `party_settings.owner_email` — **PEWNE, niska (dokumentacja / świadoma luka)**
- `DjAccountRepository.java:147`. Adres osoby z obsługi zalogowanej przez Google, która nie ma własnej imprezy (przyszła z zaproszenia),
  nie jest znany → można założyć na niego konto z hasłem. W drugą stronę logowanie Google adresem konta z hasłem niczego nie sprawdza.
  Zaproszenie mailowe (V34) pasuje wtedy do obu kont — kto pierwszy się zaloguje, ten je dostaje.
- Poprawka: bez zmiany decyzji — opisać lukę w Sekcji 8 („sprawdzane po adresach organizatorów Google”), albo dołożyć do sprawdzenia
  adresy obsługi, jeśli są gdzieś zapisywane.

### P11 — Limity klienta liczone po pełnym adresie IPv6 — **PRAWDOPODOBNE, niska**
- `GuestRequestLimiter.clientIp` (`:168-177`) używane przez `LoginLimiter`: klient z IPv6 zmienia adres w obrębie swojego /64 bez kosztu
  → limity 50 złych haseł i 20 maili na klienta nie działają (zostają limity adresu e-mail). Przy zalewie losowymi kluczami
  `maximumSize(50_000)` Caffeine może też wypchnąć licznik ofiary (W-TinyLFU to utrudnia, ale nie wyklucza).
- Poprawka: klucz klienta dla IPv6 = prefiks /64 (dla limiterów logowania; gościom można zostawić jak jest). Test jednostkowy.

### P12 — Drobne w `logIn` — **PEWNE, niska (utrzymanie)**
- `PasswordLoginController.java:270`: token CSRF usuwany po nazwie atrybutu wpisanej na sztywno — zmiana repozytorium tokenów (np.
  ciasteczko) po cichu wyłączy rotację. Lepiej: wstrzyknąć `CsrfTokenRepository` i `saveToken(null, request, response)` (albo
  `CsrfAuthenticationStrategy`). `SecurityContextHolder.setContext` statycznie zamiast `SecurityContextHolderStrategy` — zgodne z
  Spring Security 6, ale zalecana jest strategia.
- Sam przepływ jest poprawny: nowe id sesji (`changeSessionId`), nowy token CSRF, kontekst zapisany w sesji (Spring Session JDBC
  indeksuje `PRINCIPAL_NAME` = `local:<id>` — sprawdzone), powrót tylko na stronę DJ-a z `djPagesRemembered`, bez parametru celu —
  bez otwartego przekierowania.

### P13 — `password_hash varchar(100)` — **PRAWDOPODOBNE, niska**
- `V72__password_accounts.sql:14`, `DjAccountEntity` `length = 100`. `{bcrypt}$2a$10$…` ma 68 znaków; gdy domyślny koder
  `DelegatingPasswordEncoder` się zmieni (np. `{argon2@SpringSecurity_v5_8}…` ≈ 120 znaków), `upgradeEncoding` zapisze dłuższy
  hasz → błąd zapisu przy logowaniu. Poprawka w następnej migracji: `varchar(255)` (i `length` encji).

### P14 — Jakość wg AGENTS.md: komentarze z historią, nieaktualne komentarze, luki w testach — **PEWNE, niska**
- `app.css:1453` „the owner's phone, 2026-11-18”, Sekcja 8 „the owner, 2026-11-18” — historia do `docs/history/` / commita.
- `staff.html:20,110,123,134` — komentarze i teksty zastępcze nadal mówią „adres konta Google” (teksty z `messages` są już poprawne).
- Brak testów: P1 (enumeracja przez logowanie), koniec innych sesji po resecie z prawdziwym repozytorium sesji (i P5), wyścig
  rejestracji (P7), `LoginLimiter` sam w sobie (okna, `succeeded`, klient — jest tylko pośrednio w `PasswordAccountServiceTest`).
- Hasło nie jest normalizowane (NFC) — hasło z polskimi znakami wpisane na innym urządzeniu może mieć inną postać Unicode; rzadkie,
  warto `Normalizer.normalize(…, NFC)` przy zapisie i sprawdzaniu (jedno miejsce).

## Co jest dobrze

- Tokeny linków: 32 bajty z `SecureRandom`, base64url, w bazie tylko SHA-256 (unikalne indeksy częściowe), wygasanie 48 h / 1 h,
  zerowane po użyciu; strony z tokenem `noindex, nofollow` i `no-referrer`; link sam niczego nie potwierdza (wymaga hasła) — dobra
  odporność na skanery linków.
- bcrypt przez `DelegatingPasswordEncoder` z `upgradeEncoding`, limit 72 bajtów sprawdzany i przy ustawianiu, i przy porównaniu;
  stały czas logowania dla adresu bez konta (`dummyHash`).
- `OAuth2AuthenticationToken` z `local:<id>`: przejrzane miejsca czytające `sub`/`name`/`email`/`email_verified` (`StaffController`,
  `DjDashboardController.keepOwner`, `StatsController`/`AdminReportController` — `ADMIN_GOOGLE_ID` nigdy nie równa się `local:…`),
  nic nie zakłada `OidcUser` ani `registrationId`. Okres próbny po kluczu adresu (`EmailAddresses.key`) — ponowne założenie konta nie
  daje nowego okresu.
- `scan2play.mail.dev-links` tylko w `application-local.properties` i dodatkowo tylko gdy Brevo wyłączone — link nie trafi do logów
  produkcji. W logach tylko `local:<id>`, nigdy adres ani token.
- `GuestFormExpiredHandler`: stała mapa ścieżek, token przepuszczany tylko jako `[A-Za-z0-9_-]{1,100}` — bez wstrzyknięcia i otwartego
  przekierowania; komunikaty z flash są stałymi kluczami.
- V72 zgodna z encją (`validate` przechodzi w IT), indeks `lower(owner_email)` używany; maile po commicie, poza wątkiem żądania;
  wszystkie zapytania po indeksach unikalnych, bez N+1 i bez list.
- Strony bez inline JS/CSS (CSP), na telefonie bez przewijania w bok; stan kart ustawień nie łamie się w środku słowa (scenariusz
  `folds.js`). Teksty PL bezosobowe / bez rodzaju, `messages*.properties` z escape'ami. Polityka prywatności i regulamin zgodne z kodem
  (dane konta, liczniki w pamięci, skrót adresu dla okresu próbnego).

## Werdykt

**Merge po poprawkach.** Przed mergem: P1 (enumeracja — wprost łamie regułę funkcji), P2 i P3 (zablokowanie / zepsucie cudzego konta
małym kosztem), P4 (bcrypt bez limitu) i P5 (wylogowanie przy zmianie hasła — to udokumentowana droga zmiany hasła). P6–P14 mogą
pójść w tym samym PR albo w następnym. Reszta wykonania — przepływ logowania, tokeny, CSRF, sesje, migracja, testy i strony — jest
porządna.
