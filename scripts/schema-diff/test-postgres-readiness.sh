#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=postgres-readiness.sh
source "$ROOT/scripts/schema-diff/postgres-readiness.sh"

probe_index=0
probe_results=(fail ready fail ready ready ready)

postgres_ready_probe() {
  local _container="$1"
  local result="${probe_results[$probe_index]}"
  probe_index=$((probe_index + 1))
  [[ "$result" == "ready" ]]
}

wait_for_stable_postgres "fake-container" 6 3 0

if ((probe_index != 6)); then
  echo "Expected readiness to survive the temporary-server restart; used $probe_index probes" >&2
  exit 1
fi

echo "Postgres stable-readiness regression test passed."
