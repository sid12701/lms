---
title: "Implementation Plan: React Doctor Remediation [template:level_3/plan.md]"
description: "Focused implementation and verification plan for the React Doctor remediation packet."
trigger_phrases:
  - "implementation plan"
  - "React Doctor fixes"
  - "frontend verification"
importance_tier: "normal"
contextType: "implementation"
---
# Implementation Plan: React Doctor Remediation

<!-- SPECKIT_LEVEL: 3 -->
<!-- SPECKIT_TEMPLATE_SOURCE: plan-core + level2-verify + level3-arch | v2.2 -->

<!-- ANCHOR:summary -->
## 1. SUMMARY

### Technical Context

The application is a React 19 + Vite + TypeScript SPA using TanStack Query, Vitest, Tailwind CSS 4, and shadcn-style components. The baseline is already type-safe and test/build clean, so the plan is a narrow correctness/performance remediation.

### Overview

1. Record and classify all 10 findings.
2. Apply lifecycle, state, lookup, animation, and download fixes in focused batches.
3. Add regression tests for changed behavior.
4. Run full gates and React Doctor until the score is verified.

<!-- /ANCHOR:summary -->
<!-- ANCHOR:quality-gates -->
## 2. QUALITY GATES

### Definition of Ready

- Baseline commands completed.
- Verbose findings mapped to files and call paths.
- Existing worktree changes identified and preserved.

### Definition of Done

- React Doctor 100/100 with no findings.
- Lint, typecheck, tests, and build pass.
- Focused tests cover object URL cleanup, report download lifecycle, persisted visibility, and my-loans query states.
- Final diff reviewed and documented.

<!-- /ANCHOR:quality-gates -->
<!-- ANCHOR:architecture -->
## 3. ARCHITECTURE

### Pattern

Keep resource ownership close to the operation that creates the resource. Use effects for external Blob URL synchronization, event/API boundaries for downloads, and TanStack Query for keyed server state.

### Key Components

- `DocumentPreviewModal`: preview Blob URL lifecycle.
- `reports/api.ts`: authenticated report download and URL ownership.
- `MyLoansPage`: query-driven list state.
- `use-column-visibility`: persisted preference parsing.
- `globals.css`: scoped, reduced-motion-aware home card animation.

### Data Flow

Authenticated bytes enter through `requestBlob`; preview creates/revokes its own URL in an effect. Report download creates an anchor URL at the download boundary and revokes it after click. My-loans page state derives from a query key of page and page size.

<!-- /ANCHOR:architecture -->
<!-- ANCHOR:affected-surfaces -->
## FIX ADDENDUM: AFFECTED SURFACES

| Finding family | Surface | Correction |
|---|---|---|
| Set lookup | persisted table preferences | `Set.has` |
| Transition all | home cards | scoped keyframe animation |
| Object URL in render | document preview | effect-owned URL |
| Object URL without revoke | report download | API-owned anchor + revoke |
| Loading reset | my-loans list | TanStack Query state |

<!-- /ANCHOR:affected-surfaces -->
<!-- ANCHOR:phases -->
## 4. IMPLEMENTATION PHASES

### Phase 1: Setup

- Preserve dirty worktree state.
- Create this spec packet.
- Capture baseline and verbose React Doctor report.

### Phase 2: Core Implementation

- Correct lookup and animation.
- Move document preview URL creation to an effect.
- Own report download URL lifecycle at the API boundary.
- Replace manual my-loans fetch/loading state with a keyed query.
- Add focused regression tests.

### Phase 3: Verification

- Run focused tests after each group.
- Run lint, typecheck, full tests, build, and React Doctor.
- Run graphify update attempt and record tool availability.
- Review final diff and update the summary/checklist.

<!-- /ANCHOR:phases -->
<!-- ANCHOR:testing -->
## 5. TESTING STRATEGY

- Unit test persisted column parsing behavior through the hook consumer or focused hook harness.
- Assert preview URL creation occurs after data and revocation occurs on unmount/replacement.
- Assert report download uses the authenticated endpoint, anchor filename, click, and revoke.
- Assert my-loans success, error/retry, empty, and pagination rendering through a QueryClient wrapper.
- Run the existing 846-test suite plus production build.

<!-- /ANCHOR:testing -->
<!-- ANCHOR:dependencies -->
## 6. DEPENDENCIES

- Existing `frontend/node_modules` and npm lockfile.
- Network-enabled `npx react-doctor@latest . --verbose` for baseline/final score.
- No new runtime or development dependencies.

<!-- /ANCHOR:dependencies -->
<!-- ANCHOR:rollback -->
## 7. ROLLBACK PLAN

Revert only the files listed in the spec packet if a focused test or runtime check reveals a regression. Do not revert unrelated pre-existing worktree changes. The old code path remains recoverable from Git history.

<!-- /ANCHOR:rollback -->
<!-- ANCHOR:phase-deps -->
## L2: PHASE DEPENDENCIES

Verification depends on completing the lifecycle fixes first; React Doctor results are not meaningful until all focused source changes are present.

<!-- /ANCHOR:phase-deps -->
<!-- ANCHOR:effort -->
## L2: EFFORT ESTIMATION

| Workstream | Estimate |
|---|---:|
| Audit and classification | 1 hour |
| Source fixes | 2 hours |
| Focused tests | 1 hour |
| Full verification and review | 1 hour |

<!-- /ANCHOR:effort -->
<!-- ANCHOR:enhanced-rollback -->
## L2: ENHANCED ROLLBACK

### Pre-deployment Checklist

- React Doctor is 100/100.
- No suppressions or exclusions were added.
- Full frontend gates pass.

### Rollback Procedure

Revert the remediation files only, rerun the baseline gates, and retain this packet as the audit record.

### Data Reversal

No persistent application data or backend schema is changed.

<!-- /ANCHOR:enhanced-rollback -->
<!-- ANCHOR:dependency-graph -->
## L3: DEPENDENCY GRAPH

### Dependency Matrix

| Consumer | Owner | External dependency |
|---|---|---|
| Preview modal | `DocumentPreviewModal` | `requestBlob`, browser URL API |
| Report page | `reports/api.ts` | `requestBlob`, browser download API |
| My-loans page | `MyLoansPage` | TanStack Query, LSP API |

<!-- /ANCHOR:dependency-graph -->
<!-- ANCHOR:critical-path -->
## L3: CRITICAL PATH

Baseline → classify → fix lifecycle ownership → fix list state → focused tests → full validation → final React Doctor.

<!-- /ANCHOR:critical-path -->
<!-- ANCHOR:milestones -->
## L3: MILESTONES

- M1: baseline captured at 88/100 with 10 findings.
- M2: all source findings corrected.
- M3: focused tests pass.
- M4: final score and gates recorded.

<!-- /ANCHOR:milestones -->
## L3: ARCHITECTURE DECISION RECORD

### ADR-001: Keep resource lifecycle at the owning boundary

The creator of each Blob URL owns revocation. Preview URLs are effect-owned; downloads are API/event-owned. This keeps cleanup adjacent to creation and prevents page components from passing disposable resources across abstraction boundaries.
