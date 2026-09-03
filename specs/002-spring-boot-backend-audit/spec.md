---
title: "Fresh Spring Boot Backend Quality Audit"
description: "Perform a repository-wide, evidence-backed audit of the Java Spring Boot backend and implement only low-risk, behavior-preserving code-quality improvements. Keep suspected functional defects separate for discussion."
trigger_phrases:
  - "Spring Boot backend audit"
  - "thermonuclear code quality review"
  - "behavior-preserving remediation"
importance_tier: "important"
contextType: "implementation"
_memory:
  continuity:
    packet_pointer: "specs/002-spring-boot-backend-audit"
    last_updated_at: "2026-08-01T17:01:14Z"
    last_updated_by: "codex"
    recent_action: "Applied bounded behavior-preserving code-quality cleanups and completed verification"
    next_safe_action: "Review findings and approve or defer higher-risk recommendations"
    blockers: ["graphify command and generated graph report are unavailable in this checkout"]
    key_files: ["backend/pom.xml", "backend/src/main/java", "AGENTS.md"]
    session_dedup:
      fingerprint: "sha256:0000000000000000000000000000000000000000000000000000000000000000"
      session_id: "codex-2026-08-01-backend-audit"
      parent_session_id: null
    completion_pct: 100
    open_questions: ["Which higher-risk recommendations should receive separate approval and implementation packets?"]
    answered_questions: ["Use a new Level 3+ fresh-audit packet", "Thermo-Nuclear Code Quality Review skill is available and applicable"]
---
<!-- SPECKIT_LEVEL: 3+ -->
# Fresh Spring Boot Backend Quality Audit

<!-- SPECKIT_TEMPLATE_SOURCE: spec-core + level2-verify + level3-arch + level3-plus-govern -->

## EXECUTIVE SUMMARY

This packet governs a deep review of the backend application under `/backend`, including build configuration, runtime entry points, persistence, security, integrations, jobs, tests, and package structure. The review may implement bounded readability, maintainability, consistency, and confirmed-dead-code improvements only when the change is demonstrably behavior-preserving. Suspected functional defects remain documented recommendations and are not fixed.

**Key Decisions**: use the existing Maven wrapper and repository tooling; defer package restructuring until explicit approval; treat the current dirty worktree as pre-existing.

**Critical Dependencies**: a trustworthy baseline build and test environment; PostgreSQL/Testcontainers availability for database and concurrency paths; authoritative Spring documentation for structural recommendations.

<!-- ANCHOR:metadata -->
## 1. METADATA

| Field | Value |
|-------|-------|
| **Level** | 3+ |
| **Priority** | P0 |
| **Status** | Complete for audit and safe remediation; higher-risk recommendations pending approval |
| **Created** | 2026-08-01 |
| **Branch** | `main` (existing worktree preserved) |
<!-- /ANCHOR:metadata -->

<!-- ANCHOR:problem -->
## 2. PROBLEM & PURPOSE

### Problem Statement

The supplied brief requests a comprehensive Java Spring Boot code-quality audit, safe remediation, and a researched package-structure proposal. The repository has substantial existing worktree changes and no usable `graphify-out` report in the checkout, so the review must first separate baseline state from new work, trace the real backend flows, and state environment limitations precisely.

### Purpose

Deliver an evidence-backed backend quality report, implement only bounded changes with unchanged runtime and contract behavior, verify those changes with the strongest available checks, and provide a package-structure recommendation for later approval rather than applying a package migration now.
<!-- /ANCHOR:problem -->

<!-- ANCHOR:scope -->
## 3. SCOPE

### In Scope

- `backend/` Maven Spring Boot service and its Java production/test sources.
- Build, wrapper, configuration, migrations, security, repositories, services, controllers, integrations, scheduled workers, and tests.
- Baseline verification, strict maintainability review, targeted manual tracing, and configured static-analysis checks.
- Safe code-quality remediation limited to behavior-preserving edits.
- Separate suspected-defect register and researched package-structure proposal.

### Out of Scope

- Business-rule, API, database, transaction, security, integration, or application-architecture changes.
- Major package or folder restructuring before user approval.
- Frontend, infrastructure, live systems, production databases, dependency upgrades, migrations, or external scans unless separately authorized.
- Deletion of code whose runtime or external reachability cannot be proven.

### Files to Change

| File Path | Change Type | Description |
|-----------|-------------|-------------|
| `specs/002-spring-boot-backend-audit/*` | Create/Modify | Audit scope, decisions, tasks, checklist, and final implementation record |
| `backend/src/main/java/**` | Targeted Modify | Only proven behavior-preserving quality improvements |
| `backend/src/test/java/**` | Targeted Modify | Tests that protect behavior of an implemented safe cleanup |
| Package structure | Recommendation only | Proposal for later review; no package migration in this packet |
<!-- /ANCHOR:scope -->

<!-- ANCHOR:requirements -->
## 4. REQUIREMENTS

### P0 - Blockers (MUST complete)

| ID | Requirement | Acceptance Criteria |
|----|-------------|---------------------|
| REQ-001 | Establish an honest backend baseline | Exact Maven commands, exit codes, failures, warnings, skipped infrastructure tests, and pre-existing worktree state are recorded. |
| REQ-002 | Complete the deep review | Entry points, callers, persistence, transactions, security, integrations, jobs, tests, and package relationships are inspected; analyzer output is not reported without code grounding. |
| REQ-003 | Preserve behavior | No implemented change alters business rules, public API, persistence semantics, transaction boundaries, security behavior, integration behavior, or application architecture. |
| REQ-004 | Separate suspected defects | Every suspected functional defect is placed in a discussion-only section with evidence and is not modified. |

### P1 - Required (complete OR user-approved deferral)

| ID | Requirement | Acceptance Criteria |
|----|-------------|---------------------|
| REQ-005 | Implement bounded remediation | Only confirmed dead/redundant code or local readability improvements with demonstrated behavioral equivalence are changed. |
| REQ-006 | Verify changes | Focused tests, compile/build, relevant integration tests, graph refresh attempt, and final diff review are completed or limitations are explicit. |
| REQ-007 | Research package structure | Current package evidence and authoritative Spring guidance support a proposal; no structural move is applied without approval. |
<!-- /ANCHOR:requirements -->

<!-- ANCHOR:success-criteria -->
## 5. SUCCESS CRITERIA

- **SC-001**: The final report distinguishes confirmed quality issues, suspected functional defects, false positives, and environment limitations.
- **SC-002**: All application changes have a traceable behavior-preservation rationale and focused verification.
- **SC-003**: Available backend build/tests pass, with baseline failures clearly separated from change-caused failures.
- **SC-004**: The package proposal is concrete, evidence-backed, reversible, and explicitly deferred for approval.
<!-- /ANCHOR:success-criteria -->

<!-- ANCHOR:risks -->
## 6. RISKS & DEPENDENCIES

| Type | Item | Impact | Mitigation |
|------|------|--------|------------|
| Dependency | Existing dirty worktree | Baseline and diff attribution can be misleading | Record status first; do not overwrite unrelated changes; review only packet-owned deltas. |
| Dependency | Missing graphify output/tool | Graph-first navigation cannot be completed | Attempt refresh and record exact limitation; use targeted raw inspection only after documenting the gap. |
| Risk | A cleanup may hide a behavior change | Runtime or contract regression | Prefer no change when equivalence is uncertain; add focused tests before editing. |
| Risk | Tool or infrastructure failure appears to be a code failure | Incorrect findings or false confidence | Classify environment failures separately and do not claim full verification. |
<!-- /ANCHOR:risks -->

<!-- ANCHOR:questions -->
## 7. NON-FUNCTIONAL REQUIREMENTS

<!-- ANCHOR:nfr -->
### Performance

- **NFR-P01**: Safe remediation must not add database calls, locks, allocations, network calls, or measurable request/job work.
- **NFR-P02**: Review tooling must not run load tests or mutate shared infrastructure.

### Security

- **NFR-S01**: Security behavior, tenant boundaries, authentication, authorization, secrets, and PII handling remain unchanged.

### Reliability

- **NFR-R01**: Transaction, retry, idempotency, concurrency, and error semantics remain unchanged unless documented only as a recommendation.
<!-- /ANCHOR:nfr -->

<!-- ANCHOR:edge-cases -->
## 8. EDGE CASES

### Data Boundaries

- Empty and null inputs: preserve current validation and error behavior.
- Duplicate or concurrent requests: preserve existing locking, uniqueness, idempotency, and transaction behavior.
- Existing production-like data: do not run migrations or destructive cleanup during review.

### Error Scenarios

- Missing external dependencies: record the skipped integration path and residual risk.
- Baseline test failure: preserve the failure as baseline evidence unless a change demonstrably causes a new failure.
- Framework-discovered code: do not call it dead based only on static references.
<!-- /ANCHOR:edge-cases -->

<!-- ANCHOR:complexity -->
## 9. COMPLEXITY ASSESSMENT

| Dimension | Score | Triggers |
|-----------|-------|----------|
| Scope | 24/25 | Backend manifest, large Java source tree, multiple runtime concerns, broad tests |
| Risk | 24/25 | Persistence, loan lifecycle, security, PII, integrations, transaction and concurrency sensitivity |
| Research | 18/20 | Current Spring guidance and package-structure research required |
| Multi-Agent | 8/15 | Multiple review lenses but one controlled workstream |
| Coordination | 13/15 | Existing dirty worktree, deferred package migration, verification dependencies |
| **Total** | **87/100** | Level 3+ |
<!-- /ANCHOR:complexity -->

## 10. RISK MATRIX

| Risk ID | Description | Impact | Likelihood | Mitigation |
|---------|-------------|--------|------------|------------|
| R-001 | Behavior-changing cleanup is accidentally implemented | High | Medium | Require caller/config/test trace and no-change fallback for uncertain findings. |
| R-002 | Existing dirty changes are attributed to this audit | High | High | Capture status and inspect packet-owned diff only. |
| R-003 | Database/integration verification is unavailable | High | Medium | Run safe local checks; name every skipped path and required follow-up. |
| R-004 | Package recommendation is applied prematurely | Medium | Low | Keep it in the report and decision record; do not move packages. |

## 11. USER STORIES

### US-001: Reviewable backend quality baseline (Priority: P0)

**As an** engineering reviewer, **I want** a traced and reproducible backend audit, **so that** quality changes can be discussed without confusing them with functional redesign.

**Acceptance Criteria**:

1. Given the current worktree, when the audit completes, then the report identifies the baseline, findings, limitations, and changed files separately.

<!-- ANCHOR:approval-workflow -->
## 12. APPROVAL WORKFLOW

| Checkpoint | Approver | Status | Date |
|------------|----------|--------|------|
| Spec Review | User | Approved by fresh-audit selection | 2026-08-01 |
| Design Review | User | Pending package-structure discussion | |
| Implementation Review | User | Pending final diff/report | |
| Launch Approval | User | Not applicable; no deployment | |
<!-- /ANCHOR:approval-workflow -->

<!-- ANCHOR:compliance-checkpoints -->
## 13. COMPLIANCE CHECKPOINTS

### Security Compliance

- [ ] Security review completed against repository code and configuration.
- [ ] OWASP-relevant paths reviewed without changing security behavior.
- [ ] PII and secrets are not exposed in report output.

### Code Compliance

- [ ] Existing Java/Spring conventions and configured tooling reviewed.
- [ ] No dependency upgrade, migration, or live-system mutation performed.
<!-- /ANCHOR:compliance-checkpoints -->

<!-- ANCHOR:stakeholder-matrix -->
## 14. STAKEHOLDER MATRIX

| Stakeholder | Role | Interest | Communication |
|-------------|------|----------|---------------|
| User | Approval owner | High | Final report and package proposal |
| Backend maintainers | Code owners | High | Findings, safe changes, and verification evidence |
| Operations/data owners | Runtime stakeholders | Medium | Only suspected defects and skipped infrastructure risks |
<!-- /ANCHOR:stakeholder-matrix -->

<!-- ANCHOR:change-log -->
## 15. CHANGE LOG

### v1.0 (2026-08-01)

Created the fresh Level 3+ audit packet after user selected a new deep audit.

### v1.1 (2026-08-01)

Completed the evidence-backed audit. Findings, suspected defects, verification, and the deferred package proposal are in `final-report.md`.

### v1.2 (2026-08-01)

Applied two bounded behavior-preserving cleanups: private compile-time error-code constants in `GlobalExceptionHandler.java` and equivalent boolean assertion predicates in `AuditExplorerControllerTest.java`. Focused and full Maven verification passed; no functional defect was fixed.

### v1.3 (2026-08-02)

Re-audited the plan with three GPT-5.6 Luna xhigh workstreams. Corrected the historical baseline, implemented three additional behavior-preserving improvements in `LoanDocumentService` and two test classes, and obtained a fresh offline result of 725 tests with 0 failures, 0 errors, and 86 skips. Docker-backed verification and graphify remain unavailable; no functional defect was fixed.
<!-- /ANCHOR:change-log -->

## 16. OPEN QUESTIONS

- Which code-quality findings remain safe to remediate after complete path tracing?
- Which suspected functional defects should be approved as separate changes?
- Which package-structure option should be approved for a later migration?

## RELATED DOCUMENTS

- **Implementation Plan**: See `plan.md`
- **Task Breakdown**: See `tasks.md`
- **Verification Checklist**: See `checklist.md`
- **Decision Records**: See `decision-record.md`
<!-- /ANCHOR:questions -->
