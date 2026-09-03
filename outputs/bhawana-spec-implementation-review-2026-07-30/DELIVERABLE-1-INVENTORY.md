# Deliverable 1 — Repository & Specification Inventory

**Review**: Bhawana LMS bank-grade architecture & implementation review
**Date**: 2026-07-30
**Reviewer role**: Principal fintech architect (evidence-based, read-only)
**Status**: Awaiting confirmation of inventory + review order before Area 1

---

## 1. Repository relationship

The paths given in the brief were partially wrong. Resolved layout:

| Path | Role | Remote | Notes |
| --- | --- | --- | --- |
| `~/Desktop/lms` | **Implementation** — the Bhawana LMS itself | `git@github.com:sid12701/lms.git` (personal) | Spring Boot backend + React SPA. HEAD `bfd571f`, working tree dirty. |
| `~/Desktop/work/ferratum-products-specs-res` | **Specification corpus** — Multitude/Ferratum group spec monorepo | `https://github.com/Multitude-SE/ferratum-products-specs-res.git` (work) | Bhawana is *one of six* areas. |
| `~/Desktop/work/ferratum-products-specs-res/areas/bhawana` | The 31 Bhawana `spec.md` files under review | — | Entity-scoped area for India market entry. |

**`~/Desktop/work/bhawana` does not exist.** Neither repository contains the other; they are independent git repositories on **different GitHub accounts** (personal `sid12701` vs. work `Multitude-SE`). The only linkage is documentary: the specs cite an LMS commit SHA as their baseline.

**Decoys ruled out** (confirm if any is actually in scope):
`~/Desktop/bhawana-website`, `~/Desktop/lms-greenlight-demo` (fork/demo of LMS, contains duplicate `docs/design/bhawana_institutional`), `~/Desktop/projectloansync`, `~/Desktop/project-loansync-prod`.

Bhawana's five sibling areas (`client-zone`, `core-services-and-collection`, `customer-acquisition`, `ferraweb-frontend`, `lending-product-platform`) describe the **existing** Ferratum European platform (Mambu, credit cards, SEPA direct debit, collections). Per `areas/bhawana/README.md`, Bhawana is explicitly **greenfield — "no re-use of existing Ferratum platform components"** because the B2B2C LSP model and RBI context make shared-platform reuse infeasible. Those five areas are therefore *comparison material*, not scope.

---

## 2. ⚠️ Method-critical finding: the specs are as-is documentation, not requirements

This must be settled before Area 1, because it determines what the review can conclude.

Every Bhawana spec self-describes as reverse-engineered from the code:

> `api-loan-application-intake/spec.md:9-11`
> **Input**: Existing Bhawana LMS prototype — backend (`com.bhawana.lms`). **Documentation-first spec of as-is behavior.**
> **Implementation Baseline**: LMS HEAD `2269d064f0be50e7f6485c0be38e3cdcef6137d2` (2026-07-16)

Corroborated by the specs repo git history (`a5f7019 feat: [BHAW-10] align Bhawana specs with LMS implementation`) and by `CODE_REVIEW_2026-07-20.md:14`, which states the LMS was treated as the **"Source of Truth"** and the specs were corrected to match it.

**Consequences:**

1. **Spec↔code agreement is near-tautological.** A traceability matrix showing "spec says X, code does X" is close to circular and proves nothing about design quality. Deliverable 2's matrix will still be produced (it catches genuine drift, and drift already exists — see §6), but it cannot be the primary evidence.
2. **The normative baseline must come from elsewhere.** I propose, in priority order:
   - the ten cross-cutting product decisions **D1–D10** (`areas/bhawana/README.md`, sourced from the Bhawana LMS Business Guide PDF §6) — these *are* genuine forward requirements;
   - RBI and Indian statutory requirements (see §5);
   - established fintech/ledger engineering practice.
3. **Where spec and code agree but both are wrong, only an external oracle finds it.** This is exactly the "well-researched engineering vs. generic default" question in the brief, and it is where this review adds value over `CODE_REVIEW_2026-07-20.md`.

**Distinction from the prior review**: `CODE_REVIEW_2026-07-20.md` asked *"does the spec accurately describe the code?"* (fidelity). This review asks *"is the code right for a regulated B2B2C lender?"* (engineering quality). Non-overlapping; its 31-spec matrix is a useful map and I will reuse it as a lead, not as a conclusion.

---

## 3. Architecture & technology overview

**Shape**: Spring Boot modular monolith + PostgreSQL + React SPA. No microservices, no event bus.

| Layer | Technology | Evidence |
| --- | --- | --- |
| Backend | Java / Spring Boot; packages `web`, `service`, `domain`, `repo`, `security`, `tenant`, `config`, `common`, `seed` | `backend/src/main/java/com/bhawana/lms/` |
| Persistence | PostgreSQL + Flyway, **109 migrations** (V1→V113), **52 tables** | `backend/src/main/resources/db/migration/` |
| Multi-tenancy | Postgres **Row-Level Security** + tenant-routing DataSource + admin-scoped transaction escape hatch | `backend/.../tenant/` (13 classes), `V41`, `V43`, `V113` |
| AuthN/Z | JWT resource server; HttpOnly refresh cookie; in-memory SPA access token; 30s principal cache; RBAC via `@PreAuthorize` | `backend/.../security/` (18 classes) |
| Rate limiting | Bucket4j over Redis (Lettuce) | `security/RateLimitConfig.java` |
| Async | **Database-polled `@Scheduled` workers** — no broker | 6 workers (see below) |
| Partner notification | Transactional **webhook outbox** + delivery attempts + redrive | `webhook_event_outbox`, `WebhookOutboxDispatchWorker` |
| Object storage | S3/R2 + MinIO (documents, MIS report artifacts) | `minio`, `s3` deps; `FileSystemLoanDocumentStorageService`, `R2ReportStorageService` |
| Frontend | React + Vite + TypeScript, 13 feature modules, 19 routes | `frontend/src/features/`, `frontend/src/routes/router.tsx` |
| Deployment | Docker Compose only | `infra/docker-compose.yml` (single file) |

**Scheduled workers** (all DB-polling, lease-based): `LoanDisbursementWorker`, `WebhookOutboxDispatchWorker`, `ReportRequestProcessingWorker`, `AlertRuleSchedulerWorker`, `PortfolioKpiSnapshotWorker`, `IdempotencyRecordRetentionWorker`.

**Scale of surface**: 25 REST controllers, ~139 service classes, 95 domain types, 19 SPA routes, 158 backend test classes, 136 frontend unit tests, 7 Playwright E2E specs.

**Early positive signals** (deliberate, not default — to be confirmed per area):
- All monetary columns are `NUMERIC(19,2)`; **zero** float/double money columns found across all 109 migrations.
- RLS enabled on **23 tables** — tenant isolation pushed into the database, not left to developer discipline.
- Append-only audit tables for intake, status transitions, document access, PII reveal, disbursement outcome, auth events, report access, API-client and LSP mutations.
- `CONTEXT.md` contains a genuine ubiquitous-language glossary with *"Avoid:"* anti-synonyms and an explicit **"point of no return"** rule for disbursement — this is domain-expert reasoning, not scaffolding.
- Durable pre-call disbursement intent (`disbursement_intent`) committed before any bank call.

**Early concerns to test** (hypotheses, not findings):
- `spring-boot-starter-amqp` is a declared dependency with **zero usage** in `backend/src/main/java` — dead dependency.
- 29 of 52 tables have **no RLS**; need to confirm none are tenant-scoped.
- **No ledger exists.** `grep -ril ledger backend/src/main` → 0 files. Money state lives on mutable balance columns.
- 6 polling workers with no broker — need to verify multi-replica safety and lease correctness under contention.
- Single-file Docker Compose is the only deployment artifact; no evidence of DR/backup/restore posture.

---

## 4. Complete `spec.md` inventory — 31 specs in 5 domains

`Impl` = implementation status determined by code search, not by spec claim.
✅ implemented · ⚠️ mock/partial · ❌ no implementation

### 4.1 Platform Setup — 6 specs

| # | Spec | Lines | Status | Impl | Primary backend | Frontend | Key tables/migrations | Key tests |
|---|---|---|---|---|---|---|---|---|
| P1 | `internal-authentication-and-sessions` | 357 | Eng. Reviewed | ✅ | `AuthController`, `AuthAuthenticationService`, `AuthTokenService`, `AuthPrincipalCache` | `features/auth`, `/login`, `/change-password` | `app_user`, `refresh_token`, `auth_event_audit` · V73, V84, V93, V94, V95 | `AuthControllerTest`, `AuthControllerRefreshAtomicityTest`, `AuthBruteForceLockout*Test`, `Issue80SessionRevocationIntegrationTest` |
| P2 | `user-role-and-permission-management` | 327 | Eng. Reviewed | ✅ | `UserAdminController`, `AppRole`, `RoleCode` | `features/users`, `/users` | `app_user`, `app_user_audit_event` · V67, V81 | `UserAdminControllerTest`, `SecurityConfigTest` |
| P3 | `api-rate-limiting-and-payload-guards` | 291 | Eng. Reviewed | ✅ | `RateLimitFilter`, `RateLimitRuleMatcher`, `LspApiPayloadSizeFilter` | — (backend-only) | — (Redis-backed buckets) | `RateLimitFilterIntegrationTest`, `LspApiPayloadSizeFilterTest`, `RateLimitRuleMatcherTest` |
| P4 | `partner-lsp-onboarding-and-status-management` | 312 | Eng. Reviewed | ✅ | `LspAdminController`, `LspDirectoryService`, `LspIpAllowlistAdminController`, `LspUiIpAllowlistAdminController` | `features/lsps`, `/lsps` | `lsp`, `lsp_audit_event`, `lsp_ip_allowlist_entry`, `lsp_ui_ip_allowlist_entry` · V79, V89 | `LspStatusKillChainIntegrationTest`, `Issue64LspSurfaceIpAllowlistIntegrationTest` |
| P5 | `product-catalog-management` | 300 | Eng. Reviewed | ✅ | `LoanProductAdminController`, `ProductOptionsController` | `features/products`, `/products` | `loan_product`, `loan_product_version`, `loan_product_audit_event` · V104 | `ProductVersioningIntegrationTest`, `LoanProductAdminControllerTest` |
| P6 | `partner-product-mapping` | 287 | Eng. Reviewed | ✅ | `ProductLspMappingAdminController` | within `features/products` / `features/lsps` | `loan_product_lsp_mapping` | `ProductLspMappingAdminControllerTest`, `LoanProductLspMappingRepositoryPostgresTest` |

Note: `partner-api-authentication-and-api-clients` is a *platform-setup concern* but its `spec.md` lives under `partner-integration-and-reporting/` — listed as I1 in §4.4. I will review it in Wave 2 with the other identity specs regardless of its folder.

### 4.2 Origination & Underwriting — 5 specs

| # | Spec | Lines | Status | Impl | Primary backend | Frontend | Key tables | Key tests |
|---|---|---|---|---|---|---|---|---|
| O1 | `api-loan-application-intake` | 336 | Eng. Reviewed | ✅ | `LspLoanApplicationApiController`, `LoanApplicationOnboardingService`, `BorrowerOnboardingService`, `BorrowerActiveLoanChecker`, `LspApiIdempotencyService` | — (API-only by D1) | `loan_application`, `borrower`, `loan_application_intake_audit`, `lsp_api_idempotency_record` · V8, V43, V62, V113 | `LspLoanApplicationApiControllerTest`, `LoanApplicationOnboardingServiceLspGuardTest`, `LspApiIdempotencyServiceRaceTest` |
| O2 | `automated-credit-decision-rule-engine` | 404 | Eng. Reviewed | ✅ | `LoanAutoApprovalRuleEngine`, `LoanAutoApprovalGateService`, `LoanApplicationLifecycleService`, `LoanApplicationStatusWriter` | `features/loan-applications` | `loan_application_status_transition` · V9 | `Issue85AutoApprovalIntegrationTest`, `Issue135AutoApprovalStateMachineIntegrationTest`, `LoanAutoApprovalConcurrencyPostgresIntegrationTest` **(uncommitted)** |
| O3 | `kyc-document-checklist-and-gates` | 315 | Eng. Reviewed | ✅ | `LoanApplicationDocumentChecklistService`, `LoanApplicationDocumentRequirements`, `DocumentUploadPolicy` | `features/loan-applications` | `loan_application_document_checklist` | `LoanApplicationDocumentRequirementsTest`, `Issue85Issue135LspDocumentUploadIntegrationTest` |
| O4 | `partner-pre-disbursement-cancellation` | 312 | Eng. Reviewed | ✅ | `LoanApplicationInvalidationService`, `LoanInvalidationReason` | `features/loan-applications` | `loan_application`, `loan_application_audit_event` | *(coverage to verify)* |
| O5 | `ckyc-reporting-and-sftp-submission` | 276 | **Analyst Draft** | ❌ | **none** — `grep -ril ckyc` → 0 files | none | none | none |

### 4.3 Servicing — 8 specs

| # | Spec | Lines | Status | Impl | Primary backend | Frontend | Key tables | Key tests |
|---|---|---|---|---|---|---|---|---|
| S1 | `loan-account-and-repayment-schedule` | 376 | Eng. Reviewed | ✅ | `LoanRepaymentScheduleService`, `LoanFeeCalculator`, `BusinessCalendar` | `features/loan-applications`, `features/my-loans` | `loan_account`, `loan_repayment_schedule_installment` · V97 | `LoanRepaymentScheduleServiceTest`, `LoanFeeCalculatorTest`, `BusinessCalendarTest` |
| S2 | `borrower-bank-detail-updates` | 439 | Eng. Reviewed | ✅ | `BorrowerBankDetailsService`, `BankAccountHolderNameMatcher` | `features/borrowers`, `/borrowers/:id` | `borrower`, `borrower_bank_details_update_audit` · V78 | `Issue62BorrowerBankDetailsIntegrationTest`, `Issue125BankDetailHolderNameMatchIntegrationTest`, `BankAccountMaskingTest` |
| S3 | `disbursement` | 493 | Eng. Reviewed | ✅ | `DisbursementIntentWorkflowService`, `LoanDisbursementWorker(+Processor,+Service)`, `DisbursementPreflightValidator`, `DisbursementOutcomeApplier`, `DisbursementPreviewService` | `features/loan-applications` | `disbursement_intent`, `loan_disbursement_request_log`, `disbursement_outcome_audit`, `bank_mismatch_log` · V82, V90, V98, V111 | `DisbursementIntentWorkflowIntegrationTest`, `DisbursementPreflightValidatorTest`, `DisbursementOutcomeApplierTest`, `Issue62DisbursementWorkerIntegrationTest` |
| S4 | `icici-disbursal-integration` | 297 | **Analyst Draft** | ⚠️ **mock only** | `MockLoanDisbursementAdapter`, `MockIciciDisbursementScenario`, `LoanDisbursementMockProperties` — **no production ICICI client** | — | V98 ICICI fields | `MockIciciDisbursementLifecycleIntegrationTest`, `MockLoanDisbursementAdapterScenarioTest` |
| S5 | `repayment-payment-posting-and-closure` | 359 | Eng. Reviewed | ✅ | `LoanRepaymentCommandService`, `LoanServicingSupportService` | `features/my-loans`, `features/loan-applications` | `loan_payment_transaction` · V92 | `Issue86RepaymentIdempotencyIntegrationTest`, `LoanRepaymentConcurrencyIntegrationTest` |
| S6 | `foreclosure-quote-generation-and-validity` | 439 | Eng. Reviewed | ✅ | `LoanForeclosureCommandService` | `features/loan-applications` | `loan_foreclosure_quote` | `Issue74LspForeclosureExecuteIntegrationTest` |
| S7 | `foreclosure-execution-and-terminal-closure` | 437 | Eng. Reviewed | ✅ | `LoanForeclosureCommandService`, `LoanAccountClosureReason` | `features/loan-applications` | `loan_account`, `loan_foreclosure_quote` · V76 | `Issue74LspForeclosureExecuteIntegrationTest` |
| S8 | `dpd-bucketing-and-delinquency-alerts` | 457 | Eng. Reviewed | ✅ | `LoanDelinquencySupport`, `AlertRuleEvaluationWorker` | `features/alerts`, `features/home` | `loan_delinquency_state`, `ops_alert` · V60, V101 | `LoanDelinquencySupportTest`, `AlertRuleEvaluationWorkerDpdBucketTransitionIntegrationTest` |

### 4.4 Partner Integration & Reporting — 6 specs

| # | Spec | Lines | Status | Impl | Primary backend | Frontend | Key tables | Key tests |
|---|---|---|---|---|---|---|---|---|
| I1 | `partner-api-authentication-and-api-clients` | 469 | Eng. Reviewed | ✅ | *(see P3 — this spec lives here)* | `/api-clients` | `api_client` · V7, V77, V83 | `ApiClientTokenLockoutIntegrationTest` |
| I2 | `webhook-delivery-retry-and-redrive` | 680 | Eng. Reviewed | ✅ | `WebhookOutboxDispatchWorker`, `WebhookOutboxService`, `HttpWebhookDeliveryClient`, `SsrfSafeUrlValidator` | `features/lsps` (subscription cfg) | `webhook_event_outbox`, `webhook_event_delivery_attempt`, `webhook_outbox_redrive_audit` · V66, V70, V86, V88, V99 | `WebhookOutboxServiceDispatchTest`, `WebhookOutboxSoftFourxxAndRedriveTest`, `SsrfSafeUrlValidatorTest` |
| I3 | `lsp-self-service-loan-visibility` | 379 | Eng. Reviewed | ✅ | `LspLoanApiController`, `LspBorrowerApiController`, `LoanApplicationServicingReadService` | `features/my-loans`, `/my-loans`, `/my-loans/:id` | RLS-scoped reads | `LspSurfaceArchitectureTest` |
| I4 | `portfolio-mis-reports` | 512 | Eng. Reviewed | ✅ | `ReportAdminController`, `ReportRequestProcessingWorker`, `AdminReportingService`, `R2ReportStorageService` | `features/reports`, `/reports` | `report_request`, `report_access_audit` · V68, V71, V87 | `AdminReportingServicePortfolioMisExport*Test`, `ReportRequestStorageFailureTest` |
| I5 | `three-way-ledger-and-reconciliation` | 295 | **Analyst Draft** | ❌ | **none** — `grep -ril ledger backend/src/main` → 0 files | none | none | none |
| I6 | `dwh-read-interface-and-reporting-boundary` | 447 | Eng. Reviewed | ❌ | **none** — `grep -ril "dwh\|cdc_"` → 0 files (spec documents the *absence* as a boundary) | none | none | none |

### 4.5 Operations — 6 specs

| # | Spec | Lines | Status | Impl | Primary backend | Frontend | Key tables | Key tests |
|---|---|---|---|---|---|---|---|---|
| N1 | `portfolio-dashboard-and-home-kpis` | 439 | Eng. Reviewed | ✅ | `HomeDashboardController`, `HomeDashboardService`, `PortfolioKpiSnapshotWorker` | `features/home`, `/home` | `portfolio_kpi_snapshot` | `HomeDashboardControllerTest` |
| N2 | `loan-and-borrower-search` | 442 | Eng. Reviewed | ✅ | `BorrowerAdminController`, `BorrowerDirectoryService`, `LoanApplicationQueryService` | `features/borrowers`, `features/loan-applications` | `borrower`, `borrower_lsp_relationship` · V74, V113 | `BorrowerAdminControllerTest`, `LoanApplicationReadRepositoryPostgresTest` |
| N3 | `manual-status-override-and-lifecycle-transitions` | 441 | Eng. Reviewed | ✅ | `LoanApplicationOpsController`, `LoanApplicationStatusTransitioner`, `LoanApplicationStatusReasonCode`, `AdminApiIdempotencyService` | `features/loan-applications`, `/loan-applications/:id` | `loan_application_status_transition`, `admin_api_idempotency_record` · V9 | `LoanApplicationStatusTransitionerTest`, `AdminApiIdempotencyIntegrationTest` |
| N4 | `audit-explorer` | 500 | Eng. Reviewed | ✅ | `AuditExplorerController`, `AuditExplorerService`, `AuditExplorerCursorCodec` | `features/audit`, `/audit` | all `*_audit*` tables | `AuditExplorerController*StreamTest` (5), `AuditExplorerStreamProjectionParityTest` |
| N5 | `operations-alerts-subsystem` | 431 | Eng. Reviewed | ✅ | `OpsAlertController`, `AlertRuleEvaluationWorker`, `AlertRuleSchedulerWorker` | `features/alerts`, `/alerts` | `ops_alert`, `alert_rule` · V60, V95 | `OpsAlertControllerTest`, `AlertRuleSchedulerWorkerTenantContextTest` |
| N6 | `document-upload-storage-and-review` | 560 | Eng. Reviewed | ✅ | `LoanDocumentService`, `ConfigurableLoanDocumentStorageService`, `FileSystemLoanDocumentStorageService`, `DocumentPreviewSupport` | `features/loan-applications` | `loan_application_document_checklist`, `loan_application_document_access_audit` · V75, V80, V85 | `DocumentUploadPostgresIntegrationTest`, `Issue92DocumentDownload*IntegrationTest` (3) |

### 4.6 Authoritative per-domain counts

| Domain | `spec.md` count |
| --- | --- |
| `platform-setup` | 6 (`api-rate-limiting-and-payload-guards`, `internal-authentication-and-sessions`, `partner-lsp-onboarding-and-status-management`, `partner-product-mapping`, `product-catalog-management`, `user-role-and-permission-management`) |
| `origination-and-underwriting` | 5 |
| `servicing` | 8 |
| `partner-integration-and-reporting` | 6 |
| `operations` | 6 |
| **Total** | **31** |

`platform-setup/database-schema/` holds `table-reference.md` + 4 `.mmd` ER diagrams and **no** `spec.md` — it is reference material and will be used as a cross-check during the database review, not reviewed as an area.

---

## 5. Assumed regulatory & deployment context

The repositories do **not** state deployment jurisdiction, hosting region, or Bhawana Capital's licence status. Stated assumptions, to be confirmed:

| Assumption | Basis | Effect if wrong |
| --- | --- | --- |
| **India / RBI** is the governing jurisdiction | `README.md` "Entity: Bhawana Capital (India)", "regulatory context (RBI)" | Changes the entire compliance frame |
| Bhawana is (or will be) an **RBI-regulated entity (RE)** — NBFC or similar | The LSP/RE split, and "LSP" is a term of art defined in RBI's Digital Lending Directions | If Bhawana is *not* an RE, the Digital Lending Directions bind differently and much of the compliance analysis shifts |
| **RBI Digital Lending Directions 2025** apply | Consolidated directions govern RE↔LSP arrangements, data, disclosure (KFS), grievance redressal | Core to origination/partner areas |
| **RBI Master Direction — KYC** applies | Eight-document checklist, CKYC spec exists | Core to O3/O5 |
| **DPDP Act 2023** applies to borrower PII | Aadhaar/PAN handling, consent placeholders in `borrower_lsp_relationship` | Core to data-model review |
| **CERT-In 2022 Directions** (6-hr incident reporting, 180-day log retention) | Indian-hosted regulated system | Affects audit-retention findings |
| PCI DSS **not** applicable | No card data found | Would add scope if cards are planned |
| RBI **outsourcing of IT services** MD applies to R2/S3/MinIO + bank rails | Third-party storage & bank integration | Affects operational-resilience findings |

Where regulation is cited I will link the source, state whether it is **mandatory** or **guidance**, and flag jurisdictional uncertainty. Nothing here is legal advice.

---

## 6. Gaps: specs without code, code without specs, and drift

### 6.1 Specs with no implementation (4)

| Spec | Status | Evidence |
| --- | --- | --- |
| `ckyc-reporting-and-sftp-submission` | Analyst Draft | `grep -ril "ckyc"` over `backend/src/main` + `frontend/src` → **0 files** |
| `three-way-ledger-and-reconciliation` | Analyst Draft | `grep -ril "ledger"` over `backend/src/main` → **0 files** |
| `dwh-read-interface-and-reporting-boundary` | Eng. Reviewed | `grep -ril "dwh\|cdc_"` → **0 files**; spec deliberately documents a boundary, not a build |
| `icici-disbursal-integration` | Analyst Draft | Only `MockLoanDisbursementAdapter` / `MockIciciDisbursementScenario`; **no production bank client** |

These are honestly labelled — the specs do not claim implementation. The reviewable question is whether the *absence* is safe, and specifically whether **S3 disbursement is production-ready with no real bank client and no ledger**.

### 6.2 Significant implementation with **no** owning spec

These are large, high-risk subsystems that no `spec.md` owns end-to-end. This is the more serious direction of the gap, since unspecified mechanisms carry unexamined design decisions.

| Subsystem | Size | Why it matters |
| --- | --- | --- |
| **Tenant isolation architecture** (RLS + routing DataSource + `AdminScopedTransactionExecutor` escape hatch) | 13 classes in `tenant/`, 6 RLS migrations, 23 RLS tables | D8 is the platform's central security control and has **no dedicated spec**. The admin-scoped escape hatch deliberately *bypasses* RLS (used by intake for cross-partner dedup) — an unspecified privilege-escalation surface. |
| **Idempotency framework** (leases, crash recovery, fingerprinting, retention) | ~15 service classes | Referenced piecemeal by many specs; no single owner. Governs financial replay safety. |
| **PII masking & reveal** (`AadhaarMasking`, `PanMasking`, `BorrowerPiiRevealAudit`) | multiple classes + V42 | DPDP-relevant; only partially covered by `loan-and-borrower-search`. |
| **Seed/demo data services** (`LocalDemoPortfolioSeedService`, `SyntheticPortfolioSeedService`, `LocalBootstrapAdminSyncService`) | `seed/` package | Bootstrap-admin sync into a production-shaped app is a standing security question. |
| **Deployment/ops posture** | `infra/docker-compose.yml` only | No spec, no DR/backup/restore evidence. |

### 6.3 Baseline drift (specs are stale)

Specs are stamped to LMS HEAD `2269d064` (2026-07-16). Current HEAD is `bfd571f`:

```
1 commit ahead · 242 files changed, 7228 insertions(+), 3132 deletions(-)
```

Plus an **uncommitted working tree** touching the auto-approval concurrency path:
`LoanApplicationRepository.java`, `LoanApplicationLifecycleService.java`, `LoanApplicationStatusWriter.java`, `Issue85AutoApprovalIntegrationTest.java`, and two new test classes (`LoanApplicationStatusWriterTest`, `LoanAutoApprovalConcurrencyPostgresIntegrationTest`).

So every spec is at least one large commit stale, and **O2 (credit decision engine) is being actively modified right now**. Needs a baseline decision — see §8.

---

## 7. Proposed dependency-based review order

Ordered so each wave's foundations are settled before dependents. Rationale in the right column.

| Wave | Area | Specs | Why here |
| --- | --- | --- | --- |
| **W1** | **Platform Foundations** *(no spec — named subarea)* | tenancy/RLS, admin-scoped escape hatch, idempotency framework | Everything else inherits these. If tenant isolation or idempotency is unsound, findings in every later area change severity. |
| **W2** | Identity & Access | `internal-authentication-and-sessions`, `user-role-and-permission-management`, `partner-api-authentication-and-api-clients`, `api-rate-limiting-and-payload-guards` | AuthN/Z gates every endpoint reviewed later. |
| **W3** | Tenant & Product Configuration | `partner-lsp-onboarding-and-status-management`, `product-catalog-management`, `partner-product-mapping` | Defines the tenants and the priced products all loans reference; product versioning underpins financial correctness. |
| **W4** | Origination | `api-loan-application-intake`, `document-upload-storage-and-review`, `kyc-document-checklist-and-gates`, `automated-credit-decision-rule-engine`, `partner-pre-disbursement-cancellation` | First business workflow; depends on W2+W3. `document-upload` is pulled forward from Operations because the KYC gates depend on it. |
| **W5** | **Servicing & Money Movement** ⚠️ highest risk | `loan-account-and-repayment-schedule`, `borrower-bank-detail-updates`, `disbursement`, `icici-disbursal-integration`, `repayment-payment-posting-and-closure`, `foreclosure-quote-generation-and-validity`, `foreclosure-execution-and-terminal-closure`, `dpd-bucketing-and-delinquency-alerts` | Where real money moves and where the missing ledger bites. Deepest financial-correctness scrutiny. |
| **W6** | Partner Integration & Reporting | `webhook-delivery-retry-and-redrive`, `lsp-self-service-loan-visibility`, `portfolio-mis-reports` | Consumes origination + servicing state. |
| **W7** | Operations & Audit | `manual-status-override-and-lifecycle-transitions`, `audit-explorer`, `loan-and-borrower-search`, `portfolio-dashboard-and-home-kpis`, `operations-alerts-subsystem` | Reviewed last so I can assess whether audit/override actually covers everything W4–W6 does. |
| **W8** | Target-State Gaps | `three-way-ledger-and-reconciliation`, `ckyc-reporting-and-sftp-submission`, `dwh-read-interface-and-reporting-boundary` | Gap analysis; informed by what W5 shows is actually needed. |

**Deviation from a naive order**: `three-way-ledger` is a W8 *report* but its absence is a live W5 risk. I will raise ledger findings in W5 where the money is, and use W8 for target design.

---

## 8. Blockers & materially important ambiguities

Ordered by how much the answer changes the assessment. Items 1–3 I consider genuinely blocking for severity calibration; 4–6 I will proceed on stated assumptions unless corrected.

1. **Prototype or production?** `areas/bhawana/README.md` says *"local Bhawana LMS prototype"*, and both specs and code repeatedly say "prototype". But there is a mock-only bank adapter, no ledger, no DR posture, and a Docker-Compose-only deployment. Severity calibration swings by two levels depending on the answer. *If it is going to production on this codebase, the missing ledger and mock bank client are Critical; if it is a demonstrator, they are Observations with a roadmap.*

2. **Normative baseline** (see §2). Confirm I should treat **D1–D10 + RBI/statutory requirements + fintech practice** as the oracle, and treat spec↔code agreement as weak evidence. Without this, the review degrades into re-confirming `CODE_REVIEW_2026-07-20.md`.

3. **Review baseline commit.** Options: (a) HEAD `bfd571f` clean, (b) HEAD + current uncommitted worktree, (c) the specs' baseline `2269d064`. I recommend **(b)** — it is what actually exists on disk and includes in-flight concurrency fixes to the credit-decision engine — with any finding that depends on uncommitted code explicitly flagged as such.

4. **Regulatory scope** (§5): is Bhawana Capital an RBI-regulated entity, and are the 2025 Digital Lending Directions the right frame? I will proceed assuming yes.

5. **Are the decoy repos in scope?** I will proceed treating `~/Desktop/lms` as the sole implementation and ignoring `lms-greenlight-demo`, `bhawana-website`, `projectloansync`, `project-loansync-prod`.

6. **Business Guide PDF.** D1–D10 are summarised in the area README, sourced from "Bhawana LMS Business Guide — June 2026 §6". `docs/business-workflow-and-use-cases-guide.md` exists in the LMS repo. I will use the README + that markdown as the D1–D10 source unless you have the authoritative PDF.

**Not blocked on**: `gh` access. Per your standing instruction I will not run `gh` against the work account; if I need GitHub data from `Multitude-SE/ferratum-products-specs-res` I will print the command for you to run.

---

## 9. Review checklist (living)

| Wave | Area | State |
| --- | --- | --- |
| — | Deliverable 1 — inventory | ✅ Complete, awaiting confirmation |
| W1 | Platform Foundations (tenancy, idempotency) | ⏸ Pending |
| W2 | Identity & Access (4 specs) | ⏸ Pending |
| W3 | Tenant & Product Configuration (3 specs) | ⏸ Pending |
| W4 | Origination (5 specs) | ⏸ Pending |
| W5 | Servicing & Money Movement (8 specs) | ⏸ Pending |
| W6 | Partner Integration & Reporting (3 specs) | ⏸ Pending |
| W7 | Operations & Audit (5 specs) | ⏸ Pending |
| W8 | Target-State Gaps (3 specs) | ⏸ Pending |
| — | Deliverable 3 — cross-platform synthesis | ⏸ Pending |
| — | Deliverable 4 — remediation backlog | ⏸ Pending |

Specs reviewed: **0 / 31**.
