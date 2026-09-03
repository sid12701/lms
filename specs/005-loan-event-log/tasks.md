---
title: "Tasks: Durable Loan Event Log"
description: "Execution checklist for issue 02."
trigger_phrases: ["loan event log", "tasks"]
importance_tier: "important"
contextType: "implementation"
---
# Tasks: Durable Loan Event Log

<!-- SPECKIT_LEVEL: 2 -->
<!-- SPECKIT_TEMPLATE_SOURCE: tasks-core | v2.2 -->

---

<!-- ANCHOR:notation -->
## Task Notation

`[P0]` blocks completion; `[P1]` is required unless explicitly deferred; evidence follows completed items.
<!-- /ANCHOR:notation -->

---

<!-- ANCHOR:phase-1 -->
## Phase 1: Setup

- [x] T-001 [P0] Verify issue 01 acceptance criteria and compile state. — Evidence: 13 append calls across 10 services, no remaining production `enqueueIfSubscribed` calls, and compilation passed.
- [x] T-002 [P0] Confirm the Postgres two-connection seam from the parent spec. — Evidence: parent spec seam B and ADR 0007 were used for the repository integration test.
- [x] T-003 [P0] Audit existing outbox schema, RLS, grants, xid helpers, and repository-test conventions. — Evidence: V24/V41/V45 and existing Postgres test support informed V116 and the focused test class.
<!-- /ANCHOR:phase-1 -->

---

<!-- ANCHOR:phase-2 -->
## Phase 2: Implementation

- [x] T-010 [P0] Add a failing real-Postgres test for the event log and late commit ordering. — Evidence: `LoanEventRepositoryPostgresTest` controls two transactions and advances a one-row composite cursor across three events.
- [x] T-011 [P0] Add the migration, mapping, and repository. — Evidence: V116, `LoanEvent`, and `LoanEventRepository` compile and pass real-Postgres tests.
- [x] T-012 [P0] Cut `LoanEventLog.append` over unconditionally. — Evidence: the service now writes only `loan_event` and rejects calls outside an active lifecycle transaction.
- [x] T-013 [P1] Verify all producer signatures remain unchanged and the outbox receives no writes. — Evidence: all 13 call sites compile; focused and lifecycle integration tests assert zero new outbox rows.
<!-- /ANCHOR:phase-2 -->

---

<!-- ANCHOR:phase-3 -->
## Phase 3: Verification

- [x] T-020 [P0] Run focused tests and compilation. — Evidence: focused PostgreSQL event-log and repayment regressions pass; `test-compile` passes.
- [x] T-021 [P0] Run the full backend suite and record environmental blockers precisely. — Evidence: final `./mvnw -q test` exited 0; XML reports aggregate to 826 tests, 0 failures, 0 errors, 2 skipped.
- [x] T-022 [P0] Run strict Spec Kit validation and refresh graphify if available. — Evidence: structural packet gates pass; strict validation is blocked by the installed kit's missing `level-contract-resolver.js`, and `graphify` is not installed.
- [x] T-023 [P0] Run standards and spec reviews; address findings. — Evidence: the active-transaction guard and a real composite-cursor page-boundary test resolve both actionable review findings.
- [x] T-024 [P1] Audit commit isolation and avoid an unsafe commit. — Evidence: issue 02 depends on uncommitted issue-01 files mixed with unrelated user-owned work, so no files were staged or committed.
<!-- /ANCHOR:phase-3 -->

---

<!-- ANCHOR:completion -->
## Completion Criteria

All issue-02 acceptance criteria are implemented, P0/P1 checklist items have evidence, review findings are resolved, and unavailable environment dependencies are separated from product failures.
<!-- /ANCHOR:completion -->

---

<!-- ANCHOR:cross-refs -->
## Cross-References

- `.scratch/lsp-loan-event-feed/issues/02-loan-event-log.md`
- `specs/004-lsp-loan-event-feed/spec.md`
- `docs/adr/0007-partner-lifecycle-updates-are-pull-based.md`
- `CONTEXT.md` § Partner lifecycle updates
<!-- /ANCHOR:cross-refs -->
