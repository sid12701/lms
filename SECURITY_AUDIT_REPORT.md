# Security Audit Report

Audit date: 2026-05-27

Scope: static review of the Spring Boot backend, React frontends, API controllers, security middleware, tenant isolation implementation, database migrations, document storage, reporting, disbursement flows, configuration, and committed environment files. This review did not execute a live penetration test or attempt transactions against external services.

## Executive Summary

This Loan Management System is not production ready for fintech/NBFC use. The current tree contains committed database and object-storage credentials, default bootstrap administrator credentials, a default local profile that can point at hosted infrastructure, mock disbursement code wired into the main runtime, plaintext storage and export of highly sensitive borrower data, and incomplete enforcement of API-client network controls and token revocation.

The backend has several good foundations: most controllers use `@PreAuthorize`, LSP routes have a tenant context interceptor, PostgreSQL RLS migrations exist, LSP create/payment flows use idempotency in some places, and document filenames are sanitized before storage. Those controls are not enough to offset the production blockers below.

The highest risk areas are:

- Secrets and bootstrap access: committed DB/R2 credentials plus default `ops.admin` credentials create immediate account and infrastructure compromise risk.
- Disbursement: the main runtime uses a mock disbursement adapter and exposes a mock outcome endpoint. LSP clients can trigger auto-approval and disbursement without independent maker-checker control.
- Customer data: Aadhaar, PAN, bank account details, addresses, income, and reference details are stored and exported in plaintext, and some audit payloads duplicate that PII.
- API-client lifecycle: disabling/rotating API clients does not reliably invalidate existing or refreshable tokens, and configured per-client IP allowlists are not enforced.
- Frontend: JWT access tokens are stored in `localStorage`, and the newer frontend contains production-reachable mock fallback paths and seed-user role previews.

## Overall Security Rating

**Critical - Not production ready.**

Production use should be blocked until Phase 1 remediation is complete and independently retested.

## Production Blockers

1. Rotate all committed database, R2/object-storage, JWT, bootstrap, tenant DB-role, demo, and API credentials. Treat them as compromised.
2. Remove hardcoded/default bootstrap administrator credentials and fail startup when production uses default or local security values.
3. Disable mock disbursement adapter, mock outcome endpoint, and local demo disbursement seed behavior outside isolated local/test profiles.
4. Implement real bank-grade disbursement controls: maker-checker, idempotency, immutable state machine, bank acknowledgement verification, signed/mTLS bank integration, and reconciliation.
5. Encrypt/tokenize Aadhaar, PAN, bank account numbers, and KYC metadata at field level; redact audit payloads and reports by default.
6. Enforce API-client and LSP active status, per-client IP allowlists, token revocation, and refresh-token invalidation on disable/secret rotation.
7. Remove production bundle access to frontend mocks, seed users, dev role previews, and 4xx-to-mock fallbacks.
8. Add complete audit logging for logins, failed logins, user creation/password resets, LSP creation, API client creation/secret reveal/rotation, role changes, loan approvals, disbursements, document downloads, and MIS/report downloads.

## Critical Findings

### C-01: Committed Secrets And Unsafe Default Configuration

**Evidence**

- `.env:3-5` contains a hosted PostgreSQL/Supabase JDBC URL, username, and password.
- `.env:8-9` contains bootstrap administrator username/password defaults.
- `.env:12-15` and `.env:24-26` contain R2 endpoint/access/secret material.
- `backend/src/main/resources/application-local.yml:3-5` defaults local DB access to the hosted Supabase database and a hardcoded password.
- `backend/src/main/resources/application-local.yml:47-54` defaults bootstrap credentials and a local JWT secret.
- `backend/src/main/resources/application.yml:38-45` defaults bootstrap credentials and JWT config.
- `backend/src/main/resources/application.yml:73-80` defines document storage and tenant DB-role credentials via environment defaults.

**Attack Scenario**

Anyone with repository access can connect to the hosted database and object store, inspect or alter loan/borrower data, upload or download KYC documents, or attempt administrator login using known bootstrap credentials. If a production deployment accidentally uses the default `local` profile or missing environment variables, the same defaults can become live production credentials.

**Recommended Fix**

Immediately rotate all exposed credentials. Remove secrets from repository history where feasible, block secret patterns in CI, use a secrets manager, require explicit production profiles, and fail startup when defaults such as `ChangeMe123!`, local JWT secrets, local DB URLs, or tenant DB-role defaults are present.

### C-02: Bootstrap Administrator Backdoor Risk

**Evidence**

- `backend/src/main/java/com/bhawana/lms/security/SecurityProperties.java:18` defines the default bootstrap password.
- `backend/src/main/java/com/bhawana/lms/security/SecurityConfig.java:75-83` falls back to bootstrap user details when the DB user is not found.
- `backend/src/main/java/com/bhawana/lms/security/SecurityConfig.java:291-299` grants bootstrap roles and encodes the bootstrap password.
- `backend/src/main/java/com/bhawana/lms/service/LocalBootstrapAdminSyncService.java` syncs the bootstrap administrator into the DB under the `local` profile.
- `backend/src/main/resources/application.yml:38-40` and `application-local.yml:47-49` default the bootstrap user to `ops.admin` with a default password.

**Attack Scenario**

An attacker who knows the repository defaults can authenticate as a SYSTEM_ADMIN in any environment where the bootstrap fallback or local sync is active. In local profile, the sync service can also reset an existing bootstrap user's password to the configured raw value.

**Recommended Fix**

Remove runtime bootstrap fallback. Replace it with a one-time, offline setup path with explicit operator approval, non-default secret injection, and environment guardrails. Production startup must fail when bootstrap credentials are configured or when a bootstrap user exists after initialization.

### C-03: Mock Disbursement Is Wired Into Main Runtime

**Evidence**

- `backend/src/main/java/com/bhawana/lms/service/MockLoanDisbursementAdapter.java:10` declares a `@Service` implementation of the disbursement adapter without a visible production-excluding profile guard.
- `backend/src/main/java/com/bhawana/lms/web/LoanApplicationOpsController.java:385-392` exposes `/disbursement-requests/mock-outcome` to SYSTEM_ADMIN.
- `backend/src/main/java/com/bhawana/lms/service/LoanApplicationService.java:689-753` initiates disbursement through the configured adapter.
- `backend/src/main/java/com/bhawana/lms/service/LoanApplicationService.java:757-819` resolves mock disbursement outcomes.
- `backend/src/main/java/com/bhawana/lms/service/LocalDemoPortfolioSeedService.java:237-292` creates demo disbursement outcomes.

**Attack Scenario**

A production instance can mark a disbursement as accepted, failed, or disbursed through mock-only code paths without bank confirmation. A compromised SYSTEM_ADMIN can manually push loan state to a successful mock outcome, creating false accounting and potentially masking or triggering real-money workflow errors.

**Recommended Fix**

Move mock disbursement classes and endpoints behind strict `local`/`test` profiles, exclude them from production builds, and make production startup fail if no real bank adapter is configured. Real adapters must require signed requests, mTLS or bank-approved equivalent, idempotency keys, immutable provider references, callback signature verification, and reconciliation before marking funds disbursed.

### C-04: Sensitive Customer And Bank Data Stored, Audited, And Exported In Plaintext

**Evidence**

- `backend/src/main/java/com/bhawana/lms/domain/Borrower.java:36`, `:57`, and `:102` define plaintext PAN, Aadhaar, and bank account fields.
- `backend/src/main/java/com/bhawana/lms/service/LoanApplicationLifecycleService.java:1031-1065` serializes borrower PII, including bank account details, into intake audit payloads.
- `backend/src/main/java/com/bhawana/lms/service/LoanApplicationService.java:1415-1449` serializes similar PII into audit payloads.
- `backend/src/main/java/com/bhawana/lms/service/AdminReportingService.java:376` masks Aadhaar but includes PAN and bank account values in report rows.
- `backend/src/main/java/com/bhawana/lms/service/AdminReportingService.java:605-608` writes bank account, Aadhaar, and PAN fields to CSV.
- `backend/src/main/java/com/bhawana/lms/web/ReportAdminController.java:67-104` exposes portfolio MIS download endpoints.

**Attack Scenario**

A database dump, insider query, audit export, report CSV, or backup compromise exposes Aadhaar numbers, PAN, bank accounts, IFSC codes, addresses, income, reference persons, and contact details. Audit tables expand the blast radius because they duplicate PII outside the primary borrower table.

**Recommended Fix**

Use field-level encryption/tokenization for Aadhaar, PAN, and bank accounts with KMS-backed keys. Store lookup hashes separately. Redact PII in audit payloads. Split report scopes into masked/default and approved full-PII exports. Add purpose capture, approval, watermarking, expiry, and audit logs for every reveal/export.

## High Findings

### H-01: API-Client IP Allowlists Are Stored But Not Enforced

**Evidence**

- `backend/src/main/java/com/bhawana/lms/service/ApiClientManagementService.java:179-188` stores per-API-client allowlist entries.
- `backend/src/main/java/com/bhawana/lms/service/ApiClientAuthenticationService.java:28-43` authenticates client ID/secret and checks only client status, not source IP or client allowlist.
- `backend/src/main/java/com/bhawana/lms/security/LspIpAllowlistFilter.java:109` loads LSP-level allowlist entries, not API-client allowlist entries.
- `backend/src/main/java/com/bhawana/lms/security/LspIpAllowlistFilter.java:61-65` allows all traffic when the LSP-level allowlist is empty.

**Attack Scenario**

If an API client secret is stolen, the attacker can authenticate from any IP even when the admin UI/API shows a configured client allowlist. If the parent LSP allowlist is empty, LSP traffic is allowed from all networks.

**Recommended Fix**

Enforce per-client allowlists during `/api/v1/auth/token` and every bearer-token request. Production LSP/API-client traffic should fail closed when allowlists are required but absent. Include source IP, forwarded header trust policy, and match result in authentication audit logs.

### H-02: Disabled Or Rotated API Clients Can Keep Using Tokens

**Evidence**

- `backend/src/main/java/com/bhawana/lms/service/ApiClientAuthenticationService.java:56` exposes `lookupByClientId` without the active-status check present in `authenticate`.
- `backend/src/main/java/com/bhawana/lms/web/AuthController.java:202` uses `lookupByClientId` while minting tokens from stored identity.
- `backend/src/main/java/com/bhawana/lms/security/SecurityConfig.java:235-287` validates managed `AppUser` session/password versions but does not apply equivalent status/version validation to `LSP_API_CLIENT` tokens.

**Attack Scenario**

An attacker with a compromised API-client refresh token can continue minting access tokens after the client is disabled or secret-rotated. Existing access tokens also remain valid until expiration because the resource server does not re-check API-client status.

**Recommended Fix**

Add API-client token versioning, status validation, secret-rotation invalidation, active LSP validation, and refresh-token revocation. Refresh and JWT validation must fail for inactive clients, inactive LSPs, rotated secrets, and revoked sessions.

### H-03: LSP-Initiated Auto-Approval Can Lead Directly To Disbursement

**Evidence**

- `backend/src/main/java/com/bhawana/lms/web/LspLoanApplicationApiController.java:399-406` exposes LSP disbursement initiation to `LSP_API_CLIENT`.
- `backend/src/main/java/com/bhawana/lms/service/LoanDisbursementService.java:34` handles LSP disbursement requests.
- `backend/src/main/java/com/bhawana/lms/service/LoanApplicationLifecycleService.java:353-391` auto-approves eligible LSP applications.
- `backend/src/main/java/com/bhawana/lms/service/LoanApplicationLifecycleService.java:452-464` checks required LMS-managed documents before approval, but no independent maker-checker approval is required.

**Attack Scenario**

A compromised LSP API client submits a loan, uploads documents that satisfy checklist rules, and calls disbursement. The service can auto-approve and initiate disbursement without an independent internal checker, allowing business-rule abuse to become a funds-movement event.

**Recommended Fix**

Require explicit maker-checker approval for production disbursements. The maker and checker must be distinct actors with distinct roles. Auto-approval may produce only a recommendation, not a disbursement-eligible state, unless a formally approved straight-through-processing policy exists with limits, auditability, and risk controls.

### H-04: JWT Access Tokens Stored In Browser `localStorage`

**Evidence**

- `frontend-2/src/lib/api/session-storage.ts:9-36` stores the active session, including access token, under `bhawana-lms-session` in `localStorage`.
- `frontend/src/features/api/session-storage.ts:3-40` stores the legacy auth session in `localStorage`.
- `frontend-2/src/lib/api/http-client.ts:143` adds authenticated headers for API calls.

**Attack Scenario**

Any XSS, compromised browser extension, third-party script injection, or shared machine compromise can read the access token and call backend APIs as the user. This is especially serious for SYSTEM_ADMIN, OPS, and LSP write users.

**Recommended Fix**

Keep access tokens in memory only or move to a backend-for-frontend/session-cookie model with HTTP-only, Secure, SameSite cookies and CSRF protection. Add strict CSP, dependency hygiene, XSS sanitization, and short access-token TTLs with step-up authentication for privileged actions.

### H-05: Frontend Mock And Seed-User Paths Are Production-Reachable

**Evidence**

- `frontend-2/src/features/auth/LoginPage.tsx:10` imports `SEED_USERS`.
- `frontend-2/src/features/auth/LoginPage.tsx:229` renders seed users in the login page.
- `frontend-2/src/features/auth/mock-session-bridge.ts:14-21` bridges live sessions into mock seed data.
- `frontend-2/src/app/providers.tsx:147-190` always includes `MockScenarioProvider`.
- `frontend-2/src/features/audit/api.ts:199`, `frontend-2/src/features/loan-applications/api.ts:238`, and `frontend-2/src/features/loan-applications/api-detail.ts:356-609` dispatch to mock routers/fallbacks.

**Attack Scenario**

Production users can see internal seed-user role information and may receive mock data after backend authorization or domain errors. This can mask real access denials, confuse operators, and create false confidence in operational workflows.

**Recommended Fix**

Exclude mocks, seed users, mock bridges, scenario providers, and 4xx-to-mock fallbacks from production builds with compile-time guards and bundler dead-code elimination. Production API clients must fail closed on 401/403/4xx.

### H-06: Document Upload Validation Trusts Declared MIME Type

**Evidence**

- `backend/src/main/java/com/bhawana/lms/service/DocumentUploadPolicy.java:19-43` validates size and `MultipartFile.getContentType()`.
- `backend/src/main/java/com/bhawana/lms/service/ConfigurableLoanDocumentStorageService.java:84-87` stores the sanitized filename and resolved declared content type.
- `backend/src/main/java/com/bhawana/lms/service/R2LoanDocumentStorageService.java:69-80` uploads objects with the supplied descriptor content type.
- `backend/src/main/java/com/bhawana/lms/service/ConfigurableLoanDocumentStorageService.java:142-159` sanitizes filenames, which is positive, but does not prove file content type.

**Attack Scenario**

An attacker uploads HTML, script, malware, or a polyglot file while declaring `application/pdf` or `image/png`. An internal user later downloads and opens it, creating malware or browser-content execution risk.

**Recommended Fix**

Validate magic bytes and parse content with Apache Tika or equivalent, run antivirus/malware scanning, quarantine pending files, generate server-side filenames and extensions, force download-only `Content-Disposition`, and avoid inline rendering of untrusted documents.

### H-07: Sensitive Audit Logging Is Incomplete

**Evidence**

- `backend/src/main/java/com/bhawana/lms/service/LoanDocumentService.java:40-59` retrieves document content and builds ZIPs without writing document-download audit records.
- `backend/src/main/java/com/bhawana/lms/web/LoanApplicationOpsController.java:201-238` exposes document ZIP and document content downloads.
- `backend/src/main/java/com/bhawana/lms/web/ReportAdminController.java:67-104` returns MIS/generated report downloads without a visible audit event.
- `backend/src/main/java/com/bhawana/lms/service/AdminDirectoryService.java:339` resets passwords without an actor/audit path in the method signature.
- `backend/src/main/java/com/bhawana/lms/web/UserAdminController.java:74-76` invokes password reset.
- `backend/src/main/java/com/bhawana/lms/service/ApiClientManagementService.java:60` creates clients, while update/rotate paths have stronger audit behavior.

**Attack Scenario**

An insider downloads KYC documents, exports MIS data, creates credentials, or resets a user password without a complete immutable trail. Incident response cannot reliably determine who accessed sensitive data or who enabled access.

**Recommended Fix**

Make audit logging mandatory and centralized for login success/failure, token issuance, user create/update/reset, role changes, LSP create/update/disable, API-client create/secret reveal/rotate/disable, allowlist changes, loan approval, disbursement, document view/download/ZIP download, report request/download, and webhook credential changes.

### H-08: Reports Export Raw PAN And Bank Details

**Evidence**

- `backend/src/main/java/com/bhawana/lms/service/AdminReportingService.java:376` masks Aadhaar only.
- `backend/src/main/java/com/bhawana/lms/service/AdminReportingService.java:605-608` emits bank account number, Aadhaar, and PAN CSV cells.
- `backend/src/main/java/com/bhawana/lms/web/ReportAdminController.java:67-104` exposes portfolio MIS and generated report downloads to SYSTEM_ADMIN.

**Attack Scenario**

A SYSTEM_ADMIN or compromised administrator account can bulk-export bank account and PAN data. Without download audit and masking by default, the export becomes a high-value exfiltration path.

**Recommended Fix**

Default all exports to masked PAN and bank-account values. Require purpose, approval, expiry, and audited break-glass for full PII. Add row-level export limits and anomaly alerts for large downloads.

## Medium Findings

### M-01: CSRF Disabled For Cookie-Based Refresh/Logout

**Evidence**

- `backend/src/main/java/com/bhawana/lms/security/SecurityConfig.java:131` disables CSRF.
- `backend/src/main/java/com/bhawana/lms/security/SecurityConfig.java:145-155` permits auth endpoints, including refresh/logout.
- `backend/src/main/java/com/bhawana/lms/web/AuthController.java:98` and `:155` expose refresh and logout endpoints.

**Risk**

The application uses HTTP-only refresh cookies. SameSite controls help, but cookie-authenticated endpoints should still have CSRF protections in a financial system, especially across subdomains, reverse proxies, and future browser behavior changes.

**Recommended Fix**

Add CSRF protection or double-submit tokens for cookie-authenticated endpoints. Keep CORS exact, cookies Secure/HttpOnly/SameSite, and validate Origin/Referer for sensitive browser flows.

### M-02: Rate Limiting Scope Is Too Narrow

**Evidence**

- `backend/src/main/java/com/bhawana/lms/security/RateLimitFilter.java` exists and is added in `SecurityConfig`.
- Current evidence shows rate limiting is focused on auth and some LSP write paths, not all privileged admin actions, report downloads, refresh, document downloads, or password resets.

**Risk**

Credential stuffing, token refresh abuse, report scraping, document scraping, and brute-force admin operations can occur with limited throttling.

**Recommended Fix**

Add per-user, per-client, per-IP, and per-tenant limits for login, refresh, password reset, document/report downloads, disbursement, payments, foreclosure, and admin mutations. Add account lockout and MFA for privileged roles.

### M-03: Error Responses Can Leak Operational Detail

**Evidence**

- `backend/src/main/java/com/bhawana/lms/web/GlobalExceptionHandler.java` returns validation and illegal argument details.
- Several service/controller paths include supplied IDs in "unknown id" style errors.

**Risk**

Attackers can use error shape and message differences for ID enumeration, state discovery, and validation-rule discovery.

**Recommended Fix**

Return generic 404/403 messages for ownership failures and unknown IDs. Keep detailed diagnostics in structured server logs with correlation IDs, not client responses.

### M-04: Tenant Isolation Has Strong RLS Foundations But Some Fail-Open Edges

**Evidence**

- `backend/src/main/java/com/bhawana/lms/config/TenantIsolationWebConfig.java:20` applies tenant context to `/api/v1/lsp/**`.
- `backend/src/main/java/com/bhawana/lms/web/LspTenantContextInterceptor.java:25-36` sets and clears LSP tenant context.
- `backend/src/main/java/com/bhawana/lms/tenant/TenantAwareDataSource.java:31-39` sets `app.current_lsp_id` in PostgreSQL.
- `backend/src/main/resources/db/migration/V41__tenant_isolation_rls.sql:223-240` enables RLS on tenant-sensitive tables.
- `backend/src/main/resources/db/migration/V41__tenant_isolation_rls.sql:261-363` defines tenant policies.
- `backend/src/main/resources/db/migration/V45__tenant_isolation_hardening.sql:22-59` hardens borrower tenant policies.
- `backend/src/main/java/com/bhawana/lms/tenant/TenantIsolationDataSourceConfig.java:22-52` creates tenant data sources; non-PostgreSQL/test environments can bypass RLS semantics.

**Risk**

The LSP path is protected by interceptor and RLS, but any missed LSP route, manual `useAdmin()` call, non-PostgreSQL profile, or missing tenant context can bypass the intended tenant guard. `TenantDataAccessContextHolder` defaults to admin mode, so missing context is dangerous.

**Recommended Fix**

Add fail-closed assertions for all LSP service entry points, integration tests that enumerate every `/api/v1/lsp/**` route, production startup checks requiring PostgreSQL/RLS, and alerts on tenant queries without context.

### M-05: CORS And Swagger Require Production Guardrails

**Evidence**

- `backend/src/main/java/com/bhawana/lms/security/SecurityConfig.java:206-221` allows localhost origins and credentials.
- `backend/src/main/java/com/bhawana/lms/security/SecurityConfig.java:145-155` permits Swagger/OpenAPI paths when enabled.
- `backend/src/main/resources/application.yml` disables API docs by default, while local profile enables local development behavior.

**Risk**

Current defaults are development oriented. If API docs or permissive origins are enabled in production, they increase attack surface and expose endpoint structure.

**Recommended Fix**

Drive CORS from environment-specific allowlists, fail startup on localhost origins in production, disable Swagger in production, and restrict actuator exposure.

### M-06: Disabled LSP Status Is Not Enforced At API-Client Authentication

**Evidence**

- `backend/src/main/java/com/bhawana/lms/service/ApiClientAuthenticationService.java:28-43` checks API-client status but does not show parent LSP active-status enforcement.
- `backend/src/main/java/com/bhawana/lms/web/AuthController.java:202` refreshes API-client identity by client ID.

**Risk**

An active API client under a suspended/disabled LSP can continue authenticating unless downstream checks catch it. This is explicitly dangerous for loan creation and disbursement.

**Recommended Fix**

Require active LSP status at token issuance, refresh, JWT validation, and every LSP request. Disabling an LSP must revoke all users, API clients, refresh tokens, webhooks, and pending disbursement permissions for that tenant.

## Low Findings

### L-01: Development Artifacts Are Present In The Same Repository

**Evidence**

- `frontend-2/src/mocks/**` contains extensive mock API and seed data.
- `frontend-2/src/routes/router.tsx` contains dev-only component routes guarded by development checks.
- Postman/local configuration and demo seed services exist in the tree.

**Risk**

Development artifacts are normal, but in this repo they overlap with production bundle imports and security-sensitive seed/admin behavior.

**Recommended Fix**

Keep dev/test artifacts isolated by profile, build target, and CI checks. Add a production bundle scan that fails if mocks, seed users, demo credentials, or local bootstrap services are included.

## Tenant Isolation Findings

Positive controls:

- LSP APIs are under `/api/v1/lsp/**`, and `TenantIsolationWebConfig` applies `LspTenantContextInterceptor` to that path.
- JWT-derived `lspId` is placed into `TenantDataAccessContextHolder`.
- `TenantAwareDataSource` sets PostgreSQL `app.current_lsp_id`.
- RLS is enabled on borrower, loan application, loan account, document checklist, intake/audit/event, disbursement request log, repayment schedule, payment transaction, foreclosure quote, webhook outbox, app user, API client, product mapping, and LSP idempotency tables.
- Borrower global/shared data isolation was hardened in `V43` and `V45`.

Tenant isolation risks:

- Tenant context defaults to admin mode when no tenant context is present.
- Some LSP flows temporarily switch to admin mode to resolve shared borrower/product data; these paths need strict tests and code review.
- Non-PostgreSQL or test profiles cannot prove RLS behavior.
- API-client IP allowlist is per-client in admin data but enforcement uses LSP-level entries.
- Disabled LSP status is not consistently enforced during API-client authentication and refresh.

Conclusion: tenant isolation is materially better than frontend-only tenant filtering, but it must be made fail-closed and route-enumeration tested before production.

## Endpoint Inventory

Public or permit-all endpoints:

| Endpoint | Access | Notes |
| --- | --- | --- |
| `GET /actuator/health/**`, `GET /actuator/info` | Permit all | Keep production actuator exposure minimal. |
| `/swagger-ui.html`, `/swagger-ui/**`, `/v3/api-docs/**` | Permit all if enabled | Disable in production. |
| `POST /api/v1/auth/login` | Permit all | Password login. Needs login audit, MFA/lockout. |
| `POST /api/v1/auth/token` | Permit all | API-client token. Needs IP allowlist and LSP active checks. |
| `POST /api/v1/auth/refresh` | Permit all with refresh cookie | Needs CSRF and API-client active/revocation checks. |
| `POST /api/v1/auth/logout` | Permit all with refresh cookie | Needs CSRF and audit. |
| `POST /api/v1/auth/password` | Authenticated | Password change. |

Internal/admin endpoints:

| Endpoint Group | Roles | Notes |
| --- | --- | --- |
| `/api/v1/internal/system/context` | SYSTEM_ADMIN, OPS_USER, PRODUCT_ADMIN, LSP_UI_READ, LSP_UI_WRITE | Context discovery. |
| `/api/v1/internal/home/overview` | SYSTEM_ADMIN | Dashboard. |
| `/api/v1/internal/admin/metadata` | SYSTEM_ADMIN | Admin metadata. |
| `/api/v1/internal/admin/users` | SYSTEM_ADMIN | User create/update/reset. Needs full audit. |
| `/api/v1/internal/admin/api-clients` | SYSTEM_ADMIN | Client create/update/rotate. Needs allowlist enforcement and create audit. |
| `/api/v1/internal/admin/audit-events` | SYSTEM_ADMIN | Audit explorer; masks some fields. |
| `/api/v1/internal/admin/borrowers` | SYSTEM_ADMIN, OPS_USER | Borrower search/detail. |
| `/api/v1/internal/admin/products` | SYSTEM_ADMIN, PRODUCT_ADMIN | Product CRUD and mappings. |
| `/api/v1/internal/admin/product-lsp-mappings` | SYSTEM_ADMIN, PRODUCT_ADMIN | Product/LSP mapping. |
| `/api/v1/internal/admin/lsps` | SYSTEM_ADMIN | LSP CRUD and webhook subscription. |
| `/api/v1/internal/admin/lsps/{lspId}/ip-allowlist` | SYSTEM_ADMIN | LSP allowlist CRUD. Needs audit. |
| `/api/v1/internal/admin/lsp-options` | SYSTEM_ADMIN, OPS_USER, PRODUCT_ADMIN | LSP options. |
| `/api/v1/internal/admin/webhook-outbox` | SYSTEM_ADMIN | Webhook outbox dispatch/admin. |
| `/api/v1/internal/alerts` | SYSTEM_ADMIN, OPS_USER | Alert list/ack/escalation; rules are SYSTEM_ADMIN. |
| `/api/v1/internal/reports/**` | SYSTEM_ADMIN | MIS preview/download/requests. Needs redaction and download audit. |
| `/api/v1/internal/ops/loan-applications/**` | SYSTEM_ADMIN, OPS_USER for reads/create/status; SYSTEM_ADMIN for manual status, disbursement, payments, foreclosure, mock outcome | Document download is available to class-level roles and needs audit. |

LSP endpoints:

| Endpoint Group | Roles | Notes |
| --- | --- | --- |
| `GET /api/v1/lsp/products` | LSP_API_CLIENT, LSP_UI_READ, LSP_UI_WRITE | Tenant-scoped product catalog. |
| `GET /api/v1/lsp/loan-applications`, detail, external lookup, invalid reasons, documents | LSP_API_CLIENT, LSP_UI_READ, LSP_UI_WRITE | Tenant-scoped reads. |
| `POST /api/v1/lsp/loan-applications/{id}/invalid` | LSP_API_CLIENT, LSP_UI_WRITE | LSP write action. |
| `POST /api/v1/lsp/loan-applications` | LSP_API_CLIENT | Loan creation with idempotency. |
| `POST /api/v1/lsp/loan-applications/{id}/documents`, batch | LSP_API_CLIENT, LSP_UI_WRITE | Upload/metadata. Needs content validation and download audit. |
| `PUT /api/v1/lsp/loan-applications/{id}/repayment-schedule` | LSP_API_CLIENT | Schedule submission. |
| `POST /api/v1/lsp/loan-applications/{id}/disbursement` | LSP_API_CLIENT | High-risk funds movement. Needs maker-checker and idempotency. |
| `GET /api/v1/lsp/loans/{loanId}`, schedule, payments | LSP_API_CLIENT, LSP_UI_READ, LSP_UI_WRITE | Tenant-scoped loan reads. |
| `POST /api/v1/lsp/loans/{loanId}/payments` | LSP_API_CLIENT | Payment posting. |
| `POST /api/v1/lsp/loans/{loanId}/foreclosure-quote` | LSP_API_CLIENT, LSP_UI_WRITE | Quote generation. |

## Database Security Findings

Positive:

- RLS is enabled for major tenant tables in `V41__tenant_isolation_rls.sql`.
- Borrower sharing/globalization was refined in `V43__global_borrowers_with_lsp_access.sql` and `V45__tenant_isolation_hardening.sql`.
- Tenant context is propagated to PostgreSQL using `set_config('app.current_lsp_id', ..., true)`.

Risks:

- Aadhaar, PAN, bank account number, IFSC, address, income, mobile, email, and reference-person details are plaintext domain columns.
- Audit payload JSON can duplicate sensitive borrower/bank data.
- Report tables/CSV rows can contain raw PAN and bank account details.
- Tenant DB-role credentials have unsafe defaults in application configuration.
- RLS cannot be relied on outside PostgreSQL; production must make PostgreSQL/RLS mandatory.
- Missing audit coverage reduces forensic value even when audit tables exist.

Recommended database controls:

- Field-level encryption/tokenization for regulated identifiers.
- Hash-only lookup fields for PAN/Aadhaar/bank-account searches.
- KMS-backed key rotation and separation of duties.
- Strong unique/idempotency constraints for disbursement provider references.
- Immutable audit tables with append-only semantics and restricted admin writes.
- Database-level constraints for state transitions that protect against duplicate disbursement and invalid terminal-state changes.

## Disbursement Risk Review

Current risk posture: critical.

Observed risks:

- Mock adapter is a production service candidate.
- Mock outcome endpoint can set disbursement result.
- LSP API client can invoke disbursement.
- Auto-approval can move applications into disbursement-eligible state.
- No evidence of bank mTLS/cert pinning, signed requests, provider idempotency keys, bank callback signature verification, or reconciliation lockstep.
- LSP disbursement endpoint does not show the same explicit idempotency handling as LSP create/payment paths.
- Admin manual status/disbursement operations are SYSTEM_ADMIN-only but need maker-checker and stronger audit.

Required production controls:

- Two-person approval for loan approval and disbursement release.
- Hard state machine preventing disbursement before verified approval, required documents, KYC checks, repayment schedule, and product/LSP eligibility.
- Mandatory idempotency key and unique provider reference on every disbursement attempt.
- Bank response verification before state transition to `DISBURSED`.
- Signed webhooks/callbacks with replay protection.
- Reconciliation jobs that compare internal state, bank state, and ledger entries.
- Real-time alerting for duplicate attempts, amount mismatch, beneficiary mismatch, stale pending requests, and manual overrides.

## Backdoor Review

Confirmed high-risk backdoor-like paths:

- Default bootstrap SYSTEM_ADMIN credentials.
- Runtime bootstrap fallback user.
- Local bootstrap sync service.
- Mock disbursement adapter.
- Mock disbursement outcome endpoint.
- Frontend seed-user login page preview.
- Frontend mock router fallbacks and mock session bridge.

Not confirmed in this static pass:

- No separate hardcoded "master password" string was identified beyond the bootstrap/default credentials.
- No intentionally disabled global authentication middleware was found; backend route authorization is generally centralized in Spring Security plus method security.

## Business Logic Abuse Review

Key abuse paths:

- LSP client can create a loan, satisfy checklist requirements, and request disbursement through an auto-approval path.
- SYSTEM_ADMIN can manually change status, initiate disbursement, apply mock outcome, post payments, and execute foreclosure. These operations need maker-checker and immutable audit trails.
- API-client disablement/rotation is not sufficient to stop all token use.
- Disabled LSP status does not appear to be enforced consistently at token issuance and refresh.
- Document and MIS downloads can occur without complete audit logging.
- Raw PII exports create a high-value exfiltration path from a single privileged account.

## Remediation Roadmap

### Phase 1: Production Blockers

1. Rotate all committed credentials and remove them from active infrastructure.
2. Remove/default-fail bootstrap admin, local profile defaults, mock disbursement runtime wiring, and mock outcome endpoints from production.
3. Enforce API-client/LSP active status, token revocation, and per-client IP allowlists.
4. Stop plaintext PII exports; mask PAN/bank account/Aadhaar in reports by default.
5. Add mandatory audit events for login, token issuance, user/admin credential operations, document downloads, MIS downloads, approvals, and disbursement state changes.
6. Add maker-checker approval and idempotency to disbursement before any real bank account is connected.

### Phase 2: High-Priority Fixes

1. Implement field-level encryption/tokenization for Aadhaar, PAN, bank account number, and KYC metadata.
2. Replace frontend `localStorage` JWT persistence with memory/BFF/session-cookie design and CSRF protection.
3. Remove frontend mocks/seed users from production bundles and fail closed on API errors.
4. Add document magic-byte validation, malware scanning, quarantine, and secure download behavior.
5. Add active LSP enforcement and token invalidation across JWT validation and refresh.
6. Add route-enumeration tests for every internal and LSP endpoint's RBAC and tenant isolation.

### Phase 3: Hardening

1. Add MFA and step-up authentication for SYSTEM_ADMIN, report export, credential reveal/rotation, approval, and disbursement.
2. Expand rate limiting to admin writes, refresh, reports, document downloads, password resets, payments, and disbursements.
3. Standardize error responses to avoid ID/state enumeration.
4. Add production startup checks for profile, CORS, Swagger, actuator, RLS, storage provider, bank adapter, JWT secret strength, and non-default credentials.
5. Add database constraints for duplicate disbursement prevention, terminal-state immutability, and product/LSP eligibility.

### Phase 4: Monitoring And Compliance Controls

1. Centralize immutable audit logs with actor, tenant, source IP, request ID, idempotency key, entity IDs, old/new values, and reason/purpose.
2. Add anomaly detection for bulk PII/report/document access, disbursement spikes, repeated failed auth, and status override patterns.
3. Add reconciliation dashboards for bank settlement, disbursement status, internal ledger state, and webhook delivery.
4. Add periodic access reviews for SYSTEM_ADMIN, OPS, PRODUCT_ADMIN, LSP users, and API clients.
5. Add secret scanning, dependency scanning, SAST, container scanning, and security regression tests to CI.
6. Document incident-response runbooks for credential compromise, PII breach, failed disbursement, duplicate disbursement, and tenant isolation incident.

## Final Assessment

The codebase has meaningful authorization and tenant-isolation building blocks, but it currently contains multiple critical blockers that would be unacceptable for a production loan/disbursement platform. The system should remain non-production until secrets are rotated, mock/backdoor paths are removed from production, PII is protected, disbursement controls are redesigned for real funds movement, and audit logging is made complete and immutable.
