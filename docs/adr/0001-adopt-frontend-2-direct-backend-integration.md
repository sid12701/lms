# Adopt frontend-2 as the frontend, integrated directly against the live backend

## Status

accepted

(Supersedes an earlier same-session draft of this ADR, which proposed a frozen-backend adapter layer with the mock layer retained behind a `VITE_USE_MOCKS` flag.)

## Context

The repo carried two SPA frontends: `frontend/` (the live app, wired to the Spring Boot backend) and `frontend-2/` (`bhawana-lms-frontend` — a far more mature UI: 10 shipped phases, dark mode, accessibility, a full component library). `frontend-2/` was built entirely against an *idealized* contract (`frontend-implementation-plan.md`) with all data mocked in-app via `src/mocks/`; it makes no real network calls, and its expected shapes diverge from the backend's. The backend is real, well-specified, and already publishes a machine-readable OpenAPI contract at `/v3/api-docs` (springdoc).

## Decision

Adopt `frontend-2/` as the canonical frontend and retire `frontend/`. Integrate `frontend-2/` **directly** against the live backend:

- **The backend is the single source of truth.** Its OpenAPI contract — not `frontend-2`'s invented mock contract — defines every shape.
- **Remove `frontend-2`'s in-app mock layer entirely** — `src/mocks/` (router, db, seed data, handlers, scenarios) and any `VITE_USE_MOCKS`-style flag. The app always calls the real backend.
- **Each `features/*/api.ts` is rewritten** to call real backend endpoints, typed against TypeScript generated from `/v3/api-docs` with `openapi-typescript` (committed, regeneratable). Translating a backend response into what a component needs is done **inside the api layer** and is routine work.
- **Structural changes are human-in-the-loop.** When wiring requires changing a shared frontend type or component, or changing a backend endpoint/DTO, that is escalated as an explicit HITL decision rather than made unilaterally. Routine api-layer rewrites are not.
- **Tests that depended on the in-app mock router migrate to MSW** (network-boundary interception) with backend-shaped fixtures. Component, hook, and schema tests are unaffected.
- Wiring proceeds feature-by-feature with `frontend/` kept as a reference until `frontend-2` is verified against a running backend; then `frontend/` is removed and `frontend-2` renamed to `frontend`.

## Considered Options

- **Adapter layer with the backend frozen** — a permanent translation layer, backend never touched. Rejected: it keeps `frontend-2`'s invented contract alive indefinitely and treats the backend as immovable even where a small backend change is the right fix.
- **Keep the mock layer behind a `VITE_USE_MOCKS` flag** — preserves an offline/demo mode. Rejected: the mock layer encodes the invented contract, which is the thing being retired; maintaining it dual-track is not worth an offline mode for an internal ops tool.
- **Direct integration, backend as source of truth** (chosen) — `frontend-2` bends to the real contract; the backend bends only by deliberate HITL decision.

## Consequences

- The app requires the backend to run — there is no offline/demo mode. Normal for an internal ops tool.
- ~18 test files that used the in-app mock router migrate to MSW; ~106 component/hook/schema tests and ~22 `vi.mock` page tests are unaffected.
- The backend *may* change, but only as discrete HITL decisions — it is neither frozen nor freely edited.
- Shared frontend types/components *may* change, also only via HITL.
- Backend-side types are generated from the OpenAPI spec, so backend contract drift surfaces as a TypeScript compile error.
- Until a feature is wired, its `frontend-2` screen is non-functional; `frontend/` remains the working app until the final swap.
