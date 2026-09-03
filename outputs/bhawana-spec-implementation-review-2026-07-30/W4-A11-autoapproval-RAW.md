# Wave 4 Agent A11 — Automated Credit Decision Rule Engine Audit

**Spec:** `automated-credit-decision-rule-engine/spec.md` v1.0.1 (D2 fully automated credit)  
**Baseline:** LMS worktree as of 2026-07-30, including **uncommitted dirty delta** (H-01 borrower-lock remediation)  
**Mode:** READ-ONLY  
**Components reviewed:** `LoanAutoApprovalRuleEngine`, `LoanAutoApprovalGateService`, `LoanApplicationLifecycleService`, `LoanApplicationStatusWriter`, `LoanApplicationStatusTransitioner`, `LoanDocumentService` (trigger), `BorrowerActiveLoanChecker`, `OpsAlertEmitters`  
**Tests reviewed:** `Issue85*`, `Issue135*`, `LoanAutoApprovalConcurrencyPostgresIntegrationTest`, `LoanApplicationStatusWriterTest`, `LoanAutoApprovalGateServiceTest`, `LoanApplicationStatusTransitionerTest`, `LspLoanApplicationApiControllerTest` (gate/STP), `LoanApplicationOpsControllerTest` (manual override)

**Dirty-worktree marker:** Findings marked `[DIRTY]` depend on uncommitted changes in `LoanApplicationRepository`, `LoanApplicationLifecycleService`, `LoanApplicationStatusWriter`, and associated tests. HEAD without that delta reopens spec gap G-9.

---

## 1. Executive Summary

The D2 automated credit-decision path is **substantially spec-faithful**: eight hard-coded rules evaluated read-only with accumulate-all-failures semantics; completion-edge gate with correct status guards and Micrometer counters; decision executor implementing the three outcomes (two-hop approve, `AWAITING_APPROVAL` auto-reject, `INITIALIZED` no-op); single-writer persistence of transitions, audit events, rejection JSON, and `LOAN_STATUS_CHANGED` webhooks; advisory manual override with HIGH ops alert.

**Primary risk (pre-dirty):** cross-LSP concurrent approvals for the same borrower could create two open loan accounts (spec G-9). **Uncommitted H-01 work** adds pessimistic borrower-row locking and a write-boundary re-check in `ensureLoanAccountForApprovedApplication`, with concurrency integration tests — this materially closes D3 race windows but is **not yet committed**.

**Primary test gap:** no dedicated `LoanAutoApprovalRuleEngine` unit tests per rule code; several spec edge paths (INITIALIZED no-op on non-document failure, automated rejection JSON) lack targeted assertions.

**Overall verdict:** **PASS with conditions** — compliant for D2 behavior assuming H-01 dirty work is committed; test depth below bank-grade bar for individual rule fidelity.

---

## 2. Scope & Methodology

| In scope | Out of scope (per spec) |
|----------|-------------------------|
| Eight-rule evaluation & execution | Document upload edge detection (KYC spec) |
| Gate guards & metrics | Disbursement/servicing beyond approval |
| State machine enforcement | Webhook delivery retries |
| Audit trail & rejection JSON | Partner `INVALID` cancellation |
| Manual `SYSTEM_ADMIN` exception path | Bureau/score (G-2) |
| D3 one-open-loan concurrency | |

Method: spec FR/NFR/UC trace against Java sources and named tests; git diff inspected for dirty delta; call-path traced from `LoanDocumentService` → gate → lifecycle → writer.

---

## 3. Spec Traceability Matrix (D2 Core)

| Requirement | Status | Evidence |
|-------------|--------|----------|
| FR-001..010 (eight rules, accumulate, read-only) | **PASS** | `LoanAutoApprovalRuleEngine.evaluate` |
| FR-011 (completion edge + status guards) | **PASS** | `LoanAutoApprovalGateService` |
| FR-012..013 (approve hops / conditional reject) | **PASS** | `LoanApplicationLifecycleService.autoApproveIfEligibleForLsp` |
| FR-014..015 (single writer, audit, webhooks) | **PASS** | `LoanApplicationStatusWriter.updateStatus` |
| FR-016 (loan account idempotent) | **PASS** | `ensureLoanAccountForApprovedApplication` |
| FR-017 (no partner REST engine entry) | **PASS** | Gate only from `LoanDocumentService` |
| FR-018..019 (manual advisory + alert) | **PASS** | `recordManualRuleEngineOverride`, `OpsAlertEmitters` |
| FR-020 (gate metrics) | **PASS** | `lms.auto_approval.gate` counters |
| NFR-001 (fire-once edge) | **PASS** | Gate + `LspLoanApplicationApiControllerTest` metrics test |
| NFR-002 (transactional decision) | **PASS** | `@Transactional` on lifecycle + writer |
| NFR-003 (state machine) | **PASS** | `LoanApplicationStatusTransitioner` |
| NFR-008 (idempotency on manual API) | **PASS** (not deep-audited) | `AdminApiIdempotencyService` via ops controller |
| D3 one-open-loan | **PASS [DIRTY]** | Borrower lock + write-boundary check |
| D9 auditability | **PASS** | Transition row + audit event + correlationId |

---

## 4. Eight Rules Fidelity (Rule Catalog)

Evaluation order and semantics match the spec catalog. All failures accumulate (no short-circuit). Engine is `@Transactional(readOnly = true)` and performs no mutations.

| # | Rule | Implementation | Spec match |
|---|------|----------------|------------|
| 1 | `PRODUCT_INACTIVE` | `product == null \|\| status != ACTIVE` | **PASS** |
| 2 | `LSP_INACTIVE` | `lsp == null \|\| status != ACTIVE` (live re-check) | **PASS** (D4) |
| 3 | `LSP_PRODUCT_MAPPING_INACTIVE` | Only when both present; `mapping == null \|\| !enabled` | **PASS** |
| 4 | `LOAN_AMOUNT_OUT_OF_RANGE` | Null or outside `[min,max]` inclusive; skipped if no product | **PASS** (EC-005, EC-010) |
| 5 | `LOAN_TENURE_OUT_OF_RANGE` | Outside `[min,max]` inclusive | **PASS** |
| 6 | `BORROWER_REQUIRED_FIELDS_MISSING` | `BorrowerOnboardingRequirements.missingRequiredFields` | **PASS** (EC-004) |
| 7 | `REQUIRED_DOCUMENTS_NOT_UPLOADED` | Intake-required (`isRequiredForDisbursement`); `SUBMITTED`/`NOT_REQUIRED` complete; empty checklist fails | **PASS** (EC-006) |
| 8 | `BORROWER_HAS_OPEN_LOAN` | Admin-scoped `BorrowerActiveLoanChecker`; excludes `belongsToCurrentApplication` | **PASS** (D3, EC-003) |

**Not evaluated (by design):** interest-rate rule — EC-009; enforced at intake only.

**Finding:** No `LoanAutoApprovalRuleEngineTest` exists; per-rule fidelity is inferred from shared helpers and integration paths only → **W4-A11-F02**.

---

## 5. Gate Service (`LoanAutoApprovalGateService`)

```44:62:/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanAutoApprovalGateService.java
    public void maybeTriggerAutoApproval(
            UUID applicationId,
            String actorUsername,
            boolean allRequiredDocumentsJustCompleted
    ) {
        if (!allRequiredDocumentsJustCompleted) {
            gateSkippedIncomplete.increment();
            return;
        }
        LoanApplicationStatus status = loanApplicationRepository.findById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown loan application id: " + applicationId))
                .getStatus();
        if (status != LoanApplicationStatus.INITIALIZED && status != LoanApplicationStatus.AWAITING_APPROVAL) {
            gateSkippedStatus.increment();
            return;
        }
        gateFired.increment();
        loanApplicationLifecycleService.autoApproveIfEligibleForLsp(applicationId, actorUsername);
    }
```

| Behavior | Verdict |
|----------|---------|
| `skipped_incomplete` when not just-completed | **PASS** — `LoanAutoApprovalGateServiceTest`, `LspLoanApplicationApiControllerTest.serialDocumentUploadsEmitExpectedAutoApprovalGateMetrics` |
| `skipped_status` when past pre-approval | **PASS** — Issue135, Issue85Issue135 |
| `fired` → lifecycle | **PASS** |
| Unknown applicationId → `IllegalArgumentException` | **PASS** (EC-007) |
| Actor propagation from document upload | **PASS** |

**Issue #85:** Gate invoked **after** `@Transactional` persist methods return in `LoanDocumentService` — auto-approval does not run inside document-persist transactions → **W4-A11-F07 PASS**.

**Minor observability note:** `fired` counter increments before lifecycle call; a downstream failure still counts as fired (not spec-required to be atomic with outcome) → **W4-A11-F23 INFO**.

---

## 6. Decision Executor & State Machine

### Automated path (`autoApproveIfEligibleForLsp`)

```229:271:/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanApplicationLifecycleService.java
    public LoanApplication autoApproveIfEligibleForLsp(UUID applicationId, String actorUsername) {
        lockBorrowerForApproval(applicationId);          // [DIRTY]
        ...
        if (!evaluation.approved()) {
            if (currentStatus == LoanApplicationStatus.AWAITING_APPROVAL) {
                return autoRejectApplication(...);           // REJECTED + FAILED_VERIFICATION
            }
            return application;                            // INITIALIZED no-op
        }
        if (savedApplication.getStatus() == INITIALIZED) {
            savedApplication = statusWriter.updateStatus(..., AWAITING_APPROVAL, ...);
        }
        if (savedApplication.getStatus() == AWAITING_APPROVAL) {
            savedApplication = statusWriter.updateStatus(..., APPROVED_PENDING_DISBURSAL, ...);
            statusWriter.ensureLoanAccountForApprovedApplication(savedApplication);
        }
        return savedApplication;
    }
```

| Outcome | Spec | Verdict |
|---------|------|---------|
| Approved + `INITIALIZED` | Two hops → `APPROVED_PENDING_DISBURSAL` | **PASS** — `LspLoanApplicationApiControllerTest.serialSingleDocumentUploadsAutoApproveOnlyAfterEighthDocument` |
| Approved + `AWAITING_APPROVAL` | Single hop → `APPROVED_PENDING_DISBURSAL` | **PASS** — Issue85 concurrent test seeds `AWAITING_APPROVAL` |
| Failed + `AWAITING_APPROVAL` | Auto-reject `FAILED_VERIFICATION` + note + JSON | **PASS** (code); rejection JSON not asserted on automated path → **W4-A11-F04** |
| Failed + `INITIALIZED` | No-op, stay `INITIALIZED` | **PASS** (code); **no test on completion edge** → **W4-A11-F03** |
| Post-approval re-entry | `AUTO_APPROVAL_NOT_ALLOWED` | **PASS** — Issue85 |

### State machine (`LoanApplicationStatusTransitioner`)

- `STANDARD` context: `LoanApplicationStatus.canTransitionTo` only  
- `MANUAL_OVERRIDE`: relaxed (validated upstream in lifecycle)  
- `WORKER`: `APPROVED_PENDING_DISBURSAL`/`DISBURSEMENT_RETRY` → `REJECTED`  
- `enforceAutoApprovalAllowed`: only `INITIALIZED` / `AWAITING_APPROVAL`  

**PASS** — `LoanApplicationStatusTransitionerTest`, Issue135.

---

## 7. Status Writer & Audit Trail (`LoanApplicationStatusWriter`)

Per transition hop, `updateStatus` atomically (within caller transaction):

1. Enforces transition via `LoanApplicationStatusTransitioner`
2. Persists application status
3. Saves `loan_application_status_transition` (note, reason, `correlationId`, `rejection_reason_json`)
4. Records `loan_application_audit_event` (`STATUS_TRANSITION` or `MANUAL_STATUS_OVERRIDE`)
5. Enqueues `LOAN_STATUS_CHANGED` webhook if subscribed

Auto-reject path serializes:

```json
{"failedRules":["RULE_CODE",...]}
```

via `serializeRejectionReason` → `withRejection` command.

| Audit element | Verdict |
|---------------|---------|
| Transition row per hop | **PASS** |
| Audit event per hop | **PASS** — `LoanApplicationOpsControllerTest.statusTransitionsEmitLoanAuditEvents` |
| `correlationId` | **PASS** — `CorrelationIdHolder.get()` |
| Rejection JSON on auto-reject | **PASS** (code); schema column tested in `SchemaJsonColumnsPostgresTest`, not automated reject E2E → **W4-A11-F04** |
| Two webhooks on straight-through | **PASS** (code); count not asserted in STP test → **W4-A11-F05** |
| Note ≤ 500 chars | **PASS** — max 8 rule names well under limit |

---

## 8. Concurrency & D3 Races

### Spec gap G-9 (baseline HEAD)

Without borrower serialization, two concurrent completions for the same borrower on different LSPs could both pass `BORROWER_HAS_OPEN_LOAN` at evaluation time and each create a `PENDING_DISBURSEMENT` account.

### Uncommitted H-01 remediation `[DIRTY]`

| Layer | Mechanism |
|-------|-----------|
| Repository | `findBorrowerByApplicationIdForUpdate` — `PESSIMISTIC_WRITE` on shared borrower row |
| Lifecycle | `lockBorrowerForApproval` at start of `autoApproveIfEligibleForLsp` and manual approval to `APPROVED_PENDING_DISBURSAL` |
| StatusWriter | Re-acquires borrower lock in `ensureLoanAccountForApprovedApplication`; re-checks `hasOpenLoanAcrossAllLsps` before `save`; idempotent return if account exists |

**Expected concurrent outcome:** one `APPROVED_PENDING_DISBURSAL` + one `REJECTED` (`BORROWER_HAS_OPEN_LOAN` in evaluation) + exactly one open account.

**Tests [DIRTY]:**

- `/Users/siddhant/Desktop/lms/backend/src/test/java/com/bhawana/lms/web/Issue85AutoApprovalIntegrationTest.java` — `concurrentCrossLspApprovalsForSameBorrowerCreateOnlyOneOpenLoan`
- `/Users/siddhant/Desktop/lms/backend/src/test/java/com/bhawana/lms/web/LoanAutoApprovalConcurrencyPostgresIntegrationTest.java` — PostgreSQL Testcontainers variant
- `/Users/siddhant/Desktop/lms/backend/src/test/java/com/bhawana/lms/service/LoanApplicationStatusWriterTest.java` — write-boundary refusal + idempotent replay

**Residual considerations:**

- Loser rejection reason JSON not asserted to contain `BORROWER_HAS_OPEN_LOAN` → **W4-A11-F17**
- H-01 uncommitted — production HEAD may still have G-9 race → **W4-A11-F01 [DIRTY] CRITICAL if uncommitted**
- `ensureLoanAccount` throwing `BORROWER_HAS_OPEN_LOAN` rolls back entire `@Transactional` approval — safe, but would surface as 500 on document-upload path if lock logic regressed → **W4-A11-F11 [DIRTY] MEDIUM**

---

## 9. Human Override Interaction (D2 Exception Path)

| Path | Spec | Implementation | Verdict |
|------|------|----------------|---------|
| `POST .../status-transitions` → `APPROVED_PENDING_DISBURSAL` | KYC gate, advisory engine, note annotation, HIGH alert | `transitionStatus` lines 82–116 | **PASS** |
| `POST .../manual-status` | Allowed targets only, reason+note required, `MANUAL_STATUS_OVERRIDE` audit, advisory | `manuallyOverrideStatus` | **PASS** |
| Engine verdict enforced? | No (advisory only, G-8) | Alert + note; approval proceeds | **PASS** (documented gap G-8) |
| `APPROVED_PENDING_DISBURSAL` via manual-status | Blocked | `blocksManualOverrideTarget` | **PASS** — `LoanApplicationOpsControllerTest.manualStatusUpdateCannotTargetApprovedAndRequiresNote` |
| Reopen `REJECTED` → `AWAITING_APPROVAL` | Allowed with advisory | **PASS** — `systemAdminCanManuallyOverrideRejectedLoanBackIntoActiveQueue` |
| OPS_USER blocked | **PASS** — `opsUserCannotManuallyTransitionStatus` |

Advisory note format: `[ruleEngineApproved=<bool>; failedRules=<csv|none>]` — matches FR-018.

---

## 10. Observability & Metrics

| Signal | Verdict |
|--------|---------|
| `lms.auto_approval.gate` (`fired` / `skipped_incomplete` / `skipped_status`) | **PASS** |
| `MANUAL_RULE_ENGINE_OVERRIDE` HIGH alert with engine context | **PASS** — `OpsAlertEmitters.emitManualRuleEngineOverride` |
| Gate outcome vs decision outcome coupling | Partial — `fired` not rolled back on failure → **W4-A11-F23** |

---

## 11. Test Coverage Assessment

| Area | Coverage | Gap |
|------|----------|-----|
| Gate guards | Unit + integration | Adequate |
| STP via document upload | `LspLoanApplicationApiControllerTest` | No explicit 2-transition / 2-webhook count |
| State machine / auto-approval guard | `LoanApplicationStatusTransitionerTest`, Issue85, Issue135 | Adequate |
| Transaction isolation (#85) | Issue85 worker test, Issue85Issue135 HTTP | Adequate |
| D3 concurrency | Issue85 + Postgres test **[DIRTY]** | Depends on uncommitted code |
| Per-rule engine unit tests | **None** | **W4-A11-F02** |
| INITIALIZED no-op (EC-002) | **None** | **W4-A11-F03** |
| Auto-reject JSON E2E | **None** on automated path | **W4-A11-F04** |
| Individual rule failure scenarios (inactive LSP, amount OOR, etc.) | Sparse | **W4-A11-F02** |
| Manual override advisory | `LoanApplicationOpsControllerTest` | Adequate |
| StatusWriter write-boundary | `LoanApplicationStatusWriterTest` **[DIRTY]** | New, uncommitted |

---

## 12. Security & Access Control

| Control | Verdict |
|---------|---------|
| No partner REST "run engine" endpoint | **PASS** (FR-017) |
| Manual paths `SYSTEM_ADMIN`-only | **PASS** — `@PreAuthorize` on ops controller |
| Automated path actor = document uploader | **PASS** |
| Tenant isolation on reads; admin scope for open-loan | **PASS** — `BorrowerActiveLoanChecker.readAcrossAllLsps` |

---

## 13. Documented Prototype Gaps (Observational, Not Defects)

Aligned with spec "Current vs Target Gaps":

| ID | Observation |
|----|-------------|
| G-1 | Hard-coded rules — no runtime authoring |
| G-3 | INITIALIZED failure is silent no-op — relies on completion edge always firing from `INITIALIZED` |
| G-5 | Two `LOAN_STATUS_CHANGED` events on STP |
| G-7 | No re-decision on borrower data edits without doc re-completion |
| G-8 | Single-admin override, no maker-checker |
| G-9 | **Mitigated [DIRTY]** by H-01; open on committed HEAD |

---

## 14. Findings Register

| ID | Severity | Dirty? | Finding |
|----|----------|--------|---------|
| **W4-A11-F01** | **CRITICAL** | **YES** | D3 double-approval race (G-9) closed only by uncommitted borrower pessimistic lock + write-boundary re-check. **HEAD without dirty delta remains vulnerable.** |
| **W4-A11-F02** | MEDIUM | No | No `LoanAutoApprovalRuleEngineTest`; eight rule codes lack isolated unit coverage. |
| **W4-A11-F03** | LOW | No | FR-013 / EC-002 (`INITIALIZED` no-op on non-document rule failure at completion edge) implemented but **untested**. |
| **W4-A11-F04** | LOW | No | Automated auto-reject does not have integration test asserting `rejection_reason_json.failedRules` content. |
| **W4-A11-F05** | LOW | No | STP tests assert final `APPROVED_PENDING_DISBURSAL` but not exactly **two** transition rows / two webhooks (FR-015). |
| **W4-A11-F06** | PASS | No | Eight rules, accumulate-all, read-only engine — code matches FR-001/002. |
| **W4-A11-F07** | PASS | No | Auto-approval runs outside document-persist transaction (Issue #85). |
| **W4-A11-F08** | PASS | No | Gate status guards + `enforceAutoApprovalAllowed` prevent post-approval re-decision (Issue #135). |
| **W4-A11-F09** | PASS | No | Manual override is advisory; HIGH `MANUAL_RULE_ENGINE_OVERRIDE` alert emitted (FR-018/019, D9). |
| **W4-A11-F10** | PASS | No | KYC completion enforced before manual approval (`validateKycCompletionBeforeApproval`). |
| **W4-A11-F11** | MEDIUM | **YES** | `ensureLoanAccountForApprovedApplication` can throw `BORROWER_HAS_OPEN_LOAN` — safe due to transaction rollback, but would surface as upload-path failure if locking regressed. |
| **W4-A11-F12** | PASS | No | State machine enforced on every write; illegal transitions → `INVALID_STATUS_TRANSITION`. |
| **W4-A11-F13** | PASS | No | Open-loan statuses match spec D3 set; cross-tenant admin read via `BorrowerActiveLoanChecker`. |
| **W4-A11-F14** | PASS | No | Own-application loan account excluded (`belongsToCurrentApplication`, EC-003). |
| **W4-A11-F15** | PASS | **YES** | Concurrency tests assert 1 approved + 1 rejected + 1 open account — validates H-01 intent. **Uncommitted.** |
| **W4-A11-F16** | PASS | No | Gate metrics names and behavior match FR-020. |
| **W4-A11-F17** | LOW | **YES** | Concurrent loser `REJECTED` outcome not asserted to carry `BORROWER_HAS_OPEN_LOAN` in `rejection_reason_json`. |
| **W4-A11-F18** | INFO | No | Interest rate not re-checked at decision (EC-009) — intentional, intake-only. |
| **W4-A11-F19** | INFO | No | No re-evaluation on material data change without doc re-completion (G-7) — intentional prototype behavior. |
| **W4-A11-F20** | PASS | No | `LoanApplicationStatusWriter` is sole mutation owner for status + transition + audit + webhook per hop. |
| **W4-A11-F21** | PASS | **YES** | `LoanApplicationStatusWriterTest` verifies idempotent account replay and second-account refusal — **uncommitted**. |
| **W4-A11-F22** | INFO | No | Manual override on targets other than approval still runs advisory engine (per FR-019). |
| **W4-A11-F23** | INFO | No | `gateFired` counter not coupled to successful decision completion. |

---

## 15. Conclusion & Recommendations

### Conclusion

For **D2 fully automated credit**, the implementation is a faithful execution of the spec's rule catalog, gate semantics, three-outcome executor, and auditable single-writer pattern. Human override correctly records advisory engine verdict without blocking (G-8 acknowledged). The **critical production gap is D3 concurrency (G-9)**, which the **uncommitted H-01 dirty work materially addresses** via borrower-row pessimistic locking and a second open-loan check at loan-account creation.

### Recommendations (priority order)

1. **[DIRTY — commit H-01]** Land `findBorrowerByApplicationIdForUpdate`, lifecycle lock, status-writer guard, and concurrency tests before any pilot requiring D3 enforcement.
2. **Add `LoanAutoApprovalRuleEngineTest`** with parameterized cases for all eight `RuleCode` values, multi-failure accumulation, and boundary conditions (EC-004..006, EC-010).
3. **Add completion-edge integration test** for EC-002: complete docs on `INITIALIZED` app with e.g. deactivated LSP → status stays `INITIALIZED`, no reject row.
4. **Assert automated rejection audit**: transition row `rejection_reason_json.failedRules` and `FAILED_VERIFICATION` on rule-fail from `AWAITING_APPROVAL`.
5. **Strengthen concurrency assertion**: verify loser's `rejection_reason_json` contains `BORROWER_HAS_OPEN_LOAN`.
6. **Optional:** assert two transition rows + two webhook outbox entries on STP document-upload path (FR-015).

---

**Audit artifact:** Wave 4 Agent A11 | IDs `W4-A11-F01`–`W4-A11-F23` | Dirty-worktree-dependent: F01, F11, F15, F17, F21

[REDACTED]