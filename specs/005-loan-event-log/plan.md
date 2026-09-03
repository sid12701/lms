---
title: "Implementation Plan: Durable Loan Event Log"
description: "Test-first delivery plan for issue 02."
trigger_phrases: ["loan event log", "implementation plan"]
importance_tier: "important"
contextType: "implementation"
---
# Implementation Plan: Durable Loan Event Log

<!-- SPECKIT_LEVEL: 2 -->
<!-- SPECKIT_TEMPLATE_SOURCE: plan-core | v2.2 -->

---

<!-- ANCHOR:summary -->
## 1. SUMMARY

Add the Postgres event log and its mapping, prove its ordering behavior at the database seam, then replace the transitional outbox body inside `LoanEventLog.append`. Producer call sites remain untouched.
<!-- /ANCHOR:summary -->

---

<!-- ANCHOR:quality-gates -->
## 2. QUALITY GATES

- Red/green cycles occur at the real Postgres repository seam.
- Migration semantics are inspected directly and exercised through Flyway-backed tests.
- Compilation runs after each production slice.
- Strict packet validation and the two-axis review run before completion.
<!-- /ANCHOR:quality-gates -->

---

<!-- ANCHOR:architecture -->
## 3. ARCHITECTURE

`LoanEventLog` remains the single producer-facing service. It serializes the existing payload envelope and persists a `LoanEvent`; Postgres supplies `pg_current_xact_id()` and transaction-local position ordering. The table is range-partitioned by `occurred_at`, while tenant isolation uses the same session GUC convention as existing LSP-owned tables.
<!-- /ANCHOR:architecture -->

---

<!-- ANCHOR:fix-surfaces -->
## FIX ADDENDUM: AFFECTED SURFACES

| Surface | Inventory |
|---|---|
| Producers | 13 calls in 10 services; signature unchanged |
| Storage | New migration, entity, repository |
| Delivery | Outbox receives no new rows; dispatcher code remains until issue 11 |
| Security | RLS policy and tenant-role grants |
| Tests | Real Postgres repository test with two controlled connections |
<!-- /ANCHOR:fix-surfaces -->

---

<!-- ANCHOR:phases -->
## 4. IMPLEMENTATION PHASES

### Phase 1: Storage proof

Write a failing PostgreSQL test for row shape, commit-order behavior, and composite page boundaries.

### Phase 2: Persistence implementation

Add the migration, domain mapping, and repository until storage tests pass.

### Phase 3: Producer cutover

Cut `LoanEventLog.append` from the outbox to the event repository and adapt focused tests.

### Phase 4: Verification and closeout

Run full verification, two-axis review, documentation closeout, graph refresh, and commit-isolation checks.
<!-- /ANCHOR:phases -->

---

<!-- ANCHOR:testing -->
## 5. TESTING STRATEGY

The agreed seam is Postgres through two independent connections. Tests observe results through repository/query behavior, not mocks of internal collaborators. A focused service test may drive the public `append` method and assert persisted state.
<!-- /ANCHOR:testing -->

---

<!-- ANCHOR:dependencies -->
## 6. DEPENDENCIES

Issue 01, PostgreSQL/Testcontainers, Flyway, ADR 0005 tenant scope, and ADR 0007 ordering semantics.
<!-- /ANCHOR:dependencies -->

---

<!-- ANCHOR:rollback -->
## 7. ROLLBACK PLAN

Before production, revert the service cutover and migration together. Do not dual-write; the accepted design requires a hard cutover.
<!-- /ANCHOR:rollback -->

---

<!-- ANCHOR:phase-deps -->
## L2: PHASE DEPENDENCIES

Storage tests precede implementation; service cutover depends on the repository; full verification depends on Docker availability.
<!-- /ANCHOR:phase-deps -->

---

<!-- ANCHOR:effort -->
## L2: EFFORT ESTIMATION

The core production slice remained focused. Verification additionally required updating cleanup and legacy-outbox
expectations in affected integration tests because lifecycle writes now target a foreign-keyed event table.
<!-- /ANCHOR:effort -->

---

<!-- ANCHOR:enhanced-rollback -->
## L2: ENHANCED ROLLBACK

Verification confirms the outbox path is untouched except for removal of new writes, so a source revert restores prior behavior without reconstructing deleted webhook machinery.
<!-- /ANCHOR:enhanced-rollback -->
