---
title: "Feature Specification: React Doctor Remediation [template:level_3/spec.md]"
description: "Remediate the ten React Doctor findings in the frontend without suppressions or behavior regressions."
trigger_phrases:
  - "React Doctor"
  - "frontend remediation"
  - "object URL lifecycle"
importance_tier: "normal"
contextType: "implementation"
---
# Feature Specification: React Doctor Remediation

<!-- SPECKIT_LEVEL: 3 -->
<!-- SPECKIT_TEMPLATE_SOURCE: spec-core + level2-verify + level3-arch | v2.2 -->

## EXECUTIVE SUMMARY

Raise the frontend's genuine React Doctor score from the measured 88/100 baseline to 100/100 by correcting the ten reported issues without suppressions, exclusions, dependency churn, or behavioral regressions.

<!-- ANCHOR:metadata -->
## 1. METADATA

- Status: complete
- Level: 3
- Scope: `frontend/` React/Vite application
- Baseline: React Doctor 0.9.4, 88/100, 10 findings
- Validation baseline: React Doctor 0.9.4 reported 10 findings across six frontend modules.
- Final validation: React Doctor 0.9.4 scored 100/100 with no issues; `npm run verify` passed with 158 test files / 1,024 tests.

<!-- /ANCHOR:metadata -->
<!-- ANCHOR:problem -->
## 2. PROBLEM & PURPOSE

### Problem Statement

React Doctor identified resource-lifecycle bugs, an async loading-state risk, an avoidable array scan, and an ambiguous animation configuration. Two existing blob URL paths can retain browser-managed memory; the loading implementation is harder to reason about than the repository's established TanStack Query pattern.

### Purpose

Correct each finding at its ownership boundary, preserve user workflows and accessibility, and leave an auditable record of baseline, decisions, tests, and remaining environmental limitations.

<!-- /ANCHOR:problem -->
<!-- ANCHOR:scope -->
## 3. SCOPE

### In Scope

- `use-column-visibility` lookup implementation and focused test coverage.
- Home entrance animation styling.
- Document preview object URL creation and cleanup.
- Report download lifecycle and its page/hook contract.
- My-loans list loading/data ownership and focused test coverage.
- Audit URL parsing and users-page dialog orchestration.
- React Doctor, lint, typecheck, tests, build, graphify attempt, and final diff review.

### Out of Scope

- Unrelated pre-existing worktree changes.
- React upgrades, dependency upgrades, scanner configuration, rule suppression, and backend changes.
- Broad visual redesign or unrelated warning cleanup.

### Files to Change

- `frontend/src/lib/use-column-visibility.ts`
- `frontend/src/features/home/page.tsx`
- `frontend/src/styles/globals.css`
- `frontend/src/components/app/documents/DocumentPreviewModal.tsx`
- `frontend/src/components/app/documents/DocumentPreviewModal.test.tsx`
- `frontend/src/features/reports/api.ts`
- `frontend/src/features/reports/hooks/useDownloadReportRequest.ts`
- `frontend/src/features/reports/page.tsx`
- `frontend/src/features/reports/api.test.ts`
- `frontend/src/features/my-loans/page.tsx`
- focused tests as required by implementation

### Final Finding Map

| Baseline finding | Root-cause correction | Evidence |
|---|---|---|
| `no-derived-useState` | Applied-filter chips use the current animation prop directly instead of copying it into state. | Final React Doctor 100/100 |
| `rerender-memo-with-default-value` ×4 | Optional filter arrays use module-level stable empty defaults. | Final React Doctor 100/100 |
| `deslop/unused-export` ×3 | Removed dead audit exports and an unused date-picker helper. | Typecheck/lint; final React Doctor 100/100 |
| `no-giant-component` | Moved users dialog state transitions into a controller hook and dialog layer. | `features/users/page.test.tsx`; final React Doctor 100/100 |
| `js-combine-iterations` | Stream query parsing now uses one explicit pass. | `features/audit/url-filters.test.ts`; final React Doctor 100/100 |

<!-- /ANCHOR:scope -->
<!-- ANCHOR:requirements -->
## 4. REQUIREMENTS

### P0 - Blockers (MUST complete)

- Every baseline React Doctor finding must be investigated and either corrected with evidence or documented as an unavoidable scanner defect.
- No scanner configuration changes, exclusions, lint suppressions, unsafe assertions, or `any` additions.
- Blob URLs must be created outside render and revoked by the same owner after use.
- My-loans loading must settle on success and failure without stale-request races.
- Existing frontend gates must remain passing.

### P1 - Required (complete OR user-approved deferral)

- Add focused tests for changed resource and data-flow behavior.
- Keep report download semantics as a user-triggered download with server-provided filename when available.
- Keep home animation accessible under reduced-motion preferences.

<!-- /ANCHOR:requirements -->
<!-- ANCHOR:success-criteria -->
## 5. SUCCESS CRITERIA

- React Doctor reports exactly 100/100 over the same `frontend/` scope with no unresolved findings.
- `npm run lint`, `npm run typecheck`, `npm test`, and `npm run build` pass.
- Changed behavior is covered by focused tests and the complete diff contains no unrelated remediation.

<!-- /ANCHOR:success-criteria -->
<!-- ANCHOR:risks -->
## 6. RISKS & DEPENDENCIES

- React Doctor and graphify require local tooling/network availability; failures must be reported rather than hidden.
- Blob download timing differs across browsers, so the implementation must use the repository's established anchor-click and revoke pattern.
- TanStack Query adoption changes state ownership in `MyLoansPage`; list pagination, retry, error, and empty states require focused verification.

<!-- /ANCHOR:risks -->
<!-- ANCHOR:questions -->
## 7. NON-FUNCTIONAL REQUIREMENTS

### Performance

Avoid repeated linear membership scans and avoid retaining Blob-backed object URLs.

### Security

Continue fetching protected bytes through the authenticated API client; never expose storage URLs or credentials to rendered markup.

### Reliability

Loading and download states must settle on rejection as well as success, and request changes must not overwrite newer page state.

## 8. EDGE CASES

### Data Boundaries

- Empty, malformed, or unknown persisted column visibility values.
- Missing report filename metadata.
- Preview blob replacement and modal unmount.
- Pagination changes while a previous my-loans request is unresolved.

### Error Scenarios

- Blob fetch rejection.
- Report download rejection.
- My-loans list rejection and retry.

## 9. COMPLEXITY ASSESSMENT

Moderate: five source areas, two lifecycle corrections, one state-ownership refactor, and validation across the full frontend gate set.

## 10. RISK MATRIX

| Risk | Likelihood | Impact | Mitigation |
|---|---:|---:|---|
| Blob download regression | Low | High | Preserve anchor semantics and add API test |
| Pagination race | Low | High | Let TanStack Query own keyed requests |
| Scanner false positive | Medium | Medium | Verify source rule behavior and rerun verbose scan |
| Environment blocker | Medium | Medium | Record exact command and fallback evidence |

## 11. USER STORIES

### US-001: Safe document preview (Priority: P0)

As an operator, I can preview a protected document without leaking or retaining its Blob URL after the preview changes or closes.

### US-002: Reliable LSP list and report download (Priority: P0)

As an LSP operator, I see correct loading/error states while paging loans and can download a completed report without a retained object URL.

## 12. OPEN QUESTIONS

- Runtime browser verification requires the backend and seeded data; local unit/build gates are available, but live workflow verification may remain environment-dependent.

<!-- /ANCHOR:questions -->
## RELATED DOCUMENTS

- `AGENTS.md`
- `README.md`
- `frontend/package.json`
- React Doctor verbose baseline captured during this task
