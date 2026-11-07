> Part of `PROJECT_CONTEXT.md` (the index, at the repo root: what is in each part) — Section 5.1 (cont.). Keep it to what is true now.

**The party opens and closes by itself** (V55, "Godziny imprezy"; the owner, 2026-10-30): the first card of "Ustawienia imprezy", for
whoever may open and close the party (OPEN_CLOSE — the organiser, and the staff given it), any plan. "Otwórz o" and "Zamknij o", each a
day and a time in Polish time, either empty; quick choices "dziś 20:00", "jutro 20:00" / "dziś 23:00", "jutro 3:00" fill the fields from
the server's today (`schedule.js`); "Zapisz" and the card's "✕" are sent in the background — the server answers with the card alone
(the fragment `settings :: partyHoursCard`), its line and body take the old ones' place and the page does not move (a page load scrolled
to the card: the screen jumped; without the script it still is one) —; as every card, "✓" on its small "Zapisz" for a moment, or "✗"
and why in the red line under it
(`PartySchedule.Problem`: half a moment, a moment past or more than a year ahead, both at once, or one that would change nothing — "Otwórz
o" while the party is open with no close before it, "Zamknij o" while closed) with the fields as typed. A time the clocks skip in spring
is the same time after the change; one they go through twice in autumn the first. "Otwórz o" always on top, "Zamknij o" under it (the
folded line names the next moment); a moment set beside its "✕" (a form of its own, so Enter saves). "Teraz impreza jest otwarta /
zamknięta" follows "Zakończ / Wznów" pressed on that page, and with a moment planned the page loads again (the manual change may have
dropped one; `common.js`). The panel shows each planned moment under the heading, the next first — "⏰ Zamknie się jutro o 3:00 ✕" (the words lead to
the card, "✕" cancels in the background); the poll's `X-Party-Schedule` hides one no longer planned. The "party closed" banner says
what the guests read meanwhile ("Goście … zobaczą „Prośby od 20:00”"), its plain words back once the open is gone. **A manual change against the plan
wins until the next planned moment** (the owner's rule): "Zakończ" drops a planned close that comes before the next open (it has
happened), the open stays; "Wznów" drops the planned open, the close stays; a moment that would change nothing is never kept
(`PartySchedule.settle`). **The job** (`PartyScheduleService.applyDue`, every minute at :00, `Times.SCHEDULE_ZONE`): one `UPDATE … WHERE
next_open_at <= now OR next_close_at <= now` over at most 500 rows found by the partial indexes, picked once in a CTE `FOR UPDATE SKIP
LOCKED` (a row a DJ or another instance holds is taken next minute; as `id IN (SELECT … LIMIT …)` a nested-loop plan ran the subquery
again per row and the limit was not kept — `PartyScheduleIT.aRun_isBounded` forces that plan), both due → as the later left it; through `PartySettingsCommandService.applyDueSchedule`,
which empties the cache after the commit (`PartyScheduleIT`, also two runs at once). **The guests** of a closed party with a planned open
read "Prośby od 20:00" (today), "Prośby od soboty, 20:00" (within six days), "Prośby od 06.11, 20:00" in place of "DJ nie przyjmuje teraz
próśb", in their language — on `party_ended.html` and the TV screen (both languages there). Not built: a weekly plan for clubs (days and
hours — later, a migration of its own that writes the next moment into the same columns); closing by itself after hours without requests
(it would fit: a job that sets `next_close_at`, or closes at once, through the same service).

**"Motyw dla gości"** (V59, `party_settings.guest_theme`, `GuestTheme`; the owner, 2026-11-04, variant A′ of the analysis
https://claude.ai/artifact/T8uNK1tcQm4nynE53DK1uU): the look of the guests' pages — the party page, the result, the "party ended" page,
the kiosk and the TV screen; the panel, the hosts' page and the print keep theirs. The second card of "Ustawienia imprezy", for whoever
may change the vibe (VIBE — the organiser, a co-organiser), any plan. Five themes, every one dark (a dark hall; the templates keep
Bootstrap's dark classes, so the light theme of "Later F" is not needed): **Klasyczny** (as before, the default), **Świąteczny** (navy,
ice blue, snowflakes and baubles, the serif), **Straszny** (Halloween: almost black, a violet mist from above and a dark red one from below,
pumpkin orange, a drip with a small ghost, a bat and a spider, the DJ's name in dripping capitals — Nosifer), **Konfetti** (any party, a birthday: lilac,
confetti, a gold glow), **Weselny** (plum black, powder pink, a sprig, the serif). Each theme a small phone in the card, drawn by the
theme's own tokens (`data-guest-theme` on the thumbnail), its name under it; a tap saves at once (the radio's `data-auto-submit`,
`cards-in-place.js`: the server's card in place, "✓ Zapisane", the focus kept on the theme; without the script a "Zapisz" in `<noscript>`);
"Podejrzyj stronę gościa" opens `/p/{code}` in a new tab. The print's editor says the guests' theme under its own ("Strona gościa: Straszny ·
zmień" → the card): the two are set apart — six of the print's eight themes are light paper, the guests' pages must stay dark.
**What a theme changes** (`static/css/guest-themes.css`, loaded after `app.css` / `tv.css` only when the party has a theme —
`components :: guestThemeCss` —, `<body data-guest-theme="SCARY">`): the colour tokens (the page, the card, the edges, the two texts, the
action and its tints — the page's glow follows —, the TV's), one ornament (our SVG, `static/images/themes/`) in a band of 56 px at the top
of the page — the card comes 36 px lower; none in the kiosk, a band in the TV's top padding —, the face of "🎧 Gra: …" (`#djName`,
`.tv-dj`; our fonts, `font-display: swap`: the serif themes the whole line, the scary one the DJ's name alone — `components ::
djNameText`, a span marked `s2p-dj-long` over 16 letters, `s2p-dj-longer` over 26, set smaller and moved under "Gra:" whole), and the
fields — dark (the page's background, the text's colours, an edge in the second text's colour, the accent's ring when typed in):
Bootstrap's white field was the brightest thing on the page. **What it never changes:** the statuses — the green "Twoja", the yellow 👍, the red "NIE" —
and no accent may look like them (a Christmas red or gold, a summer coral did; the red of Świąteczny is in a bauble only), the logo's
cyan "2" (ours), the layout, 44 px, one action, no animation. `GuestThemesCssTest`: a block sets only tokens `app.css` / `tv.css` have
(and its own `--s2p-ornament`, `--s2p-theme-font`), every theme the whole set, no colour outside the blocks, WCAG AA (the texts on the card
and on the page, the action's words, the accent as a text, the TV's), CIEDE2000 ≥ 20 between the accent and each status. Browser
scenarios `guest-theme-*` (each theme on a phone: the ornament above every text, the contrast of the computed styles, the statuses'
colours, 44 px, one action, no animation, the face loaded; the classic page as before; the result, "party ended", the kiosk in one screen,
the TV; the card). Not built: the DJ's own colours (later, as Pro, on the same tokens — a contrast fix and the statuses' hues kept away),
a theme by the date (a surprise the DJ did not pick), a photo behind the page, a summer theme (no hue far enough from the statuses and the
classic cyan yet).

