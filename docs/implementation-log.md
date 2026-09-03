# Implementation log — production-readiness remediation

Records fixes landed after the [2026-07-12 production readiness assessment](../outputs/production-readiness-report-2026-07-12/PRODUCTION-READINESS-REPORT-2026-07-12.md). Each entry maps to a spec in that report (§19.3–§19.5).

**Also mirrored in:** `outputs/production-readiness-report-2026-07-12/PRODUCTION-READINESS-REPORT-2026-07-12.md` §19.6 (append-only).

**Deferred items:** [deferred-implementation.md](deferred-implementation.md).

---

## S3 audit fix — worker path called provider inside the initiating transaction (2026-07-15)

**Status:** Closed — found and fixed during the 2026-07-15 end-to-end implementation audit.

**Problem.** `LoanDisbursementWorkerProcessor.processApplication` (`@Transactional`) executed the
intent inline via `DisbursementIntentWorkflowService.executeForApplication`. The injected
`TransactionTemplate` uses default `REQUIRED` propagation, so Tx-A (intent insert), the claim,
the **provider call**, and Tx-B all joined the worker's single transaction. A crash after provider
acceptance would roll back the intent; the retry would mint a new intent and a new `tranRefNo` —
the exact MNY-01 duplicate-payout window S3 was written to close, reintroduced on the ~30s
auto-disburse path. The S3 integration test only covered the admin HTTP path.

**Fix.**
- Processor now runs only validation + Tx-A inside its transaction; the legacy inline auto-resolve
  stays gated to the non-intent path.
- `LoanDisbursementWorkerService.processApplication` executes the committed intent **after** the
  processor transaction returns (no transaction held across `requestDisbursement`), then
  auto-resolves when `auto-resolve-mock-outcome` is on.
- `processClaimableIntents` auto-resolve is now gated on the same flag (was unconditional).
- `executeClaimableIntents` wraps the `claimBatch` lease stamp in an explicit short transaction
  (required by the JPA pessimistic-lock fallback; guarantees the lease commits before the call).

**Regression guard.** `DisbursementIntentWorkflowIntegrationTest.workerPathCallsProviderOutsideTransactionWithCommittedIntent`
asserts, inside an adapter spy, that no transaction is active at provider-call time and that the
intent row is already committed. Red before the fix, green after.

**Also cleaned (S12).** `DisbursementReferenceService` dead fallback branch removed (queried the
same live states `findLiveByLoanAccountId` already covers); unused repository method deleted.

**Validation.** Full backend suite: 769 tests, 0 failures (2026-07-15).

---

## S1 audit fix — frontend release gates restored (2026-07-15 evening)

**Status:** Closed — gates had regressed after the S1 close-out; repaired during the audit.

- `tsconfig.app.json`: removed invalid `"ignoreDeprecations": "6.0"` (TS 5.9 accepts only "5.0"; no deprecated flags in use) — was failing `npm run build` with TS5103.
- `e2e/helpers/e2e-fixtures.ts`: `prefer-const` lint error.
- `TransitionConfirmDialog.tsx`: preview effect called setState synchronously (lint error); replaced with the reset-during-render pattern already used for `prevActionId`; effect now only starts the async fetch.
- Prettier: 9 dirty files (S7/S9/S11/S12 additions) formatted.

**Fresh-eyes audit correction (2026-07-15).** The later 124-file / 761-test run passed its
assertions but emitted React `act(...)` warnings, so the zero-warning S1 acceptance criterion was
not yet met. The affected async accessibility/upload tests now await their final state, the API
client selector remains controlled for its entire lifetime, and its form tests drive Radix's
native form bridge instead of relying on JSDOM portal timing.

**Dependency security (same audit).** `npm audit --omit=dev` found the React Router 6.30.3 open
redirect advisory and transitive advisories pulled into the production dependency graph by the
`shadcn` build CLI. React Router is patched to 6.30.4; `shadcn` is correctly classified as a dev
dependency; Vite/Vitest and their matching plugins were upgraded together; compatible transitive
fixes were applied. The frontend `prepare` script now initializes the nested Husky directory from
the repository root instead of swallowing its permanent “.git can't be found” failure. Full and
production-only `npm audit` now report zero vulnerabilities.

**Validation.** Typecheck, lint, format:check, check:encoding, build, and the affected warning
regression shard are green on the secure toolchain. The full 124-file / 761-test results are
recorded in the fresh-eyes audit report.

---

## S14 / CTRL-01 — STP caps + maker-checker — deferred (2026-07-15)

**Status:** Deferred — not implemented in this pass.

**Owner rationale:** Current pass prioritises Spec S19 (and deferred retention) over maker-checker. Maker-checker remains a real-money gate.

**Defect still true in code:** single SYSTEM_ADMIN can initiate disbursement with no second principal, amount limit, or budget/velocity control.

**Resume / gate:** before real rails / S17. Spec: production report §19.5 S14. Register: [deferred-implementation.md](deferred-implementation.md#s14--ctrl-01--disbursement-authorization-stp-caps--maker-checker).

---

## S16 / F-API-02 — Per-LSP rate plans + bulk intake — deferred (2026-07-15)

**Status:** Deferred — not implemented in this pass.

**Owner rationale:** Not a current priority. Existing limiter already applies a single global write tier per `lspId`; differentiated plans and bulk create can wait until multi-partner SLAs / high-volume intake matter.

**Defect still true in code:** static 60/min write tier; LSP GETs mostly unmetered; no bulk create; no per-LSP plan columns/admin UI.

**Resume / gate:** before high-volume partner onboarding or differentiated SLAs. Spec: production report §19.5 S16. Register: [deferred-implementation.md](deferred-implementation.md#s16--f-api-02--per-lsp-rate-plans-and-idempotent-bulk-intake).

---

## S19 / D8 — Borrower↔LSP relationship (Slice A) (2026-07-15)

**Status:** Closed for Slice A — relationship table + dual-write/read; legacy `borrower_lsp_access` retained.

**Problem.** Visibility was only an ElementCollection of LSP ids (`borrower_lsp_access`) with no room for relationship metadata (sourced-at, channel, consent placeholders).

**Solution.** `borrower_lsp_relationship` (V113) backfilled from access rows; onboarding and synthetic seed dual-write; admin borrower detail exposes relationship timestamps; RLS on the new table. Element collection + borrower RLS via access table unchanged until a later dual-read cutover.

**Not in this slice.** Drop of `visibleLspIds` / `borrower_lsp_access`; `BorrowerFieldNormalizer` + DB CHECKs; generic profile-update audit; money isolation (still S5).

**Residual close-out (same day):** Public `Borrower.grantVisibilityTo` removed; legacy collection mutation is package-private via `BorrowerLegacyAccessWriter`. All grants go through `BorrowerLspRelationshipService.grantVisibility` (onboarding, seed, tests).

**Files.** `V113__borrower_lsp_relationship.sql`; `BorrowerLspRelationship` + repo + `BorrowerLspRelationshipService` + `BorrowerLegacyAccessWriter`; onboarding/directory/admin/seed wiring; FE Profile tab relationship strip; unit + tenant isolation coverage.

**Validation.** `BorrowerLspRelationshipServiceTest` (dual-write, re-grant, parity, no public grant API); `BorrowerAdminControllerTest` (list/detail relationship fields); `TenantIsolationPostgresIntegrationTest.samePanAcrossTwoLsps…` (tenant-scoped relationship counts).

**Canonical residual register:** [deferred-implementation.md — S19 residual](deferred-implementation.md#s19-residual--drop-access-collection--normalizercheck-after-slice-a).

---

## S18 / DATA-02 — Retention lifecycle and partitioning — deferred (2026-07-15)

**Status:** Deferred — not implemented in this pass.

**Owner rationale:** Not a current priority at synthetic UAT volume; growth/partition/archive work can wait for pilot scale or compliance pressure.

**Defect still true in code:** only idempotency 90d purge; no classed retention worker, legal-hold, purge manifests, or partitioned audit/auth/webhook streams.

**Resume / gate:** before partner-pilot scale / retention audits; partition attach before real-money capacity planning. Spec: production report §19.5 S18. Register: [deferred-implementation.md](deferred-implementation.md#s18--data-02--retention-lifecycle-and-partitioning).

---

## S3 / MNY-01 / F-MNY-01 — Durable disbursement intent (2026-07-13)

See earlier entry in git history / report §19.6. Feature flag `app.disbursement.intent-workflow.enabled`.

**Fresh-eyes audit correction (2026-07-15).** `UNKNOWN` intents are no longer claimable for another
payment request. Before the provider call, the implementation persists a deterministic request log
and advances the intent to `REQUESTED`; ambiguous/crash outcomes retain that reference and are
reconciled through provider status only. Provider calls occur with no database transaction held.
Added `lms.disbursement.intent.unknown.count` and
`lms.disbursement.intent.unknown.oldest_age_seconds` gauges. Crash-after-acceptance and unknown-outcome
tests prove the adapter is invoked once.

---

## S5 / DATA-01 — Approval-time beneficiary snapshot — deferred (2026-07-15)

**Status:** Deferred — not implemented in this pass.

**Owner rationale:** Practical likelihood judged low under PAN-based borrower deduplication and a single active loan per customer.

**Defect still true in code:** disbursement initiate / intent-create read live `borrower` bank account and IFSC; `loan_account` has no approval-time snapshot.

**Related (S12, 2026-07-15):** Ops disbursement preview labels `beneficiarySource=LIVE_BORROWER` so operators see that values are current profile data, not a freeze. Freeze-at-approval remains S5.

**Resume / gate:** before real-money rails. Spec: production report §19.3 S5. Register: [deferred-implementation.md](deferred-implementation.md#s5--data-01--approval-time-beneficiary-snapshot).

---

## S6 / MOCK-01 — Mutually exclusive mock/live disbursement modes — deferred (2026-07-15)

**Status:** Deferred — not implemented in this pass.

**Owner rationale:** Current pass remains management-review / synthetic UAT with intentional mock rails; exclusive provider selection deferred until closer to live adapter work (S17).

**Defect still true in code:** `MockLoanDisbursementAdapter` is an unconditional `@Service`; `POST …/disbursement-requests/mock-outcome` is always registered; no `app.disbursement.provider` gate or prod+mock startup refusal.

**Resume / gate:** before non-mock / real-money deployment; no later than S17. Spec: production report §19.3 S6. Register: [deferred-implementation.md](deferred-implementation.md#s6--mock-01--mutually-exclusive-mocklive-disbursement-modes).

---

## S13 / MNY-02 — Receipt / allocation / suspense / reversal ledger — deferred (2026-07-15)

**Status:** Deferred — not implemented in this pass.

**Owner rationale:** Current pass continues with exact full-EMI posting only (synthetic UAT / management review); full receipt ledger postponed.

**Defect still true in code:** repayments must match one installment’s outstanding exactly (`PAYMENT_AMOUNT_MISMATCH` otherwise); `PARTIALLY_PAID` unreachable; no receipt/allocation/suspense/reversal model; LMS is not yet collections system of record at runtime (despite D1 intent).

**Resume / gate:** before real receipt ingestion / SoR collections use. Spec: production report §19.5 S13. Register: [deferred-implementation.md](deferred-implementation.md#s13--mny-02--receipt--allocation--suspense--reversal-ledger).

---

## S20 / NEW-05 / SCH-01 — Partner schedule date and interest validation (2026-07-15)

**Status:** Closed — implemented; residuals closed same day.

**Problem.** Partner-provided schedules only required strictly increasing due dates and balanced principal arithmetic. Past/far-future first dues, century-spanning cadence, and arbitrary interest (including zero on a positive-rate product) still validated.

**Solution.** Extended `LoanRepaymentScheduleService.validateProvidedInstallments` (shared by LSP submit and pre-disbursement revalidation) with date discipline (first-due window, anchored monthly cadence, horizon) and interest discipline (row + total vs frozen product rate / generator). Bounds live under `app.schedule.validation.*`.

**Residuals closed (2026-07-15):**
1. **Partner 422 tightening** — documented as intentional contract change in `docs/partner-schedule-validation.md` (violation codes + accepted bounds); BR-11 and `CONTEXT.md` updated.
2. **Config defaults** — locked as product-accepted values (no longer “engineering placeholders”).
3. **Kill-switch** — removed (`extended-checks-enabled` deleted); date/interest checks always run.

**Files.** `ScheduleValidationProperties.java`; `ScheduleViolationType` new codes; `LoanRepaymentScheduleService.java`; `application.yml` / `application-test.yml`; unit + LSP fixture updates; `docs/partner-schedule-validation.md`; `CONTEXT.md`; `docs/BRD-executive-brief.md` BR-11.

**Validation.** `LoanRepaymentScheduleServiceTest`; LSP schedule IT methods green.

---

## S15 / SEC-01(3) — PAN masking policy — deferred (2026-07-15)

**Status:** Deferred — not implemented in this pass.

**Owner rationale:** Current PAN display behaviour judged acceptable for the present pass; approved D3 matrix not scheduled now.

**Defect still true in code:** LSP/admin serializers largely return raw PAN; FE `borrowerPanMasked` still maps unmasked `panNumber`; detail-page PAN views are not page-audited. `PanMasking` exists but is not applied to the policy surfaces and uses last-4 format rather than approved first-2/last-3.

**Resume / gate:** before partner pilot / when masked PAN on LSP and lists is required. Spec: production report §19.5 S15. Register: [deferred-implementation.md](deferred-implementation.md#s15--sec-013--pan-masking-policy-partner-masked-admin-detail-full--audit).

---

## S2 / NEW-01 / TEST-BE-01 — Backend test suite database safety (2026-07-13)

See production report §19.6 / `backend/README.md` (Database safety).

---

## S1 — Frontend release baseline (2026-07-13)

See production report §19.3 / §19.6.

---

## S4 / IDEM-01 — Idempotency lease and crash recovery (2026-07-13)

See earlier entry / report §19.6.

**Fresh-eyes audit correction (2026-07-15).** Business mutation, response serialization, and
idempotency completion now share one `REQUIRES_NEW` transaction. Lease completion/deletion is fenced
by record id, attempt number, and lease owner so an expired worker cannot overwrite its successor.
Supported recovery reconstructs results from durable business state; unsupported legacy pending
records fail closed with `IDEMPOTENCY_RECOVERY_REQUIRED` rather than rerunning secret/payment work.
Added pending count and oldest-age gauges. Serialization rollback, stale-attempt fencing, and crash
recovery integration tests are green.

---

## S7–S12 group (2026-07-15)

Frontend-heavy pilot hardening from production report §19.3 Specs S7–S12. Canonical residual close-out same evening.

### S7 — Same-origin credential policy (SEC-03)

**Problem.** `http-client` accepted absolute `http(s)` paths and attached the bearer token and cookies.

**Solution.** Resolve against `VITE_API_BASE_URL`; refuse credential-bearing cross-origin requests; export `fetchExternal` (`credentials: "omit"`, no auth headers).

**Files.** `frontend/src/lib/api/http-client.ts`, `http-client.test.ts`.

**Validation.** Unit tests: attacker origin throws with zero `fetch` calls; same-origin absolute and relative paths pass.

---

### S11 — Access token out of localStorage (SEC-01 item 4)

**Problem.** Full session including `accessToken` was persisted in `localStorage`.

**Solution.** Persist only `{ user, expiresAt }`; hold token in module memory; sanitize legacy stored tokens on load; SessionProvider still bootstrap-refreshes via HttpOnly cookie.

**Files.** `frontend/src/lib/api/session-storage.ts`, `session-storage.test.ts`, `features/auth/session-types.ts`.

**Validation.** Tests assert storage never contains the token; legacy tokens are stripped.

---

### S8 — Loan-account status vocabulary (ODD-01)

**Problem.** Frontend Zod enum used fictitious `ACTIVE` and only four statuses; backend has eight with no `ACTIVE`.

**Solution.** `LOAN_ACCOUNT_STATUSES` aligned to `LoanAccountStatus.java`; exhaustive badge meta; detail mapping via `LoanAccountStatus.parse`.

**Files.** `frontend/src/schemas/loan-account.ts`, `statusBadgeMeta.ts`, related tests / sandbox.

---

### S12 — Disbursement money preview (UX-02)

**Problem.** Confirm dialog showed beneficiary but not principal/fee/net/payment mode.

**Solution.**
- Shared `DisbursementAmounts` + `DisbursementPaymentModeSelector` (same math as command path).
- `GET /api/v1/internal/ops/loan-applications/{id}/disbursement-preview` (SYSTEM_ADMIN).
- Dialog loads preview; confirm disabled until success.
- `GET …/disbursement-reference` returns live intent `tranRefNo` after Tx-A (closes async lag).
- `beneficiarySource=LIVE_BORROWER` labeled until S5.

**Files.** Backend preview/reference services + ops controller/types; FE `DisbursementPreviewSummary`, `TransitionConfirmDialog`, `api-detail.ts`.

**Validation.** `DisbursementPreviewIntegrationTest`; `DisbursementIntentWorkflowIntegrationTest` reference-before-provider case; dialog unit tests.

---

### S9 — Reproducible E2E harness (NEW-02 / E2E-*)

**Problem.** Specs targeted removed “System roles” UI; hardcoded passwords; Phase 8 threw without `E2E_APPLICATION_ID`.

**Solution.** Env-only login helpers; smoke asserts Email/Password form; Playwright `globalSetup`/`globalTeardown` API-seeds `E2E-*` fixtures; Phase 8 resolves env → fixture file → skip with reason; pinned `scripts/indep-e2e/requirements-e2e.txt`; operator guide `docs/e2e.md`.

---

### S10 — Home/Audit canaries + bootstrap-sync (OPS-01 / ENV-01 residuals)

**Problem.** Instant JDBC binds and bootstrap drift recurred historically with no guard; stray `APP_SECURITY_BOOTSTRAP_LOGIN_PASSWORD`.

**Solution.**
- `HomeAndAuditInstantBindPostgresTest` (Testcontainers) — **2/2 pass** with Docker.
- `e2e/canary.spec.ts` + `npm run e2e:canary` — **passed** against local stack.
- `POST /api/v1/internal/system/bootstrap-sync` (SYSTEM_ADMIN) re-runs bootstrap sync without restart.
- Single password env: `APP_SECURITY_BOOTSTRAP_PASSWORD` only.

---

### Residual close-out table (2026-07-15 evening)

| Residual | Closure |
|---|---|
| Phase 8 manual application id | `globalSetup` seeds fixtures |
| Instant bind unproven | Postgres Testcontainers tests green |
| Intent `tranRefNo` lag | `disbursement-reference` endpoint |
| Live bank in preview | Labeled `LIVE_BORROWER`; S5 still deferred |
| OpenAPI drift | Regenerated `openapi/openapi.json` + `schema.ts` |
| Canary not run | `e2e:canary` passed |

---

## H-01 — Atomic one-open-loan approval enforcement (2026-07-24)

**Problem.** The cross-LSP one-open-loan rule was evaluated before account creation without
serializing applications belonging to the same borrower. Two concurrent approvals could both see
no open account and each create a `PENDING_DISBURSEMENT` account.

**Solution.**

- Approval paths acquire a pessimistic write lock on the shared borrower row before evaluating the
  rule and hold it through application status and loan-account persistence.
- Loan-account provisioning reacquires the same borrower lock and rechecks the invariant at the
  write boundary, while preserving same-application retry idempotency.
- The existing business outcome remains unchanged: the winning application is approved and the
  later concurrent application is rejected by the rule engine with `BORROWER_HAS_OPEN_LOAN`.

**Files.** `LoanApplicationRepository`, `LoanApplicationLifecycleService`,
`LoanApplicationStatusWriter`.

**Validation.** Unit coverage verifies write-boundary enforcement and idempotent replay. The H2
integration test releases two same-borrower approvals simultaneously and asserts one approved
application, one rejected application, and one open account. A PostgreSQL Testcontainers version
passes against PostgreSQL 17 and covers the production database locking semantics.
