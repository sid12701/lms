# LMS Master QA & End-to-End Testing Plan

**Document:** `docs/test-document-2.md`  
**Version:** 1.0 (discussion draft)  
**Date:** 2026-05-29  
**Status:** Planning only — **no test implementation started**  
**Audience:** Engineering, QA, product, and security reviewers  

---

## Implementation status legend

Use this legend everywhere in this document:

| Tag | Meaning |
|-----|---------|
| **FULL** | Implemented end-to-end (backend + UI or API) and covered by automated tests at some layer |
| **PARTIAL** | Core logic exists but UI/API integration, edge cases, or E2E coverage incomplete |
| **MOCKED** | Behaviour exists only in removed in-app mock router / mock disbursement adapter, not production integrations |
| **BROKEN** | Known regression or documented failure |
| **MISSING** | Not found in codebase or explicitly deferred |
| **UNCLEAR** | Requires product/engineering confirmation |

---

## 1. Executive summary

The Bhawana Capital **Loan Management System (LMS)** is a multi-tenant loan operations platform with:

- **Spring Boot backend** (`backend/`) — PostgreSQL, Flyway (75+ migrations), JWT auth, LSP API, internal ops/admin APIs, webhooks (HMAC-signed outbox), alerts, MIS reports, document storage (MinIO/R2/local), tenant RLS.
- **React frontend** (`frontend/`) — canonical SPA (formerly `frontend-2/`) integrated directly against the live backend per ADR-0001. In-app mock layer removed; Vitest + Playwright CI at repo root (`.github/workflows/frontend-ci.yml`).

**Testing today:**

| Layer | Approx. scale | Against |
|-------|----------------|---------|
| Backend `@SpringBootTest` / MockMvc | ~39 test classes, 200+ test methods | H2 or Testcontainers Postgres |
| Frontend Vitest + RTL | ~123 files, ~1065 tests | Mocks, MSW (minimal), component isolation |
| Frontend Playwright | 34 tests (smoke, responsive, mock loan lifecycle) | **Mock DB only** — not the live backend |

**Strategic gap:** There is **no production-grade E2E suite** that exercises Admin UI, Ops UI, LSP UI, and LSP APIs against a **real** Postgres + backend + storage stack with deterministic seed data and DB/API/audit assertions.

This document defines that suite: modules, coverage gaps, test data, a full case matrix, and a phased automation roadmap for approval before implementation.

**Financial-safety priorities:** idempotent money movement, no duplicate disbursement, exact-installment repayment rules, tenant isolation, audit completeness, webhook delivery integrity, and KPI/report reconciliation with source tables.

---

## 2. Modules identified

### 2.1 Backend domains (authoritative)

| Module | Key packages / controllers | Primary persistence |
|--------|---------------------------|---------------------|
| Identity & auth | `AuthController`, `SecurityConfig` | `app_user`, refresh tokens |
| Users & RBAC | `UserAdminController`, `AppRole` | `app_user`, roles in JWT |
| LSP admin | `LspAdminController`, `LspIpAllowlistAdminController` | `lsp`, webhook config, IP allowlist |
| API clients | `ApiClientAdminController` | `api_client`, allowlist, audit |
| Products & mappings | `LoanProductAdminController`, `ProductLspMappingAdminController` | `loan_product`, mappings, product audit |
| Borrowers | `BorrowerAdminController` | `borrower`, global PAN dedupe, LSP access |
| Loan application lifecycle | `LoanApplicationService`, `LoanApplicationLifecycleService` | `loan_application`, status transitions |
| Documents | `LoanDocumentService`, LSP multipart upload | checklist, MinIO/R2 |
| Schedule | `LoanRepaymentScheduleService` | `loan_repayment_schedule_installment` |
| Disbursement | `LoanDisbursementService`, `MockLoanDisbursementAdapter` | `loan_disbursement_request_log` |
| Repayment | `LoanRepaymentCommandService`, `LspLoanApiController` | `loan_payment_transaction` |
| Foreclosure | `LoanForeclosureCommandService` | `loan_foreclosure_quote` |
| Webhooks | `WebhookOutboxService`, `WebhookOutboxAdminController` | outbox + delivery attempts |
| Alerts | `OpsAlertController`, `AlertRuleEvaluationService` | `ops_alert`, `alert_rule` |
| Reports / MIS | `ReportAdminController`, `AdminReportingService` | `report_request`, R2 storage |
| Dashboard | `HomeDashboardController` | aggregated queries |
| Audit | `AuditExplorerController`, per-loan audit endpoints | multiple audit tables |
| Tenant isolation | RLS, `TenantIsolationPostgresIntegrationTest` | Postgres policies |

**Canonical loan status enum (backend):**  
`INITIALIZED` → `AWAITING_APPROVAL` → `APPROVED_PENDING_DISBURSAL` → (`DISBURSEMENT_RETRY`)* → `DISBURSED` → `UNDER_REPAYMENT` → `CLOSED` | `FORECLOSED`; terminals: `REJECTED`, `INVALID`.

### 2.2 Frontend surfaces

| Surface | Path prefix | Roles | Integration (2026-05-29) |
|---------|-------------|-------|---------------------------|
| Login / password change | `/login`, `/change-password` | All | **FULL** live auth + mock session bridge |
| Home dashboard | `/home` | Internal + LSP | **PARTIAL** — live overview with mock fallback |
| Loan applications (ops) | `/loan-applications` | SYSTEM_ADMIN, OPS_USER, PRODUCT_ADMIN | **PARTIAL** — list live; detail/mutations mixed |
| Borrowers 360 | `/borrowers` | Internal | **PARTIAL** — list live; detail/tabs mock fallback |
| Alerts | `/alerts` | SYSTEM_ADMIN, OPS_USER | **FULL** live |
| Reports / MIS | `/reports` | SYSTEM_ADMIN | **FULL** live |
| Audit explorer | `/audit` | SYSTEM_ADMIN | **FULL** live (unified endpoint); mock fallback non-admin |
| Admin LSPs | `/lsps` | SYSTEM_ADMIN | **FULL** live |
| Admin products | `/products` | SYSTEM_ADMIN, PRODUCT_ADMIN | **FULL** live |
| Admin users | `/users` | SYSTEM_ADMIN | **FULL** live |
| Admin API clients | `/api-clients` | SYSTEM_ADMIN | **FULL** live |
| LSP portal | `/my-loans` | LSP_UI_READ, LSP_UI_WRITE | **FULL** live (LSP API) |

### 2.3 External integration points

| Integration | Status |
|-------------|--------|
| LSP REST API (`/api/v1/lsp/...`) | **FULL** |
| LSP API client OAuth (`POST /api/v1/auth/token`) | **FULL** |
| Outbound webhooks (HMAC `X-Webhook-Signature`) | **FULL** (delivery depends on worker/cron) |
| Disbursement bank/provider | **MOCKED** (`MockLoanDisbursementAdapter`, ops `mock-outcome` endpoint) |
| Document storage | **PARTIAL** — R2/MinIO/local; env-dependent |
| Email (report delivery) | **PARTIAL** — async report requests; **UNCLEAR** in local dev |

### 2.4 Infrastructure (test environments)

`infra/docker-compose.yml`: Postgres 17, Redis, RabbitMQ, MinIO (+ bucket init), MailHog (if extended in full stack).

Local backend bootstrap user (default): `ops.admin` / `ChangeMe123!` with `SYSTEM_ADMIN` + `OPS_USER` (`application-local.yml`).

Optional seeds: `app.seed.sample-data`, `app.seed.demo-portfolio` (disabled by default).

---

## 3. Current test coverage found

### 3.1 Backend automated tests

| Area | Representative tests | Notes |
|------|---------------------|-------|
| LSP API lifecycle | `LspLoanApplicationApiControllerTest` (21 tests) | Token scope, docs, schedule, disbursement compliance, payments, tenant isolation |
| Ops loan workflows | `LoanApplicationOpsControllerTest` (47 tests) | Transitions, disbursement mock outcomes, payments, foreclosure |
| Webhooks | `WebhookOutboxAdminControllerTest` | Dispatch, signing, dead-letter behaviour |
| Auth | `AuthControllerTest` | Login, token, refresh |
| Admin CRUD | `LspAdminControllerTest`, `UserAdminControllerTest`, `ApiClientAdminControllerTest`, `LoanProductAdminControllerTest` | |
| Alerts | `OpsAlertControllerTest` (15 tests) | Acknowledge, escalate, filters |
| Reports | `ReportAdminControllerTest`, `PortfolioMisReadRepository*` | |
| Audit | `AuditExplorerControllerTest` (16 tests) | Unified explorer |
| Security / tenancy | `SecurityConfigTest`, `TenantIsolationPostgresIntegrationTest` | |
| Schema / DB | `SchemaCheckConstraintsPostgresTest`, migration FK tests | Data integrity |

**Gap:** No single test class runs **full LSP API → webhook → alert → audit** in one scenario (pieces exist separately).

### 3.2 Frontend automated tests

| Area | Scale | Notes |
|------|-------|-------|
| Vitest unit/integration | ~1065 tests | Heavy mock-router coverage (`mocks/api/*.test.ts`) |
| Playwright | 34 tests | **MOCKED** — `localStorage` mock DB; sign-in via dev seed buttons |
| API adapter tests | `api.test.ts`, `api-detail.test.ts`, `api-tabs.test.ts`, `*.backend-map.test.ts` | Translation layer only |

### 3.3 Manual / docs coverage

- `docs/API-references/api-spec.md` — endpoint catalogue  
- `frontend/docs/Frontend/CURRENT-STATE.md` — UI phases (mock-first history)  
- `docs/adr/0001-adopt-frontend-2-direct-backend-integration.md` — target: remove mocks, MSW for unit tests  
- Lighthouse a11y passes documented for frontend routes (≥95)  

---

## 4. Gaps, mocks, missing or broken areas

### 4.1 Cross-cutting

| Item | Tag | Detail |
|------|-----|--------|
| E2E against live stack | **MISSING** | Playwright does not start backend/Postgres |
| `docs/INTEGRATION-STATUS.md` | **MISSING** | Referenced in code comments but not in repo |
| Overcharged / overpayment loan alert | **MISSING** | No `OVERCHARG` / overpay alert rule in `AlertRuleDataInitializer` |
| Real disbursement provider | **MOCKED** | Production bank integration not present |
| LSP foreclosure **execute** via API | **MISSING** | LSP can request quote; **execute** is `SYSTEM_ADMIN` ops endpoint only |
| Partial repayment | **MISSING** (by design) | Backend enforces **exact installment** amount (`validateExactInstallmentAmount`) |
| frontend lifecycle ActionBar vs backend statuses | **PARTIAL** | UI lifecycle components still know legacy mock statuses; adapter maps Gap #11 canonical 10 |
| frontend loan detail mutations | **MOCKED** for transitions/disbursement | `api-detail.ts`: `postTransition`, `postDisbursement` still use mock router |
| OPS approval beyond INITIALIZED→AWAITING_APPROVAL | **PARTIAL** | `authorizeStatusTransition` — OPS cannot approve/reject on backend |
| Reports for OPS / LSP UI | **MISSING** on UI | Reports route is SYSTEM_ADMIN only |
| Webhook tab on loan detail | **PARTIAL** | Reads admin outbox when live; falls back to mock |
| CI backend tests in frontend workflow | **MISSING** | `frontend/.github/workflows/ci.yml` does not run Maven tests |

### 4.2 Mock vs real matrix (frontend)

| Feature API module | Live backend | Mock fallback |
|------------------|-------------|---------------|
| `auth-service` | Yes | Session bridge syncs mock for dev |
| `home/api` | Yes | Yes (4xx) |
| `loan-applications/api` (list) | Yes (internal) | Yes |
| `loan-applications/api-detail` | Read yes / mutate mock | LSP always mock on internal routes |
| `loan-applications/api-tabs` | Read yes / repayment live internal | Mock fallback |
| `borrowers/api-list` | Yes (internal) | Yes |
| `borrowers/api`, `api-tabs` | **UNCLEAR** / mostly mock | Yes |
| `alerts/api` | Yes | No |
| `reports/api` | Yes | No |
| `audit/api` | Yes (SYSTEM_ADMIN) | Yes |
| `lsps`, `products`, `users`, `api-clients` | Yes | No |
| `my-loans/api` | Yes | No |

### 4.3 Known broken / flaky (documented)

| Item | Tag |
|------|-----|
| Playwright BR-15 third repayment dialog race | **PARTIAL** / flaky in headed Chromium |
| Reports page axe test under full parallel Vitest load | **PARTIAL** flake (~4097ms) |
| Mock DB debounce vs Playwright navigation | **PARTIAL** — requires `expect.poll` on `localStorage` |

---

## 5. Recommended E2E test architecture

### 5.1 Target stack (recommended)

```
┌─────────────────────────────────────────────────────────────┐
│  Playwright (browser)                                        │
│    Admin / Ops / LSP UI journeys                             │
└──────────────────────────┬──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│  Optional: API test layer (Playwright request / REST Assured) │
│    LSP API client credentials + Idempotency-Key              │
└──────────────────────────┬──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│  Spring Boot LMS (test profile)                              │
│    + Flyway migrations                                       │
│    + Mock disbursement adapter (configurable outcomes)         │
│    + Webhook test receiver (WireMock / httpbin sidecar)       │
└──────────────────────────┬──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│  Postgres (Testcontainers) + MinIO + Redis/RabbitMQ as needed│
└─────────────────────────────────────────────────────────────┘
```

### 5.2 Test pyramid for LMS

| Layer | Tooling | Purpose |
|-------|---------|---------|
| Unit | JUnit, Vitest | Business rules, adapters, pure functions |
| API contract | MockMvc, OpenAPI diff, `openapi-typescript` drift check | Fast regression on DTOs |
| Integration | Testcontainers Postgres, MinIO test support | DB constraints, RLS, repositories |
| E2E UI | Playwright against `frontend` (post-integration) or `frontend` | Role-based journeys |
| E2E API | Dedicated LSP client harness | Lifecycle without UI |
| Non-functional | k6 / Gatling (later) | List/report performance |

### 5.3 Assertions pattern (every critical test)

1. **HTTP/UI** — expected screen, status code, error envelope (`code`, `correlationId`).  
2. **Database** — SQL or repository queries for `loan_application.status`, balances, installment `paid_*`, outbox rows.  
3. **Audit** — `loan_application_audit_event`, `loan_application_intake_audit`, `loan_application_document_access_audit`, `loan_product_audit_event`.  
4. **Webhook** — `webhook_event_outbox` + `webhook_event_delivery_attempt`; optional capture server validates HMAC.  
5. **Alerts** — `ops_alert` when rule triggers.  

### 5.4 Repository layout (proposed, not implemented)

```
tests/
  e2e/
    playwright/
    fixtures/
    sql/
  api/
    lsp-lifecycle/
  harness/
    seed/
    webhook-receiver/
```

---

## 6. Test data strategy

### 6.1 Principles

- **Isolated tenant per run** — dedicated LSP codes (e.g. `LSP_E2E_A`, `LSP_E2E_B`) to prove cross-tenant denial.  
- **Deterministic IDs** — UUID v4 in fixtures; store in `tests/e2e/state.json` for multi-step flows.  
- **No reliance on frontend mock seed** for backend E2E.  
- **Idempotency keys** — generated per mutation; replay cases use fixed keys.  

### 6.2 Seed layers

| Layer | Source | Use |
|-------|--------|-----|
| Baseline catalog | Flyway + optional `SampleCatalogSeedService` | Products, document types |
| E2E fixture API | **MISSING** — propose `POST /internal/test/fixtures/reset` (test profile only) | Fast reset between tests |
| Demo portfolio | `LocalDemoPortfolioSeedService` (`demo-portfolio.enabled=true`) | Manual demos; partial lifecycle samples |
| Per-test factories | Test code via admin/LSP APIs | Preferred for E2E |

### 6.3 Reference personas (passwords rotated in CI secrets)

| Username | Role | LSP | Purpose |
|----------|------|-----|---------|
| `e2e.sysadmin` | SYSTEM_ADMIN | — | Full admin |
| `e2e.ops` | OPS_USER | — | Limited transitions |
| `e2e.product` | PRODUCT_ADMIN | — | Products only |
| `e2e.lsp.read` | LSP_UI_READ | LSP_A | Read-only portal |
| `e2e.lsp.write` | LSP_UI_WRITE | LSP_A | Upload / invalidate |
| API client `e2e-lsp-a-client` | LSP_API_CLIENT | LSP_A | API lifecycle |

### 6.4 Webhook test receiver

Deploy **WireMock** or small Node receiver in compose; configure LSP webhook URL to `http://webhook-receiver:8089/lsp-a`. Validate:

- `X-Webhook-Timestamp`  
- `X-Webhook-Signature` (HMAC-SHA256 per `WebhookOutboxService`)  
- Payload `eventType` enum matches `WebhookEventType`  

---

## 7. User roles / personas

| Role | Backend authority | UI access (frontend) | API access |
|------|-------------------|--------------------------|------------|
| `SYSTEM_ADMIN` | Full internal + manual status + disbursement + payments + foreclosure execute | All internal routes + audit | N/A (user JWT) |
| `OPS_USER` | Read ops loans; transition **only** `INITIALIZED`→`AWAITING_APPROVAL`; alerts | Loans, borrowers, alerts — no admin mutations | N/A |
| `PRODUCT_ADMIN` | Product CRUD/mappings | `/products` only among admin | N/A |
| `LSP_UI_READ` | LSP-scoped read | `/my-loans`, `/home` (LSP KPIs) | Via UI → LSP API |
| `LSP_UI_WRITE` | + invalidate, document upload, foreclosure quote | Same + write actions | Via UI → LSP API |
| `LSP_API_CLIENT` | LSP API write/read per endpoint | No dedicated UI | `/api/v1/lsp/...`, `/api/v1/auth/token` |

**JWT claims:** `lspId`, `lspCode`, `authType`, `pwdchg` (force password change).

---

## 8. Full test case matrix (summary)

Detailed steps for representative cases are in §9–§21. This matrix lists **all planned case IDs** for traceability.

| ID | Module | Priority | Automation |
|----|--------|----------|------------|
| LC-001 … LC-020 | Loan lifecycle (composite) | Critical | Hybrid |
| LSP-API-001 … LSP-API-035 | LSP API | Critical | Automated API |
| DISB-001 … DISB-015 | Disbursement | Critical | Hybrid |
| REP-001 … REP-018 | Repayment / closure | Critical | Hybrid |
| FC-001 … FC-010 | Foreclosure | High | Hybrid |
| ADM-001 … ADM-040 | Admin UI | High | Automated UI |
| OPS-001 … OPS-025 | Ops user | High | Automated UI + API |
| LSP-UI-001 … LSP-UI-020 | LSP UI | High | Automated UI |
| WH-001 … WH-012 | Webhooks | Critical | Automated API |
| AUD-001 … AUD-015 | Audit | High | Hybrid |
| RPT-001 … RPT-012 | Reports | High | Hybrid |
| DSH-001 … DSH-008 | Dashboard KPIs | High | Hybrid |
| SEC-001 … SEC-030 | Security / RBAC | Critical | Automated |
| NEG-001 … NEG-025 | Negative / edge | High | Mixed |

---

## 9. Loan lifecycle scenarios

> **Format:** Each case uses the standard fields required for implementation tracking.

### LC-001 — Happy path: LSP API origination to CLOSED

| Field | Value |
|-------|-------|
| **ID** | LC-001 |
| **Module** | Loan lifecycle |
| **User role** | LSP_API_CLIENT + SYSTEM_ADMIN (disbursement mock) |
| **Scenario** | End-to-end loan from create through full repayment closure |
| **Preconditions** | LSP_A mapped to PRODUCT_X; API client credentials; webhook receiver configured |
| **Test data** | New borrower PAN (unique), principal 100000, tenure 12, schedule 12 installments |
| **Steps** | 1) `POST /auth/token` 2) `POST /lsp/loan-applications` 3) Upload required docs (batch) 4) Ops: `INITIALIZED`→`AWAITING_APPROVAL`→`APPROVED_PENDING_DISBURSAL` 5) `PUT .../repayment-schedule` 6) `POST .../disbursement` 7) Admin mock outcome SUCCESS 8) Repeat `POST /lsp/loans/{id}/payments` until closed |
| **Expected result** | Status progression per `LoanApplicationStatus`; `LOAN_CREATED`, `DOCUMENTS_UPLOADED`, `DISBURSEMENT_*`, `LOAN_REPAYMENT_RECORDED`, `LOAN_FULLY_REPAID` webhooks; final status `CLOSED` |
| **DB/API validation** | Rows in `loan_application`, `loan_account`, installments paid, `loan_payment_transaction` count = tenure |
| **Audit validation** | Status transitions + payment audit events present |
| **Priority** | Critical |
| **Automation** | Automated API + optional UI |
| **Implementation** | Backend **FULL**; UI **PARTIAL**; E2E harness **MISSING** |

### LC-002 — LSP create → Ops reject

| Field | Value |
|-------|-------|
| **ID** | LC-002 |
| **Module** | Loan lifecycle |
| **User role** | LSP_API_CLIENT, SYSTEM_ADMIN |
| **Scenario** | Application rejected before disbursement |
| **Preconditions** | Application in `AWAITING_APPROVAL` |
| **Test data** | Standard intake |
| **Steps** | Ops `POST .../status-transitions` → `REJECTED` with reason |
| **Expected result** | Terminal `REJECTED`; LSP webhook `LOAN_STATUS_CHANGED` |
| **DB/API validation** | `loan_application.status = REJECTED` |
| **Audit validation** | Transition row with reason |
| **Priority** | High |
| **Automation** | Automated API |
| **Implementation** | **FULL** (SYSTEM_ADMIN); OPS cannot reject — **PARTIAL** for OPS persona |

### LC-003 — LSP invalidate pre-disbursal

| Field | Value |
|-------|-------|
| **ID** | LC-003 |
| **Module** | Loan lifecycle |
| **User role** | LSP_API_CLIENT |
| **Scenario** | LSP marks loan invalid with reason catalog |
| **Preconditions** | Pre-disbursal status |
| **Test data** | `reasonCode=REASON_A` |
| **Steps** | `POST .../invalid` with `Idempotency-Key`; replay same key |
| **Expected result** | `INVALID`; idempotent replay returns same body |
| **DB/API validation** | Status `INVALID`; idempotency record |
| **Audit validation** | Intake/audit entries |
| **Priority** | High |
| **Automation** | Automated API |
| **Implementation** | **FULL** (`LspLoanApplicationApiControllerTest`) |

### LC-004 — Post-disbursal invalidation blocked

| Field | Value |
|-------|-------|
| **ID** | LC-004 |
| **Module** | Loan lifecycle |
| **User role** | LSP_API_CLIENT |
| **Scenario** | Invalidate after disbursement fails |
| **Preconditions** | `DISBURSED` or later |
| **Test data** | Disbursed loan |
| **Steps** | `POST .../invalid` |
| **Expected result** | 4xx business error; status unchanged |
| **DB/API validation** | No status change |
| **Audit validation** | No invalidation audit |
| **Priority** | High |
| **Automation** | Automated API |
| **Implementation** | **FULL** |

### LC-005 … LC-020 (abbreviated titles)

| ID | Title | Priority | Impl. |
|----|-------|----------|-------|
| LC-005 | Auto-approve on disbursement when rules pass | High | **PARTIAL** (`LoanApprovalService`) |
| LC-006 | Duplicate open loan blocked (PAN) | Critical | **FULL** + alert `BORROWER_ACTIVE_LOAN_DUPLICATE` |
| LC-007 | Mobile/PAN conflict raises ops alert | High | **FULL** |
| LC-008 | Schedule required before disbursement | Critical | **FULL** |
| LC-009 | Schedule replace blocked after payment | Critical | **FULL** |
| LC-010 | Required documents before disbursement | Critical | **FULL** |
| LC-011 | Unmapped product blocked at create | Critical | **FULL** |
| LC-012 | Global borrower reuse by PAN updates profile | Medium | **FULL** |
| LC-013 | PII masked on list; reveal audited | Critical | **FULL** (reveal endpoint — confirm UI wiring) |
| LC-014 | LSP UI read-only cannot upload docs | High | **FULL** backend; UI **PARTIAL** |
| LC-015 | Assignment events deprecated but readable | Low | **PARTIAL** |
| LC-016 | Manual status override (SYSTEM_ADMIN) | Medium | **FULL** |
| LC-017 | Invalid state transition rejected | Critical | **FULL** |
| LC-018 | Loan stuck in DISBURSEMENT_RETRY triggers STUCK_DISBURSEMENT alert | High | **FULL** rule |
| LC-019 | STALE_INTAKE alert for old INITIALIZED | Medium | **FULL** rule |
| LC-020 | Full lifecycle UI walk (frontend) | Medium | **MOCKED** (Playwright mock only) |

---

## 10. Admin UI scenarios

### ADM-001 — Create LSP and configure webhook

| Field | Value |
|-------|-------|
| **ID** | ADM-001 |
| **Module** | Admin / LSPs |
| **User role** | SYSTEM_ADMIN |
| **Scenario** | Create LSP, enable webhook with event types and signing secret |
| **Preconditions** | Logged in as SYSTEM_ADMIN |
| **Test data** | Unique `code`, HTTPS webhook URL (test receiver) |
| **Steps** | Navigate `/lsps` → Create → Save → Edit webhook → select events → save |
| **Expected result** | LSP appears in list; webhook subscription persisted |
| **DB/API validation** | `lsp` row; `webhook_enabled`, `webhook_endpoint_url`, `webhook_signing_secret` |
| **Audit validation** | **UNCLEAR** — confirm LSP admin audit stream |
| **Priority** | Critical |
| **Automation** | Automated UI |
| **Implementation** | API **FULL**; frontend **FULL** |

### ADM-002 — Create API client with IP allowlist (BR-8)

| Field | Value |
|-------|-------|
| **ID** | ADM-002 |
| **Module** | Admin / API clients |
| **User role** | SYSTEM_ADMIN |
| **Scenario** | Create client, reveal secret once, configure allowlist |
| **Preconditions** | LSP exists |
| **Test data** | Client name, CIDR allowlist |
| **Steps** | `/api-clients` → Create → copy secret → add IP → token request from allowed/blocked IP |
| **Expected result** | Secret shown once; token denied from non-allowlisted IP |
| **DB/API validation** | `api_client`, `api_client_ip_allowlist` |
| **Audit validation** | `api_client_audit_event` |
| **Priority** | Critical |
| **Automation** | Hybrid |
| **Implementation** | **FULL** backend; UI **FULL** |

### ADM-003 — Create product and map LSP

| Field | Value |
|-------|-------|
| **ID** | ADM-003 |
| **Module** | Admin / Products |
| **User role** | SYSTEM_ADMIN or PRODUCT_ADMIN |
| **Scenario** | Product CRUD + mapping dialog |
| **Preconditions** | Auth |
| **Test data** | Principal bounds, tenure, fee rate |
| **Steps** | `/products` → create → map LSP_A enabled |
| **Expected result** | LSP API lists product for LSP_A only |
| **DB/API validation** | `loan_product`, `loan_product_lsp_mapping` |
| **Audit validation** | `loan_product_audit_event` on mutations |
| **Priority** | Critical |
| **Automation** | Automated UI |
| **Implementation** | **FULL** |

### ADM-004 … ADM-040 (titles)

| ID | Title | Priority |
|----|-------|----------|
| ADM-004 | Create user per role; force password change on reset | Critical |
| ADM-005 | Disable user; token invalidated (`token_version`) | Critical |
| ADM-006 | View loan application detail all tabs | High |
| ADM-007 | Ops transition buttons visible only when allowed | High |
| ADM-008 | Initiate disbursement (SYSTEM_ADMIN) | Critical |
| ADM-009 | Apply mock disbursement success/failure | Critical |
| ADM-010 | Record repayment from Repayments tab | Critical |
| ADM-011 | Request + execute foreclosure | High |
| ADM-012 | Download loan documents ZIP | High |
| ADM-013 | Update KYC checklist item | High |
| ADM-014 | View webhook deliveries on loan | Medium |
| ADM-015 | Webhook outbox admin dispatch | Medium |
| ADM-016 | View borrowers directory | Medium |
| ADM-017 | Borrower 360 PII reveal with reason | Critical |
| ADM-018 | Document preview access audit | Critical |
| ADM-019 | Dashboard KPIs match SQL totals | Critical |
| ADM-020 | Portfolio MIS download CSV | High |
| ADM-021 | Async report request + download | High |
| ADM-022 | Audit explorer filters + streams | High |
| ADM-023 | Alerts inbox acknowledge with note | High |
| ADM-024 | Manual status override dialog | Medium |
| ADM-025 | Escalate alert | Medium |
| ADM-026 | Product admin cannot access `/users` | High |
| ADM-027 | Empty states for filtered lists | Low |
| ADM-028 | Large list pagination performance | Medium |
| ADM-029 | Create loan from ops `POST` (optional) | Medium |
| ADM-030 | Assign application (legacy) | Low |
| ADM-031–040 | Regression pack per admin route (axe, responsive) | Low–Medium |

---

## 11. Ops user scenarios

### OPS-001 — Login and landing

| Field | Value |
|-------|-------|
| **ID** | OPS-001 |
| **Module** | Ops |
| **User role** | OPS_USER |
| **Scenario** | Ops lands on home with permitted nav only |
| **Preconditions** | Active ops user |
| **Test data** | `e2e.ops` |
| **Steps** | Login → inspect sidebar |
| **Expected result** | No `/users`, `/lsps`, `/api-clients`, `/audit`, `/reports` |
| **DB/API validation** | N/A |
| **Audit validation** | Login audit **UNCLEAR** |
| **Priority** | High |
| **Automation** | Automated UI |
| **Implementation** | UI guards **FULL**; verify API 403 |

### OPS-002 — Allowed status transition only

| Field | Value |
|-------|-------|
| **ID** | OPS-002 |
| **Module** | Ops / Loans |
| **User role** | OPS_USER |
| **Scenario** | Ops moves `INITIALIZED` → `AWAITING_APPROVAL` only |
| **Preconditions** | Loan in `INITIALIZED` |
| **Test data** | Ops-created or API-created loan |
| **Steps** | Open detail → submit transition → attempt `APPROVED_PENDING_DISBURSAL` via API |
| **Expected result** | First succeeds; second returns 403 Access Denied |
| **DB/API validation** | Status after first step `AWAITING_APPROVAL` |
| **Audit validation** | Transition event |
| **Priority** | Critical |
| **Automation** | Hybrid |
| **Implementation** | Backend **FULL**; UI ActionBar **PARTIAL** (may still show disabled actions) |

### OPS-003 … OPS-025 (titles)

| ID | Title | Priority |
|----|-------|----------|
| OPS-003 | Direct API `POST .../disbursement-requests` → 403 | Critical |
| OPS-004 | Direct API record payment → 403 | Critical |
| OPS-005 | View alerts; acknowledge | High |
| OPS-006 | Cannot acknowledge SYSTEM_ADMIN-only alert types | Medium |
| OPS-007 | View loans with filters | High |
| OPS-008 | Download documents if permitted | High |
| OPS-009 | Cross-LSP loan URL → 404/403 | Critical |
| OPS-010 | Borrower list accessible | Medium |
| OPS-011 | Cannot access admin metadata | High |
| OPS-012 | Password change required flow | Medium |
| OPS-013 | Session refresh | Medium |
| OPS-014 | Direct URL to `/audit` blocked | High |
| OPS-015 | Direct URL to `/reports` blocked | High |
| OPS-016–025 | Ops regression (filters, pagination, error states) | Medium |

---

## 12. LSP UI scenarios

### LSP-UI-001 — Tenant isolation on loan list

| Field | Value |
|-------|-------|
| **ID** | LSP-UI-001 |
| **Module** | LSP UI |
| **User role** | LSP_UI_READ (LSP_A) |
| **Scenario** | User sees only LSP_A loans |
| **Preconditions** | Loans exist for LSP_A and LSP_B |
| **Test data** | Two LSPs seeded |
| **Steps** | Login → `/my-loans` → search |
| **Expected result** | Only LSP_A applications; attempt deep-link to LSP_B loan ID fails |
| **DB/API validation** | API returns 403/404 for other LSP |
| **Audit validation** | N/A |
| **Priority** | Critical |
| **Automation** | Automated UI + API |
| **Implementation** | Backend **FULL**; UI **FULL** (`my-loans/api`) |

### LSP-UI-002 — Upload documents (write role)

| Field | Value |
|-------|-------|
| **ID** | LSP-UI-002 |
| **Module** | LSP UI |
| **User role** | LSP_UI_WRITE |
| **Scenario** | Batch document upload updates checklist |
| **Preconditions** | Application owned by LSP |
| **Test data** | Valid PDFs under size limits |
| **Steps** | Detail → upload → refresh checklist |
| **Expected result** | Documents `SUBMITTED`; webhook `DOCUMENTS_UPLOADED` when complete |
| **DB/API validation** | Checklist + storage keys |
| **Audit validation** | Document access audit on download |
| **Priority** | Critical |
| **Automation** | Hybrid |
| **Implementation** | **FULL** |

### LSP-UI-003 … LSP-UI-020 (titles)

| ID | Title | Priority |
|----|-------|----------|
| LSP-UI-003 | Mark invalid with reason | High |
| LSP-UI-004 | Read-only cannot invalidate | High |
| LSP-UI-005 | View disbursement/repayment status on detail | High |
| LSP-UI-006 | View failed/rejected loan | Medium |
| LSP-UI-007 | No loan create button in UI | Medium (**MISSING** UI create — API only) |
| LSP-UI-008 | Home LSP KPI cards | Medium |
| LSP-UI-009 | PII remains masked in list | Critical |
| LSP-UI-010 | Cannot access `/loan-applications` internal route | Critical |
| LSP-UI-011 | Foreclosure quote from UI **UNCLEAR** | Medium |
| LSP-UI-012 | No reports download for LSP | Low (**MISSING**) |
| LSP-UI-013–020 | Responsive + a11y smoke on `/my-loans` | Low |

---

## 13. LSP API scenarios

### LSP-API-001 — Client credentials token

| Field | Value |
|-------|-------|
| **ID** | LSP-API-001 |
| **Module** | LSP API / Auth |
| **User role** | LSP_API_CLIENT |
| **Scenario** | Mint JWT with client_id/secret |
| **Preconditions** | Active API client |
| **Test data** | Valid/invalid secret |
| **Steps** | `POST /api/v1/auth/token` |
| **Expected result** | 200 + bearer; wrong secret 401 |
| **DB/API validation** | JWT contains `lspId`, role |
| **Audit validation** | Rate limit alert on abuse |
| **Priority** | Critical |
| **Automation** | Automated API |
| **Implementation** | **FULL** |

### LSP-API-002 — Create loan application

| Field | Value |
|-------|-------|
| **ID** | LSP-API-002 |
| **Module** | LSP API |
| **User role** | LSP_API_CLIENT |
| **Scenario** | Create application with full borrower payload |
| **Preconditions** | Mapped product |
| **Test data** | Unique PAN, external loan id |
| **Steps** | `POST /api/v1/lsp/loan-applications` |
| **Expected result** | 201/200; status `INITIALIZED`; webhook `LOAN_CREATED` |
| **DB/API validation** | `loan_application`, `borrower` |
| **Audit validation** | Intake audit |
| **Priority** | Critical |
| **Automation** | Automated API |
| **Implementation** | **FULL** |

### LSP-API-003 … LSP-API-035 (titles)

| ID | Title | Priority |
|----|-------|----------|
| LSP-API-003 | List/filter/paginate applications | High |
| LSP-API-004 | Get by id and external id | High |
| LSP-API-005 | Invalid reason catalog | Medium |
| LSP-API-006 | Invalidate + idempotency replay | High |
| LSP-API-007 | Document batch upload + MIME/size violations | Critical |
| LSP-API-008 | Duplicate document type in batch rejected | High |
| LSP-API-009 | `PUT` repayment schedule validation | Critical |
| LSP-API-010 | Disbursement compliance failures aggregated | Critical |
| LSP-API-011 | Disbursement success path | Critical |
| LSP-API-012 | Cross-LSP access denied | Critical |
| LSP-API-013 | List provisioned products only | High |
| LSP-API-014 | `GET` loan + schedule + payments | High |
| LSP-API-015 | `POST` payment exact installment | Critical |
| LSP-API-016 | Payment idempotency replay | Critical |
| LSP-API-017 | Payment before disbursement rejected | Critical |
| LSP-API-018 | Payment after closure rejected | High |
| LSP-API-019 | Partial amount rejected | High |
| LSP-API-020 | Overpayment attempt rejected | High |
| LSP-API-021 | Foreclosure quote | High |
| LSP-API-022 | No LSP execute foreclosure endpoint | Medium (**MISSING** by design) |
| LSP-API-023 | IP allowlist enforced | Critical |
| LSP-API-024 | Expired/invalid JWT | Critical |
| LSP-API-025 | Missing Idempotency-Key on write | High |
| LSP-API-026 | Idempotency-Key not UUID v4 | Medium |
| LSP-API-027 | Rate limit → `RATE_LIMIT_BREACH` alert | Medium |
| LSP-API-028 | Webhook on each major event | Critical |
| LSP-API-029 | PII masked fields on detail | Critical |
| LSP-API-030 | Document list scoped to owner | High |
| LSP-API-031 | Auto-approve path on disbursement | Medium |
| LSP-API-032 | Bank detail mismatch on disbursement | High |
| LSP-API-033 | Disbursal amount > principal rejected | High |
| LSP-API-034 | Disbursal shortfall beyond fee cap | High |
| LSP-API-035 | Concurrent duplicate create **UNCLEAR** | Medium |

---

## 14. Disbursement scenarios

### DISB-001 — First attempt success

| Field | Value |
|-------|-------|
| **ID** | DISB-001 |
| **Module** | Disbursement |
| **User role** | LSP_API_CLIENT + SYSTEM_ADMIN |
| **Scenario** | Disbursement succeeds on first attempt |
| **Preconditions** | Approved, docs complete, schedule valid |
| **Test data** | Disbursal amount = principal |
| **Steps** | LSP `POST .../disbursement` → admin mock outcome SUCCESS (if async) |
| **Expected result** | `DISBURSED`; `DISBURSEMENT_COMPLETED` webhook |
| **DB/API validation** | `loan_disbursement_request_log` SUCCESS; `loan_account.disbursed_at` set |
| **Audit validation** | Disbursement + status transition audits |
| **Priority** | Critical |
| **Automation** | Automated API |
| **Implementation** | **MOCKED** provider; state machine **FULL** |

### DISB-002 — Failure then retry success

| Field | Value |
|-------|-------|
| **ID** | DISB-002 |
| **Module** | Disbursement |
| **User role** | SYSTEM_ADMIN |
| **Scenario** | Failure → `DISBURSEMENT_RETRY` → retry succeeds |
| **Preconditions** | Application ready |
| **Test data** | Mock outcome FAILED then SUCCESS |
| **Steps** | Initiate → mock FAILED → initiate retry → mock SUCCESS |
| **Expected result** | `DISBURSEMENT_RETRY` then `DISBURSED` |
| **DB/API validation** | Multiple log rows; no duplicate account |
| **Audit validation** | Each attempt logged |
| **Priority** | Critical |
| **Automation** | Automated API |
| **Implementation** | **FULL** (mock adapter) |

### DISB-003 … DISB-015 (titles)

| ID | Title | Priority |
|----|-------|----------|
| DISB-003 | Retry fails again; remains RETRY | High |
| DISB-004 | Duplicate disbursement request idempotent | Critical |
| DISB-005 | Disburse without schedule → violation | Critical |
| DISB-006 | Disburse without documents → violation | Critical |
| DISB-007 | Provider timeout simulation | Medium (**UNCLEAR** — mock may not model timeout) |
| DISB-008 | Invalid mock outcome payload | Medium |
| DISB-009 | Status mismatch manual check | High |
| DISB-010 | STUCK_DISBURSEMENT alert fires | High |
| DISB-011 | Ops visibility on disbursement tab | High |
| DISB-012 | LSP webhook on failure | High |
| DISB-013 | Admin UI initiate uses correct endpoint | High (**PARTIAL** frontend) |
| DISB-014 | LSP API disbursement without ops approve | Medium |
| DISB-015 | Deduction cap / shortfall rules | High |

---

## 15. Repayment / foreclosure scenarios

### REP-001 — Normal full installment payment

| Field | Value |
|-------|-------|
| **ID** | REP-001 |
| **Module** | Repayment |
| **User role** | LSP_API_CLIENT |
| **Scenario** | Pay next installment exact amount |
| **Preconditions** | `DISBURSED`/`UNDER_REPAYMENT`, schedule exists |
| **Test data** | `targetInstallmentId`, amount = outstanding |
| **Steps** | `POST /lsp/loans/{id}/payments` |
| **Expected result** | Payment recorded; installment updated; webhook `LOAN_REPAYMENT_RECORDED` |
| **DB/API validation** | `loan_payment_transaction`, paid amounts |
| **Audit validation** | Payment audit |
| **Priority** | Critical |
| **Automation** | Automated API |
| **Implementation** | **FULL** |

### REP-002 — Auto-advance to UNDER_REPAYMENT

| Field | Value |
|-------|-------|
| **ID** | REP-002 |
| **Module** | Repayment |
| **User role** | SYSTEM_ADMIN or LSP_API_CLIENT |
| **Scenario** | First payment moves `DISBURSED` → `UNDER_REPAYMENT` |
| **Preconditions** | First payment on disbursed loan |
| **Test data** | First installment |
| **Steps** | Record payment |
| **Expected result** | Application `UNDER_REPAYMENT` |
| **DB/API validation** | Status + installment PAID/PARTIAL |
| **Audit validation** | Status transition |
| **Priority** | Critical |
| **Automation** | Automated API |
| **Implementation** | **FULL** (BR-14); UI mock **FULL** |

### REP-003 — Full repayment closes loan

| Field | Value |
|-------|-------|
| **ID** | REP-003 |
| **Module** | Repayment |
| **User role** | LSP_API_CLIENT |
| **Scenario** | Pay all installments → `CLOSED` |
| **Preconditions** | Schedule N installments |
| **Test data** | N payments |
| **Steps** | Loop payments |
| **Expected result** | `CLOSED`; `LOAN_FULLY_REPAID` webhook |
| **DB/API validation** | All installments satisfied; account closed |
| **Audit validation** | Closure audit |
| **Priority** | Critical |
| **Automation** | Automated API |
| **Implementation** | **FULL** |

### REP-004 … REP-018 (titles)

| ID | Title | Priority |
|----|-------|----------|
| REP-004 | Partial amount rejected | Critical |
| REP-005 | Duplicate Idempotency-Key returns same payment | Critical |
| REP-006 | Payment before disbursement | Critical |
| REP-007 | Payment after CLOSED | High |
| REP-008 | Late payment (past due date) allowed | Medium |
| REP-009 | Early payment allowed | Medium |
| REP-010 | Wrong installment id | High |
| REP-011 | Admin UI repayment dialog (frontend) | High (**PARTIAL** live) |
| REP-012 | Ops cannot post payment via API | Critical |
| REP-013 | Delinquency bucket update | High |
| REP-014 | DPD dashboard bucket matches DB | High |
| REP-015 | Overpayment attempt | High (rejected) |
| REP-016 | “Overcharged loan” alert | **MISSING** |
| REP-017 | Invalid repayment alert **UNCLEAR** | Medium |
| REP-018 | UI BR-13 inline error on partial (mock Playwright) | Medium (**MOCKED**) |

### FC-001 — Foreclosure quote and execute (admin)

| Field | Value |
|-------|-------|
| **ID** | FC-001 |
| **Module** | Foreclosure |
| **User role** | SYSTEM_ADMIN |
| **Scenario** | Quote → execute → `FORECLOSED` |
| **Preconditions** | Active loan |
| **Test data** | Settlement date = quote effective date |
| **Steps** | `POST .../foreclosure-quotes` → `POST .../execute` |
| **Expected result** | `FORECLOSED`; webhook `LOAN_FORECLOSURE_COMPLETED` |
| **DB/API validation** | Quote status executed; balances settled |
| **Audit validation** | Foreclosure audits |
| **Priority** | High |
| **Automation** | Automated API |
| **Implementation** | **FULL** ops; LSP quote only |

### FC-002 … FC-010 (titles)

| ID | Title | Priority |
|----|-------|----------|
| FC-002 | LSP API foreclosure quote | High |
| FC-003 | Execute with wrong settlement date fails | High |
| FC-004 | Execute non-active quote fails | High |
| FC-005 | UI foreclosure dialog (mock lifecycle) | Medium |
| FC-006 | Foreclosure without quote fails | High |
| FC-007 | Balance zero after foreclosure | High |
| FC-008 | Ops cannot execute | Critical |
| FC-009 | LSP cannot execute via API | Medium |
| FC-010 | Foreclosure visibility on loan detail | Medium |

---

## 16. Webhook / event tests

### WH-001 — HMAC signature validation

| Field | Value |
|-------|-------|
| **ID** | WH-001 |
| **Module** | Webhooks |
| **User role** | System |
| **Scenario** | Receiver verifies HMAC signature |
| **Preconditions** | LSP webhook configured with secret |
| **Test data** | Known secret `whsec_test` |
| **Steps** | Trigger event → capture headers → recompute HMAC |
| **Expected result** | Signature matches; tampered body fails |
| **DB/API validation** | Outbox row `DELIVERED` or retrying |
| **Audit validation** | N/A |
| **Priority** | Critical |
| **Automation** | Automated API |
| **Implementation** | **FULL** (`WebhookOutboxService.signPayload`) |

### WH-002 … WH-012 (titles)

| ID | Title | Priority |
|----|-------|----------|
| WH-002 | Subscribed events only delivered | High |
| WH-003 | `LOAN_CREATED` on create | Critical |
| WH-004 | `LOAN_STATUS_CHANGED` on transition | Critical |
| WH-005 | `DOCUMENTS_UPLOADED` when checklist complete | High |
| WH-006 | `DISBURSEMENT_COMPLETED` / `DISBURSEMENT_FAILED` | Critical |
| WH-007 | `LOAN_REPAYMENT_RECORDED` / `LOAN_FULLY_REPAID` | Critical |
| WH-008 | Unreachable endpoint → retries → dead letter | Critical |
| WH-009 | WEBHOOK_DEAD_LETTER alert | High |
| WH-010 | Timeout / 500 / 401 / 403 from receiver | High |
| WH-011 | Admin outbox dispatch batch | Medium |
| WH-012 | Loan detail Webhooks tab matches outbox | Medium (**PARTIAL** UI) |

**Backend event catalogue (`WebhookEventType`):**  
`LOAN_CREATED`, `LOAN_STATUS_CHANGED`, `LOAN_DISBURSEMENT_UPDATED`, `DISBURSEMENT_REQUESTED`, `DISBURSEMENT_COMPLETED`, `DISBURSEMENT_FAILED`, `DOCUMENTS_UPLOADED`, `LOAN_REPAYMENT_RECORDED`, `LOAN_FULLY_REPAID`, `FORECLOSURE_QUOTE_REQUESTED`, `LOAN_FORECLOSURE_COMPLETED`.

**Note:** Frontend webhook subscription UI maps a **subset** of event type names — verify parity when testing (§4.1).

---

## 17. Audit log tests

### AUD-001 — Unified audit explorer streams

| Field | Value |
|-------|-------|
| **ID** | AUD-001 |
| **Module** | Audit |
| **User role** | SYSTEM_ADMIN |
| **Scenario** | Filter unified audit endpoint by stream |
| **Preconditions** | Known product mutation + loan transition |
| **Test data** | Time range filters |
| **Steps** | Perform product update + loan transition → `/audit` → filter APPLICATION vs PRODUCT |
| **Expected result** | Both events visible with correct stream |
| **DB/API validation** | Matches `GET /api/v1/internal/admin/audit-events` |
| **Audit validation** | Self-check row content |
| **Priority** | High |
| **Automation** | Hybrid |
| **Implementation** | **FULL** |

### AUD-002 … AUD-015 (titles)

| ID | Title | Priority |
|----|-------|----------|
| AUD-002 | Per-loan activity tab matches ops audit endpoint | High |
| AUD-003 | Intake audit on LSP create | High |
| AUD-004 | Document access audit on preview/download | Critical |
| AUD-005 | PII reveal audit **UNCLEAR** (endpoint retired?) | Medium |
| AUD-006 | API client secret rotate audit | High |
| AUD-007 | User create/reset audit | Medium |
| AUD-008 | Non-admin receives 403 on audit API | Critical |
| AUD-009 | correlationId deep-link from alert | Medium |
| AUD-010 | Export/filter pagination | Medium |
| AUD-011 | No cleartext secrets in audit payload | Critical |
| AUD-012 | Product audit on mapping change | High |
| AUD-013 | Status transition reason codes stored | High |
| AUD-014 | Manual override audit | Medium |
| AUD-015 | Audit completeness after full lifecycle | Critical |

---

## 18. Report / download tests

### RPT-001 — Portfolio MIS CSV matches DB

| Field | Value |
|-------|-------|
| **ID** | RPT-001 |
| **Module** | Reports |
| **User role** | SYSTEM_ADMIN |
| **Scenario** | Sync MIS CSV row count vs SQL filter |
| **Preconditions** | Known disbursal date range with N loans |
| **Test data** | `disbursalDateFrom`, `disbursalDateTo`, optional `lspId` |
| **Steps** | `GET /portfolio-mis` → parse CSV → compare to SQL |
| **Expected result** | Row count and principal totals match |
| **DB/API validation** | SQL aggregation = CSV totals |
| **Audit validation** | N/A |
| **Priority** | Critical |
| **Automation** | Automated API |
| **Implementation** | **FULL** backend |

### RPT-002 … RPT-012 (titles)

| ID | Title | Priority |
|----|-------|----------|
| RPT-002 | MIS preview table vs preview API | High |
| RPT-003 | Async report request → COMPLETED → download | High |
| RPT-004 | Report storage failure handling | Medium |
| RPT-005 | Invalid date range | Medium |
| RPT-006 | OPS cannot access reports API | Critical |
| RPT-007 | LSP cannot access reports | Critical |
| RPT-008 | Large report performance | Medium |
| RPT-009 | UI create report dialog | High |
| RPT-010 | UI polling every 5s | Medium |
| RPT-011 | Download filename from Content-Disposition | Low |
| RPT-012 | Report request notification email | **UNCLEAR** |

---

## 19. Dashboard KPI validation tests

### DSH-001 — Admin home overview totals

| Field | Value |
|-------|-------|
| **ID** | DSH-001 |
| **Module** | Dashboard |
| **User role** | SYSTEM_ADMIN |
| **Scenario** | `/internal/home/overview` matches SQL KPIs |
| **Preconditions** | Seeded portfolio |
| **Test data** | Fixed seed set |
| **Steps** | Call API → run SQL for active loans, disbursed MTD, PAR |
| **Expected result** | Fields `totalDisbursed`, `activeLoanCount`, `portfolioAtRiskPct`, etc. match |
| **DB/API validation** | Cross-query `HomeDashboardService` inputs |
| **Audit validation** | N/A |
| **Priority** | Critical |
| **Automation** | Automated API |
| **Implementation** | Backend **FULL**; frontend home **PARTIAL** hybrid |

### DSH-002 … DSH-008 (titles)

| ID | Title | Priority |
|----|-------|----------|
| DSH-002 | DPD bucket counts match installment logic | Critical |
| DSH-003 | Applications-by-status card **UNCLEAR** (replaced by DPD card in UI) | Medium |
| DSH-004 | LSP home KPI scoped to LSP | High |
| DSH-005 | Open alerts card count vs alerts API | High |
| DSH-006 | Recent applications list | Medium |
| DSH-007 | UI home vs API after live integration | High |
| DSH-008 | Mock home KPI test deprecated post-E2E | Low |

---

## 20. Security / RBAC tests

### SEC-001 — Cross-LSP API isolation

| Field | Value |
|-------|-------|
| **ID** | SEC-001 |
| **Module** | Security |
| **User role** | LSP_API_CLIENT (LSP_A) |
| **Scenario** | Token for LSP_A cannot read LSP_B loan |
| **Preconditions** | Loans in both tenants |
| **Test data** | Known application IDs |
| **Steps** | Call GET with LSP_A token for LSP_B id |
| **Expected result** | 404 or 403 consistently |
| **DB/API validation** | RLS / service-layer checks |
| **Audit validation** | No data leak in body |
| **Priority** | Critical |
| **Automation** | Automated API |
| **Implementation** | **FULL** |

### SEC-002 … SEC-030 (titles)

| ID | Title | Priority |
|----|-------|----------|
| SEC-002 | JWT expired → 401 | Critical |
| SEC-003 | Wrong role on endpoint → 403 | Critical |
| SEC-004 | Missing Authorization header | Critical |
| SEC-005 | Refresh token rotation | High |
| SEC-006 | Password change required flag blocks APIs | High |
| SEC-007 | Disabled user cannot login | Critical |
| SEC-008 | API client disabled | Critical |
| SEC-009 | IP allowlist bypass attempt | Critical |
| SEC-010 | Direct URL to guarded React route | High |
| SEC-011 | CORS preflight | Medium |
| SEC-012 | Idempotency replay cross-operation denied | High |
| SEC-013 | SQL injection in `q` filter | High |
| SEC-014 | XSS in borrower name display | High |
| SEC-015 | Document download authorization | Critical |
| SEC-016 | Webhook secret not returned in list APIs | High |
| SEC-017 | Tenant isolation integration test parity | Critical |
| SEC-018 | OPS_USER SQL RLS scope | Critical |
| SEC-019 | SYSTEM_ADMIN global borrower access | High |
| SEC-020 | Rate limit breach alert | Medium |
| SEC-021 | File upload virus scanning **MISSING** | Low |
| SEC-022 | CSRF on cookie refresh **UNCLEAR** | Medium |
| SEC-023 | Correlation ID in error responses | Medium |
| SEC-024 | Sensitive fields never in logs **UNCLEAR** | Medium |
| SEC-025 | Bootstrap user only in dev | Medium |
| SEC-026–030 | Pen-test backlog (SSRF webhook URL, etc.) | Medium |

---

## 21. Edge / negative tests

| ID | Scenario | Priority | Impl. |
|----|----------|----------|-------|
| NEG-001 | Empty loan list UI state | Low | **FULL** |
| NEG-002 | Invalid UUID in URL | Medium | **PARTIAL** |
| NEG-003 | Concurrent disbursement requests | Critical | **UNCLEAR** |
| NEG-004 | Concurrent payments same installment | Critical | **PARTIAL** (idempotency) |
| NEG-005 | Missing borrower bank on disbursement | High | **FULL** |
| NEG-006 | Wrong IFSC/account holder | High | **FULL** |
| NEG-007 | Oversized document upload | High | **FULL** |
| NEG-008 | Disallowed MIME type | High | **FULL** |
| NEG-009 | Schedule with wrong totals vs principal | High | **FULL** |
| NEG-010 | Product tenure outside bounds at create | High | **FULL** |
| NEG-011 | Webhook URL empty when enabled | Medium | **PARTIAL** |
| NEG-012 | Invalid webhook event type in admin UI | Medium | **PARTIAL** |
| NEG-013 | Network offline UI error states | Medium | **PARTIAL** |
| NEG-014 | 413 payload too large | Medium | **UNCLEAR** |
| NEG-015 | Database constraint violations surfaced cleanly | High | **FULL** (tests exist) |
| NEG-016 | Optimistic locking conflict | Medium | **PARTIAL** |
| NEG-017 | Report generation timeout | Medium | **UNCLEAR** |
| NEG-018 | MinIO/R2 unavailable | Medium | **PARTIAL** |
| NEG-019 | RabbitMQ down impact | **UNCLEAR** | |
| NEG-020 | Redis down impact | **UNCLEAR** | |
| NEG-021 | Invalid status transition from UI (mock) | High | **MOCKED** |
| NEG-022 | Invalid status transition from API | High | **FULL** |
| NEG-023 | Loan in DISBURSEMENT_RETRY unlimited retries | Medium | **PARTIAL** |
| NEG-024 | Timezone boundaries on `postedAt` | Medium | **UNCLEAR** |
| NEG-025 | Unicode in borrower name | Low | **UNCLEAR** |

---

## 22. Automation priority

| Priority | Scope | Rationale |
|----------|-------|-----------|
| **P0 — Critical** | LSP API lifecycle, disbursement+retry, repayment idempotency, tenant isolation, webhook HMAC, SEC-001–010, DISB-001–006, REP-001–007 | Money movement + data isolation |
| **P1 — High** | Ops RBAC negatives, admin CRUD smoke, audit/document access, MIS reconciliation, alert rules | Operational safety |
| **P2 — Medium** | UI regression (frontend post-wiring), dashboard KPI, foreclosure, performance smoke | UX + reporting |
| **P3 — Low** | a11y/responsive extended, edge locales, pen-test items | Quality polish |

**Deprecate when live E2E exists:** mock-router Playwright lifecycle (`e2e/loan-lifecycle.spec.ts`) — keep until `frontend` mutations are fully backend-backed.

---

## 23. Manual testing checklist

Use before each release candidate (cannot be fully automated initially):

- [ ] Smoke login for all six role types  
- [ ] Visual review loan detail tabs with real backend data  
- [ ] Confirm webhook receiver in staging receives signed payloads  
- [ ] Manual bank disbursement cutover checklist (**when provider exists**)  
- [ ] R2/MinIO document download opens correct file  
- [ ] Email delivery of async MIS report  
- [ ] Verify Flyway migration applied on staging DB version  
- [ ] Cross-browser spot check (Chrome + Edge)  
- [ ] Ops user attempt every forbidden admin action (UI + curl)  
- [ ] Review `correlationId` in support ticket reproduction  
- [ ] Compare dashboard to raw SQL workbook (finance sign-off)  
- [ ] Disaster recovery: restore Postgres + replay webhooks **UNCLEAR**  

---

## 24. Risks and unknowns

| Risk | Impact | Mitigation |
|------|--------|------------|
| frontend hybrid mock fallback masks integration bugs | High | Fail tests if backend required; remove fallback in CI |
| Mock disbursement ≠ production provider | High | Adapter contract tests; provider sandbox phase |
| OPS cannot approve loans in backend | Medium | Confirm product intent; tests reflect actual RBAC |
| No “overcharged loan” implementation | Medium | Product decision; add rule or remove from BRD |
| Webhook delivery async worker timing | Medium | Poll outbox; deterministic dispatch endpoint in test |
| LSP UI cannot create loans | Low | Document API-only; add UI later if needed |
| Foreclosure execute admin-only | Medium | LSP flows stop at quote unless product changes |
| Playwright CI without backend | High | Add compose job to pipeline |
| PII reveal endpoint changes | Medium | Reconcile AUD-005 with latest API spec |
| frontend vs frontend divergence during migration | High | Complete ADR-0001; single E2E target |

---

## 25. Phased implementation roadmap

> **Gate:** Do **not** start implementation until this document is reviewed and approved.

### Phase 0 — Foundation (1–2 weeks)

- [ ] Approve this document; resolve **UNCLEAR** items in §24  
- [ ] Add `tests/e2e` harness: Testcontainers Postgres + Flyway + Spring Boot test profile  
- [ ] Webhook WireMock receiver in compose  
- [ ] Test-only fixture reset endpoint **or** documented SQL seed scripts  
- [ ] CI job: `mvn test` + compose E2E smoke  

**Exit:** One API test (LSP-API-001 + LSP-API-002) green in CI.

### Phase 1 — API lifecycle suite (2–3 weeks)

- [ ] Implement LSP-API-001 … LSP-API-035 (REST Assured or Playwright `request`)  
- [ ] Implement DISB-*, REP-*, FC-*, WH-* API cases  
- [ ] DB assertion helper library  
- [ ] Nightly run against `demo-portfolio` seed + dedicated E2E data  

**Exit:** LC-001 green end-to-end without UI.

### Phase 2 — Security & data integrity (1–2 weeks)

- [ ] SEC-* automated  
- [ ] Tenant isolation extended scenarios  
- [ ] Idempotency + concurrency cases (NEG-003/004)  

**Exit:** Critical security cases in CI blocking merge.

### Phase 3 — Admin/Ops UI E2E (2–3 weeks)

- [ ] Complete frontend wiring for loan mutations (remove mock fallback in CI)  
- [ ] Playwright against live stack: ADM-*, OPS-*  
- [ ] Replace mock `loan-lifecycle.spec.ts` with backend-driven spec  

**Exit:** ADM-008, ADM-010, OPS-002 green in Playwright.

### Phase 4 — LSP UI + dashboards + reports (1–2 weeks)

- [ ] LSP-UI-* on `/my-loans`  
- [ ] DSH-*, RPT-* reconciliation tests  

**Exit:** Finance sign-off on RPT-001 + DSH-001.

### Phase 5 — Hardening & performance (ongoing)

- [ ] NEG-*, load tests, chaos on webhook/redis  
- [ ] Manual checklist automation where ROI clear  
- [ ] Deprecate mock layer per ADR-0001  

---

## Appendix A — Standard test case template (reference)

```text
Test case ID:
Module:
User role:
Scenario:
Preconditions:
Test data needed:
Steps:
Expected result:
Database/API validation:
Audit event validation:
Priority: Critical | High | Medium | Low
Automation type: Automated | Manual | Hybrid
Implementation status: FULL | PARTIAL | MOCKED | BROKEN | MISSING | UNCLEAR
```

## Appendix B — Key file references

| Area | Path |
|------|------|
| API spec | `docs/API-references/api-spec.md` |
| ADR frontend integration | `docs/adr/0001-adopt-frontend-2-direct-backend-integration.md` |
| Backend LSP controller | `backend/.../LspLoanApplicationApiController.java` |
| Backend ops controller | `backend/.../LoanApplicationOpsController.java` |
| Status enum | `backend/.../LoanApplicationStatus.java` |
| Webhook types | `backend/.../WebhookEventType.java` |
| Alert rules | `backend/.../AlertRuleDataInitializer.java` |
| frontend HTTP client | `frontend/src/lib/api/http-client.ts` |
| frontend routes | `frontend/src/routes/router.tsx` |
| Playwright (mock) | `frontend/e2e/loan-lifecycle.spec.ts` |
| Live frontend reference | `frontend/src/features/api/` |

## Appendix C — Suggested next review questions

1. Should OPS users approve/reject loans, or only SYSTEM_ADMIN?  
2. Is “overcharged loan” alert in scope? What defines overcharge?  
3. Is LSP foreclosure execute required on API timeline?  
4. Single canonical frontend for E2E: `frontend` after wiring or `frontend` today?  
5. Real disbursement provider timeline and sandbox availability?  

---

*End of document.*
