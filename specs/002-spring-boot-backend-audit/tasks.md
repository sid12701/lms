---
title: "Tasks: Fresh Spring Boot Backend Quality Audit"
description: "Track the evidence, bounded remediation, verification, and package-structure recommendation for the backend audit."
trigger_phrases:
  - "backend audit tasks"
  - "quality remediation tasks"
importance_tier: "important"
contextType: "implementation"
_memory:
  continuity:
    packet_pointer: "specs/002-spring-boot-backend-audit"
    last_updated_at: "2026-08-01T17:01:14Z"
    last_updated_by: "codex"
    recent_action: "Completed audit tasks, safe remediation, and verification"
    next_safe_action: "Review evidence and approve or defer higher-risk recommendations"
    blockers: ["graphify unavailable", "Docker/Testcontainers unavailable in the current sandbox"]
    key_files: ["plan.md", "checklist.md"]
    session_dedup:
      fingerprint: "sha256:0000000000000000000000000000000000000000000000000000000000000000"
      session_id: "codex-2026-08-01-backend-audit"
      parent_session_id: null
    completion_pct: 100
    open_questions: ["Which higher-risk recommendations should receive separate approval and implementation packets?"]
    answered_questions: ["New fresh-audit packet selected"]
---
# Tasks: Fresh Spring Boot Backend Quality Audit

<!-- SPECKIT_LEVEL: 3+ -->
<!-- SPECKIT_TEMPLATE_SOURCE: tasks-core + level3-arch + level3-plus-govern -->

<!-- ANCHOR:notation -->
## Task Notation

| Prefix | Meaning |
|--------|---------|
| `[ ]` | Pending |
| `[x]` | Completed |
| `[P]` | Parallelizable |
| `[B]` | Blocked |

**Task Format**: `T### [P?] Description (file path)`
<!-- /ANCHOR:notation -->

<!-- ANCHOR:phase-1 -->
## Phase 1: Setup

- [x] T001 Create the Level 3+ audit packet (`specs/002-spring-boot-backend-audit/`)
- [x] T002 Record repository instructions, current branch, and dirty worktree (`AGENTS.md`, `git status`)
- [x] T003 Confirm the backend stack manually after detector failure (`backend/pom.xml`, `backend/mvnw`)
- [B] T004 Refresh or definitively classify graphify availability (`graphify-out/`, `graphify update .`); executable and report are absent
- [x] T005 Run baseline Maven build, tests, and configured checks (`backend/mvnw`)
<!-- /ANCHOR:phase-1 -->

<!-- ANCHOR:phase-2 -->
## Phase 2: Implementation

- [x] T006 Trace application entry points and package relationships (`backend/src/main/java`)
- [x] T007 Review persistence, transactions, locking, migrations, and query paths (`backend/src/main/java`, `backend/src/main/resources/db`)
- [x] T008 Review security, authorization, tenant isolation, input validation, PII, and secret handling (`backend/src/main/java`)
- [x] T009 Review integrations, schedulers, workers, retries, idempotency, and resource lifecycle (`backend/src/main/java`)
- [x] T010 Apply Thermo-Nuclear review to complexity, abstractions, duplication, giant classes, and spaghetti growth (`backend/src/main/java`)
- [x] T011 Classify each candidate as confirmed quality issue, suspected defect, false positive, acceptable pattern, or limitation (`specs/002-spring-boot-backend-audit`)
- [x] T012 Determine focused behavior-protection tests for all bounded cleanups (`GlobalExceptionHandlerDataIntegrityTest`, `AuditExplorerControllerTest`, `LspLoanDocumentUploadIdempotencyIntegrationTest`, `WebhookOutboxSoftFourxxAndRedriveTest`, `R2RegionAndStoreProbeTest`)
- [x] T013 Implement only approved behavior-preserving cleanup: retain the two earlier mechanical edits, remove ineffective self-invocation transaction annotations, centralize webhook test cleanup, and require explicit opt-in for live R2 probing (five named backend files)
<!-- /ANCHOR:phase-2 -->

<!-- ANCHOR:phase-3 -->
## Phase 3: Verification

- [x] T014 Run focused verification for all bounded edits: the prior 27-test set passed; the new document-upload, webhook, and R2 sets passed with the live probe intentionally skipped by default (`backend/mvnw`)
- [x] T015 Run the fresh full available backend verification ladder: 725 tests, 0 failures, 0 errors, 86 skips (`backend/mvnw` with the cached Byte Buddy agent); Docker-backed coverage remained unavailable
- [x] T016 Review packet-owned diff for functional, configuration, generated, and package changes (`git diff`)
- [x] T017 Research package-by-feature, package-by-layer, modular monolith, and vertical-slice options using authoritative sources (`specs/002-spring-boot-backend-audit`)
- [x] T018 Write final findings, suspected defects, rejected signals, limitations, and recommendation (`specs/002-spring-boot-backend-audit`)
- [B] T019 Refresh graphify after code changes (`graphify update .`); exact result: `zsh:1: command not found: graphify`, and `graphify-out/GRAPH_REPORT.md` is absent
<!-- /ANCHOR:phase-3 -->

<!-- ANCHOR:completion -->
## Completion Criteria

- [x] All P0 tasks and checklist items have evidence.
- [x] No blocked task remains without an explicit environment limitation or user-approved deferral.
- [x] The final report does not claim functional fixes.
- [x] Implementation summary records actual changes and exact verification outcomes.
<!-- /ANCHOR:completion -->

<!-- ANCHOR:architecture-tasks -->
## Architecture Tasks

- [x] T020 Document current package coupling and high-fan-in areas without moving packages.
- [x] T021 Compare package-by-layer and package-by-feature options against current domain count, integration count, tests, and team maintenance needs.
- [x] T022 Document migration sequencing, dependency-direction risks, and rollback requirements for later approval.
<!-- /ANCHOR:architecture-tasks -->

<!-- ANCHOR:governance-tasks -->
## Governance Tasks

- [x] T023 Keep suspected defects in a separate discussion-only register.
- [x] T024 Keep user approval checkpoints explicit for package restructuring and any behavior-affecting recommendation.
- [B] T025 Validate the spec packet strictly before completion claims; packet checks pass but the shared validator exits 2 because required validator modules/runtime are missing
<!-- /ANCHOR:governance-tasks -->

<!-- ANCHOR:cross-refs -->
## Cross-References

- **Specification**: See `spec.md`.
- **Plan**: See `plan.md`.
- **Verification**: See `checklist.md`.
- **Decisions**: See `decision-record.md`.
<!-- /ANCHOR:cross-refs -->
