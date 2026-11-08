> Part of `PROJECT_CONTEXT.md` (the index, at the repo root: what is in each part) — Section 5.1 (cont.). Keep it to what is true now.

**Notifications on the DJ's devices** (Web Push; only when the server has the VAPID keys — Section 10): the switch "Powiadomienia
o prośbach" in the card "To urządzenie" of the page "Ustawienia imprezy" (`js/dashboard/push.js`; the card's folded line says
"Powiadomienia wł. / wył." for this browser, `settings-rows.js`), per browser — the permission, the service worker
`/sw.js` (scope `/dj/`), the subscription with the server's key → `POST /dj/push/subscribe`; off → `/dj/push/unsubscribe`. A **new**
song on the list (accepted, or unchecked with the AI down; not a vote on a waiting one) → `PushNotificationService` sends
"🎵 Nowa prośba — <song>" to each of the DJ's devices, off the guest's thread (a pool of 2, queue 200, TTL 1 h, urgency high); the
service worker folds the notifications of one party into one ("🎵 Nowe prośby: 3"), a tap opens the dashboard. On an iPhone only
the dashboard added to the Home Screen (the web app manifest `/manifest.webmanifest`, iOS 16.4+) can take them — the switch says
so in Safari. Delivery is best effort: the dashboard stays the truth.
**"Zainstaluj aplikację"** in the same card (`js/dashboard/install.js`): the browser's own offer to install comes when it decides
and, once dismissed or the app removed, not again for months — the button asks when the DJ wants (Chrome / Edge: the kept
`beforeinstallprompt` opens the install window — once per offer: closed without installing, the next click says where the browser's
menu has it; an iPhone's Safari: the steps "Udostępnij → Do ekranu początkowego"; nothing in the installed app or a browser that
never offered). On a phone or a tablet (narrower than 992 px), **under an empty queue** — before the party, never over its requests —
"Panel jako aplikacja na telefonie: własna ikona i powiadomienia o nowych prośbach." with "Zainstaluj aplikację" (→ that card) and
"Nie teraz" (for good on the device, `localStorage`), where the app can be installed (an iPhone, or the browser's
`beforeinstallprompt`), never in the app or the demo (`js/dashboard/install-hint.js`; `install-hint-empty-queue`,
`install-hint-not-on-a-computer`).

**A new organiser's welcome** (V48): above the queue of the organiser's own panel, until they close it (`welcomeDismissedAt` null;
never the staff's, never the demo's, not the accounts of before V48) — "Pierwszy raz? Napisz do nas", one line of help, "Napisz do
nas" (an outline — the view's one action stays "Drukuj"; opens "Pomoc i kontakt") and "Jak to działa →" (the guide). "✕" closes it at
once, "Napisz do nas" once "Pomoc i kontakt" closes; either way `POST /dj/dashboard/welcome-dismiss` keeps it closed on the server —
the DJ logs in on a phone and a laptop (`js/dashboard/welcome.js`). While it shows, the empty queue's "Pierwszy raz? Zobacz, jak to
działa" and "Panel jako aplikacja" wait (`app.css`, `:has`). On a phone up to 400 px the heading stays one line (checked with the CI
runner's fonts too), "Napisz do nas" the card's width and the guide under it. Browser scenarios `welcome-card-*`. The organisers new in
the last 7 days with their address are on `/dj/stats` for the site's owner.

**The owner's daily report** (`AdminReportService`, V65, V66): an e-mail when the owner's schedule says — set on `/dj/stats/report`
(on / off, the time in Polish time, the days of the week; by default every day at 9:10), checked every 5 minutes, a failed send tried
again for 3 hours, never past midnight — to
`scan2play.admin-email` (`ADMIN_EMAIL`, by default the Gmail of the replies): yesterday's traffic and funnel beside the 7 days' average
(`SiteCounter`), the organisers new in 7 days with their guests' requests since (`EveningSummaryRepository.guestRequestsSince`), every
party of yesterday's evening (6:00–6:00: guests' requests, played, refused by the AI and before it, kiosk, tips, the DJ's own), the AI's
and the e-mails' state and the log's ERROR / WARN since the last report (`LogTally`, in memory: a restart starts from zero; a kind
is the message's pattern with its numbers as "#", at most 30 kinds). The e-mail names a party by its code — no organiser's address or
name, no evening's title (the privacy policy gives Brevo those only to write to that organiser); the page `/dj/stats/report`
(`AdminReportController`, the owner only) shows them, and its "Wyślij teraz" sends it at once without zeroing the log's counts.
"Once a day": the claim of `email_status.admin_report_day`; a failed send or build gives back the claim and the log's counts
(`AdminReportIT`, `AdminReportServiceTest`).

**The automatic e-mails** (V64, Section 7.4 Brevo): **the welcome** — once an account (`welcome_sent_at`), the text of
`docs/welcome-email.md` in the panel's language (`email_language`, kept with the address when the organiser opens the panel), no
marketing. The organiser's panel asks for it (`DjDashboardController.keepOwner` → `WelcomeEmailService.sendLater`): after the address
is committed, on the e-mails' own thread (`Mailer.later`, one thread, a queue of 100) — the page never waits for Brevo and a failed send
never fails it. "Once" is a claim (`UPDATE … WHERE welcome_sent_at IS NULL`): of two logins at once, or a login and the job, one sends; a
failed send gives the claim back, and the nightly job (4:35) sends the welcomes of the accounts of the last 7 days that failed. The
accounts of before V64 count as welcomed. **The morning summary** — Pro (`PlanFeature.SUMMARY`), the organiser's switch "Poranne
podsumowanie" on (`morning_summary`, its card in "Ustawienia imprezy" before "Obsługa", the organiser's alone, answered in place — browser scenario
`morning-summary-switch`), an address, and an evening of **at least 5 guests' requests** (the DJ's own additions not counted, the kept
ones of `evening_song` counted): at **10:00 Polish time** the next day (`MorningSummaryEmailService`, the run at 12:00 sends what failed
at 10), once an evening (`evening_summary.morning_sent_at`, one upsert claim). The evening's title (or "Podsumowanie wieczoru") and day,
three numbers (the guests' requests — the DJ's additions not counted, as for the threshold —, played, the guests' votes, each label
in its Polish form: "5 próśb gości", "1 zagrana"), the top 3, a button to `/dj/summary?evening=…&party=…`. The morning e-mail has
**"Nie wysyłaj mi tego"**, without a login: `GET /email/stop/{email_token}` only asks ("Wyłączyć poranne podsumowanie?",
`email-stop.html`) — mail filters open an e-mail's links to check them, and a link that switched off would switch it off unseen —;
its "Tak, wyłącz" posts to the same address (a form without `action` and without a CSRF token: the path is exempt, and a token would
make a session), and so does the mail program's own "Unsubscribe" (`List-Unsubscribe` + `List-Unsubscribe-Post`, RFC 8058); that
`POST` switches the morning summary off (`email-stopped.html` says where to switch it on again). `EmailStopWebTest`. The welcome has
neither the link nor `List-Unsubscribe`: it goes once, and the link switches off a summary it never names. "Once": `AutomaticEmailsIT`.

**When the AI cannot be asked** (an error, a timeout), the request goes on to the DJ unchecked — accepted, the guest's words, the note
`ai.unavailable.to_dj`, `requestKind` `unchecked`; the guest sees "PRZEKAZANE".

