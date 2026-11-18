# Backing up the production database

Railway's scheduled volume backups are not on the Hobby plan. Two copies instead:

- **every night, by itself** — the Railway service `db-backup` (`tools/db-backup`) runs `pg_dump` over Railway's private network
  and puts the file in a Cloudflare R2 bucket: another provider than the database's, so a lost Railway project does not take the
  copies with it. Set up once, below;
- **by hand, before a release whose migration changes or moves existing rows** (an `UPDATE`, a `DELETE`, a column dropped or
  changed, rows moved between tables) — `pg_dump` from the owner's computer ("Take a copy (PowerShell)"). A migration that only
  adds (a new table, a new column with a default, an index) needs none: it touches no row, a failed one is rolled back whole and
  the old deploy keeps running — the nightly copy is enough (the owner, 2026-11-18).

## The nightly copy to Cloudflare R2 (set up once)

1. **Cloudflare → R2 → Create bucket**: name `scan2play-backups`, location hint Europe, **not public**. In the bucket: **Settings →
   Object lifecycle rules → Add rule**: delete objects 30 days after they were made (the copies hold personal data; thirty nightly
   copies are enough to go back to).
2. **R2 → Manage API tokens → Create Account API token**: permission **Object Read & Write**, **only the bucket
   `scan2play-backups`**, no end date. Cloudflare shows the Access Key ID, the Secret Access Key and the endpoint
   (`https://<account id>.r2.cloudflarestorage.com`) once — they go straight into Railway (step 4), never into the repo, a chat or
   a screenshot.
3. **Railway → the project `celebrated-enjoyment` (open it first; "+ Create" on its canvas) → GitHub Repo →
   `Adas553/scan2play`**, name the service `db-backup`. **In that project, not a new one**: `${{Postgres.…}}` and the private
   network reach only the services of the same project — in a project of its own the run ends with "the variable PGHOST is not
   set" (it happened, 2026-11-16). Set the Root Directory before the first build, or Railway builds the application itself. Its **Settings**: Root
   Directory `tools/db-backup` (Railway builds its `Dockerfile`), Watch Paths `tools/db-backup/**` (a change of the application
   does not rebuild it), **Cron Schedule `17 3 * * *`** (UTC: 4:17 or 5:17 in Poland — after the parties, before the night's jobs
   are of interest), no domain, no healthcheck.
4. Its **Variables**: `PGHOST` = `${{Postgres.PGHOST}}`, and the same for `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD`
   (references to the Postgres service — the private address, no TCP proxy); `R2_ENDPOINT`, `R2_BUCKET` = `scan2play-backups`,
   `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` from step 2.
5. Run it once by hand (the service's **Run now**): its log ends with `Backup done: scan2play-<day>.dump (<size> bytes)` and the
   file is in the bucket. A failure is a line `BACKUP FAILED: …` and a crashed run — Railway's notifications (Account →
   Notifications → deployment crashed) send an e-mail.

Once a month: the bucket holds last night's file, and one of them restores ("Check that a copy restores": download it from the
bucket in Cloudflare first). When PostgreSQL on Railway moves to a new major version, raise the image in
`tools/db-backup/Dockerfile` with it — `pg_dump` must not be older than the server.

The script was tried against a local PostgreSQL with a stand-in for the upload (2026-11-16); the upload to R2 itself and the
image are first run in step 5.

## A copy by hand (before a migration that changes existing rows)

The owner takes a copy with `pg_dump` from their own computer (Section 10 of `PROJECT_CONTEXT.md`). A copy is one small file (the data is well under the volume's 500 MB).

The copy holds the guests' words, the DJs' parties and Google account ids: personal data. Keep it **outside the repository**
(`*.dump` is in `.gitignore` anyway) and off shared folders; delete copies older than 90 days. To restore from a nightly copy,
download its file from the R2 bucket and use it as the file below.

## Take a copy (PowerShell)

0. Railway → service **Postgres** → **Settings** → **Networking** → **TCP Proxy** → add it (port 5432). It gives the database a
   public address, which `DATABASE_PUBLIC_URL` names — only for the copy: the app reaches the database inside Railway
   (`postgres.railway.internal`) and never needs it.
1. Railway → project → service **Postgres** → **Variables** → `DATABASE_PUBLIC_URL` → the eye icon → copy the value (fresh each time:
   a new TCP proxy may get another host and port). It holds the database's password: never paste it into the repo, a chat or a
   screenshot.
2. In PowerShell (the PostgreSQL 18 tools of the local install; production runs 18 too, and `pg_dump` must not be older than the
   server):

```powershell
$pg = "C:\Program Files\PostgreSQL\18\bin"
$dir = "$HOME\scan2play-backups"
New-Item -ItemType Directory -Force $dir | Out-Null
$url = Read-Host "DATABASE_PUBLIC_URL"          # paste the value; it is kept only in this window
& "$pg\pg_dump.exe" --dbname=$url --format=custom --no-owner --no-privileges `
    --file="$dir\scan2play-$(Get-Date -Format yyyy-MM-dd).dump"
Remove-Variable url
Get-ChildItem $dir | Sort-Object Name -Descending | Select-Object -First 3 Name, Length
```

The database sleeps when nobody uses it (Railway's serverless): the first connection wakes it, which can take a few seconds. A
"timeout" or "connection refused" on the first try — run the `pg_dump` line again.

3. **Remove the TCP proxy at once** (the same place, the bin icon): while it is there the production database is open to the whole
   internet on a public port, guarded by its password alone, and such ports are scanned all the time. The copy is in the file.

## Check that a copy restores (once a month, or after changing these steps)

Into a throwaway local database — **never** into the developer's `scan2play` (`CLAUDE.md`):

```powershell
$env:PGPASSWORD = "1111"                        # the local PostgreSQL (application-local.properties)
$file = (Get-ChildItem "$HOME\scan2play-backups\*.dump" | Sort-Object Name -Descending | Select-Object -First 1).FullName
& "$pg\createdb.exe" -U postgres s2p_restore_check
& "$pg\pg_restore.exe" -U postgres -d s2p_restore_check --no-owner --no-privileges $file
& "$pg\psql.exe" -U postgres -d s2p_restore_check -c "SELECT count(*) AS parties FROM party_settings" -c "SELECT count(*) AS requests FROM song_requests" -c "SELECT max(version) AS flyway FROM flyway_schema_history"
& "$pg\dropdb.exe" -U postgres s2p_restore_check
Remove-Item Env:PGPASSWORD
```

The counts should match what the panel shows; `flyway` is the newest migration (`V<n>`).

## Restore production (only after losing data)

1. Stop the app: Railway → service **scan2play** → the deployment's menu → **Remove** (or set its replicas to 0), so nothing
   writes meanwhile.
2. Take a copy of the broken state first (the steps above, another file name, the TCP proxy kept until step 5) — it may still hold
   something the old copy lacks.
3. Restore over it:

```powershell
$url = Read-Host "DATABASE_PUBLIC_URL"
& "$pg\pg_restore.exe" --dbname=$url --clean --if-exists --no-owner --no-privileges "$HOME\scan2play-backups\scan2play-<day>.dump"
Remove-Variable url
```

4. Deploy the app again (**Redeploy**). Flyway sees the restored `flyway_schema_history` and applies only migrations newer than
   the copy. Everything written after the copy was taken is lost.
5. Remove the TCP proxy (step 3 of "Take a copy"): the restore needed it, the app does not.
