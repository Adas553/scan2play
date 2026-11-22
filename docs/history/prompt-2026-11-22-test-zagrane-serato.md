# Prompt do nowej sesji: próba „Zagrane samo” z Serato i VirtualDJ na Windows (2026-11-22)

Do wklejenia w nową sesję Claude Code (lokalną, na komputerze właściciela z Windows, `D:\Coding\scan2play`):

---

Przeczytaj CLAUDE.md (z AGENTS.md), „Start here” w SESSION_HANDOFF.md, w docs/context/06-3-config-templates-static.md wiersze o
`guide*.html`, `images/guide/*.webp` i `js/guide-pictures.js`, oraz
docs/history/review-2026-11-22-p3-serato-play-count.md (całe) i w docs/history/review-2026-11-22-zagrane-serato-window.md sekcję
„Próba Serato 5.1 na Windows”. Gałąź: claude/youthful-heisenberg-3whpcr — pracuj na niej lokalnie, nic nie commituj, dopóki nie
powiem — zmiany zostaw niezacommitowane do przejrzenia w IntelliJ.

Cel: poprowadzić mnie przez próbę programu „Scan2Play Zagrane” na moim Windows 11 (Serato DJ Lite 5.1 i VirtualDJ 8.5), krok po
kroku, jeden krok na raz: co mam kliknąć, czego się spodziewać, co mi podać. Aplikacja z IntelliJ (`localhost:8080`,
`PLANS_ENFORCE=false`); program z „Run workflow” „Program DJ-a (deck-agent)” na tej gałęzi (powiedz, gdzie go znaleźć i jak sprawdzić,
że to wersja z tej gałęzi).

Do sprawdzenia, w tej kolejności:
1. P1 (wzrost licznika do najnowszego wczytania): gość prosi o piosenkę X, którą wcześniej załadowałem na deck bez Play i zdjąłem;
   po minucie ładuję X i puszczam — prośba ma się oznaczyć w kilka sekund (w panelu „ZAGRANE”, w oknie programu „Oznaczone”).
2. Piosenka tylko załadowana (bez Play), także dwa razy — prośba zostaje w kolejce. Piosenka puszczona dwa razy — dwie prośby (jeśli
   gość poprosi o nią drugi raz po pierwszym zagraniu).
3. P8: zamknąć i otworzyć Serato, załadować piosenkę bez Play. Czy Serato założyło nową sesję? Sprawdzić w **kopii** bazy (z `-wal`),
   tylko do odczytu: `SELECT id, start_time, end_time FROM history_session ORDER BY id DESC LIMIT 3` oraz `PRAGMA table_info(asset)`
   — czy `id` ma `pk = 1`. Potem Play — oznacza się raz, nic sprzed restartu drugi raz.
4. Czy Serato DJ Lite pozwala skasować licznik odtworzeń piosenki (gdzie, jak się to nazywa)? Jeśli tak: skasować dla jednej, załadować,
   puścić — prośba ma się oznaczyć (P3).
5. VirtualDJ bez zmian: prośba oznacza się ok. 45 s po puszczeniu.
6. Instrukcja `/jak-to-dziala`, sekcja „Zagrane samo” (`#deck`): czy jest zrozumiała dla DJ-a i zgadza się z tym, co widzę w programie
   i w karcie panelu (na komputerze i na telefonie).
7. **Zrzuty okna programu do instrukcji — zrób je sam**, na tym Windows (prawdziwe czcionki i wygląd, nie Xvfb):
   - Dwa stany okna: (a) „Połącz ten komputer z imprezą” z pustym polem kodu; (b) połączone, „Działa — połączono z imprezą”,
     „Czyta” (VirtualDJ / Serato) i „Oznaczone” z 3–4 piosenkami. Do (b) użyj zmyślonej imprezy na lokalnej aplikacji i znanych
     przebojów (nie prawdziwych gości ani mojej imprezy); okno nie może pokazywać nazwy mojego komputera, użytkownika, ścieżek ani
     adresu `localhost` — jeśli któryś stan je pokazuje, powiedz, zanim zrobisz zrzut.
   - Zrzut samego okna, bez pulpitu: PowerShell z .NET (`System.Drawing`, proces „Scan2Play Zagrane” → `MainWindowHandle` →
     `GetWindowRect` przez `user32.dll`, proces DPI-aware — `SetProcessDPIAware` — żeby przy skalowaniu 125/150 % nie uciąć okna;
     albo `PrintWindow`). Skrypt w katalogu tymczasowym, nie w repo. Pokaż mi PNG, zanim pójdzie dalej.
   - Do repo: `src/main/resources/static/images/guide/program-pl.webp` (i `program-en.webp` — to samo okno, program jest tylko po
     polsku; albo jeden plik dla obu języków, jeśli tak prościej — powiedz, co wybrałeś), WebP jakość ~85, rozmiar w pikselach jak
     zrzut, plik możliwie mały (< 60 KB).
   - W `guide_pl.html` i `guide.html`, w sekcji `#deck` po wierszu „Okno programu”: `<figure class="s2p-guide-picture" id="guidePicture4">`
     jak trzy istniejące (link do pliku, `width`/`height` pliku, `loading="lazy"`, `decoding="async"`, opisowy `alt`, `<figcaption>`);
     dużo węższy niż zrzut panelu — ogranicz szerokość (jak `.s2p-guide-picture-tall`), żeby na komputerze nie był rozciągnięty.
     `GuidePageRenderTest.assertThePictures` obejmuje czwarty obrazek (najpierw czerwony), `06-3-config-templates-static.md`
     dopisany. Potem `python tools/check.py unit -t GuidePageRenderTest` i `python tools/check.py audit -t guide` (320 / 390 / 1280 px)
     oraz browser scenarios instrukcji (`guide-pictures.js`: dotknięcie powiększa) — w kopii repo, jak każe CLAUDE.md.

Zasady: bazy Serato (%LOCALAPPDATA%\Serato\Library\master.sqlite) nie czytaj bezpośrednio — tylko kopię (z -wal), tylko do odczytu,
bez kolumn ze ścieżkami plików (`file_name`, `portable_id`, `location_id` i podobne). Nie otwieraj ~/.scan2play/zagrane.properties ani
mojej bazy scan2play do zapisu. Testy tylko w kopii repo (CLAUDE.md), nigdy w nim.

Wynik: dopisz sekcję „Próba po review P3 (data)” na końcu docs/history/review-2026-11-22-p3-serato-play-count.md — każdy punkt
1–7: zrobione / wynik / co widać; pytania z tego review zamknij albo zostaw z odpowiedzią. Jeśli coś nie działa: najpierw opisz
scenariusz i przyczynę w kodzie (plik:linia), poprawkę zrób dopiero po mojej zgodzie, z testem widzianym najpierw na czerwono.
Zaktualizuj SESSION_HANDOFF.md (jeden wpis, całość ≤ 15 KB). Odpowiadaj krótko, po polsku.
