# Frontend-2 ↔ Backend Integration Status

This document tracks how far `frontend-2/` has been migrated from its
in-process mock layer to the live Spring Boot backend at
`VITE_API_BASE_URL` (default `http://localhost:8080`).

Goal: every screen that has a backend counterpart should call it directly;
features without a backend counterpart stay on mocks until one ships.

## Wired to the live backend

| Surface | Backend endpoint(s) | Notes |
| --- | --- | --- |
| Login / sign-in form | `POST /api/v1/auth/login` | Real form lives at the top of `LoginPage`; dev-mode role preview lists the seeded backend accounts and prefills the username field. |
| Forced password change | `POST /api/v1/auth/password` | Triggered when the backend sets `passwordChangeRequired: true`. |
| Silent refresh + 401 retry | `POST /api/v1/auth/refresh` (uses `lms-refresh` httpOnly cookie) | Registered into the HTTP transport — any `401` retries once after a successful refresh. |
| Logout | `POST /api/v1/auth/logout` | Clears both persisted session + mirrored mock session. |
| Session bootstrap | `GET /api/v1/internal/system/context` | Resolves username / roles / lspId from the active access token. |
| Home overview (SYSTEM_ADMIN) | `GET /api/v1/internal/home/overview` | Best-effort projection onto `InternalHomeKpis` — see *Gaps* below. |

The HTTP transport lives at `frontend-2/src/lib/api/http-client.ts`. It
handles base URL, `Authorization: Bearer` injection, error-envelope
normalisation into `ApiError`, in-flight GET de-duplication, and refresh-
on-401.

## Not yet wired — falls back to mock data

These surfaces still rely on `frontend-2/src/mocks/api/*`. They render
real-looking data because the live login mirrors the session into the
mock db via `features/auth/mock-session-bridge.ts`.

- **Loan applications** list, detail, lifecycle write actions
- **Borrowers** profile, documents, audited PII reveal
- **Products** admin catalogue + create form
- **LSPs** admin registry + webhook subscriptions
- **Users** admin
- **API clients** admin (incl. one-time secret reveal)
- **Alerts** inbox
- **Reports / MIS** previews + history
- **Audit** explorer
- **LSP self-service workspace** (`/my-loans`)
- **Home** for OPS_USER / PRODUCT_ADMIN / LSP roles
  *(SYSTEM_ADMIN home is wired — see above.)*

Each of those features has a corresponding GitHub issue under
`sid12701/lms` (#3 – #19) describing the contract delta.

## Gaps when calling the real backend today

- `GET /api/v1/internal/home/overview` returns six KPIs
  (`totalDisbursed`, `totalOutstanding`, `dpd90PlusAmount`,
  `dpd90PlusLoanCount`, `lspBreakdown`, `priorityAccounts`). The
  frontend's `InternalHomeKpis` projection asks for additional values
  (`applicationsAwaitingApproval`, `applicationsInDisbursement`,
  `avgApprovalTatHours`, `applicationsByStatus`, `dpdBuckets`,
  `openAlerts`). Until those land in the backend the adapter defaults
  them to `0` / `[]`.
- The backend currently restricts `/internal/home/overview` to
  `SYSTEM_ADMIN`. OPS_USER and PRODUCT_ADMIN sessions remain on the mock.
- The session shape requires a user UUID; the backend's
  `system/context` does not surface one, so we synthesise a stable UUID
  per browser via `crypto.randomUUID()` (persisted in localStorage).
- LSP_API_CLIENT logins are not exercised in the UI — that role is API-only.

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

Wiring proceeds feature-by-feature in line with GitHub issues #3 – #19.
Each feature lands in its own commit and updates this table.
