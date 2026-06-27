#!/usr/bin/env bash
# Publishes the §17 execution-plan work items (scalability-assessment-2026-06-10.md)
# as ready-for-agent issues on sid12701/lms, in dependency order.
set -euo pipefail

REPO="sid12701/lms"
LABEL="ready-for-agent"
MAP_FILE="scripts/scalability-issue-map.txt"

gh label list -R "$REPO" --limit 100 | grep -q "$LABEL" || \
  gh label create "$LABEL" -R "$REPO" --color "0E8A16" --description "Ready for autonomous agent pickup"

: > "$MAP_FILE"

create() {
  local title="$1" body="$2" url num
  url=$(gh issue create -R "$REPO" --title "$title" --body "$body" --label "$LABEL")
  num="${url##*/}"
  echo "$num	$title" >> "$MAP_FILE"
  echo "created #$num  $title" >&2
  echo "$num"
}

# ---------------- Phase 0 ----------------

B=$(cat <<'EOF'
## What to build

A profile-gated synthetic data seeder that fills a staging database with a production-shaped portfolio: ~200K loan applications across 5 synthetic LSPs in a realistic status mix, ~150K loan accounts with repayment schedules, ~1M payment transactions, and ~10M rows across the audit streams. Use bulk JDBC batch inserts, not per-entity JPA persistence. Follow the existing demo-portfolio seeder pattern, scaled up.

Source: scalability-assessment-2026-06-10.md §17.1, WI-0.1.

## Acceptance criteria

- [ ] Seeder runs only under an explicit profile/flag, never in default configuration
- [ ] Full seed completes in under 30 minutes on a developer-grade machine
- [ ] Row counts and status distribution match the documented spec
- [ ] Re-running is idempotent or performs a clean reset
- [ ] Seeded data satisfies all existing schema constraints (FKs, checks, uniques)
EOF
)
N1=$(create "WI-0.1: Synthetic portfolio seeder for staging-scale testing" "$B

## Blocked by

None - can start immediately")

B=$(cat <<'EOF'
## What to build

A two-instance staging stack mirroring the agreed launch topology (D1): two API pods with all scheduled workers disabled via the existing config flags, one dedicated worker pod running them, plus Postgres, Redis, and object storage. One command brings the whole stack up.

Source: scalability-assessment-2026-06-10.md §17.1, WI-0.2.

## Acceptance criteria

- [ ] Both API pods serve authenticated traffic behind a single entry point
- [ ] Only the worker pod executes scheduled workers (verifiable from logs/config)
- [ ] Stack boots with a single documented command
- [ ] The §18 test scenarios are runnable against this stack
EOF
)
N2=$(create "WI-0.2: Two-instance staging stack matching launch topology (2 API + 1 worker)" "$B

## Blocked by

None - can start immediately")

B=$(cat <<'EOF'
## What to build

A reusable JUnit concurrency harness for money-safety testing: latch-synchronized N-thread races against a Postgres testcontainer, plus an instrumented mock disbursement adapter exposing an invocation counter so tests can assert exactly how many times the provider was called.

Source: scalability-assessment-2026-06-10.md §17.1, WI-0.3.

## Acceptance criteria

- [ ] A demonstration race test (duplicate loan-application create) runs in CI
- [ ] The mock disbursement adapter invocation counter is assertable from tests
- [ ] Harness utilities are documented for reuse by later work items
- [ ] Tests are deterministic (latch-based synchronization, no sleep-based timing)
EOF
)
N3=$(create "WI-0.3: Concurrency race harness + instrumented mock disbursement adapter" "$B

## Blocked by

None - can start immediately")

B=$(cat <<'EOF'
## What to build

A k6 (or Gatling) load-test suite covering the documented load scenarios: steady-state multi-LSP traffic, settlement-morning payment burst, document-upload spike, single-partner rate-limit breach, and dashboard-under-load. Record a baseline run against current main so improvements are measurable.

Source: scalability-assessment-2026-06-10.md §17.1, WI-0.4 and §18.

## Acceptance criteria

- [ ] Suite runs against the staging stack via a single documented command
- [ ] Each §18 load scenario has a corresponding profile
- [ ] Baseline results against unfixed main are recorded in the repo
- [ ] Pass/fail thresholds are parameterized per scenario
EOF
)
N4=$(create "WI-0.4: Load-test suite + recorded baseline" "$B

## Blocked by

- #$N1
- #$N2")

# ---------------- Phase 1 ----------------

B=$(cat <<'EOF'
## What to build

Production-grade connection-pool and query-timeout configuration. Explicit Hikari sizing for BOTH pools (admin datasource and the tenant routing datasource) with per-deployment-role values (API pods vs worker pod), connection timeout, and leak detection. Postgres-side statement_timeout per application role plus idle_in_transaction_session_timeout, with a separate longer-limit role for the report/KPI workers.

Source: scalability-assessment-2026-06-10.md §17.2, WI-1.1 (findings R6).

## Acceptance criteria

- [ ] Pool sizes are configurable per deployment role via environment
- [ ] Injected slow query is killed by statement_timeout without exhausting the pool
- [ ] Tenant pool is explicitly sized and named, not on Spring defaults
- [ ] Defaults for API vs worker roles documented
EOF
)
N5=$(create "WI-1.1: Hikari pool sizing (both datasources) + Postgres statement timeouts" "$B

## Blocked by

None - can start immediately")

B=$(cat <<'EOF'
## What to build

Rework the LSP API idempotency wrapper to claim-before-execute. The idempotency record is inserted FIRST, inside the same transaction as the business action, with a status column (PENDING at insert, COMPLETED with the response body before commit). Postgres unique-index blocking serializes concurrent duplicates: the second caller blocks until the first commits (then replays the stored response after a fingerprint check) or rolls back (then proceeds). Remove the REQUIRES_NEW claim path entirely so a rollback removes the claim atomically — a recorded success for rolled-back work becomes impossible. Migration adds the status column and backfills existing rows as COMPLETED.

Source: scalability-assessment-2026-06-10.md §17.2, WI-1.2 (finding R2/F2).

## Acceptance criteria

- [ ] Concurrent duplicate pair executes the business action exactly once; the loser receives the replayed response
- [ ] Forced rollback after claim leaves no idempotency record; a retry executes cleanly
- [ ] Fingerprint mismatch on a reused key returns 409 IDEMPOTENCY_CONFLICT
- [ ] Happy-path behavior of all LSP write endpoints is unchanged
- [ ] Race tests built on the WI-0.3 harness are green in CI
EOF
)
N6=$(create "WI-1.2: Claim-before-execute LSP idempotency with status column" "$B

## Blocked by

- #$N3")

B=$(cat <<'EOF'
## What to build

Rework disbursement work selection: replace the unbounded find-by-status batch with an atomic claim of N applications (configurable, default 25) using FOR UPDATE SKIP LOCKED plus lease columns, and process each claimed application in its own short transaction via a separate executor bean (eliminating the current self-invocation that silently merges the whole batch into one transaction). Replace the count-based retry budget with an atomic attempt counter on the disbursement attempt records.

Source: scalability-assessment-2026-06-10.md §17.2, WI-1.3a (finding R1/F1).

## Acceptance criteria

- [ ] Two worker instances never process the same application (race-harness test)
- [ ] One scheduler tick claims at most N applications
- [ ] A per-loan failure does not roll back sibling loans in the batch
- [ ] Retry budget is enforced atomically under concurrent attempts
- [ ] An expired lease makes the application claimable again
EOF
)
N7=$(create "WI-1.3a: Disbursement worker — SKIP-LOCKED claim, per-loan transactions, atomic retry budget" "$B

## Blocked by

- #$N3")

B=$(cat <<'EOF'
## What to build

Make the disbursement provider call money-safe and parallel. Transaction 1 records an intent row carrying a unique provider reference and commits; the adapter call happens OUTSIDE any database transaction; transaction 2 records the outcome. A sweeper job resolves expired claims and accounts stuck in DISBURSEMENT_REQUESTED by querying provider status — never by blind resend. Deliveries run on a configurable thread pool (default 10), mirroring the webhook dispatcher design.

Source: scalability-assessment-2026-06-10.md §17.2, WI-1.3b (finding R1/F1; design per D2 assumptions).

## Acceptance criteria

- [ ] Provider invocation counter equals disbursed-loan count under every test interleaving, including kill -9 mid-call (sweeper resolves the ambiguous attempt)
- [ ] Every attempt carries a unique provider reference
- [ ] A 2,000-loan backlog drains in under 10 minutes on staging
- [ ] No database transaction spans an adapter call (verified by test or explicit review checklist)
- [ ] Sweeper never resends without first confirming attempt status
EOF
)
N8=$(create "WI-1.3b: Disbursement intent rows, provider call outside transaction, stale-claim sweeper, parallel delivery" "$B

## Blocked by

- #$N7")

B=$(cat <<'EOF'
## What to build

Close the orphan-payment window: insert the payment transaction row in the SAME transaction as its installment allocation (remove the REQUIRES_NEW claim and the per-JVM synchronized-intern block). The existing unique constraint on idempotency_key remains the arbiter: on unique violation, read the committed winner, fingerprint-check, and return it. Simplify the optimistic-lock recovery path accordingly — orphan rows become impossible rather than handled.

Source: scalability-assessment-2026-06-10.md §17.2, WI-1.4 (finding R3/F3).

## Acceptance criteria

- [ ] Same-key duplicate race produces exactly one payment row with exactly one allocation
- [ ] Forced failure after payment insert leaves zero committed payment rows
- [ ] Existing repayment API contract is unchanged
- [ ] Race tests on the WI-0.3 harness are green in CI
EOF
)
N9=$(create "WI-1.4: Payment row and allocation commit in the same transaction (no orphan payments)" "$B

## Blocked by

- #$N3")

B=$(cat <<'EOF'
## What to build

Serialize concurrent payments against the same installment: acquire a row lock (SELECT ... FOR UPDATE) on the target installment when resolving it for payment, so two payments with different idempotency keys cannot both pass the already-paid check.

Source: scalability-assessment-2026-06-10.md §17.2, WI-1.5 (finding R4).

## Acceptance criteria

- [ ] Different-key same-installment race: one succeeds, the other receives INSTALLMENT_ALREADY_PAID (409)
- [ ] No deadlocks with schedule reads under the load suite
- [ ] Lock is held only for the duration of the short payment transaction
EOF
)
N10=$(create "WI-1.5: Installment row locking on payment posting" "$B

## Blocked by

- #$N9")

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

Surface write conflicts as client errors and constrain manual overrides. A global exception mapping turns optimistic-lock and lock-acquisition failures into 409 responses with a stable error code (today they surface as 500s). The MANUAL_OVERRIDE status-transition context is restricted to an explicit allowed set; the unbounded form requires an explicit confirmation flag and produces a CRITICAL-severity audit/alert.

Source: scalability-assessment-2026-06-10.md §17.2, WI-1.7 (finding F13).

## Acceptance criteria

- [ ] Concurrent conflicting status transitions return 409, not 500
- [ ] Override outside the allowed set without the confirmation flag is rejected
- [ ] Flagged overrides produce a CRITICAL audit entry and ops alert
- [ ] Error contract documented in the API standards doc
EOF
)
N12=$(create "WI-1.7: Map write conflicts to 409 + constrain manual status override" "$B

## Blocked by

None - can start immediately")

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

Convert the six fastest-growing tables to monthly range partitions on created_at, pre-launch while they are small (rename → create partitioned → copy → swap): the loan-application intake audit, status transitions, application audit events, document access audit, auth event audit, and webhook delivery attempts. Add a worker job that pre-creates partitions three months ahead with an alert if a partition is missing. Document the plan for the payment-transaction table to join the scheme before repayment volume ramps.

Source: scalability-assessment-2026-06-10.md §17.2, WI-1.10 (finding F15, decision D6: 24 months hot, 8-year archive).

## Acceptance criteria

- [ ] All six tables partitioned with data, constraints, and indexes intact
- [ ] Date-windowed queries show partition pruning in EXPLAIN
- [ ] Partition-creation job verified across a month rollover; missing-partition alert fires
- [ ] Rollback plan for the conversion documented
EOF
)
N15=$(create "WI-1.10: Monthly range partitions for the big six audit/event tables + partition-creation job" "$B

## Blocked by

None - can start immediately")

B=$(cat <<'EOF'
## What to build

Scheduled retention sweeps: purge LSP API idempotency records older than 90 days (they store full response payloads and grow 10–20M rows/year), and purge delivered/exhausted webhook outbox rows per policy. Batched deletes (or partition drops where applicable) that never take long locks; metrics for purged row counts.

Source: scalability-assessment-2026-06-10.md §17.2, WI-1.11.

## Acceptance criteria

- [ ] Retention windows configurable; defaults documented
- [ ] Purges run in bounded batches without long-held locks
- [ ] Purged-row metrics emitted
- [ ] Tests cover aged fixtures and the do-not-purge boundary
EOF
)
N16=$(create "WI-1.11: Retention sweeps — idempotency records + delivered webhook outbox rows" "$B

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
N18=$(create "WI-1.13a: Housekeeping — remove RabbitMQ; ADRs for INR-only and deployment topology" "$B

## Blocked by

None - can start immediately")

B=$(cat <<'EOF'
## What to build

The ICICI bank-integration design ADR (design only — no production code). Per decision D2, assume status callbacks plus a reconciliation source. The ADR covers: outbox-side intent rows with provider-side idempotency references; a bank event inbox (unique provider event id, acknowledge fast, process asynchronously); nightly three-way reconciliation (our intents vs our outcomes vs the bank's records) with ops alerts on mismatch; circuit breaker/timeout/bulkhead on the adapter; the repayment feed flowing through the same inbox (D3a option iii, post-launch); the embedded go-live checklist (Phase 1 complete, recon live, phone-grade alerting); and the open questions for ICICI (callback contract, idempotency reference parameter, recon file format).

Source: scalability-assessment-2026-06-10.md §17.2 WI-1.13 and §17.5 WI-4.6 (decision D2).

## Acceptance criteria

- [ ] ADR in docs/adr/ consistent with the existing ADR format and numbering
- [ ] All listed design elements covered, including the go-live checklist
- [ ] Open questions for ICICI enumerated as a checklist
- [ ] No production code changes in this issue
EOF
)
N19=$(create "WI-1.13b: ICICI integration design ADR (inbox/outbox/recon, go-live checklist)" "$B

## Blocked by

None - can start immediately")

# ---------------- Phase 2 ----------------

B=$(cat <<'EOF'
## What to build

The nightly dashboard KPI snapshot backend (decision D5). A snapshot table keyed uniquely by (snapshot_date, scope_type, scope_id) holding the KPI payload, status, and timings; a nightly job (00:15 IST, claimed via SKIP LOCKED so it is multi-instance safe) computing all portfolio KPIs in SQL — including the average-approval-TAT as a single window-function query, eliminating the current per-approval N+1; an idempotent admin recompute endpoint; failure handling that writes a FAILED row and raises an ops alert while preserving the last good snapshot.

Source: scalability-assessment-2026-06-10.md §17.3, WI-2.1 / §6 (finding R5/F5, decision D5).

## Acceptance criteria

- [ ] Snapshot values equal the current live computation on seeded data
- [ ] Job is multi-instance safe and completes within minutes at the 200K-application seed
- [ ] Recompute endpoint is idempotent (upsert on the unique key)
- [ ] Failure writes a FAILED row, raises an alert, and keeps the last good snapshot servable
EOF
)
N20=$(create "WI-2.1a: KPI snapshot table + nightly SQL aggregation job + admin recompute" "$B

## Blocked by

None - can start immediately")

B=$(cat <<'EOF'
## What to build

Switch the home dashboard to snapshot reads (decision D5 split). The overview endpoint serves the latest COMPLETED snapshot for portfolio KPIs and keeps exactly three live values: applications awaiting approval, applications in disbursement, and open alerts (cheap indexed counts). The frontend shows "Data as of <date>" permanently. Delete the per-account in-memory aggregation path entirely.

Source: scalability-assessment-2026-06-10.md §17.3, WI-2.1 / §6 (decision D5).

## Acceptance criteria

- [ ] Overview endpoint issues exactly one snapshot read plus three indexed counts (query-count assertion test)
- [ ] UI displays the snapshot as-of date; on job failure it shows the last good snapshot with its date
- [ ] The old unbounded aggregation code is removed
- [ ] Dashboard latency unaffected by portfolio size in the load suite
EOF
)
N21=$(create "WI-2.1b: Dashboard serves KPI snapshots + as-of date UI + three live counters" "$B

## Blocked by

- #$N20")

B=$(cat <<'EOF'
## What to build

Restructure the report worker's transaction boundaries and add retries. Claim + mark-processing commits first (with a processing lease so a crashed worker's claim expires); generation and storage upload happen outside any DB transaction; completion/failure plus notification happen in a new transaction, with the email sent only after the status commit. Add a retry budget (attempt count, next-attempt-at); terminal failure raises an ops alert.

Source: scalability-assessment-2026-06-10.md §17.3, WI-2.2 (findings R7/R10/F6).

## Acceptance criteria

- [ ] Worker kill mid-generation: lease expires, request is reclaimed and retried
- [ ] Transient failure retries up to budget, then terminal FAILED with ops alert
- [ ] Notification email sent only after the terminal status commits
- [ ] No DB transaction spans storage or SMTP I/O
EOF
)
N22=$(create "WI-2.2a: Report worker transaction split + processing lease + retry budget" "$B

## Blocked by

None - can start immediately")

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

Audit explorer guardrails for the 50–150M-rows/year reality. Enforce a mandatory date window at the API (default 7 days, maximum 90); replace the regex-over-jsonb LSP predicate with the indexed lsp_id column it already coalesces from; cap or estimate the total count instead of a full COUNT(*) wrap; use keyset pagination beyond deep offsets.

Source: scalability-assessment-2026-06-10.md §17.3, WI-2.3 (finding R8/F8).

## Acceptance criteria

- [ ] Windowless or over-window requests rejected with 400 and a clear error
- [ ] EXPLAIN shows partition pruning and index usage at the 10M-row seed
- [ ] Deep pagination latency is stable
- [ ] Existing UI filters keep working within the window rules
EOF
)
N25=$(create "WI-2.3: Audit explorer guardrails — mandatory date window, indexed predicates, keyset pagination" "$B

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

LSP self-serve report API (decision D7): LSP-scoped endpoints to create a report request, list their own requests, and download completed reports via time-limited URLs. Row-level security already scopes report rows per LSP — verify it end-to-end. Rate-limited under the reports rule.

Source: scalability-assessment-2026-06-10.md §17.3, WI-2.6 (decision D7).

## Acceptance criteria

- [ ] LSP A cannot see or fetch LSP B's reports (API-level and DB-level tests)
- [ ] Downloads use the time-limited URL mechanism
- [ ] LSP-created requests are visible in the admin reports view
- [ ] API contract documented
EOF
)
N30=$(create "WI-2.6b: LSP self-serve report API (request, list, download)" "$B

## Blocked by

- #$N24")

B=$(cat <<'EOF'
## What to build

Two new report generators on the hardened pipeline (decision D7): a collections/DPD report (bucket-wise outstanding, slippage between buckets) and a disbursement register (every transfer with provider references — the human-readable face of bank reconciliation).

Source: scalability-assessment-2026-06-10.md §17.3, WI-2.6 (decision D7).

## Acceptance criteria

- [ ] Both generators produce correct output on seeded data (golden-file tests)
- [ ] Registered as report types in the catalog and request UI
- [ ] Honor LSP and date-range filters
- [ ] Stream within the pipeline's memory bounds
EOF
)
N31=$(create "WI-2.6c: Collections/DPD report + disbursement register generators" "$B

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

# ---------------- Phase 3 ----------------

B=$(cat <<'EOF'
## What to build

Metrics and database telemetry foundation. Micrometer Prometheus registry with the scrape endpoint exposed on the internal network only; HTTP latency/error rate per endpoint, both Hikari pools as distinct metrics, JVM, and scheduler timings. Postgres side: pg_stat_statements enabled and slow-query logging (500ms threshold) configured and documented.

Source: scalability-assessment-2026-06-10.md §17.4, WI-3.1 + WI-3.5 (finding R9).

## Acceptance criteria

- [ ] Prometheus endpoint scrapeable internally, not reachable on the public surface
- [ ] Both connection pools visible as separately-named metrics
- [ ] Slow-query logging verified with an injected slow query
- [ ] Metric naming documented for dashboard work
EOF
)
N33=$(create "WI-3.1: Prometheus metrics + actuator exposure + Postgres telemetry" "$B

## Blocked by

None - can start immediately")

B=$(cat <<'EOF'
## What to build

Structured JSON logging with domain context. JSON log encoding in non-local profiles; an MDC enrichment filter adding lspId, applicationId/loanAccountId, and actor wherever resolvable, alongside the existing correlationId; a guard test asserting known PII fields never appear in logs. Local profile keeps human-readable output.

Source: scalability-assessment-2026-06-10.md §17.4, WI-3.2 (finding R9).

## Acceptance criteria

- [ ] Non-local logs are valid JSON carrying the MDC fields on LSP API calls
- [ ] Correlation id flows through workers (webhook/report/disbursement) as today
- [ ] PII-leak guard test green
- [ ] Local profile remains human-readable
EOF
)
N34=$(create "WI-3.2: JSON structured logging + MDC enrichment (lspId, applicationId, actor)" "$B

## Blocked by

None - can start immediately")

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
N37=$(create "WI-3.6: Grafana dashboards as code + full alert rule set" "$B

## Blocked by

- #$N33
- #$N35")

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
N38=$(create "WI-3.7: Runbooks per failure mode + automated alert-path verification" "$B

## Blocked by

- #$N36
- #$N37")

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
N39=$(create "WI-3.8: Upload malware scanning with quarantine states" "$B

## Blocked by

- #$N17")

echo ""
echo "==== DONE: $(wc -l < "$MAP_FILE") issues created ===="
cat "$MAP_FILE"
