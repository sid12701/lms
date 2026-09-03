# Wave 6 — Partner Integration Auditor Report
**Mode:** READ-ONLY | **Specs:** webhook-delivery-retry-and-redrive, lsp-self-service-loan-visibility, portfolio-mis-reports  
**Code baseline:** `/Users/siddhant/Desktop/lms` (Spring Boot backend + React SPA)

---

## 1. Executive Summary

Wave 6 combines three partner-facing surfaces: **outbound webhooks**, **LSP self-service loan reads**, and **internal Portfolio MIS reporting**. The implementation is materially aligned with the as-is specs on happy paths — transactional outbox, HMAC signing, LSP `lspId` ownership checks, MIS Aadhaar/PAN/bank masking, and webhook stale-`IN_FLIGHT` reclaim.

**Bank-grade gaps concentrate in four areas:**

| Area | Verdict |
|------|---------|
| Webhook SSRF | Partial — registration + egress checks exist, but redirect-following and DNS TOCTOU weaken guarantees |
| Webhook signing/retry/redrive | Strong — HMAC-SHA256, bounded retries, admin redrive with audit |
| LSP tenant bleed | Strong on loan/loan-account paths; residual PII-policy and shared-borrower visibility risks |
| MIS PII/retention/lease | Weak on lifecycle — masking is good; **no stale-`PROCESSING` recovery**, **no R2 retention**, preview unaudited |

**Finding count:** 7 High · 11 Medium · 5 Low (W6-F01–W6-F23)

---

## 2. Audit Scope & Methodology

**In scope**
- Webhook: `WebhookOutbox*`, `HttpWebhookDeliveryClient`, `SsrfSafeUrlValidator`, `LspDirectoryService` subscription path
- LSP visibility: `LspLoanApplicationApiController`, `LspLoanApiController`, `LspBorrowerApiController`, `LoanApplicationQueryService`, `LoanServicingSupportService`, `LspLoanApplicationResponses`
- MIS: `ReportAdminController`, `AdminReportingService`, `ReportRequestService`, `ReportRequestProcessingWorker`, `R2ReportStorageService`, `ReportAccessAuditService`

**Evidence:** spec cross-walk, source trace, unit/integration tests (`SsrfSafeUrlValidatorTest`, `WebhookOutboxSoftFourxxAndRedriveTest`, `LspLoanApplicationApiControllerTest`, `TenantIsolationPostgresIntegrationTest`, `ReportAdminControllerTest`)

**Out of scope:** partner receiver implementation, frontend-only UX gaps unless security-relevant

---

## 3. Combined Architecture (ASCII)

```text
Domain event ──► enqueueIfSubscribed ──► webhook_event_outbox
                                              │
                    WebhookOutboxDispatchWorker (admin scope)
                                              │
                    claim IN_FLIGHT (5m TTL) ──► sign HMAC ──► HttpWebhookDeliveryClient
                                              │                      │
                                              │                 SSRF validate (egress)
                                              ▼
                                    delivery_attempt + status update
                                    PERMANENT_FAILURE ──► WEBHOOK_DEAD_LETTER alert
                                              │
                                    admin redrive ──► PENDING

LSP JWT (lspId) ──► /api/v1/lsp/** ──► ownership check (404) ──► masked responses
                                   └──► GET bank-details ──► full account + reveal audit

SYSTEM_ADMIN ──► /api/v1/internal/reports/** ──► preview/summary (masked)
                                              └──► async request ──► worker ──► R2 store ──► download + access audit
```

---

## 4. Spec Compliance Matrix (High-signal)

| Spec FR/NFR | Status | Notes |
|-------------|--------|-------|
| Webhook FR-006 signing | **Met** | `v1=` HMAC-SHA256 over `{timestamp}.{payloadJson}` |
| Webhook NFR-002 SSRF egress | **Partial** | `SsrfSafeUrlValidator.validate()` before POST; redirect/TOCTOU gaps |
| Webhook FR-008/012 retry cap | **Met** | Default 8 attempts, exponential backoff, dead-letter |
| Webhook FR-010 redrive | **Met** | `PERMANENT_FAILURE` only, cap, `webhook_outbox_redrive_audit` |
| LSP FR-007 tenant isolation | **Met** | `getApplicationForLsp` / `getLoanAccountForLsp` → 404 |
| LSP FR-018 PII reveal audit | **Partial** | Bank-details GET audited; PAN/contact unmasked without audit on loan reads |
| MIS FR-012 masking | **Met** | `AadhaarMasking`, `PanMasking`, `BankAccountMasking` in `AdminReportingService` |
| MIS FR-014 worker lifecycle | **Partial** | PENDING→PROCESSING→COMPLETED/FAILED; **no stale PROCESSING reclaim** |
| MIS retention | **Not met** | No purge/lifecycle in code or migrations |

---

## 5. Webhook Security — SSRF & Egress

### Controls present
- Registration: `SsrfSafeUrlValidator.validateRegistrationTarget()` in `LspDirectoryService` — blocks resolvable private/reserved IPs; allows temporarily unresolvable hosts.
- Egress: `HttpWebhookDeliveryClient.deliver()` calls strict `validate()` (requires resolvable host) immediately before POST.
- Blocks loopback, site-local, link-local, any-local, CGNAT (100.64/10).

### Gaps (High)

**W6-F01 [High] — HTTP redirect bypass of SSRF validation**  
`HttpWebhookDeliveryClient` uses default `RestClient` + `SimpleClientHttpRequestFactory`, which follows redirects. SSRF is validated only on the **initial** URL string; a partner (or compromised endpoint) can return `302` to `http://169.254.169.254/` or an internal address after passing validation.  
Evidence: `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/HttpWebhookDeliveryClient.java` — no redirect disable, no re-validation on redirect target.

**W6-F02 [High] — DNS resolution TOCTOU**  
`SsrfSafeUrlValidator` resolves once via `InetAddress.getByName(host)` then `RestClient` connects using the hostname again (separate resolution). Classic DNS-rebinding window between validate and connect. No IP pinning or custom `DnsResolver` that binds validated address to the socket.

**W6-F10 [Medium] — Cleartext `http://` permitted at registration**  
Backend accepts `http://` (`LspDirectoryService`); frontend requires HTTPS only. Webhook payloads and HMAC secrets traverse cleartext if partner configures HTTP.

**W6-F18 [Low] — Incomplete SSRF test coverage**  
`SsrfSafeUrlValidatorTest` covers loopback, scheme, unresolvable host; no tests for redirect bypass, metadata IP, or multi-A DNS records.

---

## 6. Webhook Signing & Secret Handling

### Controls present
- Signature: `HmacSHA256` over `{epochSeconds}.{payloadJson}`, header `X-Webhook-Signature: v1=<hex>` (`WebhookOutboxDispatchExecutor.buildDeliveryRequest`).
- Missing secret → `IllegalStateException` at prepare time → retryable failure path.
- Admin read APIs return `signingSecret: null`, `secretSet: boolean` (write-only).
- Bank-detail webhook payloads mask account numbers before enqueue (`BorrowerBankDetailsService`).

### Gaps

**W6-F09 [Medium] — HMAC signatures persisted in delivery attempts**  
`WebhookEventDeliveryAttempt.request_signature` stores the full `v1=` signature per attempt. While not the raw secret, this expands the attack surface for offline forgery attempts and couples secret rotation to historical rows.  
Evidence: `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/domain/WebhookEventDeliveryAttempt.java`

**W6-F21 [Low] — No multi-secret rotation overlap**  
Single active secret per LSP; rotation is immediate PUT replacement (spec G-006). Partners cannot verify during overlap window.

---

## 7. Webhook Retry, Redrive & Resilience

### Controls present
- Claim: Postgres `FOR UPDATE SKIP LOCKED`; stale `IN_FLIGHT` reclaimed after 5-minute `claim_expires_at` (`WebhookEventOutboxRepositoryImpl`).
- Classification: 2xx success; 408/429/5xx + transport → retryable; other 4xx → permanent + `WEBHOOK_DEAD_LETTER`.
- Retry budget: `app.webhooks.delivery.max-attempts` default 8; backoff `min(60×2^min(n-1,5), 3600)` → effective max **1920s** (never hits 3600s ceiling).
- Redrive: admin-only, `PERMANENT_FAILURE` only, `redriveCount` cap (default 3), audit row written.
- HTTP outside DB transaction; outcome recorded in separate tx (correct pattern).

### Gaps

**W6-F08 [Medium] — Dispatch does not re-check subscription state**  
`enqueueIfSubscribed` gates at enqueue; `prepareDelivery` reads current LSP endpoint/secret but does **not** check `webhook_enabled` or event-type subscription. Disabled LSPs or rotated-off event types can still deliver queued rows (spec D4 gap).

**W6-F11 [Medium] — No outbox deduplication**  
Repeated producer calls create duplicate outbox rows (spec G-008). Partners must dedupe on `X-Webhook-Delivery-Id`.

**W6-F20 [Low] — No redrive UI**  
API exists (`WebhookOutboxAdminController`); no frontend surface.

**W6-F22 [Low] — No dead-letter auto-resolution on successful redrive**  
Alerts persist after recovery.

### Positive test evidence
`WebhookOutboxSoftFourxxAndRedriveTest` — soft 4xx permanent failure, retry exhaustion, redrive cap, audit persistence.

---

## 8. LSP Tenant Isolation & Cross-LSP Bleed

### Controls present (strong)
- Every LSP read derives `lspId` from JWT via `LspAuthenticationSupport.authenticatedLspId()`.
- Application ownership: `LoanApplicationQueryService.getApplicationForLsp` compares `application.getLsp().getId()` → generic 404.
- Loan account ownership: `LoanServicingSupportService.getLoanAccountForLsp` same pattern.
- Borrower bank details: `BorrowerBankDetailsService.loadBorrowerForLsp` uses `borrower.hasVisibilityFor(lspId)` → 404.
- `@PreAuthorize` on all `/api/v1/lsp/**` controllers.
- Integration tests: `apiClientCannotAccessAnotherLspLoanEndpoints` in `LspLoanApplicationApiControllerTest`; `TenantIsolationPostgresIntegrationTest`; foreclosure cross-tenant test.

### Residual risks

**W6-F07 [High] — Shared-borrower visibility model enables cross-loan bank reveal**  
Borrowers are global (PAN-deduped) with `borrower_lsp_access` / `hasVisibilityFor`. Any LSP granted visibility can call `GET /api/v1/lsp/borrowers/{borrowerId}/bank-details` and receive **full unmasked account number** (audited, but not loan-scoped). If visibility is over-granted at onboarding, partner A could read bank details for a borrower whose active loan is only with partner B.  
Evidence: `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/web/LspBorrowerApiController.java`, `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/domain/Borrower.java`

**W6-F19 [Low] — List pagination cap 200 vs spec 1000**  
`LspLoanApplicationApiController` `@Max(200)` on `limit`; spec documents 1–1000.

**Detail-assembler pattern note (not a finding):** `getApplication` calls `getApplicationForLsp` then `loanApplicationDetailAssembler.getDetail(applicationId)` without passing `lspId`. Safe because ownership is verified first; assembler is admin-scoped internal read.

---

## 9. LSP PII Exposure & Masking

### Masking applied
| Field | List/Detail API | Bank-details GET |
|-------|-----------------|------------------|
| Aadhaar | Masked (`AadhaarMasking`) | N/A |
| Bank account | Masked | **Full** (intentional, audited) |
| PAN | **Full plaintext** | N/A |
| Email/mobile/address | **Full plaintext** | N/A |

**W6-F06 [High] — Inconsistent PII policy on primary LSP loan surfaces**  
`/api/v1/lsp/loan-applications` list and detail return full PAN, email, mobile, DOB, and address without reveal audit. Only bank-details GET writes `BorrowerPiiRevealAudit` via `BorrowerPiiRevealAuditService`. Spec FR-018 marks this as partial; bank-grade policy typically requires masked PAN/contact or audited reveal on all full-value fields.  
Evidence: `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/web/LspLoanApplicationResponses.java` lines 38–39, 95–102.

**Positive:** Webhook `BORROWER_BANK_DETAILS_UPDATED` payload masks account numbers before egress.

---

## 10. MIS PII Masking & Data Protection

### Controls present (strong)
- Preview and CSV rows mask Aadhaar (`XXXXXXXX` + last 4), PAN (`XXXXXX` + last 4), bank account (`BankAccountMasking`) in `AdminReportingService`.
- Tests assert masked values in preview/CSV (`ReportAdminControllerTest`).
- Download paths write `report_access_audit` with actor, IP, correlation ID, filters, byte count (`ReportAccessAuditService`).
- `SYSTEM_ADMIN` only on all `/api/v1/internal/reports/**`.

### Gaps

**W6-F12 [Medium] — Preview, summary, and request-create are not access-audited**  
Only `MIS_CSV_DOWNLOADED` and `MIS_REQUEST_DOWNLOADED` write audit rows (spec G-010, D9 partial). An admin can browse paginated borrower PII in preview without forensic trail.

**W6-F14 [Medium] — Full CSV materialized in heap**  
`AdminReportingService` keyset-batches DB reads (1000 accounts) but appends all rows to one `StringBuilder`/byte array. Large portfolios risk OOM and long GC pauses (spec G-007).

**W6-F16 [Medium] — Duplicate async requests without idempotency key**  
`Idempotency-Key` optional on `POST /portfolio-mis/requests`; retries can create duplicate `report_request` rows and duplicate R2 artifacts.

---

## 11. MIS Async Processing & Lease Recovery

### Controls present
- Worker claims `PENDING` via Postgres `FOR UPDATE SKIP LOCKED` (`ReportRequestRepositoryImpl`).
- Terminal states: `COMPLETED` (storage key + metadata) or `FAILED` (truncated error, no storage key).
- Feature flag: `app.reports.processing.enabled`.
- Manual drain: `POST /requests/process`.

### Gaps (critical for ops resilience)

**W6-F03 [High] — No stale-`PROCESSING` recovery**  
Unlike webhooks (5-minute `claim_expires_at`), `ReportRequest.markProcessing()` sets status with **no lease timestamp**. Worker crash after `markProcessing()` leaves the row stuck forever — no reclaim query, no timeout, no admin redrive (spec G-006).  
Evidence: `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/domain/ReportRequest.java`, `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/ReportRequestService.java`

**W6-F04 [High] — Entire worker batch in one `@Transactional` boundary**  
`ReportRequestService.processPendingRequests` is `@Transactional`; inner loop generates CSV, uploads to R2, and updates status without per-request transaction boundaries. Long-running reports hold DB connections and enlarge rollback blast radius (spec G-008).

**W6-F13 [Medium] — No retry/redrive for `FAILED` report requests**  
Operator must manually create a new request; no clone-and-rerun workflow (spec G-005).

---

## 12. R2 Storage & Retention

### Controls present
- Async artifacts stored in R2/S3 via `R2ReportStorageService`; key format `reports/{requestId}/{reportType}/{epoch}-{uuid}-{fileName}`.
- Sanitized filenames; `forcePathStyle(true)` for R2 compatibility.
- Sync CSV bypasses object storage (streamed from memory in controller).
- Missing R2 config → `IllegalStateException` → request `FAILED`.

### Gaps

**W6-F05 [High] — No retention, purge, or lifecycle policy**  
No code, migration, or bucket lifecycle hooks for report objects or `report_access_audit`. MIS CSVs contain masked but still sensitive portfolio data (names, addresses, employment, income) and persist indefinitely in R2 (spec G-014, IG-003).

**W6-F17 [Medium] — No object-level encryption metadata or time-limited download URLs**  
Downloads proxy full bytes through backend (`reportStorageService.retrieve`); no presigned URL expiry, no SSE-KMS configuration in `PutObjectRequest`.

---

## 13. RBAC & Access Control

| Surface | Roles | Verdict |
|---------|-------|---------|
| Webhook admin (`/internal/admin/webhook-outbox/**`) | `SYSTEM_ADMIN` | **Correct** — class-level `@PreAuthorize` |
| Per-loan webhook feed (`/internal/ops/loan-applications/{id}/webhook-events`) | `SYSTEM_ADMIN`, `OPS_USER` | **Correct** |
| LSP APIs (`/api/v1/lsp/**`) | `LSP_API_CLIENT` / `LSP_UI_READ` / `LSP_UI_WRITE` | **Correct** — write endpoints further restricted |
| Reports (`/internal/reports/**`) | `SYSTEM_ADMIN` | **Correct** |
| LSP UI routes | `RequireLsp` blocks internal roles | **Correct** (per spec) |

**W6-F15 [Medium] — Admin webhook outbox returns full `payloadJson`**  
`WebhookOutboxAdminController.toResponse` exposes stored envelope to any `SYSTEM_ADMIN`. Payloads include loan IDs, amounts, invalidation reasons, disbursement metadata — acceptable for ops but widens insider threat surface; no field-level redaction.

---

## 14. Findings Register

| ID | Sev | Domain | Finding | Primary evidence |
|----|-----|--------|---------|------------------|
| **W6-F01** | **High** | Webhook SSRF | HTTP redirect can bypass egress validation | `HttpWebhookDeliveryClient.java` |
| **W6-F02** | **High** | Webhook SSRF | DNS validate/connect TOCTOU; no IP pinning | `SsrfSafeUrlValidator.java`, `HttpWebhookDeliveryClient.java` |
| **W6-F03** | **High** | MIS lease | Stuck `PROCESSING` with no reclaim/TTL | `ReportRequest.java`, `ReportRequestService.java` |
| **W6-F04** | **High** | MIS processing | Single transaction spans CSV gen + R2 + full batch | `ReportRequestService.java` |
| **W6-F05** | **High** | MIS retention | No R2/audit purge or lifecycle | `R2ReportStorageService.java` (no lifecycle hooks) |
| **W6-F06** | **High** | LSP PII | Full PAN/contact on loan list/detail without reveal audit | `LspLoanApplicationResponses.java` |
| **W6-F07** | **High** | LSP tenant | Shared-borrower visibility allows bank reveal beyond loan scope | `Borrower.java`, `LspBorrowerApiController.java` |
| W6-F08 | Med | Webhook | No subscription/enabled re-check at dispatch | `WebhookOutboxDispatchExecutor.prepareDelivery` |
| W6-F09 | Med | Webhook | HMAC signatures stored in delivery_attempt | `WebhookEventDeliveryAttempt.java` |
| W6-F10 | Med | Webhook | `http://` endpoints allowed | `LspDirectoryService.java` |
| W6-F11 | Med | Webhook | No outbox deduplication key | `WebhookOutboxService.enqueueIfSubscribed` |
| W6-F12 | Med | MIS audit | Preview/summary/create not audited | `ReportAdminController.java` |
| W6-F13 | Med | MIS ops | No FAILED request retry/redrive | `ReportRequestService.java` |
| W6-F14 | Med | MIS perf | Full CSV heap materialization | `AdminReportingService.java` |
| W6-F15 | Med | Webhook admin | Full payload_json in outbox list API | `WebhookOutboxAdminController.java` |
| W6-F16 | Med | MIS idempotency | Duplicate requests without key | `ReportAdminController.java` |
| W6-F17 | Med | R2 security | No SSE/presigned-expiry on stored reports | `R2ReportStorageService.java` |
| W6-F18 | Low | Webhook | Backoff never reaches 3600s (max 1920s) | `WebhookOutboxDispatchExecutor.calculateBackoffSeconds` |
| W6-F19 | Low | LSP API | List `limit` max 200 vs spec 1000 | `LspLoanApplicationApiController.java` |
| W6-F20 | Low | Webhook ops | No admin redrive UI | Spec G-003 |
| W6-F21 | Low | Webhook ops | No secret rotation overlap | Spec G-006 |
| W6-F22 | Low | Webhook ops | Dead-letter alerts not auto-resolved on redrive | Spec investigation gap |
| W6-F23 | Low | LSP ops | IP allowlist 60s process-local cache staleness | Spec G-12 |

---

## 15. Recommendations & Priority Roadmap

### P0 — Before production partner traffic
1. **SSRF hardening (W6-F01, W6-F02):** Disable redirect following on webhook `RestClient`; re-validate or pin resolved IP on connect; consider hostname allowlist per LSP for bank-grade tier.
2. **Report lease recovery (W6-F03):** Add `processing_claim_expires_at` + reclaim query mirroring webhook pattern; admin redrive for stuck rows.
3. **Report transaction boundaries (W6-F04):** One transaction per request (claim → generate → store → complete); never hold TX across R2 I/O.
4. **Retention policy (W6-F05):** R2 lifecycle rules (e.g. 90-day expiry) + documented `report_access_audit` retention; operational runbook.

### P1 — Partner data protection
5. **LSP PII policy (W6-F06):** Mask PAN/contact on list/detail OR add audited reveal consistent with bank-details pattern.
6. **Borrower visibility scoping (W6-F07):** Tie bank-details GET to active loan-application ownership, not just `borrower_lsp_access`.
7. **MIS preview audit (W6-F12):** Log `MIS_PREVIEW_ACCESSED` / `MIS_SUMMARY_ACCESSED` with filters and row counts.

### P2 — Operational maturity
8. Dispatch-time subscription check (W6-F08); HTTPS-only enforcement (W6-F10); report FAILED retry (W6-F13); streaming CSV export (W6-F14); mandatory idempotency on report create (W6-F16).

### Controls to preserve (no regression)
- Webhook outbox + attempt tables decoupled from HTTP
- HMAC signing with timestamp header
- Webhook stale-`IN_FLIGHT` reclaim (5 min)
- LSP wrong-tenant → 404 pattern
- MIS masking trifecta (Aadhaar/PAN/bank)
- Bank-details reveal audit trail
- `SYSTEM_ADMIN`-only reports and webhook admin

### Test gaps to close
- SSRF redirect + DNS rebinding integration tests
- Worker-crash → stuck `PROCESSING` → reclaim integration test
- Cross-LSP bank-details access with shared borrower fixture
- R2 lifecycle / retention verification in staging

---

*End of Wave 6 audit. All paths absolute under `/Users/siddhant/Desktop/lms` unless spec paths under `/Users/siddhant/Desktop/work/ferratum-products-specs-res/`.*

[REDACTED]