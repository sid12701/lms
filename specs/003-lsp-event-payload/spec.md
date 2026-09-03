---
title: "Record the LSP event payload decision"
description: "Record that the initial pull-feed contract uses one full, unmasked payload for every LSP while payload minimisation remains a pre-production review item."
trigger_phrases:
  - "LSP event payload"
  - "full unmasked payload"
importance_tier: "normal"
contextType: "implementation"
---
# Feature Specification: LSP Event Payload Decision

<!-- SPECKIT_LEVEL: 1 -->
<!-- SPECKIT_TEMPLATE_SOURCE: spec-core -->

<!-- ANCHOR:metadata -->
## 1. METADATA

| Field | Value |
|-------|-------|
| **Level** | 1 |
| **Status** | Complete |
| **Created** | 2026-08-17 |
<!-- /ANCHOR:metadata -->

<!-- ANCHOR:problem -->
## 2. PROBLEM & PURPOSE

ADR 0007 currently combines a full-payload default with optional per-LSP field configuration. The user has resolved the initial contract: every LSP receives the same full, unmasked payload for now. The ADR must state that choice directly without implying that per-LSP payload variants will be built.
<!-- /ANCHOR:problem -->

<!-- ANCHOR:scope -->
## 3. SCOPE

- Update only the payload decision and its recorded consequence in ADR 0007.
- Keep payload minimisation as an explicit pre-production review item.
- Do not change application code, schemas, endpoints, or `CONTEXT.md`.
<!-- /ANCHOR:scope -->

<!-- ANCHOR:requirements -->
## 4. REQUIREMENTS

- **REQ-001:** ADR 0007 must state that the initial feed contains full, unmasked loan and borrower data.
- **REQ-002:** ADR 0007 must state that all LSPs receive one common payload schema, with no initial per-LSP field configuration.
- **REQ-003:** ADR 0007 must preserve the warning that later pruning cannot retract data already distributed.
<!-- /ANCHOR:requirements -->

<!-- ANCHOR:success-criteria -->
## 5. SUCCESS CRITERIA

- **Given** a reader reviews the event payload consequence, **When** they interpret the initial API contract, **Then** they understand that it is full and unmasked for every LSP.
- **Given** payload minimisation is revisited before production, **When** the contract changes, **Then** the ADR makes clear that it is a future versioned contract decision rather than existing per-LSP configuration.
<!-- /ANCHOR:success-criteria -->

<!-- ANCHOR:risks -->
## 6. RISKS & DEPENDENCIES

The decision deliberately increases the sensitivity of the retained feed. Payload minimisation and compliance review remain required before production, and later pruning will not recall data already delivered to an LSP.
<!-- /ANCHOR:risks -->

<!-- ANCHOR:questions -->
## 7. OPEN QUESTIONS

None for this documentation change.
<!-- /ANCHOR:questions -->
