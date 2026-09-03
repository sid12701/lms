---
title: "Decision Record: React Doctor Remediation [template:level_3/decision-record.md]"
description: "Architecture decision for keeping disposable browser resource lifecycles at their owning boundaries."
trigger_phrases:
  - "decision record"
  - "object URL ownership"
  - "download lifecycle"
importance_tier: "normal"
contextType: "implementation"
---
# Decision Record: React Doctor Remediation

<!-- SPECKIT_LEVEL: 3 -->
<!-- SPECKIT_TEMPLATE_SOURCE: decision-record | v2.2 -->

<!-- ANCHOR:adr-001 -->
## ADR-001: Keep disposable browser resources at their owning boundary

### Metadata

- Status: accepted
- Date: 2026-08-01
- Scope: document preview and report download

<!-- ANCHOR:adr-001-context -->
### Context

The baseline created Blob-backed object URLs in a memoized render path and returned another URL from the reports API to the page. React Doctor correctly identified that render-time resource creation and cross-layer URL return make cleanup difficult to prove and easy to omit.

### Constraints

Authenticated bytes must continue to arrive through `requestBlob`; existing preview/download behavior and filenames must remain intact; no new dependency is justified.

<!-- /ANCHOR:adr-001-context -->
<!-- ANCHOR:adr-001-decision -->
### Decision

Create preview URLs in a `useEffect` and revoke the captured URL in that effect's cleanup. Make the report download API perform the anchor click and revoke its own URL, returning only completion rather than a disposable resource.

<!-- /ANCHOR:adr-001-decision -->
<!-- ANCHOR:adr-001-alternatives -->
### Alternatives Considered

- Return the URL and ask the page to revoke it: rejected because ownership remains split and callers can forget cleanup.
- Add a global URL registry: rejected because it adds shared state for a local lifecycle problem.
- Keep render-time `useMemo`: rejected because it creates external resources during render and is unsafe under interrupted/concurrent rendering.

<!-- /ANCHOR:adr-001-alternatives -->
<!-- ANCHOR:adr-001-consequences -->
### Consequences

Cleanup is explicit and local; the report page no longer handles Blob URLs; focused tests can assert lifecycle behavior. The API helper now owns a small DOM download operation, which is appropriate because it already owns the authenticated Blob response.

<!-- /ANCHOR:adr-001-consequences -->
<!-- ANCHOR:adr-001-five-checks -->
### Five Checks Evaluation

- Correctness: cleanup follows creation and error paths do not leak.
- Maintainability: lifecycle ownership is easy to locate.
- Performance: no retained Blob URLs and no repeated linear lookup.
- Accessibility: download remains a button-triggered native anchor download; reduced motion is respected.
- Operability: existing retry and loading states remain observable and testable.

<!-- /ANCHOR:adr-001-five-checks -->
<!-- ANCHOR:adr-001-impl -->
### Implementation

Apply the decision only to the reported paths, add regression tests, and rerun the complete frontend gate set plus verbose React Doctor.

<!-- /ANCHOR:adr-001-impl -->
<!-- /ANCHOR:adr-001 -->
