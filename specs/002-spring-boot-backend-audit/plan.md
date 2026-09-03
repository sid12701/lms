---
title: "Implementation Plan: Fresh Spring Boot Backend Quality Audit"
description: "Use a staged, read-mostly review to establish the backend baseline, trace critical paths, apply only behavior-preserving cleanup, and verify the result."
trigger_phrases:
  - "backend audit plan"
  - "behavior preserving plan"
  - "package structure proposal"
importance_tier: "important"
contextType: "implementation"
_memory:
  continuity:
    packet_pointer: "specs/002-spring-boot-backend-audit"
    last_updated_at: "2026-08-01T17:01:14Z"
    last_updated_by: "codex"
    recent_action: "Executed the audit plan, bounded remediation, and verification ladder"
    next_safe_action: "Review evidence and approve or defer higher-risk recommendations"
    blockers: ["graphify unavailable", "Docker/Testcontainers unavailable in the current sandbox"]
    key_files: ["backend/pom.xml", "backend/mvnw", "spec.md"]
    session_dedup:
      fingerprint: "sha256:0000000000000000000000000000000000000000000000000000000000000000"
      session_id: "codex-2026-08-01-backend-audit"
      parent_session_id: null
    completion_pct: 100
    open_questions: ["Safe remediation candidates"]
    answered_questions: ["Fresh Level 3+ packet selected"]
---
# Implementation Plan: Fresh Spring Boot Backend Quality Audit

<!-- SPECKIT_LEVEL: 3+ -->
<!-- SPECKIT_TEMPLATE_SOURCE: plan-core + level2-verify + level3-arch + level3-plus-govern -->

<!-- ANCHOR:summary -->
## 1. SUMMARY

### Technical Context

| Aspect | Value |
|--------|-------|
| **Language/Stack** | Java, Maven wrapper |
| **Framework** | Spring Boot, Spring MVC, Spring Security, Spring Data JPA |
| **Storage** | PostgreSQL in runtime; H2 and Testcontainers in tests; Flyway migrations |
| **Testing** | Maven/Spring Boot tests, MockMvc, Testcontainers, ArchUnit |

### Overview

Review the backend in stages: repository map and graph status, baseline verification, automated navigation signals, manual path tracing, safe remediation, and final verification. The package proposal is a report-only deliverable until approval.
<!-- /ANCHOR:summary -->

<!-- ANCHOR:quality-gates -->
## 2. QUALITY GATES

### Definition of Ready

- [x] Problem statement, scope, and behavior-preservation constraints documented.
- [x] Existing worktree and graph limitation recorded.
- [x] Review skills and evidence standard loaded.

### Definition of Done

- [x] Baseline and analyzer commands are recorded with exit status.
- [x] Critical backend flows are traced to side effects, tests, configuration, and database mappings.
- [x] Only safe, bounded edits are implemented and verified; no production edit was warranted.
- [x] Final report separates confirmed quality issues, suspected defects, false positives, and limitations.
<!-- /ANCHOR:quality-gates -->

<!-- ANCHOR:architecture -->
## 3. ARCHITECTURE

### Pattern

Existing layered Spring Boot monolith with controllers/web, services, repositories, domain entities, common/config/security packages, migrations, and infrastructure integrations.

### Key Components

- **Web/controllers**: HTTP entry points, request validation, response mapping, and security-boundary integration.
- **Services/workers**: application orchestration, lifecycle transitions, scheduled/background work, and integration coordination.
- **Domain**: JPA entities, statuses, value-like types, and domain operations.
- **Repositories**: persistence queries and locking/fetch plans.
- **Common/config/security**: cross-cutting behavior that must be reviewed for ownership and reachability.

### Data Flow

Requests and jobs enter through Spring-discovered components, cross service/application orchestration, access repositories and integrations, update domain state within existing transactions, and emit API/audit/outbox results. The audit follows these paths without changing their semantics.
<!-- /ANCHOR:architecture -->

<!-- ANCHOR:affected-surfaces -->
## FIX ADDENDUM: AFFECTED SURFACES

The audit must inventory every changed symbol and consumer before implementation.

| Surface | Current Role | Action | Verification |
|---------|--------------|--------|--------------|
| Backend production classes | Runtime behavior and domain boundaries | Unchanged unless safe cleanup is proven | Full caller/config/test trace |
| Backend tests | Behavior protection | Add or strengthen only for implemented cleanup | Focused Maven tests |
| Public API, persistence, security, integrations | Contract and runtime semantics | Explicitly unchanged | Contract/config/mapping review |
| Package structure | Navigation proposal | Report only | Decision record and research citations |

Required inventories:

- Search same-class producers and duplicate policy implementations before extracting anything.
- Search all consumers of renamed or moved symbols before considering a rename.
- Treat transaction, lock, API, security, and integration behavior as independent invariants that must remain unchanged.
<!-- /ANCHOR:affected-surfaces -->

<!-- ANCHOR:phases -->
## 4. IMPLEMENTATION PHASES

### Phase 1: Baseline and repository map

- [x] Verify graphify availability and refresh if possible; unavailable in this checkout and recorded.
- [x] Inspect repository instructions, manifests, CI, configuration, migrations, and tests.
- [x] Run the existing backend baseline through the wrapper.

### Phase 2: Deep review and safe remediation

- [x] Run the thermonuclear maintainability review over current backend changes and high-risk units.
- [x] Trace each selected finding through callers, framework semantics, side effects, tests, and configuration.
- [x] Implement only confirmed behavior-preserving cleanup; record defects separately. Three additional changes met the proof threshold: two ineffective self-invocation transaction annotations were removed, webhook test cleanup was centralized, and the live R2 probe became explicitly opt-in.

### Phase 3: Verification and report

- [x] Run focused and full available tests/build checks; the fresh offline suite passed with 725 tests, 0 failures, 0 errors, and 86 skips after remediation.
- [x] Review the final diff for accidental functional/config/generated changes.
- [x] Produce package-structure research and final audit report.
<!-- /ANCHOR:phases -->

<!-- ANCHOR:testing -->
## 5. TESTING STRATEGY

| Test Type | Scope | Tools |
|-----------|-------|-------|
| Unit | Pure helpers, mappings, deterministic cleanup | Maven Surefire/JUnit |
| Component | Spring wiring, validation, exception translation | Spring Boot tests/MockMvc |
| Integration | JPA, transactions, migrations, security, external test dependencies | Testcontainers/H2 where already configured |
| Static/architecture | Package dependencies and configured analyzers | Maven plugins and ArchUnit |
| Manual review | Caller, framework, contract, and diff evidence | `rg`, targeted source/config inspection |
<!-- /ANCHOR:testing -->

<!-- ANCHOR:dependencies -->
## 6. DEPENDENCIES

| Dependency | Type | Status | Impact if Blocked |
|------------|------|--------|-------------------|
| `backend/mvnw` | Internal tool | Available | Baseline cannot be reproduced without fallback |
| Maven dependency cache | Local environment | To verify | Build/test may fail before source execution |
| Docker/Testcontainers | Local infrastructure | To verify | PostgreSQL/MinIO integration paths may be skipped |
| Graphify CLI/report | Repository mapping | Unavailable at intake | Graph-first navigation requirement cannot be fully met |
| Official Spring documentation | Research source | Available via web if needed | Package proposal source quality is reduced |
<!-- /ANCHOR:dependencies -->

<!-- ANCHOR:rollback -->
## 7. ROLLBACK PLAN

- **Trigger**: Any test, compile result, diff inspection, or path analysis indicates altered behavior or unclear equivalence.
- **Procedure**: Do not retain the candidate edit; use the patch boundary to restore only packet-owned changes, then rerun the relevant baseline. Never reset unrelated worktree changes.
<!-- /ANCHOR:rollback -->

<!-- ANCHOR:phase-deps -->
## L2: PHASE DEPENDENCIES

| Phase | Depends On | Blocks |
|-------|------------|--------|
| Baseline | Scope and tool detection | Deep review |
| Deep review | Baseline and repository map | Remediation decision |
| Verification | Remediation or explicit no-change result | Final report |
| Package proposal | Architecture evidence and research | Later approval discussion |
<!-- /ANCHOR:phase-deps -->

<!-- ANCHOR:effort -->
## L2: EFFORT ESTIMATION

| Phase | Complexity | Estimated Effort |
|-------|------------|------------------|
| Baseline and map | High | 1-2 hours |
| Deep review | High | 4-8 hours |
| Safe remediation | Medium/High | 2-6 hours depending on confirmed findings |
| Verification/report | High | 2-4 hours |
| **Total** | | **9-20 hours** |
<!-- /ANCHOR:effort -->

<!-- ANCHOR:enhanced-rollback -->
## L2: ENHANCED ROLLBACK

### Pre-deployment Checklist

- [x] No deployment or data migration is in scope.
- [x] Existing dirty changes are preserved.
- [ ] Final packet-owned diff reviewed before handoff.

### Rollback Procedure

1. Stop at the first behavior-preservation failure.
2. Remove or revert only the candidate cleanup using its bounded patch.
3. Rerun the focused check and baseline command that exposed the issue.
4. Keep the finding in the report as a recommendation if approval is required.

### Data Reversal

- **Has data migrations?** No.
- **Reversal procedure**: Not applicable; no migrations or database writes are performed by the audit.
<!-- /ANCHOR:enhanced-rollback -->

<!-- ANCHOR:dependency-graph -->
## L3: DEPENDENCY GRAPH

```text
Repository instructions and graph status -> baseline -> deep trace -> safe remediation decision
                                                |                         |
                                                +-> research proposal     +-> focused verification
                                                                            |
                                                                            +-> final report
```

### Dependency Matrix

| Component | Depends On | Produces | Blocks |
|-----------|------------|----------|--------|
| Baseline | Wrapper and local dependencies | Reproducible status | Review attribution |
| Deep trace | Source, callers, config, tests | Evidence-backed findings | Safe edits |
| Research | Current package evidence and official guidance | Proposal and tradeoffs | Later migration approval |
| Verification | Candidate diff and available infrastructure | Confidence and limitations | Completion claim |
<!-- /ANCHOR:dependency-graph -->

<!-- ANCHOR:critical-path -->
## L3: CRITICAL PATH

1. Baseline and graph limitation classification.
2. Critical-flow tracing and finding validation.
3. Behavior-preserving edit decision and focused tests.
4. Full available verification and report.

**Total Critical Path**: One controlled audit sequence.

**Parallel Opportunities**:

- Independent read-only inspections of security/configuration, persistence/transactions, and maintainability can proceed after baseline.
- Package-structure research can proceed while code findings are being traced, but its recommendation remains unapproved.
<!-- /ANCHOR:critical-path -->

<!-- ANCHOR:milestones -->
## L3: MILESTONES

| Milestone | Description | Success Criteria | Target |
|-----------|-------------|------------------|--------|
| M1 | Baseline captured | Commands and failures recorded | Phase 1 |
| M2 | Review evidence complete | Findings meet evidence contract | Phase 2 |
| M3 | Safe changes verified | No new behavior or build/test failures | Phase 3 |
| M4 | Recommendation ready | Package options and migration risks documented | Phase 3 |
<!-- /ANCHOR:milestones -->

<!-- ANCHOR:ai-execution -->
## L3+: AI EXECUTION FRAMEWORK

### Tier 1: Sequential Foundation

Primary agent establishes scope, baseline, graph limitation, and review map.

### Pre-Task Checklist

- Confirm the current worktree and preserve unrelated changes.
- Confirm the backend wrapper, configured checks, and graphify limitation.
- Read the complete target class and trace callers before classifying a finding.

### Execution Rules

| Rule | Required behavior |
|------|-------------------|
| TASK-SEQ | Complete baseline and evidence collection before editing application code. |
| TASK-SCOPE | Keep changes inside the requested backend quality scope. |
| TASK-EVIDENCE | Do not report analyzer output without a code-grounded path and test/configuration review. |
| TASK-SAFETY | If equivalence is uncertain, document the recommendation and do not implement it. |

### Status Reporting Format

Each progress update records the current phase, commands run, observed result, blockers, and next safe action.

### Blocked Task Protocol

When an environment or evidence blocker occurs, record the exact command and missing capability, continue with safe read-only work, and do not mark the blocked verification as passed.

### Tier 2: Parallel Execution

| Agent | Focus | Files |
|-------|-------|-------|
| Primary review | Backend flow, persistence, security, tests | `backend/src/main/**`, `backend/src/test/**` |
| Maintainability lens | Thermo-nuclear structure, abstractions, duplication | Same backend scope |
| Research lens | Spring and package-structure guidance | `specs/002...` only |

### Tier 3: Integration

Primary agent reconciles evidence, applies only authorized bounded changes, verifies, and writes the final summary.
<!-- /ANCHOR:ai-execution -->

<!-- ANCHOR:governance-plan -->
## L3+: GOVERNANCE PLAN

- No code-quality edit is accepted without a behavior-preservation rationale.
- No suspected functional defect is fixed in this packet.
- No package migration is implemented without explicit later approval.
- P0 checklist items block completion; P1 items require completion or user-approved deferral.
<!-- /ANCHOR:governance-plan -->
