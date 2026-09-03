---
title: "Implementation Plan: LSP Event Payload Decision"
description: "Apply the resolved full, unmasked payload decision to ADR 0007 without changing runtime behavior."
trigger_phrases:
  - "LSP payload plan"
importance_tier: "normal"
contextType: "implementation"
---
# Implementation Plan: LSP Event Payload Decision

<!-- SPECKIT_LEVEL: 1 -->
<!-- SPECKIT_TEMPLATE_SOURCE: plan-core -->

<!-- ANCHOR:summary -->
## 1. SUMMARY

Replace the ADR's configurable-payload language with one explicit initial contract: every LSP receives the same full, unmasked loan and borrower payload.
<!-- /ANCHOR:summary -->

<!-- ANCHOR:quality-gates -->
## 2. QUALITY GATES

- Preserve the user's decision exactly.
- Do not imply that per-LSP payload variants exist.
- Retain the pre-production minimisation warning.
<!-- /ANCHOR:quality-gates -->

<!-- ANCHOR:architecture -->
## 3. ARCHITECTURE

This is a contract-documentation change only. The pull-feed architecture and all other ADR decisions remain unchanged.
<!-- /ANCHOR:architecture -->

<!-- ANCHOR:phases -->
## 4. IMPLEMENTATION PHASES

1. Update the payload consequence in ADR 0007.
2. Update the open minimisation item so it no longer relies on per-LSP configuration.
3. Validate the ADR and this packet.
<!-- /ANCHOR:phases -->

<!-- ANCHOR:testing -->
## 5. TESTING STRATEGY

Review the resulting diff and run Markdown/spec validation. No application tests are required because runtime files are untouched.
<!-- /ANCHOR:testing -->

<!-- ANCHOR:dependencies -->
## 6. DEPENDENCIES

The decision depends only on the user's explicit approval of full, unmasked payloads for the initial contract.
<!-- /ANCHOR:dependencies -->

<!-- ANCHOR:rollback -->
## 7. ROLLBACK PLAN

Revert only this packet's two ADR sentences if the user changes the payload decision before implementation.
<!-- /ANCHOR:rollback -->
