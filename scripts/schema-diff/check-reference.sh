#!/usr/bin/env bash
# Regenerate the reference schema and fail if it differs from the committed artifact.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
REFERENCE="$ROOT/scripts/schema-diff/artifacts/reference-schema.normalized.sql"

"$ROOT/scripts/schema-diff/generate-reference.sh"

if git -C "$ROOT" diff --quiet -- "$REFERENCE"; then
  echo "Reference schema artifact is up to date."
  exit 0
fi

echo "Reference schema artifact is stale. Commit the updated file:" >&2
echo "  $REFERENCE" >&2
git -C "$ROOT" diff --stat -- "$REFERENCE" >&2 || true
exit 1
