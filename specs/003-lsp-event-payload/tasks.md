---
title: "Tasks: LSP Event Payload Decision"
description: "Track the narrow ADR update for the resolved LSP event payload contract."
trigger_phrases:
  - "LSP payload tasks"
importance_tier: "normal"
contextType: "implementation"
---
# Tasks: LSP Event Payload Decision

<!-- SPECKIT_LEVEL: 1 -->
<!-- SPECKIT_TEMPLATE_SOURCE: tasks-core -->

<!-- ANCHOR:notation -->
## Task Notation

`[x]` means completed; `[ ]` means pending.
<!-- /ANCHOR:notation -->

<!-- ANCHOR:phase-1 -->
## Phase 1: Setup

- [x] T001 Confirm the user's payload decision and inspect ADR 0007.
- [x] T002 Confirm that no related active lifecycle-feed spec packet exists.
<!-- /ANCHOR:phase-1 -->

<!-- ANCHOR:phase-2 -->
## Phase 2: Implementation

- [x] T003 Replace per-LSP field-configuration language with one full, unmasked schema.
- [x] T004 Preserve payload minimisation as a pre-production review item.
<!-- /ANCHOR:phase-2 -->

<!-- ANCHOR:phase-3 -->
## Phase 3: Verification

- [x] T005 Review the final diff.
- [x] T006 Run Markdown and strict spec validation; packet-owned structural checks pass, while the shared validator reports missing installed runtime modules.
<!-- /ANCHOR:phase-3 -->

<!-- ANCHOR:completion -->
## Completion Criteria

- [x] ADR wording matches the user's decision without ambiguity.
- [x] No runtime files are changed.
- [x] Validation results are recorded.
<!-- /ANCHOR:completion -->

<!-- ANCHOR:cross-refs -->
## Cross-References

- Specification: `spec.md`
- Plan: `plan.md`
- Decision: `docs/adr/0007-partner-lifecycle-updates-are-pull-based.md`
<!-- /ANCHOR:cross-refs -->
