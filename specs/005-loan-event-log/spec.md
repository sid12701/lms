---
title: "Feature Specification: Durable Loan Event Log"
description: "Persist every loan lifecycle fact unconditionally in an ordered, immutable, tenant-isolated Postgres log."
trigger_phrases: ["loan event log", "xid8 ordering", "issue 02"]
importance_tier: "important"
contextType: "implementation"
---
# Feature Specification: Durable Loan Event Log

<!-- SPECKIT_LEVEL: 2 -->
<!-- SPECKIT_TEMPLATE_SOURCE: spec-core | v2.2 -->

---

<!-- ANCHOR:metadata -->
## 1. METADATA

| Field | Value |
|---|---|
| **Level** | 2 |
| **Priority** | P0 |
| **Status** | Complete |
| **Created** | 2026-08-17 |
| **Branch** | `agent/frontend-hygiene` |
| **Source** | `.scratch/lsp-loan-event-feed/issues/02-loan-event-log.md` |
<!-- /ANCHOR:metadata -->

---

<!-- ANCHOR:problem -->
## 2. PROBLEM & PURPOSE

Lifecycle production is centralized, but the seam still writes to a subscription-filtered webhook outbox. The platform needs an unconditional event log whose ordering remains safe when transactions commit out of start order.

The purpose is to land durable storage and hard-cut producers onto it without adding a partner read endpoint yet.
<!-- /ANCHOR:problem -->

---

<!-- ANCHOR:scope -->
## 3. SCOPE

### In Scope

- Monthly-partitioned `loan_event` table with immutable event data and `(transaction_id, position)` ordering.
- Tenant RLS and tenant application-role grants matching the existing outbox posture.
- Persistence model and repository required by `LoanEventLog.append`.
- Hard cutover from subscription-filtered outbox writes to unconditional event-log writes.
- A real Postgres two-connection test for late commit visibility.

### Out of Scope

- Partner feed HTTP endpoint, filtering, cursors, rewind, and expiry; issue 03 and later tickets own them.
- Automated partition creation and retention; issue 05 owns them.
- Webhook code/table deletion; issues 11 and 12 own it.
- Internal status-transition and audit history changes.

### Files to Change

| File Path | Change Type | Description |
|---|---|---|
| `backend/src/main/resources/db/migration/V116__create_loan_event_log.sql` | Create | Table, partitions, immutability, RLS, grants |
| `backend/src/main/java/com/bhawana/lms/domain/LoanEvent.java` | Create | Persistence mapping |
| `backend/src/main/java/com/bhawana/lms/repo/LoanEventRepository.java` | Create | Append/read support |
| `backend/src/main/java/com/bhawana/lms/service/LoanEventLog.java` | Modify | Unconditional event-log append |
| `backend/src/test/java/com/bhawana/lms/repo/LoanEventRepositoryPostgresTest.java` | Create | Database correctness and commit-order proof |
<!-- /ANCHOR:scope -->

---

<!-- ANCHOR:requirements -->
## 4. REQUIREMENTS

### P0 - Blockers

| ID | Requirement | Acceptance Criteria |
|---|---|---|
| REQ-001 | Store the complete event envelope | Stable event ID, owner LSP, event and aggregate identity, application ID, JSON payload, business-event time, correlation ID, transaction ID, and position are persisted |
| REQ-002 | Use commit-safe ordering | Every row receives the current `xid8` and a monotonic position unique within that transaction |
| REQ-003 | Partition from birth | The parent is range-partitioned by business-event month and current writes have a partition |
| REQ-004 | Enforce tenant isolation | RLS filters by `app.current_lsp_id` and tenant application roles have only required privileges |
| REQ-005 | Cut over unconditionally | `LoanEventLog.append` has no webhook enablement or subscription guard and writes no outbox row |
| REQ-006 | Prove late-commit safety | A two-connection test commits the later transaction first and still observes the earlier-started transaction after watermark advancement |
| REQ-007 | Preserve internal history | No status-transition or audit history schema or writer is modified |

### P1 - Required

| ID | Requirement | Acceptance Criteria |
|---|---|---|
| REQ-008 | Preserve producer contract | All 13 existing call sites compile without signature changes |
| REQ-009 | Fail atomically | Serialization or persistence failure rolls back the surrounding lifecycle state transaction |
<!-- /ANCHOR:requirements -->

---

<!-- ANCHOR:success-criteria -->
## 5. SUCCESS CRITERIA

- **Given** two transactions that start in order A then B, **When** B commits before A, **Then** paging below a safe snapshot watermark eventually returns both without skipping A.
- **Given** an LSP with webhooks disabled and no subscriptions, **When** a lifecycle producer appends, **Then** a `loan_event` row is persisted and no outbox row is created.
- **Given** a tenant-scoped connection, **When** it queries `loan_event`, **Then** it sees only rows owned by that LSP.
- **Given** a write in the current month, **When** the event is persisted, **Then** Postgres routes it to an existing monthly partition.
- **SC-005**: Focused database tests and compilation pass; the full suite is run and any environment-only blockers are recorded.
<!-- /ANCHOR:success-criteria -->

---

<!-- ANCHOR:risks -->
## 6. RISKS & DEPENDENCIES

| Type | Item | Impact | Mitigation |
|---|---|---|---|
| Dependency | Issue 01 append seam | Producer churn if incomplete | Verified 13 calls across 10 services and no remaining direct enqueue calls |
| Risk | Postgres transaction IDs are not commit timestamps | Incorrect cursor assumptions | Store `xid8`; consumer watermark semantics remain explicit and are proven with two connections |
| Risk | Partition key constrains uniqueness | Duplicate event IDs | Use partition-compatible keys and an application-generated UUID |
| Risk | Dirty worktree contains unrelated changes | Unsafe commit | Stage only audited files/hunks or report if isolation is impossible |
<!-- /ANCHOR:risks -->

---

<!-- ANCHOR:nfr -->
## L2: NON-FUNCTIONAL REQUIREMENTS

- Append remains in the caller transaction and performs one database insert.
- Storage is append-only for application roles; updates and deletes are rejected.
- Payload serialization preserves the existing envelope contract.
<!-- /ANCHOR:nfr -->

---

<!-- ANCHOR:edge-cases -->
## L2: EDGE CASES

- Multiple events appended in one transaction receive distinct, increasing positions.
- A missing correlation identifier is represented consistently rather than blocking the domain write.
- A transaction rollback leaves no event row.
- Writes outside provisioned partitions fail loudly; automated partition management remains out of scope.
<!-- /ANCHOR:edge-cases -->

---

<!-- ANCHOR:complexity -->
## L2: COMPLEXITY ASSESSMENT

The implementation is moderate in size but high in correctness sensitivity because Postgres MVCC ordering, partitioning, RLS, and surrounding transaction atomicity interact. Level 2 adds explicit verification for those risks.
<!-- /ANCHOR:complexity -->

---

<!-- ANCHOR:questions -->
## 10. OPEN QUESTIONS

None. The source ticket and ADR fix the storage, ordering, cutover, and test seams.
<!-- /ANCHOR:questions -->
