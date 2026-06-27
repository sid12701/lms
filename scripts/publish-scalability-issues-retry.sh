#!/usr/bin/env bash
# Retry the 14 failed issue creations via REST (avoids GraphQL label resolution),
# then patch the 4 issues whose Blocked-by references were left dangling.
set -uo pipefail

REPO="sid12701/lms"
MAP_FILE="scripts/scalability-issue-map.txt"

api_retry() { # passes all args to gh api, retries on failure
  local attempt out
  for attempt in 1 2 3 4 5; do
    if out=$(gh api "$@" 2>&1); then
      echo "$out"
      return 0
    fi
    echo "  attempt $attempt failed: $out" >&2
    sleep 4
  done
  echo "FAILED after retries: gh api $*" >&2
  return 1
}

create() { # title body -> number
  local title="$1" body="$2" num
  num=$(api_retry "repos/$REPO/issues" -f title="$title" -f body="$body" -f "labels[]=ready-for-agent" --jq .number) || exit 1
  echo "$num	$title" >> "$MAP_FILE"
  echo "created #$num  $title" >&2
  echo "$num"
}

patch_body() { # number body
  api_retry -X PATCH "repos/$REPO/issues/$1" -f body="$2" --jq .number > /dev/null || exit 1
  echo "patched #$1" >&2
}

# ---- known numbers from the first run ----
N3=199; N9=205; N10=206; N17_OLD_REF=""; N22=213; N24_OLD_REF=""; N33=217; N37=219

# ---------------- missing Phase 1 issues ----------------

B=$(cat <<'EOF'
## What to build

Payment bounce/reversal support (decision D3b — required for NACH/UPI collection reality where 5–15% bounce rates are normal). New payment statuses (BOUNCED, REVERSED) with check constraints; an idempotent reversal command exposed to ops and to LSPs; allocation rollback via the existing recompute mechanism; the affected installment reopens; and closure unwind — if the bounced payment was the one that closed the loan, the application transitions back from CLOSED to UNDER_REPAYMENT and the loan account reopens (state-machine addition). Emits a new webhook event for the LSP and full audit rows. The exact-full-EMI payment rule stays.

Source: scalability-assessment-2026-06-10.md §17.2, WI-1.6 (finding F18, decision D3b).

## Acceptance criteria

- [ ] Mid-tenure bounce reopens the installment and restores outstanding amounts
- [ ] Closing-EMI bounce reopens the closed loan and account
- [ ] Reversal is idempotent; a second reversal of the same payment is rejected
- [ ] Webhook event is enqueued through the outbox and audit rows are written
- [ ] MIS/ledger outputs reflect the reversal correctly
EOF
)
N11=$(create "WI-1.6: Payment bounce/reversal incl. closed-loan reopen + webhook event" "$B

## Blocked by

- #$N9
- #$N10")

B=$(cat <<'EOF'
## What to build

Define rate limiting behavior when Redis is unavailable (today it is undefined). The rate-limit filter catches Redis connectivity failures explicitly: fail-open for LSP business traffic (with a deduplicated ops alert and a metric), fail-closed for auth endpoints. Recovery is automatic when Redis returns.

Source: scalability-assessment-2026-06-10.md §17.2, WI-1.8 (finding R12/F9).

## Acceptance criteria

- [ ] With Redis stopped, LSP API traffic passes and auth endpoints are limited per policy
- [ ] Ops alert raised once (deduplicated) on Redis unavailability, plus a metric
- [ ] Integration test covers the Redis-down and recovery paths
- [ ] Policy documented in the API standards doc
EOF
)
N13=$(create "WI-1.8: Redis failure policy for rate limiting (fail-open LSP / fail-closed auth)" "$B

## Blocked by

None - can start immediately")

B=$(cat <<'EOF'
## What to build

Brute-force lockout for LSP API client credentials, mirroring the existing app-user lockout design: failed-attempt counter, lockout window, auth-event audit parity, extension of the brute-force alert rules, and an admin unlock action.

Source: scalability-assessment-2026-06-10.md §17.2, WI-1.9 (finding R13/F11).

## Acceptance criteria

- [ ] N failed token attempts lock the client, with an audit row
- [ ] Lockout expires on schedule and is admin-unlockable
- [ ] Successful authentication resets the counter
- [ ] Brute-force alert rule fires at the configured threshold
EOF
)
N14=$(create "WI-1.9: ApiClient credential lockout parity with AppUser" "$B

## Blocked by

None - can start immediately")

B=$(cat <<'EOF'
## What to build

Take the document upload's object-storage call out of the database transaction: store bytes first, then a short transaction records the checklist/audit updates (note an orphaned-object sweep as the failure-window cleanup). Set explicit servlet multipart max-file-size and max-request-size aligned with the per-document-type policy caps, so the container rejects oversize uploads before application code.

Source: scalability-assessment-2026-06-10.md §17.2, WI-1.12 (findings R7/F16).

## Acceptance criteria

- [ ] No DB connection is held during object-storage upload (pool metrics flat during the 100×5MB spike test)
- [ ] Oversize upload rejected at the container layer with a clear error
- [ ] Mid-upload failure leaves no checklist/audit row
- [ ] Existing upload API contract unchanged for clients
EOF
)
N17=$(create "WI-1.12: Document upload outside the DB transaction + explicit multipart limits" "$B

## Blocked by

None - can start immediately")

B=$(cat <<'EOF'
## What to build

Phase 1 housekeeping per decisions D9/D10/D1: remove RabbitMQ from the infrastructure compose and configuration templates (nothing consumes it); write an ADR recording INR-only currency by design; write an ADR recording the deployment topology (2 API pods + 1 worker pod, workers disabled on API pods via config flags).

Source: scalability-assessment-2026-06-10.md §17.2, WI-1.13 (decisions D1, D9, D10).

## Acceptance criteria

- [ ] No RabbitMQ references remain in infra or config; local stack boots without it
- [ ] INR-only ADR merged in docs/adr/ following existing numbering and format
- [ ] Topology ADR merged in docs/adr/
EOF
)
N18B=$(create "WI-1.13a: Housekeeping — remove RabbitMQ; ADRs for INR-only and deployment topology" "$B

## Blocked by

None - can start immediately")

# ---------------- missing Phase 2 issues ----------------

B=$(cat <<'EOF'
## What to build

Streaming report generation with guardrails. Replace full-entity hydration with a keyset-paged projection loop that streams CSV rows to storage (multipart upload), bounding memory regardless of date range. Guardrails: maximum 366-day range per request, a cap on concurrent pending requests per user, and a file-size cap.

Source: scalability-assessment-2026-06-10.md §17.3, WI-2.2 (finding R11/F6).

## Acceptance criteria

- [ ] One-year full-portfolio report on seeded data completes with flat worker memory
- [ ] Over-range request rejected with 400 and a clear message
- [ ] Per-user concurrent-pending cap enforced
- [ ] Output byte-identical to the previous generator for identical inputs
EOF
)
N23=$(create "WI-2.2b: Streaming report generation (keyset-paged) + request guardrails" "$B

## Blocked by

- #$N22")

B=$(cat <<'EOF'
## What to build

Time-limited download URLs for completed reports, replacing byte-array proxying through the JVM. Add an "issue time-limited download URL" operation to the report storage abstraction — specified provider-agnostically ("URL valid for 15 minutes") so the R2 presigned-GET implementation satisfies it now and an Azure Blob SAS implementation satisfies it later as a one-class change. The download endpoint records the access-audit row first, then returns the URL.

Note: this is NEW capability, not a refactor — no URL issuance exists anywhere today; all downloads currently buffer the full file in the JVM.

Source: scalability-assessment-2026-06-10.md §17.3, WI-2.2 (finding R11/F6).

## Acceptance criteria

- [ ] Completed-report download returns a short-lived URL instead of file bytes
- [ ] URL expires after the configured window (test proves expiry)
- [ ] Access-audit row is written before URL issuance
- [ ] The JVM no longer buffers report content on the download path
- [ ] Interface documented as provider-agnostic (R2 presign / Azure SAS)
EOF
)
N24=$(create "WI-2.2c: Provider-agnostic time-limited download URLs for reports (R2 presign)" "$B

## Blocked by

None - can start immediately")

B=$(cat <<'EOF'
## What to build

Per-LSP rate limits as data, not config (decision D4: 3–10 partners, any may be high-volume — the static 60/min default saturates for a whale partner). A per-LSP override table (writes/min, reads/min, optional daily quota) with admin endpoints/UI; resolution order DB-override then static default; a new read-lane rule for LSP GET traffic (default 300/min); Retry-After on every 429.

Source: scalability-assessment-2026-06-10.md §17.3, WI-2.4 (finding F10, decision D4).

## Acceptance criteria

- [ ] Limit changes take effect without redeploy (cache TTL documented)
- [ ] Read lane enforced per LSP with the configured default
- [ ] All 429 responses carry Retry-After
- [ ] Limit changes are audited
EOF
)
N26=$(create "WI-2.4a: DB-backed per-LSP rate limits + LSP read lane + Retry-After" "$B

## Blocked by

None - can start immediately")

B=$(cat <<'EOF'
## What to build

Per-LSP webhook delivery concurrency cap in the dispatch executor, so one partner's dead/slow endpoint cannot consume the shared delivery thread pool and starve the other partners' webhooks.

Source: scalability-assessment-2026-06-10.md §17.3, WI-2.4 (decision D4).

## Acceptance criteria

- [ ] With one LSP endpoint blackholed and 1,000 pending events, other LSPs' deliveries proceed at normal latency
- [ ] Cap configurable per LSP with a sane default
- [ ] Per-LSP in-flight delivery count exposed as a metric
EOF
)
N27=$(create "WI-2.4b: Per-LSP webhook delivery concurrency cap" "$B

## Blocked by

None - can start immediately")

B=$(cat <<'EOF'
## What to build

Bulk repayment ingestion (decision D3a — confirmed for launch). A batch endpoint where an LSP posts up to ~10K payment rows in one call; rows persist to a payment-inbox table (unique per lsp + batch id + row number) and the call acknowledges immediately with a batch id; a drain worker applies rows asynchronously through the existing per-payment machinery (installment locking and bounce/reversal aware); a batch status endpoint reports per-row outcomes. Batch resubmission is idempotent.

Source: scalability-assessment-2026-06-10.md §17.3, WI-2.5 / §9 menu (finding F19, decision D3a).

## Acceptance criteria

- [ ] 5,000-row settlement batch fully applied in under 5 minutes on staging
- [ ] Batch resubmit duplicates nothing (inbox uniqueness + payment idempotency)
- [ ] Per-row failures reported in batch status and individually re-drivable
- [ ] Rate limiting treats the batch as one write call
EOF
)
N28=$(create "WI-2.5: Bulk repayment ingestion — batch endpoint + payment inbox + drain worker" "$B

## Blocked by

- #$N10
- #$N11")

B=$(cat <<'EOF'
## What to build

Nightly partner MIS auto-enqueuer (decision D7): a scheduled job that drops a standing portfolio-MIS report request per active LSP covering the previous day's close, deduplicated per (LSP, date), flowing through the hardened report pipeline.

Source: scalability-assessment-2026-06-10.md §17.3, WI-2.6 (decision D7).

## Acceptance criteria

- [ ] Every active LSP receives exactly one nightly MIS request; inactive LSPs are skipped
- [ ] Re-running the enqueuer does not duplicate requests
- [ ] Failures surface through the report pipeline's retry/alert path
EOF
)
N29=$(create "WI-2.6a: Nightly partner MIS auto-enqueuer" "$B

## Blocked by

- #$N22")

B=$(cat <<'EOF'
## What to build

A generic parameterized regulatory extract generator (v1.2 decision): configurable column set, filters (LSP, product, status, disbursal date range), CSV output — with a preset mechanism so that when compliance supplies exact report shapes later, each becomes a stored preset rather than new code.

Source: scalability-assessment-2026-06-10.md §17.3, WI-2.6 (decision D7, v1.2).

## Acceptance criteria

- [ ] Extract definitions validated (unknown columns/filters rejected)
- [ ] Presets can be stored, listed, and reused
- [ ] PII-bearing columns gated by permission
- [ ] Golden-file test for at least one sample preset
EOF
)
N32=$(create "WI-2.6d: Generic parameterized regulatory extract generator with presets" "$B

## Blocked by

- #$N22")

# ---------------- missing Phase 3 issues ----------------

B=$(cat <<'EOF'
## What to build

Domain-level metrics on top of the metrics foundation: webhook outbox depth and oldest-pending age; report queue depth and oldest; payment inbox depth; disbursement pending/claimed/stuck counts and oldest pending-disbursal age; idempotency replays per LSP; rate-limit rejections per rule per LSP; payments posted and bounced.

Source: scalability-assessment-2026-06-10.md §17.4, WI-3.3 (finding R9, decision D4).

## Acceptance criteria

- [ ] All listed metrics exposed and observable under the load suite
- [ ] Consistent naming scheme following the WI-3.1 conventions
- [ ] Documented in the ops docs for dashboard/alert use
EOF
)
N35=$(create "WI-3.3: Domain metrics — queue depths, oldest-pending ages, per-LSP counters" "$B

## Blocked by

- #$N33")

B=$(cat <<'EOF'
## What to build

Alert fan-out (decision D8, resolved: email + generic webhook). An AlertNotifier interface invoked on ops-alert creation with two implementations: email (daily digest for MEDIUM and below) and a generic webhook (configurable URL and body template — pointable at Slack/Teams/Google Chat by configuration alone) firing instantly for HIGH/CRITICAL. Severity routing is configurable; alert storms are grouped using the existing dedupe-by-subject logic. Notifier failures are logged and retried and never block alert-row creation.

Source: scalability-assessment-2026-06-10.md §17.4, WI-3.4 (finding R9, decision D8).

## Acceptance criteria

- [ ] HIGH/CRITICAL alert reaches the webhook test sink within seconds
- [ ] MEDIUM and below appear only in the next daily digest email
- [ ] Webhook target/format switchable purely via configuration
- [ ] Notifier failure never prevents the alert row from being created
EOF
)
N36=$(create "WI-3.4: AlertNotifier fan-out — email digests + generic webhook with severity routing" "$B

## Blocked by

None - can start immediately")

# ---------------- patch dangling Blocked-by references ----------------

B=$(cat <<'EOF'
## What to build

LSP self-serve report API (decision D7): LSP-scoped endpoints to create a report request, list their own requests, and download completed reports via time-limited URLs. Row-level security already scopes report rows per LSP — verify it end-to-end. Rate-limited under the reports rule.

Source: scalability-assessment-2026-06-10.md §17.3, WI-2.6 (decision D7).

## Acceptance criteria

- [ ] LSP A cannot see or fetch LSP B's reports (API-level and DB-level tests)
- [ ] Downloads use the time-limited URL mechanism
- [ ] LSP-created requests are visible in the admin reports view
- [ ] API contract documented
EOF
)
patch_body 215 "$B

## Blocked by

- #$N24"

B=$(cat <<'EOF'
## What to build

Operations dashboards and the full alert rule set: Grafana dashboards as code (API, workers, database, per-LSP) and alert rules for the complete §12 list — latency, error rate, pool usage, queue ages, stuck disbursements, terminal report failures, KPI-snapshot-missing-by-01:00, partition-creation-missing, Redis down, per-LSP error spikes.

Source: scalability-assessment-2026-06-10.md §17.4, WI-3.6 (§12 alert list).

## Acceptance criteria

- [ ] Dashboards provisioned as code, importable into a fresh Grafana
- [ ] Every §12 alert has a rule with a parameterized threshold
- [ ] Per-LSP board covers the D4 isolation metrics
EOF
)
patch_body 219 "$B

## Blocked by

- #$N33
- #$N35"

B=$(cat <<'EOF'
## What to build

Operational runbooks plus automated alert-path verification. One runbook per documented failure mode (§13 of the assessment): symptom, diagnosis steps, action, rollback — authored as repo docs referencing the real metric and alert names. Plus an automated end-to-end test that raises a synthetic alert and asserts delivery at the webhook test sink. The human fire drill and on-call rota naming live on the launch checklist, not in this issue.

Source: scalability-assessment-2026-06-10.md §17.4, WI-3.7 (§13 failure table).

## Acceptance criteria

- [ ] Every §13 failure row has a runbook in the repo
- [ ] Runbooks reference real metric/alert names from the observability work
- [ ] Automated alert-path test runs in CI/staging and asserts end-to-end delivery
- [ ] Launch checklist line added for the human fire drill + rota
EOF
)
patch_body 220 "$B

## Blocked by

- #$N36
- #$N37"

B=$(cat <<'EOF'
## What to build

Malware scanning for uploaded documents before they become downloadable by ops staff: scan-on-upload (ClamAV sidecar or equivalent), quarantine states on the document checklist (pending-scan, infected), ops alert on detection, downloads blocked until a document is clean. Define the scanner-outage policy (queue for scan — never bypass).

Source: scalability-assessment-2026-06-10.md §17.4, WI-3.8 (§13 security findings).

## Acceptance criteria

- [ ] EICAR test file is quarantined and raises an ops alert
- [ ] Clean files pass with acceptable added latency
- [ ] Download of a quarantined or unscanned document is blocked
- [ ] Scanner outage queues documents rather than bypassing the scan
EOF
)
patch_body 221 "$B

## Blocked by

- #$N17"

echo ""
echo "==== retry batch complete ===="
sort -n "$MAP_FILE"
