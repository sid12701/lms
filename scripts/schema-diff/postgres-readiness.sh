#!/usr/bin/env bash

# Probe seam kept separate so the readiness state machine can be tested
# without Docker. Tests override this function after sourcing the file.
postgres_ready_probe() {
  local container="$1"
  docker exec "$container" pg_isready -U lms -d lms >/dev/null 2>&1
}

# The official Postgres image briefly starts a temporary server during init,
# stops it, and then starts the final server. A single successful pg_isready
# can therefore be a false ready signal. Require a stable streak instead.
wait_for_stable_postgres() {
  local container="$1"
  local max_attempts="${2:-60}"
  local required_streak="${3:-3}"
  local sleep_seconds="${4:-1}"
  local ready_streak=0
  local attempt

  for ((attempt = 1; attempt <= max_attempts; attempt += 1)); do
    if postgres_ready_probe "$container"; then
      ready_streak=$((ready_streak + 1))
      if ((ready_streak >= required_streak)); then
        return 0
      fi
    else
      ready_streak=0
    fi

    if ((attempt < max_attempts)); then
      sleep "$sleep_seconds"
    fi
  done

  return 1
}
