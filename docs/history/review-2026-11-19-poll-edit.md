# Przegląd 2026-11-19 — edycja pytań („Edytuj”, „Popraw jako nowe”), PR-y #288 / #289

Zakres: Adas553/scan2play#288 (do `main`) i Adas553/scan2play#289 (do `dev`), gałąź `claude/admiring-cannon-fud6t3`, commit
`2528ecf` od `f393a90` (scalone #286). 15 plików, +251 / −30, bez migracji. Powód przeglądu: właściciel zgłosił, że po kliknięciu
„Edytuj” ekran skacze.

## Uwagi

### E1 — „Edytuj” i „Popraw jako nowe” przeładowują stronę i przewijają ją na górę; ekran skacze trzy razy — **błąd (zgłoszony)**

`templates/polls.html` (linki `?edit=` / `?copy=` z `#pollNewCard`), `DjPollsController.page`.

Oba przyciski były linkami. Każde kliknięcie przeładowywało stronę, a kotwica `#pollNewCard` wygrywa z zapamiętanym przewinięciem
(`scroll-restore.js`), więc DJ, który przewinął do pytania na dole listy, lądował na górze strony. `autofocus` w polu na telefonie
dodatkowo otwierał klawiaturę i przesuwał widok.

To nie koniec skoków:
- po „Zapisz zmiany” strona wracała tam, gdzie zapamiętała przewinięcie, czyli na górę (formularz), a nie do karty;
- „Anuluj” było kolejnym przeładowaniem;
- po nieudanym zapisie link `?edit=` zostawał w pasku adresu i w historii.

**Poprawka:** formularz otwiera się w samej karcie pytania (`<details>`), bez przeładowania i bez ruchu czegokolwiek nad nim.
„Anuluj” zwija go i przywraca słowa. Po zapisie strona wraca tam, gdzie DJ był. Scenariusze `polls-edit-in-place` i
`polls-copy-in-place` mierzą przewinięcie i położenie karty. Przed poprawką były **czerwone**: kliknięcie ładowało stronę od nowa.

### E2 — Edycja przejmowała formularz „Nowe pytanie” — **do poprawy**

`templates/polls.html` (karta `#pollNewCard` w trybie `pollEdit` / `pollCopy`).

W trybie edycji nagłówek karty na górze zmieniał się na „Edytuj pytanie”, a przyciski „Zadaj teraz” / „Zapisz na później” znikały.
DJ nie mógł w tym czasie dodać nowego pytania. Kto przewinął do listy, nie widział, które pytanie edytuje. Formularz był daleko od
pytania, którego dotyczył.

**Poprawka:** każde pytanie ma swój formularz w swojej karcie. Karta „Nowe pytanie” zawsze tworzy nowe. Formularz jest jeden,
wspólny (`fragments/poll-form.html`), a pola mają własne id w każdej karcie.

### E3 — „Popraw jako nowe” dokładało trzeci przycisk do głównego rzędu zadanego pytania — **do poprawy (wygląd)**

`templates/polls.html` (rząd `s2p-btn-row` zadanego pytania).

Przegląd #286 (P4) odchudził kartę zadanego pytania. „Popraw jako nowe” w głównym rzędzie znowu dawało trzy równorzędne przyciski
obok „Zakończ pytanie” i wyników. To działanie poboczne, rzadkie, na wypadek literówki.

**Poprawka:** „Edytuj” / „Popraw jako nowe” stoi w ostatniej linii karty, po lewej, a „Usuń” po prawej. Po otwarciu formularz
zajmuje szerokość karty, a „Usuń” schodzi pod niego.

### E4 — Odrzucony zapis wracał do formularza na górze — **drobiazg**

`DjPollsController.edit`.

Przy błędnych odpowiedziach (np. „Tak” i „tak”) albo przy pytaniu zadanym w międzyczasie przekierowanie prowadziło na `?edit=…#pollNewCard`.

**Poprawka:** powrót tam, gdzie DJ był, z komunikatem na dole ekranu, a formularz w karcie jest znowu otwarty (`pollEditOpen`). Przy
pytaniu zadanym w międzyczasie otwiera się jego „Popraw jako nowe”. Wpisane zmiany przepadają, tak samo jak przy nowym pytaniu.

### E5 — Testy sprawdzały adresy linków, a nie to, co widzi DJ — **drobiazg**

`PollsPageRenderTest` sprawdzał `href` i wypełnione pola, ale żaden scenariusz nie klikał „Edytuj”. Dlatego skok przeszedł.

**Poprawka:** scenariusze z E1 klikają oba przyciski, mierzą ekran, wpisują słowa i klikają „Anuluj”. Render sprawdza formularze w
kartach, ich id, ponowne otwarcie po odmowie i to, że strona nie ma już `?edit=` / `?copy=` ani `autofocus`.

### Co jest dobrze

- Zasada „zadanego pytania się nie edytuje” jest słuszna i trzymana na serwerze: `UPDATE … WHERE opened_at IS NULL` jest odporne na
  „Zadaj” w tej samej chwili (blokada wiersza, warunek czytany ponownie). Test `PollServiceIT.onlyAQuestionSavedForLater_isEdited`.
- Uprawnienie `POLLS` i plan Pro na nowym endpoincie (testy endpointów). IDOR: `party_code` w zapytaniu. CSRF jak w innych formularzach.
- Teksty PL/EN są spójne z resztą strony i bezosobowe.

## Poprawki (2026-11-19, ta sama gałąź)

| Punkt | Co zrobiono |
|---|---|
| E1, E2, E3 | Formularz w karcie: `<details class="s2p-poll-change">` w ostatniej linii (`.s2p-poll-foot`), `fragments/poll-form.html` wspólny dla „Nowe pytanie”, „Edytuj” i „Popraw jako nowe”. „Anuluj” to `type="reset"` + zwinięcie i fokus na przycisku (`polls.js`). Usunięte `?edit=` / `?copy=`, `autofocus`, kotwice i reguła CSS dla linków w rzędzie |
| E4 | Odmowa: `redirect:/dj/polls?party=…` + `pollEditOpen` (flash), formularz karty otwarty |
| E5 | Scenariusze `polls-edit-in-place`, `polls-copy-in-place` (czerwone przed poprawką: strona ładowała się od nowa). Render: `aSavedQuestion_isEditedInItsCard_andAnAskedOne_isCorrectedAsANewOne`, strona `polls-edit` (otwarty formularz) w audycie |

Przy okazji: fragment formularza był najpierw na dole `polls.html`, a definicja fragmentu renderuje się na stronie jako zwykły
element. Audyt wykrył przez to przewijanie w bok o 4 px. Dlatego fragment ma własny plik.

### Sprawdzenia

| Krok | Wynik |
|---|---|
| `unit` | 1433 testy — OK |
| `browser` | 454 scenariusze (2 nowe) — OK |
| `audit -t "guest-poll,polls,tv-poll,settings,dashboard"` | bez przewijania w bok i błędów; cele < 44 px tylko 4 znane z panelu — OK |
| zrzuty `polls`, `polls-edit` (320 / 390 px) | obejrzane: „Popraw jako nowe ▾” po lewej, „Usuń” po prawej; otwarty formularz w karcie, pod nim „Zapisz zmiany” i „Anuluj” |
