#!/usr/bin/env bash
# Realign the Flyway schema history on Supabase with the migration files in this repo.
#
# Run this when `flyway validate` (or backend startup) reports a checksum or description
# mismatch for a migration that was edited in place after it had already been applied.
# Repair only rewrites the flyway_schema_history table — checksums, descriptions, and
# entries for failed migrations. It never re-runs a migration and never touches
# application data.
#
# Pass `info` as the first argument to inspect state without writing anything.
set -euo pipefail

COMMAND="${1:-repair}"
case "$COMMAND" in
  repair|info|validate) ;;
  *)
    echo "Usage: $(basename "$0") [repair|info|validate]" >&2
    exit 2
    ;;
esac

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ENV_FILE="$ROOT/.env"
MIGRATIONS="$ROOT/backend/src/main/resources/db/migration"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing $ENV_FILE" >&2
  exit 1
fi

# Sourced through a temp file rather than `source <(...)`: macOS ships bash 3.2, where
# sourcing a process substitution silently sets nothing (the sibling scripts here have
# that bug and only work under bash 4+).
ENV_TMP="$(mktemp)"
trap 'rm -f "$ENV_TMP"' EXIT
grep -E '^(LMS_DB_URL|LMS_DB_USERNAME|LMS_DB_PASSWORD|APP_TENANT_DATASOURCE_PASSWORD)=' "$ENV_FILE" \
  | sed 's/\r$//' > "$ENV_TMP"

# shellcheck disable=SC1090
set -a
source "$ENV_TMP"
set +a

if [[ -z "${LMS_DB_URL:-}" || -z "${LMS_DB_USERNAME:-}" || -z "${LMS_DB_PASSWORD:-}" ]]; then
  echo "LMS_DB_URL / LMS_DB_USERNAME / LMS_DB_PASSWORD must all be set in $ENV_FILE" >&2
  exit 1
fi

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

# Placeholders are only needed so migration resolution succeeds; Flyway computes
# checksums from the raw file content, so the values never affect the repair result.
docker run --rm \
  -v "$MIGRATIONS_DOCKER:/flyway/sql:ro" \
  flyway/flyway:11.1.0 \
  -locations=filesystem:/flyway/sql \
  -url="$LMS_DB_URL" \
  -user="$LMS_DB_USERNAME" \
  -password="$LMS_DB_PASSWORD" \
  -placeholders.tenant_app_role=lms_tenant_app \
  -placeholders.tenant_app_password="${APP_TENANT_DATASOURCE_PASSWORD:-unused}" \
  "$COMMAND"

echo "Flyway $COMMAND completed."
