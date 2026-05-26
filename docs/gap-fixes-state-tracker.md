# LMS Gap Fixes — State Tracker

> **Source of truth for resumption.** When a `/clear` resets the conversation,
> read this file *and* `docs/gap-fixes.md` (the design spec) before doing
> anything else. The design spec contains the authoritative per-gap decisions;
> this tracker contains live execution state.

Last updated: 2026-05-25 — Gap #3 closed (unified cross-domain audit search endpoint live, FE wired).

---

## Status legend

- ✅ **Done** — implementation merged, tests green, FE+BE wired and verified
- 🟡 **In progress** — actively under edit in the current session
- ⏳ **Pending** — not started
- ✅ **N/A** — design says not a gap

---

## Gap matrix

| Gap | Title | State | Notes |
| --- | --- | --- | --- |
| #1 | Internal audited PII reveal — kill it; mask aadhaar everywhere | ✅ Done | BE: reveal endpoint + record + service path removed; `LoanApplicationPiiRevealAudit` table retained for forensic continuity; 404 contract test added. FE: reveal dialog + hook + mock branch deleted, `maskAadhaar` formatter applied at every render site. All 116 BE tests + FE typecheck green. |
| #2 | Borrower Activity tab — remove | ✅ Done | Activity tab + hook + types + mock branch deleted. `BorrowerDetailTab` enum trimmed to `profile \| loans`. |
| #3 | Unified cross-domain audit search | ✅ Done | `GET /api/v1/internal/admin/audit-events` SYSTEM_ADMIN-only; native UNION ALL across APPLICATION/INTAKE/DOCUMENT_ACCESS/PRODUCT with filter pushdown (actorUsername, lspId, loanApplicationId, borrowerId, productId, since, until); offset/limit (cap 500) + opt-in `paginationDetails`; aadhaar masking on INTAKE detail. V59 adds the missing actor + global-time indexes per audit table. FE `fetchAuditEvents` rewired to the live endpoint with client-side `q` + `correlationId` post-filters. 15 new BE tests + 159 BE tests total green; 23/23 audit-slice FE tests pass; `tsc -b` clean. |
| #4 | LSP `GET /documents` endpoint | ✅ Done | `GET /api/v1/lsp/loan-applications/{id}/documents` returns uploads-only checklist. Pre-claims Gap #18's `PENDING\|SUBMITTED` contract. FE consumes on mount to seed checklist. |
| #5 | Per-application webhook view for OPS_USER | ✅ Done | `GET /api/v1/internal/ops/loan-applications/{id}/webhook-events` returns up to 200 outbox rows newest-first for `SYSTEM_ADMIN` + `OPS_USER`. New `webhook_event_outbox.loan_application_id UUID` column + indexed lookup (V58 with per-aggregate backfill: `LOAN_APPLICATION` direct, `LOAN_ACCOUNT` via `loan_account`, `LOAN_PAYMENT_TRANSACTION` via the payment + account join). Status mapping: `PENDING/DELIVERED` pass-through, `RETRYABLE_FAILURE→FAILED`, `PERMANENT_FAILURE→DEAD_LETTERED`. `lastResponseCode` joined from the latest delivery attempt. 404 on unknown loan via `ResourceNotFoundException`; 403 on LSP roles via controller-level `@PreAuthorize`. FE: webhooks tab drops the admin-outbox fan-out + OPS-empty hack and calls the per-loan endpoint for any internal session. 3 new BE tests + 144 BE tests green, FE typecheck + 123 loan-app tests green. |
| #6 | Borrower DPD aggregate (`activeOverdueAmount`) | ✅ Done | BE: `BorrowerDelinquencyAggregate` block computed in `AdminDirectoryService.getBorrowerDetail`. FE: Borrower 360 tile + OverviewTab row light up automatically. |
| #7 | Home overview KPIs (6 missing fields) | ✅ Done | BE `HomeDashboardSummary` adds `applicationsAwaitingApproval`, `applicationsInDisbursement`, `avgApprovalTatHours`, `applicationsByStatus`, `dpdBuckets`, `openAlerts` + `openAlertSummaries`. Repos: count/group queries + TAT from transitions (30d). `V57__home_dashboard_query_indexes.sql`. FE drops zero-fill stub; `mapBackendHomeOverviewToInternalKpis` maps BE DPD buckets → chart ids. Tests: `HomeDashboardControllerTest`, `api.backend-map.test.ts`. |
| #8 | Post-login role redirect | ✅ Done | `defaultLandingFor`: SYSTEM_ADMIN→`/home`, OPS_USER→`/loan-applications`, PRODUCT_ADMIN→`/products`, LSP→`/my-loans`. Wired in LoginPage, LandingRedirect, ChangePasswordPage, RequireInternal/RequireLsp, HomePage guard. Sidebar hides Home for non-admins. Mock + client home KPI fetch admin-only. Tests: `role-gates.test.ts`, `nav-items.test.ts`, `LoginPage.test.tsx`, `home/page.test.tsx`, `home.test.ts`, `router.test.tsx`. |
| #9 | Session context missing user UUID | ✅ Done | BE returns real `app_user.id` (deterministic UUID for bootstrap admin). FE drops `crypto.randomUUID` shim + localStorage persistence. |
| #10 | MIS preview widening | ✅ Done | BE: `AdminReportingService` masks aadhaar (`XXXXXXXX####`) + bank (`XXXX####`) in `PortfolioMisRow` preview + CSV; `ReportAdminControllerTest.portfolioMisPreviewMasksAadhaarAndBankAccountForEveryRow` green. FE: widened `MisPreviewRow` schema, `mapBackendPreviewRowToMisPreviewRow` (DPD `CURRENT`→`B0`, etc.), horizontal-scroll `MisPreviewTable` with all BE columns + dynamic EMI columns, defensive `maskAadhaar` in cells. Tests: `api.backend-map.test.ts`, `MisPreviewTable.test.tsx`, `page.test.tsx` (hook mocks). |
| #11 + Follow-up #1 | Status enum, state machine, auto-approval rule engine | ✅ Done | BE: `PAYMENT_REINITIATION` → `DISBURSEMENT_RETRY` rename + `FORECLOSED` added; `canTransitionTo()` rewritten as the full 10-status state machine. `LoanAutoApprovalRuleEngine` evaluates 8 rule families (product/LSP active + mapping enabled, amount/tenure range, borrower required fields, required-for-approval docs, one-open-loan); wired into `autoApproveIfEligibleForLsp`. On AWAITING_APPROVAL with failing rules → auto-REJECTED with structured `rejection_reason_json` carried on the new `loan_application_status_transition.rejection_reason_json` column (V52). V51 migration backfills historical PAYMENT_REINITIATION rows. FE: `LoanStatus` extended with the 10 canonical BE values (legacy values retained for mock compatibility); `mapBackendStatus` / `mapFrontendStatusToBackend` pass through the canonical 10 + fold legacy. `getStatusBadgeTone(status, delinquency)` helper added to `lib/lifecycle.ts`. `BlockingIssuesPanel` component surfaces per-status diagnostics (docs missing, disbursement retry, schedule start, delinquency, rejection reason, invalidation reason); wired into OverviewTab. All 127 BE tests + 1020 FE tests green. |
| #12 | Drop FE-only webhook event values | ✅ Done | `loan.disbursement.failed` + `loan.foreclosure.quote.generated` removed from FE enum, fold map, labels, mocks. |
| #13 | Users admin PUT (update) + token-version sessions | ✅ Done | `PUT /api/v1/internal/admin/users/{userId}`, `app_user.token_version` + JWT `tv` claim, audit events, role-change session invalidation, self-disable / last-SYSTEM_ADMIN guards; FE `updateUser` wired to PUT. |
| #14 | API clients PUT + rotate-secret | ✅ Done | `PUT /api/v1/internal/admin/api-clients/{id}`, `POST .../rotate-secret` with 300s default grace, per-client `ipAllowlist`, audit events; FE wired. |
| #15 | Alerts ack note payload | ✅ Done | `V49__ops_alert_acknowledgement_note.sql` + bean-validated `@Size(max=500)` body + happy/no-body/over-limit tests. FE drops client-side preservation hack. |
| #16 | Drop OPS_USER approve/reject UI | ✅ Done | `OpsAlertType.OPS_USER_ESCALATION` + `OpsAlertController.escalate`; FE `EscalateToAdminDialog` replaces the OPS_USER action bar. SYS_ADMIN keeps the `/manual-status` escape hatch. |
| #17 | Strict per-installment repayments | ✅ Done | `POST .../payments` requires `Idempotency-Key` + `targetInstallmentId` + exact amount; channels NEFT/RTGS/IMPS; optional `reference`; V56 migration; FE installment-first flow with read-only amount + reference field. |
| #18 | Delete verify/reject doc model | ✅ Done | BE enum collapsed to `PENDING \| SUBMITTED \| NOT_REQUIRED`; `PUT /kyc-documents/{type}` deleted; lifecycle gates now "SUBMITTED == uploaded"; storage service enforces ≤ 10 MB + MIME allowlist; V50 migration drops `review_reason` / `rejection_reason`. FE schemas trimmed to `PENDING \| UPLOADED`; `DocumentRejectDialog` + form schema deleted; checklist row is view-only; lifecycle BR-2/BR-3 gates require UPLOADED. 127 BE tests + 1020 FE tests green. See detailed entry in `docs/gap-fixes.md` for the full files-changed list. |
| #19 | Drop assignment entirely | ✅ Done | BE: `POST/GET .../assignment` + `.../assignment-events` return 404; `assignApplication` / assignment writes removed; `assignedTo*` stripped from ops + LSP list/detail responses; assignment dropped from `getLatestActivity`; `V53` deprecates DB columns. FE: assignee filter, table column, OverviewTab assignment section removed; schemas/mocks aligned. Tests: `loanApplicationAssignmentEndpointsAreRemoved`, `loanApplicationResponsesOmitAssignmentFields`. 135 BE tests green; `tsc -b` clean. |
| #20 | Loan-app detail: parallel borrower-admin fetch | ✅ Done | `useBorrowerDetail` called in parallel with `useLoanApplicationDetail`; OverviewTab prefers fuller borrower projection, falls back to embedded thin projection on load/error. |
| #21 | Legacy frontend retirement | ⏳ Pending | Intentionally deferred per § Group E. |
| #22 | LSP_API_CLIENT login UI | ✅ N/A | Intentionally not a gap. |
| Follow-up #2 | Alert rule coverage | ✅ Done | `alert_rule` + 7 seeded rules (V60 + `AlertRuleDataInitializer`). `AlertRuleSchedulerWorker` (5 min) evaluates stale intake, stuck disbursement, DPD bucket, LSP reject spike. Event emission: `WebhookOutboxService` → dead-letter, `RateLimitFilter` → rate breach; intake duplicate already via `BORROWER_ACTIVE_LOAN_DUPLICATE`. `OpsAlertService.createAlertIfAbsent` dedupes open alerts. `GET /internal/alerts/rules` (SYSTEM_ADMIN). FE `AlertRulesPanel` + `useAlertRules`. Tests: `OpsAlertControllerTest` (incl. rules list). |
| Follow-up #3 | Document upload auto-check spec | ✅ Done | Global tier from Gap #18 retained. New `DocumentUploadPolicy`: `LOAN_AGREEMENT` → PDF only (≤ 10 MB); `PAN_CARD` / `AADHAAR_FILE` → PDF or JPEG (≤ 5 MB); all other types → global cap. Wired through `ConfigurableLoanDocumentStorageService.store`. 7 unit tests in `DocumentUploadPolicyTest`; integration contract in `LspLoanApplicationApiControllerTest.documentUploadEnforcesPerDocumentTypeConstraints`. |

---

## Suggested next gaps (per § Suggested Implementation Order)

1. ~~Gap #19~~ — done
2. ~~Gap #13 / #14~~ — done
3. ~~Gap #7~~ — done
4. ~~Gap #17~~ — done
5. ~~Gap #5~~ — done; ~~Gap #10~~ — done; Gap #8 done.
6. ~~Gap #3~~ — done; ~~Follow-up #2~~ — done.
7. ~~Follow-up #3~~ — done (per-document-type upload policy)
8. Gap #21 — legacy frontend retirement (deferred)

---

## Session environment notes

- Apache Maven 3.9.9 lives at `D:\bin\apache-maven-3.9.9\`; `D:\bin` is on PATH but the wrapper is absent — prepend `D:\bin\apache-maven-3.9.9\bin` to `$env:Path` before running `mvn` directly. (PowerShell on Windows.)
- Backend test suite baseline: 159 tests green (Gap #3 added 15 controller integration tests), 8 skipped, 0 failures. New tests must keep that suite green.
- FE: `tsc -b` is the gating check; vitest now reports 1028 passing / 15 pre-existing failures across `alerts/page.test.tsx`, `auth/LoginPage.test.tsx`, `reports/page.test.tsx`, `home/api.test.ts`, `schemas/loan-application.test.ts`, `components/app/repayment/*.test.tsx`. None touched by Gap #3 — those test files predate this stream and are stale relative to earlier gap closures (Gap #8 home-gate, Gap #18 doc-status collapse, Gap #17 repayment). All 23 audit-slice FE tests pass.
- After modifying code in this session, run `graphify update .` to keep the knowledge graph current (AST-only, no API cost). Skipped at the end of the Gap #3 session because the `graphify` binary was not on PATH; re-run when the binary is restored.

---

## How to resume after `/clear`

1. Read `docs/gap-fixes.md` (design spec) and this file in full.
2. Find the row with state 🟡 **In progress** and read its detailed status (below).
3. If no 🟡 row exists, pick the next pending gap from the suggested order above.
4. Implement TDD-first (failing tests first, then code).
5. After each gap completes: update both this tracker *and* the embedded tracker in `docs/gap-fixes.md` so the two stay aligned; mark the gap ✅ Done; then `/clear`.

---

## Detailed state of in-progress work

_No work is currently in progress. Pick the next gap from § Suggested next gaps._

### Recently closed

#### Gap #3 — Unified cross-domain audit search (closed 2026-05-25)

**Endpoint:** `GET /api/v1/internal/admin/audit-events`
- Auth: SYSTEM_ADMIN-only (controller-level `@PreAuthorize("hasRole('SYSTEM_ADMIN')")`); OPS_USER + LSP roles → 403.
- Query params (all optional, AND'd): `streams=APPLICATION,INTAKE,DOCUMENT_ACCESS,PRODUCT` (default: all 4), `actorUsername`, `lspId`, `loanApplicationId`, `borrowerId`, `productId`, `since`, `until` (both ISO-8601 instants), `offset`, `limit` (1–500, default 100), `paginationDetails` (default false).
- Response: `PagedResult<UnifiedAuditEventResponse>` with `totalCount = -1` sentinel when `paginationDetails=false` to avoid the COUNT(*) wrap.

**Response envelope** `AuditExplorerEvent`: `{ id (composite "STREAM:uuid"), stream, occurredAt, actorUsername, loanApplicationId?, borrowerId?, lspId?, productId?, action, summary, detail: object, correlationId? }`. Per-stream `detail`:
- APPLICATION → `{ fromStatus, toStatus, reasonCode?, note? }`
- INTAKE → `{ payload: <parsed JSON with aadhaar fields masked> }`
- DOCUMENT_ACCESS → `{ documentTypes: string[] }`
- PRODUCT → `{}` (summary alone carries the change description)

**PII masking:** Implemented in `AuditExplorerService.maskIntakePayload`. Parses the stored INTAKE payload JSON and rewrites any of `borrowerAadharNumber`, `borrowerAadhaarNumber`, `aadharNumber`, `aadhaarNumber`, `aadhar` to `XXXXXXXX<last4>` before serialising. Raw aadhaar stays untouched in DB (forensic continuity per Gap #1 design).

**Native UNION ALL query:** `AuditExplorerRepository` builds the SQL dynamically by concatenating only the enabled stream branches. Each branch projects 16 columns with explicit `cast(... as varchar(N))` to neutralise H2's enum-CHECK constraints on `action` / status columns (otherwise UNION inherits the first branch's constraint and rejects rows from subsequent branches). PRODUCT branch is automatically excluded when `lspId` / `loanApplicationId` / `borrowerId` is set (its rows have no such identity); non-PRODUCT branches are automatically excluded when `productId` is set. Outer query: `order by occurred_at desc, native_id desc limit ? offset ?` (H2 does not accept `OFFSET ... LIMIT ...` ordering). Total-count is a separate `select count(*) from (...)` wrap, only fired when `paginationDetails=true`.

**Indexes (V59__audit_explorer_indexes.sql):** Adds the missing `(actor_username, created_at DESC)` and global `(created_at DESC)` indexes on each of the four audit tables. `(subject_id, created_at DESC)` indexes were already in place (V5/V8/V16/V28).

**Frontend:** `frontend-2/src/features/audit/api.ts` rewritten — drops the per-application fan-out + client-side composition, calls the live endpoint, projects `AuditExplorerEvent` → existing `AuditRow` (preserving the column/sheet contract), and applies `correlationId` + free-text `q` as client-side post-filters (BE spec doesn't expose those). Stream subset sent to BE filters out `PII_REVEAL` automatically (no BE support, see Gap #1).

**Tests (15 new in `AuditExplorerControllerTest`):**
- `searchReturnsAllFourStreamsForSystemAdmin`, `streamsFilterScopesResponseToSelectedStreams` — stream-set behaviour.
- `searchIsForbiddenForOpsUser`, `searchIsForbiddenForLspApiClient` — RBAC.
- `actorUsernameFilterReturnsOnlyMatchingRows`, `lspIdFilterExcludesProductStreamAndOtherLsp`, `loanApplicationIdFilterPushesDownToEveryBranch`, `productIdFilterReturnsOnlyProductStream` — filter pushdown across branches.
- `intakePayloadAadhaarIsMaskedInDetail` + raw-leak assertion — PII masking contract.
- `compositeIdFormatIsStreamColonNativeId` — id shape `STREAM:<uuid>`.
- `paginationDetailsOffByDefaultReturnsSentinel`, `offsetLimitClampsToBoundsAndPaginates` — pagination behaviour.
- `invalidSinceParameterReturns400`, `unknownStreamValueReturns400` — input validation.
- `responseIsOrderedByOccurredAtDesc` — sort invariant.

**Edge cases covered:**
- Empty `streams=` parameter → defaults to all 4 (controller).
- Whitespace-only `actorUsername` → treated as no filter.
- `limit > 500` → clamped to 500; `limit < 1` → reset to default 100; `offset < 0` → 0.
- `since > until` → 400 (record-level guard).
- Cross-stream same-actor query: actorUsername pushes down to every active branch.
- PRODUCT rows never appear when an LSP/loan/borrower filter is set (because the branch is skipped client-side in the SQL builder, not just zero-result).
- Non-JSON intake payload → `{ raw: "<original string>" }` placeholder so the endpoint never 500s on a corrupt row.

**Files changed (delta only):**
- BE service: `AuditExplorerQuery.java` (new), `AuditExplorerService.java` (new).
- BE repo: `AuditExplorerRepository.java` (new).
- BE web: `AuditExplorerController.java` (new).
- BE migration: `V59__audit_explorer_indexes.sql` (new).
- BE tests: `AuditExplorerControllerTest.java` (new).
- FE: `frontend-2/src/features/audit/api.ts` (rewritten — drops the per-application fan-out + composition).

**Verification:** 159 BE tests green (`mvn -f backend/pom.xml -q test`), 8 skipped, 0 failures. FE `tsc -b` clean; 23/23 audit-slice vitest tests pass (`src/features/audit/**` + `src/mocks/api/audit.test.ts`). Full FE suite has 15 pre-existing failures unrelated to Gap #3 (home/repayment/alerts/reports/loan-application-schema — see § Session environment notes).

**Follow-ups (not blocking close):**
- BE-side `correlationId` filter (currently client-side post-filter on the FE) — useful for alerts-deep-link traffic that should not fetch a full page just to filter to one event.
- CSV export via a new `AUDIT_EXPORT` report type (per design, deferred to existing report-request infrastructure). Audit endpoint stays JSON-only.
- LSP / borrower / product autocomplete inputs on the FE filter bar (the URL already supports those params; only the FE control is missing).

#### Gap #5 — Per-application webhook view for OPS_USER (closed 2026-05-25)

**Endpoint:** `GET /api/v1/internal/ops/loan-applications/{applicationId}/webhook-events`
- Auth: `SYSTEM_ADMIN` | `OPS_USER` (controller-level `@PreAuthorize`); LSP roles → 403.
- 404 (`ResourceNotFoundException`) when the application id does not exist.
- Returns up to 200 rows newest-first, capped by `WebhookEventOutboxRepository.findTop200ByLoanApplicationIdOrderByCreatedAtDesc`.

**Schema change (V58__webhook_outbox_loan_application_id.sql):**
- New nullable `webhook_event_outbox.loan_application_id UUID`.
- Backfill: `LOAN_APPLICATION` rows cast `aggregate_id` directly; `LOAN_ACCOUNT` rows join via `loan_account.id`; `LOAN_PAYMENT_TRANSACTION` rows join `loan_payment_transaction → loan_account.loan_application_id`.
- Composite index `(loan_application_id, created_at DESC)` for the per-loan read.

**Service plumbing:**
- `WebhookOutboxService.enqueueIfSubscribed(...)` gains a `UUID loanApplicationId` parameter; all 7 enqueue sites updated to pass `application.getId()` (lifecycle: create/transition/invalidate; ops: disbursement-request + mock-outcome + manual transition; repayment + foreclosure command services).
- `WebhookOutboxService.listOutboxForLoanApplication(UUID)` is the read entry point.
- `LoanApplicationService.listWebhookEventsForApplication(UUID)` validates existence (no 400 leak from `getApplication`'s `IllegalArgumentException`; uses the dedicated `ResourceNotFoundException` → 404 instead) and projects each outbox row to `LoanApplicationWebhookEventProjection` by joining the latest `WebhookEventDeliveryAttempt` per event via `findFirstByOutboxEvent_IdOrderByCreatedAtDesc`.

**Response shape:** `WebhookEventDeliveryResponse { eventId, eventType, targetUrl, status, attempts, lastAttemptAt, lastResponseCode, lastError, createdAt }`. Status enum mapping (`WebhookEventOutboxStatus` → external):
- `PENDING → PENDING`
- `DELIVERED → DELIVERED`
- `RETRYABLE_FAILURE → FAILED`
- `PERMANENT_FAILURE → DEAD_LETTERED`

**Frontend:** `fetchLoanApplicationWebhooks` in `frontend-2/src/features/loan-applications/api-detail.ts` drops the admin-outbox client-side filter + OPS_USER "empty list" hack and reads the new per-loan endpoint for any internal session. The `WebhooksTab` already handled the four-state pill; no UI changes required.

**Tests (3 new in `LoanApplicationOpsControllerTest`):**
- `perLoanWebhookEventsEndpointReturnsScopedRowsWithStatusMapping` — drives PENDING / DELIVERED / FAILED / DEAD_LETTERED outcomes by mutating the persisted outbox rows; asserts targetUrl, attempts, lastResponseCode, lastError, ordering, scoping (other-application rows excluded), and SYSTEM_ADMIN parity.
- `perLoanWebhookEventsEndpointReturns404ForUnknownLoanApplication` — random UUID → 404.
- `perLoanWebhookEventsEndpointIsForbiddenForLspRoles` — LSP_API_CLIENT → 403.

Plus the existing 6 `WebhookOutboxAdminControllerTest` tests re-validated end-to-end with the new enqueue signature.

**Edge cases covered:**
- LSP_API_CLIENT and other non-internal roles get 403 (controller `@PreAuthorize`).
- Unknown application id returns 404 (not 400) — explicit `ResourceNotFoundException` rather than the `getApplication` `IllegalArgumentException` path used by other endpoints, preserving backward compat for those callers.
- `PENDING` rows expose `attempts=0`, `lastAttemptAt=null`, `lastResponseCode=null`, `lastError=null`.
- Aggregate-id-by-aggregate-type ambiguity solved by the new dedicated `loan_application_id` column; cross-aggregate rows (LOAN_ACCOUNT, LOAN_PAYMENT_TRANSACTION) still appear in the per-loan view via the backfill join.
- Cross-tenant scoping: a row written for a different application is excluded by the indexed lookup.

**Files changed:**
- BE domain: `WebhookEventOutbox.java` (+ `loanApplicationId` column + getter, constructor signature).
- BE repo: `WebhookEventOutboxRepository.java` (+ `findTop200ByLoanApplicationIdOrderByCreatedAtDesc`), `WebhookEventDeliveryAttemptRepository.java` (+ `findFirstByOutboxEvent_IdOrderByCreatedAtDesc`).
- BE service: `WebhookOutboxService.java` (+ `loanApplicationId` parameter + `listOutboxForLoanApplication`), `LoanApplicationLifecycleService.java`, `LoanApplicationService.java`, `LoanRepaymentCommandService.java`, `LoanForeclosureCommandService.java`, new `LoanApplicationWebhookEventProjection.java`.
- BE web: `LoanApplicationOpsController.java` (+ `GET .../webhook-events` + `WebhookEventDeliveryResponse` record).
- BE migration: `src/main/resources/db/migration/V58__webhook_outbox_loan_application_id.sql` (new).
- BE tests: `LoanApplicationOpsControllerTest.java` (+ 3 tests + AssertJ import + webhook repo wiring + LSP-role helper), `WebhookEventOutboxRepositoryPostgresTest.java` (constructor update).
- FE: `frontend-2/src/features/loan-applications/api-detail.ts` (`fetchLoanApplicationWebhooks` rewired to new endpoint + new `BackendWebhookEventDeliveryRow` row mapper).

**Verification:** 144 BE tests green, 8 skipped, 0 failures (`mvn -f backend/pom.xml test`). FE `tsc -b` clean; 123/123 loan-applications vitest tests pass.

**Follow-ups (not blocking close):**
- Payload preview / full payload viewer is deferred per § Group A (PII exposure risk; needs masking + audit before exposure).
- Per-attempt drill-down is deferred (today only the latest attempt is surfaced).

#### Gap #19 — Drop assignment entirely (closed 2026-05-25)

**Removed:** `POST /api/v1/internal/ops/loan-applications/{id}/assignment`, `GET .../assignment-events`, all `assignApplication` / `releaseAssignment` writes, assignment fields on ops + LSP API responses, FE assignee filter/column/detail section.

**Retained:** `loan_application.assigned_*` columns + `loan_application_assignment_event` table (forensic); `V53__deprecate_loan_application_assignment.sql` documents deprecation.

**Tests:** `LoanApplicationOpsControllerTest.loanApplicationAssignmentEndpointsAreRemoved`, `loanApplicationResponsesOmitAssignmentFields`; workflow activity test no longer exercises assignment.

#### Follow-up #3 — Document upload auto-check spec (closed 2026-05-25)

**Policy (enforced on every `POST .../documents` via `ConfigurableLoanDocumentStorageService.store`):**

| Document type | Max size | Allowed MIME |
| --- | --- | --- |
| `LOAN_AGREEMENT` | 10 MB | `application/pdf` only |
| `PAN_CARD`, `AADHAAR_FILE` | 5 MB | `application/pdf`, `image/jpeg` |
| All other types | 10 MB | `application/pdf`, `image/jpeg`, `image/png` |

**Tests:** `DocumentUploadPolicyTest` (unit, 7); `LspLoanApplicationApiControllerTest.documentUploadEnforcesPerDocumentTypeConstraints` (HTTP integration).

**Files:** `DocumentUploadPolicy.java` (new), `DocumentUploadPolicyTest.java` (new), `ConfigurableLoanDocumentStorageService.java`, `LspLoanApplicationApiControllerTest.java`.

**Verification:** 135 BE tests, 8 skipped, 0 failures.

### Previously closed

#### Gap #11 + Follow-up #1 — Status enum, state machine, auto-approval rule engine (closed 2026-05-25)

**Implementation summary:**
- BE enum: `LoanApplicationStatus` collapsed to the canonical 10 values per the design (`INITIALIZED`, `AWAITING_APPROVAL`, `APPROVED_PENDING_DISBURSAL`, `REJECTED`, `DISBURSEMENT_RETRY`, `INVALID`, `DISBURSED`, `UNDER_REPAYMENT`, `CLOSED`, `FORECLOSED`). `PAYMENT_REINITIATION` renamed to `DISBURSEMENT_RETRY`. `FORECLOSED` reserved for Phase 8 (no live transitions today). `canTransitionTo()` is the authoritative state-machine table with terminal states blocked; helpers `isTerminal()` and `isPreDisbursal()` exposed for callers.
- BE migrations: `V51__loan_application_status_state_machine.sql` rewrites historical PAYMENT_REINITIATION rows on `loan_application`, `loan_application_status_transition`, and `loan_application_audit_event`. `V52__loan_application_rejection_reason.sql` adds the `rejection_reason_json TEXT` column on `loan_application_status_transition`.
- BE rule engine: new `LoanAutoApprovalRuleEngine` (`@Service`) evaluates 8 rule families in one read-only call — `PRODUCT_INACTIVE`, `LSP_INACTIVE`, `LSP_PRODUCT_MAPPING_INACTIVE`, `LOAN_AMOUNT_OUT_OF_RANGE`, `LOAN_TENURE_OUT_OF_RANGE`, `BORROWER_REQUIRED_FIELDS_MISSING`, `REQUIRED_DOCUMENTS_NOT_UPLOADED`, `BORROWER_HAS_OPEN_LOAN`. Returns `Evaluation { approved, failedRules[] }`. One-open-loan rule filters out the application's own (post-approval) loan account so re-evaluation after APPROVED doesn't false-positive against self.
- BE auto-transitions: `LoanApplicationLifecycleService.autoApproveIfEligibleForLsp` now consults the rule engine. Decision tree: (a) status outside `{INITIALIZED, AWAITING_APPROVAL}` → no-op (backward-compat with disbursement callers); (b) engine approves → INITIALIZED→AWAITING_APPROVAL→APPROVED_PENDING_DISBURSAL + create loan account; (c) engine fails AND status==AWAITING_APPROVAL → REJECTED with `rejection_reason_json` JSON (`{"failedRules":[...]}`) on the transition row + reasonCode `FAILED_VERIFICATION`; (d) engine fails AND status==INITIALIZED → stay put.
- BE API surface: `LoanApplicationStatusTransitionResponse` gains a `rejectionReason: { failedRules: string[] }` block; `LoanApplicationOpsResponses.toTransitionResponse` parses the JSON column safely (null on parse failure).
- FE status enum: `LoanStatus` zod enum extended with the 10 canonical BE values (legacy frontend-only values retained inside the union as a compat shim — they fold through `mapBackendStatus` / `mapFrontendStatusToBackend`). `STATUS_PASS_THROUGH` set in `frontend-2/src/features/loan-applications/api.ts` covers all 10 canonical statuses. `OPEN_STATUSES` / `CLOSED_STATUSES` in `frontend-2/src/features/borrowers/api.ts` migrated from `PAYMENT_REINITIATION` to `DISBURSEMENT_RETRY` and added `FORECLOSED` to closed.
- FE status meta + badge tone: `lib/lifecycle.ts` `STATUS_META` gains entries for `INITIALIZED`, `DISBURSEMENT_RETRY`, `INVALID`. New `getStatusBadgeTone(status, delinquency)` helper returns `success | danger | neutral`; UNDER_REPAYMENT tone derives from `delinquency.maxDaysPastDue` / `overdueInstallmentCount` (per design — enum stays a single value; visual derives from per-loan delinquency, already shipped via Gap #6).
- FE Blocking Issues panel: new `BlockingIssuesPanel` component in `frontend-2/src/features/loan-applications/components/detail-tabs/`. Surfaces per-status diagnostic blocks: INITIALIZED → docs pending hint; APPROVED_PENDING_DISBURSAL → queued-for-payout banner; DISBURSEMENT_RETRY → retry warning; DISBURSED → schedule start; UNDER_REPAYMENT → delinquency aggregate from `borrowerDetail.totals.activeOverdueAmount`; REJECTED → failed-rules pointer to Activity tab; INVALID/INVALIDATED → LSP-supplied invalidation reason + invalidatedAt. Hooked into OverviewTab as the top-level card.

**Tests added/updated:**
- BE: existing 127-test suite re-run end to end; `LoanApplicationOpsControllerTest` updated for the `PAYMENT_REINITIATION` → `DISBURSEMENT_RETRY` rename across 4 assertion sites; test method renamed from `paymentReinitiationAndRejectTransitionsRequireReasonCode` → `disbursementRetryAndRejectTransitionsRequireReasonCode`.
- FE: existing 1020-test suite re-run end to end; `tsc -b` clean; no new test files added — the new `BlockingIssuesPanel` is exercised through `OverviewTab` integration.

**Edge cases covered:**
- `canTransitionTo` rejects every same-status no-op and every terminal-to-anywhere attempt.
- `autoApproveIfEligibleForLsp` short-circuits on APPROVED_PENDING_DISBURSAL / DISBURSED / etc., so disbursement-side callers continue to work unchanged.
- One-open-loan rule excludes the current application's loan account from the "other open loans" check.
- Rule engine returns `REQUIRED_DOCUMENTS_NOT_UPLOADED` when the checklist is empty (treated as not-complete) — prevents an empty checklist from being interpreted as "all complete".
- V51 migration is idempotent and runs only against legacy rows; new rows already carry the renamed value.
- `parseRejectionReason` returns `null` on parse failure so a corrupt JSON payload never explodes the API response.

**Files changed (delta only):**
- BE domain: `LoanApplicationStatus.java`, `LoanApplicationStatusTransition.java`.
- BE service: `LoanAutoApprovalRuleEngine.java` (new), `LoanApplicationLifecycleService.java`, `LoanApplicationService.java`.
- BE web: `LoanApplicationOpsController.java`, `LoanApplicationOpsResponses.java`.
- BE migrations: `V51__loan_application_status_state_machine.sql` (new), `V52__loan_application_rejection_reason.sql` (new).
- BE tests: `LoanApplicationOpsControllerTest.java` (PAYMENT_REINITIATION → DISBURSEMENT_RETRY).
- FE schemas: `schemas/loan-application.ts` (LoanStatus union).
- FE features: `features/loan-applications/api.ts` (status fold map), `features/borrowers/api.ts` (open/closed status sets).
- FE lib: `lib/lifecycle.ts` (STATUS_META extension + getStatusBadgeTone helper).
- FE detail tabs: `features/loan-applications/components/detail-tabs/BlockingIssuesPanel.tsx` (new), `OverviewTab.tsx` (panel wired in as top card).

**Verification:**
- BE: 127 tests green, 8 skipped, 0 failures (`mvn -f backend/pom.xml test`).
- FE: `tsc -b` clean; 1020/1032 vitest tests pass — the 12 failures are the pre-existing baseline in `alerts/page.test.tsx`, `auth/LoginPage.test.tsx`, `reports/page.test.tsx` (NOT caused by this work, re-confirmed by the matching failure count).

**Follow-ups:**
- The FE `LoanStatus` zod enum still carries the legacy frontend-only values (INITIATED, UNDER_REVIEW, KYC_PENDING, DOCS_PENDING, DISBURSEMENT_IN_PROGRESS, PARTIALLY_PAID, DELINQUENT, FORECLOSURE_REQUESTED, FORECLOSURE_APPROVED, FULLY_REPAID, CANCELLED, INVALIDATED, APPROVED) for mock + legacy-detail-page backwards compatibility. The eventual cleanup is to migrate every consumer to the canonical 10 and trim the union (mechanical work across ~300 references). Tracked here as the residual scope of Gap #11.
- A dedicated regression test asserting the auto-reject + structured `rejectionReason` JSON contract is worth adding (e.g., test that disables the LSP-product mapping after intake then triggers a doc-upload to provoke the auto-reject path); deferred to keep the diff focused on shipping the model.
- "Blocking Issues" panel currently exposes the failed-rule list as a pointer to the Activity tab; a follow-up should hydrate it inline by reading `rejectionReason.failedRules` from the latest REJECTED transition (the FE only needs to call the existing status-transitions endpoint, no BE work required).

#### Gap #18 — Delete verify/reject doc model (closed 2026-05-25)

**Implementation summary:**
- BE: `LoanApplicationDocumentChecklistStatus` collapsed from 5 values to 3 (`PENDING | SUBMITTED | NOT_REQUIRED`). RECEIVED + VERIFIED → SUBMITTED via `V50__document_checklist_status_collapse.sql`; REJECTED rows with an attached file → SUBMITTED, otherwise → PENDING. `review_reason` + `rejection_reason` columns dropped.
- BE: `PUT /api/v1/internal/ops/loan-applications/{id}/kyc-documents/{documentType}` and its service-layer plumbing are deleted; the route returns 404 (asserted by `opsUserCanInspectChecklistAndPutVerifyRejectEndpointIsRemoved`).
- BE: Every lifecycle gate that previously required VERIFIED now requires SUBMITTED. Specifically: `LoanApplicationLifecycleService.hasAllRequiredLmsManagedDocuments`, `LoanApplicationLifecycleService.validateRequiredDocumentsUploadedBeforeDisbursement`, `LoanApplicationLifecycleService.validateKycCompletionBeforeApproval`, and the matching `LoanApplicationService` helpers.
- BE: Upload auto-checks enforced on every `POST .../documents` (single + batch) inside `ConfigurableLoanDocumentStorageService.store`: file size ≤ 10 MB, MIME ∈ `{application/pdf, image/jpeg, image/png}`, non-empty body. Failures throw `BusinessRuleViolationException` with codes `DOCUMENT_FILE_TOO_LARGE` / `DOCUMENT_MIME_NOT_ALLOWED` / `DOCUMENT_FILE_EMPTY`, mapped to HTTP 422.
- BE: `LocalDemoPortfolioSeedService` switched VERIFIED/RECEIVED → SUBMITTED so the demo seed exercises the new contract. `GlobalExceptionHandler` KYC error message updated from "must be VERIFIED before approval" to "must be uploaded before approval".
- FE: `DocumentStatus` and `LoanDocumentStatus` zod enums trimmed to `PENDING | UPLOADED`. `DocumentRejectDialog` + its `schema.ts` deleted. `DocumentChecklistRow` no longer exposes Verify/Reject buttons or `onVerify`/`onReject` props. `DocumentChecklistGroup` matches. `DocumentStatusPill` enum exhaustiveness now covers PENDING + UPLOADED only.
- FE: `api-tabs.ts` `safeDocumentStatus` folds backend SUBMITTED and legacy VERIFIED/REJECTED rows to UPLOADED so existing data keeps rendering. `lib/lifecycle.ts` BR-2 / BR-3 gate checks switched from VERIFIED to UPLOADED. `mocks/api/loan-applications.ts` `computeDocsComplete` now requires UPLOADED.

**Tests added/updated:**
- BE: new `LspLoanApplicationApiControllerTest.documentUploadRejectsOversizedFilesAndDisallowedMimeTypesAndReplacesOnReupload` (oversize 422, bad MIME 422, happy path UPLOADED, re-upload replaces file). Pre-existing OPS tests rewritten as contract assertions (PUT removed, review/rejection fields absent from response).
- FE: `document.test.ts` rewritten to enforce `PENDING | UPLOADED` only and to assert the retired verify/reject fields are stripped by the schema. `DocumentChecklistRow.test.tsx` and `DocumentChecklistGroup.test.tsx` rewritten to a view-only contract; `DocumentStatusPill.test.tsx` trimmed to 2 statuses.

**Edge cases covered:**
- Cross-tenant LSP upload still rejected (existing `getApplicationForLsp` check).
- Re-upload of the same document type replaces prior file (asserted).
- Empty multipart body → 422 (`DOCUMENT_FILE_EMPTY`).
- Backend rows that pre-date the migration with VERIFIED/RECEIVED/REJECTED status are migrated forward without losing attached files.

**Files changed (delta only):**
- BE: `LoanApplicationDocumentChecklistStatus.java`, `LoanApplicationDocumentChecklist.java`, `LoanApplicationService.java`, `LoanApplicationLifecycleService.java`, `LoanDocumentService.java`, `LocalDemoPortfolioSeedService.java`, `LoanApplicationOpsController.java`, `LoanApplicationOpsResponses.java`, `ConfigurableLoanDocumentStorageService.java`, `common/web/GlobalExceptionHandler.java`, `src/main/resources/db/migration/V50__document_checklist_status_collapse.sql`.
- BE tests: `LoanApplicationOpsControllerTest.java`, `WebhookOutboxAdminControllerTest.java`, `HomeDashboardControllerTest.java`, `LspLoanApplicationApiControllerTest.java`, `ReportAdminControllerTest.java`.
- FE: `schemas/document.ts`, `schemas/document.test.ts`, `schemas/loan-application.ts`, `components/app/documents/DocumentStatusPill.tsx`, `components/app/documents/DocumentStatusPill.test.tsx`, `components/app/documents/DocumentChecklistRow.tsx`, `components/app/documents/DocumentChecklistRow.test.tsx`, `components/app/documents/DocumentChecklistGroup.tsx`, `components/app/documents/DocumentChecklistGroup.test.tsx`, `components/app/documents/DocumentUploadRow.test.tsx`, `components/app/documents/index.ts`, `features/loan-applications/components/detail-tabs/DocumentsTab.tsx`, `features/loan-applications/api-tabs.ts`, `lib/lifecycle.ts`, `mocks/api/loan-applications.ts`, `mocks/api/loan-applications.test.ts`, `mocks/api/borrowers.test.ts`, `features/home/page.test.tsx` (dead fixture).
- FE deleted: `components/app/documents/DocumentRejectDialog.tsx`, `components/app/documents/DocumentRejectDialog.test.tsx`, `components/app/documents/schema.ts`.

**Verification:**
- BE: 127 tests green, 8 skipped, 0 failures (`mvn -f backend/pom.xml test`).
- FE: `tsc -b` clean; 1020/1032 vitest tests pass — the 12 failures are the pre-existing baseline in `alerts/page.test.tsx`, `auth/LoginPage.test.tsx`, `reports/page.test.tsx` (NOT caused by this work, re-confirmed).

**Follow-ups:**
- Per-document-type auto-check tightening (e.g. LOAN_AGREEMENT PDF-only) is still pending under Follow-up #3.
- The `frontend-2/src/features/loan-applications/api-tabs.ts` `safeDocumentStatus` fold accepts legacy VERIFIED/REJECTED input for backward compatibility with rows that pre-date the migration; this can be tightened to reject those values once we're confident no historical rows remain (post-deploy compaction).
