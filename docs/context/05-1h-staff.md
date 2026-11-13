> Part of `PROJECT_CONTEXT.md` (the index, at the repo root: what is in each part) — Section 5.1 (cont.). Keep it to what is true now.

**The party's staff** (V30, the owner 2026-10-09; roles and permissions V32, 2026-10-10 after the review of the staff's flows,
https://claude.ai/artifact/XMPNxcn9e4NswLfmLdcMyx): a bartender, a second DJ, the venue's manager works the owner's party with their
own Google account, **as far as the owner allows**. The owner's page **"Obsługa"** (`/dj/staff`, `staff.html`; from the row "Obsługa ·
Osób: 1" of the panel and its line on "Ustawienia imprezy" — not the menu "Konto", which is about the person, 2026-10-10; the card in
the settings was 1750 px down a phone and 990 px tall with ten people; its heading leads back to "← Ustawienia" and to "Panel" of the
same party — the one row of the settings with a page of its own, 2026-10-10): a card per person, **folded to one line** — the name, the role,
"w obsłudze od 10.10" (a `<details>`; the owner, 2026-10-10: ten unfolded cards repeated the same picker; the one just saved comes back
unfolded with "Zapisano"; an invitation by e-mail waiting is folded the same way — the address, the role, "czeka od 10.10" —, and so
are "Zaproś osobę" and "Albo wyślij link" (2026-10-25, `details.s2p-fold`: their lines say "Wolne miejsca: 7 z 10" and "Podgląd ·
do 24.11"; "Zaproś" unfolded when it has an answer to show or nobody helps yet, the link after "Nowy link" / "Wyłącz" —
remembered unfolded, `js/folds.js`; every form back to the page where it was, no anchor, `scroll-restore.js`; "← Ustawienia imprezy"
`.s2p-back-link`, as on the wishes' page)); unfolded, **"Co może"**: a role (`StaffRole`: "Podgląd", "Obsługa
kolejki", "Współorganizator", "Własne") and, under "Uprawnienia po kolei", a checkbox per `StaffPermission` (`js/staff.js`: a role
ticks its set, a tick changed picks the role it makes — "Własne" when none; the server decides the same way), "Zapisz" (`POST
/dj/staff/permissions`, "✓ Zapisano") and "Usuń dostęp" (asks first, `POST /dj/staff/remove`); under the people, **the invitations by
e-mail waiting** (V34): the address, "zaproszono 10.10 · czeka na zalogowanie", "Rola: …", "Cofnij zaproszenie" (asks first, `POST
/dj/staff/invitation/cancel`); the card **"Zaproś osobę"** — the address of the person's Google account (`type="email"`) and the role
(the same picker as at a person, `fragments/staff-role.html`; "Obsługa kolejki" first), "Zaproś" (`POST /dj/staff/invite`, the page's
one `.btn-action`; the page then says "Zaproszono: …", "To nie wygląda na adres e-mail: …" with the address kept in the field, or that
the 10 places are taken); the card **"Albo wyślij link"** — the link made **with a role** (V33, the owner 2026-10-10): "Ten link:
Podgląd", the role picker, "Utwórz link zaproszenia" / "Nowy link" (the old one dead, asks first) / "Wyłącz link (osoby z listy
zostają)" (`POST /dj/staff/link`). **A link's role changes only with a new link** (the owner's pick "A": a link sent as "Podgląd" must
never start to give more); who joined keeps their role, changed at the person. Full page loads. Never handed over: the staff, the
DJ's profiles and tip link (money), "Wyczyść historię", the account.
**An invitation by e-mail** (V34, `StaffInvitationService`): nothing is sent — no mail server. Whoever logs in (the app, a browser, the
landing page: every login ends on `/dj/dashboard`) with a Google account whose address Google **verified** (`email_verified`;
`StaffController.verifiedAddress`) and an organiser invited is sent to `/dj/invitation` **before any panel** — so no DJ's party is
made for someone who came to help (one query by the index on `email_key` per opening of the panel). The page is `join.html`: "Ola
Kowalska zaprasza Cię do obsługi imprezy: Klub Ola", the role and what it allows, "Dołącz" (`POST /dj/invitation/accept`) and "Nie,
dziękuję" (`POST /dj/invitation/decline`: the invitation goes; the panel says "Zaproszenie odrzucone." — someone without a party gets
"no-panel", not a DJ's party). Joining (`accept`, `@Transactional`): the invitation must be for that verified address and not too old;
the party row locked, the invitation asked again from the database (another tab may have answered it), then
`PartyStaffService.addToStaff` (not the owner, not twice, the places — the invitation's own counted already) and the invitation
deleted — two "Dołącz" at once join once (`StaffInvitationIT`, seen red without the lock: the unique key). "Zaproś" refuses the organiser's own verified address (`OWN_ADDRESS`: it would hold a place and join nobody) and counts the places
under the same lock: the staff and the invitations never pass 10, however many invitations and joins come at once (`StaffInvitationIT`).
An invitation to the organiser's own party is deleted with a note. The link stays the other way.
**Joining** — never a side effect of opening a page (the review: two top-level navigations of a foreign site joined a logged-in DJ to
its party and showed it their Google name): `/join/{token}` (public, `join.html`, `noindex`, `no-referrer`) names the party and who
invites ("Ola Kowalska zaprasza Cię do obsługi imprezy: Klub Ola" — the organiser's name, when it is not the party's name already), the
role **the link gives** (V33) and what it allows. Logged in, it asks: "Dołącz" (`POST /join/{token}`, CSRF) or "Nie, dziękuję"; not logged in, its button goes through
Google's login (`/start`) with the token in the session, and the panel sends the person back to the question. The link
is built from the address the owner opened the page at, not `scan2play.guest-url`: Google's login refuses a computer's address in
the local network ("device_id and device_name are required for private IP"; locally test it on `localhost`, a second Google account
in a private window). Joined (`PartyStaffService.join`, `@Transactional`: the party read **by the token with its row locked**
(`lockByStaffToken`) — a new link made at that moment is waited for and the old one then joins nobody, not even with its old role
(`PartyStaffRepositoryIT`, seen red when the link was read before the lock) —, then not the owner, not twice, at most 10 places with
the invitations by e-mail — `PartyStaffRepositoryIT`, 20 at once make 10), with the link's role, the panel opens that party:
"Dołączono do obsługi: Klub Ola." A full staff
or an old link: the invitation page says what happened (409 / 404) — "Obsługa jest pełna (10 osób): …", "Ten link zaproszenia już
nie działa — organizator mógł utworzyć nowy". A link pasted in the app (an iPhone's Home Screen app has a login of its own; the field
"Zaproszenie do obsługi? Wklej link" in "To urządzenie" of "Ustawienia imprezy" — on the person's own party and "no-panel" only, not on
another's party: she has joined it already, it read as if she still had to (the owner, 2026-10-10) —, `POST /dj/join`) leads to that invitation; a guests' link
(`…/p/…`) is told apart ("To link dla gości…, nie zaproszenie"). On the landing page, under the last "Zbieraj prośby gości", "Masz link z zaproszeniem od
DJ-a? Wklej go tutaj" (2026-10-18; it was "Masz zaproszenie do obsługi imprezy? Dołącz →" between the hero and the steps — a `<details>`,
`GET /join?link=`, public): a party's link opens its invitation (`/join/{token}`: the party, the role, the login from there),
anything else back to `/?staffLink=invalid#staffLogin` (or `guests`) — at the field and its note — before any login. A GET, so
the form carries no CSRF token and the landing page makes no session (the review of 2026-10-21, S2; until then every visitor of
`/` was a row in `spring_session`).
**Which party a request works on** (`DjSessionHelper`, V32): **the page names it** — every form and fetch of the panel sends
`partyCode` (hidden inputs; `forms.js` adds it to what it sends, the "Cofnij" bar too), the pages' links carry `?party=`, and the
server checks the person's access to *that* party on every request (the owner without a query, a person of the staff by their row —
an access taken away or a permission changed counts at once). Until V32 the session held one panel party for every tab: a tab still
showing another party's queue cleared the person's own ("Wyczyść kolejkę", "Zakończ imprezę" — the review; on Android the installed
app and Chrome share one session). The session keeps only the **default panel** (`/dj/dashboard` without `?party=`; a request
without a code — a page of an older version — works on it). `require(partyCode, permission)` — the owner, or a person given it;
`requireOwner(partyCode)` — what is never handed over; anything else is a 403 (`StaffPermissionEndpointsTest` walks every endpoint:
refused without its permission, let through with it). A person **without a party of their own** gets none made behind their back: a
bartender opens the party they work at; one whose access was taken away, or whose invitation did not work, gets the page
**"no-panel"** ("Nie obsługujesz teraz żadnej imprezy": the note why, an invitation to paste, "Załóż własną imprezę" — `POST
/dj/panel` without a party); a DJ's first login, with no note waiting, makes the DJ's party as before.
**The panel of a person of the staff**: one line under the tabs — **"Klub Ola · Obsługa kolejki ▾"** (`#panelBar`, a `<details>`):
unfolded, "Co możesz na tej imprezie" (the permissions given) and, with more than one panel, "Otwórz inny panel" (the own one named as
the guests see it, "DJ Kuba (Twoja impreza)" — "Mój panel" was gone: for a bartender a click made a DJ's party); a note above it once
("Dołączono…", "Organizator usunął Twój dostęp do imprezy: …" — `DjSessionHelper.Note`, kept in the session until shown). The page
shows the buttons of the permissions given (hidden, not greyed; the poll's rows too) and refuses the rest on the server; a
co-organiser gets the settings' cards and rows they were given (the button says "Impreza i kod QR" then, else "Kod QR"). The QR card
says "Link dla gości" (it said "Twój link do imprezy" to the staff). "Zakończyć imprezę: Klub Ola?" names the party. The menu
"Konto": "Opuść obsługę" (`POST /dj/staff/leave`, the party the page shows; another panel opens, or "no-panel") in place of "Usuń
konto"; "Załóż własną imprezę" for someone without one. **Every poll** says what the person may do (`X-Panel-Access`: "owner" or the
permissions' names): another value than the page was made with (`#panelAccess`), or a 403 (the access taken away), and the page loads
itself again and says so — before, the queue stood still and only the console knew (`polling.js`; browser scenarios
`staff-access-taken-away`, `staff-access-changed`). Their devices get the notifications of new requests too.
The texts call the party's owner "organizator" and the people "obsługa".

