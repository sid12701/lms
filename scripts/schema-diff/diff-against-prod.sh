#!/usr/bin/env bash
# Diff a live database schema (e.g. Supabase prod) against the committed reference snapshot.
#
# Usage:
#   DATABASE_URL='postgresql://user:pass@host:5432/db?sslmode=require' \
#     ./scripts/schema-diff/diff-against-prod.sh
#
# Writes:
#   scripts/schema-diff/artifacts/prod-schema.normalized.sql
#   scripts/schema-diff/artifacts/prod-vs-reference.diff
set -euo pipefail

if [[ -z "${DATABASE_URL:-}" ]]; then
  echo "DATABASE_URL is required (postgresql://...)" >&2
  exit 1
fi

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ARTIFACTS="$ROOT/scripts/schema-diff/artifacts"
REFERENCE="$ARTIFACTS/reference-schema.normalized.sql"
PROD="$ARTIFACTS/prod-schema.normalized.sql"
DIFF="$ARTIFACTS/prod-vs-reference.diff"

if [[ ! -f "$REFERENCE" ]]; then
  echo "Missing reference snapshot. Run ./scripts/schema-diff/generate-reference.sh first." >&2
  exit 1
fi

mkdir -p "$ARTIFACTS"

pg_dump "$DATABASE_URL" --schema-only --no-owner --no-privileges --schema=public \
  | python3 "$ROOT/scripts/schema-diff/normalize_schema.py" \
  > "$PROD"

if diff -u "$REFERENCE" "$PROD" > "$DIFF"; then
  echo "No structural drift: prod matches reference."
  rm -f "$DIFF"
  exit 0
fi

echo "Structural drift detected. See $DIFF"
exit 1
