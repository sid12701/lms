# W4-A9 — API Loan Intake + D3 One-Open-Loan (Verified)

**Status**: Verified (read-only)  
**Date**: 2026-07-30  
**Agent**: A9  
**Baseline**: LMS HEAD + dirty worktree (`LoanApplicationRepository`, `LoanApplicationLifecycleService`, `LoanApplicationStatusWriter`, `LoanAutoApprovalConcurrencyPostgresIntegrationTest`)  
**Spec anchors**: `api-loan-application-intake/spec.md`, `partner-pre-disbursement-cancellation/spec.md` (skimmed), D1/D3/D8/D9

---

## 1. Executive assessment

Partner loan intake is **well-structured for a bank-aware prototype**: API-only create (`LSP_API_CLIENT`), strict JSON validation, admin-scoped global borrower dedup, optional lease-based idempotency with crash recovery for create, intake audit + ops alerts on identity/active-loan conflicts, and tenant-scoped reads.

The **material D3 gap is semantic and temporal**: intake blocks only **open loan accounts** (`loan_account` rows in disbursement/servicing statuses), not **in-flight applications** without accounts. Cross-LSP concurrent intake for the same PAN can yield multiple `INITIALIZED` applications (explicitly tested). D3 hardening at **approval** is in the dirty worktree (borrower pessimistic lock + second open-loan check). **No test anywhere asserts `BORROWER_HAS_ACTIVE_LOAN` at intake.**

Cancellation to `INVALID` is terminal, idempotent (mandatory key), and correctly marks loan accounts `INVALID` — which **removes** the borrower from open-loan blocking for subsequent intake.

**No Critical** under pre-production posture. Highest verified issues are **High**: D3 not enforced on in-flight applications at intake; concurrent cross-LSP intake race before loan-account existence.

---

## 2. Purpose and actors

| Actor | Intake | Cancellation |
|-------|--------|----------------|
| `LSP_API_CLIENT` | Create + read | Cancel (`POST …/invalid`) |
| `LSP_UI_READ` / `LSP_UI_WRITE` | Read only (D1) | `LSP_UI_WRITE` can cancel |
| Ops / admin | No create path | No invalidation path (partner-only) |

Intake creates `INITIALIZED` + audit + 8-doc checklist + optional `LOAN_CREATED` webhook. Cancellation drives pre-disbursal apps to terminal `INVALID`.

---

## 3. End-to-end architecture

```
POST /api/v1/lsp/loan-applications
  → LspLoanApplicationApiController (lspId binding, optional Idempotency-Key)
  → [if key] LspApiIdempotencyService → IdempotencyExecutionCoordinator (lease/claim/replay/recover)
  → LoanApplicationLifecycleService → LoanApplicationOnboardingService
       → adminScopedTransactionExecutor (REQUIRES_NEW, ADMIN datasource)
       → gates: LSP/product/mapping/rate/amount/tenure/external-id uniqueness
       → BorrowerOnboardingService.resolveBorrowerForOnboarding (@Transactional in admin txn)
            → findByPan + mobile lookup (cross-LSP)
            → identity conflict → ops alert (separate REQUIRES_NEW txn) + 409
            → open loan account check → ops alert + BORROWER_HAS_ACTIVE_LOAN
            → create/reuse borrower + grantVisibility
       → save INITIALIZED application + intake audit + checklist + webhook

POST …/{id}/invalid (mandatory Idempotency-Key)
  → LspApiIdempotencyService (fingerprint: appId + reasonCode + reasonText)
  → LoanApplicationInvalidationService
       → eligibility: not INVALID, not servicing, loan-account guards
       → markInvalid application + loan account, transition row, INVALIDATED audit, webhook
```

**Admin-scoped bypass**: Intake and idempotent LSP writes elevate to admin datasource so PAN dedup and idempotency records see cross-LSP data (RLS would hide other LSP borrowers). Application access remains LSP-filtered on reads.

---

## 4. Traceability (intake + D3 + cancellation touchpoints)

| Requirement | Spec | Implementation | Tests | Status |
|-------------|------|----------------|-------|--------|
| FR-001 API-only create | intake FR-001 | `@PreAuthorize LSP_API_CLIENT` on POST | `LspLoanApplicationApiControllerTest` UI blocked | Complete |
| FR-003 lspId binding | FR-003 | Controller + `enforcedLspId` in service | Controller + `LoanApplicationOnboardingServiceLspGuardTest` | Complete |
| FR-008 external id unique | FR-008 | `existsByLsp_IdAndExternalLoanIdIgnoreCase` | Ops + DataIntegrity tests | Complete |
| FR-009 PAN dedup | FR-009 | `BorrowerOnboardingService` | dedupe + conflict tests | Complete |
| FR-010 D3 open loan | FR-010 | `BorrowerActiveLoanChecker` on **loan accounts** | **None at intake** | Partial |
| FR-016 create idempotency | FR-016 | Optional key, `LOAN_APPLICATION_CREATE` | `IdempotencyCrashRecoveryIntegrationTest`, race test | Partial (optional key) |
| FR-018 INITIALIZED only | FR-018 | `LoanApplicationStatus.INITIALIZED` at create | Controller test | Complete |
| Cancel FR-007–009 guards | cancellation | `LoanApplicationInvalidationService` | Controller invalidation tests | Complete |
| Cancel FR-015 mandatory idempotency | cancellation | `requireUuidV4` always on invalid path | Controller tests | Complete |
| NFR-004 borrower race retry | NFR-004 | 3× `DataIntegrityViolationException` retry | Implicit via PAN unique | Partial |

---

## 5. Backend — intake validation chain

Gate order in `LoanApplicationOnboardingService.doCreateApplication` matches spec:

1. LSP `ACTIVE` → `LSP_NOT_ACTIVE`
2. Product resolve + `ACTIVE` → `PRODUCT_NOT_ACTIVE`
3. Latest product version; optional rate match → `INTEREST_RATE_MISMATCH`
4. Mapping enabled → `PRODUCT_NOT_MAPPED` / `PRODUCT_MAPPING_DISABLED`
5. External loan id → `DUPLICATE_EXTERNAL_LOAN_ID` (409)
6. Amount/tenure ranges
7. Borrower resolution
8. Persist application, audit, checklist, webhook

Transactional boundary: entire `doCreateApplication` runs inside one admin-scoped transaction (or nested when idempotency coordinator already opened one). Failure rolls back application artifacts; ops alerts use separate `REQUIRES_NEW` via `AdminScopedTransactionExecutor` and **survive** intake rejection (spec-aligned).

`enforcedLspId` path also runs `BorrowerOnboardingRequirements.missingRequiredFieldErrors` — API path always passes enforced LSP from controller.

---

## 6. PAN dedupe and identity resolution

**Anchor**: PAN (normalized upper-case, `uk_borrower_pan`).

| Case | Behavior |
|------|----------|
| PAN match, mobile consistent | Reuse borrower, `mergeLatestProfile`, grant LSP visibility |
| PAN match, mobile owned by different borrower | `BORROWER_IDENTITY_CONFLICT` + HIGH alert |
| Mobile match, different PAN | `BORROWER_IDENTITY_CONFLICT` + HIGH alert |
| Aadhaar change on existing PAN | `BORROWER_IDENTITY_CONFLICT` (immutable Aadhaar) |
| New PAN + new mobile | `new Borrower(profile)` + visibility grant |

**Mobile lookup**: `findTop10ByMobileOrderByUpdatedAtDesc` — most recently updated wins; not a unique constraint on mobile (by design per spec G-2).

**Concurrent first-time PAN**: unique index on PAN + up to 3 retries on `DataIntegrityViolationException` in `createApplication`.

**Admin scope**: Documented in `BorrowerOnboardingService` — required for cross-LSP `findByPan`.

---

## 7. Idempotency

### Create (`LOAN_APPLICATION_CREATE`)

- **Optional** header; blank/missing → direct create (relies on `lspLoanId` uniqueness only — spec gap G-3).
- **Fingerprint**: SHA-256 of Jackson-serialized `LspLoanApplicationRequest` (full body including `lspId`).
- **Coordinator**: claim → lease → complete in same admin txn; mismatch → `IDEMPOTENCY_CONFLICT`; in-flight → `IDEMPOTENCY_IN_PROGRESS`; lease reclaim + **`LoanApplicationCreateIdempotencyReconstructor`** (lookup by `lspId` + `lspLoanId`).
- **Failed business rules**: exception releases pending row — safe retry (not cached as success).
- **Race**: `LspApiIdempotencyServiceRaceTest` — 5 concurrent same-key → single record.

`LspApiIdempotencyService.runUnderAdminScope` sets tenant context; coordinator also uses `adminScopedTransactionExecutor` — layered but coherent.

### Invalidate (`LOAN_APPLICATION_INVALIDATION`)

- **Mandatory** UUID v4 key (controller always routes through `execute` → `requireUuidV4`).
- Fingerprint: `applicationId` + `reasonCode` + normalized `reasonText`.
- Replay returns stored detail response; duplicate invalidation attempt without new key hits `LOAN_ALREADY_INVALID` inside business logic (not idempotency replay).

### Invalidate vs create asymmetry

| Operation | Key required | Recovery reconstructor |
|-----------|--------------|------------------------|
| Create | Optional | Yes |
| Invalidate | Mandatory | No (lease reclaim may hit `IDEMPOTENCY_RECOVERY_REQUIRED` if unsupported) |

---

## 8. D3 — one open loan (cross-LSP)

### What “open” means in code

`BorrowerActiveLoanChecker.OPEN_STATUSES`:

- `PENDING_DISBURSEMENT`
- `DISBURSEMENT_REQUESTED`
- `DISBURSED`
- `DISBURSEMENT_PENDING_RECONCILIATION`

**Not** included: `INVALID`, `CLOSED`, `FORECLOSED`, `DISBURSEMENT_FAILED`, application statuses (`INITIALIZED`, `AWAITING_APPROVAL`, etc.).

### Where D3 is enforced

| Stage | Mechanism |
|-------|-----------|
| **Intake** (PAN reuse path only) | `raiseActiveLoanDuplicateIfPresent` → `findOpenLoansAcrossAllLsps` |
| **Auto-approval rule engine** | `BORROWER_HAS_OPEN_LOAN` if other open account exists |
| **Approval / loan-account creation** (dirty worktree) | `findBorrowerByApplicationIdForUpdate` + `hasOpenLoanAcrossAllLsps` before insert |

### Intake gap (core finding)

`onboardingReusesGlobalBorrowerByPanUpdatesLatestProfileAndExpandsLspVisibility` **proves** two `INITIALIZED` applications for the same borrower across LSPs when no loan account exists. Spec FR-010 text says “open loan” via `findOpenLoansAcrossAllLsps` — **spec and code agree**, but **business D3 (“one open loan per borrower”) is weaker at intake** than at approval.

### Cross-LSP approval race (dirty worktree)

Uncommitted changes add:

- `LoanApplicationRepository.findBorrowerByApplicationIdForUpdate` — pessimistic lock on borrower row via application id
- `lockBorrowerForApproval` in `LoanApplicationLifecycleService` before auto-approve / transition to `APPROVED_PENDING_DISBURSAL`
- `LoanApplicationStatusWriter.ensureLoanAccountForApprovedApplication` — open-loan re-check under lock

`LoanAutoApprovalConcurrencyPostgresIntegrationTest` validates: two cross-LSP `AWAITING_APPROVAL` apps → exactly one `APPROVED_PENDING_DISBURSAL`, one `REJECTED`, one loan account.

**Intake concurrency is not similarly locked.**

### Admin read optimization

`BorrowerActiveLoanChecker.readAcrossAllLsps` joins existing admin txn when already in ADMIN mode (avoids nested pool exhaustion under concurrent onboarding).

---

## 9. Cancellation and terminal states

### Eligibility (`LoanApplicationInvalidationService`)

- Already `INVALID` → `LOAN_ALREADY_INVALID` (409)
- `hasEnteredServicing()` on application (`DISBURSED`, `UNDER_REPAYMENT`, `CLOSED`, `FORECLOSED`) → `INVALIDATION_NOT_ALLOWED`
- Loan account `DISBURSED`/`CLOSED`/`FORECLOSED` → `INVALIDATION_NOT_ALLOWED`
- Loan account already `INVALID` → `LOAN_ALREADY_INVALID`
- Pre-disbursal set: `INITIALIZED`, `AWAITING_APPROVAL`, `APPROVED_PENDING_DISBURSAL`, `DISBURSEMENT_RETRY` — allowed

**Does not use** `LoanApplicationStatusTransitioner.enforceTransition` (dedicated invalidate path — spec-aligned).

### Effect on D3

- Application → `INVALID` (terminal, no outbound transitions)
- Loan account → `LoanAccountStatus.INVALID` (not in `OPEN_STATUSES`)
- **Borrower can intake again** even if an `INVALID` application row remains (no open loan account)

### Spec/code drift

- Cancellation spec lists `REASON_A`/`REASON_B`/`REASON_C`; code uses `DUPLICATE_APPLICATION`, `KYC_MISMATCH`, etc. (`LoanInvalidationReason`) — tests use real enum values.
- Cancellation spec **G-1**: `REJECTED` → `INVALID` **not blocked** by current gate (neither servicing nor INVALID) — state machine forbids it but invalidate path bypasses `canTransitionTo`.

### Reason-text policy

Enforced in `normalizeInvalidReasonText`: `OTHERS` / `FRAUD_SUSPECTED` require text; others forbid text — controller tests cover this.

---

## 10. Admin-scoped bypass (tenancy)

| Concern | Mechanism |
|---------|-----------|
| Cross-LSP PAN visibility | `AdminScopedTransactionExecutor` in onboarding |
| Idempotency record writes | Admin txn in coordinator |
| Open-loan reads | Admin (or join current admin txn) |
| Ops alerts on conflict | `OpsAlertService.createAlert` in separate admin txn |
| LSP data isolation on reads | `LoanApplicationQueryService.getApplicationForLsp` |
| Defense in depth on writes | `enforcedLspId` in onboarding when called from API |

Residual risk class: admin-scoped write without `lspId` filter (mitigated on API path by controller binding + service guard).

---

## 11. Database tables touched

**Writes at intake**: `loan_application`, `borrower`, `borrower_lsp_access`, `borrower_lsp_relationship`, `loan_application_intake_audit`, `loan_application_document_checklist`, `lsp_api_idempotency_record` (if keyed), `ops_alert` (on conflict), webhook outbox.

**Reads**: `lsp`, `loan_product`, `loan_product_version`, `loan_product_lsp_mapping`, `loan_account` (open check), `borrower` (PAN/mobile).

**Constraints**: `uk_borrower_pan`; per-LSP external loan id uniqueness (case-insensitive check in code).

---

## 12. Testing coverage

| Area | Covered | Gap |
|------|---------|-----|
| Create happy path + gates | `LspLoanApplicationApiControllerTest` | — |
| PAN dedupe cross-LSP | `onboardingReusesGlobalBorrowerByPan…` | — |
| Identity conflict | `onboardingConflictOnExistingMobile…` | Aadhaar-only conflict not isolated |
| Create idempotency replay | `IdempotencyCrashRecoveryIntegrationTest` | No controller-level replay test |
| Create idempotency race | `LspApiIdempotencyServiceRaceTest` | — |
| Invalidate + idempotency | Controller tests | — |
| **`BORROWER_HAS_ACTIVE_LOAN` at intake** | **None** | **Zero tests** |
| Cross-LSP intake concurrency | None | Race untested |
| D3 at approval | `LoanAutoApprovalConcurrencyPostgresIntegrationTest` (dirty) | — |
| INVALID frees re-intake | None explicit | — |

---

## 13. External research (applicability, not legal advice)

| Source | Relevance to W4-A9 |
|--------|------------------|
| OWASP API Security — idempotency keys on unsafe methods | Supports mandatory keys for create in production; current optional create is partner-risk |
| Digital lending audit expectations | Intake audit + conflict alerts support D9; multiple in-flight apps per borrower weakens origination control narrative |
| Postgres `SELECT FOR UPDATE` on parent row | Dirty worktree pattern is standard for serializing borrower-level decisions |

---

## 14. Default-driven decision register

| Decision | Classification | Notes |
|----------|----------------|-------|
| Admin-scoped intake for global dedup | Documented + demonstrated | Spec + code comments |
| D3 = loan account statuses only | Demonstrated; business D3 may imply more | Test allows dual `INITIALIZED` |
| Optional create idempotency | Spec gap G-3 acknowledged | `lspLoanId` fallback |
| Invalidate bypasses state machine | Demonstrated | Allows `REJECTED→INVALID` |
| Ops alert always `createAlert` on conflict | Default-driven | No dedup on repeated conflicts |
| Approval-stage borrower lock | Dirty worktree; not at intake | Fixes approval race only |
| Invalidation reason enum vs spec labels | Doc drift | Code is richer than spec |

---

## 15. Verified findings

### W4-A9-F01 — D3 at intake ignores in-flight applications (only open loan accounts)
- **Severity**: High · **Confidence**: High  
- **Evidence**: `BorrowerOnboardingService.raiseActiveLoanDuplicateIfPresent` queries `loan_account` only; `OPEN_STATUSES` excludes application-only pipeline; `LspLoanApplicationApiControllerTest.onboardingReusesGlobalBorrowerByPan…` creates two `INITIALIZED` apps same `borrowerId` across LSPs  
- **Why it matters**: Two partners can onboard the same borrower concurrently before approval; D3 is deferred to approval engine + dirty worktree lock.  
- **Scenario**: Borrower has `INITIALIZED` at LSP-A; LSP-B submits same PAN → succeeds.  
- **Bank expectation**: One origination pipeline per borrower globally at submission time.  
- **Change**: At intake, check `loan_application` non-terminal pre-disbursal statuses cross-LSP (admin-scoped), or pessimistic lock borrower row during resolve.  
- **Tests**: Cross-LSP second intake rejected; concurrent dual-intake integration test.

### W4-A9-F02 — No borrower-level lock at intake (cross-LSP race before account exists)
- **Severity**: High · **Confidence**: Medium  
- **Evidence**: No `findBorrowerByApplicationIdForUpdate` or equivalent in onboarding; approval lock only in dirty `LoanApplicationLifecycleService.lockBorrowerForApproval`  
- **Why it matters**: Concurrent intakes for new PAN converge via PAN unique index, but concurrent intakes for **existing** borrower without open account can both pass active-loan check.  
- **Change**: Serialize `resolveBorrowerForOnboarding` on borrower id/PAN (`FOR UPDATE` on borrower or advisory lock).  
- **Tests**: Parallel POST same PAN different LSPs → one success one `BORROWER_HAS_ACTIVE_LOAN` or conflict.

### W4-A9-F03 — Zero test coverage for `BORROWER_HAS_ACTIVE_LOAN` at intake
- **Severity**: Medium · **Confidence**: High  
- **Evidence**: `grep BORROWER_HAS_ACTIVE_LOAN` across `backend/src/test` → no matches  
- **Why it matters**: Core D3 rejection path is unverified in CI.  
- **Change**: Integration test: seed open `loan_account`, attempt intake same PAN → 409 + `BORROWER_ACTIVE_LOAN_DUPLICATE` alert.

### W4-A9-F04 — Create idempotency optional (partner retry without key can duplicate on new external id)
- **Severity**: Medium · **Confidence**: High  
- **Evidence**: `LspLoanApplicationApiController.createApplication` lines 231–232 skip idempotency when key blank; spec G-3  
- **Mitigation**: `lspLoanId` uniqueness per LSP  
- **Change**: Require UUID v4 on create in production; keep external-id uniqueness as second fence.

### W4-A9-F05 — Cancellation to `INVALID` frees D3 gate; prior `INVALID` application row remains
- **Severity**: Medium · **Confidence**: High  
- **Evidence**: `LoanAccountStatus.INVALID` ∉ `OPEN_STATUSES`; intake does not check existing application statuses  
- **Why it matters**: Partner can cancel and re-submit; multiple `INVALID` + active `INITIALIZED` rows per borrower possible — audit noise, weak “single pipeline” story.  
- **Change**: Product decision — block intake if non-terminal application exists, or treat `INVALID` as closed pipeline only when no other pre-disbursal app exists.

### W4-A9-F06 — `REJECTED` applications can be invalidated (state-machine bypass)
- **Severity**: Low · **Confidence**: High  
- **Evidence**: Cancellation spec EC-001 / G-1; `LoanApplicationInvalidationService` checks `hasEnteredServicing()` + `INVALID` only; `REJECTED` is terminal in `canTransitionTo`  
- **Change**: Add `currentStatus.isTerminal()` guard or explicit allow-list matching `isPreDisbursal()`.

### W4-A9-F07 — Repeated identity/active-loan conflicts spam ops alerts
- **Severity**: Low · **Confidence**: Medium  
- **Evidence**: `BorrowerOnboardingService` calls `opsAlertService.createAlert` (not `createAlertIfAbsent`) on every conflict  
- **Change**: Use `createAlertIfAbsent` for `BORROWER_IDENTITY_CONFLICT` / `BORROWER_ACTIVE_LOAN_DUPLICATE`.

### W4-A9-F08 — Invalidation reason catalog diverges from cancellation spec
- **Severity**: Low · **Confidence**: High  
- **Evidence**: Spec `REASON_A/B/C`; code `LoanInvalidationReason` with `DUPLICATE_APPLICATION`, `KYC_MISMATCH`, etc.  
- **Change**: Update spec to as-is enum or align API docs.

### W4-A9-F09 — `DISBURSEMENT_FAILED` loan accounts do not block re-intake
- **Severity**: Low · **Confidence**: High  
- **Evidence**: `DISBURSEMENT_FAILED` ∉ `OPEN_STATUSES`  
- **Note**: May be intentional (retry via new application); document explicitly.

### W4-A9-F10 — Dirty worktree: approval-stage D3 hardening (positive)
- **Severity**: Informational · **Confidence**: High  
- **Evidence**: git diff on `LoanApplicationRepository`, `LoanApplicationLifecycleService`, `LoanApplicationStatusWriter`; `LoanAutoApprovalConcurrencyPostgresIntegrationTest`  
- **Note**: Addresses cross-LSP **approval** race; does not close intake gaps F01/F02.

### W4-A9-F11 — New-borrower path skips active-loan check
- **Severity**: Low · **Confidence**: High  
- **Evidence**: `raiseActiveLoanDuplicateIfPresent` only on `borrowerByPan != null` branch  
- **Note**: Harmless if PAN is true anchor; would matter only if open-loan borrower could submit new PAN (fraud path).

### W4-A9-F12 — Borrower-insert race: bounded 3 attempts, no global lock
- **Severity**: Low · **Confidence**: High  
- **Evidence**: `LoanApplicationOnboardingService.createApplication` retry loop; spec G-7  
- **Change**: Advisory lock on PAN during resolve if extreme contention observed.

---

## Summary matrix

| Focus area | Verdict |
|------------|---------|
| Idempotency (create) | Sound lease model + create reconstructor; optional key is the main gap |
| Idempotency (invalidate) | Mandatory key, fingerprinting, replay tested |
| PAN dedupe | Matches spec; cross-LSP reuse tested |
| D3 at intake | **Loan-account-only**; in-flight apps allowed |
| D3 at approval | Dirty worktree adds lock + re-check (tested) |
| Admin bypass | Intentional, documented, dual-filtered on API path |
| Cancellation terminals | `INVALID` terminal; frees re-intake; `REJECTED→INVALID` hole |

---

**Dirty worktree relevance**: The uncommitted approval concurrency work **materially improves D3 at loan-account creation** but **does not change intake behavior** reviewed here. Intake audit should treat F01/F02 as open until intake-level enforcement or tests land.

[REDACTED]