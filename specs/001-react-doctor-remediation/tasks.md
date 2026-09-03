---
title: "Tasks: React Doctor Remediation [template:level_3/tasks.md]"
description: "Execution checklist for investigating, fixing, and verifying the ten React Doctor findings."
trigger_phrases:
  - "React Doctor tasks"
  - "frontend remediation tasks"
importance_tier: "normal"
contextType: "implementation"
---
# Tasks: React Doctor Remediation

<!-- SPECKIT_LEVEL: 3 -->
<!-- SPECKIT_TEMPLATE_SOURCE: tasks-core | v2.2 -->

<!-- ANCHOR:notation -->
## Task Notation

- `[x]` completed
- `[ ]` pending
- P0/P1 indicate priority from `spec.md`

<!-- /ANCHOR:notation -->
<!-- ANCHOR:phase-1 -->
## Phase 1: Setup

- [x] P0 Read repository instructions and frontend architecture.
- [x] P0 Run baseline lint, typecheck, tests, build, and React Doctor.
- [x] P0 Capture the 10 verbose findings and affected call paths.
- [x] P0 Create the Level 3 spec packet.

<!-- /ANCHOR:phase-1 -->
<!-- ANCHOR:phase-2 -->
## Phase 2: Implementation

- [x] P1 Replace repeated visibility membership scans with `Set.has`.
- [x] P0 Replace the home entrance utility combination with explicit reduced-motion-aware animation styling.
- [x] P0 Create and revoke document preview URLs inside an effect.
- [x] P0 Move report download URL ownership into the download boundary and revoke it.
- [x] P0 Use TanStack Query for my-loans list state and retry behavior.
- [x] P1 Add or update focused regression tests.
- [x] P1 Move the blocked-activation handlers in `TransitionDisabledTooltip` to module scope after the follow-up scan.

<!-- /ANCHOR:phase-2 -->
<!-- ANCHOR:phase-3 -->
## Phase 3: Verification

- [x] P0 Run focused tests.
- [x] P0 Run `npm run lint`.
- [x] P0 Run `npm run typecheck`.
- [x] P0 Run `npm test`.
- [x] P0 Run `npm run build`.
- [x] P0 Run verbose React Doctor v0.9.4 and confirm 100/100.
- [x] P1 Attempt `graphify update .` and document availability.
- [x] P0 Review diff and complete the packet.

<!-- /ANCHOR:phase-3 -->
<!-- ANCHOR:completion -->
## Completion Criteria

- React Doctor reports 100/100 with no findings.
- No suppression, exclusion, dependency, or lockfile manipulation was used.
- All frontend gates pass.
- Final report identifies tested and untested runtime surfaces.

<!-- /ANCHOR:completion -->
<!-- ANCHOR:cross-refs -->
## Cross-References

- `spec.md`
- `plan.md`
- `checklist.md`
- `implementation-summary.md`
- `decision-record.md`

<!-- /ANCHOR:cross-refs -->
