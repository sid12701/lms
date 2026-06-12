# Postman E2E Collection Plan — Admin and LSP

**Collection name:** `LMS End-to-End Testing - Admin and LSP`  
**Generated artifacts:**

- `postman/LMS-E2E-Testing-Admin-and-LSP.postman_collection.json`
- `postman/LMS-E2E-Local.postman_environment.json`
- Generator: `postman/_build_e2e_admin_lsp_collection.py`

**Related:** Runnable demo collection `postman/LMS.postman_collection.json` (folders 0–11, payment loop, MIS poll).

---

## 1. Collection name

`LMS End-to-End Testing - Admin and LSP`

---

## 2. Folder structure

| Folder | Purpose |
|--------|---------|
| 00 - Environment Health | Actuator + metadata probe |
| 01 - Authentication | Login, refresh, password, token, logout |
| 02 - Admin - User and Role Setup | Users CRUD, session revoke |
| 03 - Admin - LSP Management | LSP CRUD, webhook, options |
| 04 - LSP - Authentication / Credential Validation | Client credentials + product catalog |
| 05 - LSP - Borrower / Customer Creation | Loan intake creates borrower; bank details |
| 06 - LSP - Loan Creation | Origination, invalidate, ops intake |
| 07 - LSP - Document Upload | List, upload, admin download |
| 08 - Admin - Loan Review and Approval | Transitions, override, history |
| 09 - Disbursement Flow | Schedule, bank check, disburse, mock |
| 10 - Repayment Flow | Ops + LSP payments |
| 11 - Foreclosure / Closure Flow | Quote + execute (ops + LSP) |
| 12 - Dashboard and Reports | Home KPI, MIS sync/async |
| 13 - Audit Logs | Explorer, auth audit, doc access, webhooks |
| 14 - Negative and Edge Cases | Auth/RBAC/idempotency + **typed API errors** (`assertApiError(status, code)` for B4/F2) |

---

## 3. Request-by-request plan

Legend: **Confirmed** = verified in `backend/src/main/java/com/bhawana/lms/web/*Controller.java`.  
**Needs confirmation** = body shape or optional feature not fully validated in this audit.

### 00 - Environment Health

| Folder | Request Name | Method | URL | Auth | Headers | Body | Pre-request | Test Script | Variables Set | Depends On |
|--------|--------------|--------|-----|------|---------|------|-------------|-------------|---------------|------------|
| 00 | Backend health | GET | `{{base_url}}/actuator/health` | None | X-Correlation-Id | — | base_url default | status=200, status UP | — | — |
| 00 | Admin metadata | GET | `{{base_url}}/api/v1/internal/admin/metadata` | Bearer admin | Authorization | — | — | 200, roles array | — | 01 Admin login |

### 01 - Authentication

| Folder | Request Name | Method | URL | Auth | Headers | Body | Pre-request | Test Script | Variables Set | Depends On |
|--------|--------------|--------|-----|------|---------|------|-------------|-------------|---------------|------------|
| 01 | Admin login | POST | `/api/v1/auth/login` | None | Content-Type JSON | username, password | — | 200, accessToken | `admin_token` | — |
| 01 | Session context | GET | `/api/v1/internal/system/context` | Bearer admin | Authorization | — | — | 200, roles | — | Admin login |
| 01 | Refresh token | POST | `/api/v1/auth/refresh` | Cookie | lms-refresh cookie | — | — | 200, new token | `admin_token` | Admin login |
| 01 | Change password | POST | `/api/v1/auth/password` | Bearer admin | JSON | newPassword | — | 200 | updates password | Admin login |
| 01 | LSP API client token | POST | `/api/v1/auth/token` | None | JSON | client_id, secret, grant_type | — | 200, access_token | `lsp_access_token` | 03 API client |
| 01 | Logout | POST | `/api/v1/auth/logout` | Bearer admin | — | — | — | 204/200 | clears session | Admin login |

**Assertions:** status code; token string non-empty; optional JWT decode for `lspId` / roles.

### 02 - Admin - User and Role Setup

| Folder | Request Name | Method | URL | Auth | Body highlights | Variables Set | Depends On |
|--------|--------------|--------|-----|------|-----------------|---------------|------------|
| 02 | List users | GET | `/api/v1/internal/admin/users` | SYSTEM_ADMIN | — | — | Admin login |
| 02 | Create LSP UI user | POST | `/api/v1/internal/admin/users` | SYSTEM_ADMIN | username, lspId, roles LSP_UI_WRITE | `user_id` | lsp_id |
| 02 | Revoke sessions | POST | `/api/v1/internal/admin/users/{{user_id}}/revoke-sessions` | SYSTEM_ADMIN | — | — | user_id |

### 03 - Admin - LSP Management

| Folder | Request Name | Method | URL | Auth | Variables Set | Depends On |
|--------|--------------|--------|-----|------|---------------|------------|
| 03 | Create LSP | POST | `/api/v1/internal/admin/lsps` | SYSTEM_ADMIN | `lsp_id`, `tenant_id` | Admin login |
| 03 | List LSPs | GET | `/api/v1/internal/admin/lsps` | SYSTEM_ADMIN | — | — |
| 03 | Get LSP | GET | `/api/v1/internal/admin/lsps/{{lsp_id}}` | SYSTEM_ADMIN | — | lsp_id |
| 03 | Update LSP status | PUT | `.../lsps/{{lsp_id}}/status` | SYSTEM_ADMIN | — | lsp_id |
| 03 | Webhook subscription | PUT | `.../webhook-subscription` | SYSTEM_ADMIN | — | lsp_id, callback_url |
| 03 | LSP options | GET | `/api/v1/internal/admin/lsp-options` | SYSTEM_ADMIN | — | — |

**Also needed (add to runner):** `POST /api/v1/internal/admin/api-clients` (folder 04 overlap), IP allowlist CRUD on `.../api-ip-allowlist` and `.../ui-ip-allowlist`.

### 04 - LSP - Authentication / Credential Validation

| Folder | Request Name | Method | URL | Auth | Variables Set | Depends On |
|--------|--------------|--------|-----|------|---------------|------------|
| 04 | LSP API client token | POST | `/api/v1/auth/token` | — | `lsp_access_token` | api client created |
| 04 | List LSP products | GET | `/api/v1/lsp/products` | LSP token | — | product mapping |

### 05 - LSP - Borrower / Customer Creation

| Folder | Request Name | Method | URL | Auth | Notes | Variables Set |
|--------|--------------|--------|-----|------|-------|---------------|
| 05 | Create loan (creates borrower) | POST | `/api/v1/lsp/loan-applications` | LSP_API_CLIENT | Idempotency-Key | `application_id`, `borrower_id` |
| 05 | Get bank details | GET | `/api/v1/lsp/borrowers/{{borrower_id}}/bank-details` | LSP | — | — |
| 05 | Patch bank details | PATCH | `.../bank-details` | LSP_API_CLIENT | IFSC validation | — |
| 05 | Admin search borrowers | GET | `/api/v1/internal/admin/borrowers?q={{test_pan}}` | SYSTEM_ADMIN | — | — |

### 06 - LSP - Loan Creation

| Folder | Request Name | Method | URL | Auth | Notes |
|--------|--------------|--------|-----|------|-------|
| 06 | Create loan application | POST | `/api/v1/lsp/loan-applications` | LSP_API_CLIENT | Confirmed |
| 06 | Get application | GET | `/api/v1/lsp/loan-applications/{{application_id}}` | LSP | Confirmed |
| 06 | Get by external id | GET | `/api/v1/lsp/loan-applications/external/{{external_loan_id}}` | LSP | Confirmed |
| 06 | Invalidate | POST | `.../invalid` | LSP_API_CLIENT or LSP_UI_WRITE | Idempotency-Key + reasonCode |
| 06 | Ops create loan | POST | `/api/v1/internal/ops/loan-applications` | SYSTEM_ADMIN, OPS_USER | **Needs confirmation** — full `LoanApplicationRequest` body in collection is stub |

### 07 - LSP - Document Upload

| Folder | Request Name | Method | URL | Auth | Notes |
|--------|--------------|--------|-----|------|-------|
| 07 | List submitted docs | GET | `.../documents` | LSP | Confirmed |
| 07 | Upload (JSON) | POST | `.../documents` | LSP_UI_WRITE | JSON path; multipart preferred |
| 07 | Upload (multipart) | POST | `.../documents` | LSP_UI_WRITE | **Add in Postman desktop** — use `postman/assets/*.pdf` |
| 07 | Batch upload | POST | `.../documents/batch` | LSP_UI_WRITE | Confirmed endpoint; not in generated JSON |
| 07 | Admin list KYC | GET | `.../ops/.../kyc-documents` | OPS | Confirmed |
| 07 | Download KYC | GET | `.../kyc-documents/PAN_CARD/content` | OPS | Confirmed |

### 08 - Admin - Loan Review and Approval

| Folder | Request Name | Method | URL | Auth | Notes |
|--------|--------------|--------|-----|------|-------|
| 08 | List applications | GET | `/api/v1/internal/ops/loan-applications` | OPS/ADMIN | Confirmed |
| 08 | Get detail | GET | `.../{{application_id}}` | OPS/ADMIN | Confirmed |
| 08 | Transition AWAITING_APPROVAL | POST | `.../status-transitions` | SYSTEM_ADMIN effective | OPS gets 403 in service |
| 08 | Transition APPROVED_PENDING_DISBURSAL | POST | `.../status-transitions` | SYSTEM_ADMIN | After auto-approval or manual |
| 08 | Manual override | POST | `.../manual-status` | SYSTEM_ADMIN only | Confirmed |
| 08 | Status history | GET | `.../status-transitions` | OPS/ADMIN | Confirmed |

### 09 - Disbursement Flow

| Folder | Request Name | Method | URL | Auth | Notes |
|--------|--------------|--------|-----|------|-------|
| 09 | Submit repayment schedule | PUT | `.../lsp/.../repayment-schedule` | LSP_API_CLIENT | **Needs confirmation** — installment array math |
| 09 | Disbursement bank check | POST | `.../disbursement-bank-check` | LSP_API_CLIENT | Confirmed |
| 09 | Initiate disbursement | POST | `.../disbursement-requests` | SYSTEM_ADMIN | Confirmed |
| 09 | Mock outcome | POST | `.../mock-outcome` | SYSTEM_ADMIN | Local profile only |
| 09 | List disbursements | GET | `.../disbursement-requests` | OPS/ADMIN | Confirmed |
| 09 | Get schedule | GET | `.../repayment-schedule` | OPS/ADMIN | Confirmed |

### 10 - Repayment Flow

| Folder | Request Name | Method | URL | Auth | Notes |
|--------|--------------|--------|-----|------|-------|
| 10 | Record payment (ops) | POST | `.../payments` | **SYSTEM_ADMIN** | Idempotency-Key required |
| 10 | List payments | GET | `.../payments` | OPS/ADMIN | Confirmed |
| 10 | LSP payment | POST | `/api/v1/lsp/loans/{{loan_id}}/payments` | LSP_API_CLIENT | Confirmed |
| 10 | LSP list payments | GET | `/api/v1/lsp/loans/{{loan_id}}/payments` | LSP | Confirmed |

**Assertion strategy:** exact installment amount; channel ∈ {NEFT, RTGS, IMPS}; status transitions DISBURSED→UNDER_REPAYMENT→CLOSED.

### 11 - Foreclosure / Closure Flow

| Folder | Request Name | Method | URL | Auth | Variables Set |
|--------|--------------|--------|-----|------|---------------|
| 11 | Foreclosure quote (ops) | POST | `.../foreclosure-quotes` | SYSTEM_ADMIN | `foreclosure_quote_id` |
| 11 | Foreclosure quote (LSP) | POST | `/api/v1/lsp/loans/{{loan_id}}/foreclosure-quote` | LSP | quote id |
| 11 | Execute (ops) | POST | `.../foreclosure-quotes/{{foreclosure_quote_id}}/execute` | SYSTEM_ADMIN | — |
| 11 | Execute (LSP) | POST | `/api/v1/lsp/loans/{{loan_id}}/foreclosure-quotes/{{foreclosure_quote_id}}/execute` | LSP | — |

### 12 - Dashboard and Reports

| Folder | Request Name | Method | URL | Auth |
|--------|--------------|--------|-----|------|
| 12 | Home overview | GET | `/api/v1/internal/home/overview` | SYSTEM_ADMIN |
| 12 | MIS preview | GET | `/api/v1/internal/reports/portfolio-mis/preview` | SYSTEM_ADMIN |
| 12 | MIS sync CSV | GET | `/api/v1/internal/reports/portfolio-mis` | SYSTEM_ADMIN |
| 12 | Queue async MIS | POST | `/api/v1/internal/reports/portfolio-mis/requests` | SYSTEM_ADMIN |
| 12 | List requests | GET | `/api/v1/internal/reports/requests` | SYSTEM_ADMIN |
| 12 | Download | GET | `/api/v1/internal/reports/requests/{{report_request_id}}/download` | SYSTEM_ADMIN |

### 13 - Audit Logs

| Folder | Request Name | Method | URL | Auth |
|--------|--------------|--------|-----|------|
| 13 | Audit explorer | GET | `/api/v1/internal/admin/audit-events` | SYSTEM_ADMIN |
| 13 | Auth audit | GET | `/api/v1/internal/ops/auth-audit` | SYSTEM_ADMIN |
| 13 | Document access audits | GET | `.../document-access-audits` | OPS/ADMIN |
| 13 | Webhook events | GET | `.../webhook-events` | OPS/ADMIN |

### 14 - Negative and Edge Cases

| Folder | Request Name | Method | URL | Expected |
|--------|--------------|--------|-----|----------|
| 14 | Wrong password | POST | `/api/v1/auth/login` | 401 |
| 14 | No auth | GET | `/api/v1/internal/ops/loan-applications` | 401 |
| 14 | LSP on admin route | GET | `/api/v1/internal/admin/lsps` | 403 |
| 14 | OPS on reports | GET | `/api/v1/internal/reports/portfolio-mis/preview` | 403 |
| 14 | Payment no Idempotency-Key | POST | `.../payments` | 400 |

Extend from matrix Edge Cases EC-001..EC-110.

---

## 4. Environment variable list

| Variable | Purpose | Source | Required For | Manual / Auto | Notes |
|----------|---------|--------|--------------|---------------|-------|
| `base_url` | API root | Default localhost:8080 | All | Manual | |
| `frontend_url` | UI root | Default 5173 | Screen verification | Manual | |
| `admin_email` | Login username | Bootstrap ops.admin | Admin auth | Manual | Field is username not email |
| `admin_password` | Login password | Bootstrap / rotated | Admin auth | Manual | |
| `admin_new_password` | Rotation target | Plan | First login | Manual | |
| `admin_token` | Bearer JWT | Login test script | Admin APIs | Auto | |
| `lsp_client_id` | Machine id | Create API client | LSP auth | Auto | |
| `lsp_client_secret` | Machine secret | Create API client | LSP auth | Auto | One-time at create |
| `lsp_access_token` | LSP JWT | Token exchange | LSP APIs | Auto | |
| `lsp_id` / `tenant_id` | Tenant scope | Create LSP | Origination | Auto | |
| `loan_product_id` | Product FK | Create product | Origination | Auto | |
| `application_id` / `loan_id` | Loan FK | Create application | Lifecycle | Auto | |
| `borrower_id` / `customer_id` | Borrower FK | Create application | Bank details | Auto | |
| `external_loan_id` | LSP external ref | Prerequest timestamp | Search | Auto | |
| `test_pan` | Borrower PAN | Plan | Dedup tests | Manual | Unique per run if needed |
| `test_aadhaar` | Aadhaar | Plan | Intake | Manual | |
| `test_mobile` | Mobile | Plan | Intake | Manual | |
| `test_bank_account` | Bank acct | Plan | Intake / patch | Manual | |
| `ifsc_code` | IFSC | Plan | Bank validation | Manual | |
| `amount` | Principal | Plan | Origination | Manual | Within product bounds |
| `idempotency_key` | Intake idempotency | Prerequest GUID | POST application | Auto | |
| `payment_idempotency_key` | Payment idempotency | Prerequest GUID | POST payments | Auto | |
| `target_installment_id` | Payment target | Schedule GET | Payments | Auto | |
| `installment_amount` | Exact due | Schedule GET | Payments | Auto | |
| `foreclosure_quote_id` | Quote FK | Quote POST | Execute | Auto | |
| `report_request_id` | Report job | Async MIS POST | Download | Auto | |
| `callback_url` | Webhook URL | webhook.site | Webhook tests | Manual | |
| `ops_token` | OPS_USER JWT | Separate login | Negative RBAC | Auto | |

---

## 5. Auth / token handling approach

1. **Collection pre-request:** set `X-Correlation-Id` = GUID; default `base_url`.
2. **Admin chain:** Folder 01 login → capture `admin_token` → `Authorization: Bearer {{admin_token}}` on internal routes.
3. **Password rotation:** if `passwordChangeRequired`, inline `POST /auth/password` (see existing `LMS.postman_collection.json` for full script).
4. **LSP machine chain:** Create API client (admin) → `POST /auth/token` → `lsp_access_token` on `/api/v1/lsp/*`.
5. **LSP UI chain (optional):** Create user → login → `lsp_ui_token` for UI-write document paths.
6. **Logout:** clears tokens; refresh cookie invalidated.

---

## 6. Data chaining approach

```
Admin login → create LSP → create product → map product → create API client
  → LSP token → create loan → upload docs → transitions → schedule → disburse
  → GET schedule → loop payments → CLOSED / foreclosure
```

Variables written in test scripts at each step; Collection Runner order matters. For payment loops, reuse pattern from `postman/LMS.postman_collection.json` folder 8 (`postman.setNextRequest`).

---

## 7. Assertion strategy

| Layer | Checks |
|-------|--------|
| HTTP | `pm.response.to.have.status(expected)` |
| Auth | Token present; 401/403 on negatives |
| Business | `status` field matches state machine |
| Schema | Required fields: `applicationId`, `id`, `clientId` |
| Idempotency | Replay same key → same response; mismatch → 409 |
| PII | MIS preview masks aadhaar `XXXXXXXX####` |
| Side effects | Follow-up GET confirms persistence |

---

## 8. Negative testing strategy

- Folder **14** samples: auth failure, missing auth, role bypass, missing Idempotency-Key.
- Map remaining **110 edge cases** from `e2e-test-matrix.xlsx` into folder 14 subfolders by module.
- Run negatives **after** happy-path data exists (separate environment or disposable loan ids).
- Rate-limit cases: enable `APP_RATE_LIMIT_ENABLED=true` + Redis.

---

## 9. Screen verification mapping

See `docs/e2e-testing-readiness-review.md` § API-to-Screen Verification Map.

**Manual UI checklist (minimum):**

1. Admin loan detail after folder 06–09 — status, documents, schedule.
2. Document download via UI — matches uploaded PDFs.
3. Reports MIS download — matches `report_request_id`.
4. LSP `/my-loans` — tenant isolation.
5. `/alerts` — acknowledge after escalation.
6. `/audit` — events for login, transition, doc download.

---

## 10. Open questions

| # | Question | Impact |
|---|----------|--------|
| 1 | Postman MCP OAuth — complete in Cursor? | Cloud sync / MCP runCollection |
| 2 | Is `ops.admin` password already rotated in target DB? | First login / Postman env |
| 3 | R2 vs FILE_SYSTEM for local uploads? | UC-019 blocked without FILE_SYSTEM |
| 4 | Should OPS_USER post repayments (UI) or is 403 expected? | G-004 RBAC |
| 5 | In-flight loans when LSP deactivated? | UC-007 / EC-030 |
| 6 | Full 8-document upload required before auto-approval? | UC-018 / EC-018 |
| 7 | Use demo collection runner or new 00–14 collection as primary? | Test maintenance |
| 8 | Remote Supabase vs local Docker Postgres? | `.env` LMS_DB_URL |

---

## Postman MCP status (2026-06-10)

| Item | Status |
|------|--------|
| Server | `plugin-postman-postman` registered in Cursor |
| Tools visible | Only `mcp_auth` until authenticated |
| Auth attempt | **Timed out after 2 minutes** — user action required |
| After auth (expected) | `getWorkspaces`, `getCollections`, `runCollection`, `createCollection`, etc. per Postman skill |
| Local workaround | Import JSON from `postman/` directory; run via Postman desktop or `npx newman` |

**Action:** When Cursor prompts for Postman OAuth, approve it. Then collections can be pushed/synced to your Postman workspace via MCP instead of manual import only.
