> Part of `PROJECT_CONTEXT.md` (the index, at the repo root: what is in each part) — Section 5.1 (cont.). Keep it to what is true now.

"Zbieraj prośby gości" → `/start` → Google's login → `/dj/dashboard` (`DjSessionHelper.getPartySettings`
creates the DJ's party on the first visit; the old tile links `/start/{kind}` lead to the login too). The dashboard: the guest queue
(polled every 3 s; sort, search, a number per request) with "Oznacz jako zagrane", "Pomiń" (`POST /dj/dashboard/dismiss`: the request
leaves as rejected with `skipped_at`, keeping the AI's comment — the history says "POMINIĘTE" in place of the verdict (on a wide screen every verdict's word as wide as the longer of "ZAGRANE" /
"POMINIĘTE" — hidden copies in an inline grid, `.s2p-decision-word` —, so the "↩ Przywróć" after them stand in one column; amber, not the red "ODRZUCONE"; "⏭ Pominięta przez DJ-a …" in its title; on a phone only ⏭, beside the votes; still
under the "Rejected" filter), V23; "Cofnij" for 8 s, "↩ Przywróć" in the history — Section 4.1; "Zagrane" has both too, 2026-10-10), "Kopiuj" (the artist and the title for the search
box of the DJ's software) and "Podejrzyj" on every waiting request, and on a computer the queue's keys — "Skróty klawiszowe", each key drawn as a key
(`<kbd>`) with one word: ↑ ↓ wybierz · C kopiuj · Enter zagrane · S pomiń · Backspace cofnij · Esc odznacz, under the list (`queue-keys.js`,
2026-10-12; the legend 2026-10-16; Ctrl+C / Ctrl+Z work too, the guide names them; the staff without the queue's buttons: only ↑ ↓ / C /
Esc); the vibe, the vibe note, "Kto gra";
the QR code (`/dj/qr-print`: an A4 poster, table cards (eight on a sheet) or a **table tent** (V50: one A4 sheet folded across its
middle — the fold printed dashed —, two faces of the same code and words, the upper one turned upside down, so the tent reads from both
sides of a table; each face as a card larger, the code 78 mm, 72 mm with the DJ's logo; a tent a page); **the number of tables** (V50,
"Liczba stołów", 1 … 50, empty = none): as many cards and tents as tables, numbered "Stół / Table 5" in the print's language — on a
card in its top corner, on a tent's face above the heading —, a reprint the same set; without it eight cards and one tent; the poster
has none; "Numer pierwszego stołu" numbers them from it (22 from 22 — tables 22 … 43; a reprint of one — 1 from 16; at most 999; in
the page only, of a form of its own so it never holds back "Zapisz", never sent nor saved — it goes along with the layout switch
(`poster-switch-keeps-first-table`, `…-takes-…`), a reload starts from 1), and a line
under it says what prints, "Na wydruku: stoły 22–43 · strony: 3" (scenarios `poster-tent-layout`, `poster-tent-longest-fits`, `poster-tables-cards`, `…-tents`, `…-cards-fit`,
`…-from`, `…-from-tents`, `poster-tent-phone`); **the print as the DJ sets it** (V46) — an editor over the paper, for
whom may change the card "Impreza" (VIBE), any plan: the DJ's own heading (≤ 40) in place of "Zeskanuj i poproś o piosenkę / Scan to
request a song", one line of the DJ's own (≤ 60, "Osiemnastka Dawida"), the language — "Polski + English", "Polski", "English";
until the DJ picks one (`AUTO`, the default) the panel's is printed and picked: a Polish panel Polish and English at once, any other
English alone — and boxes for what of the DJ's it shows (the
logo, the profiles, the tip, the address with the party's code; a box only for what the party has) and **the look** (V47): a theme —
"Klasyczny" (white, as before), "Elegancki" (cream, for a wedding, a serif heading, a double frame), "Klubowy" (dark, the DJ's logo on a
white badge), "Pastelowy" (soft pink, rounded) and, V52, "Rustykalny" (kraft, olive, a stitched frame with a line inside — a barn
wedding), "Gala" (white, navy and gold: a navy heading and band, a gold line inside it and gold diamonds — a ball, New Year's Eve),
"Neon" (white, a violet heading, a frame in a gradient pink → the heading's colour → cyan — the club's colours without its ink),
"Konfetti" (a warm cream, coloured dots in the frame's band only — a birthday); the rustic and the gala headings in the serif as the
elegant one; the lines inside a frame are outlines (a box-shadow took the place of the preview's white page margin); every two
themes apart at a glance — at least two of the paper, the heading's colour and the kind of frame differ (`poster-themes-apart`: the
elegant and the gala, the pastel and the neon were alike); the decoration never under the words or the code (`poster-themes-more`); the
themes' scenarios take every theme of the editor, so a new one is checked by itself; **"Kolor tła"** (V53): the theme's paper, one of six
light tints or the DJ's own — printed as picked (two shades alike print alike), the words on it black or white, whichever reads
better (always at least 4.5:1; `body.paper-own`), the code on its white tile, the DJ's logo on a white badge on any paper of
the DJ's (one shape while the DJ tries colours — `poster-paper-logo-steady`), the classic paper coloured with the words 8 mm off its edges (`poster-paper`, `…-cards-tent`) — and the colour of the heading and the frame: the theme's, one of eight, or the
DJ's own (the browser's colour field); the editor's colours are `PosterColours` (the paper's six, the heading's eight), each told
by its name to a screen reader and in its title, never by its code (`poster-colours-named`, `PosterColoursTest`), and "Własny" opens
the colour field from its whole chip. The theme is `<body>`'s class (`qr-print.css`, `--paper` / `--ink` / `--muted` /
`--accent`, its fonts `--heading-font` / `--line-font` — "Krój nagłówka" sets the heading's over it; a new theme is one block of
tokens and, if it has one, its frame); the DJ's colour prints as picked — the DJ decides; a heading that stands out poorly from its paper (under 3:1, WCAG's
least for large text) is said under the colours ("Nagłówek słabo odcina się od tła"), never changed (`qr-print.js`;
`poster-accent-exact`; while the DJ drags in a colour picker the preview follows and the note waits for the colour picked — shown
and hidden under the open picker, it moved the editor and the browser closed the picker, `poster-accent-note-steady`); the theme's own colours read on its paper — browser scenario `poster-themes-readable`: every theme with white,
a pale yellow, black and a light cyan. The code
stays black on white (its own white margin is its tile) in every theme; every theme fits A4 and the cards with the longest words
(`poster-themes-fit`, `poster-themes-cards-fit`, also with DejaVu Sans, GitHub's font). **The print's own fonts** (`static/fonts`,
the SIL Open Font License beside them, ~150 KB): "S2P Sans" (Source Sans 3) on the paper in every theme, "S2P Serif" (Playfair
Display) for the elegant headings and the DJ's line on the poster and a tent (on a card the line stays in the sans: 60 letters of
the serif do not fit its column) — variable fonts, the Latin and the Latin Extended halves apart, `font-display: block`; the bar and
the editor keep the device's fonts, as the panel. So the paper lays out the same everywhere and the scenarios measure what prints
(`poster-fonts-ours`). **Balanced lines**: a heading or a line of two or three lines is balanced (`text-wrap: balance`; the pictures'
`qr-social.js` `balance`, the narrowest width keeping the lines' number) — filled as far as each went, a word was left alone below
("Poproś o piosenkę dla Młodej" over "Pary", the owner's screenshot); and a one-letter word ("i", "o", "w", "z", "a", "u") never ends a
line — the space after it non-breaking in what is printed and drawn, ours too (`typeset`; the field keeps what was typed) —
`poster-heading-balanced`, `…-many`, `…-cards-tent`, `…-story`, `poster-heading-one-letter-words`. **The DJ's heading and line fitted** (`qr-print.js`): a long one made smaller half a point at a time until
whole in its lines — two for the poster's heading, three elsewhere —, no smaller than its least (the poster's heading 16 pt, a card's
7 pt, a tent's 12 pt); a short one keeps the stylesheet's size; measured on the first card or tent, the same size for all; on a phone
too — **a phone's preview** is the sheet laid out as it prints (186 mm wide) and made smaller to the screen with `zoom`
(`fitPreview`; laid out again for the narrow screen, the code covered the frame — `poster-phone-preview`, `…-cards`; a tent's sheet
with its white page margin, centred — `poster-phone-preview-tent-centred`); the line clamp
stays the last guard (before, the longest words were cut on a card and a tent — `poster-heading-fitted`, `…-tent`, `…-poster`). **"Krój
nagłówka"** (V51): the theme's (the default), "Bezszeryfowy", "Szeryfowy" or "Odręczny" ("S2P Script", Dancing Script, OFL) whatever the
theme — the heading only (`body.font-*`), the DJ's line and the code's texts stay in the theme's; the script's small letters brought to
the sans's height (`font-size-adjust: 0.49`) and its least sizes its own (a card 5.5 pt, a tent 11.5 pt: its letters are wider); every
font in every theme fits A4, a card and a tent with the longest words (`poster-font-choice`, `poster-font-fits-a4`, `…-cards`,
`…-tent`); the switch keeps it; the Instagram pictures draw the heading in it. **"Rozmiar nagłówka"** (V54, under "Więcej
wyglądu"): "Mniejszy", "Zwykły" (the default) or "Większy" — every heading's size times `--heading-scale` (0.85, 1, 1.2; `body.size-*`),
ours too, the pictures' heading as well; a long one still fitted to its lines, so the larger with the longest words in every theme and
font fits A4, a card and a tent, the poster's code at least 100 mm (`poster-size-choice`, `poster-size-large-fits-a4`, `…-cards`,
`…-tent`, `poster-size-story`, `poster-print-pdf-large`). **"Białe tło pod logo"** (V70, under "Pokaż", with a logo only):
"Automatycznie" (the default: on the club's and the DJ's own paper), "Zawsze" (a logo with a white background of its own then a
rounded badge on any paper) or "Nigdy" (`body.badge-*`); the badge's room is the logo's on every paper (`.dj-logo` padding), so the
choice and the paper only colour it — the logo never changes size or place, never touches the code; on a card the logo itself is
at most 37 × 12 mm, the badge's room within the column's 40 × 14 mm; the choice hidden with the logo left off (`poster-logo-badge-choice`,
`poster-paper-logo-size`, `…-cards`, `…-tent`); the pictures draw it as the print has it (`poster-social-logo-badge`). A part of a card or a tent nearer than 3 mm to its edge
(the cut, the fold) is said under "Pokaż" (`#posterTightNote`, `qr-print.js checkMargins`, the preview's zoom taken out; a card's
table number 3 mm in from its corner as well), never changed. Every card the editor makes keeps its parts 3 mm in: with the
DJ's logo and all three profiles the code is 33 mm (36 mm would take the profiles 2 mm from the cut)
(`poster-cards-tight-note`, `…-phone`, `…-none`, `poster-tight-note-not-on-the-poster`, `poster-cards-code-with-three-profiles`). The cards' cut: a 5 mm mark at every
corner (crosses where the cards meet, for a trimmer) in the paper's `--muted`, and a light dashed edge for scissors — all of it the
card's background, no border (`poster-cards-cut-marks`). "Działa na" and our small mark on one
line, our big mark apart from the code's tile in every theme (`poster-themes-powered-aligned`). What is typed or ticked shows at
once (`js/qr-print.js`: a part left off is rendered `hidden`, `data-poster-part`; the DJ's logo left off brings ours back);
"Zapisz" keeps it with the party in the background (`POST /dj/qr-print/settings`, 204); a new language is saved and the page loads
again (the server writes it). Changes not saved say "Niezapisane zmiany" and go along to the other layouts (the switch "Plakat A4 |
Kartki na stoliki | Namiot na stół" keeps them in `sessionStorage` for the tab, the next page takes them — scenarios `poster-switch-keeps`,
`…-takes`; on a phone the switch spans the bar, a long name on two lines); leaving the page with them asks first (`beforeunload` —
never on the layout switch nor on the reload after a new language, which saved them: `poster-unsaved-asks-before-leaving`). The
address a guest types from paper is printed without "https://" and the party's code in it never breaks across lines (the fragment
`printedLink`, `.link-code`; on a card's narrow column the address broke anywhere, "…/p/P" over "RNT1" —
`poster-address-code-whole-cards`, `…-cards-logo`, `…-tent`, `…-poster`). The page works without `Element.checkVisibility` (Safari
before 17.4: the script threw at load and the editor did nothing — `poster-editor-without-check-visibility`, the harness's `without`).
**The poster is its A4 page** (`.poster`, `qr-print.js` `fitPoster`): 186 × 272 mm on the screen as on paper — the theme's frame down
to the page's foot —; the room its words and parts leave goes to the code, larger up to 140 mm (our words alone: 140; the longest
words, the DJ's logo, three profiles and the tip: still ≥ 100), what is left centres them (`poster-fills-the-page`, `…-longest`,
`poster-preview-a4-wide`), 2 mm left to spare; fitted again when a font loads late (the script first picked: the code took the
fallback's room and the poster printed two pages — `poster-fills-the-page-font-late`). **The print itself**: `run.py`'s `pdf` prints a
scenario's page (DevTools `Page.printToPDF`) and counts its A4 pages — the poster, the cards, the tent with the longest words in a
theme, 22 cards, 3 tents (`poster-print-pdf*`, a control told one page for two). A computer's column narrower than the sheet makes the preview smaller (`--preview-zoom`, used by the
stylesheet on the screen only — never on paper; `poster-preview-a4-laptop`), as a phone's (`fitPreview`; its margin is
`--preview-margin`, screen only too: on the sheet itself it printed the cards off the page's middle from a phone).
**"Edytuj | Podgląd"** (`#posterModes`, below 1100 px): the editor or the preview, one at a time, the switch held at the top; the page
opens on the preview (what prints). The preview away while editing stays laid out (fixed, hidden), so the fitting measures it and the
print has it (`poster-phone-modes`, `…-fitted`, `poster-modes-wide`); without the script, the editor above the paper as before.
On a wide screen (≥ 1100 px) the editor is a panel on the left (Treść, Wygląd, Na wydruku), the page beside it on a dark desk; the bar and
the editor in the panel's colours (a card's #161616, a group #1e1e1e, black fields — 2026-10-28, `poster-editor-panel-colours`), only
the paper light. **Helpers** (2026-10-29), of a form of their own (`#posterHelperForm`: never sent, never saved, never "Niezapisane
zmiany" by themselves): "Wzór na okazję" — the first field — six occasions (wesele, osiemnastka, urodziny, klub, pub, impreza firmowa), each
text in the print's language (Polish and English at once "Piosenka dla Pary / Song for the couple"), all within 40 (`QrPrintPageTest`) —
fills the heading as if typed and sets its look: the theme, the heading's font, the colour (none: the theme's) and the theme's paper
(the wedding Elegancki with the script, the 18th Neon, the birthday Konfetti, the club Klubowy, the pub Rustykalny, the company
Klasyczny in navy and the sans); the list shows the occasion picked (on a phone the preview is out of sight) until the DJ changes
by hand one of the things it set — the heading, the theme, the font, the colour, the paper —, then it is back at its prompt, so the
same occasion can be picked again (`poster-presets`, `…-both`, `…-look`). **Fewer fields to start**:
"Treść" is the occasion, the heading, "Druga linia" and the language; the paper, the heading's font and its colour are folded under
"Więcej wyglądu" (`#posterMoreLook`, open when the party has one of them set; the weak heading's note outside it); the tables and the
first table's number are with what is on the print, on the cards and the tents only (on the poster hidden, still sent —
`poster-editor-fewer-fields`, `poster-editor-tables-on-cards`); no note about the ink of a dark sheet (obvious;
"Kolor tła"'s hint says a dark colour prints dark). **"Do internetu"**
(`js/qr-social.js`): a PNG story 1080 × 1920 or post 1080 × 1350 drawn on a canvas in the page from what the preview shows, unsaved
changes too — the logo, the heading, the line, the code on a white tile, the party's code large and the address (nobody scans the screen
they hold), the profiles (never broken across lines), the tip, "Działa na" with the DJ's logo; the theme's paper, ink, colour and frame
as on paper (the double, the rounded, the stitched, the gala's band, the neon's gradient, the confetti's dots — `poster-social-frames`),
the party's code in the print's font; a story's words, logo and code between Instagram's bars, 250 px at its top and foot
(`poster-social-story-safe`); the DJ's logo on a white badge on the club paper; too much, and every size shrinks until it fits. A phone that can share files opens its
share sheet, anything else saves `scan2play-<code>-story.png`; nothing is sent (`poster-social-story`, `…-post`, `…-longest`: every theme
with the longest words, the DJ's logo, three profiles and the tip inside the picture, the code ≥ 420 / 340 px). The longest words still fit: browser scenarios `poster-editor-longest-fits-a4`, `…-cards` — the
DJ's heading 26 pt, the line 15 pt, the code 100 mm while there is a line); the
history; "Zakończ / Wznów imprezę" (in words — "Zakończ" on a phone —, beside the account's menu) and the menu "Konto" — about the person and their help: "Instrukcja" (the public guide below), "Pomoc i kontakt" (2026-10-12: one window with
the address contact@scan2play.com.pl — `contact.email`, as every public page; forwarded to the owner's Gmail by Cloudflare — and "Kopiuj" — `dj-nav.js`, as every "Kopiuj" of the DJ's pages —, the guide, and the
form of the old "Zgłoś uwagę", `POST /dj/feedback`), "Wyloguj",
"Usuń konto" (red, the last one) — in place of four icons without words (the design review, 2026-10-09; `.s2p-account-bar` keeps the open
menu over the sticky tab bar). What a DJ sets before a party is on the page **"Ustawienia imprezy"** (below). The forms are sent in the background (`forms.js`; not logout and account deletion); a song played or skipped leaves the list at once (`s2p:guest-queue-changed` → the queue is fetched again). **The forms tell the truth** (the review of 2026-10-14, W1 / W11): a form the server refuses (the session ran out — a 403 for the CSRF token —, the plan, the access) or that never reaches it says ✗ on its button and why — its own note (`[data-form-error]`, the DJ's profiles and tip link) or the page's notice `components :: formRefused` ("Nie zapisano. Sesja mogła wygasnąć albo zerwało się połączenie: skopiuj wpisany tekst i odśwież stronę.", "Odśwież stronę", ×; on the panel and the settings) — never ✓; the page does not reload by itself, so what was typed can be copied; "Zakończ imprezę" refused leaves the panel open; a refused "Cofnij" shows the notice too. The button that sent a form waits for the answer (`disabled`), so a double tap sends once (two taps on 💸 counted two tips); a form sent without a button (the vibe's select) is not held back. **A poll that keeps failing** (three in a row: no network, a 5xx) shows "Brak połączenia z serwerem: lista może być nieaktualna." above the queue (`[data-poll-offline]`, `polling.js`, W12); the next answer hides it. An answer without the queue (`#song-list`; a
Wi-Fi login page) counts as failed and leaves the queue as it was. A poll that says the page must load again (403, the login page,
the access changed) loads it once in a run of such answers (`sessionStorage` `s2p-poll-reloaded`; a good answer starts over) — then
the notice says "Nie udało się wczytać…" instead of reloading every 3 s (review 2026-10-14, M14). The notice has that second text
(`[data-refused-text="load"]`, `common.js` `showRefused('load')`) for a History tab, filter or "Pokaż więcej" that did not load too.

