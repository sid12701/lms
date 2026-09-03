# ADR 0007 — Partner lifecycle updates are pull-based; the webhook delivery path is removed

- **Status:** Accepted (2026-08-16)
- **Supersedes:** the outbound webhook delivery model introduced in V23–V25 (`lsp.webhook_*`, `webhook_event_outbox`, `webhook_event_delivery_attempt`) and extended by V27, V58, V66, V70, V86, V88, V99.
- **Related:** ADR 0003 (loan origination is API-only), ADR 0005 (tenant scope from principal, fail-closed), `docs/architecture/deployment-strategy.md` (D10 — no message broker; DB-table queues + `SKIP LOCKED`).

## Context

LSPs need to learn about loan lifecycle changes: approval verdicts, disbursement outcomes, delinquency, repayment, closure. Today the platform pushes these as webhooks — an outbox row is written in the same transaction as the state change, and `WebhookOutboxDispatchWorker` delivers it with retries, backoff, delivery-attempt records, dead-letter alerting, and a capped manual redrive tool.

That model makes the platform responsible for something it cannot control or prove. Delivery depends on each partner's uptime, TLS, DNS, rate limits and handler correctness; partner outages become platform queue depth; and an HTTP 200 means "received", never "applied", so the strongest guarantee the platform can offer is weaker than the guarantee partners actually need. Every failure in that chain arrives as a platform support ticket regardless of where it originated.

Three properties of the existing implementation sharpened the case:

1. **The dispatcher cannot carry the planned volume.** `fixed-delay-ms: 60000` × `batch-size: 20` is 0.33 events/s per pod against a requirement of roughly 10–25 events/s at 100K originations/day. Keeping webhooks means rebuilding the dispatcher, not leaving it alone.
2. **`claimDispatchBatch` has no per-tenant fairness.** It orders globally by `created_at`, so one LSP with a dead endpoint fills every batch with its own retries and starves the others — cross-tenant head-of-line blocking.
3. **The outbox was subscription-filtered at write time.** `enqueueIfSubscribed` returned early unless the LSP had `webhook_enabled` and had subscribed to that event type, so the table's contents were a function of current configuration rather than of what happened. History could not be replayed or backfilled.

The system is pre-production, so there is no partner-compatibility constraint on the choice.

## Decision

**LSPs pull lifecycle changes from an append-only loan event log. The platform does not push.**

1. **One event log, written unconditionally.** `loan_event` records every loan lifecycle fact in the same transaction as the state change it describes. There is no subscription, no enable flag, and no write-time filter. Every LSP has a feed from the moment it exists.
2. **One endpoint.** `GET /api/v1/lsp/loan-events?cursor=&limit=&eventTypes=` returns events plus a `nextCursor` and `hasMore`. `eventTypes` filters the response only; cursors are always over the unfiltered stream, so an LSP that widens its filter can rewind and receive everything.
3. **Cursor safety uses an `xid8` composite cursor.** Postgres assigns sequence values and timestamps *before* commit, so a cursor over a bare sequence permanently skips events from transactions that commit late. The feed query filters on `transaction_id < pg_snapshot_xmin(pg_current_snapshot())` — serving only transactions that have definitively completed — and paginates on the composite `(transaction_id, position)`.
4. **Ordering is guaranteed per loan, not globally.** Concurrent transactions touching the same loan serialise on row locks, so per-loan order is real. Across unrelated loans the feed is a stable replay order, not a causal one. This is what the mechanism can actually keep, and it is what the partner contract states.
5. **30-day retention on the event log; internal history is untouched.** `loan_event` is monthly-partitioned, and a partition is dropped once every row it can hold is more than 30 days old. Retention is therefore a **floor of 30 days, not an exact age**: partitions are whole months, so the month containing the cutoff still holds rows inside the window and is kept, putting effective retention between 30 and 60 days. Dropping that month on its 30th day would serve partners less history than this ADR promises, which is the worse failure of the two. `loan_application_status_transition` and `loan_application_audit_event` keep their existing lifetimes and remain the system of record for who changed what.
6. **Cursor expiry fails loud.** A cursor pointing before what is still retained returns `410 CURSOR_EXPIRED` naming the resync path, never a silently truncated `200`. Expiry is measured against the oldest retained partition, not against a flat 30 days — by point 5 those differ by up to a month, and a flat check would expire cursors whose events the feed is still serving. Resync uses the existing `GET /api/v1/lsp/loan-applications`; no bulk snapshot endpoint is built.
7. **Delivery is at-least-once.** Duplicates are expected on consumer restart; LSPs dedupe on `eventId`. This is stated as a contract obligation, not an edge case.
8. **The webhook delivery machinery is deleted**, not left dormant behind a flag.

## Considered alternatives

- **Keep webhooks, fix the dispatcher.** Rejected: it requires per-tenant queues, fairness, and real concurrency, and still leaves the platform owning delivery to ten counterparties. It is more work than the pull design and buys a weaker guarantee.
- **Hybrid: pull feed plus a payload-free "you have events" nudge.** Rejected on contract grounds rather than mechanism. Partners would treat the nudge as the delivery channel, quietly stop polling on a healthy interval, and file tickets when one is lost — recreating the support burden without the delivery guarantees.
- **Long polling.** Rejected: buys latency well below the requirement, holds connections across pod rollouts, and requires async request handling plus ingress timeout tuning. At ~20 tenants polling every 10s the aggregate load is ~2 rps; there is no cost problem to solve.
- **Serve the feed by projecting existing tables instead of a new log.** Rejected for three reasons: `loan_application_status_transition` carries internal actor identity and notes; it would weld the partner contract to the internal `LoanApplicationStatus` enum, whose history has already been rewritten in place three times (V32, V51, V76); and there is no orderable key across the six source aggregates.
- **Dense, gap-free sequence numbers** so consumers can self-detect loss. Rejected after review of production change feeds (Stripe, Plaid, Square, GoCardless, Twilio, DynamoDB Streams) — all expose opaque cursors, none expose dense sequences. A dense sequence forbids rollbacks consuming numbers, forbids per-tenant assignment, and hands partners a verification tool that alerts on legitimate gaps.
- **Logical replication / CDC (Debezium-style).** Rejected: heavier infrastructure, and a stuck replication slot can take down the primary — a materially worse blast radius than a stalled watermark, which only stalls the feed.

## Consequences

- Two aggregates gain durable history they never had. `loan_account.status` and `loan_delinquency_state` are currently stored as current value only; the disbursement path (`DISBURSEMENT_REQUESTED → DISBURSEMENT_PENDING_RECONCILIATION → DISBURSED`) and DPD transitions become permanently recorded facts rather than overwritten columns.
- **New failure mode: any long-running transaction anywhere in the database becomes a ceiling on feed freshness for every tenant.** A `pg_dump`, a leaked connection, or a stuck analytics query stalls the feed until it completes. An alert on oldest-transaction age (`now() - xact_start`) is a launch blocker, not a follow-up.
- **Stalled consumers become invisible to the platform.** Under webhooks a broken partner surfaced as dead letters; under polling it is indistinguishable from a quiet day. Accepted deliberately: the published guarantee is availability of events, never consumption. A per-LSP lag endpoint and an ops alert are both deferred, and both are small additions if the position changes.
- **A retained, replayable feed is a better exfiltration target than a webhook stream** — a leaked API key drains 30 days of history rather than intercepting future traffic. Per-LSP rate limiting via the existing Redis/`bucket4j` path applies; short retention is part of the mitigation.
- Roughly 15 files and 4 tables are removed. Tenant isolation continues to come from RLS on `lsp_id` plus ADR 0005's principal-derived scope, exactly as the outbox table already had.
- Event payloads use one versioned schema for every LSP and carry **full, unmasked loan and borrower data** for now. Per-LSP field configuration is deliberately not part of the initial design.

## Open, and deliberately not decided here

- **Payload minimisation before production.** RBI (Digital Lending) Directions 2025 clause 13(i) requires the RE to ensure LSPs do not store borrower personal information beyond basic minimal data. The platform owns the loans and the partners are LSPs, so the obligation sits here. Full, unmasked payloads are explicitly accepted for the current pre-production contract and will be revisited before production as one versioned schema change for every LSP. Pruning is not retroactive for data already distributed.
- **LSP-held disbursal and collection accounts.** `CONTEXT.md` records that each LSP operates its own pair of bank accounts. Clause 9(ii)/(iii) of the same Directions requires fund flow not be controlled directly or indirectly by the LSP. Unrelated to this decision; flagged because the LSP/RE roles were confirmed while making it.
- **Volume reconciliation.** A €100M book of ₹5,000–50,000 tickets implies roughly 350K active loans, closer to ~1K new loans/day than the stated 100K/day peak. The design is identical either way; only partition sizing depends on it.

## Amendments

| Date | Change |
|---|---|
| 2026-08-16 | Initial acceptance |
| 2026-08-17 | Point 5 sharpened while implementing partition lifecycle (V117): retention is a 30-day floor, effective 30–60 days, because partitions are whole months and the month holding the cutoff still contains rows inside the window. Point 6 follows: cursor expiry is measured against the oldest retained partition, not a flat 30 days. |

## Trigger to re-open

1. A partner contractually requires sub-second notification, or a latency SLA the platform cannot meet by polling.
2. Tenant count grows far beyond the ~20 planned, to the point where empty-poll cost (O(tenants × poll rate)) becomes material.
3. Regulation mandates push delivery, or constrains partner-initiated bulk reads in a way that makes a cursor feed untenable.
4. The `xmin` watermark ceiling proves operationally unmanageable — recurring long transactions stalling the feed despite alerting.
5. Partners repeatedly implement the cursor loop incorrectly (dropping cursors on deploy, ignoring `hasMore`, non-durable checkpoints) at a rate that costs more support than the webhook machinery did.
