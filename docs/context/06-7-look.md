> Part of `PROJECT_CONTEXT.md` (the index, at the repo root: what is in each part) — Section 6.7. Keep it to what is true now.

### 6.7 The look (`app.css`, the design review of 2026-10-09)

What every page shares, so a new screen looks like the others:

- **Three kinds of button.** The action — `.btn-action`, the cyan of the logo's "2" with dark text, **one action per view** ("Wyślij
  prośbę", "▶ Zagrane", "Zaloguj się przez Google i dołącz", "Zapisz listę", "Zaproponuj kolejną", "Drukuj"). The owner (2026-10-15):
  the same action repeated is one — on every item of a list ("▶ Zagrane" of each request), at the top and the bottom of a long page
  (the landing page); a window over the page (the feedback's "Wyślij") and the "Cofnij" bar are views of their own, with their own.
  Two different `.btn-action` in one view — never: a closed party's action is "Wznów imprezę", so the empty queue's "Drukuj" is an
  outline while the banner shows it (`app.css`, `:has`; `closed-party-one-action`). The second kind — an outline
  (`btn-outline-light` / `-secondary`, the picked one of a group filled light): "Pomiń", "Zapisz" in the settings, "Podejrzyj", the
  tip (`btn-outline-warning`: money); the text one — `btn-link` ("↻ Odśwież", "−1"). What deletes is an outline in `--s2p-danger-text`,
  never filled red. Green (`btn-success`, dark text) and yellow are statuses — "Twoja", a given 👍, ⚠, 💸 — not actions.
  Under a pointer that hovers the action rises 2 px with a cyan glow (`--s2p-action-glow`; none on a phone, none with
  `prefers-reduced-motion`), and the logo that leads home (`.s2p-home-logo`) turns the accent, its mark ringed in it (the owner,
  2026-10-24; `AppCssHoverTest`).
- **Colours** as tokens on `:root`, nowhere else in `app.css` (`AppCssColoursTest`; 2026-10-15 every colour written in a rule became a
  token, 2026-10-16 the near-identical tones merged — the owner: three light texts `--s2p-white` / `--s2p-light` / `--s2p-text`, one
  panel background `--s2p-bg-elevated`, two edges, one yellow; `--s2p-<colour>-<n>` is that colour at an alpha of n %): `--s2p-action`, `--s2p-text-muted` (#a3acb6, 7 : 1 — Bootstrap's grey text was 3.3–4.2 : 1, under
  WCAG AA; `.text-secondary` / `.text-muted` use it), `--s2p-danger-text`, `--s2p-green-ink`. **One accent**: what is picked or
  done (the lit tab and its line, the line under a table's head, a sorted column, a suggestion under the pointer, a text button
  `.btn-link`) is the action's cyan; green is only the status "accepted" / "Twoja" (2026-10-28; browser scenario `one-accent`,
  `queue-empty-guide-readable`). **One font**: `--s2p-font` is the device's own, named — `-apple-system`, `BlinkMacSystemFont`, Segoe UI,
  Roboto, Helvetica Neue, then Arial (the TV the same) — the old list fell to Verdana on an iPhone (`one-font`). Not `system-ui` first:
  on a Linux without those fonts (GitHub's runner) it is DejaVu Sans, a tenth wider than Arial, and three one-line layouts broke. Messages (`.alert-*`) are a tint of their
  colour with a coloured edge and light text — Bootstrap's pale boxes were the brightest things on a dark page.
- **The background** of every page of `app.css`: `--s2p-bg` with a faint glow of the action's cyan from the top-left corner
  (`--s2p-action-20`) and the bottom-right one (`--s2p-action-12`) — `body::before`, a layer fixed to the screen under the page (the
  glow stays while a long page scrolls; `background-attachment: fixed` is ignored by Safari on an iPhone), sized by the screen, not
  printed. The cards keep their own background over it; the muted text keeps 6 : 1 on its strongest tint. The look of the Instagram
  pictures. The TV (`tv.css`), the summary and the QR print have their own.
- **The guests' themes** (V59, "Motyw dla gości", 5.1): a theme redefines tokens only (`guest-themes.css`, `[data-guest-theme]`) — the
  page, the card, the edges, the texts, the action and its tints, the TV's — and adds one ornament in a band above the card and a face for
  "🎧 Gra: …". Every theme is dark. The statuses (green "Twoja", yellow 👍, red "NIE"), the logo's cyan "2", the layout, 44 px and one
  action are the same in every theme; the accent is the theme's one accent, never a status's hue (`GuestThemesCssTest`). A new token of
  the guests' pages that a theme should follow: add it to every theme and to the test's set.
- **Sizes.** Radius 8 px for buttons and fields, 12–15 px for cards. On a phone (narrower than 768 px, or a touch screen) every button,
  field, tab and menu item is **at least 44 px high** (browser scenarios `guest-touch-targets`, `dashboard-touch-targets`).
- **Cards** of the settings: `.s2p-card-title` and one line of `.s2p-card-help`, then the fields, then one "Zapisz". The help line
  says once who sees what ("Goście widzą klimat, opis i to, kto gra.") — the labels are short ("Opis klimatu", "Kto gra"), never "(widzą
  goście)" after each. Nothing explains what the DJ does not set (the server's limits: one line, what they are unfolded).
- **What goes where** (the panel's clean-up, 2026-10-10): the panel holds what the DJ uses during a party (the queue, the vibe, "Kto gra",
  the QR code); what is set before it is on the page of the tab "Ustawienia" ("Ustawienia imprezy", "Obsługa") — never a card more on the
  panel, nor a second way to the same page (the rows of "Więcej", removed 2026-10-28). **Every page of the DJ's** (the panel, the
  history, "Ustawienia imprezy", "Obsługa") has the same heading and tabs (`dj-nav`, the panel's width) — no "← Panel" of its own; a page
  under another (for now "Obsługa" under the settings) lights its parent's tab and has one "← <parent>" line above its heading (2026-10-28;
  `settings-page-one-heading`, `history-page-one-heading`, `staff-page-way-back`). While a closed party's banner shows, the page's own
  action is an outline (`.s2p-dj-page-body`, as the empty queue's "Drukuj"). "Konto" is about the person and their help (the guide, "Pomoc i kontakt", logout,
  the account), not the party. A state that needs attention shows on the panel only when it does (a limit from 80 %, yellow). What a person
  may not do is hidden, not greyed. A list of alike things with the same fields (the people of "Obsługa") is folded to one line each —
  what tells them apart (the name, the role) — and unfolds on a tap. Browser scenarios: `settings-two-cards`, `dashboard-heading-one-row`,
  `settings-page-touch-targets`, `staff-people-folded`.
- **A list one works through on a phone** (the queue, the history; 2026-10-10) folds each item to what the DJ decides by and does most:
  the queue keeps its action row ("▶ Zagrane" one tap), the history its one action ("↩"); a state that needs attention stays in the
  folded item (⚠ with the guest's words, ⭐, "💸 2"). A tap on the item unfolds it, "▾" / "▴" (a button with `aria-expanded`) says
  which; a tap on a control is the control's alone; unfolded items survive the list being replaced (remembered by id). Not on a wide
  screen. A folded line keeps its signs in fixed columns (the history: the votes, the decision, "↩" — its column empty where there is
  none — and "▾", a grid), a badge as its sign alone with a gap from the title ("⚠" yellow, "🎧" plain), so the list reads down
  (2026-10-28). Browser scenarios: `queue-cards-folded`, `queue-card-open-survives-poll`, `queue-cards-viewer`, `queue-cards-wide-screen`,
  `history-lines-phone`, `history-line-columns-phone`.
- **A table on a wide screen** gives its width to what identifies a row (the song), never lets a title break inside a word, and puts the
  secondary words (the guest's, the AI's comment) in one muted line under it, the whole in the title; what is the same in every row is
  not a column; colour marks a state only where it tells something apart (a dot at the decision, ⚠, votes above 1) — the words stay
  quiet (the history, 2026-10-10; `history-wide-calm`).
- **A feature of the Pro plan** (V35, variant B of the review 2026-10-14; the look: the review of the same day,
  https://claude.ai/artifact/PPSwW3nDrRdWFcbM8hWAoN — the owner: the cyan tag, the tidy-up, the page of the product) to an organiser
  without it, while the plans are enforced: **visible, marked** — the row and the card keep their place with "PRO" by the name, **said
  once** (`components :: proMark`, `.s2p-pro`: a **tag** in the logo's cyan on a 12 % tint of it, 4 px radius — neither a pill like the
  jumps nor a button, never filled: it is not a control and does not compete with the screen's one action; 6.2–7.5 : 1). No state
  beside it in the row nor in the folded card's line (the limits' the limits that hold, "2 na gościa · 3 min", one line on a phone); in place of the card's form one sentence — what was saved and waits, or what holds — with "Zobacz,
  co daje Pro" → `/pro` (`components :: proOffer`, `.s2p-pro-box`: a box tinted with the cyan, a solid line — never dashed, that reads
  as an empty place; on a wide screen the sentence and its outline button on one line); a fact is said once (the staff's places: the
  offer, not the help line again); the other choices of a list are there but not to pick, under the list's own heading (`<optgroup label="W planie Pro">`, quieter —
  the comment styles "Zabawne", "Sarkastyczne", "Ciekawostki", "Krótkie"; never "· Pro" after each, it read as one more choice); "Podsumowanie wieczoru PRO" leads
  to `/pro`. **The page `/pro` is the pricing page** ("Cennik" / "Pricing", the owner's variant A of 2026-11-05; `/cennik` and
  `/pricing` redirect to it in their language — one canonical address): the plans Free and Pro side by side on a wide screen — Free
  "0 zł zawsze" with "Zacznij za darmo" (`/start`, a visitor only), Pro tinted with the tag "WCZESNY DOSTĘP" and "Napisz do nas" in
  place of a price, its one action at the card's foot ("Napisz, że chcesz Pro", `mailto:contact@…`, the address as text with "Kopiuj
  adres" — there is no payment, no price, no date) —, each Pro feature named with what it gives under it, "Porównanie" (a table, Free /
  Pro, in its own scroll box), "Dobrze wiedzieć" (what Pro will cost — not known yet, we write before anything is paid; what happens when
  Pro ends; the staff; the guests) (`.s2p-product-*`, `.s2p-plan*`, `.s2p-compare*`, `.s2p-faq`). **While the plans are not enforced**
  (the owner, 2026-11-06: a visitor's first question is the price) the same address shows one card instead — "Za darmo", "0 zł",
  everything a DJ gets today, "Zacznij za darmo" (a visitor only), and "Dobrze wiedzieć": "Czy tak zostanie?" (what a party needs stays
  free; if anything becomes paid, a notice in the panel and 30 days at no charge for the accounts then — the terms' section 5,
  `/terms#plans`), "Czy potrzebuję karty?", "A goście?" — no word of Pro (`.s2p-plans-one`; scenarios `pro-page-plans-off-*`); every
  sentence of it stays true after the switch. Linked whether enforced or not: "Cennik" beside the language at the top of the landing
  page and the pages "Dla kogo" (`#headerPricing`, `.s2p-top-link` — as quiet and as tall as the language's links; scenarios
  `pricing-at-the-top-*`, 320 px), the footer's "Cennik" on the site's own pages (`pricingLink` — `HomeController`, `ForWhomController`,
  `LegalController`; never on a party's), the landing page's "Ile to kosztuje?" ("Zobacz cennik →"); only while enforced the account
  menu's "Twój plan: Free · Cennik" (the organiser only, `PlanService.View.offered`). It names
  the Free plan's 150 AI checks an evening (then unchecked, not on the guests' list nor the TV), Pro's check of every request, and a
  party's 300 requests a day for both (`FreeAiLimit`, `GuestRequestLimiter`, Section 5.2). **Never in the queue** (no 💸, no ⭐ — nothing changes during a party). Not the rule of the staff's
  permissions (hidden, not greyed): the organiser can change their plan, so they see what there is. The staff of a party without Pro
  see none of it (hidden — not theirs to buy). While the plans are not enforced nothing of this shows but "Cennik". Browser scenarios
  `pro-free-panel`, `pro-free-settings-phone`, `pro-free-settings-wide`, `pro-page-phone`, `pro-page-wide`.
- **Emoji** only as markers in a list or a status (⭐ ⚠ 💸 👍 ▶ ⏭, "🎧 Gra: …", 🚫 / ⭐ of the hosts' lists) — not in card titles,
  headings or ordinary buttons. **Words**: "Ty" to everyone, "Wy" only on the hosts' page; "organizator" (the party's owner), "obsługa"
  (bartender, second DJ), "gospodarze", "goście"; no English words ("Zgłoś uwagę", not "Feedback"); "DJ‑a" in a heading with a
  non-breaking hyphen.

---

