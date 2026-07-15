#!/usr/bin/env bash
# Build a normalized structural schema snapshot from a fresh Flyway migrate (Postgres 17).
# Committed output: scripts/schema-diff/artifacts/reference-schema.normalized.sql
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MIGRATIONS="$ROOT/backend/src/main/resources/db/migration"
ARTIFACTS="$ROOT/scripts/schema-diff/artifacts"
REFERENCE="$ARTIFACTS/reference-schema.normalized.sql"
CONTAINER="lms-b12-schema-ref-$$"
FLYWAY_IMAGE="${FLYWAY_IMAGE:-flyway/flyway:11.1.0}"
POSTGRES_IMAGE="${POSTGRES_IMAGE:-postgres:17-alpine}"

to_docker_volume_path() {
  local path="$1"
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -w "$path" | sed 's|\\|/|g'
    return
  fi
  if [[ "$path" =~ ^/([a-zA-Z])/(.*)$ ]]; then
    local drive="${BASH_REMATCH[1]}"
    local rest="${BASH_REMATCH[2]}"
    printf '%s:/%s' "$(printf '%s' "$drive" | tr '[:lower:]' '[:upper:]')" "$rest"
    return
  fi
  printf '%s' "$path"
}

MIGRATIONS_DOCKER="$(to_docker_volume_path "$MIGRATIONS")"

cleanup() {
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
}
trap cleanup EXIT

mkdir -p "$ARTIFACTS"

if [[ ! -d "$MIGRATIONS" ]] || [[ -z "$(ls -A "$MIGRATIONS"/*.sql 2>/dev/null)" ]]; then
  echo "No SQL migrations found under $MIGRATIONS" >&2
  exit 1
fi

docker run -d --name "$CONTAINER" \
  -e POSTGRES_DB=lms \
  -e POSTGRES_USER=lms \
  -e POSTGRES_PASSWORD=lms \
  "$POSTGRES_IMAGE" >/dev/null

echo "Waiting for Postgres..."
for _ in $(seq 1 60); do
  if docker exec "$CONTAINER" pg_isready -U lms -d lms >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

docker exec "$CONTAINER" pg_isready -U lms -d lms >/dev/null

echo "Running Flyway migrate from $MIGRATIONS_DOCKER ..."
docker run --rm --network "container:$CONTAINER" \
  -v "$MIGRATIONS_DOCKER:/flyway/sql:ro" \
  "$FLYWAY_IMAGE" \
  -locations=filesystem:/flyway/sql \
  -url=jdbc:postgresql://localhost:5432/lms \
  -user=lms \
  -password=lms \
  -placeholders.tenant_app_role=lms_tenant_app \
  -placeholders.tenant_app_password=lms_tenant_app_password \
  migrate

echo "Dumping schema..."
docker exec "$CONTAINER" pg_dump --schema-only --no-owner --no-privileges --schema=public -U lms lms \
  | python3 "$ROOT/scripts/schema-diff/normalize_schema.py" \
  > "$REFERENCE"

LINE_COUNT="$(wc -l < "$REFERENCE" | tr -d ' ')"
if [[ "$LINE_COUNT" -lt 100 ]]; then
  echo "Reference schema looks too small ($LINE_COUNT lines). Check Docker volume mount." >&2
  exit 1
fi

echo "Wrote $REFERENCE ($LINE_COUNT lines)"
