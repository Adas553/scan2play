# Scan2Play — Project Context

> The application as it is on branch `dev` (2026-10-28). Read it before an architectural change; `AGENTS.md` says how to work,
> `SESSION_HANDOFF.md` what is going on now; the reviews and what was fixed: `docs/history/review-2026-09-30.md`,
> `docs/history/review-2026-10-14.md`, `docs/history/review-2026-10-21.md`.
> **History** — the decisions, the owner's reports, what was tried and measured, how every stage was verified — is in
> `docs/history/project-context-2026-10-01.md` (this file word for word before it was cut down, review 7.1; it still describes the
> YouTube player, Auto-Pilot and the background playlist) and `docs/history/session-handoff-2026-09.md`. The full application with
> YouTube and Spotify is the tag `full-player-2026-10-04` (branch `archive/full-player`). Keep this file to what is true now.
> Section numbers are referred to from the code and from applied migrations (5.4, 10, 13, 14) — keep them.

---

## 1. Product Overview

**Scan2Play** is an AI-powered music request platform for events. Guests scan a QR code and send song requests from a phone;
Google Gemini decides whether each one fits the party's vibe. The DJ plays from their own software (VirtualDJ, Serato, rekordbox…) —
**Scan2Play plays nothing**: it collects and filters the requests, counts requests for the same song as votes, and the DJ marks each
one played or skips it. One kind of party ("Twój program DJ-a"). YouTube and Spotify were removed on 2026-10-04 (Spotify: development
mode only, and its policy forbids this use; YouTube: 100 API searches a day shared by every party, and its terms limit playing to
personal use) — migrations V18 and V19.

**Production URL:** `https://www.scan2play.com.pl` (Railway, behind Cloudflare; `main` is live since 2026-10-06, Section 10).

**The repository** `Adas553/scan2play` is public since 2026-10-24, for viewing only: `LICENSE` says "All rights reserved" (no
permission to use, copy or build on the code). Nothing secret may be committed — every key and password comes from the environment
(Section 10).

---

## 2. Technology Stack

| Layer            | Technology |
|------------------|------------|
| Language         | Java 21 |
| Framework        | Spring Boot 4.1.2 (Spring MVC, Thymeleaf, Spring Security + OAuth2 Client, Spring Data JPA); embedded Tomcat 11.0.27 as the parent manages it. The 4.1 line has free updates until about 2027-07; the next line is 4.2 |
| Front end        | Thymeleaf pages, Bootstrap 5 (webjar `org.webjars:bootstrap`, served by the app at `/webjars/bootstrap/…`, the version only in `pom.xml` — `webjars-locator-lite`), plain JavaScript — the dashboard's scripts are ES modules, no bundler, no framework |
| Database         | PostgreSQL 18 (Railway `postgres-ssl:18`), schema by **Flyway** (Section 10) |
| AI               | Google Gemini (`google-genai` 1.74.0), model `gemini-3.6-flash` (thinking level low) |
| Other            | ZXing 3.5.4 (QR codes; `javase` without its command line and image decoders), the Maven wrapper with the sha256 of Maven 3.9.12, Caffeine (caches), Maven; the guests' song suggestions come from Apple's iTunes Search API, asked by the browser |
| i18n             | `messages.properties` (EN), `messages_pl.properties` (PL) — non-ASCII as `\uXXXX` escapes; the language: `?lang=pl` / `?lang=en` (any case, `en-US` too; kept in the cookie `lang` for a year — the pages' links do not carry it), else that cookie, else the browser's `Accept-Language`, with none — or an empty one, or `*` — Polish (`WebConfig`, 2026-10-14 / 15 — a crawler sends none); the switch "Polski · English" (the fragment `components :: langSwitch`) in every page's footer, at the top of the landing page and in the panel's menu "Konto" (2026-10-18, `LanguageSwitchAdvice`: the page's own address with `?lang=`, its other parameters kept; none on a page answered to a POST; not on the print pages and the TV screen, which are in both languages or the DJ's) |

---

## 3. Architecture

A monolithic Spring Boot application, layered:

```
controller/   HTTP: Thymeleaf views, HTML fragments for AJAX, a few JSON endpoints
service/      business logic
repository/   Spring Data JPA
entity/       JPA entities        model/   enums, records        config/  Spring beans
util/         AfterCommit, CodeGenerator, EmailAddresses, SocialLinks, SongList, SongNames, Texts, Times, TipLinks, YouTubeSearchLinks
```

Server-rendered pages with AJAX: the DJ dashboard polls the guest queue every 3 s (ETag / 304) and sends its forms by `fetch` (the
DJ keeps their place in the list; on a phone the settings stay as they were). A hidden window does not poll (review 3.4) and asks at
once when it is shown again. **Single instance by design** (Section 13): the caches and the guest limits are in memory. The HTTP
sessions are in PostgreSQL (Spring Session JDBC, `V11`; review 2.3): a deploy or a restart does not log the DJs out.

---

## Where the rest is

Sections 4 – 14 are in `docs/context/`, one subject a file (each ≤ ~25 KB): read the part the task needs, not all of them.
A "Section N" in the code, a migration or a document is the row of that number below. When you change something, update
its part (and this table, if a part's subject changes); a new subject goes to the part of its section.

| Section | File | Size | What is in it |
|---|---|---|---|
| 4, 4.1 | [`docs/context/04-1a-entities-party-requests.md`](docs/context/04-1a-entities-party-requests.md) | 12 KB | every moment a `timestamptz`; `party_settings` (the party: its code, vibe, limits, hours, kiosk token, theme…); `song_requests` (a request, its states and columns) |
| 4.1 (cont.) | [`docs/context/04-1b-entities-votes-lists.md`](docs/context/04-1b-entities-votes-lists.md) | 11 KB | votes and merged requests (`saveOrVote`, V15); numbers and tips; a song the DJ adds; the hosts' lists; a guest's 👍 |
| 4.1 (cont.) | [`docs/context/04-1c-entities-dj-library.md`](docs/context/04-1c-entities-dj-library.md) | 11 KB | dedications; `evening_summary`; feedback; push subscriptions; the staff and invitations; the DJ's library (`dj_library`, `dj_library_track`) |
| 4.1 (cont.), 4.2, 4.3 | [`docs/context/04-1d-entities-logo-plans.md`](docs/context/04-1d-entities-logo-plans.md) | 11 KB | the DJ's logo; `dj_plan`; the plans Free and Pro; enums; records |
| 5, 5.1 | [`docs/context/05-1a-public-pages.md`](docs/context/05-1a-public-pages.md) | 3 KB | the landing page `/`; the pages "Dla kogo" |
| 5.1 (cont.) | [`docs/context/05-1b-dashboard-qr-print.md`](docs/context/05-1b-dashboard-qr-print.md) | 18 KB | login → the dashboard: the queue's buttons and keys; the QR print (poster, cards, tents, tables, "Wzór na okazję", "Do internetu"); the account's menu; background forms and polling |
| 5.1 (cont.) | [`docs/context/05-1c-notifications-emails.md`](docs/context/05-1c-notifications-emails.md) | 6 KB | Web Push on the DJ's devices; a new organiser's welcome; the automatic e-mails (Brevo); when the AI cannot be asked |
| 5.1 (cont.) | [`docs/context/05-1d-panel-heading-queue.md`](docs/context/05-1d-panel-heading-queue.md) | 10 KB | the panel's heading; the active queue's columns; every window follows the party's state |
| 5.1 (cont.) | [`docs/context/05-1e-party-hours-theme.md`](docs/context/05-1e-party-hours-theme.md) | 7 KB | the party opens and closes by itself ("Godziny imprezy", V55); "Motyw dla gości" (V59) |
| 5.1 (cont.) | [`docs/context/05-1f-kiosk.md`](docs/context/05-1f-kiosk.md) | 5 KB | the venue's kiosk (V56): the link, the page, the turns, its limits |
| 5.1 (cont.) | [`docs/context/05-1g-history-summary.md`](docs/context/05-1g-history-summary.md) | 12 KB | the history (`PlayHistoryService`); "Podsumowanie wieczoru" (the evening, the hosts' version, CSV) |
| 5.1 (cont.) | [`docs/context/05-1h-staff.md`](docs/context/05-1h-staff.md) | 11 KB | the party's staff: roles, permissions, invitations, joining, which party a request works on |
| 5.2 – 5.5 | [`docs/context/05-2-guest-flow.md`](docs/context/05-2-guest-flow.md) | 14 KB | the guest's page `/p/{code}`; what reaches the AI; the limits; the Free plan's AI checks; (5.3, 5.4 removed); the demo |
| 6, 6.1, 6.2 | [`docs/context/06-1-controllers-services.md`](docs/context/06-1-controllers-services.md) | 15 KB | file inventory: controllers, services |
| 6.3 – 6.6 | [`docs/context/06-3-config-templates-static.md`](docs/context/06-3-config-templates-static.md) | 13 KB | file inventory: configuration classes, templates, static assets, resources |
| 6.7 | [`docs/context/06-7-look.md`](docs/context/06-7-look.md) | 12 KB | the look: `app.css`, colours, components, the design review |
| 7, 8, 9 | [`docs/context/07-09-integrations-security-caching.md`](docs/context/07-09-integrations-security-caching.md) | 15 KB | Gemini, Web Push, Brevo, (Spotify, YouTube removed); the security model; caching |
| 10 | [`docs/context/10-1-configuration.md`](docs/context/10-1-configuration.md) | 5 KB | environment variables; key application properties |
| 10 (cont.) | [`docs/context/10-2-migrations.md`](docs/context/10-2-migrations.md) | 17 KB | Flyway: the rules, every migration V1 … , the production first-deploy checklist |
| 11, 12 | [`docs/context/11-12-dependencies-endpoints.md`](docs/context/11-12-dependencies-endpoints.md) | 19 KB | main dependencies; every HTTP endpoint (public, DJ) |
| 13, 14 | [`docs/context/13-14-limitations.md`](docs/context/13-14-limitations.md) | 7 KB | known limitations and technical debt (architecture, security, front end, testing, AI); (14 removed) |

