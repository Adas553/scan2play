#!/bin/sh
# One copy of the database to an S3 bucket (Cloudflare R2): pg_dump in the custom format, checked that it reads, uploaded as
# scan2play-<day>.dump. Old copies are deleted by the bucket's own lifecycle rule, not here: the token this runs with may write
# objects and nothing more it does not need. Every value comes from the service's variables (docs/database-backup.md) — none is
# ever printed.
#
#   PGHOST PGPORT PGDATABASE PGUSER PGPASSWORD   the database, by Railway's private network (references to the Postgres service)
#   R2_ENDPOINT                                  https://<account id>.r2.cloudflarestorage.com
#   R2_BUCKET                                    the bucket's name
#   AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY      the R2 API token (Object Read & Write, this bucket only)
set -eu

for name in PGHOST PGDATABASE PGUSER PGPASSWORD R2_ENDPOINT R2_BUCKET AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY; do
    eval "value=\${$name:-}"
    if [ -z "$value" ]; then
        echo "BACKUP FAILED: the variable $name is not set" >&2
        exit 1
    fi
done

# R2 has one region, and does not take the checksums newer versions of the AWS CLI add to every upload
export AWS_DEFAULT_REGION=auto
export AWS_REQUEST_CHECKSUM_CALCULATION=when_required
export AWS_RESPONSE_CHECKSUM_VALIDATION=when_required

file="scan2play-$(date -u +%Y-%m-%d).dump"
path="${TMPDIR:-/tmp}/$file"
trap 'rm -f "$path"' EXIT

echo "Backup: dumping the database"
pg_dump --format=custom --no-owner --no-privileges --file="$path"

# a copy that cannot be read is no copy: its table of contents must list the parties' table
if ! pg_restore --list "$path" | grep -q "TABLE DATA public party_settings"; then
    echo "BACKUP FAILED: the dump does not read, or holds no party_settings" >&2
    exit 1
fi

size=$(wc -c < "$path" | tr -d ' ')
echo "Backup: uploading $file ($size bytes)"
aws s3 cp "$path" "s3://$R2_BUCKET/$file" --endpoint-url "$R2_ENDPOINT" --only-show-errors
echo "Backup done: $file ($size bytes)"
