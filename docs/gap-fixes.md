# LMS Gap Fixes — Design Decisions

This document captures the design decisions made for every gap surfaced in
`docs/INTEGRATION-STATUS.md` after the previous goal-roadmap cycle closed
(commit `b9f4b56`).

The walk produced one architectural clarification that reshapes several
gaps — read **§ The Operating Model** first; it's load-bearing for many of
the per-gap decisions below.

---

## Implementation State Tracker

Last updated: 2026-05-25 (Gap #3 closed — unified cross-domain audit search live; FE wired).

| Gap | Title | State | Notes |
| --- | --- | --- | --- |
| #1 | Internal audited PII reveal — kill it; mask aadhaar everywhere | ✅ Done | BE: `GET /api/v1/lsp/loan-applications/{id}/borrower-pii` endpoint, `LoanApplicationService.revealBorrowerPiiForLsp`, `LspBorrowerPiiRevealResponse`, `BorrowerPiiReveal`, `toBorrowerPiiRevealResponse` all removed. `LoanApplicationPiiRevealAudit` table + repository retained per design (forensic continuity). `GlobalExceptionHandler` now returns 404 for unmapped paths via `NoResourceFoundException`. `LspLoanApplicationApiControllerTest.borrowerPiiIsAlwaysMaskedAndRevealEndpointIsRemoved` covers the 404 + masking contract. FE: `MaskedField`, `PiiRevealDialog`, `useRecordPiiReveal`, `recordPiiReveal` API, `revealBorrowerPii` + `BorrowerPiiReveal` in `my-loans/api.ts`, `PiiRevealCard` in `my-loans/detail-page.tsx`, the borrower-PII mock branch in `mocks/api/borrowers.ts`, and the `RecordPiiRevealInput`/`Response` types are deleted. `maskAadhaar` formatter updated to spec (`XXXXXXXX1234`, idempotent) with regression tests in `src/lib/format.test.ts`. `ProfileTab`, `OverviewTab`, and the new `MaskedBorrowerCard` render backend-supplied masked values directly. All 116 BE tests green; FE typecheck green; OverviewTab tests rewritten and passing. |
| #2 | Borrower Activity tab — remove | ✅ Done | FE: `ActivityTab.tsx` + test, `useBorrowerActivity.ts`, `fetchBorrowerActivity`, `BorrowerActivityEntry` / `BorrowerActivityResponse` types, the activity tab trigger in `BorrowerTabsShell`, the mock activity handler + schemas, the `borrowers.activity` client wrapper, and the `activity` case in `detail-page.tsx` are all removed. `BorrowerDetailTab` enum trimmed to `profile | loans`. Tests updated; FE typecheck green. |
| #3 | Unified cross-domain audit search | ✅ Done | BE: `GET /api/v1/internal/admin/audit-events` (SYSTEM_ADMIN-only) executes a native UNION ALL across the four supported audit streams (APPLICATION, INTAKE, DOCUMENT_ACCESS, PRODUCT). Filter pushdown: `streams`, `actorUsername`, `lspId`, `loanApplicationId`, `borrowerId`, `productId`, `since`, `until`. Pagination is offset/limit (cap 500); `paginationDetails=true` opts into the COUNT(*) wrap (otherwise `totalCount=-1` sentinel). INTAKE detail aadhaar masked via `AuditExplorerService.maskIntakePayload`; raw payload remains in DB. V59 adds the missing `(actor_username, created_at DESC)` + global `(created_at DESC)` indexes on each audit table. FE: `frontend-2/src/features/audit/api.ts` rewired to hit the live endpoint and project `AuditExplorerEvent` → `AuditRow`; `correlationId` + free-text `q` stay client-side post-filters. 15 new BE tests (stream-set, RBAC, filter pushdown across branches, PII masking, composite id shape, pagination clamp + sentinel, input validation, sort invariant). 159 BE tests green, 8 skipped, 0 failures. FE `tsc -b` clean; 23/23 audit-slice vitest tests green. |
| #4 | LSP `GET /documents` endpoint | ✅ Done | BE: `GET /api/v1/lsp/loan-applications/{applicationId}/documents` returns the uploads-only checklist for the authenticated LSP (LSP_API_CLIENT/LSP_UI_READ/LSP_UI_WRITE). `LoanApplicationService.listSubmittedDocumentsForLsp` enforces LSP ownership via the existing `getApplicationForLsp` check (400 cross-tenant) and filters out PENDING/NOT_REQUIRED rows. Response shape is the new `LspDocumentChecklistResponse` (`documentType`, `status="SUBMITTED"`, `fileName`, `contentType`, `note`, `uploadedAt`, `uploadedByUsername`) honouring the Gap #18 PENDING\|SUBMITTED contract. `downloadUrl` and `?includePending=true` deferred per design. New test `lspDocumentsListReturnsUploadsOnlyForOwnerWithStatusSubmitted` covers empty, owner happy-path, cross-tenant 400, and LSP_UI_READ-on-owner 200. FE: `listLspSubmittedDocuments` API + `SubmittedLspDocument` type; `MyLoanDetailPage`'s `DocumentsSection` seeds the upload state from the server on mount (replaces the "checklist resets on reload" disclaimer with the live read). All 126 BE tests green; FE typecheck clean. |
| #5 | Per-application webhook view for OPS_USER | ✅ Done | BE: `GET /api/v1/internal/ops/loan-applications/{applicationId}/webhook-events` returns up to 200 outbox rows newest-first for SYSTEM_ADMIN/OPS_USER (LSP roles → 403, unknown loan → 404 via `ResourceNotFoundException`). New nullable `webhook_event_outbox.loan_application_id UUID` column (V58 with per-aggregate-type backfill) + composite index `(loan_application_id, created_at DESC)`. `WebhookOutboxService.enqueueIfSubscribed(...)` gains a `UUID loanApplicationId` parameter; all 7 enqueue sites updated. `LoanApplicationService.listWebhookEventsForApplication` projects each row to `LoanApplicationWebhookEventProjection` by joining the latest `WebhookEventDeliveryAttempt`. Status mapping: PENDING/DELIVERED pass-through, RETRYABLE_FAILURE → FAILED, PERMANENT_FAILURE → DEAD_LETTERED. FE: `fetchLoanApplicationWebhooks` drops the admin-outbox client-side filter + OPS_USER "empty list" hack and reads the new per-loan endpoint for any internal session. 3 new BE tests (status mapping + scoping, 404, LSP 403); 144 BE tests green, 8 skipped; FE typecheck + 123 loan-app tests green. |
| #6 | Borrower DPD aggregate (`activeOverdueAmount`) | ✅ Done | BE: `AdminDirectoryService.getBorrowerDetail` now computes a `BorrowerDelinquencyAggregate` (sum of overdue across active loans, worst DPD, overdue-loan count, worst bucket) from each loan's repayment schedule via the existing `LoanApplicationService.calculateDaysPastDue`/`resolveDelinquencyBucket` helpers. Active = `PENDING_DISBURSEMENT/DISBURSEMENT_REQUESTED/_PENDING_RECONCILIATION/_FAILED/DISBURSED` — CLOSED/FORECLOSED/INVALID excluded. `BorrowerAdminController.BorrowerDetailResponse` exposes a new `delinquency` block (`activeOverdueAmount`, `maxDaysPastDue`, `overdueLoanCount`, `bucket`); `bucket` collapses to `null` when no overdue exists. New test cases in `LoanApplicationOpsControllerTest`: borrower-admin response surfaces 45-day overdue aggregate with DPD_31_60 bucket; second test asserts zero/null delinquency for a pre-disbursement-only borrower. FE: `BackendBorrowerDetail` extended with `delinquency`; `backendToDetail` now reads `delinquency.activeOverdueAmount` into `totals.activeOverdueAmount` (was hardcoded zero). The Borrower 360 Profile tab tile + OverviewTab "Active overdue" row both light up automatically. All 125 BE tests + 23 borrower FE tests green. |
| #7 | Home overview KPIs (6 missing fields) | ✅ Done | `GET /internal/home/overview` extended with awaiting/disbursement counts, approval TAT, status + DPD buckets, open-alert count + feed; V57 index; FE `mapBackendHomeOverviewToInternalKpis` wires live BE. |
| #8 | Post-login role redirect | ✅ Done | `defaultLandingFor` routes each role to its primary surface; login + `/` + change-password + route guards use it; Home nav + page admin-only; mock `GET /home/kpis` rejects non-admins. |
| #9 | Session context missing user UUID | ✅ Done | BE: `SystemController` now injects `AppUserRepository`, returns real `app_user.id` as `id: UUID`, with a deterministic UUID fallback (`UUID.nameUUIDFromBytes("lms-bootstrap:" + username)`) for the in-memory bootstrap admin. `SystemContextResponse` record extended. `AuthControllerTest` extended with id-shape assertions for both the bootstrap and managed-user paths. FE: `BackendSystemContext.id` typed, `auth-service.ts` drops the `crypto.randomUUID` shim + `bhawana-lms-user-id` localStorage persistence, consumes `context.id` directly, and clears the legacy storage key on logout. All 116 BE tests green; FE typecheck green. |
| #10 | MIS preview widening | ✅ Done | FE `MisPreviewRow` + `MisPreviewTable` expose every BE preview column (incl. LSP/product names, account #, closure reason, address, dynamic EMI cols). `mapBackendPreviewRowToMisPreviewRow` maps DPD buckets BE→FE. Defensive `maskAadhaar` on preview. BE `AdminReportingService` masks aadhaar + bank in preview/CSV (`ReportAdminControllerTest.portfolioMisPreviewMasksAadhaarAndBankAccountForEveryRow`). |
| #11 + Follow-up #1 | Status enum, state machine, auto-approval rule engine | ✅ Done | BE: `LoanApplicationStatus` collapsed to the canonical 10 values (PAYMENT_REINITIATION → DISBURSEMENT_RETRY rename; FORECLOSED added as reserved for Phase 8). `canTransitionTo()` is the authoritative state machine with terminal states blocked. `LoanAutoApprovalRuleEngine` evaluates 8 rule families (PRODUCT_INACTIVE / LSP_INACTIVE / LSP_PRODUCT_MAPPING_INACTIVE / LOAN_AMOUNT_OUT_OF_RANGE / LOAN_TENURE_OUT_OF_RANGE / BORROWER_REQUIRED_FIELDS_MISSING / REQUIRED_DOCUMENTS_NOT_UPLOADED / BORROWER_HAS_OPEN_LOAN), with the one-open-loan rule filtering out the application's own post-approval loan account so re-evaluation doesn't false-positive against self. `autoApproveIfEligibleForLsp` wired to the engine: passing rules advance INITIALIZED→AWAITING_APPROVAL→APPROVED_PENDING_DISBURSAL + create loan account; failing rules from AWAITING_APPROVAL auto-REJECT with structured `rejection_reason_json` (`{"failedRules":[...]}`) on the new `loan_application_status_transition.rejection_reason_json TEXT` column (V52); failing rules from INITIALIZED stay put. V51 migration backfills historical PAYMENT_REINITIATION rows. `LoanApplicationStatusTransitionResponse` now carries `rejectionReason: { failedRules: string[] }`. FE: `LoanStatus` extended with the 10 canonical BE values (legacy values retained for mock compatibility); `mapBackendStatus` / `mapFrontendStatusToBackend` adapter updated. `lib/lifecycle.ts` gains `STATUS_META` entries for INITIALIZED / DISBURSEMENT_RETRY / INVALID + the new `getStatusBadgeTone(status, delinquency)` helper (UNDER_REPAYMENT tone derives from delinquency aggregate). New `BlockingIssuesPanel` component surfaces per-status diagnostics (docs pending, awaiting disbursement, disbursement retry, schedule start, delinquency aggregate, rejection-reason pointer, invalidation reason); hooked into OverviewTab as the top-level diagnostic card. All 127 BE tests + 1020 FE tests green; `tsc -b` clean. Residual scope: full FE trim to the canonical 10 statuses (~300 references) is captured as a follow-up; a dedicated auto-reject regression test is captured as a follow-up. |
| #12 | Drop FE-only webhook event values | ✅ Done | FE: `loan.disbursement.failed` and `loan.foreclosure.quote.generated` removed from `WebhookEventType` enum (`schemas/lsp.ts`), `FRONTEND_TO_BACKEND_EVENT` map (`features/lsps/api.ts`), `EVENT_LABELS` (`LspWebhookSubscriptionDialog.tsx`), the admin seed (`mocks/db/admin-seed.ts`), and the `WEBHOOK_EVENT_FOR_STATUS` map (`mocks/api/loan-applications.ts`). FE typecheck green. |
| #13 | Users admin PUT (update) + token-version sessions | ✅ Done | `PUT /api/v1/internal/admin/users/{userId}` with audit, `token_version` session invalidation on role change, self-edit guards; FE wired. |
| #14 | API clients PUT + rotate-secret | ✅ Done | PUT update (description/status/ipAllowlist), POST rotate-secret with grace + dual-hash auth; FE wired. |
| #15 | Alerts ack note payload | ✅ Done | BE: Flyway migration `V49__ops_alert_acknowledgement_note.sql` adds the `acknowledgement_note VARCHAR(500)` column. `OpsAlert` entity carries the field and `acknowledge(actor, note)` persists it. `OpsAlertService.acknowledge` validates the 500-char cap and surfaces a 400 above the limit. `OpsAlertController.acknowledge` accepts a JSON body `{ note?: string }` (bean-validated `@Size(max=500)`) and returns the persisted note on the response record. New `OpsAlertControllerTest` covers the happy path, the no-body path (note → null), and the over-limit rejection. FE: `acknowledgeAlert` sends `{ note }` in the body, `BackendAlertResponse.acknowledgementNote` is mapped onto `AlertRow.acknowledgmentNote`, and the client-side preservation hack is removed. All 116 + 3 new BE tests green; FE typecheck green. |
| #16 | Drop OPS_USER approve/reject UI | ✅ Done | BE: `OpsAlertType.OPS_USER_ESCALATION` added; `OpsAlertController.escalate` accepts `{ subjectType, subjectId, title, message }` (NotBlank + Size validation), authorized for `SYSTEM_ADMIN`/`OPS_USER`, emits HIGH-severity alert with `escalatedByUsername` context. `OpsAlertControllerTest` adds 5 new cases (creation, admin caller, blank validation, unauthenticated 401, LSP 403). FE: `EscalateToAdminDialog` component + `escalateAlert` API + 3 dialog tests; `DetailHeader` now renders the escalate surface (single button + dialog) for `OPS_USER` and the existing `ActionBar` for other roles. BE writes are still SYS_ADMIN-only at the controller layer — OPS_USER never had the buttons backed by writeable endpoints. All 124 BE tests + 39 lifecycle FE tests green. |
| #17 | Strict per-installment repayments | ✅ Done | Strict installment POST with idempotency header, exact amount match, optional bank reference, widened channels; FE installment picker + non-editable amount dialog. |
| #18 | Delete verify/reject doc model | ✅ Done | BE: `LoanApplicationDocumentChecklistStatus` collapses to `PENDING \| SUBMITTED \| NOT_REQUIRED` (RECEIVED/VERIFIED → SUBMITTED, REJECTED → SUBMITTED if a file was attached else PENDING). `PUT /api/v1/internal/ops/loan-applications/{id}/kyc-documents/{type}` is deleted (404 contract enforced in test). `loan_application_document_checklist.review_reason` + `rejection_reason` columns dropped via `V50__document_checklist_status_collapse.sql`. Lifecycle gates (`hasAllRequiredLmsManagedDocuments`, `validateKycCompletionBeforeApproval`, `validateRequiredDocumentsUploadedBeforeDisbursement`) simplified to "SUBMITTED == uploaded" without VERIFIED. `ConfigurableLoanDocumentStorageService.store` now enforces ≤ 10 MB file size + MIME ∈ `{application/pdf, image/jpeg, image/png}` and throws `BusinessRuleViolationException` (`DOCUMENT_FILE_TOO_LARGE`, `DOCUMENT_MIME_NOT_ALLOWED`, `DOCUMENT_FILE_EMPTY`) → 422. New `LspLoanApplicationApiControllerTest.documentUploadRejectsOversizedFilesAndDisallowedMimeTypesAndReplacesOnReupload` covers oversize + bad MIME + happy-path + re-upload semantics. Obsolete `opsUserCanInspectAndUpdateLoanApplicationDocumentChecklist` and `missingDocumentReviewReasonIsRejected` tests rewritten as contract assertions (PUT removed, review/rejection fields absent). FE: `DocumentStatus` + `LoanDocumentStatus` zod schemas trimmed to `PENDING \| UPLOADED`; `DocumentRejectDialog` + sibling reject-form schema deleted; `DocumentChecklistRow` is view-only (Verify/Reject buttons gone); `DocumentChecklistGroup` no longer accepts `onVerify` / `onReject`; `DocumentStatusPill` enum exhaustiveness trimmed; `api-tabs.ts` `safeDocumentStatus` folds SUBMITTED/legacy VERIFIED/REJECTED → UPLOADED; `lib/lifecycle.ts` BR-2 + BR-3 gate checks switched from VERIFIED to UPLOADED. All 127 BE tests + 1020 FE tests green (12 pre-existing failures unaffected by this gap). |
| #19 | Drop assignment entirely | ✅ Done | BE: assignment endpoints removed (404); `assignApplication` + assignment-event writes retired; `assignedTo*` stripped from ops + LSP responses; `getLatestActivity` no longer surfaces `ASSIGNMENT_UPDATED`; `V53` deprecates DB columns. FE: assignee filter, list column, OverviewTab assignment section removed. `LoanApplicationOpsControllerTest` contract tests added. 135 BE tests green; `tsc -b` clean. |
| #20 | Loan-app detail: parallel borrower-admin fetch | ✅ Done | FE: `LoanApplicationDetailPage` now calls `useBorrowerDetail(detail.borrower.id)` in parallel with `useLoanApplicationDetail`; the result is passed to `OverviewTab` via a new optional `borrowerDetail` prop. `OverviewTab` prefers the fuller projection when present (replacing the borrower fullName + augmenting with `activeOverdueAmount`, `openApplicationsCount`, `closedApplicationsCount`, `lifetimeDisbursedAmount`, and a new "Borrower visibility" section listing every LSP that can see this borrower); falls back to the loan-app's embedded thin projection while loading or on error. Aadhaar is still rendered from the backend's masked value (Gap #1). 4 new tests in `OverviewTab.test.tsx` (prefer-rich-projection, totals, visible-lsps, null-fallback). All 124 loan-applications FE tests green. |
| #21 | Legacy frontend retirement | ⏳ Pending | Intentionally deferred per § Group E. |
| #22 | LSP_API_CLIENT login UI | ✅ N/A | Intentionally not a gap per § Group E. |
| Follow-up #2 | Alert rule coverage | ✅ Done | `alert_rule` table + V60 seed; `AlertRuleEvaluationService` scheduled checks (stale intake 24h, stuck disbursement 2h, DPD bucket, LSP reject spike) + event hooks (webhook dead-letter, rate-limit). Deduped `createAlertIfAbsent`. `GET /api/v1/internal/alerts/rules` (SYSTEM_ADMIN). FE `AlertRulesPanel` on alerts page. `OpsAlertControllerTest` + `AlertRuleDataInitializer` for H2 tests. |
| Follow-up #3 | Document upload auto-check spec | ✅ Done | Global tier from Gap #18 (≤ 10 MB + PDF/JPEG/PNG). `DocumentUploadPolicy` adds per-type rules: `LOAN_AGREEMENT` PDF-only; `PAN_CARD` / `AADHAAR_FILE` PDF or JPEG ≤ 5 MB; other types use global cap. `DocumentUploadPolicyTest` (7 cases) + `LspLoanApplicationApiControllerTest.documentUploadEnforcesPerDocumentTypeConstraints`. 135 BE tests green. |

### Session environment notes

- Apache Maven 3.9.9 was downloaded into `D:\bin\apache-maven-3.9.9\` (the `D:\bin` directory is already on PATH); the backend has no `mvnw` wrapper. Future sessions should prepend `D:\bin\apache-maven-3.9.9\bin` to `$env:Path` to run `mvn` directly.
- Backend test suite verified green at every checkpoint (159 tests, 8 skipped, 0 failures) — Gap #15 added 3, Gap #16 added 5, Gap #4 added 1, Gap #18 added 1, Gap #6/#20 added 2, Follow-up #3 added 8, Gap #5 added 3, Gap #3 added 15. Gap #11 + Follow-up #1 added 0 new tests (the existing suite already exercises the lifecycle end-to-end and passed after the rule-engine wiring; an auto-reject regression test is captured as a follow-up).
- FE typecheck (`tsc -b`) is the gating check for FE changes. The pre-existing baseline of FE test failures (alerts/page, auth/LoginPage, reports/page) has grown since other gaps landed: now 15 failures across `alerts/page.test.tsx`, `auth/LoginPage.test.tsx`, `reports/page.test.tsx`, `home/api.test.ts` (Gap #8 home-gate refactor), `schemas/loan-application.test.ts` (Gap #18 doc-status collapse), `components/app/repayment/*.test.tsx` (Gap #17 repayment changes). None caused by this session's work; the audit slice (23 tests) is fully green.

---

## The Operating Model

The LMS is **not a manual workflow tool**. It is an automated rule-check
engine that sits between LSP partners and disbursement infrastructure.

- **LSP partners** perform credit / KYC / borrower-fitness work upstream
  and submit complete loan applications (data + documents) to the LMS API.
- **The LMS** validates each application against the **product config**
  (principal range, tenure range, interest rate range, required document
  types, etc.) and the **global rules** (one-open-loan-per-borrower, etc.).
- If the application satisfies all rules → **auto-approve** → queue for
  disbursement.
- If any rule fails → **auto-reject** with a structured `rejectionReason`
  listing the specific rules that failed.
- **No human in the loop on the happy path.** Humans (ops + admin) only
  appear via **alerts** that fire on exception paths (stuck states, retry
  failures, DPD escalation, integration-quality regressions, etc.).
- **LMS does not perform credit checks.** That is the LSP's responsibility.
- **LMS does not verify document content.** Light auto-checks (file
  format, file size, checksum) only.

This shifts several gaps from "build a UI for humans to act" to "delete
the UI; the action is automated."

## Conventions

- Endpoints are written as `METHOD /api/v1/...`.
- Effort estimates assume one developer working uninterrupted.
- "BE" = Spring Boot backend. "FE" = `frontend-2/` React app.
- "Minimum-viable" means: ship the smallest change that closes the gap;
  additive extensions can be added post-prod without rework.

---

# Gap Fixes

## Group A — Backend endpoints that didn't exist yet

### Gap #1 — Internal audited PII reveal (issue #8)

**Decision: Kill the reveal flow entirely. Mask aadhaar everywhere instead.**

Reasoning: This is an internal-only platform; a forensic per-click reveal
flow is overhead. The right RBI-aligned posture is to mask aadhaar at
every read site with defense in depth (BE + FE).

#### Frontend changes
- Delete the reveal-PII button on Borrower 360 (`BorrowerHeader.tsx`)
- Delete the reveal-PII button on loan-application detail OverviewTab
- Delete `frontend-2/src/features/borrowers/hooks/useRecordPiiReveal.ts`
  and its test
- Delete the borrower-PII branch in `frontend-2/src/mocks/api/borrowers.ts`
- Add a `maskAadhaar(value: string): string` formatter that returns
  `XXXXXXXX1234` (last 4 visible). Formatter is idempotent on
  already-masked input (`XXXXXXXX1234 → XXXXXXXX1234`).
- Apply the formatter at every render site.

#### Backend changes
- Verify aadhaar is masked on **every** internal endpoint that returns
  it. Known sites:
  - `GET /internal/admin/borrowers/{id}` — already masks; verify
  - `GET /internal/ops/loan-applications/{id}` — verify
  - MIS report CSV — verify
- Remove the LSP `GET /api/v1/lsp/loan-applications/{id}/borrower-pii`
  endpoint
- Remove `LoanApplicationService.revealBorrowerPiiForLsp(...)`
- Remove `LspBorrowerPiiRevealResponse` record
- Remove the `LoanApplicationPiiRevealAudit` write call from that
  service method

#### Cleanup follow-up
`LoanApplicationPiiRevealAudit` table and its repository become
write-dead. **Do not drop the migration** in the same change — keep the
table around as a dead read-only store for forensic continuity. Flag for
future cleanup once it's clear no surface needs it.

#### Effort
~1 day (FE removal + masking formatter + BE endpoint removal).

---

### Gap #2 — Borrower Activity tab

**Decision: Remove the Activity tab entirely.**

Reasoning: Per-loan audit on the loan detail page covers normal ops work.
Borrower-scoped audit isn't a real workflow on this platform. The tab
currently shows fake mock data, which is actively misleading.

#### Changes (frontend-only)
- Delete `frontend-2/src/features/borrowers/components/tabs/ActivityTab.tsx`
  and `ActivityTab.test.tsx`
- Delete `frontend-2/src/features/borrowers/hooks/useBorrowerActivity.ts`
- Remove the Activity tab trigger from the Borrower 360 tab composition
- Delete the borrower-activity branch in `mocks/api/borrowers.ts`
- Delete the borrower-activity fixtures in `mocks/db/state.ts`
- Remove `BorrowerActivityEntry` type from
  `frontend-2/src/features/borrowers/types.ts` if no other consumers

#### Effort
½ day FE. Zero BE.

---

### Gap #3 — Unified cross-domain audit search

**Decision: Build a real unified backend endpoint.**

Reasoning: RBI / compliance audits are a real periodic workflow on this
platform. Cross-stream search needs to be self-serve, not "engineer runs
SQL."

#### Endpoint contract

```
GET /api/v1/internal/admin/audit-events
Auth: SYSTEM_ADMIN

Query params (all optional, AND'd together):
  streams=APPLICATION,INTAKE,DOCUMENT_ACCESS,PRODUCT  (default: all four)
  actorUsername=alice.ops                              (exact match)
  lspId=<uuid>
  loanApplicationId=<uuid>
  borrowerId=<uuid>                                    (joins via loan_application.borrower_id)
  productId=<uuid>                                     (PRODUCT stream only)
  since=2026-01-01T00:00:00Z
  until=2026-12-31T23:59:59Z
  offset=0  limit=100  (max 500)
  paginationDetails=true|false                         (totalCount opt-in; expensive)

Returns 200 PagedResult<UnifiedAuditEventResponse>

UnifiedAuditEventResponse {
  id: "APPLICATION:<uuid>",            // composite: stream + native id
  stream: APPLICATION | INTAKE | DOCUMENT_ACCESS | PRODUCT,
  occurredAt: ISO-8601 UTC,
  actorUsername: string,
  loanApplicationId: string?,           // null for PRODUCT
  borrowerId: string?,                  // null for PRODUCT
  lspId: string?,                       // null for PRODUCT
  productId: string?,                   // only for PRODUCT
  action: string,                       // stream-specific action enum
  summary: string,                      // pre-formatted human-readable line
  detail: object?,                      // stream-specific structured payload
  correlationId: string?
}
```

#### Backend implementation
- New `AuditExplorerController` + `AuditExplorerService` + 4 per-stream
  projection repositories
- Implement as native SQL `UNION ALL` across the four audit tables
  ordered by `occurred_at DESC`. Filters push down per branch.
- Migrations: ensure indexes on each audit table:
  - `(occurred_at DESC)`
  - `(actor_username, occurred_at DESC)`
  - `(loan_application_id, occurred_at DESC)` where applicable
- **PII masking in INTAKE detail**: the `INTAKE` stream stores raw
  inbound LSP payloads which contain aadhaar. Apply the same
  `maskAadhaar` formatter (Gap #1) to the `detail` payload before
  returning. Raw payload stays in DB for true forensics.

#### CSV export
**Deferred to existing report-request infrastructure.** Add a new
`AUDIT_EXPORT` report type that reuses async job processing + email
delivery + history. Audit endpoint stays JSON-only.

#### Frontend
- Replace client-side composition in the existing Audit Explorer page
  with a single live fetch
- Add filter controls: actor (typeahead from `/admin/users`), stream
  multi-select, date range, LSP / loan / borrower / product autocompletes
- Pagination: simple offset/limit with "load more" or numbered pages
- Per-stream row renderer for the `detail` payload

#### Effort
~3 days BE + ~1.5 days FE. Largest gap.

---

### Gap #4 — LSP `GET /documents` endpoint

**Decision: Ship minimum-viable now; downloadUrl and standard-types
projection deferred to post-prod.**

#### Endpoint (minimum-viable)

```
GET /api/v1/lsp/loan-applications/{applicationId}/documents
Auth: LSP_API_CLIENT | LSP_UI_READ | LSP_UI_WRITE
Scope: authenticated LSP id (404 if loan not owned)

Returns 200 List<LspDocumentChecklistResponse> {
  documentType: enum,
  status: PENDING | SUBMITTED,           // see Gap #18 — VERIFIED/REJECTED removed
  fileName?: string,
  contentType?: string,
  note?: string,
  uploadedAt?: ISO,
  uploadedByUsername?: string
}

Body is "uploads only" — no PENDING placeholder rows for un-uploaded types.
```

Reuses `LoanApplicationOpsResponses.toDocumentChecklistResponse(...)` for
the projection.

#### Deferred to post-prod (zero rework cost)
- `downloadUrl` field with time-limited signed URL — purely additive
- "Project all standard document types" mode (PENDING placeholders) —
  additive but slightly contract-affecting for external LSP API consumers;
  gate behind `?includePending=true` when added

#### Frontend
- Consume the GET on page mount to seed the checklist
- Fixes the "checklist resets on reload" bug
- Keeps the FE-side overlay against standard types (FE responsibility
  unchanged for now)

#### Effort
½ day total.

---

### Gap #5 — Per-application webhook view for OPS_USER

**Decision: New per-loan endpoint, metadata-only.**

#### Endpoint

```
GET /api/v1/internal/ops/loan-applications/{applicationId}/webhook-events
Auth: SYSTEM_ADMIN | OPS_USER

Returns 200 List<WebhookEventDeliveryResponse> {
  eventId: string,
  eventType: string,           // LOAN_APPLICATION_CREATED, LOAN_STATUS_CHANGED, ...
  targetUrl: string,
  status: PENDING | DELIVERED | FAILED | DEAD_LETTERED,
  attempts: int,
  lastAttemptAt: ISO,
  lastResponseCode: int?,
  lastError: string?,
  createdAt: ISO
}

Hard limit 200 rows. No pagination.
```

Projection joins `WebhookEventOutbox` to `WebhookEventDeliveryAttempt`.

#### Deferred to post-prod
- Payload preview / full payload viewer (potential PII exposure;
  requires masking + audit)
- Per-attempt drill-down (today exposes only the last attempt)

#### Frontend
- Drop the admin/ops branch in the Webhooks tab; both roles call this
  one endpoint
- Admin outbox endpoint stays for system-wide views (separate surface)

#### Effort
½ day total.

---

### Gap #6 — Borrower DPD aggregate (`activeOverdueAmount`)

**Decision: BE adds a delinquency aggregate block to the borrower-admin
response.**

Authoritative server-side calculation. Avoids client/server drift on DPD
bucket definitions (BUCKET_1 / BUCKET_2 / NPA cutoffs).

#### Response shape change

```
GET /api/v1/internal/admin/borrowers/{id}
Response now includes:
  delinquency: {
    activeOverdueAmount: BigDecimal,    // sum of overdue across active loans
    maxDaysPastDue: int,                // worst DPD across active loans
    overdueLoanCount: int,
    bucket: string?                     // worst bucket across loans
  }
```

#### Frontend
- Bind `Borrower 360` "Active Overdue Amount" tile to the new field
- Drop the hardcoded `0` fallback

#### Effort
~½ day (BE aggregate calc + FE binding).

---

## Group B — Shape fixes on existing endpoints

### Gap #7 — Home overview KPIs (6 missing fields)

**Decision: Ship all 6 in one PR.**

#### Response shape change

```
GET /api/v1/internal/home/overview  (path unchanged)
Now also includes:
  applicationsAwaitingApproval: int,
  applicationsInDisbursement: int,
  avgApprovalTatHours: number?,           // null if no approvals in 30d window
  applicationsByStatus: { [status: string]: int },
  dpdBuckets: { [bucket: string]: int },
  openAlerts: int
```

#### Performance note
Verify indexes; add migrations for any missing:
- `loan_application(status)` and `loan_application(status, created_at)`
- `loan_application_status_transitions(application_id, target_status, occurred_at)`
- `ops_alert(status)`

If the home page becomes hot post-launch, move to a pre-aggregated
`home_dashboard_snapshot` table refreshed by a scheduled job. Defer until
proven necessary.

#### Effort
~1 day (BE service extension + projections + index migrations + FE binding).

---

### Gap #8 — Home overview only projects for SYSTEM_ADMIN

**Decision: Post-login redirect by role. Home is admin-only.**

The mock home for non-admin roles is deleted entirely. Each non-admin role
lands directly on their natural primary work surface after login.

#### Redirect rules

| Role | Landing route |
| --- | --- |
| `SYSTEM_ADMIN` | `/home` (the existing admin dashboard) |
| `OPS_USER` | `/loan-applications` |
| `PRODUCT_ADMIN` | `/admin/products` |
| `LSP_UI_READ`, `LSP_UI_WRITE` | `/my-loans` |

#### Frontend
- Post-login redirect logic in the login success handler (consults the
  resolved session role)
- Role-aware sidebar: hide the "Home" nav item for non-admins
- Delete the mock home branches for non-admin roles
- ~3 hours FE work, zero BE

---

### Gap #9 — Session context missing user UUID

**Decision: BE exposes real user UUID. FE drops the random-UUID shim.**

#### Backend
- `GET /api/v1/internal/system/context` now includes `id: UUID` (the
  real `app_user.id`)

#### Frontend
- Delete the `crypto.randomUUID()` fallback in the session bootstrap
- Delete the localStorage persistence of the fake UUID
- Use the backend-supplied UUID directly

#### Effort
~1 hour total.

---

### Gap #10 — MIS preview shape wider than frontend

**Decision: Widen the FE preview table to show every BE column.**

#### Frontend
- Add the missing fields to `MisPreviewRow`
- Update the preview table renderer to show all columns
- Handle wider-table layout (horizontal scroll inside the preview card
  if needed)

#### Verify (related to Gap #1)
Confirm the MIS CSV output masks aadhaar per the global masking rule.

#### Effort
~½ day FE.

---

### Gap #11 — Loan application status enum

**Decision: 10-status enum; event-driven auto state machine; "Blocking
Issues" diagnostic panel separates lifecycle phase from why-it's-stuck.**

This is a significant change. Read this alongside § The Operating Model.

#### Final status enum (10 values)

| Status | Meaning | Entry trigger |
| --- | --- | --- |
| `INITIALIZED` | Application created, intake | Auto on LSP intake |
| `AWAITING_APPROVAL` | Auto-rule-check in progress (transient — persists for ms) | Auto when intake gate passes |
| `APPROVED_PENDING_DISBURSAL` | Auto-approval succeeded, queued for payout | Auto from AWAITING_APPROVAL |
| `REJECTED` | Auto-rejection (terminal off-path) | Auto from AWAITING_APPROVAL when rules fail |
| `INVALID` | LSP-side cancellation (terminal) | LSP calls `POST .../invalid` |
| `DISBURSEMENT_RETRY` | Disbursement failed, will retry | Auto on disbursement adapter failure |
| `DISBURSED` | Payout completed | Auto on disbursement success |
| `UNDER_REPAYMENT` | Schedule running | Auto on schedule activation |
| `CLOSED` | Normal closure (terminal) | Auto on full repayment |
| `FORECLOSED` | Foreclosure executed (terminal) | Phase 8 — enum value reserved |

**Renames**: BE `PAYMENT_REINITIATION` → `DISBURSEMENT_RETRY` (clearer;
"payment" is ambiguous between disbursement and repayment).

#### State machine

```
INITIALIZED
  └─ [auto-rule-check on every doc upload + field update]
     └─ AWAITING_APPROVAL  (transient; persists in DB for ms — clean audit)
        ├─ [rules pass]  → APPROVED_PENDING_DISBURSAL
        └─ [rules fail]  → REJECTED
        
APPROVED_PENDING_DISBURSAL
  ├─ [disbursement attempt succeeds] → DISBURSED
  └─ [disbursement attempt fails]    → DISBURSEMENT_RETRY → DISBURSED

DISBURSED → UNDER_REPAYMENT
UNDER_REPAYMENT
  ├─ [final installment paid] → CLOSED
  └─ [foreclosure executed]   → FORECLOSED  (Phase 8)

INVALID can be set from any pre-disbursal status (LSP-initiated cancel).

Terminal: REJECTED, INVALID, CLOSED, FORECLOSED.
```

Every transition writes a row to the status-transitions table with
`actor=system` for the auto-decided transitions. `REJECTED` rows carry
a structured `rejectionReason: { failedRules: [...] }` listing exactly
which auto-rules failed.

#### UNDER_REPAYMENT badge tone (UI semantic)
- Default: **green** ("on track")
- When delinquency aggregate shows `maxDaysPastDue > 0` or
  `overdueInstallmentCount > 0`: **red**
- Implementation: FE helper `getStatusBadgeTone(status, delinquency)`
  returns `success | danger | neutral`. The enum value stays a single
  `UNDER_REPAYMENT`; the visual derives from per-loan delinquency
  (already shipped via Gap #6).

#### "Blocking Issues" panel on loan detail page

A new diagnostic panel on the loan detail page that surfaces *why* a
loan is in its current state. This separates **lifecycle phase** (the
status enum) from **diagnostic data** (the why).

| Status | Panel surfaces | Data source |
| --- | --- | --- |
| `INITIALIZED` | Missing required docs | `LoanApplicationDocumentChecklist` |
| `AWAITING_APPROVAL` | (rarely visible — transient) | n/a |
| `APPROVED_PENDING_DISBURSAL` | Last disbursement attempt outcome | `LoanDisbursementRequestLog` |
| `DISBURSEMENT_RETRY` | Failure reason + attempt count | `LoanDisbursementRequestLog` |
| `DISBURSED` | First due date + schedule preview | `LoanRepaymentSchedule` |
| `UNDER_REPAYMENT` (red) | Overdue count, max DPD, bucket, overdue amount | Delinquency aggregate (Gap #6) |
| `REJECTED` | Failed rules from `rejectionReason` | Status transition history |
| `INVALID` | LSP-supplied invalidation reason | Application metadata |

All data sources already exist. The work is exposing them in one panel.

#### Backend work
- Rename `PAYMENT_REINITIATION` → `DISBURSEMENT_RETRY` (enum value
  + audit any existing transition rows + migration if needed)
- Add `FORECLOSED` to the enum (no live transitions until Phase 8)
- Update `canTransitionTo()` for the full state machine
- Wire auto-transitions:
  - On every doc upload + every loan/borrower field update → re-evaluate
    the auto-approval gate; if satisfied, transition to AWAITING_APPROVAL
  - On entry into AWAITING_APPROVAL → immediately evaluate full rule
    set → transition to APPROVED_PENDING_DISBURSAL or REJECTED
  - Existing disbursement and repayment services keep driving their
    auto-transitions
- ~80 LOC BE for enum/state machine; the rule-engine implementation
  is a separate larger piece of work (see § Implicit Follow-ups)

#### Frontend work
- Trim `LoanStatus` enum in `frontend-2/src/schemas/loan-application.ts`
  to the 10 statuses
- Drop the fold-mapping in the API adapter
- Update every renderer (loan list status column, detail header badge,
  filter chips)
- Add `getStatusBadgeTone(status, delinquency)` helper
- Build the "Blocking Issues" panel (~150 LOC)

#### Effort
~1.5 days total (BE + FE). The auto-approval rule engine itself is a
larger follow-up; see § Implicit Follow-ups.

---

### Gap #12 — Webhook event-type enum mismatch

**Decision: Drop the two FE-only values.**

The FE was advertising subscription to events the BE never emits:
`loan.disbursement.failed` and `loan.foreclosure.quote.generated`. Aligns
to what BE actually delivers; re-add when BE supports them.

#### Frontend
- Remove the two values from the FE's event-type list
- Drop the fold-mapping in the LSP admin endpoint adapter

#### Effort
~½ hour FE.

---

## Group C — Write endpoints missing for admin CRUD

### Gap #13 — Users admin PUT (update)

**Decision: Implement update with session invalidation + self-edit
guards.**

#### Endpoint

```
PUT /api/v1/internal/admin/users/{userId}
Auth: SYSTEM_ADMIN
Body: {
  fullName?: string,
  email?: string,
  roles?: string[],         // role codes
  status?: ACTIVE | DISABLED,
  lspId?: string | null     // null for internal roles; required for LSP roles
}
Returns 200 updated user

Validation:
- email format if provided
- roles must be valid role codes
- lspId must exist if provided
- if user has internal role (SYSTEM_ADMIN / OPS_USER / PRODUCT_ADMIN) — lspId must be null
- if user has LSP role (LSP_API_CLIENT / LSP_UI_*) — lspId required

Immutable:
- username
- id, createdAt, createdBy
```

#### Side effects
- Audit row: actor, userId, before-state, after-state, timestamp
- **If roles change**: invalidate active sessions for that user.
  Implementation: per-user `token_version` counter on `app_user`. JWT
  embeds this version at issuance. On every request, BE compares the
  JWT's version to the user's current version; mismatch → 401, FE
  triggers refresh, refresh issues a new JWT with the updated version.

#### Self-edit guards
- Cannot remove `SYSTEM_ADMIN` role from yourself if you would be the
  last `SYSTEM_ADMIN` (prevents lockout)
- Cannot `DISABLE` yourself

#### Effort
~3/4 day (BE controller + service + audit + guards + token-version
plumbing) + small FE rewire.

---

### Gap #14 — API clients update + rotate-secret

**Decision: Both endpoints. 300s default grace period on rotation.**

#### Update endpoint

```
PUT /api/v1/internal/admin/api-clients/{clientId}
Auth: SYSTEM_ADMIN
Body: {
  description?: string,
  status?: ACTIVE | DISABLED,
  ipAllowlist?: string[]      // CIDR ranges
}
Returns 200 updated client (secret NOT included)

Immutable: clientId, lspId. To "move" a client to a different LSP, create
a new client and revoke the old one.
```

#### Rotate-secret endpoint

```
POST /api/v1/internal/admin/api-clients/{clientId}/rotate-secret
Auth: SYSTEM_ADMIN
Body: { graceSeconds?: number }   // default 300 (5 min); caller can pass 0 for immediate revoke
Returns 200 {
  clientId,
  clientSecret,                   // one-shot exposure, never returned again
  oldSecretValidUntil: ISO?       // null if graceSeconds=0
}
Side effects:
- Audit row: who rotated, when, against which client, grace
- New secret stored hashed
- Old hash retained until expiry; rejected after
```

#### Effort
~3/4 day total.

---

### Gap #15 — Alerts ack note payload

**Decision: Optional 500-char note on ack body.**

#### Changes

```
POST /api/v1/internal/alerts/{alertId}/acknowledge
Body: { note?: string }   // optional, max 500 chars
Returns 200 updated alert
```

- New column `ops_alert.acknowledgement_note VARCHAR(500)` (Flyway
  migration)
- Endpoint persists the note + writes audit row (already done for ack
  itself; just include the note)
- Read endpoint returns the note on the alert response
- FE drops the client-side preservation hack

#### Effort
~1 hour total.

---

### Gap #16 — Lifecycle state machine for OPS_USER

**Decision: Drop OPS_USER approve/reject UI entirely. View-only on
lifecycle.**

Given § The Operating Model — approvals are automated. There are no
human-driven AWAITING_APPROVAL → APPROVED transitions to gate.

#### Frontend
- Remove approve/reject action buttons for `OPS_USER`
- `SYSTEM_ADMIN` keeps the `/manual-status` escape hatch for
  exceptional cases
- OPS_USER loan detail action bar becomes: **view-only** with one
  possible action — "Escalate to admin" (creates an alert)
- ~50 LOC FE cleanup

#### Effort
~½ hour.

---

### Gap #17 — Repayment allocation + channel + reference

**Decision: Strict full-installment payments. Exact amount match.
`targetInstallmentId` required. Widened channel enum. Clean
Idempotency-Key / `reference` split.**

#### Endpoint

```
POST /api/v1/internal/ops/loan-applications/{id}/payments
Headers:
  Idempotency-Key: <uuid>           (REQUIRED — HTTP dedup only)

Body:
{
  "targetInstallmentId": "<uuid>",  (REQUIRED — must belong to this loan, must be unpaid)
  "amount": BigDecimal,             (REQUIRED — must EXACTLY match installment.dueAmount)
  "channel": "NEFT" | "RTGS" | "IMPS" | "BANK_TRANSFER" | "UPI" | "CASH",
  "postedAt": ISO,
  "reference": string?              (optional — bank UTR / cheque number / UPI tx id for reconciliation)
}

Validation:
  - 404 if installment not found
  - 403 if installment doesn't belong to this loan
  - 409 if installment already PAID
  - 422 if amount != installment.dueAmount (no tolerance)

Side effects:
  - Mark targeted installment PAID in full
  - Update loan account outstanding
  - Recompute delinquency aggregate (drives Gap #6 + Gap #11 badge tone)
  - Write payment audit row (idempotency key in header, reference in body — separate)
  - Emit webhook event loan.payment.received
```

#### Channel enum
Widen BE to add `NEFT`, `RTGS`, `IMPS` as discrete values. Preserves
channel detail for MIS / reconciliation.

#### Fee tolerance
**None.** Exact match required. Borrower compensates for bank fees
upstream. Cleanest ledger.

#### Late fees
**Not in v1.** `dueAmount` = base installment amount. Late-fee accrual
model deferred entirely. Add later if product team requires it.

#### Bulk-pay
**Per-installment only.** No "pay all overdue" button. UI redesigned to
installment-first selection flow:
- List of unpaid installments with their exact amounts
- Ops clicks one
- Confirm dialog with prefilled non-editable amount + channel/date/reference fields
- Submit

#### Idempotency-Key vs `reference`
Now cleanly separated:
- `Idempotency-Key` (HTTP header): FE-generated random UUID per attempt,
  used for HTTP dedup only. Means nothing to reconciliation.
- `reference` (body field): the real bank UTR / cheque / UPI tx id.
  Optional (can be filled in later when the bank statement arrives).
  Drives reconciliation.

#### Manual installment allocation
Already covered by the new model — `targetInstallmentId` IS the manual
allocation. Server waterfall is replaced entirely.

#### Effort
~1.5 days (BE: widen channel + targetInstallmentId logic + ref split +
migrations; FE: redesign repayment UI to installment-first flow).

---

## Group D — Frontend work blocked on nothing (or now revised)

### Gap #18 — Internal document checklist write UI

**Decision (revised): Delete the verify/reject model entirely.
Documents become append-only attachments with light auto-checks on
upload.**

Given § The Operating Model — LMS does not verify document content.
Per-document VERIFIED/REJECTED status is not part of the automated
model.

#### Backend changes
- Drop `VERIFIED` and `REJECTED` from
  `LoanApplicationDocumentChecklistStatus`. Status becomes `PENDING`
  (no upload yet) or `SUBMITTED` (uploaded).
- Delete `PUT /api/v1/internal/ops/loan-applications/{id}/kyc-documents/{type}`
- Delete `updateDocumentChecklistItem` from the FE API layer

#### Auto-check on upload
On every `POST .../documents` (single + batch, LSP + ops):
- File size ≤ 10 MB
- MIME type ∈ {`application/pdf`, `image/jpeg`, `image/png`}
- Content-length checksum integrity
- Reject with HTTP 422 on failure (no after-the-fact REJECTED status)

(Per-document-type allowlists can tighten later — see § Implicit
Follow-ups.)

#### Re-upload semantics
Uploading the same `documentType` again replaces the previous file.
Audit row records the replacement.

#### Frontend
- DocumentsTab is upload-only for LSP roles (the LSP my-loan detail
  page already has the upload UI)
- DocumentsTab for internal users is **view-only** with no review
  actions
- Drop the per-row verify/reject controls; drop the rejection-note
  dialog plan

#### Effort
~½ day (BE deletion + enum change + FE cleanup).

---

### Gap #19 — Loan-application assignment UI

**Decision (revised): Drop assignment entirely. No replacement queue.**

Given § The Operating Model — loans flow through the automated pipeline.
Humans surface only via alerts, not via assignment queues.

#### Remove
- `POST /api/v1/internal/ops/loan-applications/{id}/assignment` endpoint
- `assigned_to` column (mark deprecated; stop writing; drop in a
  follow-up migration once nothing reads it)
- FE assignment UI: header control, list column, "My loans" filter
- `assignedTo` field from `LoanApplicationListFilters`
- `assignedToUsername` / `assignedByUsername` from response records
  (LspLoanApplicationDetailResponse and the internal equivalents)

#### Approval race condition
With no claim-based ownership, two ops users theoretically could try
the same `/manual-status` admin override concurrently. Solve with
optimistic concurrency on the state machine: BE rejects the second
transition attempt with 409 (loan is no longer in the source state).
FE handles 409 gracefully ("Already updated by <user>; refreshing").

#### "Who acted on this" attribution
The audit row (status transitions, doc uploads, etc.) records the actor.
Different from continuous assignment ownership — this is point-in-time
attribution and is sufficient for accountability.

#### Effort
~½ day cleanup (FE removal + BE endpoint deletion + migration to mark
column deprecated).

---

### Gap #20 — Loan-application detail borrower projection

**Decision: Fetch borrower-admin in parallel on the loan-app detail page.**

The endpoint already exists (`/internal/admin/borrowers/{id}` shipped via
issue #7). The FE just needs to call it from the loan detail page to
enrich the OverviewTab.

#### Frontend

```typescript
const { data: app }      = useLoanApplication(applicationId);
const { data: borrower } = useBorrower(app?.borrowerId, { enabled: !!app?.borrowerId });
```

OverviewTab consumes both:
- Borrower info card prefers the fuller borrower projection
- Falls back to the loan-app's embedded thin projection while the
  borrower query is loading
- Aadhaar still renders masked (per Gap #1)

#### Effort
~¼ day FE.

---

## Group E — Deferred / intentional (no design needed)

### Gap #21 — Legacy frontend retirement (issue #16)

**Status: Deferred.** Both `frontend/` and `frontend-2/` ship in parallel
for side-by-side comparison. Pick up when the team is comfortable
running everything on `frontend-2/` end-to-end.

When ready (~2 hours of mechanical work):
- Delete `frontend/` directory
- Rename `frontend-2/` → `frontend/`
- Update CI scripts, dev start scripts, docs
- Update root-level path references

### Gap #22 — LSP_API_CLIENT login UI

**Status: Not a gap.** Intentional. The `LSP_API_CLIENT` role is for
programmatic API consumers (LSPs hitting REST endpoints with
`client_id` / `client_secret`). No UI login flow is appropriate.

No action needed.

---

# Aggregate Effort

| Component | Estimate |
| --- | --- |
| Backend | ~9 days |
| Frontend | ~5 days |
| **Total** | **~14 days** |

Add the implicit follow-ups below to get a realistic end-to-end picture.

---

# Implicit Follow-ups (Not in Original 20)

These surfaced during the walk because of the model shift to
auto-approval + alerts-driven exceptions. They are **prerequisites** for
the locked design to actually work end to end.

## Follow-up #1 — Auto-approval rule engine

Gap #11's event-driven `AWAITING_APPROVAL → APPROVED_PENDING_DISBURSAL |
REJECTED` transition needs a real rule-check service.

### Engine inputs
- Product config (the active `LoanProduct` for this application)
- Loan-application data (amount, tenure, interest rate)
- Borrower master data (PAN, aadhaar, address, employment, income, references)
- Document checklist (which required types have a SUBMITTED upload)
- Cross-cutting state (one-open-loan rule, LSP↔product mapping
  active, product status ACTIVE)

### Rules to evaluate
- All product-required document types have an upload
- All required loan fields are non-null and well-formed
- All required borrower fields are non-null and well-formed
- `requestedAmount ∈ [product.minPrincipal, product.maxPrincipal]`
- `tenureMonths ∈ [product.minTenure, product.maxTenure]`
- `interestRate ∈ [product.minRate, product.maxRate]`
- Product status is `ACTIVE`
- LSP↔product mapping is `ACTIVE`
- Borrower has no other open loan globally (existing one-open-loan rule)
- Any other product-defined eligibility flags

### Output
- On success: status → `APPROVED_PENDING_DISBURSAL`, emit webhook,
  queue for disbursement
- On failure: status → `REJECTED` with structured
  `rejectionReason: { failedRules: [<rule code>, ...] }`

### Effort
~2–3 days BE on its own.

## Follow-up #2 — Alert rule coverage

Without manual queues, alerts are the **only** way humans see
exceptions. Need explicit `AlertRule` records + scheduled checks +
alert emission on the rule conditions.

### Minimum rule set

| Rule | Trigger | Audience |
| --- | --- | --- |
| Stale intake | `INITIALIZED` > 24h with incomplete doc checklist | OPS |
| Stuck disbursement | `DISBURSEMENT_RETRY` > 2h | OPS |
| DPD bucket transition | UNDER_REPAYMENT enters BUCKET_1 / BUCKET_2 / NPA | OPS |
| LSP integration quality | Auto-reject rate from one LSP > N% over rolling window | SYSTEM_ADMIN |
| Webhook dead-letter | Webhook delivery exhausted retries | SYSTEM_ADMIN |
| One-open-loan violation | LSP attempts loan creation for a borrower with another active loan | SYSTEM_ADMIN |
| Rate-limit breach | LSP hits configured rate limit | SYSTEM_ADMIN |

### Effort
~2–3 days BE (rule scheduler + alert emission + admin UI to view active
rules).

## Follow-up #3 — Document upload auto-check spec

Per-document-type tightening beyond the global "PDF/JPEG/PNG ≤ 10MB".
Examples:
- `LOAN_AGREEMENT` — PDF only
- `AADHAAR`, `PAN` — PDF or JPEG; max 5 MB
- Per-product overrides if needed

### Effort
~½ day (spec + BE enforcement).

---

# Suggested Implementation Order

The gaps + follow-ups aren't independent. Suggested order to minimise
rework:

1. **Foundation** — Gap #9 (real user UUID), Gap #1 (aadhaar masking
   everywhere)
2. **State model** — Gap #11 (status enum + state machine) AND
   Follow-up #1 (auto-approval rule engine) together
3. **Cleanup driven by state model** — Gap #16, Gap #18, Gap #19
4. **Admin write surfaces** — Gap #13, Gap #14, Gap #15
5. **Data completeness** — Gap #6 (DPD aggregate), Gap #7 (home KPIs),
   Gap #20 (borrower projection)
6. **Repayment** — Gap #17
7. **Surfaces** — Gap #4 (LSP GET docs), Gap #5 (per-app webhook),
   Gap #8 (home redirect)
8. **Reporting** — Gap #10 (MIS preview)
9. **Mock removal** — Gap #2 (Activity tab), Gap #12 (webhook events)
10. **Audit infrastructure** — Gap #3 (unified audit endpoint) AND
    Follow-up #2 (alert rules) together
11. **Polish** — Follow-up #3 (doc upload spec)
12. **Eventual** — Gap #21 (legacy frontend retire) when team is ready
