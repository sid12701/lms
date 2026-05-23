# Frontend-2 ↔ Backend Integration Status

This document tracks how far `frontend-2/` has been migrated from its
in-process mock layer to the live Spring Boot backend at
`VITE_API_BASE_URL` (default `http://localhost:8080`).

Goal: every screen that has a backend counterpart should call it directly;
features without a backend counterpart stay on mocks until one ships.

## Wired to the live backend

| Surface | Backend endpoint(s) | Notes |
| --- | --- | --- |
| Login / sign-in form | `POST /api/v1/auth/login` | Real form at the top of `LoginPage`; dev-mode role preview lists the seeded backend accounts and prefills the username. |
| Forced password change | `POST /api/v1/auth/password` | Triggered when the backend sets `passwordChangeRequired: true`. |
| Silent refresh + 401 retry | `POST /api/v1/auth/refresh` (uses `lms-refresh` httpOnly cookie) | Registered into the HTTP transport — any `401` retries once after a successful refresh. |
| Logout | `POST /api/v1/auth/logout` | Clears both persisted session + mirrored mock session. |
| Session bootstrap | `GET /api/v1/internal/system/context` | Resolves username / roles / lspId from the active access token. |
| Home overview (SYSTEM_ADMIN) | `GET /api/v1/internal/home/overview` | Best-effort projection onto `InternalHomeKpis` — gaps default to 0 / []. |
| Loan-applications list (internal) | `GET /api/v1/internal/ops/loan-applications` | Page/pageSize translated to offset/limit. |
| Loan-application detail + read-only tabs | `GET /api/v1/internal/ops/loan-applications/{id}`, `/audit-events`, `/repayment-schedule`, `/kyc-documents`, `/payments` | Flat backend payload translated into the nested `LoanApplicationDetail` projection in the api layer. Borrower/LSP/product fill what's available; banking/aadhaar/references are empty pending issue #7. Webhooks tab filters `/admin/webhook-outbox` by `aggregateId` for SYSTEM_ADMIN and returns empty for OPS_USER. |
| Loan-application lifecycle write actions | `POST .../status-transitions`, `POST .../manual-status` (admin fallback), `POST .../disbursement-requests`, `POST .../payments` | Frontend status mapped to backend `LoanApplicationStatus`. SYSTEM_ADMIN automatically falls back to `manual-status` if the simple state machine on `status-transitions` rejects the target. Idempotency-Key forwarded as header on every mutation. Repayment posting maps `mode` → backend channel + uses idempotency key as the reference. |
| LSPs admin | `GET/POST/PUT /api/v1/internal/admin/lsps[…]` | Webhook event-type enum bridged backend↔frontend. |
| Products admin | `GET/POST/PUT /api/v1/internal/admin/products[…]` | List + create + update + mappings all live. |
| Users admin | `GET/POST /api/v1/internal/admin/users[…]` | List + create + reset-password live; update endpoint pending on backend. |
| API clients admin | `GET/POST /api/v1/internal/admin/api-clients` | Create returns one-shot clientSecret; rotate is local-only until backend ships. |
| Alerts inbox | `GET /api/v1/internal/alerts`, `POST .../acknowledge` | Ack-note is preserved client-side only. |
| Reports / Portfolio MIS | `GET /api/v1/internal/reports/portfolio-mis/{summary,preview}`, `POST .../requests`, `GET .../requests`, blob download | Field-name translation between backend + frontend MIS shapes. |
| LSP my-loans list | `GET /api/v1/lsp/loan-applications` | Replaces the Phase-6 placeholder; renders the LSP-scoped list. |

The HTTP transport lives at `frontend-2/src/lib/api/http-client.ts`. It
handles base URL, `Authorization: Bearer` injection, error-envelope
normalisation into `ApiError`, in-flight GET de-duplication, refresh-
on-401, and `Content-Disposition`-aware blob downloads.

## Still on the mock layer

These surfaces still rely on `frontend-2/src/mocks/api/*`. They render
real-looking data because the live login mirrors the session into the
mock db via `features/auth/mock-session-bridge.ts`.

- **Borrower 360 profile** + sub-tabs.
- **Borrower documents + audited PII reveal**.
- **Audit explorer** (the audit streams come from per-application
  endpoints; the unified explorer needs a backend aggregation that
  doesn't exist yet — explicit gap per issue #15).
- **LSP my-loans detail + write actions** (mark-invalid with the
  `/invalid-reasons` catalog, document upload, audited PII reveal).
  Scaffolded under issues #18 and #19.

## Known partial-integration gaps

- `GET /api/v1/internal/home/overview` returns six KPIs; the frontend
  `InternalHomeKpis` shape asks for more (`applicationsAwaitingApproval`,
  `applicationsInDisbursement`, `avgApprovalTatHours`,
  `applicationsByStatus`, `dpdBuckets`, `openAlerts`). Until those land
  on the backend, the adapter defaults them to `0` / `[]`.
- Home is restricted to `SYSTEM_ADMIN`; OPS_USER / PRODUCT_ADMIN /
  LSP roles still see the mock home.
- The Session shape requires a user UUID; the backend's
  `system/context` does not surface one, so we synthesise a stable
  UUID per browser via `crypto.randomUUID()` (persisted in
  localStorage).
- Webhook event types on the LSP admin endpoint are not 1:1: the
  frontend's `loan.disbursement.failed` and
  `loan.foreclosure.quote.generated` events collapse onto the closest
  backend enum value or are dropped on the read path.
- Users admin: backend has no PUT update endpoint — `updateUser`
  applies the change locally but cannot persist.
- API clients admin: backend has no update + no rotate-secret
  endpoint — those mutations stay local-only.
- Alerts: backend acknowledge endpoint does not accept a note payload.
- Reports: backend MIS preview row shape is wider than the frontend's
  `MisPreviewRow`; unused columns are dropped silently.
- Loan-applications list: backend status enum is a subset of the
  frontend's; values without a direct equivalent fold onto
  `INITIATED` / `DISBURSEMENT_IN_PROGRESS`.
- LSP_API_CLIENT logins are not exercised by the UI — that role is
  API-only.
- Loan-application detail Borrower projection is thin — full banking,
  references, address, and Aadhaar require `/internal/admin/borrowers/{id}`
  (wired in issue #7). Until then, the OverviewTab Aadhaar field renders
  as a masked empty value.
- Loan-application detail Webhooks tab projects from the SYSTEM_ADMIN
  outbox; OPS_USER sees an empty list because the outbox endpoint is
  admin-only. A per-application webhook endpoint would close this gap.
- Loan-application lifecycle: the backend status-transitions endpoint
  only allows INITIALIZED → AWAITING_APPROVAL → APPROVED_PENDING_DISBURSAL
  / REJECTED. SYSTEM_ADMIN gets an automatic fallback to `manual-status`
  on rejected transitions; OPS_USER does not — disallowed actions
  surface as the backend's 4xx error.
- Loan repayments: the backend assigns allocation server-side; the
  client does not pass an `installmentId`. Channel maps `BANK`/`NEFT`/
  `RTGS`/`IMPS` to backend `BANK_TRANSFER`. The frontend's idempotency
  key doubles as the backend `reference`.
- Loan-application assignment: not wired yet; backend endpoint exists
  (`POST .../assignment`) but the frontend has no UI for it.

## Running it locally

1. Start the local infra (Postgres / Redis / RabbitMQ / MinIO / MailHog):
   ```cmd
   docker compose -f infra/docker-compose.yml up -d
   ```
2. Start the backend:
   ```cmd
   local-start-backend.cmd
   ```
   Wait for `Started LmsApplication` in `backend-dev.log`. The OpenAPI
   spec is then available at `http://localhost:8080/v3/api-docs`.
3. Start the frontend:
   ```cmd
   cd frontend-2
   npm install
   npm run dev
   ```
4. Visit `http://localhost:5173/login`. Sign in with one of the seeded
   accounts (default bootstrap is `ops.admin` / `ChangeMe123!`). The
   dev-mode preview lists every seeded role.

## Roadmap

The remaining gaps map to issues #5–#8, #15, #16, #18, and #19 on
`sid12701/lms`. Each follow-up commit should land a single feature and
update the wired vs. mocked tables above.
