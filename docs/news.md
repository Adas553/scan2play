# "Nowości" — the panel's card and the news e-mail

What is new in Scan2Play, told to the DJs who already have an account (V80; how it works: `docs/context/05-1c-notifications-emails.md`,
"Nowości"). Two ways, one text:

- **The panel's card** "Nowości w Scan2Play" above the queue — every organiser of an account older than the news sees it once, until
  "✕". Needs no consent: it is the product itself, not an e-mail.
- **The e-mail** — only to whoever switched "Nowości mailem" on (on the card or in "Ustawienia imprezy"). The privacy policy promises
  it (section 3, art. 6(1)(a) GDPR; and Polish law on e-mail marketing asks for that consent first): **never an e-mail of news to
  anyone else**, never to the whole database. Each has "Nie wysyłaj mi nowości" and `List-Unsubscribe`.

Whoever says yes gets the news now by the next send too — even when they said yes on its card: they asked for the e-mail, and it keeps
the links in their mailbox. News 1 (2026-11: "Zagrane samo", "Pytanie do gości", "Plan imprezy"): release, wait a week or two while the
DJs open their panels and say yes, then send it; whoever says yes later gets it by the next click of "Wyślij".

## A new news, step by step

1. `PanelNews`: `NUMBER` + 1, `SINCE` = the day it is released (accounts made since do not see it as news).
2. The card's words: `dashboard.news.*` in `messages.properties` and `messages_pl.properties` (non-ASCII as `\u` escapes — `CLAUDE.md`),
   the card's lines in `dashboard.html` (`#newsCard`) — a line each, linking its part of the guide.
3. The e-mail: `templates/email/news_pl.html`, `news_en.html` and its subject in `NewsEmailService.subject`.
4. The tests that name the words: `DashboardPageRenderTest.anOrganiserOfBefore_seesTheNewsAboveTheQueue`, `NewsEmailServiceTest`,
   `src/test/browser/scenarios/news.js`.
5. Release; then, as the site's owner, `/dj/stats/news`: read the words, **"Wyślij próbę do mnie"** (PL and EN — to `ADMIN_EMAIL`),
   look at it in the mailbox (a phone too), tick "Próba sprawdzona" and **"Wyślij do N"**. Reload: "czeka" falls to 0. More than
   200 waiting: send again the next day (Brevo's free plan: 300 e-mails a day, the welcomes and the report among them).

**How often:** rarely, at most once a month — a news is worth an e-mail when a DJ would change how they work for it (the three of
news 1), not for a fix: the consent was given for "tylko o nowych funkcjach, bez reklam".
