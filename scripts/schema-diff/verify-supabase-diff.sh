#!/usr/bin/env bash
# Dump Supabase public schema and diff against committed reference.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ENV_FILE="$ROOT/.env"
REFERENCE="$ROOT/scripts/schema-diff/artifacts/reference-schema.normalized.sql"
PROD="$ROOT/scripts/schema-diff/artifacts/prod-schema.normalized.sql"
DIFF="$ROOT/scripts/schema-diff/artifacts/prod-vs-reference.diff"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing $ENV_FILE" >&2
  exit 1
fi

# Sourced through a temp file rather than `source <(...)`, which silently sets nothing
# under the bash 3.2 that macOS ships (see repair-supabase.sh).
ENV_TMP="$(mktemp)"
trap 'rm -f "$ENV_TMP"' EXIT
grep -E '^(LMS_DB_URL|LMS_DB_USERNAME|LMS_DB_PASSWORD)=' "$ENV_FILE" | sed 's/\r$//' > "$ENV_TMP"

set -a
# shellcheck disable=SC1090
source "$ENV_TMP"
set +a

if [[ -z "${LMS_DB_URL:-}" || -z "${LMS_DB_USERNAME:-}" || -z "${LMS_DB_PASSWORD:-}" ]]; then
  echo "LMS_DB_URL / LMS_DB_USERNAME / LMS_DB_PASSWORD must all be set in $ENV_FILE" >&2
  exit 1
fi

jdbc="${LMS_DB_URL#jdbc:}"
pg_url="postgresql://${LMS_DB_USERNAME}:${LMS_DB_PASSWORD}@${jdbc#postgresql://}"

docker run --rm postgres:17-alpine pg_dump "$pg_url" \
  --schema-only --no-owner --no-privileges --schema=public \
  | python3 "$ROOT/scripts/schema-diff/normalize_schema.py" \
  > "$PROD"

if diff -u "$REFERENCE" "$PROD" > "$DIFF"; then
  echo "OK: Supabase public schema matches reference."
  rm -f "$DIFF"
  exit 0
fi

echo "Structural drift detected. See $DIFF"
head -40 "$DIFF"
exit 1
