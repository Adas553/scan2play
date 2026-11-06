# Switching the plans on (Free / Pro)

The steps for the day the plans start to work on production. Until then `PLANS_ENFORCE=false`: everyone has every feature, no page
says "Pro", `/pro` shows one card "Za darmo" (the owner, 2026-11-05: everyone keeps Pro until there are customers and a registered
business; 2026-11-06: the pricing page public before the switch too).

Everything below is set on Railway (service `scan2play`, Variables); nothing is written to the database by hand, and no migration is
needed — the gift of Pro is a variable (`PlanService.giftEndsFor`), not rows.

## Before

- The business is registered, if anything is to be paid. Nothing in the app takes payments: Pro stays "on request, free of charge"
  (the terms, section 5) until a payment provider, the terms of the paid service (price, period, withdrawal), the privacy policy's
  payment data and invoices are built (`PROJECT_CONTEXT.md` 4.1, "Plans").
- The pricing page (`/pro`) and the terms say what is true: Free 150 AI checks an evening (`guest.limit.free-ai-per-party`), the gift
  and the trial 30 days. Change the page and the terms with the numbers.
- The trial's abuse (a new Google account for another 30 days): it costs little — at most 150 more AI checks an evening, ~$0.45,
  and a morning e-mail —, a new account also means a new party code (new prints of the QR code) and none of the lists, the library or
  the history; and while Pro is "on request, free of charge" nobody gains by it. So no tracking of networks or devices (personal data,
  false matches at a venue's Wi-Fi). Add one sentence to the terms' section 5 (PL, EN — `terms_pl.html`, `terms.html`):
  "Okres próbny przysługuje raz na osobę lub firmę. Konta zakładane po to, by znów dostać okres próbny, możemy przełączyć na plan
  Free." / "The trial is once per person or business. Accounts made to get the trial again may be switched to the Free plan."
  `/dj/stats` lists the new organisers with their address (the last 7 days) for a look by hand. When payments come: a trial that
  needs a card, the payment provider's card fingerprint as the key.
- A weekday morning, Tuesday or Wednesday — never before a weekend of parties.

## The variables

| Variable | Value | What it does |
|---|---|---|
| `SCAN2PLAY_PLANS_PRO_OWNERS` | the owner's Google subject (the value of `ADMIN_GOOGLE_ID`), testers' after commas | Pro for ever, no row |
| `PLANS_GIFT_UNTIL` | the switch's day + 30, e.g. `2026-12-09` | Pro for every organiser without Pro of their own until the end of that day (Polish time); the panel says "Do 9.12 masz Pro w prezencie" |
| `PLANS_TRIAL_DAYS` | `30` | a new account's 30 days of Pro, once per Google account (`trial_used`) |
| `PLANS_ENFORCE` | `true` — set it last | the plans work |

Railway redeploys with each change; set the first three, then `PLANS_ENFORCE`, so the one deploy that enforces has them all.

## After the deploy

1. The start's log: `Plans enforced (1 Pro for ever, trial 30 days, Pro for everyone until 2026-12-09)` — not "Plans not enforced".
2. Without cookies: `curl -s https://www.scan2play.com.pl/pro` to the page's end (200, "Cennik", "0 zł", "WCZESNY DOSTĘP" — before
   the switch the page has "Za darmo" and no "Pro"); `/cennik` → 301 `/pro?lang=pl`.
3. The owner's panel: no "PRO" tags, no gift line (Pro of their own). A test account's panel: "Do … masz Pro w prezencie" above the
   queue, the account menu "Twój plan: Pro · Cennik", everything works.

## Rollback

`PLANS_ENFORCE=false` and the redeploy: every party has every feature again; nothing is lost ("Leaving Pro deletes nothing"), the
trial rows of new accounts stay and count again at the next switch.

## When the gift ends

The day after `PLANS_GIFT_UNTIL` the organisers without Pro of their own are Free: the "PRO" tags and "Zobacz, co daje Pro" in their
settings, 150 AI checks an evening (then "bez oceny AI"), 1 person of staff. Unset `PLANS_GIFT_UNTIL` afterwards (or leave it: a day
gone gives nothing). Pro by hand for a DJ who writes: a row of `dj_plan` (PRO, GIFT, `valid_until`) — there is no screen for it yet.
