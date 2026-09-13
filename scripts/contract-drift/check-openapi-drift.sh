#!/usr/bin/env bash
# Contract drift gate.
#
# Three artifacts are meant to describe one API surface:
#
#   backend controllers  ->  openapi/openapi.json  ->  frontend generated TypeScript
#
# Nothing currently forces them to agree. The OpenAPI export is opt-in
# (OpenApiContractExportTest#exportOpenApiSnapshot is @EnabledIfSystemProperty), and CI runs a
# plain `mvnw verify`, so a controller signature can change and both downstream artifacts keep
# describing the old shape. The frontend then type-checks green against a schema that no longer
# matches the server — which is exactly the seam findings M13, M14 and H28 sit on.
#
# This regenerates both and fails on any diff. It is deterministic: no network, no flake.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

fail() {
  echo "" >&2
  echo "✖ contract drift: $1" >&2
  echo "" >&2
  shift
  for line in "$@"; do echo "  $line" >&2; done
  exit 1
}

echo "▶ exporting OpenAPI document from the running backend context"
(
  cd "$ROOT/backend"
  ./mvnw -q -Dtest=OpenApiContractExportTest -Dopenapi.export=true \
    -DfailIfNoSpecifiedTests=false test
)

if ! git diff --quiet -- openapi/openapi.json; then
  git diff --stat -- openapi/openapi.json >&2
  fail "openapi/openapi.json does not match the backend controllers." \
    "The committed contract is stale. Commit the regenerated document:" \
    "  git add openapi/openapi.json" \
    "Then regenerate the frontend types so all three artifacts agree:" \
    "  npm --prefix frontend run generate:api-types"
fi
echo "  openapi/openapi.json matches the backend."

echo "▶ regenerating frontend types from the OpenAPI document"
npm --prefix frontend run generate:api-types --silent

if ! git diff --quiet -- frontend/src/lib/api/generated; then
  git diff --stat -- frontend/src/lib/api/generated >&2
  fail "frontend/src/lib/api/generated is stale relative to openapi/openapi.json." \
    "The frontend is type-checking against a contract the server no longer serves." \
    "Commit the regenerated types:" \
    "  git add frontend/src/lib/api/generated"
fi
echo "  frontend/src/lib/api/generated matches the contract."

echo ""
echo "✔ contract drift: backend, OpenAPI document and frontend types agree."
