> Part of `PROJECT_CONTEXT.md` (the index, at the repo root: what is in each part) — Section 5.1 (cont.). Keep it to what is true now.

**The venue's kiosk** (V56, "Kiosk dla gości", Pro; the owner, 2026-10-30 — Rekwest has one): a tablet at the bar where guests without a
phone ask for a song and vote. The card of "Ustawienia imprezy" (whoever has the permission KIOSK; without Pro the organiser sees the
offer): "Utwórz link kiosku" — a secret `/k/{token}` (`kioskToken`), to copy and open on the tablet —, "Nowy link" (asks first: the old
tablet's link dies; Pro) and "Wyłącz" (any plan: without Pro a link made before opens nothing, and taken away it does not come back
with Pro — the card without Pro says so and calls it "Usuń stary link kiosku"), sent in the background — the server's card takes the old one's place, "✓" on the button, the page
does not move (`cards-in-place.js`, as "Godziny imprezy" and the hosts' link: a card with `data-saves-in-place` whose forms marked
`data-in-place` are fetched; the answer — the card alone, or the settings page a redirect leads to — gives the card's state and its body,
or the part the form names; a button gone with the answer leaves the focus in the card); a tip for Guided Access (iPad) / screen pinning (Android). **The kiosk's page**
(`KioskController`, `kiosk.html`, `js/kiosk.js`; public, no login, `noindex`; a token that is no kiosk's, or a party without Pro, a 404;
the secret kept out of the tablet's address bar, where a guest could read it and use the kiosk from home: the link leaves the kiosk's
cookie `S2P_KIOSK` (HttpOnly, `Path=/k`, 90 days) and kiosk.js shows the address `/k`, which opens the same kiosk by the cookie — a pull
to refresh too; the forms and kiosk.js's way back still go by the token):
the request form as the party page's (the iTunes suggestions, the dedication when on) and beside it — on a tablet held across, else under
it — the most wanted with 👍, as the TV screen, "Dotknij 👍 przy piosence, którą też chcesz usłyszeć." under its heading; "Następny gość" under the form, in its column; one screen on a tablet, the page never
scrolls (the list scrolls in its card, keeps its place there when it comes again and fades at the bottom while more is below — a
tablet hides the scrollbar); a screen too small for the form and three songs (a phone; under 900 px wide and 1000 px high, or under
560 px high) scrolls as a page, the whole list under the form (scenarios `kiosk-full-*`); no link out (no profiles, tip link, footer).
Opened in a browser with a login, the page warns (`kioskLoggedIn`): on a tablet at the bar that is the DJ's panel for a guest who
leaves the kiosk, and the list's fetches keep the login from running out. **A guest's turn is a session:** "Następny
gość" (`POST /k/{token}/next`, sent in the background by kiosk.js — lost, or no answer within 10 s, the kiosk stays and tries again
after 30 s; meanwhile no 👍 is sent, it would be the next guest's) ends it (`session.invalidate()`), so the next guest has the DJ's limit and their own 👍 again — but a
session with a login in it (the DJ opened the kiosk in their own browser) is kept, only the turn's limit, requests and 👍 at the party
forgotten (`GuestSessionService.forget`, `GuestVoteService.forget`): ending it would log the DJ out; kiosk.js
sends it by itself after a minute without a touch once the turn began (a typed song, a 👍 — or before the page loaded: a refused request
back with its words, a 👍 given, `data-kiosk-turn`), 20 s after an answer; an untouched form loads
itself again every 10 minutes (its session and CSRF token fresh), a closed party's page every minute. The only page kiosk.js loads is
the kiosk's own address (`data-kiosk-url`), and only once it answers (asked for first in the background: a page load with the Wi-Fi
down or the server failing would leave the tablet on the browser's error page, with no script; else again after 30 s) — never a
reload: an answer is a POST's page, and a reload would send the request again. **Limits** (`GuestRequestLimiter`):
not the address's (the tablet shares the venue's Wi-Fi with the phones) but the kiosk's own — 3 requests a minute, 20 👍 per 10 min,
said as the kiosk's, with the real wait ("Kiosk dostał teraz dużo głosów — spróbuj za 7 min") —; the party's day counts its requests as every guest's. A request goes the
guests' way (`GuestRequestService`: `fromParty` for the party page, `fromKiosk`), marked `viaKiosk` — the queue and the history show
"🖥 kiosk", the evening summary counts "Z kiosku" and its CSV says "tak" in the column "Z kiosku" (after "Dodana przez DJ-a"). The answer in the kiosk: the verdict, the AI's comment, "Gotowe — następny gość"
(no number or tip: they are for a phone). 👍 in the background (`X-Requested-With: fetch`, the list comes back; its note read out by
the page's own live region `#kioskAnnounce` and left 15 s on the screen); the list again every 10 s — not within 5 s of a touch (its
rows move with the votes: a 👍 would land on another song), nor while a 👍 is on its way or its note is read (an answer asked for
before the 👍 would show it not given; every fetch is given up after 10 s, so such an answer lands in that time), the focus kept on the same song's
👍; a list that says the party closed (`data-active="false"`) sends the kiosk to its page. An expired form returns to the kiosk with what was typed (`GuestFormExpiredHandler`). `KioskWebTest`, browser scenarios `kiosk-*`.

