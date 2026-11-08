> Part of `PROJECT_CONTEXT.md` (the index, at the repo root: what is in each part) — Section 10. Keep it to what is true now.

## 10. Production Configuration

### Environment variables

`GOOGLE_CLIENT_ID` / `_SECRET`, `GOOGLE_AI_API_KEY`, the database (`PGHOST`, `PGPORT`, `PGDATABASE`, and **`PGUSER` / `PGPASSWORD`
required** — no defaults since review 5.5; Railway sets all five), `SCAN2PLAY_GUEST_URL` (the base URL in the QR code; locally the LAN
address, so a phone on the same Wi-Fi can open it), `SCAN2PLAY_SITE_URL` (optional; the site's address for search engines and shared links,
default the production domain — not set on Railway), `GUEST_CLIENT_IP_HEADER=CF-Connecting-IP` (Railway), **`ORIGIN_SECRET`** (a secret; Section 8, "Only Cloudflare reaches
the app" — in this order: (1) Cloudflare → the zone → Rules → Transform Rules → Modify Request Header → "Set static" header
`X-Scan2Play-Origin` = a long random value, for every request to the host `www.scan2play.com.pl`; (2) Railway → `scan2play` →
Variables → `ORIGIN_SECRET` = the same value; (3) the start's log says "Origin guard on"; `curl -sI https://www.scan2play.com.pl` is a
200 and `curl -sI https://www.scan2play.com.pl --resolve www.scan2play.com.pl:443:<the address of oqz5xl6q.up.railway.app>` a 403.
The variable before the rule refuses every page — back: delete the variable), **`VAPID_PUBLIC_KEY` /
`VAPID_PRIVATE_KEY`** (the notifications' key pair, made once by `VapidKeyGenerator` in `src/test/java` — secrets; without them the
notifications are off; a new pair makes every device switch them on again; `VAPID_SUBJECT` optional), and the optional overrides
below. The app no longer reads `YOUTUBE_API_KEY`, `SPOTIFY_*` or `YOUTUBE_SEARCH_DAILY_BUDGET`. The DJ's login works only on
`localhost` or a public HTTPS address (Google refuses a LAN IP as a redirect URI); the guest side works through the LAN IP.
**The plans** (V35, Section 4.1 "Plans"): `PLANS_ENFORCE` (default `false`: everything for everyone — not set on Railway),
`SCAN2PLAY_PLANS_PRO_OWNERS` (Google subjects, comma-separated: Pro for ever — the owner's own `party_settings.owner_id`, testers),
`PLANS_TRIAL_DAYS` (default 0). **`BREVO_API_KEY`** (V64, Section 7.4): the automatic e-mails' key — a secret, never in the repo; unset,
no e-mail is sent. `SCAN2PLAY_MAIL_FROM` (optional, default `contact@scan2play.com.pl`): the sender — it must be a verified sender in
Brevo (Senders), or Brevo takes the e-mail and then drops it ("sender … is not valid", seen only in Brevo's log). **`ADMIN_GOOGLE_ID`** (`scan2play.admin-id`): the owner's Google subject — the account that sees `/dj/stats` (V40) and whose devices get the AI alarm (Section 7.1); not a secret; unset, the page is a 404. **`ADMIN_EMAIL`** (`scan2play.admin-email`, default `scan2playcontact@gmail.com`): where the owner's daily report goes (Section 5.1c); empty = no report. Switching the plans on is one variable — decide first what the DJs of that day get (a data migration).
**`JAVA_TOOL_OPTIONS=-Xmx512m -XX:+ExitOnOutOfMemoryError`** on Railway's `scan2play` (set; the deploy log's first line says
"Picked up JAVA_TOOL_OPTIONS", at the level ERROR because the JVM writes it to stderr — not a failure): Railway bills the RAM in use, and the JVM saw the container's 8 GB and let its heap grow to 2 GB (the metrics: 780 MB
on average, 2 GB at the top) — the live data is ~55 MB. Measured on a copy (20 parties × 50 guests polling every 3 s, 1 500
requests in 2 min): 0 errors, the same speed as the default, ~800 MB at the top instead of 1–1.1 GB. `-Xmx256m` with SerialGC was
lower (536 MB at one party of 150 guests) but slower under the 20 parties; out of memory the app restarts instead of hanging.
Backups: Railway's scheduled ones are not on Hobby — `docs/database-backup.md` (a weekly `pg_dump` by hand).

### Key application properties

| Property | Value |
|----------|-------|
| `google.ai.model-name` / `google.ai.thinking-level` (`thinking-budget` for gemini-2.x) | `gemini-3.6-flash` / low (1024) |
| `spring.jpa.hibernate.ddl-auto` | `validate` (Flyway owns the schema) |
| `spring.task.scheduling.pool.size` | `2`: the job of every minute that opens and closes the parties (V55) never waits for a night's job (`PartyScheduleIT`) |
| `server.forward-headers-strategy` | `FRAMEWORK` |
| `server.compression.*` | gzip for HTML / CSS / JS / JSON from 2 KB |
| `server.tomcat.max-http-form-post-size` | 10 KB |
| `server.shutdown` | graceful, 30 s |
| `spring.task.execution.pool.*` | 16 core / 32 max threads, queue 50 (env `ASYNC_POOL_*`) — the guests' requests (`Callable`) |
| `spring.datasource.hikari.*` | 15 max, 5 idle, 5 s connect timeout |
| `guest.limit.*` | 60 per 10 min per address + party, 300 per 24 h per party, the Free plan's 150 AI checks an evening (`free-ai-per-party`) (env `GUEST_LIMIT_*`) |
| `guest.client-ip-header` | empty (env `GUEST_CLIENT_IP_HEADER`) |
| `security.csp.enforce` | `false` = report only (env `CSP_ENFORCE`) |
| `scan2play.plans.enforce` / `.pro-owners` / `.trial-days` / `.gift-until` | `false` / empty / 0 / empty (env `PLANS_ENFORCE`, `SCAN2PLAY_PLANS_PRO_OWNERS`, `PLANS_TRIAL_DAYS`, `PLANS_GIFT_UNTIL`; V35) |
| `spring.session.jdbc.initialize-schema` | `never` (the tables are Flyway's, `V11`); a session lives 30 min without a request |

**The profile `local`** (`application-local.properties`): the developer's defaults `PGUSER=postgres`, `PGPASSWORD=1111`. IntelliJ's run
configuration has *Active profiles: local* (or `SPRING_PROFILES_ACTIVE=local`); without it and without the variables the application
does not start (`Could not resolve placeholder 'PGUSER'`).

