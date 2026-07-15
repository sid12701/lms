# Bhawana LMS — End-to-End Fintech Production-Readiness Audit

**Audit date:** 2026-07-10, revalidated 2026-07-11  
**Code state:** current working tree, including the large uncommitted July remediation set  
**Deployment context:** management review; disbursement is mocked; no approval for real-money production  
**Method:** read-only source review, prior-artifact reconciliation, local runtime walkthrough, and current type/test/static checks. No application source was changed. On 2026-07-11 both services were reachable. A pre-existing `ops.admin` browser session briefly restored Home and confirmed its current failure, but a subsequent protected-route reload invalidated that session. The user-supplied email was then confirmed absent from the running `app_user` table; the only active bootstrap row used a different email and did not accept the supplied or repository-configured local password. Protected-route revalidation therefore stopped to avoid lockout. The 2026-07-10 authenticated screenshots remain the evidence for the remaining protected routes, while the 2026-07-11 Home failure, authentication drift, source review, and quality-gate results are current.

## 1. Executive summary

The platform has a materially stronger foundation than the oldest audit reports suggest. Tenant isolation, API-client revocation, IP allowlists, strict partner payload validation, payment idempotency, installment locking/versioning, money types and database constraints, webhook outbox delivery, audit streams, pagination, document signature checks, product versioning, and several frontend correctness defects have been implemented well.

It is nevertheless **not ready for real-money production** and should remain in management review/internal UAT. The mocked disbursement context reduces immediate financial exposure, but it does not make the current disbursement design safe to connect to a bank. The main blockers are architectural rather than cosmetic:

1. A provider call occurs inside a database transaction before the durable request log commits. A crash after provider acceptance can produce a blind retry and double payment.
2. The repayment contract cannot represent real borrower receipts: only an exact, single-installment amount is accepted. There is no partial/lump-sum/advance allocation, bounce, reversal, suspense, or cash reconciliation model.
3. Sensitive borrower data remains plaintext and is duplicated into JSON/audit/alert payloads; full PAN is displayed by default; browser access tokens persist in `localStorage`.
4. The committed launch target (~1M originations/month, 150K/day peak, 10 LSPs) has not been revalidated after remediation. The last load report failed far below that target, and several launch controls remain absent.
5. The 2026-07-10 runtime verification found Home and Audit Log failing while Loan Applications and Reports worked. The 2026-07-11 recheck confirmed both services were reachable but could not revalidate protected pages because the local admin credentials had changed.
6. Real-money operational controls are incomplete: one SYSTEM_ADMIN can initiate disbursement; there is no maker-checker, approval limit, segregation of duties, or reconciliation sign-off. The confirmation dialog omits the disbursement amount and net/fee breakdown.
7. The current frontend tree does not pass its release gate: lint fails, and the production build has three TypeScript contract errors. Focused changed-area tests pass, so this is a broken integration baseline rather than a blanket frontend failure.
8. Local bootstrap identity configuration has drifted from the running database, so management UAT is not reproducibly accessible with the supplied or repository-configured credentials.

### Readiness verdict

| Deployment mode | Verdict | Conditions |
|---|---|---|
| Management review / synthetic UAT | **Conditional go** | Keep mock rails, synthetic/non-customer data where possible, disable outbound webhooks unless intentionally tested, disclose the last observed Home/Audit failures, and repair the frontend release gate before a formal demo build. |
| Limited partner pilot without real disbursement | **No-go until P0/P1 functional defects close** | Silent intake stalls, partner servicing/reconciliation gaps, PII decisions, and runtime errors must be resolved first. |
| Real-money production | **No-go** | Complete durable disbursement intent/reconciliation, maker-checker, real repayment/reversal accounting, PII protection, capacity gate, DR/ops controls, and independent security/financial-control testing. |

This is an engineering control assessment, not a legal certification. Regulatory applicability, retention, consent, and privacy obligations require counsel/compliance sign-off against the final operating model and data inventory.

## 2. Highest-risk issues

| Rank | ID | Severity | Type | Business impact |
|---:|---|---|---|---|
| 1 | MNY-01 | P0 | Architecture bug at real-rail go-live | Crash-after-provider-success can produce duplicate disbursement. |
| 2 | MNY-02 | P0 | Missing lending capability | Actual receipts cannot be represented when partial, over, bunched, prepaid, bounced, or reversed. |
| 3 | SEC-01 | P0 | Security/compliance risk | Plaintext/duplicated PII plus default PAN exposure and browser token theft blast radius. |
| 4 | SCALE-01 | P0 | Production-readiness gap | No evidence the remediated platform meets the committed 150K/day peak; partition/pool/bulkhead work remains. |
| 5 | CTRL-01 | P0 at real-rail go-live | Operational-control gap | A single admin can move money; UI does not show amount/net fee; no maker-checker or limits. |
| 6 | DATA-01 | ~~P1~~ **Deferred (2026-07-15)** | Data-integrity risk | Shared borrower bank details remain mutable after approval. Spec S5 deferred — see `docs/deferred-implementation.md`. |
| 7 | API-01 | P1 | Partner reliability bug | Create accepts data that later gates require, creating silently stuck INITIALIZED applications. |
| 8 | IDEM-01 | P1 | Reliability risk | Crash after business commit but before idempotency completion leaves a PENDING key with no recovery sweep. |
| 9 | CI-01 | P1 | Release-gate regression | The frontend cannot produce a clean release build; audit/LSP/user contract changes are not integrated. |
| 10 | OPS-01 | P1 | Runtime/operability defect | Home fails currently and Audit Log failed in the last durable authenticated walkthrough; operators get no correlation ID. |
| 11 | ENV-01 | P1 for UAT | Environment/configuration drift | The running bootstrap identity cannot be authenticated with supplied or repository-configured local credentials. |
| 12 | DATA-02 | P1 | Lifecycle/scale risk | Audit, token, webhook and other append-only streams lack a complete partition/retention implementation. |
| 13 | PARTNER-01 | P1 | Operational product gap | LSPs lack servicing detail, daily reconciliation/MIS, disbursement-attempt visibility, and security self-service. |
| 14 | OBS-01 | P1 | Incident-response gap | Metrics are selective; no end-to-end tracing, SLO dashboard, money-reconciliation alerts, or proven alert delivery. |

## 3. Detailed findings

### MNY-01 — Provider call precedes durable intent commit

**Evidence**

- `LoanDisbursementCommandService.initiateDisbursement` is transactional at `backend/src/main/java/com/bhawana/lms/service/LoanDisbursementCommandService.java:115`.
- The adapter call executes at line 163; the account and request log are persisted only afterward at lines 165–182.
- Status polling also calls the provider inside a transaction at lines 240–268.
- The transaction reference is random (`newTranRefNo`, lines 381–384) and there is no unique constraint on `tran_ref_no` or `provider_request_id` in migrations.
- The worker performs unbounded status scans (`LoanDisbursementWorkerService.java:59-66`, `:77-96`) rather than bounded `SKIP LOCKED` claims.

**Risk:** With the mock adapter this is harmless test behavior. With a real bank, a crash or database rollback after provider acceptance loses the fact that money moved. Retry can pay again; a slow provider also pins a DB connection.

**Required fix:** Commit a deterministic, unique disbursement intent first; call the provider outside the DB transaction; record outcome in a second transaction; reconcile unresolved intents via provider status (never blind resend). Add provider-reference uniqueness, bounded claims/leases, atomic attempts, and crash-window tests.

### MNY-02 — Repayment model is exact-EMI-only

**Evidence**

- `LoanServicingSupportService.validateExactInstallmentAmount` rejects any amount different from outstanding (`LoanServicingSupportService.java:196-211`).
- `applyFullInstallmentPayment` asserts full settlement (`:214-223`).
- The command locks one target installment and applies the full amount (`LoanRepaymentCommandService.java:228-255`).
- The domain already has `PARTIALLY_PAID` and a general recomputation waterfall (`LoanServicingSupportService.java:240-275`), but the public posting path cannot reach it.
- `LoanPaymentStatus` lacks `BOUNCED`/`REVERSED`; no reversal command exists.

**Risk:** The system cannot be the book of record for real collection behavior. Partners will either reject valid receipts, split transactions artificially, or maintain a shadow ledger.

**Required fix:** Define a receipt ledger independent of installment settlement; allocate oldest-due-first under locks; support partial, multi-installment, advance/credit and suspense; model reversal/bounce as immutable compensating entries; reconcile bank settlement references. Do not retrofit this as more conditionals around the exact-EMI method.

### CTRL-01 — Real-money authorization and confirmation controls are insufficient

**Evidence**

- Disbursement initiation is restricted to `SYSTEM_ADMIN`, but only one approval is required (`LoanApplicationOpsController.java:410-433`).
- The mock-outcome endpoint is also always registered (`:440-475`).
- `DisbursementInitiateDialog` displays beneficiary, bank, masked account and IFSC, but its target model has no amount (`frontend/src/components/app/disbursement/DisbursementInitiateDialog.tsx:25-31`, `:91-122`).
- Dialog copy says the request is “queued” (`:88`), while the backend calls the adapter synchronously.

**Risk:** A compromised or mistaken admin can move an uncapped amount without independent approval, and the confirmation omits the most important financial fields.

**Required fix:** Maker-checker with distinct principals, approval limits, reason codes, queue/intent ID, net amount + principal + fee + beneficiary snapshot, typed confirmation for exceptional amounts, and immutable maker/checker audit. Disable mock outcome routes outside explicit mock profiles.

### MOCK-01 — Mock behavior is wired as the default runtime adapter

`MockLoanDisbursementAdapter` is an unconditional `@Service` (`MockLoanDisbursementAdapter.java:19-20`); there is no production adapter or profile/property selector. Staging disables the worker, but the adapter and ops endpoints remain in the application.

This is acceptable only because management has not approved real rails. Before integration, introduce an explicit `mock` adapter mode that production validation rejects, and a production adapter that cannot coexist with mock outcome endpoints.

### DATA-01 — Approved beneficiary data is not frozen

**Status (2026-07-15):** Technically still accurate; **deferred** for the current implementation pass (owner rationale: PAN dedupe + single active loan). Canonical record: `docs/deferred-implementation.md` (Spec S5). Remains a real-money launch gate.

Disbursement reads the current global borrower bank account and IFSC (`LoanDisbursementCommandService` / intent-create). `loan_account` has no beneficiary snapshot. The borrower is global across LSP relationships, and bank details can be updated later.

**Risk:** A valid post-approval profile update can silently redirect money. Audit after the fact is not prevention.

**Fix (when resumed):** Snapshot beneficiary name/account/IFSC/bank plus verification evidence at approval; require explicit re-affirmation after any beneficiary change (production-report Spec S5).

### API-01 — Intake contract and progression gates disagree

The LSP request keeps address/reference fields optional and accepts monthly **or** annual income (`LspLoanApplicationApiController.java:572-600`). Progression requires address, city/state/ZIP, positive monthly income, and reference person fields (`BorrowerOnboardingRequirements.java:45-64`).

**Risk:** The API returns success for a loan that remains INITIALIZED without a machine-readable blocked reason.

**Fix:** Either make create validation match progression requirements or return a structured `requirements`/`blockedReasons` object and status designed for incomplete intake. Do not silently encode missing requirements as “no transition.”

### IDEM-01 — Claim-before-execute fixed duplicate races but crash recovery is incomplete

LSP/admin idempotency claims are committed in `REQUIRES_NEW`; the action runs separately; completion is another transaction (`LspApiIdempotencyService.java:55-85`). A duplicate polls for 30 seconds and then returns `IDEMPOTENCY_IN_PROGRESS` (`:114-143`). There is no stale-PENDING recovery worker.

**Risk:** A crash after the business transaction commits but before response completion permanently wedges the key until retention removes it. Releasing PENDING after arbitrary runtime errors is safe only if every action is atomic and side-effect boundaries are understood.

**Fix:** Store action/result state in the same durable workflow, or add leases + recovery that derives the completed response from the resource. Test kill-after-commit, external-storage success/DB failure, and multi-node retries.

### SEC-01 — PII and access-token protection are below fintech production bar

**Evidence**

- Borrower PAN, Aadhaar and bank details remain plaintext; encryption is only mentioned in migration comments.
- Full identifiers are copied into alert/audit JSON (`BorrowerOnboardingService.java:199-206`, `:228-242`); an ops alert summary embeds raw PAN (`OpsAlertEmitters.java:192-204`).
- LSP/ops responses return full PAN (`LspLoanApplicationResponses.java:39`, `:102`; `LoanApplicationOpsResponses.java:46`, `:94`). Current UI shows full PAN by default in the Overview screenshot.
- The frontend stores the whole session/access token in `localStorage` (`frontend/src/lib/api/session-storage.ts:21-36`, `:51-53`).

**Positive controls:** Aadhaar/bank masking exists on many outward surfaces; MIS export now masks bank/Aadhaar/PAN; partner bank reveal is audited; refresh cookie is HttpOnly/Secure/SameSite=Strict; API client versions/status and LSP status are checked on each JWT; allowlists and auth lockout are implemented.

**Required fix:** KMS-backed envelope encryption for Aadhaar/bank/webhook secrets; deterministic HMAC for searchable PAN; masked-by-default PAN with audited, purpose-bound reveal; redact JSON/audit at write time; CSP for the frontend; access token in memory (or BFF/session-cookie architecture) rather than localStorage; immutable/WORM audit export and access anomaly detection.

### SEC-02 — Document controls improved, malware scanning remains open

`DocumentUploadPolicy` validates filename, per-type size/MIME and PDF/JPEG/PNG signatures (`DocumentUploadPolicy.java:41-55`, `:84-99`). This closes the old “trust declared MIME” finding. It is not malware/CDR scanning and reads the entire file into memory (`:76-80`).

Add streaming upload, asynchronous AV/CDR/quarantine, content hash, safe preview conversion, and retention/deletion policy before accepting production customer documents.

### DATA-02 — Retention/partitioning is incomplete

Only LSP/admin idempotency records have a scheduled 90-day purge. `RefreshTokenRepository.deleteByExpiresAtBefore` has no caller. Audit streams, webhook attempts/outbox, auth events, reports and document access lack complete hot/archive/purge automation. No migration uses table partitioning, despite the committed 200–400M audit rows/year and 24-month hot/8-year archive decision.

Implement partitioning before high volume, per-table retention classes, legal hold, object-storage archive manifests/checksums, purge evidence, and restore tests.

### SCALE-01 — Capacity is unproven and the tracker is stale

The June performance report failed at ~1.5 RPS with 28.6% errors and 32s dashboard p95, but it predates KPI snapshots, batched MIS, auth caching and query guardrails. It therefore does **not** measure the current code. Conversely, no replacement run demonstrates the committed 150K-loans/day peak.

The execution tracker still marks landed controls OPEN (#205/#206 payment atomicity/locking, #211/#212 snapshots, #214 audit guardrails, #224 lockout, #246 auth cache). It remains accurate about major open gates: multi-instance staging, peak profiles, pool/statement timeouts, partitioning, per-tenant bulkheads, disbursement claim/intent, webhook throughput, Prometheus/Grafana and deployment substrate.

**Gate:** two-hour all-LSP peak test on 2+ API and 2+ worker instances, month-9 dataset, <0.5% errors, create p95 <2s, dashboard p95 <3s, no cross-tenant p99 regression, no connection-timeout 5xx, webhook backlog <15m, and zero duplicate money movement.

### OPS-01 — Last authenticated operator walkthrough failed and hid diagnostic context

Authenticated 2026-07-10 local walkthrough:

- Login: healthy.
- Loan Applications: healthy with one current row.
- Loan Detail/Documents: healthy.
- Reports: healthy empty state.
- Home: “Couldn't load your dashboard.”
- Audit Log: “Couldn't load audit events.”

Both errors offer Retry but no correlation ID, backend error code, timestamp or support action. This is a production blocker until root cause is identified and regression tested. It also contradicts the older “already good” UX claim that error states expose correlation IDs. On 2026-07-11 a pre-existing browser session reached Home and reproduced the failure in `ux-live-2026-07-11-authenticated/01-home-error.png`; the Login/API returned 401 after that session was invalidated, so Audit could not be recaptured. This confirms Home remains open and does not close Audit.

### OBS-01 — Good primitives, incomplete production observability

Present: correlation IDs, actuator health, DB health indicator, structured-ish event logs, webhook outcome counters/backoff/dead-letter/redrive, selected alert/exception counters, ops alerts.

Missing/unproven: Prometheus deployment/scrape, OpenTelemetry traces, JSON log standard/MDC completeness, SLOs, per-LSP latency/error/bulkhead metrics, pool/statement metrics, disbursement intent age/outcome/reconciliation, unallocated/suspense/reversal totals, idempotency PENDING age, report/webhook queue depth and oldest age dashboards, alert-notifier delivery, synthetic canaries and DR/restore telemetry.

### PARTNER-01 — Partner operations cannot run a permanent loan book safely

The LSP API is strong on isolation, strict JSON, errors, pagination and idempotency. The partner UI/API still lacks:

- arbitrary payment allocation and reversals;
- daily disbursement/repayment/settlement reconciliation extract;
- disbursement attempt/failure timeline and remediation status;
- rich installment/payment/delinquency servicing UI;
- self-service secret rotation, webhook and IP-allowlist management;
- list filtering/search in the LSP UI;
- an explicit PAN exposure decision.

KFS visibility is now fixed and should be marked closed in the July LSP report.

### UX-01 — Lifecycle gates are visible but do not prevent or explain the action

The inspected INITIALIZED application visibly showed “Docs incomplete” and “Schedule missing,” yet “Submit for approval” was enabled. The confirmation dialog said only that status would change and audit would record it; it did not repeat blockers or explain the expected resulting state.

Make action availability derive from the same backend `blockedReasons` contract. If submission-before-completion is intentional, say “Submit incomplete application for review,” show blockers, and distinguish manual review from auto-approval.

### UX-02 — Money confirmation omits amount and fee

The disbursement dialog shows target bank data but not principal, processing fee, net transfer, payment mode, loan ID or approval actor. Add those fields and a second-person approval state. The current “queued” copy must match the actual intent workflow.

### ARCH-01 — Architecture improved, but large boundary files and duplicated contracts remain

**Improved:** former 1,172/1,455-line facade/god service is decomposed; no `@Lazy` injection remains; architecture tests exist; money helpers and typed errors improved; frontend status drift/pagination/format/dialog issues were fixed.

**Open:**

- `LspLoanApplicationApiController.java` is ~945 lines and owns a large nested contract surface.
- `GlobalExceptionHandler.java` is ~700 lines with 31 conditionals.
- `AdminReportingService.java` is ~585 lines.
- `AdminApiIdempotencyService.java` is ~400 lines, over half blank lines, suggesting mechanical/formatting churn.
- `frontend/src/features/my-loans/api.ts` is ~696 lines and continues hand-written contract mapping despite generated OpenAPI types.
- `LspLoanApplicationResponses` depends on controller-nested response types (`LspLoanApplicationResponses.java:206`), reversing a clean boundary.
- Frontend `LoanAccountStatus` still models `ACTIVE`, while backend models `DISBURSED`, `DISBURSEMENT_REQUESTED`, failed/reconciliation and invalid states; `api-detail.ts:198-202` casts the backend value into an inaccurate union.

Decompose by bounded API resource, move contracts to dedicated packages generated/validated from OpenAPI, and delete obsolete account-status abstractions instead of adding translators.

### CI-01 — Current frontend release gate is broken

The 2026-07-11 `npm run verify` run passed `tsc --noEmit` and then failed lint:

- unused `filters` parameter in `frontend/src/features/audit/components/AuditTable.tsx:123`;
- synchronous state accumulation inside an effect in `frontend/src/features/audit/page.tsx:149`;
- synchronous state reset inside an effect in `frontend/src/features/my-loans/detail-page.tsx:78`;
- missing `docLabels` effect dependency warning in `frontend/src/features/my-loans/components/DocumentsSection.tsx:214`.

An independent production build also failed with three TypeScript errors: the unused Audit parameter, a `MyLoanDetail` fixture missing required `lastActivity`, and a `CreateUserInput` test fixture missing required `lspId`. Focused tests for Audit, LSP loan detail/documents, and Users passed **12/12**, which confirms the changed components are not uniformly broken but the release baseline is inconsistent. Fix the compile/lint contract errors, then require `verify` to pass in CI before management demo artifacts are cut.

### ENV-01 — Local bootstrap identity drift blocks repeatable UAT

The supplied `siddhant@bhawanafinance.com` identity is not present in the running local `app_user` table. The only matching active bootstrap user is `ops.admin` with a different local email and no lock. The root `.env` contains a bootstrap password but no explicit bootstrap email, and neither the supplied password nor the repository-configured password authenticated the running bootstrap row. A pre-existing refresh/session state briefly exposed Home before a full navigation returned to Login.

This is not a production-domain bug, but it is a P1 UAT/operations defect: reviewers cannot reproduce protected flows, credentials/configuration and database state disagree, and repeated guessing can trigger brute-force lockout. Make local bootstrap identity and secret inputs explicit, restart/synchronize the bootstrap row through the supported bootstrap process, verify one documented login, and keep the secret outside source control. Do not repair this by editing the password hash directly.

## 4. Database and API assessment

### Keep — already correct

- `BigDecimal`/`NUMERIC(19,2)` money model; no floating point.
- DB CHECK constraints for non-negativity and allocation/installment totals.
- Foreign keys/restrictive delete semantics across the loan graph.
- RLS foundation plus tenant-isolation integration coverage.
- Pessimistic installment lock + `@Version` concurrency backstop.
- DB-enforced idempotency uniqueness/fingerprints.
- Product term versioning frozen at origination.
- `FOR UPDATE SKIP LOCKED` claims/leasing for webhook and report workers.
- Keyset-batched MIS export and audit explorer date/keyset guards.
- Strict partner JSON, structured error codes/violations/correlation IDs.
- Header-based pagination now correctly consumed by the main frontend lists.
- API-client/LSP token versions, active-status validation and allowlist enforcement.

### Improve

1. Durable disbursement intent/reference uniqueness/reconciliation.
2. Beneficiary snapshot and re-approval on change.
3. Receipt/allocation/reversal ledger.
4. Partition/retention/archive/restore pipeline.
5. PII encryption/hash and JSON redaction.
6. Production datasource/pool/statement/idle-transaction timeouts.
7. Resolve dual lifecycle history tables and dead legacy PII-reveal infrastructure.
8. Replace CSV webhook subscription column with normalized rows.
9. Remove regex/JSON data extraction where a typed indexed column exists.
10. Publish a partner-only OpenAPI contract with rate/idempotency/retention semantics.

## 5. UI/UX evidence

Accepted screenshots are in `ux/`. Full-page capture duplicated a section at some responsive states, so the mobile audit uses the viewport capture `10-mobile-reports-viewport.png`; the duplicated full-page image is not accepted as UI evidence.

### Flow steps

1. **Sign in — visually healthy, environment unhealthy.** Clear form, specific branding, keyboard/semantic labels and a specific invalid-credential error. The supplied identity is absent from the running database and the active bootstrap identity does not accept the supplied/configured password, so the UAT environment is not reproducibly accessible.
2. **Home — unhealthy.** Operator-critical dashboard failed; Retry only; no diagnostic/correlation context.
3. **Loan queue — mostly healthy.** Rich filters, correct pagination, accessible sort names. Wide table requires horizontal scrolling at desktop content width.
4. **Loan overview — risky.** Strong status/gate hierarchy, but full PAN is shown by default and enabled actions conflict with visible blockers.
5. **Documents — healthy with caveat.** KFS now appears; required/pending states are clear. Very long vertical checklist increases scanning cost.
6. **Approval confirmation — risky.** Audited confirmation exists, but blockers and resulting workflow state are absent.
7. **Audit Log — unhealthy in the last authenticated capture; not closed.** Core compliance surface failed; generic recovery only. The 2026-07-11 recheck could not cross authentication.
8. **Reports — healthy empty state, incomplete provenance.** Clear filters/KPIs/mobile reflow, but no “data as of” stamp or reconciliation certification.

Accessibility evidence is limited to DOM semantics, accepted screenshots and existing axe tests. It is not a WCAG conformance claim; keyboard order, focus trapping, screen-reader announcement, zoom and color contrast need dedicated verification. Fresh evidence is in `ux-live-2026-07-11-authenticated/`: `01-home-error.png` and `02-login-account-mismatch.png`. Remaining protected-route screenshots in `ux/` were captured on 2026-07-10.

## 6. Prior artifact reconciliation

| Artifact | Current assessment |
|---|---|
| `SECURITY_AUDIT_REPORT.md` (May) | Directionally valuable but materially stale. API-client/LSP revocation, allowlists, document signatures, report masking, bootstrap production validation and many audits are fixed. Mock-default runtime, localStorage access token and PII-at-rest/default-PAN exposure remain. |
| `code-quality-review-tracker.md` | Mostly accurate about completed facade/lifecycle/status/blob fixes. Current largest file is below 1K but close; generated/manual contract duplication remains. Its warning that Graphify/Fallow reports are stale is correct. |
| `scalability-execution-tracker.md` | Operating target/launch gates remain authoritative; individual issue statuses are stale. Several OPEN controls exist in the working tree. The gate itself is still open. |
| June performance reports | Valid historical baseline, not current capacity evidence. Must be rerun after remediation. |
| `database-audit-report-2026-07-03.md` + remediation spec | Best current technical tracker. Its fixed/partial/open register aligns closely with current code. |
| `outputs/ux-audit/2026-07-03/...` | Many F1–F20 defects are fixed (login context, reject reason, pagination, compact INR, status filter, dialog overflow, source channel, temp password, KFS). Current Home/Audit failures and lifecycle-gate mismatch are new/current evidence. |
| `outputs/lsp-audit/2026-07-08/...` | Isolation/idempotency/contract strengths remain valid. KFS F1 is fixed. Silent intake stall, PAN decision, exact-EMI repayment, reconciliation, servicing visibility and security self-service remain. |
| Graphify report | Stale (2026-06-12) and structurally noisy; Graphify is not installed in this environment, so it could not be refreshed. It was used only as an initial index, never as proof. |

## 7. Testing and quality gates

### Evidence obtained

- Frontend `tsc --noEmit`: **pass** when run as the first stage of `verify`.
- Frontend release gate: **fail**. Lint reports 3 errors/1 warning, and the independent production build reports 3 TypeScript errors. The gate stops before the full Vitest/build chain.
- Frontend focused changed-area Vitest: **12 tests across 5 files passed** (Audit API, LSP loan detail/documents, Users API/table). This does not supersede the failed release gate.
- Backend focused critical-control suite: **21 tests, 0 failures, 0 errors** with a clean Maven `BUILD SUCCESS`; coverage included repayment concurrency, LSP/admin idempotency, disbursement processing-fee calculation, Flyway migration validation against PostgreSQL, and tenant datasource enforcement.
- Backend broad suite: 141 Java test files / ~27K lines, including Postgres/Testcontainers schema, RLS, concurrency, idempotency, webhook, rate-limit, OpenAPI and architecture coverage. The earlier capped run produced Surefire results for **737 tests across 134 classes: 0 failures, 0 errors, 1 skipped** but did not emit a full-suite verdict.
- Live local flow: 2026-07-10 authenticated frontend/backend walkthrough against repository Docker dependencies; 2026-07-11 fresh Home failure capture via pre-existing session plus Login/API/database bootstrap-drift verification. Remaining protected routes could not be recaptured after the stale session was invalidated. No production/external system was touched.
- Fallow: unavailable locally; downloading/executing third-party analyzer was rejected by the environment safety reviewer. Manual static evidence is used instead.

### Required test strategy

1. Money-path property/invariant tests: total receipt = allocated + suspense; reversal restores balances; ledger entries balance.
2. Deterministic multi-node crash tests around every disbursement step and idempotency completion.
3. Bank contract tests with replayed signed fixtures, duplicate references, timeout/unknown, late success, rejection and reconciliation.
4. Migration tests from production-like schema snapshots, not only fresh Flyway.
5. RLS/IDOR matrix generated for every tenant-owned table and endpoint.
6. Consumer-driven partner contracts and backward-compatibility diff in CI.
7. Two-hour multi-instance peak/soak/spike plus failure injection (DB/Redis/provider/storage/webhook).
8. Playwright operator/LSP journeys with seeded states, keyboard-only and accessibility assertions.
9. CI time budget and hanging-test detection; eliminate test warnings so warnings are actionable.
10. Restore/DR drill and reconciliation evidence as release artifacts.

## 8. What should not be changed casually

- Do not replace `BigDecimal`/fixed-scale database numerics.
- Do not weaken RLS because application-level tenant filters currently pass tests.
- Do not remove DB uniqueness/idempotency fingerprints or installment locks.
- Do not make optimistic UI status changes on money/lifecycle transitions.
- Do not collapse webhook/report worker claims back into unbounded JPA scans.
- Do not expose raw provider responses/secrets to partners to improve “debuggability.”
- Do not build microservices merely to look more “enterprise.” A modular monolith with explicit transaction boundaries remains pragmatic at this stage.
- Do not connect the current mock adapter seam to real rails without changing the durable workflow around it.

## 9. Prioritized remediation roadmap

### Immediate — before further management/demo sign-off

1. Root-cause and fix current Home/Audit runtime failures; add regression tests and correlation IDs to UI errors.
2. Mask PAN by default on ops/LSP UI and redact raw PII from alert/audit JSON writes.
3. Align intake validation with progression requirements or expose blocked reasons.
4. Correct approval/disbursement action copy and gate behavior; show money amount/net fee.
5. Update the audit/scalability/LSP trackers to current implementation state.
6. Make frontend test completion deterministic; establish a clean current backend/frontend CI baseline.
7. Reconcile the local bootstrap identity/email/password inputs with the running `app_user` row and document one supported UAT login path.

### Short term — before any real partner pilot

1. Partner reconciliation/MIS API; disbursement attempt/status visibility; servicing schedule/payment/delinquency UI.
2. LSP security self-service with constrained roles and audit.
3. Idempotency stale-PENDING recovery.
4. Complete retention for refresh tokens/webhooks/reports/audits and document policy.
5. Production datasource pool/timeout configuration; Prometheus/JSON logs/SLO dashboards/alerts.
6. Multi-instance staging and committed-scale load/concurrency gate.
7. Beneficiary snapshot/re-approval.
8. Split oversized contract/controller files; finish generated contract adoption.

### Real-rail program — launch blockers

1. Durable disbursement intent/provider/outcome/reconciliation state machine.
2. Maker-checker, limits, segregation of duties and exception approvals.
3. Receipt/allocation/suspense/reversal/bounce ledger and bank reconciliation.
4. PII/secrets encryption with KMS, key rotation and audited reveal.
5. Partitioning/archive/legal-hold/restore implementation.
6. ICICI/provider security: mTLS/signing/encryption, key rotation, replay protection, circuit/bulkhead, contract certification.
7. DR: Multi-AZ, PITR, RPO/RTO drill; reconciliation and recovery runbooks.
8. Independent penetration test, threat model, privacy/compliance assessment, financial-control walkthrough and production readiness review.

## 10. Final assessment

The platform is a credible, well-remediated fintech prototype/modular monolith with several genuinely production-grade primitives. It is **not** yet a production lending system of record and must not be connected to real disbursement rails in its current workflow. The fastest safe path is not a rewrite: keep the current domain/database/RLS/outbox foundations, finish the missing durable money workflows and operational controls, prove them under multi-instance load and failure injection, and only then conduct a real-rail launch review.
