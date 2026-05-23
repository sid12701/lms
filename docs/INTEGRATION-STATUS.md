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
| Borrower 360 profile + Loans tab | `GET /api/v1/internal/admin/borrowers/{id}` | Backend returns borrower master + loans projection in one envelope. Aadhaar / bank account masked server-side. Loans tab reuses the same payload (no second request). Visible LSPs derived from the loans collection's lspId/lspName join. |
| Audit explorer (SYSTEM_ADMIN) | `GET /api/v1/internal/ops/loan-applications` + per-app `/audit-events` | Composed client-side from the most recent 50 loan applications. Only the APPLICATION stream is surfaced today; INTAKE / PII_REVEAL / DOCUMENT_ACCESS / PRODUCT streams require additional fan-out and are deferred until a backend unified endpoint ships. Filters operate over the composition window only. |
| LSPs admin | `GET/POST/PUT /api/v1/internal/admin/lsps[…]` | Webhook event-type enum bridged backend↔frontend. |
| Products admin | `GET/POST/PUT /api/v1/internal/admin/products[…]` | List + create + update + mappings all live. |
| Users admin | `GET/POST /api/v1/internal/admin/users[…]` | List + create + reset-password live; update endpoint pending on backend. |
| API clients admin | `GET/POST /api/v1/internal/admin/api-clients` | Create returns one-shot clientSecret; rotate is local-only until backend ships. |
| Alerts inbox | `GET /api/v1/internal/alerts`, `POST .../acknowledge` | Ack-note is preserved client-side only. |
| Reports / Portfolio MIS | `GET /api/v1/internal/reports/portfolio-mis/{summary,preview}`, `POST .../requests`, `GET .../requests`, blob download | Field-name translation between backend + frontend MIS shapes. |
| LSP my-loans list | `GET /api/v1/lsp/loan-applications` | Replaces the Phase-6 placeholder; renders the LSP-scoped list. |
| LSP my-loan detail + write actions | `GET /api/v1/lsp/loan-applications/{id}`, `/invalid-reasons`, `POST .../{id}/invalid`, `GET .../{id}/borrower-pii` | Renders the LSP-scoped loan detail (terms, contact, loan account). Mark-invalid posts to the audited LSP endpoint with an Idempotency-Key header; `reasonText` is only forwarded when the catalog row says `requiresText`. The PII reveal card calls the audited `/borrower-pii` endpoint — every reveal is recorded server-side. |
| LSP document upload (per-row + batch) | `POST .../{id}/documents` (single), `POST .../{id}/documents/batch` (batch) | Multipart upload from the my-loan detail page. The checklist is a best-effort projection over the standard document types because the LSP API does not yet expose a GET-documents endpoint; uploaded rows are tracked in component state for the duration of the session. Uploads are disabled in terminal states. |

The HTTP transport lives at `frontend-2/src/lib/api/http-client.ts`. It
handles base URL, `Authorization: Bearer` injection, error-envelope
normalisation into `ApiError`, in-flight GET de-duplication, refresh-
on-401, and `Content-Disposition`-aware blob downloads.

## Still on the mock layer

These surfaces still rely on `frontend-2/src/mocks/api/*`. They render
real-looking data because the live login mirrors the session into the
mock db via `features/auth/mock-session-bridge.ts`.

- **Borrower 360 Activity tab** — no borrower-scoped audit endpoint
  exists on the backend (audit streams are per-application). Stays on
  the mock router until a backend aggregation lands.
- **Audited PII reveal (internal ops)** — backend has no internal
  endpoint; reveal flows through the mock router so no audit row hits
  the database on ops reveals. Tracked in issue #8.
- **Document checklist write surface (internal)** — backend
  `PUT .../kyc-documents/{type}` is wired in `updateDocumentChecklistItem`
  but the DocumentsTab UI is still read-only (`canManage: false`). A
  manage-enabled variant would consume the helper as-is.
- _Nothing currently — see the gaps below for limitations on shipped
  surfaces._

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
- Borrower 360 audited PII reveal: the backend exposes an audited PII
  reveal endpoint only on the LSP path
  (`/api/v1/lsp/loan-applications/{id}/borrower-pii`). Internal
  borrower reveal flows through the mock router — no audit row hits
  the database. Backend needs an internal admin equivalent (issue #8).
- Borrower 360 `activeOverdueAmount` tile is hardcoded to 0; the
  borrower admin endpoint does not surface DPD aggregates.
- Audit explorer client-side composition is limited to the
  APPLICATION stream and the most recent 50 applications (per the #15
  decision). For complete cross-domain audit search, a backend unified
  endpoint is still needed.
- LSP document checklist is built locally over the standard document
  types because the LSP API exposes no GET endpoint for previously
  uploaded documents. After a page reload the checklist resets to its
  empty state until the user uploads again. A `GET .../{id}/documents`
  endpoint on the backend would close this gap.

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
