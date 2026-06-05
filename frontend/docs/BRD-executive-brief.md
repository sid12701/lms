# Business Requirements Document
## Bhawana Capital — Loan Management System (LMS)

**Document type:** Executive Business Requirements Document (BRD)
**Audience:** Upper Business Management, Program Sponsors, Risk & Compliance Leadership
**Prepared:** 2026-04-18
**Source of truth:** Knowledge graph extracted from production codebase (backend, frontend, product documentation)
- Backend: 1,881 code nodes · 4,880 relationships · 78 functional communities
- Frontend: 396 UI/API nodes · 454 relationships · 77 functional communities
- Documentation & Design: 340 screen/spec nodes · 520 relationships · 25 workflows

---

## 1. Executive Summary

The Loan Management System (LMS) is an institutional-grade digital lending platform purpose-built for Bhawana Capital to originate, underwrite, disburse, service, and report on secured and unsecured loans across a network of Lending Service Providers (LSPs). It is delivered as a modular-monolith Java/Spring Boot backend with a React operations console, supported by a documented external LSP API surface for partner integration.

The platform is designed around three commercial priorities:

1. **Operational control** — every loan, application, borrower, and LSP action is tracked, audited, and reversible through a governed workflow.
2. **Partner scalability** — onboarding new LSPs, products, and API clients is a configurable, self-serve exercise, not a code change.
3. **Regulatory defensibility** — PII access, document access, status transitions, and disbursements all leave tamper-evident audit trails designed to withstand internal audit and regulator inspection.

The system is **production-complete for the core lending workflow** (intake → KYC → approval → disbursement → repayment → closure) with supporting MIS, alerts, and webhook infrastructure in place.

---

## 2. Business Objectives

| # | Objective | Business Outcome |
|---|-----------|------------------|
| O1 | Digitise end-to-end loan lifecycle across Bhawana and partner LSPs | Faster time-to-disbursement, lower operational cost per loan |
| O2 | Enforce the "one open loan per borrower" rule at the system level | Credit risk containment, regulatory alignment |
| O3 | Provide a single operations console for admin, ops, and LSP roles | Unified control plane replaces spreadsheets and email workflows |
| O4 | Deliver real-time MIS and portfolio visibility to management | Data-driven capital allocation and provisioning |
| O5 | Enable external partners to integrate via a stable, versioned API | LSP-led growth without per-partner engineering work |
| O6 | Produce an audit-ready, PII-aware record of every operator action | Regulatory readiness, forensic traceability |

---

## 3. Scope

### 3.1 In Scope (Delivered)

- Borrower onboarding and profile management
- Loan product catalog with LSP-specific mappings
- Loan application intake, document checklist, and KYC gating
- Credit approval workflow with role-gated transitions
- Disbursement orchestration (with pluggable disbursement adapter)
- Repayment schedule generation, installment tracking, DPD/delinquency bucketing
- Foreclosure quote and settlement flow
- Loan payment transaction recording across channels
- LSP (Lending Service Provider) registry with IP allow-listing
- External LSP-facing API with idempotency and tenant isolation
- API client credential management and authentication
- Operations home dashboard with portfolio KPIs
- MIS reporting engine with asynchronous report generation and CSV export
- System alerts and operational notifications
- Webhook event delivery (outbox pattern with retry)
- Document storage (pluggable: local filesystem or S3/R2-compatible object store)
- Full audit logs: application audit, PII reveal audit, document access audit, intake audit, product audit

### 3.2 Out of Scope (This Release)

- Mobile application for borrowers
- Borrower self-service portal
- Automated credit bureau integration
- Direct banking-rails integration (uses mock disbursement adapter; production adapter is a pluggable extension point)
- Collection agent field-workflow tooling

---

## 4. Stakeholders & User Roles

| Role | Representative Persona | Primary Workflow |
|------|-----------------------|------------------|
| System Admin | Institutional Admin | LSP, product, user, API-client administration; system configuration |
| Bank Admin | Bhawana credit desk | Approve/reject applications, manage products, view full portfolio |
| Operations User | Ops Admin | Triage applications, resolve alerts, run reports, reconcile disbursements |
| LSP Operator | Partner lender | Originate applications via API, query status, receive webhooks |
| Reporting Consumer | Management & Finance | Consume MIS reports, dashboards, portfolio summaries |

Role boundaries are enforced both in the backend (Spring Security + role-based authorisation) and in the frontend (navigation gates and route-level guards).

---

## 5. Functional Capabilities

### 5.1 Loan Application Lifecycle
A governed state machine drives every application through defined statuses (draft, submitted, under review, approved, pending disbursal, disbursed, closed, rejected, cancelled, invalidated). Transitions are explicitly validated; each transition is recorded with actor, timestamp, reason code, and correlation ID. Document checklist gates block approval and disbursement until required documents are present.

**Business value:** Standardised, auditable underwriting with zero "silent" state changes.

### 5.2 Borrower & One-Open-Loan Enforcement
A borrower entity is the canonical identity carrier across applications and loans. A platform-level rule enforces that a single borrower cannot hold more than one active loan at any moment, checked at intake and approval gates.

**Business value:** Reduces concentration and over-leverage risk at the source.

### 5.3 LSP Partner Management
LSPs are first-class tenants. Each LSP has its own product catalog mappings, API credentials, IP allow-list, webhook configuration, and tenant-scoped data access. A shared-schema multi-tenant model keeps the platform efficient while preserving logical isolation.

**Business value:** Add a new partner lender without redeploying code; isolate partner data by policy.

### 5.4 External LSP API
A documented, versioned API surface allows partner LSPs to submit applications, attach documents, query status, and receive lifecycle webhooks. Requests are authenticated via API client credentials, protected by an IP allow-list, deduplicated via idempotency keys, and scoped by tenant context interceptor.

**Business value:** Partner-led origination at scale, with controlled blast radius if a key is compromised.

### 5.5 Disbursement & Repayment Schedule
Disbursement is orchestrated via a pluggable adapter (today a mock adapter; production adapter slots in without code changes to the core). Disbursement is gated: it cannot proceed unless a valid repayment schedule already exists for the loan account.

The repayment schedule is produced in one of two governed ways:
- **Platform-generated schedule** — Bhawana's engine computes a standard EMI schedule from the approved principal, tenure, and product interest rate (amortised monthly, first due date one month after approval).
- **LSP-provided schedule** — Partner LSPs may submit their own schedule via the external API. The platform validates it end-to-end before accepting: installment count must match approved tenure, numbering must be contiguous, due dates must be strictly increasing, amounts non-negative, each installment's principal-due plus interest-due must equal the stated installment amount, and total principal-due must reconcile to the approved principal.

Schedule replacement is allowed only before disbursement is requested and only while no repayments have been posted. Once disbursed, the schedule is frozen. Each installment is tracked through its lifecycle and delinquency is bucketed daily (DPD 0, 1-30, 31-60, 61-90, 90+).

**Business value:** Rails-agnostic disbursement; LSP flexibility on schedule structure; zero risk of a disbursed loan with a missing or mathematically invalid schedule.

### 5.6 Repayment Posting & Loan Closure
Payments across channels (bank transfer, UPI, cash, adjustment) are recorded against the loan and reconciled against the repayment schedule. Standard repayment posting accepts only the full outstanding amount of the next installment; partial installment payments are rejected to keep the schedule reconciliation deterministic.

Lifecycle side-effects of repayment are automated and governed:
- **Status auto-advance** — on the first posted payment, the application auto-transitions from DISBURSED to UNDER_REPAYMENT, with a correlation-ID-stamped audit entry.
- **Allocation recompute** — each posting recomputes the payment allocation across installments (principal/interest).
- **Auto-closure** — once the loan is fully repaid, the loan account is synchronised to a closed state with closure reason `FULLY_REPAID`.
- **Partner notification** — every posted repayment enqueues a `LOAN_REPAYMENT_RECORDED` webhook to the originating LSP (if subscribed) through the transactional outbox.

Foreclosure is the alternative closure path: borrowers (via ops) can request a foreclosure quote with an expiry window and settle on a single payment; stale quotes are rejected.

**Business value:** Every rupee posted moves the loan's lifecycle, audit trail, and partner view in lockstep — no manual reconciliation required to close a loan.

### 5.7 MIS & Reporting
An asynchronous report-request engine generates portfolio, disbursement, repayment, and delinquency reports. Reports are queued, processed by a worker, and delivered with status notifications. The operations console exports CSV on demand.

**Business value:** Management gets numbers without analysts running ad-hoc SQL.

### 5.8 Home Dashboard
A single-pane operations dashboard surfaces active portfolio, disbursed amount, overdue amount, pipeline health, portfolio risk index, loan-status distribution, and critical disbursements requiring attention.

**Business value:** Morning standup signal for ops leadership; no dashboard-hunting.

### 5.9 Alerts & Operational Signals
System alerts are categorised by severity, type, and status. They flow into an alerts console with filter, acknowledge, and note-taking workflows. Routing logic is configurable.

**Business value:** Incidents surface before customers call.

### 5.10 Webhook Outbox
Outbound events (status changes, disbursement confirmations, etc.) are written to a transactional outbox and delivered asynchronously by a dispatch worker with retries and a visible admin console.

**Business value:** Partner notifications are reliable even if the downstream is temporarily unavailable.

### 5.11 Document Vault
Documents attached to applications (KYC, income proof, agreements) are stored via a pluggable storage service — local filesystem for development, S3/R2-compatible object store for production. Every document access is audited.

**Business value:** Decouples document storage from compute; every access is defensible.

---

## 6. Safety, Security & Compliance Capabilities

### 6.1 Authentication
- JWT-based session authentication for operator users
- Separate API client credential flow for LSP partner integrations
- Password-change and session-refresh flows

### 6.2 Authorisation
- Role-based access control (System Admin, Bank Admin, Ops User, LSP Operator)
- Role-gated navigation in the operations console
- Explicit tenant-context interceptor on the LSP API preventing cross-tenant data access

### 6.3 Network Controls
- API-client level IP allow-listing (per-LSP configurable)
- Tenant isolation web configuration enforced at request time

### 6.4 Data Privacy (PII)
- Sensitive-data toggle in the UI (PII masked by default)
- Every PII reveal is written to a dedicated PII Reveal Audit table with actor, timestamp, and reason
- Document access is audited independently (Document Access Audit)

### 6.5 Audit Trails
The platform maintains five distinct, append-only audit streams:
| Audit Stream | What It Captures |
|--------------|------------------|
| Loan Application Audit Event | Every status transition and edit on an application |
| Loan Application Intake Audit | Intake-time capture for fraud/accuracy review |
| Loan Application PII Reveal Audit | Each time masked PII is unmasked by an operator |
| Loan Application Document Access Audit | Every read/download of a stored document |
| Loan Product Audit Event | Every change to a lending product |

### 6.6 Operational Integrity
- **Idempotency**: LSP API mutations require an idempotency key; duplicate submissions return the original result
- **Correlation IDs**: Every request carries a correlation ID through logs and audit entries for end-to-end traceability
- **Global exception handling**: Standardised API error envelope; no stack traces leak to clients
- **Business-rule exceptions**: Domain violations (KYC incomplete, document missing, conflict) produce typed, user-friendly errors

### 6.7 Resilience
- Transactional outbox for webhooks survives downstream outages
- Asynchronous report processing insulates the UI from heavy queries
- Pluggable disbursement adapter isolates the core from rails-partner outages

---

## 7. Technical Capabilities (Non-Functional)

| Dimension | Capability |
|-----------|-----------|
| **Architecture** | Modular monolith (Spring Boot) with clear service, repository, and domain layers |
| **Database** | PostgreSQL (shared-schema multi-tenant) |
| **Async Infrastructure** | Redis, RabbitMQ (local stack), outbox-driven delivery |
| **Object Storage** | S3/R2-compatible (MinIO local) |
| **Frontend** | React operations console, shadcn/ui component system, Vite build, Vitest test suite |
| **API Documentation** | OpenAPI (Swagger) auto-generated |
| **Testability** | Postgres-backed integration tests, controller tests across all major surfaces, frontend test suites for shell navigation, formatters, workflows, and ledger |
| **Deployment** | Docker Compose local stack (Postgres, Redis, RabbitMQ, MinIO, MailHog); production-deployable as a standard Spring Boot artefact |

---

## 8. Business Rules & Constraints

- **BR-1** A borrower may hold at most one active loan at any given time.
- **BR-2** A loan application may not be approved until its required-for-approval document checklist is complete.
- **BR-3** A loan application may not be disbursed until its required-for-disbursement document checklist is complete.
- **BR-4** KYC completion is a precondition for approval; attempts to bypass yield a typed exception.
- **BR-5** Every LSP-API mutation must carry an idempotency key; the platform guarantees at-most-once effect.
- **BR-6** Every status transition is validated against an allowed-transitions map; illegal transitions are rejected.
- **BR-7** Every PII reveal and document access is audited; no silent reads.
- **BR-8** LSP API traffic is permitted only from allow-listed IPs for the corresponding API client.
- **BR-9** Foreclosure quotes have an expiry window; stale quotes are rejected for settlement.
- **BR-10** Disbursement cannot proceed unless a valid repayment schedule already exists for the loan account.
- **BR-11** LSP-provided repayment schedules must reconcile to the approved principal and tenure; installment amount must equal principal-due + interest-due for every row; due dates must be strictly increasing.
- **BR-12** The repayment schedule may be replaced only before disbursement is requested and only while no repayments have been posted; once disbursed, the schedule is frozen.
- **BR-13** Standard repayment posting accepts only the full outstanding amount of the next installment; partial installment payments are rejected.
- **BR-14** On the first posted repayment, the application auto-transitions from DISBURSED to UNDER_REPAYMENT.
- **BR-15** When the loan is fully repaid, the loan account is auto-closed with closure reason `FULLY_REPAID` and a `LOAN_REPAYMENT_RECORDED` webhook is enqueued to the originating LSP.

---

## 9. Assumptions & Dependencies

- Production deployment assumes managed PostgreSQL, managed object store (S3 or Cloudflare R2), and a managed message broker.
- A production disbursement adapter (bank rails / payout partner) is expected to be provided by Treasury/Partnerships; the interface is defined and the mock adapter is production-swappable.
- Email and notification transport in production replaces local MailHog.
- Identity provider integration (SSO) is a roadmap item; today the platform uses built-in JWT auth.

---

## 10. Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Partner LSP credentials leak | Medium | High | IP allow-listing per client; idempotency prevents replay; credential rotation supported |
| Cross-tenant data exposure via LSP API | Low | High | Tenant-context interceptor + explicit tenant scoping in read repositories; covered by integration tests |
| PII misuse by internal operators | Medium | High | PII masked by default; every reveal audited with actor and timestamp |
| Disbursement adapter outage | Medium | Medium | Pluggable adapter; operations can switch/retry; outbox guarantees partner notification once recovered |
| Report generation overwhelms DB | Low | Medium | Asynchronous report queue; dedicated worker; read-optimised repositories for portfolio queries |
| Silent status-transition bugs | Low | High | Explicit transition map + append-only audit + integration tests for every state edge |

---

## 11. Success Metrics

| Metric | Target Signal |
|--------|--------------|
| Loan application processing time | Median hours from intake to approval |
| Disbursement SLA adherence | % of approved loans disbursed within SLA |
| DPD portfolio quality | % of portfolio by DPD bucket (0, 1-30, 31-60, 61-90, 90+) |
| LSP API availability | Uptime % and p95 latency of external LSP endpoints |
| Webhook delivery reliability | % of outbox events delivered on first attempt; tail latency |
| Audit completeness | Zero status transitions / PII reveals / document accesses without corresponding audit row (automated check) |
| Operator efficiency | Applications processed per ops user per day |

---

## 12. Roadmap Alignment (Derivable from Current State)

The code and documentation base indicate the near-term expansion surface:

- Production disbursement adapter (slot available; interface defined)
- Expanded LSP API coverage (recent commits: `feat: expand lsp onboarding api and doc gates`, `feat: complete external lsp api surface`)
- Refreshed application lifecycle statuses (recent commit: `feat: refresh loan application lifecycle statuses`)
- Portfolio/admin home dashboard enhancements (recent commit: `feat: add admin home portfolio dashboard`)
- DPD bucket-level LSP breakdowns (recent commit: `feat: add lsp dpd bucket breakdown`)
- Repayment command and schedule services (new modules: `LoanRepaymentCommandService`, `LoanRepaymentScheduleService`) delivering governed repayment posting, LSP-provided schedule acceptance with validation, and automatic loan closure on full repayment

These indicate active investment in partner integration depth, management-level portfolio visibility, and end-to-end lifecycle automation.

---

## 13. Appendix — Core System Building Blocks (from Knowledge Graph)

**Top backend abstractions by connectivity:**
1. Standard API response envelope (touches every controller) — 212 connections
2. LoanApplicationService (orchestrator) — 88 connections
3. LoanApplicationLifecycleService (state machine) — 60 connections
4. JWT security layer — 48 connections
5. Borrower (identity anchor) — 45 connections
6. ReportRequest (MIS engine) — 29 connections
7. **LoanRepaymentCommandService** (new) — governs posted repayments, status auto-advance, auto-closure, and LSP notification
8. **LoanRepaymentScheduleService** (new) — generates or validates-and-accepts the repayment schedule; enforces pre-disbursement immutability guard

**Top frontend abstractions by connectivity:**
1. `requestJson()` — HTTP client backbone, 60 connections
2. Role gates (`isLspUiUser`, `canManageLsps`, `canManageUsers`, `canAccessReports`) — drive access control
3. Shell navigation builder — constructs role-appropriate menus
4. Admin metadata loader — powers configuration screens

**Top documented screens by workflow centrality:**
1. Loan Ledger Screen — 45 links (core operator workspace)
2. Actor Context Panel — 23 links (identity/scope awareness)
3. Sidebar Navigation — 22 links (app shell)
4. Loan Records Table — 22 links
5. MIS Reporting Screen — 17 links
6. Dashboard Summary Screen — 14 links

**Functional clusters confirmed in the code graph:**
Loan Application Lifecycle · Borrower Profile · LSP Entity · API Client Auth · Webhook Delivery · Active Loan Checks (one-open-loan) · Admin Directory · Admin Reporting · Loan Application Query · Ops Alerts · Document Storage · PII Reveal Audit · Intake Audit · Disbursement Adapter · Tenant Isolation.

---

*This document is derived from a static analysis of the LMS codebase and product documentation as of 2026-04-18. It is intended as an executive-level briefing and should be read alongside the engineering architecture blueprint (`docs/architecture/lms-blueprint.md`) and the implementation roadmap (`docs/planning/implementation-roadmap.md`) for technical depth.*
