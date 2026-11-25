# Prompt do nowej sesji: review „Zagrane samo” ze Spotify (2026-11-25)

Do wklejenia w nową sesję Claude Code (w chmurze albo lokalnie):

---

Przeczytaj CLAUDE.md (z AGENTS.md) i „Start here” w SESSION_HANDOFF.md, potem w docs/context/05-1d-panel-heading-queue.md opis
programu `deck-agent/` („The program, `deck-agent/`”) i akapit „Spotify in the program”, oraz punkty 19 i 20 w docs/ideas.md.
Gałąź: `claude/gracious-bell-iap7ms` (od `main` = `0316624`), PR-y do `main` i `dev`. Zmiany: `git diff origin/main...HEAD`.

Co zostało zrobione: program na komputerze DJ-a („Scan2Play Zagrane”, `deck-agent/`) czyta oprócz historii VirtualDJ i Serato
także to, co gra aplikacja Spotify (`SpotifyNowPlaying`): na Windows tytuł okna Spotify przez jeden długo żyjący PowerShell, na
Macu AppleScript (`osascript`; workflow dopisuje do `Info.plist` `NSAppleEventsUsageDescription`). Piosenka słyszana 30 s jest
zagrana od chwili, gdy ją pierwszy raz usłyszano. Serwer bez zmian. Spotify wymienione tylko tam, gdzie program jest opisany
(karta w panelu, przewodnik `#deck` PL / EN, polityka prywatności PL / EN) — celowo nie w „Nowościach”, e-mailu z nowościami ani
na stronach reklamowych (regulamin Spotify zabrania grania publicznie). W oknie programu link „Inny folder historii…” zamieniony
na „Twojego programu DJ-a nie ma na liście? Wskaż jego folder…” z podpowiedzią, który folder wskazać. Sprawdzone przez właściciela
na Windows (produkcja): odczyt tytułu, oznaczenie po ~30 s, pauza („nic nie gra”), prośba dodana do grającej już piosenki. Mac
niesprawdzony.

Zrób review tych zmian i zapisz je w `docs/history/review-2026-11-25-spotify.md` (punkty P1, P2, … — każdy: co, gdzie
`plik:linia`, dlaczego to problem, propozycja poprawki, waga: błąd / ważne / drobne). Oceń cztery rzeczy:

1. **Czy kod jest dobrze zrobiony.** `SpotifyNowPlaying.java` i jego miejsce w `Platform.sources()` (jedna instancja na cały
   czas działania), zgodność z `Agent` (wersja, „seen”, pierwszy odczyt, 10 minut przed startem), wątki (`version()` z wątku
   agenta, czytnik PowerShella na swoim wątku, pola `volatile` / `synchronized`), cykl życia PowerShella (start tylko przy
   działającym `Spotify.exe`, koniec ze Spotify albo z programem — czy może zostać sierota, czy dwa naraz), koszt na laptopie DJ-a
   (`ProcessHandle.allProcesses()` co 10 s, PowerShell co 2 s, `osascript` co 3 s), kodowanie (UTF-8, BOM), rozbiór tytułu okna
   (reklamy, pauza, „Artist - Title - Remastered”, tytuł Spotify w innym języku Windows), skrypt AppleScript (czy nie uruchamia
   Spotify, gdy nie działa; reklama `spotify:ad`), krok `plutil` w `.github/workflows/deck-agent.yml` (przed `codesign`). Testy:
   `SpotifyNowPlayingTest` — czy pokrywają reguły (30 s, pauza, ta sama piosenka zaraz po sobie, przerwa > 10 s, Spotify
   wyłączone, agent wysyła raz); czego brakuje. Uruchom: `sh mvnw -f deck-agent/pom.xml -B -ntp verify` w kopii repo (w chmurze
   `mvn` z `/opt/maven` w kopii, wrapper może nie mieć sieci).
2. **Czy zmiany są profesjonalne i spójne z projektem.** Komentarze „dlaczego”, nie historia (AGENTS.md); styl i nazwy jak w
   reszcie `deck-agent/`; dokumentacja: `docs/context/05-1d-…` (czy opisuje to, co jest teraz), `docs/ideas.md` punkt 20,
   `SESSION_HANDOFF.md` (limity długości z jego nagłówka); `messages*.properties` (escape'y `\uXXXX`, bez literalnych znaków spoza
   ASCII); polityka prywatności — czy nowe zdanie jest prawdziwe i pełne (co program czyta, co wysyła).
3. **Czy wygląda profesjonalnie i spójnie.** Okno programu: zrzuty w xvfb (`xvfb-run`, motyw z `Main.theme()` przez refleksję —
   klasa pomocnicza tylko w kopii repo, nie w repo) w stanach: Spotify gra, Spotify pauza, nic nie czytane, długi tytuł; czy nowy
   link nie jest za długi i nie poszerza okna; podpowiedź. Panel (karta „🎧 Z programu DJ-a”) i przewodnik `#deck` w przeglądarce
   (`python tools/check.py audit -t /dj,/jak-to-dziala,/how-it-works` albo zrzuty 390 i 1280 px).
4. **Czy jest czytelne i łatwe dla DJ-a.** Przeczytaj teksty oczami DJ-a, który pierwszy raz widzi program: czy wie, że Spotify
   działa i od kiedy liczy się piosenka (30 s), co znaczy „Spotify (nic nie gra)”, kiedy i który folder wskazać, co jeśli Spotify
   jest schowany do zasobnika (nic nie słychać — czy gdzieś to powiedziano?), czy teksty PL są naturalne i bezosobowe jak reszta
   programu (`WindowTest.theTexts_areImpersonal`), czy EN odpowiada PL.

Na koniec: krótkie podsumowanie w czacie (najważniejsze punkty, rekomendacja: scalić / poprawić najpierw), a poprawki zrób dopiero,
gdy powiem które — commit i push na tę samą gałąź (w chmurze), lokalnie zostaw niezacommitowane.
