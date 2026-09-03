# Bhavana LMS Architecture and Business Decision Audit

**Audit date:** 2026-07-23

**Reviewer posture:** Staff-level architecture, implementation, business-rule, and production-readiness audit

**Specifications repository:** `/Users/siddhant/Desktop/work/ferratum-products-specs-res`

**Specifications branch / revision:** `feature/BHAW-10-bhawana-lms-specs` / `a5f701...`

**Implementation repository:** `/Users/siddhant/Desktop/lms`

**Implementation branch / revision:** `main` / `bfd571f3bc1a22c6e4c7d411c7a447cfffe8a7e0`

**Historical spec baseline:** LMS `2269d064f0be50e7f6485c0be38e3cdcef6137d2` plus the then-uncommitted 2026-07-20 worktree

**Code changes made by this audit:** None

> **Remediation update — 2026-07-24:** H-01 has been implemented in the current
> worktree using borrower-scoped pessimistic locking, a write-boundary invariant
> recheck, and concurrent cross-LSP regression tests. The findings below remain
> the historical 2026-07-23 audit baseline.

---

## 1. Executive summary

### Overall verdict

Bhavana is a coherent, unusually well-documented modular-monolith lending prototype. Its central architectural choices—API-only origination, a global borrower identity, product and schedule snapshots, explicit lifecycle states, PostgreSQL row-level tenant isolation, durable idempotency, transactional outbox delivery, leased background work, and a durable disbursement intent—are appropriate for the current team and product maturity. A rewrite or premature microservice split would increase money-safety and operational risk.

The implementation is suitable for management review and synthetic UAT. It is **not ready for unrestricted real-money production or use as the authoritative collections book**. The principal reason is not general code quality: it is the deliberate absence of bank integration, dual authorization, receipt/reversal accounting, reconciliation, privacy lifecycle, and production-operability controls.

### Deployment decision

| Operating mode                                  | Decision           | Conditions                                                                                                                                                                 |
| ----------------------------------------------- | ------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Management demo / synthetic UAT                 | **GO**             | Use synthetic data and mock rails only; label mock behavior clearly.                                                                                                       |
| Controlled partner API pilot without live money | **CONDITIONAL GO** | Fix PAN exposure, dashboard semantics, concurrent open-loan enforcement, test isolation, and define pilot data-retention/support controls.                                 |
| Live ICICI disbursal                            | **NO-GO**          | Requires exclusive live-provider mode, ICICI adapter/security, maker-checker or signed STP caps, approval-time beneficiary control, reconciliation, and production drills. |
| LMS as collections system of record             | **NO-GO**          | Requires receipt/allocation/suspense/reversal ledger, bank/partner reconciliation, correction accounting, and signed accounting policy.                                    |

### Highest-value strengths

1. **Money movement is designed around durable state.** The default disbursement path persists a unique intent before the provider call, leases work, performs the provider call outside the database transaction, and persists ambiguous outcomes for inquiry rather than blindly resending.
2. **Tenant isolation has defense in depth.** Tenant scope is derived from the authenticated principal, missing context fails closed, and PostgreSQL RLS covers the important LSP-owned tables.
3. **Historical loan economics are protected.** Product versions are immutable snapshots referenced by applications and accounts; repayment schedules are generated before disbursal and frozen afterwards.
4. **Partner delivery is operationally credible.** API clients are hashed and revocable; LSP deactivation forms a kill chain; webhooks use an outbox, leases, HMAC, bounded timeouts, retry/dead-letter/redrive, SSRF checks, and attempt evidence.
5. **Business decisions are explicit.** The workflow guide and ADRs record ten foundational decisions rather than leaving them implicit in code.

### Most important risks

1. **Concurrent approval can violate “one open loan per borrower.”** Approval locks only the individual application. Two applications for one borrower can evaluate simultaneously, both observe no open account, and each create a `PENDING_DISBURSEMENT` account. There is no borrower-level serialization or database invariant.
2. **Mock and live disbursement modes are not mutually exclusive.** The mock adapter and outcome endpoint exist in every profile, while the production startup validator checks only bootstrap/JWT secrets. A configuration mistake could run mock behavior in a production-like deployment.
3. **A feature flag can restore the unsafe legacy payout path.** Setting `APP_DISBURSEMENT_INTENT_WORKFLOW_ENABLED=false` invokes the provider inside the transaction without the durable-intent recovery boundary. No startup guard prohibits this in production.
4. **A single administrator can initiate uncapped payout.** There is no maker-checker, approved STP limit, velocity cap, or budget control.
5. **Beneficiary details are frozen too late.** Bank details are copied at intent creation, not approval; an edit between approval and intent creation can redirect payout.
6. **The money model is not an accounting ledger.** Exact-EMI posting works for a demo but cannot faithfully represent partial receipts, bunched payments, suspense, bounce, reversal, correction, or three-way reconciliation.
7. **PII handling is not partner-production ready.** Raw PAN is returned on partner/admin surfaces, raw identity data is copied into audit/alert JSON and MIS, core PII is plaintext at rest, and retention/legal-hold automation is largely absent.

### Recommended strategy

Retain the modular monolith and harden its invariants. Make the next milestone a **production-control slice**, not a feature-volume slice: enforce one-open-loan atomically; make disbursement provider modes exclusive and fail closed; require the intent workflow; add payout authorization and beneficiary affirmation; implement the real bank adapter; and introduce a financial receipt/reconciliation ledger. Add CDC/read-replica reporting only when operational workloads justify it.

---

## 2. Scope, method, and evidence rules

### Scope completed

- Read the Bhavana area index, all five domain indexes, all 31 feature specifications, the historical code review, and both Draw.io sequence-diagram source/render pairs.
- Followed cross-references into LMS business, architecture, deployment, API, ADR, implementation, deferred-work, database, scalability, testing, performance, and production-readiness material.
- Traced implemented behavior through controllers, security filters, DTO mapping, services, repositories, entities, Flyway migrations, workers, adapters, frontend API mappers/components, and tests.
- Compared the specifications' historical LMS baseline with current LMS `main`.
- Ran frontend verification and backend tests without changing source.

### Evidence vocabulary

- **Code-evident:** directly observable in the current source, schema, configuration, or test.
- **Spec-declared:** stated by a specification or decision record; it may describe current or target behavior.
- **Inference:** architectural or business rationale reconstructed from multiple facts. Inferences are labeled.
- **Not verified:** dependent on an unavailable runtime, external system, credential, or production artifact.

### Important baseline qualification

All 31 specifications cite an older implementation baseline (`2269d064...`, reviewed 2026-07-20). This audit uses current LMS `bfd571f...` as implementation truth. The historical `CODE_REVIEW_2026-07-20.md` proves the earlier review scope, not current conformance.

### Tool limitations

- The LMS `AGENTS.md` requires Graphify as the primary repository map. `graphify-out/` is absent and `graphify update .` could not run because the `graphify` executable is not installed. Repository navigation therefore used focused symbol/path search and raw-file verification.
- PostgreSQL/Testcontainers suites could not start in the sandbox because Docker access is unavailable. RLS, PostgreSQL-specific constraints, and `SKIP LOCKED` behavior were reviewed statically and through existing test sources, not re-executed.
- The real ICICI and CKYC endpoints do not exist in the implementation; no external integration could be exercised.

---

## 3. Complete specification and supporting-document inventory

### Bhavana specification inventory

Every feature spec is currently `Draft`. Twenty-eight are `Engineering Reviewed`; three are `Analyst Draft` target-state features.

|   # | Domain            | Feature                                          | Spec status          | Current implementation classification                             |
| --: | ----------------- | ------------------------------------------------ | -------------------- | ----------------------------------------------------------------- |
|   1 | Operations        | Audit explorer                                   | Engineering Reviewed | Implemented with bounded-query and audit-of-audit gaps            |
|   2 | Operations        | Document upload, storage, and review             | Engineering Reviewed | Implemented; malware/quarantine and true review verdict missing   |
|   3 | Operations        | Loan and borrower search                         | Engineering Reviewed | Implemented; sensitive list/search exposure remains               |
|   4 | Operations        | Manual status override and lifecycle transitions | Engineering Reviewed | Implemented; intentional emergency power is broad                 |
|   5 | Operations        | Operations alerts subsystem                      | Engineering Reviewed | Implemented; workflow/audit maturity limited                      |
|   6 | Operations        | Portfolio dashboard and home KPIs                | Engineering Reviewed | Implemented with confirmed semantic/privacy defects               |
|   7 | Origination       | API loan-application intake                      | Engineering Reviewed | Implemented; concurrent one-open-loan invariant missing           |
|   8 | Origination       | Automated credit-decision rule engine            | Engineering Reviewed | Implemented; same concurrency invariant missing                   |
|   9 | Origination       | CKYC reporting and SFTP submission               | Analyst Draft        | Specified target; not implemented                                 |
|  10 | Origination       | KYC document checklist and gates                 | Engineering Reviewed | Implemented as upload-completeness gate, not verification         |
|  11 | Origination       | Partner pre-disbursement cancellation            | Engineering Reviewed | Implemented with rejected-to-invalid classification defect        |
|  12 | Partner/reporting | DWH read interface and reporting boundary        | Engineering Reviewed | Boundary/absence documented; DWH interface not implemented        |
|  13 | Partner/reporting | LSP self-service loan visibility                 | Engineering Reviewed | Implemented with RLS and partner scoping                          |
|  14 | Partner/reporting | Partner API authentication and API clients       | Engineering Reviewed | Implemented                                                       |
|  15 | Partner/reporting | Portfolio MIS reports                            | Engineering Reviewed | Implemented; memory/worker/privacy limitations                    |
|  16 | Partner/reporting | Three-way ledger and reconciliation              | Analyst Draft        | Specified target; not implemented                                 |
|  17 | Partner/reporting | Webhook delivery, retry, and redrive             | Engineering Reviewed | Implemented strongly                                              |
|  18 | Platform          | API rate limiting and payload guards             | Engineering Reviewed | Implemented; fixed global tiers and some fail-open key resolution |
|  19 | Platform          | Internal authentication and sessions             | Engineering Reviewed | Implemented                                                       |
|  20 | Platform          | Partner/LSP onboarding and status management     | Engineering Reviewed | Implemented                                                       |
|  21 | Platform          | Partner-product mapping                          | Engineering Reviewed | Implemented                                                       |
|  22 | Platform          | Product catalog management                       | Engineering Reviewed | Implemented with immutable versions                               |
|  23 | Platform          | User, role, and permission management            | Engineering Reviewed | Implemented                                                       |
|  24 | Servicing         | Borrower bank-detail updates                     | Engineering Reviewed | Implemented; approval snapshot/idempotency gap                    |
|  25 | Servicing         | Disbursement                                     | Engineering Reviewed | Mock implementation; durable core, live controls incomplete       |
|  26 | Servicing         | DPD bucketing and delinquency alerts             | Engineering Reviewed | Implemented                                                       |
|  27 | Servicing         | Foreclosure execution and terminal closure       | Engineering Reviewed | Implemented; correction/ledger implications incomplete            |
|  28 | Servicing         | Foreclosure quote generation and validity        | Engineering Reviewed | Implemented; no expiry/effective-date pricing                     |
|  29 | Servicing         | ICICI disbursal integration                      | Analyst Draft        | Specified target; not implemented                                 |
|  30 | Servicing         | Loan account and repayment schedule              | Engineering Reviewed | Implemented                                                       |
|  31 | Servicing         | Repayment payment posting and closure            | Engineering Reviewed | Implemented exact-EMI slice; not a receipt ledger                 |

### Area-owned supporting files

- `areas/bhawana/README.md`
- Domain indexes:
  - `operations/README.md`
  - `origination-and-underwriting/README.md`
  - `partner-integration-and-reporting/README.md`
  - `platform-setup/README.md`
  - `servicing/README.md`
- Historical audit: `areas/bhawana/CODE_REVIEW_2026-07-20.md`
- Intake diagram source and render:
  - `api-loan-application-intake/diagrams/loan-application-intake-sequence.drawio`
  - `api-loan-application-intake/diagrams/loan-application-intake-sequence.drawio.png`
- Decision-engine diagram source and render:
  - `automated-credit-decision-rule-engine/diagrams/credit-decision-sequence-detailed.drawio`
  - `automated-credit-decision-rule-engine/diagrams/credit-decision-sequence-detailed.drawio.png`

`.DS_Store` files were inventoried but are not architectural evidence.

### LMS supporting sources followed

**Business and architecture**

- `docs/business-workflow-and-use-cases-guide.md`
- `docs/use-cases-and-core-workflows.md`
- `docs/architecture/lms-blueprint.md`
- `docs/architecture/platform-architecture-and-deployment-package.md`
- `docs/architecture/deployment-strategy.md`
- `docs/BRD-executive-brief.md`

**Decision records**

- `docs/adr/0001-adopt-frontend-2-direct-backend-integration.md`
- `docs/adr/0002-lsp-disable-kill-chain.md`
- `docs/adr/0003-loan-origination-is-api-only.md`
- `docs/adr/0004-processing-fee-deduction.md`
- `docs/adr/0005-tenant-scope-from-principal-fail-closed.md`
- `docs/adr/0006-schema-drift-reconciliation.md`

**Implementation and risk records**

- `docs/implementation-log.md`
- `docs/deferred-implementation.md`
- `outputs/production-readiness-report-2026-07-12/PRODUCTION-READINESS-REPORT-2026-07-12.md`
- `docs/database-audit-report-2026-07-03.md`
- `docs/db-audit-remediation-spec-2026-07-05.md`
- `docs/scalability-audit-report-2026-06-14.md`
- `docs/api-consistency-backlog.md`

**API, test, and operations**

- `docs/API-references/*`, `openapi.yaml`, `openapi-lsp.yaml`
- `docs/document-preview-download.md`
- `docs/partner-schedule-validation.md`
- `docs/e2e*.md`, `docs/postman-e2e-collection-plan.md`
- `docs/perf/LOAD_TEST_PLAN.md`, `PERFORMANCE_REPORT.md`, and `PERFORMANCE_REPORT_AGGREGATED.md`
- `docs/runbooks/database-migrations.md`

**Vendor/input artifacts**

- `docs/COMPOSITE PAY API_1.21 (27).pdf`
- `docs/Composite API _ ErrorCodes_1.21 2 (19).xlsx`
- proposal, vendor-question, pricing, and scorecard files under `docs/`

Vendor files were treated as inputs, not proof that an integration exists.

---

## 4. Current architecture

### Structural view

Bhavana is a Java 21 / Spring Boot 3.5 modular monolith with a React/TypeScript SPA, PostgreSQL/Flyway/JPA persistence, Redis/Bucket4j rate limiting, S3-compatible R2 document/report storage, and scheduled workers. The codebase contains roughly 411 main Java files, 159 backend test files, 109 Flyway migrations, and 136 frontend test files.

```text
Admin/ops SPA ───────────────┐
                             ├─> Spring MVC API
LSP OAuth/API clients ───────┘      │
                                    ├─ security + principal-derived tenant context
                                    ├─ controllers / DTOs
                                    ├─ domain services + explicit state machines
                                    ├─ repositories / PostgreSQL RLS / Flyway
                                    ├─ Redis rate limits and caches
                                    ├─ R2 documents and report objects
                                    └─ leased workers
                                         ├─ webhook delivery
                                         ├─ disbursement intent/status
                                         ├─ reports
                                         ├─ DPD snapshots/alerts
                                         └─ retention (idempotency only)
```

### Dependency and ownership boundaries

- **Web layer:** internal and LSP controllers, request validation, role checks, idempotency entry points, API response mapping.
- **Application/domain layer:** onboarding, lifecycle, credit rules, product configuration, disbursement, repayment, foreclosure, reports, alerts, audit, authentication.
- **Persistence layer:** JPA repositories and Flyway-defined invariants. PostgreSQL is part of the security and concurrency model, not interchangeable plumbing.
- **External boundaries:** `LoanDisbursementAdapter`, `DocumentObjectStore`, report object storage, and `WebhookDeliveryClient`.
- **Frontend:** direct REST integration per ADR 0001; domain feature modules map backend contracts to view models.

### Architectural fit

The modular monolith is the right default. Most invariants—borrower uniqueness, lifecycle transitions, schedule generation, payout intent, repayment allocation, and audit/outbox writes—benefit from a single transactional database. Service extraction would be justified only after a boundary has an independent scaling/availability need and an explicit consistency contract.

---

## 5. Business architecture and decision model

The business guide records the following foundational decisions at `docs/business-workflow-and-use-cases-guide.md:560-569`.

| ID  | Decision                                          | Implementation assessment                                                                                                    |
| --- | ------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| D1  | Origination is API-only                           | Implemented. No borrower portal or admin-created origination path.                                                           |
| D2  | Credit decision is automated                      | Implemented with eight deterministic rules and automatic approval/rejection. No underwriter queue.                           |
| D3  | One open loan per borrower across partners        | Semantically implemented at intake/approval, but not atomic under concurrency.                                               |
| D4  | LSP deactivation is a kill chain                  | Implemented across status, clients, tokens, mappings, and authorization checks.                                              |
| D5  | Processing fee Model 1: net cash, gross principal | Implemented and snapshotted through product version/account economics. GST treatment remains outside the current model.      |
| D6  | Only full-installment repayment                   | Implemented intentionally. Suitable for the current demo, insufficient for real collections accounting.                      |
| D7  | Schedule freezes after disbursement               | Implemented. Pre-disbursement replacement is validated; post-disbursement mutation is blocked.                               |
| D8  | Tenant isolation                                  | Implemented through principal-derived scope plus RLS.                                                                        |
| D9  | Every action is auditable                         | Broadly implemented for business mutations; not literally complete for reads, alert acknowledgement, and some admin actions. |
| D10 | No borrower portal                                | Implemented. Borrower-facing flows are out of scope.                                                                         |

The decisions are mutually coherent: this is a partner-operated lending platform, not a consumer LOS. The most consequential mismatch is that D3 is expressed as a read-before-write rule rather than an enforceable invariant.

---

## 6. End-to-end implementation flows

### 6.1 Platform setup

1. Internal users authenticate with JWT/session controls and role checks.
2. LSPs are created and activated/deactivated; API client secrets are stored hashed and tokens are versioned.
3. Products are created with immutable versions; LSP-product mappings govern availability.
4. Authentication establishes an LSP identity; the tenant filter derives `lspId` from the principal.
5. The routing datasource fails closed without a context; PostgreSQL RLS constrains tenant-owned rows.
6. Redis-backed rules rate-limit selected paths; request validation/payload limits protect the web boundary.

### 6.2 Origination and underwriting

1. An authenticated LSP submits an idempotent application.
2. The service validates active LSP/product/mapping, requested economics, and partner external ID.
3. PAN resolves a global borrower; identity conflicts and existing open loans create high-severity alerts and reject intake.
4. The application snapshots the product version, records intake/audit evidence, seeds the document checklist, and enqueues `LOAN_CREATED`.
5. Required document uploads advance checklist completeness.
6. The eight-rule engine rechecks live LSP/product/mapping/range/borrower/document/open-loan state and automatically rejects or moves through `AWAITING_APPROVAL` to `APPROVED_PENDING_DISBURSAL`.
7. Approval creates the loan account and repayment schedule.

Concurrency qualification: steps 6–7 lock neither the global borrower nor an invariant row, so two applications can pass together.

### 6.3 Servicing and money movement

1. Pre-disbursement gates validate state, product/mapping, documents, bank details, schedule, and attempt limits.
2. The default flow creates a durable `disbursement_intent`, deterministic reference, amount, mode, and beneficiary snapshot in Tx-A.
3. A leased worker claims the intent; the provider call occurs outside Tx-A; Tx-B persists success/failure/pending/unknown evidence.
4. The current provider is mock ICICI. Success activates the account and freezes the schedule; ambiguous outcomes use status inquiry/reconciliation state.
5. Repayment accepts exactly one outstanding installment amount, uses database idempotency and pessimistic installment locking, updates account state, and enqueues webhooks.
6. DPD snapshots and alert rules classify delinquency.
7. Foreclosure quotes sum current unpaid principal and interest; execution pays the quote and terminally closes the account.

### 6.4 Partner integration and reporting

1. LSP clients receive tenant-scoped loan lists/details/schedules/documents.
2. Business events are written to the webhook outbox in the same database transaction as state change.
3. A leased dispatcher applies HMAC, SSRF-safe egress validation, bounded HTTP limits, retries, dead-letter classification, alerts, and audited redrive.
4. Internal report requests generate portfolio MIS, persist objects to R2, and expose audited downloads.
5. No DWH/CDC interface or three-way reconciliation exists; reporting remains coupled to the operational database.

### 6.5 Operations

1. Internal users search loans/borrowers and inspect application/account details.
2. Dashboard aggregations provide status, disbursement, DPD, LSP, and activity summaries.
3. Audit explorer merges eight audit/event streams with keyset pagination and bounded windows.
4. Operations alerts expose new/acknowledged states and contextual links.
5. Privileged users can override lifecycle state, manage documents, resolve mock outcomes, initiate disbursement, post repayments, and execute foreclosure subject to role/state checks.

---

## 7. Spec-to-code traceability matrix

Status legend: **Fully implemented** means current behavior aligns materially; **Partially implemented** identifies an implemented slice with a proven gap; **Specified, not implemented** is target state only; **Boundary documented** means the spec intentionally records absence.

Specification paths below are relative to `areas/bhawana/` in the specifications repository.

| Domain            | Feature                          | Specification                                                                          | Code modules / important methods                                        | APIs / UI                                 | Tables / integrations                                           | Representative tests                         | Alignment status                                                                                |
| ----------------- | -------------------------------- | -------------------------------------------------------------------------------------- | ----------------------------------------------------------------------- | ----------------------------------------- | --------------------------------------------------------------- | -------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| Operations        | Audit explorer                   | `operations/audit-explorer/spec.md`                                                    | `AuditExplorerController`, `AuditExplorerService`                       | internal audit API and UI                 | eight audit/event repositories                                  | `AuditExplorerControllerTest`                | Partially implemented: bounded merged read exists; read/audit coverage is not universal         |
| Operations        | Document upload/storage/review   | `operations/document-upload-storage-and-review/spec.md`                                | `LoanDocumentStorageService`, `DocumentUploadPolicy`, checklist service | LSP/internal upload/download; loan detail | document/checklist tables; R2                                   | upload/download/storage integration tests    | Partially implemented: storage/presence gate exists; scan/quarantine/review verdict does not    |
| Operations        | Loan/borrower search             | `operations/loan-and-borrower-search/spec.md`                                          | ops controllers, query/admin services and repositories                  | internal loan/borrower lists/details      | borrower, application, account                                  | controller/search tests                      | Partially implemented: search aligns; sensitive-data exposure needs hardening                   |
| Operations        | Manual lifecycle override        | `operations/manual-status-override-and-lifecycle-transitions/spec.md`                  | `LoanApplicationLifecycleService`, transitioner/writer                  | ops status mutation                       | transitions, audit, webhook outbox                              | `LoanApplicationOpsControllerTest`           | Fully implemented, with broad privileged power as an accepted design                            |
| Operations        | Operations alerts                | `operations/operations-alerts-subsystem/spec.md`                                       | `OpsAlertService`, emitters, evaluation worker                          | `OpsAlertController`, alerts UI           | `ops_alert`, `alert_rule`, DPD snapshots                        | `OpsAlertControllerTest`, rule tests         | Partially implemented: alert queue exists; case workflow/action audit is limited                |
| Operations        | Portfolio dashboard/KPIs         | `operations/portfolio-dashboard-and-home-kpis/spec.md`                                 | `HomeDashboardService`                                                  | home API and `features/home`              | aggregate queries                                               | home service/API mapper tests                | Implemented differently/defect: all-time and DPD90+ values are mislabeled; “masked” name is raw |
| Origination       | API loan-application intake      | `origination-and-underwriting/api-loan-application-intake/spec.md`                     | onboarding, borrower resolution, idempotency                            | `LspLoanApplicationApiController` create  | borrower/relationship/application/intake audit/checklist/outbox | 40 controller tests plus integration tests   | Partially implemented: functional flow aligns; D3 is not concurrency safe                       |
| Origination       | Automated credit rules           | `origination-and-underwriting/automated-credit-decision-rule-engine/spec.md`           | rule engine, gate, lifecycle writer                                     | document-triggered/explicit auto-approval | application/account/schedule/audit/outbox                       | auto-approval/state-machine tests            | Partially implemented: eight rules align; cross-application D3 race remains                     |
| Origination       | CKYC/SFTP                        | `origination-and-underwriting/ckyc-reporting-and-sftp-submission/spec.md`              | none                                                                    | none                                      | no CKYC batch/record/submission/SFTP                            | none                                         | Specified but not implemented; Analyst Draft                                                    |
| Origination       | KYC checklist/gates              | `origination-and-underwriting/kyc-document-checklist-and-gates/spec.md`                | checklist/document requirements                                         | LSP/internal document APIs                | checklist/document/R2                                           | document and auto-approval integration tests | Partially implemented: upload-completeness, not verification                                    |
| Origination       | Partner cancellation             | `origination-and-underwriting/partner-pre-disbursement-cancellation/spec.md`           | invalidation service, transitioner                                      | LSP invalidate API                        | transition/audit/outbox                                         | LSP controller/state tests                   | Implemented differently: `REJECTED -> INVALID` conflicts with terminal taxonomy                 |
| Partner/reporting | DWH read boundary                | `partner-integration-and-reporting/dwh-read-interface-and-reporting-boundary/spec.md`  | operational reporting only                                              | none                                      | no CDC/publication/replica/warehouse role                       | none                                         | Boundary documented; target interface not implemented                                           |
| Partner/reporting | LSP loan visibility              | `partner-integration-and-reporting/lsp-self-service-loan-visibility/spec.md`           | query/response services                                                 | LSP list/detail/schedule/document APIs    | RLS, relationship/access, application/account                   | tenant/LSP integration tests                 | Fully implemented; relationship-table cutover remains deferred                                  |
| Partner/reporting | Partner authentication           | `partner-integration-and-reporting/partner-api-authentication-and-api-clients/spec.md` | API-client auth, token version/cache                                    | token/client/admin APIs                   | hashed API clients, token versions, Redis                       | auth and kill-chain tests                    | Fully implemented                                                                               |
| Partner/reporting | Portfolio MIS                    | `partner-integration-and-reporting/portfolio-mis-reports/spec.md`                      | request/generation/admin reporting                                      | report request/status/download API/UI     | report request/audit; R2                                        | report controller/service tests              | Partially implemented: memory, lease/recovery, PII, retention gaps                              |
| Partner/reporting | Three-way ledger/reconciliation  | `partner-integration-and-reporting/three-way-ledger-and-reconciliation/spec.md`        | none                                                                    | none                                      | no journal/ingest/match/exception case                          | none                                         | Specified but not implemented; Analyst Draft                                                    |
| Partner/reporting | Webhook delivery/redrive         | `partner-integration-and-reporting/webhook-delivery-retry-and-redrive/spec.md`         | outbox service/dispatcher/client                                        | subscription/admin redrive APIs           | outbox/attempt/redrive tables; partner HTTP                     | outbox/admin/SSRF tests                      | Fully implemented                                                                               |
| Platform          | Rate limiting/payload guards     | `platform-setup/api-rate-limiting-and-payload-guards/spec.md`                          | `RateLimitFilter`, key strategies                                       | servlet boundary                          | Redis/Bucket4j; multipart limits                                | filter/rule tests                            | Partially implemented: distributed limits align; fixed plans/key fail-open remain               |
| Platform          | Internal authentication/sessions | `platform-setup/internal-authentication-and-sessions/spec.md`                          | authentication/session/revocation                                       | login/refresh/logout and SPA              | users, refresh sessions, token versions                         | session revocation/auth tests                | Fully implemented                                                                               |
| Platform          | LSP onboarding/status            | `platform-setup/partner-lsp-onboarding-and-status-management/spec.md`                  | LSP admin/status services                                               | admin LSP API/UI                          | LSP, clients, mappings, tokens, audit                           | `LspStatusKillChainIntegrationTest`          | Fully implemented                                                                               |
| Platform          | Partner-product mapping          | `platform-setup/partner-product-mapping/spec.md`                                       | mapping service                                                         | mapping admin API/UI                      | `loan_product_lsp_mapping`                                      | mapping controller tests                     | Fully implemented                                                                               |
| Platform          | Product catalog                  | `platform-setup/product-catalog-management/spec.md`                                    | `ProductConfigurationService`                                           | product/version admin API/UI              | product plus immutable versions                                 | product-versioning tests                     | Fully implemented                                                                               |
| Platform          | Users/roles/permissions          | `platform-setup/user-role-and-permission-management/spec.md`                           | user/session services, Spring Security                                  | user admin API/UI                         | users/roles/sessions                                            | `UserAdminControllerTest`                    | Fully implemented                                                                               |
| Servicing         | Bank-detail updates              | `servicing/borrower-bank-detail-updates/spec.md`                                       | borrower update/audit services                                          | borrower/admin bank API                   | borrower and update audit                                       | issue 62/125 integration tests               | Partially implemented: validation/audit align; approval snapshot/idempotency missing            |
| Servicing         | Disbursement                     | `servicing/disbursement/spec.md`                                                       | command, intent workflow, adapter, outcome applier                      | ops initiate/mock outcome; worker         | account/intent/request log/outbox; mock provider                | intent/worker/mock lifecycle tests           | Partially implemented: durable mock workflow; no safe live mode                                 |
| Servicing         | DPD/delinquency                  | `servicing/dpd-bucketing-and-delinquency-alerts/spec.md`                               | DPD snapshot and alert evaluation                                       | ops reads; scheduled workers              | installment/account/snapshot/alert                              | bucket-transition tests                      | Fully implemented for current policy                                                            |
| Servicing         | Foreclosure execution            | `servicing/foreclosure-execution-and-terminal-closure/spec.md`                         | foreclosure command service                                             | execute quote API/UI                      | quote/payment/installment/account/audit/outbox                  | foreclosure integration tests                | Partially implemented: happy path aligns; correction/reversal authority incomplete              |
| Servicing         | Foreclosure quote                | `servicing/foreclosure-quote-generation-and-validity/spec.md`                          | foreclosure command/query                                               | quote request/read API/UI                 | foreclosure quote                                               | quote integration tests                      | Partially implemented: no expiry or effective-date pricing                                      |
| Servicing         | ICICI integration                | `servicing/icici-disbursal-integration/spec.md`                                        | adapter seam only; no real adapter/crypto                               | mock-shaped operations only               | no ICICI config/poll/recon schema; mock provider                | mock ICICI tests                             | Specified but not implemented; Analyst Draft                                                    |
| Servicing         | Account/schedule                 | `servicing/loan-account-and-repayment-schedule/spec.md`                                | status writer, schedule service/validator                               | internal/LSP account/schedule APIs        | account, installments, product version                          | schedule/product integration tests           | Fully implemented                                                                               |
| Servicing         | Repayment/closure                | `servicing/repayment-payment-posting-and-closure/spec.md`                              | repayment command/servicing support                                     | internal repayment API/UI                 | payment transaction/installments/account/idempotency/outbox     | idempotency/concurrency tests                | Partially implemented: exact-EMI slice aligns; receipt/reversal ledger absent                   |

---

## 8. Area-by-area architecture and business analysis

### 8.1 Operations

#### Audit explorer

The explorer is a read model over eight separate audit/event streams rather than a new canonical log. That avoids migration risk and preserves source evidence. Keyset pagination and a seven-day default / 90-day maximum bound are sound operational choices. The compromise is semantic inconsistency across streams and incomplete “audit of audit”: viewing sensitive history is not uniformly captured.

#### Document upload, storage, and review

The upload boundary validates size, extension/content type, magic bytes, filename traversal, checksum, and storage metadata. The service separates object storage from database metadata and audits downloads. “Uploaded” currently satisfies document completeness; there is no verified/rejected reviewer verdict, malware scan, CDR, quarantine, or delayed release. For identity documents in production, this is a missing security and compliance control, not merely UX polish.

#### Loan and borrower search

Admin search is direct and operationally useful, with bounded pages and query-specific repository methods. Global borrower identity allows cross-LSP operations while RLS protects partner paths. Raw PAN and other identity fields on list/detail and MIS surfaces conflict with least-privilege disclosure and the deferred masking matrix.

#### Manual status override and lifecycle transitions

State transitions use an explicit transitioner/writer, append transition and audit rows, and enqueue status webhooks. Emergency override power is intentionally centralized in privileged roles. The architecture should retain this escape hatch, but production needs reason taxonomy, stronger step-up/maker-checker for money-relevant overrides, mandatory idempotency, and periodic override review.

#### Operations alerts

Alerts are persisted domain signals, not transient logs. Emitters cover identity conflicts, webhook dead letters, DPD, disbursement reconciliation, and manual overrides. Acknowledgement mutates the alert row; there is no separate action history, assignment, snooze, close reason, or escalation/SLA model. This is adequate for a prototype queue but not a mature case-management subsystem.

#### Portfolio dashboard and home KPIs

The dashboard uses backend aggregates and current/recent activity rather than loading raw portfolios. Two mappings are incorrect: all-time disbursed value is labeled MTD, and DPD90+ is labeled all overdue. A raw borrower name is mapped into a field named `borrowerNameMasked`. These are decision-quality and privacy defects because the UI asserts stronger semantics than the backend supplies.

### 8.2 Origination and underwriting

#### API loan-application intake

The API-only model is appropriate for partner-led origination. Intake validates tenant ownership, active configuration, economics, global PAN identity, partner external ID, and documents; it records an intake audit and webhook in the transaction. PAN uniqueness resolves simultaneous creation of the same new borrower, and the service retries a uniqueness race.

The D3 check is not atomic. Existing-open-loan queries do not lock the borrower or a guard row, and the schema has no “one open account per borrower” invariant. Intake also copies raw identity data into audit/alert JSON. Fix concurrency before a partner pilot; fix PII before real data.

#### Automated credit-decision rule engine

The engine contains eight readable, deterministic rules: active product, active LSP, active mapping, amount, tenure, required borrower fields, required documents, and no other open loan. It returns structured failures and delegates state mutation to lifecycle services. This is simpler and safer than an external rules engine at current complexity.

Decision-time re-evaluation is a strength, but simultaneous applications can both pass because each approval transaction reads without borrower-level serialization. There is also no human exception/underwriter queue by business decision; any override is an administrative lifecycle action and should be monitored as such.

#### CKYC reporting and SFTP submission

This is an Analyst Draft target, not implemented behavior. There are no CKYC entities, batches, generation workers, SFTP client, acknowledgement ingestion, retry/reconciliation, admin UI, or retention controls. The spec still needs decisions on regulatory applicability, file/channel format, submission gate, correction authority, credentials, frequency, and retention. It should not be scheduled until compliance owns those inputs.

#### KYC document checklist and gates

Checklist seeding and required-upload checks are integrated into intake, approval, and disbursement. The architecture provides a useful deterministic gate and LSP self-service upload. It establishes document presence, not authenticity or verification. Rename/communicate that distinction or implement reviewer/automated verification states before relying on it as a KYC control.

#### Partner pre-disbursement cancellation

The invalidation path is tenant-scoped, state-gated, audited, and webhooked. A confirmed state-model mismatch allows `REJECTED -> INVALID`: `REJECTED` is terminal in the state model but is not classified as “entered servicing,” and invalidation checks only the latter. Decide whether rejected applications may be reclassified; then align the transitioner, endpoint contract, and reporting taxonomy.

### 8.3 Partner integration and reporting

#### DWH read interface and reporting boundary

The spec correctly documents an absent boundary: no CDC publication, logical replication, read replica, warehouse role, or analytical API exists. Current reports read the operational database. That is acceptable at current volume, but DWH consumers must not be granted direct ad hoc access to the primary. Introduce a read replica/CDC contract when report concurrency or analytical consumers become real.

#### LSP self-service loan visibility

Partner list/detail, schedule, document, and status surfaces consistently derive tenant scope from authenticated LSP identity. RLS supplies a database backstop, and borrower visibility is modeled separately from the global borrower identity. The residual dual-write between `borrower_lsp_access` and the new relationship table is explicitly deferred; parity monitoring is important until cutover.

#### Partner API authentication and API clients

Client secrets are hashed, clients can be revoked, access tokens are versioned, and authentication is coupled to LSP active status. Deactivation revokes clients/tokens and disables mappings, providing a credible kill chain. Production still needs secrets-manager issuance/rotation runbooks, client ownership, expiry policy, and tested emergency revocation.

#### Portfolio MIS reports

Asynchronous report requests, object storage, status tracking, and download audit are appropriate. Generation fetches keyset batches but appends the whole CSV to a `StringBuilder` and materializes a `byte[]`; the request worker also spans generation/storage within a broad transaction and lacks a stale-`PROCESSING` lease/recovery protocol. The report includes high-risk PII. Stream to storage, lease jobs, minimize columns by audience, encrypt objects, and enforce retention.

#### Three-way ledger and reconciliation

This Analyst Draft target is entirely absent: no immutable/double-entry ledger, bank/LSP file ingest, matching engine, suspense, exception cases, or shadow reconciliation. The current account/installment/payment rows are operational state, not a financial subledger. This is a launch dependency for authoritative money accounting, not a reason to replace the whole application with event sourcing.

#### Webhook delivery, retry, and redrive

This is one of the strongest areas. The transaction outbox, `FOR UPDATE SKIP LOCKED` leasing, HMAC, SSRF checks at egress, bounded connect/read/body limits, eight attempts with backoff, permanent-failure classification, dead-letter alerts, and audited capped redrive form a coherent delivery subsystem. Add per-partner SLO dashboards and retention, but preserve the design.

### 8.4 Platform setup

#### API rate limiting and payload guards

Redis/Bucket4j provides distributed rather than node-local limits, and rules are explicit per path/key. Payload/multipart validation complements throttling. LSP tiers are globally fixed, most reads are unmetered, and a selected LSP rule whose key cannot be resolved permits the request. Authenticated write paths reduce the practical bypass, but production should fail closed for configured protected routes and expose limiter degradation metrics.

#### Internal authentication and sessions

JWT/session issuance, refresh rotation/revocation, password hashing, role enforcement, and non-local secret startup validation provide a sound baseline. Continue to treat session revocation and user deactivation as security-critical integration paths. Production requirements still include identity-provider/MFA decisions, secrets management, operator lifecycle, and incident evidence.

#### Partner/LSP onboarding and status management

LSP status is a real control plane, not a label: disabled LSPs lose client/token/mapping utility and fail business checks. This implements ADR 0002 well. Consider maker-checker and effective-date scheduling for activation/deactivation once partner changes carry contractual or monetary effect.

#### Partner-product mapping

The mapping provides an explicit allowlist checked at intake and decision time. Disabling a mapping prevents new activity without mutating historical loans. This is the correct boundary for partner eligibility. Per-LSP economics are not modeled; the current decision is a shared product version plus enablement.

#### Product catalog management

Mutable catalog identity plus immutable versions is a strong bitemporal-lite design: new applications snapshot the latest version while existing applications/accounts retain economics. Schedule validation protects partner-provided schedules. Preserve immutable versions; add four-eyes approval/effective dating only when product changes go live without deployment review.

#### User, role, and permission management

Roles are coarse and understandable (`SYSTEM_ADMIN`, operations-oriented access, partner identity). This reduces policy ambiguity at prototype scale. As duties separate, introduce capability-level permissions for payout approval, reconciliation resolution, PII reveal, report export, and security administration instead of proliferating ad hoc role checks.

### 8.5 Servicing

#### Borrower bank-detail updates

Updates validate fields, match holder names, block edits during in-flight disbursement, and append audit evidence. The unresolved risk is the approval-to-intent window: payout uses live borrower details until intent creation. Update commands also lack a first-class idempotency contract. Add approval-time snapshot/reaffirmation and require idempotency.

#### Disbursement

The default durable-intent path is well structured: application locking, gates, database uniqueness, deterministic references, leased claims, provider call outside the transaction, unknown-outcome recovery, and outcome application. It is mock-only. A configuration switch can bypass this design and use the legacy inline provider call, and mock endpoints/adapters are profile-independent. Treat intent workflow as mandatory and live/mock selection as a fail-closed startup invariant.

#### DPD bucketing and delinquency alerts

DPD derives from installment state, snapshots bucket transitions, and drives persisted alert rules with worker serialization/advisory locking. This is suitable as an operational read model. Define timezone/cutoff, holiday, moratorium, write-off, and backdated-correction policies before regulatory or bureau reporting consumes it.

#### Foreclosure execution and terminal closure

Execution locks and validates an active quote, records payment effects, settles installments, closes the account/application, audits, and emits webhooks. Without receipt/reversal ledger support, a mistaken or reversed foreclosure cannot be corrected faithfully. Production should make execution idempotent and subject it to payment authority controls.

#### Foreclosure quote generation and validity

Only one active quote is maintained and prior active quotes are superseded. The requested effective date is stored, but the amount is current unpaid principal plus interest and does not vary by effective date; there is no expiry window. Add accrual/charge policy, expiry, holiday/cutoff rules, and a quote version/hash before externalizing quotes.

#### ICICI disbursal integration

The target spec correctly distinguishes the existing state machine from the missing provider adapter. Missing items include Composite Pay/Status HTTP, encryption/decryption/certificates, secrets and allowlists, full error taxonomy, duplicate/deemed-approved/reversal/return handling, poll evidence, T+1/T+2 reconciliation, and production startup guards. Vendor decisions on rail/version/callback/limits/certificates/MIS remain unresolved.

#### Loan account and repayment schedule

Approval creates an account tied to the application, borrower, LSP, product, and product version. Schedule rows use optimistic versioning and are validated; post-disbursement replacement is blocked. This is an appropriate operational model. Keep schedules as contract snapshots and represent later corrections through additive adjustments, not mutation.

#### Repayment payment posting and closure

The command uses database idempotency, short transactional work, pessimistic installment locking, optimistic versions, and terminal closure/webhook behavior. Exact-EMI enforcement faithfully implements D6. It cannot represent a real payment receipt lifecycle; add a receipt/allocation/suspense/reversal layer without discarding the reliable installment/account projection.

---

## 9. Architecture decision records reconstructed from code and specs

| ADR        | Decision                                        | Current suitability rating                                                                     |
| ---------- | ----------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| ADR-BH-001 | Modular monolith and direct SPA/API             | **Strong and appropriate**                                                                     |
| ADR-BH-002 | API-only partner origination                    | **Strong and appropriate**                                                                     |
| ADR-BH-003 | Global borrower and one-open-loan rule          | **Needs redesign before scale** for atomic enforcement; identity model remains appropriate     |
| ADR-BH-004 | Deterministic in-process credit rules           | **Appropriate with minor improvements**                                                        |
| ADR-BH-005 | Immutable product versions and frozen schedules | **Strong and appropriate**                                                                     |
| ADR-BH-006 | Principal tenant context plus PostgreSQL RLS    | **Strong and appropriate**                                                                     |
| ADR-BH-007 | Durable disbursement intent                     | **Strong and appropriate** when mandatory; legacy bypass is a **material production risk**     |
| ADR-BH-008 | Processing-fee Model 1                          | **Acceptable due to current constraints**, pending finance/legal confirmation                  |
| ADR-BH-009 | Exact-installment posting                       | **Acceptable due to current constraints**; **needs redesign before** authoritative collections |
| ADR-BH-010 | Transactional webhook outbox                    | **Strong and appropriate**                                                                     |
| ADR-BH-011 | Privileged operations/no borrower portal        | **Appropriate with minor improvements**                                                        |
| ADR-BH-012 | Operational reporting before DWH                | **Acceptable due to current constraints**                                                      |

### ADR-BH-001 — Retain a modular monolith and direct SPA/API integration

- **Context:** Most lending invariants span borrower, application, account, schedule, payment, audit, and outbox state.
- **Decision:** One deployable backend and database; the React SPA calls backend APIs directly.
- **Evidence:** package/service/repository organization; ADR 0001; single Maven application.
- **Rationale (inference):** transactional consistency and delivery speed outweigh independent service scaling.
- **Alternatives:** microservices per domain; BFF plus services; event-sourced platform.
- **Consequences:** simpler atomicity and operations, but reporting/background workloads share the deployment and schema.
- **Assessment:** **Retain.** Extract only proven hot or independently governed boundaries.

### ADR-BH-002 — API-only partner origination

- **Decision:** LSP APIs create applications; internal UI operates but does not originate.
- **Evidence:** ADR 0003, D1, LSP intake controller.
- **Consequences:** a clear source-of-truth and partner accountability; no assisted/internal origination fallback.
- **Assessment:** **Retain.** Build an exception path only if the business explicitly changes D1.

### ADR-BH-003 — Global borrower identity with cross-partner one-open-loan rule

- **Decision:** Normalize PAN into one global borrower and grant per-LSP relationships/visibility.
- **Evidence:** unique `uk_borrower_pan`, borrower relationship/access tables, cross-LSP checker, D3.
- **Rationale (inference):** avoid duplicate identity and cross-partner over-lending.
- **Alternatives:** tenant-local borrowers; master-party service; external identity provider.
- **Consequences:** stronger risk view but heightened privacy/governance responsibility and cross-tenant concurrency.
- **Assessment:** **Retain and harden.** Add borrower-level lock/guard or atomic DB invariant.

### ADR-BH-004 — Deterministic in-process automated credit rules

- **Decision:** Eight code-defined rules automatically approve/reject; no underwriter queue.
- **Evidence:** `LoanAutoApprovalRuleEngine`, D2.
- **Alternatives:** manual underwriting; configurable DB rules; external decision engine/model.
- **Consequences:** transparent/reproducible outcomes but code deployment for policy changes and no exception workflow.
- **Assessment:** **Retain now.** Externalize only when policy owners need independent change cadence.

### ADR-BH-005 — Immutable product versions and frozen schedules

- **Decision:** Applications/accounts reference an immutable product version; schedules freeze at disbursal.
- **Evidence:** V104, product version service, schedule validation, D7.
- **Alternatives:** mutable product FK; full event sourcing; contract-document-only state.
- **Consequences:** reliable historical economics with additional version-management complexity.
- **Assessment:** **Retain.**

### ADR-BH-006 — Principal-derived tenant context plus PostgreSQL RLS

- **Decision:** Derive LSP scope from authentication, route with explicit context, and enforce RLS.
- **Evidence:** ADR 0005, tenant filter/datasource, V41/V45.
- **Alternatives:** controller parameters/application filtering only; schema/database per tenant.
- **Consequences:** defense in depth and shared operations view; PostgreSQL-specific tests and careful admin-scope handling.
- **Assessment:** **Retain.** Never accept tenant identity from request payload as authority.

### ADR-BH-007 — Durable intent before external payout

- **Decision:** Persist one live intent and deterministic reference before provider side effects; use leased execution and ambiguous-outcome recovery.
- **Evidence:** V111 and intent workflow service.
- **Alternatives:** synchronous provider call in request transaction; message-broker command; provider-owned idempotency alone.
- **Consequences:** strong duplicate protection and recoverability; more worker/state complexity.
- **Assessment:** **Make mandatory.** Remove or prohibit the legacy inline path in production.

### ADR-BH-008 — Processing-fee Model 1

- **Decision:** Gross sanctioned principal is owed; processing fee is withheld; borrower receives net cash.
- **Evidence:** ADR 0004, D5, disbursement amount calculation.
- **Alternatives:** fee collected separately; fee capitalized above principal; fee deducted but not financed.
- **Consequences:** schedule/account remain on gross principal and payout is lower.
- **Assessment:** **Retain if finance/legal confirms disclosure and tax treatment.**

### ADR-BH-009 — Full-installment posting and frozen operational projection

- **Decision:** Public repayment accepts exactly one installment’s outstanding amount.
- **Evidence:** D6 and repayment command validation.
- **Alternatives:** receipt allocator; partner-preallocated posting; full double-entry ledger.
- **Consequences:** simple closure logic but inability to book real-world exceptions.
- **Assessment:** **Retain as a projection, not as the future accounting boundary.**

### ADR-BH-010 — Transactional webhook outbox

- **Decision:** Persist events with business changes and deliver asynchronously.
- **Evidence:** outbox service/migrations, dispatcher, delivery attempts/redrive.
- **Alternatives:** synchronous callback; broker publish without DB outbox; CDC-to-broker.
- **Consequences:** reliable delivery and replay with operational tables/workers.
- **Assessment:** **Retain.**

### ADR-BH-011 — Privileged operations over separate borrower-facing UX

- **Decision:** Internal/LSP operational surfaces only; no borrower portal.
- **Evidence:** D10, role-specific APIs and SPA.
- **Alternatives:** omnichannel portal; separate ops service; partner-only black box.
- **Consequences:** focused scope, but internal roles carry broad sensitive powers.
- **Assessment:** **Retain scope; split sensitive capabilities as production duties mature.**

### ADR-BH-012 — Operational reporting now, analytical boundary later

- **Decision:** Generate bounded reports from operational data and store results in R2; explicitly defer DWH.
- **Evidence:** report services and DWH-boundary spec.
- **Alternatives:** direct SQL consumers; read replica; CDC/lakehouse.
- **Consequences:** fast delivery but primary-load, memory, and schema-coupling risks.
- **Assessment:** **Accept at low volume; add replica/CDC before broad analytics access.**

---

## 10. Findings

### Severity model

- **Critical:** active path can plausibly cause catastrophic money, security, regulatory, or irreversible integrity harm without unusual preconditions.
- **High:** serious business/security/integrity risk or a launch-blocking missing control.
- **Moderate:** meaningful correctness, operability, privacy, or maintainability defect with bounded impact.
- **Low:** localized robustness/documentation/cleanup issue.

No unconditional critical defect was demonstrated in the present **mock-only synthetic-UAT** operating mode. Several controls become P0 launch gates the moment real money or authoritative collections are introduced.

### High findings

#### H-01 — One-open-loan rule is not concurrency safe

- **Impact:** Two applications for one borrower can be approved concurrently and both create open accounts, defeating a core credit-risk rule across LSPs.
- **Evidence:** approval loads with `findDetailedById`, not the available `findByIdForUpdate` (`LoanApplicationLifecycleService.java:226-266,319-321`); the rule performs a non-locking cross-LSP read (`LoanAutoApprovalRuleEngine.java:111-123`); account creation is unique only by application (`V17__loan_account.sql:1-16`).
- **Trigger:** two eligible applications for the same borrower reach auto-approval concurrently before either account commits.
- **Recommendation:** serialize on a stable borrower guard row (`SELECT ... FOR UPDATE`) and recheck inside the same transaction; add a database-enforced guard/invariant where feasible; add a two-application concurrent approval PostgreSQL test.
- **Owner:** Credit platform + database.

#### H-02 — Mock/live disbursement is not fail-closed

- **Impact:** A production-like instance can start with the mock adapter and accept manual mock outcomes.
- **Evidence:** `MockLoanDisbursementAdapter` is unconditional; mock outcome is registered for every profile; defaults auto-resolve mock outcomes (`application.yml:174-196`); startup validator checks only bootstrap/JWT secrets (`UnsafeDeploymentConfigurationValidator.java:24-62`); deferral S6 (`docs/deferred-implementation.md:30-41`).
- **Recommendation:** explicit `app.disbursement.provider=mock|icici`; conditional beans/controllers; forbid mock under production profiles; expose selected provider in health/info.
- **Launch posture:** P0 before real money.

#### H-03 — Intent workflow can be disabled into an unsafe legacy provider call

- **Impact:** A configuration change restores an external provider call inside the surrounding database transaction without durable intent/unknown-outcome recovery.
- **Evidence:** feature switch branches to `initiateDisbursementInline` (`LoanDisbursementCommandService.java:147-164`); provider call occurs at `:167-205`; configuration permits disabling (`application.yml:187-191`).
- **Recommendation:** remove the inline path or confine it to tests; production startup must require the intent workflow.

#### H-04 — No disbursement maker-checker or signed STP controls

- **Impact:** One `SYSTEM_ADMIN` or worker can initiate uncapped payout without a second principal, amount/budget/velocity control, or approval queue.
- **Evidence:** single-principal ops endpoint and worker; deferral S14 (`docs/deferred-implementation.md:56-67`).
- **Recommendation:** risk-approved STP thresholds plus maker-checker above them; enforce maker ≠ checker, daily/partner caps, idempotent approvals, and immutable decision evidence.
- **Launch posture:** P0 before live rails.

#### H-05 — Beneficiary is not frozen at approval

- **Impact:** A bank-detail change after approval but before intent creation changes the payout destination.
- **Evidence:** intent copies live borrower values at creation (`DisbursementIntentWorkflowService.java:75-105`); deferral S5 (`docs/deferred-implementation.md:17-28`).
- **Recommendation:** snapshot at approval; compare at disbursement; fail closed on change and require audited reaffirmation.

#### H-06 — No authoritative receipt/reversal/reconciliation ledger

- **Impact:** partial/bunched/advance receipts, suspense, bounce, reversal, correction, and bank/LSP mismatches cannot be faithfully accounted.
- **Evidence:** exact-installment validation; no receipt/allocation/reversal/suspense tables; three-way reconciliation is target-only; deferral S13 (`docs/deferred-implementation.md:43-54`).
- **Recommendation:** immutable receipt and journal layer, deterministic allocator, suspense, additive reversal/correction, and shadow three-way reconciliation before cutover.
- **Launch posture:** P0 for collections system-of-record use.

#### H-07 — PII minimization, masking, encryption, and lifecycle are incomplete

- **Impact:** raw PAN/Aadhaar/bank data is exposed to more surfaces and copies than necessary; a breach or overbroad partner access has increased impact.
- **Evidence:** raw PAN in `LspLoanApplicationResponses.java:39,102`; raw PAN mapped to `borrowerPanMasked` in `features/my-loans/api.ts:256-257`; raw identity in onboarding alert JSON (`BorrowerOnboardingService.java:213-249`); high-risk MIS fields (`PortfolioMisCsvWriter.java:68-78,124-134`); deferrals S15/S18/D4 (`docs/deferred-implementation.md:69-80,108-128`).
- **Recommendation:** approved disclosure matrix; backend masking by audience; audited break-glass reveal; envelope encryption/tokenization; structured redaction; object encryption/expiry; legal hold and purge manifests.

#### H-08 — Uploaded identity documents have no malware/quarantine/review control

- **Impact:** authorized users may retrieve malicious content; uploaded does not mean verified KYC.
- **Evidence:** `DocumentUploadPolicy` validates type/magic/size/name but there is no scan/quarantine/CDR service or verdict workflow.
- **Recommendation:** quarantine on upload, asynchronous malware/CDR, clean-only download, verified/rejected reviewer states, scan evidence and retention.

#### H-09 — Production capacity, DR, and PostgreSQL control evidence is incomplete

- **Impact:** RLS, leases, migration behavior, RTO/RPO, and workload isolation may fail operationally despite sound code design.
- **Evidence:** PostgreSQL suites were skipped locally; DWH/reporting shares the primary; no current production restore/failover/capacity evidence was available.
- **Recommendation:** CI-gated PostgreSQL suites; load tests at signed volumes; backup/restore and failover drills; connection/worker budgets; RTO/RPO sign-off; dashboarded SLOs.

### Moderate findings

#### M-01 — Rejected applications can be reclassified as invalid

`LoanApplicationStatus` calls `REJECTED` terminal, but `isPreDisbursal`/servicing classification and the invalidation service permit `REJECTED -> INVALID`. Align business taxonomy, transition rules, partner contract, and reporting.

#### M-02 — Dashboard labels and masking semantics are incorrect

`HomeDashboardService.java:86-95` returns all-time disbursed and DPD90+ counts; `features/home/api.ts:142-144` maps them to MTD and all-overdue fields; `InternalKpiSummary.tsx:91,94` displays those labels. Raw names are passed through a “masked” field. Correct backend DTO names and UI copy together.

#### M-03 — Foreclosure quote validity is under-specified

Quotes store an effective date but use current unpaid principal/interest and have no expiry window. Define accrual, fees, expiry, holidays/cutoffs, and stale-quote enforcement.

#### M-04 — MIS generation is memory-bound and weakly leased

Keyset database reads still append the complete CSV in memory and materialize bytes. `PROCESSING` jobs lack explicit lease/recovery. Stream to object storage and reclaim stale work idempotently.

#### M-05 — Backend test isolation is unreliable in the reviewed environment

With a Byte Buddy agent workaround, 722 tests ran with 0 failures, 17 errors, and 85 skipped. One opt-in R2 “test” reads repository `.env` credentials and performs real create/delete network I/O. Sixteen webhook test setup errors delete LSPs while shared-context loan accounts still reference them. Separate probes from unit/integration tests and use the central database cleaner or isolated contexts.

#### M-06 — Alert acknowledgement and some sensitive reads lack full action history

Alert state is mutable and several read/reveal paths are not consistently audit-covered. Add append-only alert action history and explicit PII/document/report access events.

#### M-07 — Idempotency is optional or absent on some privileged mutations

Admin disbursement initiation accepts optional idempotency, while bank edits/foreclosure and selected overrides do not consistently require it. Require durable keys for money-relevant and externally retried admin commands.

#### M-08 — LSP rate-limit key resolution can fail open

When a configured rule matches but the LSP key cannot be resolved, the filter permits the request. Fail closed for protected LSP paths and emit a configuration/security metric.

### Low findings

- The new borrower-relationship table remains dual-written while legacy access remains authoritative; this is an explicit maintainability deferral.
- Some specification vocabulary (“review,” “masked,” “MTD,” “terminal”) overstates implementation semantics.
- Operational reports, audit streams, and webhook evidence have no unified retention catalog.

### Findings by leadership review lens

| Review lens                        | Findings                                                                                                                                                           |
| ---------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Critical production / money safety | No unconditional critical defect in mock UAT. H-02 through H-06 and H-09 become live-launch blockers; H-01 can create duplicate open accounts.                     |
| High-priority architecture         | H-01 concurrency invariant, H-03 legacy payout bypass, H-06 missing financial journal, H-09 production evidence.                                                   |
| Compliance and audit               | H-07 PII/lifecycle, H-08 document trust, M-06 action/read audit, CKYC decisions and implementation absent.                                                         |
| Specification mismatch             | Dashboard semantics (M-02), `REJECTED -> INVALID` (M-01), “uploaded” versus “reviewed,” raw values named “masked,” three Analyst Draft targets absent as declared. |
| Maintainability                    | Legacy disbursement branch, borrower visibility dual-write, broad roles, report worker lifecycle, semantic DTO naming.                                             |
| Performance/scalability            | In-memory MIS (M-04), primary-database reporting, unbounded non-idempotency retention streams, fixed rate plans, unverified capacity/DR (H-09).                    |
| Operational weakness               | Mock/live ambiguity, no maker-checker, late beneficiary snapshot, no reconciliation, limited alert workflow, non-hermetic tests.                                   |
| Documentation gap                  | Launch mode is not declared as a single acceptance baseline; several business policies remain unresolved; spec implementation baselines are stale.                 |
| Preserve                           | Modular monolith, API-only intake, product versions, frozen schedules, tenant RLS, client kill chain, idempotency framework, durable intent, webhook outbox.       |

### Finding-type classification

| Finding                                                | Type                                                                               |
| ------------------------------------------------------ | ---------------------------------------------------------------------------------- |
| H-01, M-01, M-02                                       | **Confirmed defects**                                                              |
| H-03, M-04, M-08                                       | **Architectural weaknesses**                                                       |
| H-02, H-04–H-09, M-06–M-07                             | **Missing controls**                                                               |
| CKYC, ICICI, ledger/reconciliation absence             | **Specified but not implemented** rather than accidental defects                   |
| Dashboard/“masked”/“review” terminology                | **Documentation and contract gaps**, with confirmed implementation consequences    |
| Exact-EMI posting, operational reporting, coarse roles | **Acceptable temporary trade-offs** only within the declared low-volume/mock scope |
| Capacity, DR, regulatory semantics                     | **Risks requiring validation** where runtime/owner evidence was unavailable        |

---

## 11. Alternatives considered

| Decision area        | Current choice                           | Credible alternatives                                                    | Comparison and recommendation                                                                                                 |
| -------------------- | ---------------------------------------- | ------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------- |
| System topology      | Modular monolith                         | domain microservices; event-sourced services                             | Monolith best preserves atomic money/business state. Do not split until independent scale/ownership is proven.                |
| Intake response      | Synchronous create                       | async accepted/job; file/bulk intake                                     | Synchronous is clear at current volume. Add idempotent bulk/async only for signed partner throughput.                         |
| Borrower identity    | Global PAN identity + LSP relationship   | tenant-local duplicates; external master-party service                   | Current choice best supports D3. Its concurrency and privacy governance must be strengthened.                                 |
| Credit decision      | Code-defined deterministic rules         | manual underwriter; DB-configured rules; external engine/model           | Current rules are auditable and proportionate. Externalize when rule-change governance, explainability, or models justify it. |
| Lifecycle state      | Relational state machine + audit         | event sourcing; workflow engine                                          | Current approach is simpler and transactionally strong. Add immutable financial journals, not wholesale event sourcing.       |
| Disbursement         | Durable DB intent + leased worker        | synchronous call; broker command; bank-only idempotency                  | Current is safest. Broker may later transport commands, but DB intent remains source of truth.                                |
| Repayment            | Exact EMI against installment projection | partner-preallocated transaction; receipt allocator; double-entry ledger | Add receipt/journal layer and keep installment projection. Partner allocation alone is not auditable enough.                  |
| Tenant isolation     | Shared schema + RLS                      | application filters only; schema/database per LSP                        | RLS is the right defense-in-depth/operability tradeoff. Per-tenant DB is costly and does not solve global borrower rules.     |
| Partner events       | Transactional outbox                     | synchronous webhooks; direct broker publish; CDC                         | Retain outbox. CDC can feed analytics, not replace partner-delivery evidence.                                                 |
| Reporting            | Primary DB + async CSV/R2                | read replica; CDC warehouse; direct SQL                                  | Current is acceptable at low volume. Progress to replica/CDC; never grant broad direct primary access.                        |
| Payout authorization | Single admin/worker                      | all maker-checker; capped STP + checker exceptions; bank-portal checker  | Capped STP plus LMS maker-checker above threshold is the most balanced target, subject to risk sign-off.                      |
| KYC documents        | Presence gate                            | manual verification; vendor verification; hybrid                         | Presence is not KYC verification. Use hybrid verdict states if regulatory reliance is intended.                               |

---

## 12. Prioritized roadmap

### Immediate — before any live money

1. Atomically enforce one open loan per borrower and add a PostgreSQL concurrency test.
2. Make provider mode explicit and exclusive; remove mock adapter/outcome endpoint from live profiles.
3. Make durable intent workflow mandatory; delete or production-disable inline provider execution.
4. Implement risk-approved STP caps and maker-checker with maker ≠ checker, budgets, velocity controls, and immutable approvals.
5. Freeze beneficiary at approval and require audited reaffirmation after a change.
6. Implement the real ICICI adapter: Composite Pay/Status, certificate/hybrid crypto, secrets, allowlists, timeout/error taxonomy, duplicate/unknown handling, status evidence, and startup validation.
7. Build receipt/allocation/suspense/reversal journal and shadow three-way reconciliation.
8. Mask partner/list PII, encrypt sensitive values/objects, quarantine document uploads, and define production retention.

### Short term — before a controlled partner pilot

1. Correct dashboard MTD/overdue/name semantics and add contract tests across backend DTO and frontend mapper.
2. Resolve `REJECTED -> INVALID` business semantics and state transition.
3. Require idempotency for money-relevant/admin mutations.
4. Stream MIS output, lease report workers, minimize PII, and expire report objects.
5. Separate the R2 probe from ordinary tests; fix shared database cleanup; make backend CI green without environment-specific Java agent setup.
6. Run all PostgreSQL/RLS/concurrency/migration suites in CI and publish results.
7. Add append-only alert action history and sensitive-read audit.
8. Sign partner API limits, client rotation, support, incident, and data-disclosure contracts.

### Medium term — before production scale

1. Operate three-way reconciliation in shadow mode and measure unmatched/late/duplicate rates before enabling automated actions.
2. Implement retention catalog, legal hold, purge manifests, object deletion, and partition/archive strategy.
3. Establish CDC or read replica and a governed DWH contract.
4. Exercise performance budgets, worker backlogs, database failover, restore, RTO/RPO, key/certificate rotation, and provider outage runbooks.
5. Add SLOs for intake, decision, payout, status inquiry, webhook delivery, report completion, DPD freshness, and reconciliation aging.
6. Complete borrower-relationship cutover after observed zero-divergence.

### Long term / only when justified

- Externalize the credit engine only if policy-change cadence or modeling demands it.
- Extract webhook/reporting/provider connectors only after independent scaling or ownership becomes material.
- Add a borrower portal only by superseding D10 with a separate identity, consent, privacy, and support design.
- Adopt event streaming for downstream consumers without replacing relational business invariants.

### Documentation-only improvements

1. Declare the intended deployment mode and acceptance gates in one versioned launch-readiness document.
2. Refresh every specification's implementation baseline or record that specs describe the 2026-07-20 review rather than current `main`.
3. Replace overstated vocabulary: “document present” versus “KYC verified,” raw versus masked fields, all-time versus MTD, DPD90+ versus overdue.
4. Publish state-transition, idempotency, retry, reconciliation, data-classification, and retention catalogs owned jointly by engineering and business/compliance.
5. Record superseding decisions for D3 atomicity, live provider mode, payment accounting, foreclosure validity, and CKYC/ICICI vendor inputs.

### Required business decisions

- ICICI rails/version, beneficiary registration, credentials/identifiers, limits/timeouts, certificates, callback vs polling, MIS format, deemed-approved handling, and reconciliation authority.
- CKYC applicability, official schema/channel, frequency, correction flow, approval, and retention.
- Payout STP thresholds, per-LSP/day budgets, maker/checker roles, and emergency override.
- Partial/advance/overpayment, suspense, bounce/reversal, refund, and write-off accounting.
- Foreclosure accrual/charges/expiry/cutoff policy.
- PII disclosure matrix, encryption/KMS provider, retention schedule, legal hold, and report/document access policy.
- DPD timezone, holidays, moratorium, write-off, backdating, and regulatory/bureau semantics.

### Actionable recommendation register

The register consolidates the roadmap into work packages. Every roadmap bullet above maps to one of these packages; program planning should split packages into separately deployable changes.

| ID / priority                | Problem and evidence                                                                                                | Proposed change; business and technical justification                                                                                                                                                                                                         | Blast radius, dependencies, and migration                                                                                                                                                                                                | Required testing                                                                                                                                                                   | Change risk / risk of no change                                                                                                                                    |
| ---------------------------- | ------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| R-01 / **P0 live**, P1 pilot | D3 read-before-write can race (H-01); application-specific locks and account uniqueness do not serialize a borrower | Lock a borrower/guard row and recheck in the approval transaction; add a DB guard if practical. Prevents cross-LSP over-lending and makes the business rule an invariant.                                                                                     | Approval/intake services, borrower/account repository, possible V114+ migration. Depends on agreeing whether pending applications also count. Existing data must be scanned for duplicate-open borrowers before enabling.                | PostgreSQL two-application concurrency, rollback, deadlock, retry, existing-loan, same-application replay, and migration data checks                                               | Change: contention/deadlock if lock order is poor. No change: duplicate open accounts and possible duplicate payout.                                               |
| R-02 / **P0 live**           | Mock provider is profile-independent and intent can be disabled (H-02/H-03)                                         | Add exclusive provider selection, conditional beans/controllers, live startup validation, mandatory intent workflow, and provider health detail. Makes deployment intent explicit and preserves duplicate-payment recovery.                                   | Disbursement configuration, controller registration, adapter wiring, deployment manifests/runbooks. Depends on provider environment naming and secret source. No data migration; staged config rollout.                                  | Startup matrix for local/test/staging/live, bean absence, endpoint 404, intent mandatory, ambiguous outcome/restart tests                                                          | Change: misconfigured non-live environments may fail until manifests are fixed. No change: mock or unsafe inline payout can run in production.                     |
| R-03 / **P0 live**           | Single principal can initiate uncapped payout (H-04)                                                                | Implement signed STP limits plus maker-checker above limits, budgets/velocity caps, maker ≠ checker, immutable decision audit, and mandatory idempotency. Reduces fraud/error and supports segregation of duties.                                             | Ops API/UI, roles, intent state, worker selection, new approval/budget tables. Depends on Risk/Finance thresholds and operating roles. Backfill existing pending intents as requiring approval or explicitly grandfather them.           | Authorization matrix, concurrency, cap boundary, daily reset, duplicate approval, maker=checker rejection, audit, recovery, migration                                              | Change: payout latency and operational queue burden. No change: unbounded single-user money movement.                                                              |
| R-04 / **P0 live**           | Bank details can change after approval (H-05)                                                                       | Snapshot beneficiary at approval; compare at intent; fail closed and require audited reaffirmation after changes. Preserves the approved payout mandate.                                                                                                      | Borrower updates, account/application snapshot, disbursement preview/gates/UI; V114+ columns/table. Depends on checker policy. Existing approved loans require one-time snapshot/reaffirmation.                                          | Concurrent bank edit/payout, unchanged path, mismatch, reaffirm maker/checker, migration/backfill, masked audit                                                                    | Change: legitimate edits can delay payout. No change: payout redirection after approval.                                                                           |
| R-05 / **P0 live**           | No real ICICI integration or bank reconciliation evidence (ICICI spec, H-02/H-09)                                   | Implement adapter behind existing seam: Composite Pay/Status, crypto/certs/secrets, error taxonomy, unknown/duplicate inquiry, poll evidence, reconciliation cases, and fail-closed config.                                                                   | Adapter/config, request evidence, workers, ops timeline, secrets/network/deployment; schema extensions. Depends on all unresolved ICICI/vendor decisions and R-02/R-04. Deploy sandbox, shadow/status, then live canary.                 | Contract/sandbox, crypto vectors, certificate rotation/expiry, timeout-after-send, duplicates, deemed approval, not-found window, restart, reconciliation, redaction, load         | Change: vendor complexity and incorrect mappings can stall payments. No change: live disbursal is impossible/unsafe.                                               |
| R-06 / **P0 collections**    | Exact EMI has no receipt, suspense, bounce, reversal, or financial journal (H-06)                                   | Add immutable receipt/journal/allocation/reversal entities and deterministic allocator; keep account/installments as projections; run three-way reconciliation in shadow mode. Makes LMS auditable as a collections book.                                     | Payment API/UI, webhooks, foreclosure, closure/reopen, reports, accounting exports; substantial new schema. Depends on Finance policy and bank/LSP inputs. Prefer feature-flagged dual-write, reconcile, then cut over.                  | Property/allocation tests, partial/bunched/advance/overpay, duplicate receipt, reversal, bounce, closure/reopen, concurrent allocation, dual-write parity, reconciliation fixtures | Change: highest migration/financial-correctness risk; requires formal ledger review. No change: cannot serve as collections system of record.                      |
| R-07 / **P0/P1 data**        | Raw PII is returned/copied/stored without complete lifecycle (H-07)                                                 | Enforce audience-specific backend masking, break-glass reveal audit, structured redaction, KMS-backed encryption/tokenization, report/object encryption and retention/legal hold. Reduces breach and contract/regulatory exposure.                            | DTOs, frontend, search, audit/alerts, database columns/indexing, R2, reports. Depends on disclosure matrix, KMS, retention owners. Use dual-read/write encryption migration and staged API version/contract communication.               | Contract snapshots by role, tenant isolation, log/audit scanning, crypto rotation, migration rollback, search behavior, purge/legal hold, object expiry                            | Change: API compatibility, search/index complexity, migration load. No change: unnecessary sensitive-data exposure and unbounded copies.                           |
| R-08 / **P0/P1 documents**   | File checks do not establish safe or verified KYC (H-08)                                                            | Quarantine uploads, scan/CDR asynchronously, permit clean-only download, and add reviewer/vendor verdict states. Separates evidence presence from verification.                                                                                               | Upload/status/download APIs, checklist/credit gates, storage prefixes, worker/vendor, UI; new verdict/evidence schema. Depends on security/compliance vendor and failure policy. Existing objects need scan/backfill classification.     | Malware fixtures, spoofed files, scan timeout/retry, quarantine authorization, clean release, reviewer roles/audit, legacy backfill                                                | Change: false positives and approval latency. No change: malicious downloads and falsely asserted KYC completion.                                                  |
| R-09 / **P1 pilot**          | Dashboard, cancellation, and privileged idempotency defects (M-01/M-02/M-07)                                        | Correct DTO semantics/labels/masking; decide and enforce rejected cancellation taxonomy; require durable idempotency for money/admin commands. Restores MIS trust and retry safety.                                                                           | Backend DTO/services, frontend mappers/copy, API contracts/tests, transition rules, idempotency table usage. Depends on Product decision for `REJECTED`. No large data migration; reporting may need status recategorization.            | Backend/frontend contract tests, status transition matrix, replay/concurrency, historical dashboard fixtures                                                                       | Change: visible metrics/statuses and API contracts change. No change: misleading management data, taxonomy drift, duplicate privileged actions.                    |
| R-10 / **P1 pilot**          | MIS is memory-bound, PII-heavy, and not robustly leased (M-04)                                                      | Stream rows to object storage, add claim lease/heartbeat/recovery, minimize columns by report type, encrypt and expire artifacts. Protects primary/API availability and sensitive exports.                                                                    | Report generator/request worker/R2/download/UI; possible lease columns migration. Depends on retention and report contracts. Old reports can expire under announced policy.                                                              | Large-volume memory/load, crash/reclaim, duplicate workers, deterministic output, object failure, access audit, expiry/legal hold                                                  | Change: streaming multipart cleanup and recovery complexity. No change: OOM/stuck work and long-lived sensitive exports.                                           |
| R-11 / **P1 engineering**    | Tests are non-hermetic and PostgreSQL controls were not re-executed (M-05/H-09)                                     | Remove network probes from ordinary tests, centralize cleanup, pin supported JDK/Mockito agent behavior, and gate CI on PostgreSQL/Testcontainers suites. Produces trustworthy release evidence.                                                              | Test source, CI images/workflows, fixtures/cleaner; no production schema except test migrations. Depends on Docker-capable CI and secrets-free fixtures.                                                                                 | The work is the test program: repeated clean runs, order randomization, parallelism, PostgreSQL migration/RLS/lock suites, mutation of cleanup order                               | Change: CI may initially expose more failures and run longer. No change: false confidence and environment-specific releases.                                       |
| R-12 / **P1/P2 operations**  | Retention, case workflow, observability, capacity, DR, and DWH boundaries are incomplete (H-09/M-06)                | Add alert action history; SLOs/backlog metrics; retention/legal hold; backup/restore/failover/load drills; then read replica/CDC and governed analytical contracts. Supports auditability and predictable operations without premature service decomposition. | Cross-cutting workers/tables/dashboards/runbooks/infrastructure; later reporting topology. Depends on volume, RTO/RPO, retention, and platform choices. Roll out retention disabled, dry-run manifests, then enforce; CDC starts shadow. | Retention dry run/hold, restore/failover, backlog/outage, load/capacity, alert escalation, CDC parity/schema evolution                                                             | Change: operational cost, purge irreversibility, CDC complexity. No change: unbounded growth, weak incident recovery, primary overload, incomplete audit evidence. |
| R-13 / **P2 decision-led**   | CKYC, foreclosure policy, DPD semantics, and longer-term extraction choices lack owner decisions                    | Resolve business/regulatory inputs first; implement CKYC only when applicable; version foreclosure/DPD policy; extract components only against measured bottlenecks. Avoids coding invented requirements.                                                     | Specs/ADRs first, then affected regulatory/reporting/servicing modules. Depends on Compliance, Finance, Operations, vendors, and observed scale. Migrations are decision-specific.                                                       | Policy examples, regulatory fixtures, effective-date/version tests, cutover/reconciliation tests as decisions mature                                                               | Change: delay while owners decide. No change: divergent or legally unsupported implementations.                                                                    |

---

## 13. Validation results

### Frontend

`npm run verify` completed successfully:

- TypeScript typecheck passed.
- ESLint and formatting checks passed.
- Encoding checks passed.
- 136 test files / 809 tests passed.
- Production build and bundle-boundary checks passed.
- JSDOM emitted canvas `getContext` warnings, but they did not fail tests.

### Backend

The default `./mvnw -q test` first failed because the local Homebrew JDK disallowed Mockito/Byte Buddy self-attachment. Re-running with the Byte Buddy Java agent allowed the suite to execute:

- **722 tests run**
- **0 assertion failures**
- **17 errors**
- **85 skipped**

The 17 errors were environmental/test-isolation issues:

- 1 R2 probe attempted real network I/O because repository `.env` values enabled it; network resolution is restricted.
- 16 webhook redrive test setup errors attempted to delete LSP rows while loan-account rows from a shared Spring context still referenced them.
- PostgreSQL/Testcontainers suites were among the 85 skipped because Docker is unavailable.

This result does not demonstrate a production-code regression, but it does mean the backend test command is not clean or hermetic in this reviewed environment.

---

## 14. Evidence index

The following are the principal source anchors used for conclusions. Paths are relative to the LMS repository unless an absolute spec-repository path is shown.

| Subject                                    | Evidence                                                                                                                                                                         |
| ------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Foundational decisions D1–D10              | `docs/business-workflow-and-use-cases-guide.md:560-569`                                                                                                                          |
| Active accepted deferrals                  | `docs/deferred-implementation.md:15-128`                                                                                                                                         |
| Tenant fail-closed routing                 | `backend/src/main/java/com/bhawana/lms/tenant/TenantRoutingDataSource.java:10-23`                                                                                                |
| Principal-derived tenant scope             | `backend/src/main/java/com/bhawana/lms/web/AuthenticationTenantScopeFilter.java:17-57`                                                                                           |
| PostgreSQL tenant RLS                      | `backend/src/main/resources/db/migration/V41__tenant_isolation_rls.sql:223-367`                                                                                                  |
| Borrower PAN uniqueness/global identity    | `backend/src/main/resources/db/migration/V43__global_borrowers_with_lsp_access.sql:47-73`                                                                                        |
| Intake and transaction retry               | `backend/src/main/java/com/bhawana/lms/service/LoanApplicationOnboardingService.java:76-217`                                                                                     |
| Raw identity in alert context              | `backend/src/main/java/com/bhawana/lms/service/BorrowerOnboardingService.java:179-249`                                                                                           |
| Auto-approval open-loan read               | `backend/src/main/java/com/bhawana/lms/service/LoanAutoApprovalRuleEngine.java:111-123`                                                                                          |
| Approval without borrower/application lock | `backend/src/main/java/com/bhawana/lms/service/LoanApplicationLifecycleService.java:226-266,319-321`                                                                             |
| Loan account uniqueness scope              | `backend/src/main/resources/db/migration/V17__loan_account.sql:1-16`                                                                                                             |
| Durable intent invariant                   | `backend/src/main/resources/db/migration/V111__disbursement_intent.sql:1-32`                                                                                                     |
| Intent creation/beneficiary snapshot       | `backend/src/main/java/com/bhawana/lms/service/DisbursementIntentWorkflowService.java:75-105`                                                                                    |
| Provider outside transaction               | `backend/src/main/java/com/bhawana/lms/service/DisbursementIntentWorkflowService.java:108-159`                                                                                   |
| Unsafe legacy branch                       | `backend/src/main/java/com/bhawana/lms/service/LoanDisbursementCommandService.java:147-205`                                                                                      |
| Mock/live defaults                         | `backend/src/main/resources/application.yml:174-196`                                                                                                                             |
| Startup validator limits                   | `backend/src/main/java/com/bhawana/lms/config/UnsafeDeploymentConfigurationValidator.java:24-62`                                                                                 |
| Repayment exact-EMI rule                   | `backend/src/main/java/com/bhawana/lms/service/LoanRepaymentCommandService.java:220-245`                                                                                         |
| Dashboard backend semantics                | `backend/src/main/java/com/bhawana/lms/service/HomeDashboardService.java:86-95`                                                                                                  |
| Dashboard frontend mapping/labels          | `frontend/src/features/home/api.ts:142-144`; `frontend/src/features/home/components/InternalKpiSummary.tsx:91-94`                                                                |
| Raw partner PAN                            | `backend/src/main/java/com/bhawana/lms/web/LspLoanApplicationResponses.java:39,102`                                                                                              |
| Raw PAN mislabeled masked                  | `frontend/src/features/my-loans/api.ts:256-257`                                                                                                                                  |
| MIS PII and materialization                | `backend/src/main/java/com/bhawana/lms/service/PortfolioMisCsvWriter.java:22-27,68-78,124-134`                                                                                   |
| Report generation/storage transaction      | `backend/src/main/java/com/bhawana/lms/service/ReportRequestService.java:99-181`                                                                                                 |
| Retention limited to idempotency           | `backend/src/main/java/com/bhawana/lms/service/IdempotencyRecordRetentionWorker.java:34-51`                                                                                      |
| CKYC missing target definition             | `/Users/siddhant/Desktop/work/ferratum-products-specs-res/areas/bhawana/origination-and-underwriting/ckyc-reporting-and-sftp-submission/spec.md:15-46,143-176,263-275`           |
| DWH absence/boundary                       | `/Users/siddhant/Desktop/work/ferratum-products-specs-res/areas/bhawana/partner-integration-and-reporting/dwh-read-interface-and-reporting-boundary/spec.md:16-32,76-93,267-279` |
| Three-way ledger target                    | `/Users/siddhant/Desktop/work/ferratum-products-specs-res/areas/bhawana/partner-integration-and-reporting/three-way-ledger-and-reconciliation/spec.md:15-36`                     |
| ICICI current/missing state                | `/Users/siddhant/Desktop/work/ferratum-products-specs-res/areas/bhawana/servicing/icici-disbursal-integration/spec.md:15-45,139-177,263-297`                                     |

---

## 15. Audit progress and open questions

### Completion tracker

- [x] Bhavana area index reviewed
- [x] Five domain indexes reviewed
- [x] 31/31 feature specifications reviewed
- [x] Area diagrams and historical code review inventoried
- [x] LMS business/architecture/ADR/deferred/implementation material reviewed
- [x] Controllers, services, repositories, entities, migrations, workers, adapters, frontend mappings, and representative tests traced
- [x] 31-row spec-to-code matrix completed
- [x] Business decisions and reconstructed ADRs assessed
- [x] Alternatives compared
- [x] Severity-ranked findings produced
- [x] Frontend verification executed
- [x] Backend test suite executed with documented limitations
- [x] Immediate/short/medium/long roadmap produced
- [x] No code files changed

### Open questions for owners

1. Is the current deployment target synthetic UAT, a data-bearing partner pilot, live payout, or authoritative collections? Each has a different gate.
2. Is D3 intended to block only open **accounts**, or also multiple simultaneously pending/approved **applications**?
3. Who owns financial accounting policy and signs the receipt/allocation/reversal journal?
4. What ICICI product/version, rails, and operational reconciliation source are contractually selected?
5. Does Bhavana have a signed PAN/Aadhaar/bank-data disclosure and retention matrix?
6. Does “KYC complete” mean documents present, manually verified, vendor-verified, or regulatory reporting accepted?
7. What are the approved STP threshold, daily/LSP budget, and maker/checker roles?
8. What RTO/RPO and peak application/payment/report volumes must the platform meet?

---

## Final assessment

Bhavana’s core is worth hardening, not replacing. The domain model, tenant boundary, immutable snapshots, transactional outbox, idempotency, and durable disbursement intent are sound foundations. The immediate architectural work is to turn key business rules and deployment assumptions into fail-closed invariants, then add the financial, privacy, bank, and operational controls required by the intended launch mode.
