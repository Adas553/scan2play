# The nightly copy of the production database to Cloudflare R2 (docs/database-backup.md). A Railway service of its own with a cron
# schedule: it starts, takes the copy, and exits. pg_dump must not be older than the server (PostgreSQL 18 on Railway) — raise this
# image with the database.
FROM postgres:18-alpine
RUN apk add --no-cache aws-cli
COPY backup.sh /backup.sh
ENTRYPOINT ["sh", "/backup.sh"]
