# LMS Implementation Roadmap

This roadmap breaks the LMS into delivery phases with concrete micro-tasks. The sequence is optimized for a greenfield build where integrations and workflows are important, but the platform must become usable early.

## Confirmed Scope Decisions

- One open loan per borrower is enforced globally across all LSPs.
- PAN is the primary borrower dedupe and uniqueness identifier.
- A single shared API will be used by all LSPs.
- Initial disbursement flow uses a mock provider with successful responses.
- Day-one reporting includes one MIS report.

## Delivery Strategy

- Build the backend and frontend in parallel after the platform foundation is ready.
- Prefer thin vertical slices over finishing every backend module first.
- Deliver internal admin and ops workflows before tenant self-service features.
- Keep all external integrations behind adapters and queues from day one.

## Phase 0: Discovery and Contract Finalization

### Objective

Freeze business rules, payload contracts, and lifecycle definitions before scaffolding.

### Micro-tasks

1. Finalize loan lifecycle stages and allowed status transitions.
2. Finalize the meaning of "open loan" and the borrower dedupe rule.
   Confirmed target: one open loan globally, PAN-first dedupe.
3. Finalize loan product attributes, fee models, and LSP-product mapping rules.
4. Define the canonical inbound API contract for LSP loan creation.
5. Define outbound webhook event catalogue and payload format.
6. Define the shared LSP authentication model and standard headers.
7. Finalize the MIS report columns, filters, and recipient rules.
8. Confirm required document types and file size limits.
9. Confirm user types, role bundles, and permission matrix.
10. Sign off the day-one UI scope for internal users and LSP users.

### Exit criteria

- Approved API spec
- Approved lifecycle matrix
- Approved RBAC matrix
- Approved report catalogue

## Phase 1: Platform Foundation

### Objective

Create the runnable technical base for all future modules.

### Micro-tasks

1. Initialize the repository structure with `backend`, `frontend`, `infra`, and `docs`.
2. Create Spring Boot backend skeleton with modular package layout.
3. Create React frontend skeleton with shadcn-style components, Tailwind, and app shell.
4. Add Docker Compose for PostgreSQL, Redis, RabbitMQ, MinIO, and MailHog.
5. Add Flyway and baseline database migration strategy.
6. Add shared error handling, API response model, and request correlation id support.
7. Add base security configuration and JWT token flow for internal users.
8. Add OpenAPI generation and API documentation pipeline.
9. Add structured logging and audit event scaffolding.
10. Add CI pipeline for build, unit tests, and linting.

### Exit criteria

- Local environment boots with all dependencies
- Backend and frontend pipelines pass
- Baseline auth works

## Phase 2: Identity, Tenancy, and User Administration

### Objective

Establish who can access what and how tenant scoping is enforced.

### Micro-tasks

1. Create database tables for users, roles, permissions, API clients, and LSPs.
2. Implement tenant context resolution for UI users and API clients.
3. Implement permission guards in backend services and controllers.
4. Build admin APIs for LSP creation and update.
5. Build admin APIs for user creation, role assignment, and activation.
6. Build API client credential management for LSP integrations.
7. Implement password reset and token refresh flow.
8. Build React screens for login and user administration.
9. Add `All LSPs` access handling for internal roles only.
10. Add audit logging for auth, user, and permission changes.

### Exit criteria

- Admin can create LSPs and users
- LSP data scope is enforced in API and UI
- API clients authenticate successfully

## Phase 3: Product Configuration

### Objective

Allow admins to define and manage loan products before loan intake starts.

### Micro-tasks

1. Model loan product entity, fee model, rate model, tenure constraints, and status.
2. Add LSP-to-product mapping and activation rules.
3. Build backend APIs to create, edit, activate, deactivate, and view products.
4. Add validation rules for duplicate product codes and invalid rate ranges.
5. Build React product list and product create-edit forms.
6. Add audit trail for product changes.
7. Seed sample products and LSP mappings for test environments.

### Exit criteria

- Admin can manage products end to end
- LSP mappings are visible and enforceable

## Phase 4: Loan Intake and Borrower Management

### Objective

Accept loan applications from LSPs and create the borrower-loan foundation.

### Micro-tasks

1. Model borrower master, borrower identifiers, addresses, contacts, and LSP tags.
2. Implement borrower dedupe strategy and conflict handling using PAN as the primary match key.
3. Model loan application, external loan id, source channel, and intake metadata.
4. Add idempotency support for LSP loan creation API.
5. Implement validation for product eligibility and required fields.
6. Enforce the "one open loan" rule globally across all LSPs.
7. Build document metadata capture and upload linking during intake.
8. Persist raw inbound request payloads for audit and troubleshooting.
9. Build internal loan list API with server-side pagination and filters.
10. Build React loan listing screen using LSP and status filters.

### Exit criteria

- LSP API can create applications
- Duplicate and invalid applications are safely rejected
- Internal users can view created applications

## Phase 5: Loan Lifecycle and Loan 360 View

### Objective

Make the system operational for review, approval, rejection, and full loan visibility.

### Micro-tasks

1. Implement lifecycle status machine with allowed transitions.
2. Create loan status history and assignment tracking.
3. Build internal APIs for approve, reject, hold, and manual status updates.
4. Capture actor, remarks, and reason code on every transition.
5. Create the unified Loan 360 read model.
6. Build React loan detail page with borrower, documents, status history, and loan data.
7. Show last-modified actor and LSP source on the UI.
8. Add role-based action buttons for allowed transitions only.
9. Add search by LMS loan id, external loan id, borrower name, and mobile.
10. Add audit events for every status transition.

### Exit criteria

- Ops can manage applications end to end
- Loan detail page surfaces the full case state

## Phase 6: Disbursement, Repayment Schedule, and EMI Tracking

### Objective

Move from approval-only origination into active loan servicing.

### Micro-tasks

1. Create loan account entity and approval-to-account conversion flow.
2. Generate repayment schedule at the correct lifecycle point.
3. Model installments, due amounts, paid amounts, and outstanding amounts.
4. Implement mock disbursement adapter and request log.
5. Add disbursement initiation API and internal UI action.
6. Handle disbursement success, failure, and pending reconciliation states.
7. Build payment transaction ingestion model.
8. Implement payment allocation waterfall and installment state recomputation.
9. Calculate DPD and delinquency buckets.
10. Show schedule and payment history on the loan detail page.

### Exit criteria

- Approved loans can be disbursed through ICICI integration
- EMI schedules and payments are tracked accurately

## Phase 7: Webhooks, Reporting, and Email Delivery

### Objective

Expose system activity to LSPs and operations through reliable outbound communication.

### Micro-tasks

1. Create webhook subscription configuration per LSP.
2. Create webhook event outbox and retry worker.
3. Sign outbound webhooks and store delivery attempts.
4. Emit events on loan creation, status change, disbursement, repayment, and foreclosure.
5. Build report request API with date range and optional recipient input.
6. Build asynchronous report job processing.
7. Generate CSV export for the day-one MIS report.
8. Email report completion to requester and optional recipient.
9. Build React report request and report history screens.
10. Add webhook delivery monitoring for internal users.

### Exit criteria

- LSPs receive reliable webhook notifications
- Reports can be requested, generated, and delivered by email

## Phase 8: Foreclosure and Closure Flows

### Objective

Support early closure and final settlement cleanly.

### Micro-tasks

1. Model foreclosure quote request and quote versioning.
2. Implement payoff calculation for a given effective date.
3. Add approval flow for foreclosure execution if required.
4. Post final settlement transaction and close installments.
5. Close the loan account and mark lifecycle status correctly.
6. Emit outbound webhook and audit event on foreclosure completion.
7. Add foreclosure request and execution controls to the loan detail page.

### Exit criteria

- Foreclosure quote and execution work end to end
- Closed loans no longer violate open-loan rules

## Phase 9: Hardening, Compliance, and Go-Live Readiness

### Objective

Make the platform safe to operate in production.

### Micro-tasks

1. Add integration contract tests for LSP APIs and webhooks.
2. Add sandbox and mock test suite for ICICI integration.
3. Add API throttling and abuse protection for LSP clients.
4. Add field-level masking for PII in logs and UI where required.
5. Add document access audit and retention rules.
6. Add alerting for webhook failures, disbursement failures, and job backlogs.
7. Add backup, restore, and disaster recovery runbooks.
8. Run performance tests for high-volume loan intake and report generation.
9. Run UAT with at least one LSP and internal ops users.
10. Prepare release checklist and support handover.

### Exit criteria

- Critical operational risks are covered
- UAT is signed off
- Production release checklist is approved

## Recommended Build Order Inside the Team

1. One stream on backend platform plus security.
2. One stream on React shell plus auth and shared table components.
3. One stream on domain modules in this order:
   product -> borrower and intake -> lifecycle -> servicing -> integrations -> reporting.

## Suggested First Sprint

If execution starts immediately, the first sprint should cover:

1. Repository initialization
2. Local infrastructure with Docker Compose
3. Spring Boot and React skeletons
4. Auth baseline
5. LSP entity and user administration basics
6. Loan product entity and CRUD foundation

## Decisions That Block Build Start

Do not start Phase 4 or beyond until these are closed:

1. Final lifecycle transition matrix
2. Shared LSP API auth scheme and field-level payload definition
3. Document storage ownership model
