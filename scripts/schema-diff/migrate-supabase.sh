#!/usr/bin/env bash
# Apply pending Flyway migrations to Supabase using repo-root .env credentials.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ENV_FILE="$ROOT/.env"
MIGRATIONS="$ROOT/backend/src/main/resources/db/migration"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing $ENV_FILE" >&2
  exit 1
fi

# shellcheck disable=SC1090
set -a
source <(grep -E '^(LMS_DB_URL|LMS_DB_USERNAME|LMS_DB_PASSWORD|APP_TENANT_DATASOURCE_PASSWORD)=' "$ENV_FILE" | sed 's/\r$//')
set +a

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

docker run --rm \
  -v "$MIGRATIONS_DOCKER:/flyway/sql:ro" \
  flyway/flyway:11.1.0 \
  -locations=filesystem:/flyway/sql \
  -url="$LMS_DB_URL" \
  -user="$LMS_DB_USERNAME" \
  -password="$LMS_DB_PASSWORD" \
  -placeholders.tenant_app_role=lms_tenant_app \
  -placeholders.tenant_app_password="$APP_TENANT_DATASOURCE_PASSWORD" \
  migrate

echo "Flyway migrate completed."
