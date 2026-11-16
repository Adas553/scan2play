# Watching production from outside

The application cannot tell anyone that it is down. An uptime monitor outside asks it every few minutes and writes to the owner
when it does not answer.

## What is asked

`GET https://www.scan2play.com.pl/healthz` (`HealthController`) — `ok` (200) while the application answers **and** reaches its
database; `database` (503) when the database does not answer; anything else (a timeout, a 5xx, Cloudflare's error page) when the
application itself is away. The landing page would stay a 200 with the database down, while every party's page fails.

The address is public, makes no session and asks the database at most once in five seconds, however often it is asked. It goes
through Cloudflare like every request (the origin's own address answers 403, Section 8 of `PROJECT_CONTEXT.md`).

## UptimeRobot (set up once, free)

1. An account at uptimerobot.com with the address the owner reads; install its phone app and allow its notifications — an e-mail at
   23:00 on a Saturday is read on Sunday.
2. **New monitor**: type **Keyword**, URL `https://www.scan2play.com.pl/healthz`, keyword `ok`, alert when the keyword **does not
   exist**, every 5 minutes, timeout 30 s. (A plain HTTP monitor would also do; the keyword also catches a 200 from a page that is
   not ours.)
3. A second monitor, type **HTTP(s)**, URL `https://www.scan2play.com.pl/` — the landing page as a visitor gets it.
4. Alert contacts: the e-mail and the phone app, for both monitors. Leave "alert after N failures" at 2: one missed check during a
   deploy is not an outage.
5. Try it: pause nothing in production — add a third monitor for `https://www.scan2play.com.pl/healthz-test-404`, see the alert
   arrive on the phone, delete that monitor.

If Cloudflare's "Bot Fight Mode" or a WAF rule answers the monitor with a challenge (the monitor shows 403), add a WAF skip rule
for the path `/healthz`.

## When an alert comes

- `/healthz` down, `/` up: the database — Railway → Postgres (is it running, its volume full?) and the application's log
  ("Connection is not available").
- both down: Railway → `scan2play` → the latest deployment and its log; Cloudflare's status; `docs/database-backup.md` if data is
  lost.
