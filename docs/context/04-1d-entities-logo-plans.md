> Part of `PROJECT_CONTEXT.md` (the index, at the repo root: what is in each part) — Section 4.1 (cont.), 4.2, 4.3. Keep it to what is true now.

**The DJ's logo → `party_logo`** (V45, plan Pro; plain SQL in `PartyLogoService`, no entity) — one row per party (`party_code` PK,
FK ON DELETE CASCADE — gone with the party and the account, `PartyLogoIT`): `png` (bytea: our own PNG, never the file uploaded), `width`,
`height`, `version` (the PNG's SHA-256, hex: the ETag and the `?v=` of its address), `updated_at`. **`party_settings.logo_version`**
mirrors `version` (mapped read-only, `insertable = false, updatable = false`: a save of the settings never writes it back) so every page
learns from the cached settings whether there is a logo and its address — `PartySettingsEntity.logoPath()`, `/p/{code}/logo.png?v=<16
hex>` — with no query of its own; `replace` / `delete` write both under the party row's lock and evict `partySettings` after the commit
(two uploads at once end as one of them). **Made safe** (`LogoImage`): the browser (`js/dashboard/logo.js`) reads the chosen image — PNG,
JPEG or WebP, ≤ 2 MB, an SVG refused — draws it at most 512 px a side on the preview's canvas and sends that PNG as the request's body
(as the library: no form's upload) — its empty margins cut off first (`contentBox`: transparent ones, or ones of the colour all
four corners share, 4 % left; a logo saved in the middle of a large square would show as a dot; browser scenario
`logo-trims-margins`); the server checks it again as a stranger's file — ≤ 2 MB, the format by its first bytes (PNG or
JPEG only: the JDK reads no WebP, and nothing else is tried), the size read from the header before a pixel is decoded (≤ 4096 a side,
≤ 16 megapixels: an image "bomb" is refused unread), a large one read every n-th pixel, scaled to ≤ 512 px and written again as a new
PNG (no EXIF, no text chunks, no second frame), two decodes at a time on the server. **Served** from our address, `GET
/p/{code}/logo.png` (`PartyLogoController`, public as the party page): the ETag is the version (a 304 never reads the row),
`Cache-Control: public, max-age=3600` (a new logo is a new address; a deleted one may live an hour in a browser or Cloudflare — the
privacy policy says so), a 404 without one or while the organiser's plan has no `LOGO` — the CSP needs no change. **Shown** (`djLogo`,
as `PlanService.asPlanned` leaves it) as a white-label head — the party is the DJ's: on the guest page, the result page and "party
ended" the logo **in place of ours** on a white badge (a dark logo vanishes on the dark pages; "party ended" loses its 👋), ours a
quiet "Działa na Scan2Play" over the footer (`components :: footer`, `#poweredBy`); the QR poster headed by it (≤ 30 mm; still one A4 page —
browser scenario `logo-poster-fits-a4`), ours a quiet "Działa na / Powered by Scan2Play"
at its foot; every card with it above the code in place of ours (two marks stacked were clutter; the address names us —
`logo-cards-fit`); the TV screen and the evening summary above ours. Set on "Ustawienia imprezy", the card **"Twoje logo"** (the organiser's; its state
"Wgrane" / "Brak"): the saved logo with a small "Usuń" (asks first), the file's field, the preview "Tak zobaczą je goście:", "Wgraj";
the card is brought again in place after a save or a deletion, as the library's.
**`DjPlanEntity` → `dj_plan`** (V35, the owner 2026-10-14) — a DJ's plan, one row per organiser: `ownerId` (the primary key, the Google
subject as `party_settings.owner_id`), `plan` (`Plan`: FREE / PRO), `source` (`PlanSource`: TRIAL / PAID / GIFT), `validUntil` (null = no
end; after it the row reads as FREE, `planAt`), `updatedAt`. **Empty at first: no row is Free.** Deleted with the account. Read only by
`PlanService` and only while the plans are enforced (below).
**`trial_used`** (V36, the owner 2026-10-15; no entity — native queries of `DjPlanRepository`) — the Google accounts that had a trial of
Pro: `owner_hash` (PK, the SHA-256 of the Google subject in hex, `PlanService.trialKey` = PostgreSQL's
`encode(sha256(convert_to(owner_id, 'UTF8')), 'hex')`), `used_at`. **Not deleted with the account** — that is what it is for: the
same Google account after "Usuń konto" gets no second trial; nothing else of the account (the privacy policy, section 5: kept
while Scan2Play offers a trial, on its legitimate interest — if the trial is dropped for good, a migration empties the table).
**`spring_session`, `spring_session_attributes`** (V11, Spring Session's own schema, no entity) — the HTTP sessions; expired ones are
deleted every minute. An attribute is written with `INSERT … ON CONFLICT DO UPDATE` (`AppConfig.sessionAttributesUpsert`): two requests
of one session at once that both added the same one (a new session's CSRF token) met the primary key, and one was a 500
(`SessionStoreIT`).

**Plans — Free and Pro** (V35, the owner 2026-10-14; the review and its mockups: https://claude.ai/artifact/32eMmdwz9pTGLs8pRQzLBv —
the owner picked variant B). The app stays free; the foundation is there to make some features paid one day, **without payments yet**.
**`scan2play.plans.enforce=false`** (env `PLANS_ENFORCE`, the start): every party has every feature, no plan is read, no page says
"Pro" — nothing changed for anyone. `true`: a party works as **its organiser's plan** allows (`PlanService`, the one place that
decides, as `DjSessionHelper.require` for the staff): Pro for ever for the Google subjects of `scan2play.plans.pro-owners` (env
`SCAN2PLAY_PLANS_PRO_OWNERS` — the owner and testers, no SQL on production), else the row of `dj_plan` while valid, else Free.
- **Pro features** (`PlanFeature`): the hosts' lists and their link (`HOST_LISTS`), the profiles (`PROFILES`), the tip link and 💸
  (`TIPS`), the AI's comment styles but "Klasyczne" (`COMMENT_STYLES`), the guests' limits set by the DJ (`GUEST_LIMITS` — without Pro
  every party has a new party's 2 requests / 3 min / no repeats of the last 15, `PartySettingsEntity.DEFAULT_*`), "Podsumowanie
  wieczoru" and its CSV (`SUMMARY`), the venue's kiosk (`KIOSK`, V56 — without Pro its link opens nothing; taking it away needs no plan), the guests' wishes before an event (`WISHES`, V68 — without Pro their links open nothing and no event is made; deleting one needs no plan), the DJ's logo (`LOGO`, V45 — without Pro kept, not shown; deleting it needs no plan), the DJ's library and its marks in the queue (`LIBRARY`, V41 — without Pro the library is kept,
  unmarked; deleting it needs no plan); and the staff's places (`Plan.staffPlaces`): 10 with Pro, 1 without (the invitations by e-mail
  waiting count, as before). **Everyone's**: the QR code and prints, the AI, the queue and its keys, the votes and 👍, the vibe, its
  note and "Kto gra", the history, the notifications, 1 person of staff.
- **The staff work the organiser's party with the organiser's plan** (the party's `ownerId` decides, never the person's); on their
  own party, their own plan.
- **Leaving Pro deletes nothing**: the lists, the profiles, the tip link, the comment style and the limits stay saved and stop working
  (`PlanService.asPlanned` — the party as its plan lets it work, a copy for the guests, the AI, the queue's ⭐ / 💸, the print; never
  saved); the hosts' link is a 404 as an old one; the people already on the staff keep their access — only no one new joins while the
  places are taken. The card says "Twoje listy są zachowane: 🚫 3 · ⭐ 5. Wrócą, gdy włączysz Pro".
- **A trial**: `scan2play.plans.trial-days` (env `PLANS_TRIAL_DAYS`, 0) days of Pro written for a new DJ when their party is made —
  only while enforced, once per Google account — also after "Usuń konto" (`trial_used`, V36; `PlanService.startTrial`). The
  cached plan goes after the trial's commit, and the cache keeps the row's end (`PlanService.Read`): a plan ends at its `validUntil`
  (that moment is Free already), not up to 10 min later.
- **The gift** (the owner, 2026-11-05: 30 days): `scan2play.plans.gift-until` (env `PLANS_GIFT_UNTIL`, a day; empty: none) — until the
  end of that day, Polish time, every organiser without Pro of their own has Pro (`PlanService.planOf`; no row written, so nothing
  waits in the database for a switch not yet made, and it ends by itself; a trial begun during it runs from its end, and a day
  not written as 2026-12-09 stops the start naming the variable); the organiser it gives Pro to reads one line above the
  queue, "Scan2Play ma teraz plany Free i Pro. Do 9.12 masz Pro w prezencie, potem plan Free. Zobacz cennik →" (`giftEndsFor`,
  `data-plan-gift`). The day of the switch, the variables and the checks: `docs/plans-switch-on.md`. The terms (section 5 "Plany
  Free i Pro", 2026-11-05): free of charge, what may become Pro, the Free limit, the notice and the 30 days, nothing deleted, nothing
  paid without separate terms and consent — true while the plans are off and after.
- The server refuses a Pro feature's endpoint (`PlanService.require` → `PlanRequiredException`, a 403) — `PlanFeatureEndpointsTest`
  walks them all; the pages show it as 6.7 says ("PRO"). No payment, no prices: "Zobacz, co daje Pro" leads to `/pro`, which says to
  write to contact@scan2play.com.pl (while not enforced it shows one card, everything free — 6.7). Each `PlanFeature` has its line there (`data-feature`, both languages —
  `ProPageRenderTest.everyPlanFeature_isOnThePage`). Later, before the first payment: a payment provider (Stripe Billing /
  Przelewy24 / Tpay) writing `dj_plan` (PAID, `validUntil`), the terms (a paid service, renewal, the consumer's withdrawal), the
  privacy policy (the payment provider, the billing data), invoices and VAT (KSeF, OSS) — the review's list.

### 4.2 Enums

`StaffPermission` (V32) QUEUE ("Zagrane", "Pomiń", "Cofnij", "↩ Przywróć", "Dodaj piosenkę" — V44), TIPS (💸), CLEAR_QUEUE, OPEN_CLOSE (end / resume the
party), HISTORY, VIBE (the card "Impreza"), LIMITS, HOST_LISTS (the lists and their link), SUMMARY (the evening summary and its CSV),
KIOSK (V56: the kiosk's link; given by V56 to everyone who had all the rest — `KioskMigrationIT`) · WISHES (V68: "Życzenia przed imprezą"; given by V68 the same way) ·
`StaffRole` (V32, ready sets by what the person does, not by their job) VIEWER "Podgląd" (HISTORY), QUEUE "Obsługa kolejki" (QUEUE,
TIPS, CLEAR_QUEUE, OPEN_CLOSE, HISTORY — what every person of the staff could do before V32; `DEFAULT`: the role picked first for a new link and for "Zaproś", and what a link made before V33 gives), CO_ORGANISER
"Współorganizator" (all of them), CUSTOM "Własne" (ticked one by one) ·
`Plan` (V35) FREE (1 place of staff) / PRO (every `PlanFeature`, 10 places) · `PlanFeature` HOST_LISTS, PROFILES, TIPS, COMMENT_STYLES,
GUEST_LIMITS, SUMMARY, LIBRARY, LOGO, KIOSK (V56), WISHES (V68) · `PlanSource` TRIAL / PAID / GIFT ·
`HistoryFilter` all / played / rejected (an old link's "guest" / "background" reads as all) · `VibeType` ANY and 16 genres (V16:
Polish hits, 2000s/2010s, R&B & soul, folk / biesiada, kids added; bachata, salsa and reggaeton merged into LATINO — the rows moved
by the migration).

### 4.3 Records

`DjResponse` (the AI's answer: `decision`, `comment`, `songName`, `requestKind` title / artist / lyrics / mood /
unchecked, `requestId`, votes, `ownSong`), `HistoryEntry` (one line of the history), `GuestQueueService.GuestQueue` (what the guest
sees under the form), `PlayHistoryService.Page`.

---

