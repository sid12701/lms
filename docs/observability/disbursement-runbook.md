# Disbursement visibility runbook (H27 slice)

**Owner:** Disbursement Ops on-call (SYSTEM_ADMIN role).
**Scope:** disbursement reconciliation signals only — no repayments, reports, or worker-health coverage here.

> **STAGING STATUS: UNVERIFIED.** The staging Prometheus collector, Alertmanager routing, and
> alert delivery for these rules have **not** been verified. Do not rely on these alerts in
> staging until the collection + delivery check is recorded here. No delivery is claimed.

Scrape: `GET /actuator/prometheus` (SYSTEM_ADMIN only). Gauges carry no borrower, account, or
reference labels by design — correlate a bucket to concrete loans via the admin APIs/DB, never
via metric dimensions.

Shared invariants for every alert below: **never re-initiate** an uncertain payment (no new
`tranRefNo`); reconcile the original reference; applying a terminal result must move the
account, application transition, audit, and partner event together (C02 applier).

## DisbursementUnappliedBacklog

Terminal bank evidence exists but the loan still shows `DISBURSEMENT_REQUESTED` (pre-C02 rows or
a crashed apply). Run the bounded stranded-terminal repair from stored evidence
(`DisbursementIntentWorkflowService.repairStrandedTerminalDisbursements`), verify exactly one
`DISBURSED` transition per loan and zero new provider initiations, then confirm the
`lms_disbursement_intent_unapplied_count` gauge returns to 0. Overlap note: these rows also
appear in `parked_without_intent` (no live intent) — clear the unapplied backlog first; the
parked signal should follow.

## DisbursementUnknownNeedsReconciliation

`UNKNOWN` intents are in-flight uncertainty, not failure. Status-check the original reference
against the provider. On a terminal verdict, apply through the C02 path. On continued
uncertainty past the poll cap, the loan parks for manual bank-evidence review — that moves it
to the parked bucket, which is expected, not a second incident.

## DisbursementParkedWithoutIntent

Accounts in `DISBURSEMENT_REQUESTED`/`DISBURSEMENT_PENDING_RECONCILIATION` with no live intent:
manual parking plus legacy/pre-intent mismatches. The H02 reconciliation queue owns recovery
selection — check the queue panel first; each entry still needs bank evidence before any state
move, and there is no evidence-free dismissal path. Do not invent ad-hoc recovery writes.

## DisbursementReconciliationQueueBacklog

Unresolved entries awaiting their bounded sweep turn (backoff-bounded re-poll of the original
reference, stranded-terminal repair, or operator-held conflict). Confirm the reconciliation
schedule is running; entries clear only after the applier commits. Past the 24h escalation age,
page the owner and reconcile from bank evidence. Queue rows age from the original evidence
(first stored request/intent), not from discovery time.

## DisbursementPendingStuck

Live `CREATED`/`REQUESTED` intents older than the threshold point at the worker, not the bank:
check worker pod health, claim-lease expiry, and provider outage. Do not mint replacement
intents for stuck ones.

## DisbursementProviderLatencyHigh

Slow bank calls. Check provider status first. Slow calls serialize the worker loop — scale only
after confirming the provider is healthy, and never retry an uncertain submission to "catch up".
