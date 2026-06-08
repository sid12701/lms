# LMS API Specification

Detailed response and error examples are documented in [api-standards.md](C:/Users/LENOVO/Desktop/Folders/LMS/docs/API-references/api-standards.md).

## Response Conventions

- Single-resource success responses return the resource payload directly.
- Collection responses return arrays directly; when `paginationDetails=ON`, pagination metadata is emitted through `X-Total-Count`, `X-Limit`, and `X-Offset` headers.
- All error responses use a consistent structure with backward-compatible fields:
  - `code` / `error` / `errorReason`: machine-readable error identifier
  - `message` / `errorSource`: human-readable summary
  - `errors[]`: structured error details for field-level or business-rule issues
  - `violations[]`: backward-compatible field violation list
  - `correlationId`: request correlation identifier for tracing
  - `status`, `path`, `timestamp`: transport and debugging metadata

## Authentication (`/api/v1/auth`) - Public

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| POST | `/login` | User login with username and password | None |
| POST | `/token` | API client token issuance with client credentials | None |
| POST | `/refresh` | Rotate refresh cookie and issue new access JWT. On failure returns `401` with `{ code, message }` (`MISSING_REFRESH_COOKIE`, `TOKEN_EXPIRED`, `TOKEN_REVOKED`, `REFRESH_INVALID`). | Valid refresh cookie (`lms-refresh`) |
| POST | `/password` | Change password | Valid JWT + `pwdchg` flag |

---

## LSP External APIs (`/api/v1/lsp/...`)

### Loan Applications (`/api/v1/lsp/loan-applications`)

| Method | Endpoint | Description | Roles |
|--------|----------|-------------|-------|
| GET | `/` | List applications (filters: productId, status, sourceChannel, query; optional pagination: `offset`, `limit`, `paginationDetails`) | LSP_API_CLIENT, LSP_UI_READ, LSP_UI_WRITE |
| GET | `/invalid-reasons` | List allowed invalid-loan reason options for LSP clients and portals | LSP_API_CLIENT, LSP_UI_READ, LSP_UI_WRITE |
| GET | `/{applicationId}` | Get application detail | LSP_API_CLIENT, LSP_UI_READ, LSP_UI_WRITE |
| GET | `/external/{externalLoanId}` | Get application by external loan ID | LSP_API_CLIENT, LSP_UI_READ, LSP_UI_WRITE |
| GET | `/{applicationId}/borrower-pii` | Reveal full borrower PII for a single application and create an audit trail entry | LSP_API_CLIENT, LSP_UI_WRITE |
| POST | `/` | Create new loan application | LSP_API_CLIENT |
| POST | `/{applicationId}/invalid` | Mark a loan application invalid using a constrained reason catalog (`REASON_A`, `REASON_B`, `REASON_C`, `OTHERS`) and a required `Idempotency-Key` UUID v4 header | LSP_API_CLIENT, LSP_UI_WRITE |
| POST | `/{applicationId}/documents` | Submit document metadata (legacy compatibility) | LSP_API_CLIENT, LSP_UI_WRITE |
| POST | `/{applicationId}/documents/batch` (`multipart/form-data`) | Upload one or more documents into LMS-managed storage in a single API call | LSP_API_CLIENT, LSP_UI_WRITE |
| PUT | `/{applicationId}/repayment-schedule` | Generate or replace repayment schedule before disbursement | LSP_API_CLIENT |
| POST | `/{applicationId}/disbursement` | Request disbursement with compliance checks on documents, schedule, and deduction cap | LSP_API_CLIENT |

**Create Application Request fields:** borrower info (name, email, mobile, DOB, gender, marital status, father name, Aadhaar, PAN), address, employment, income, bank details, reference person, loan details (amount, tenure, productId, lspLoanId)

**PII exposure rule:** normal LSP list/detail responses are masked by default for high-risk borrower fields including Aadhaar, PAN, bank account number, IFSC, account holder name, employee ID, and reference contact details. Full values are available only from `GET /{applicationId}/borrower-pii`, which is role-restricted and audited.

**Invalidation request body:** `reasonCode` is required. `reasonText` is required only when `reasonCode=OTHERS`, and must not be sent for the other reason codes.

**Pagination metadata:** when `paginationDetails=ON`, the response includes `X-Total-Count`, `X-Limit`, and `X-Offset` headers. Pagination defaults to `offset=0` and `limit=50` when any pagination parameter is used.

### Provisioned Products (`/api/v1/lsp/products`)

| Method | Endpoint | Description | Roles |
|--------|----------|-------------|-------|
| GET | `/` | List active products provisioned to the authenticated LSP | LSP_API_CLIENT, LSP_UI_READ, LSP_UI_WRITE |

### Loans (`/api/v1/lsp/loans`)

| Method | Endpoint | Description | Roles |
|--------|----------|-------------|-------|
| GET | `/{loanId}` | Get loan detail | LSP_API_CLIENT, LSP_UI_READ, LSP_UI_WRITE |
| GET | `/{loanId}/repayment-schedule` | List repayment schedule | LSP_API_CLIENT, LSP_UI_READ, LSP_UI_WRITE |
| GET | `/{loanId}/payments` | List payment transactions | LSP_API_CLIENT, LSP_UI_READ, LSP_UI_WRITE |
| POST | `/{loanId}/foreclosure-quote` | Request foreclosure quote | LSP_API_CLIENT, LSP_UI_WRITE |

---

## Internal APIs (`/api/v1/internal/...`)

### System Context (`/internal/system`)

| Method | Endpoint | Roles |
|--------|----------|-------|
| GET | `/context` | SYSTEM_ADMIN, OPS_USER, PRODUCT_ADMIN, LSP_UI_READ, LSP_UI_WRITE |

### Admin Metadata (`/internal/admin/metadata`) - SYSTEM_ADMIN

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/` | Get admin metadata |

### Home Dashboard (`/internal/home`) - SYSTEM_ADMIN

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/overview` | Portfolio dashboard summary |

### User Management (`/internal/admin/users`) - SYSTEM_ADMIN

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/` | List all users |
| POST | `/` | Create user (username, email, password, status, lspId, roles) |
| POST | `/{userId}/reset-password` | Reset user password |

### API Client Management (`/internal/admin/api-clients`) - SYSTEM_ADMIN

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/` | List API clients |
| POST | `/` | Create API client (name, description, lspId, status) |

### LSP Management (`/internal/admin/lsps`) - SYSTEM_ADMIN

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/` | List all LSPs |
| GET | `/{lspId}` | Get LSP detail |
| POST | `/` | Create LSP (code, name, status) |
| PUT | `/{lspId}/status` | Change operational status (`ACTIVE` / `INACTIVE`; `DISABLED` alias). Body: `status`, `reason` (`SECURITY_INCIDENT` \| `COMPLIANCE` \| `OFFBOARDING` \| `OPERATIONAL`), `note`. Disable revokes JWTs and deactivates API clients. |
| GET | `/{lspId}/audit-events` | List LSP status-change audit events (newest first) |
| PUT | `/{lspId}/webhook-subscription` | Update webhook config (enabled, endpointUrl, signingSecret, eventTypes) |

### Product Management (`/internal/admin/products`) - SYSTEM_ADMIN, PRODUCT_ADMIN

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/` | List products |
| GET | `/{productId}` | Get product detail |
| POST | `/` | Create product (code, name, min/max principal, interest rate, processing fee, min/max tenure, status) |
| PUT | `/{productId}` | Update product |
| GET | `/{productId}/mappings` | Get LSP mappings for product |
| PUT | `/{productId}/mappings` | Replace LSP mappings |
| GET | `/{productId}/audit-events` | List product audit events |

### Product-LSP Mappings (`/internal/admin/product-lsp-mappings`) - SYSTEM_ADMIN, PRODUCT_ADMIN

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/` | List all mappings |
| GET | `/entries` | List all mapping entries |
| PUT | `/{productId}` | Replace mappings for product |
| POST | `/entries` | Upsert single mapping entry (lspId, productId, enabled) |

### Webhook Outbox (`/internal/admin/webhook-outbox`) - SYSTEM_ADMIN

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/` | List outbox events (optional lspId filter) |
| POST | `/dispatch` | Dispatch pending events (batchSize, default=20) |

### Reports (`/internal/reports`) - SYSTEM_ADMIN

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/portfolio-mis` | Download portfolio MIS report (CSV) - filters: lspId, disbursalDateFrom/To |
| POST | `/portfolio-mis/requests` | Create async report request (+ recipientEmail) |
| GET | `/requests` | List all report requests |
| GET | `/requests/{requestId}/download` | Download generated report |

### Loan Application Ops (`/internal/ops/loan-applications`) - SYSTEM_ADMIN, OPS_USER

| Method | Endpoint | Description | Auth Override |
|--------|----------|-------------|---------------|
| GET | `/` | List applications (filters: lspId, productId, status, sourceChannel, query, disbursalDateFrom/To; optional pagination: `offset`, `limit`, `paginationDetails`) | Default |
| GET | `/{applicationId}` | Get application detail | Default |
| GET | `/{applicationId}/intake-audits` | List intake audit trail | Default |
| GET | `/{applicationId}/status-transitions` | List status transition history | Default |
| GET | `/{applicationId}/assignment-events` | List assignment history | Default |
| GET | `/{applicationId}/audit-events` | List audit events | Default |
| GET | `/{applicationId}/document-access-audits` | List document access audits | Default |
| GET | `/{applicationId}/disbursement-requests` | List disbursement requests | Default |
| GET | `/{applicationId}/repayment-schedule` | List repayment schedule | Default |
| GET | `/{applicationId}/payments` | List payment transactions | Default |
| GET | `/{applicationId}/foreclosure-quotes` | List foreclosure quotes | SYSTEM_ADMIN |
| GET | `/{applicationId}/kyc-documents` | List KYC document checklist | Default |
| POST | `/` | Create application (lspId, productId, externalLoanId, sourceChannel, borrower data, amount, tenure) | Default |
| POST | `/{applicationId}/status-transitions` | Transition status (targetStatus, note, reasonCode) | Default (role-based) |
| POST | `/{applicationId}/manual-status` | Manual status override | SYSTEM_ADMIN |
| POST | `/{applicationId}/assignment` | Assign to user (assigneeUsername, note) | Default |
| PUT | `/{applicationId}/kyc-documents/{documentType}` | Update document checklist item | Default |
| POST | `/{applicationId}/disbursement-requests` | Initiate disbursement | SYSTEM_ADMIN |
| POST | `/{applicationId}/disbursement-requests/mock-outcome` | Apply mock disbursement outcome | SYSTEM_ADMIN |
| POST | `/{applicationId}/payments` | Record payment (amount, paymentDate, reference, channel, status, note) | SYSTEM_ADMIN |
| POST | `/{applicationId}/foreclosure-quotes` | Request foreclosure quote | SYSTEM_ADMIN |
| POST | `/{applicationId}/foreclosure-quotes/{quoteId}/execute` | Execute foreclosure (settlementDate, reference, note) | SYSTEM_ADMIN |

---

## Security Model

**Auth methods:**
- User/password -> `POST /api/v1/auth/login` -> JWT bearer token
- API client credentials (client_id/secret) -> `POST /api/v1/auth/token` -> JWT with `LSP_API_CLIENT` role

**Roles:**
- `SYSTEM_ADMIN` - Full access
- `OPS_USER` - Limited ops workflow
- `PRODUCT_ADMIN` - Product configuration
- `LSP_API_CLIENT` - External API access (scoped to LSP)
- `LSP_UI_READ` - LSP portal read access
- `LSP_UI_WRITE` - LSP portal write access

**JWT LSP claims:** `lspId`, `lspCode`, `lspName`, `authType` (`API_CLIENT` or user), `pwdchg`

---

**Total: 14 controllers, 70+ endpoints.** Swagger UI available at `/swagger-ui.html` via SpringDoc.
