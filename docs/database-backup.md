# Backing up the production database (by hand)

Railway's scheduled volume backups are not on the Hobby plan, so the owner takes a copy with `pg_dump` from their own computer —
**once a week, and before every release with a migration** (Section 10 of `PROJECT_CONTEXT.md`). A copy is one small file (the data is well under the volume's 500 MB).

The copy holds the guests' words, the DJs' parties and Google account ids: personal data. Keep it **outside the repository**
(`*.dump` is in `.gitignore` anyway) and off shared folders; delete copies older than 90 days.

## Take a copy (PowerShell)

1. Railway → project → service **Postgres** → **Variables** → `DATABASE_PUBLIC_URL` → the eye icon → copy the value.
   It holds the database's password: never paste it into the repo, a chat or a screenshot.
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
2. Take a copy of the broken state first (the steps above, another file name) — it may still hold something the old copy lacks.
3. Restore over it:

```powershell
$url = Read-Host "DATABASE_PUBLIC_URL"
& "$pg\pg_restore.exe" --dbname=$url --clean --if-exists --no-owner --no-privileges "$HOME\scan2play-backups\scan2play-<day>.dump"
Remove-Variable url
```

4. Deploy the app again (**Redeploy**). Flyway sees the restored `flyway_schema_history` and applies only migrations newer than
   the copy. Everything written after the copy was taken is lost.
