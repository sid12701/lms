#!/usr/bin/env bash
# Local stack quality gate, ordered so the cheap checks fail first. GitHub also runs browser,
# supply-chain and full-stack journey jobs that are intentionally not duplicated here.
#
#   ./scripts/quality/gate.sh          fast tier, only for the stacks you actually touched
#   ./scripts/quality/gate.sh --full   full local stack gate, including the slow suites
#   ./scripts/quality/gate.sh --all    ignore change detection, run the fast tier everywhere
#
# The fast tier is what the pre-push hook runs. It is scoped to changed paths and kept under a
# couple of minutes, because a gate slower than that gets bypassed with --no-verify and then it
# protects nothing.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

MODE="fast"
FORCE_ALL=0
for arg in "$@"; do
  case "$arg" in
    --full) MODE="full" ;;
    --all) FORCE_ALL=1 ;;
    -h|--help) sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

BOLD=$'\033[1m'; RED=$'\033[31m'; GREEN=$'\033[32m'; DIM=$'\033[2m'; RESET=$'\033[0m'
FAILED=()
PASSED=()

run_step() {
  local label="$1"; shift
  printf '\n%s▶ %s%s\n' "$BOLD" "$label" "$RESET"
  if "$@"; then
    PASSED+=("$label")
  else
    FAILED+=("$label")
    printf '%s  ✖ %s failed%s\n' "$RED" "$label" "$RESET"
  fi
}

# ---------------------------------------------------------------------------
# Change detection: compare against the upstream branch when there is one, and
# fall back to origin/main. A brand-new branch with no upstream checks everything.
# ---------------------------------------------------------------------------
base_ref() {
  local upstream
  if upstream="$(git rev-parse --abbrev-ref --symbolic-full-name '@{u}' 2>/dev/null)"; then
    git merge-base HEAD "$upstream" 2>/dev/null && return
  fi
  git merge-base HEAD origin/main 2>/dev/null && return
  echo ""
}

BASE="$(base_ref)"
if [[ $FORCE_ALL -eq 1 || -z "$BASE" ]]; then
  CHANGED="$(git ls-files)"
  [[ -z "$BASE" ]] && printf '%s(no upstream found — checking everything)%s\n' "$DIM" "$RESET"
else
  # Committed changes plus anything currently dirty, so the gate sees the real tree.
  CHANGED="$({ git diff --name-only "$BASE"...HEAD; git diff --name-only HEAD; git ls-files --others --exclude-standard; } | sort -u)"
fi

touched() { grep -qE "$1" <<<"$CHANGED"; }

BACKEND_TOUCHED=0;  touched '^(backend/|pom\.xml$)' && BACKEND_TOUCHED=1
FRONTEND_TOUCHED=0; touched '^frontend/' && FRONTEND_TOUCHED=1
CONTRACT_TOUCHED=0; touched '^(backend/src/main/java/com/bhawana/lms/web/|openapi/)' && CONTRACT_TOUCHED=1
MIGRATION_TOUCHED=0; touched '^backend/src/main/resources/db/migration/' && MIGRATION_TOUCHED=1

printf '%sscope:%s backend=%s frontend=%s contract=%s migrations=%s  %s(mode: %s)%s\n' \
  "$BOLD" "$RESET" "$BACKEND_TOUCHED" "$FRONTEND_TOUCHED" "$CONTRACT_TOUCHED" "$MIGRATION_TOUCHED" \
  "$DIM" "$MODE" "$RESET"

# ---------------------------------------------------------------------------
# Fast tier
# ---------------------------------------------------------------------------
if [[ $BACKEND_TOUCHED -eq 1 ]]; then
  # Architecture rules are the highest value per second spent: they encode H03, H04, H12, H24
  # and ADR 0005, and they finish in about thirty seconds.
  run_step "backend architecture rules" \
    bash -c "cd '$ROOT/backend' && ./mvnw -q -o -Dtest='*ArchitectureTest' -DfailIfNoSpecifiedTests=false test"
fi

if [[ $FRONTEND_TOUCHED -eq 1 ]]; then
  run_step "frontend typecheck" bash -c "cd '$ROOT/frontend' && npm run --silent typecheck"
  run_step "frontend lint"      bash -c "cd '$ROOT/frontend' && npm run --silent lint"
  run_step "frontend format"    bash -c "cd '$ROOT/frontend' && npm run --silent format:check"
  run_step "frontend encoding"  bash -c "cd '$ROOT/frontend' && npm run --silent check:encoding"
fi

if [[ $MIGRATION_TOUCHED -eq 1 ]]; then
  run_step "schema drift" bash -c "cd '$ROOT' && ./scripts/schema-diff/check-reference.sh"
fi

# Whitespace damage is invisible in review and shows up as a CRLF shebang that dies with
# exit 127 on Linux. Cheap enough to always run.
run_step "whitespace and line endings" \
  bash -c "cd '$ROOT' && git -c core.whitespace=cr-at-eol diff --check HEAD"

# ---------------------------------------------------------------------------
# Full tier
# ---------------------------------------------------------------------------
if [[ "$MODE" == "full" ]]; then
  if [[ $CONTRACT_TOUCHED -eq 1 || $FORCE_ALL -eq 1 ]]; then
    run_step "API contract drift" bash -c "cd '$ROOT' && ./scripts/contract-drift/check-openapi-drift.sh"
  fi
  if [[ $FRONTEND_TOUCHED -eq 1 || $FORCE_ALL -eq 1 ]]; then
    run_step "frontend unit tests + coverage ratchet" \
      bash -c "cd '$ROOT/frontend' && npm run --silent test:cov"
    run_step "frontend build"      bash -c "cd '$ROOT/frontend' && npm run --silent build"
    run_step "frontend bundle boundaries" bash -c "cd '$ROOT/frontend' && npm run --silent check:bundle"
  fi
  if [[ $BACKEND_TOUCHED -eq 1 || $FORCE_ALL -eq 1 ]]; then
    # Carries the PostgreSQL overlapping-transaction tests and the coverage ratchet. Needs Docker.
    run_step "backend verify (tests + coverage ratchet)" \
      bash -c "cd '$ROOT/backend' && ./mvnw -q verify"
    run_step "backend skipped-test policy" \
      python3 "$ROOT/scripts/quality/check-junit-skips.py" "$ROOT/backend/target/surefire-reports"
  fi
fi

# ---------------------------------------------------------------------------
printf '\n%s────────────────────────────────────────%s\n' "$DIM" "$RESET"
for step in "${PASSED[@]:-}"; do [[ -n "$step" ]] && printf '%s  ✔ %s%s\n' "$GREEN" "$step" "$RESET"; done
for step in "${FAILED[@]:-}"; do [[ -n "$step" ]] && printf '%s  ✖ %s%s\n' "$RED" "$step" "$RESET"; done

if [[ ${#FAILED[@]} -gt 0 ]]; then
  printf '\n%s✖ quality gate failed: %s%s\n' "$RED" "${FAILED[*]}" "$RESET"
  if [[ "$MODE" == "fast" ]]; then
    printf '%s  Run ./scripts/quality/gate.sh --full for the complete picture.%s\n' "$DIM" "$RESET"
  fi
  exit 1
fi

printf '\n%s✔ quality gate passed%s' "$GREEN" "$RESET"
if [[ "$MODE" == "fast" ]]; then
  printf ' %s(fast tier — CI still runs tests, coverage, contract drift and the journey suite)%s' "$DIM" "$RESET"
fi
printf '\n'
