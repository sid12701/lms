# Wave 5 Agent A14 — Bank-Grade Servicing Auditor (READ-ONLY)

**Agent:** W5-A14  
**Date:** 2026-07-30  
**Baseline:** Specs under `/Users/siddhant/Desktop/work/ferratum-products-specs-res/areas/bhawana/servicing/` vs LMS code at `/Users/siddhant/Desktop/lms/backend`  
**Prior findings cross-checked:** W1-F01 (foreclosure null payment idempotency), deferred S13 receipt ledger

---

## 1. Executive Summary

Bhawana servicing is a **coherent prototype** for schedule generation, exact-EMI posting, foreclosure quote/execute, DPD exposure, and bank-detail guards — but it is **not bank-grade** for production collections or terminal-closure safety.

**Strengths:** D6 (full-EMI-only) is enforced with pessimistic installment locking and concurrency tests; D7 (schedule freeze) is enforced at `PENDING_DISBURSEMENT` plus zero payments; LSP bank-detail updates block in-flight disbursement and post-disbursal partner edits; DPD math is consistent between Java and SQL; LSP foreclosure HTTP idempotency replays correctly.

**Critical gaps:** Foreclosure execution posts `loan_payment_transaction` rows with **`idempotency_key = null`** and **no quote/account row lock**, creating race surfaces absent from EMI posting. Stale foreclosure quotes can execute at superseded settlement amounts after intervening EMI payments. Deferred **S13** leaves no receipt ledger — foreclosure allocation via full reset/recompute is not SoR-grade.

**Verdict:** Acceptable for synthetic UAT under exact-EMI assumptions; **not ready** for real-money servicing, concurrent ops/LSP foreclosure, or collections system-of-record without remediation.

---

## 2. Audit Charter

| Item | Detail |
|------|--------|
| **In scope** | 6 servicing specs + S13 deferral + W1 idempotency finding |
| **Code paths** | `LoanRepaymentScheduleService`, `LoanRepaymentCommandService`, `LoanForeclosureCommandService`, `LoanServicingSupportService`, `LoanDelinquencySupport`, `AlertRuleEvaluationWorker`, `BorrowerBankDetailsService`, controllers |
| **Method** | Spec-to-code trace, call-path analysis, edge-case reasoning, test inventory |
| **Out of scope** | Disbursement worker internals (except bank-detail interaction), webhook delivery, frontend except role gates |

---

## 3. Spec Traceability Matrix

| Spec area | Spec status | Implementation | Match |
|-----------|-------------|----------------|-------|
| Loan account & schedule | Engineering Reviewed | `LoanRepaymentScheduleService`, `LoanApplicationStatusWriter` | **High** — amortisation, D7 lock, validation |
| Repayment posting & closure | Engineering Reviewed | `LoanRepaymentCommandService` | **High** — D6, idempotency, closure |
| Foreclosure quote & validity | Engineering Reviewed | `LoanForeclosureCommandService.request*` | **High** — supersession, no expiry (as documented) |
| Foreclosure execution | Engineering Reviewed | `LoanForeclosureCommandService.execute*` | **Medium** — logic matches; concurrency weak |
| DPD & alerts | Engineering Reviewed | `LoanDelinquencySupport`, `AlertRuleEvaluationWorker` | **High** — with DISBURSED blind spot |
| Borrower bank details | Engineering Reviewed | `BorrowerBankDetailsService` | **High** — S5 guards implemented |
| S13 receipt ledger | **Deferred 2026-07-15** | Not implemented | **N/A** — accepted residual risk |
| W1 foreclosure idempotency | High finding | Still present | **Open** |

---

## 4. Schedule & Account Foundation (D7)

### Spec requirements (D7)
Schedule replaceable only while account is `PENDING_DISBURSEMENT` and **no payment posted**.

### Implementation
`/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanRepaymentScheduleService.java` lines 151–162:

- Rejects replace if `loanAccount.status != PENDING_DISBURSEMENT` → `REPAYMENT_SCHEDULE_LOCKED`
- Rejects if `loanPaymentTransactionRepository.existsByLoanAccount_Id(...)` → `REPAYMENT_SCHEDULE_LOCKED`

**Stronger than spec wording:** `DISBURSEMENT_REQUESTED` also blocks replace (status ≠ `PENDING_DISBURSEMENT`). Correct for D7 intent.

### Schedule generation
- First due = `approved_at` (UTC) + 1 month — not disbursement date (spec FR-009)
- Gross principal amortisation (D5) — fee not deducted from schedule
- `generateIfAbsent` is idempotent

### Assessment
**D7: PASS** for documented behaviour. No evidence of post-disbursement schedule mutation paths.

---

## 5. Full-EMI Payment Posting (D6)

### Spec requirements (D6)
Posted amount must **exactly equal** target installment `outstanding_amount`; partial payments rejected.

### Implementation chain
1. `validateExactInstallmentAmount` — `PAYMENT_AMOUNT_MISMATCH` if mismatch  
2. `resolveTargetInstallmentForUpdate` — `SELECT … FOR UPDATE` pessimistic lock  
3. `applyFullInstallmentPayment` — throws if installment not fully `PAID` with zero outstanding  
4. Idempotency: UUID v4 required; fingerprint over application, installment, amount, date, reference, channel  
5. Concurrency: `LoanRepaymentConcurrencyIntegrationTest` — two different keys on same installment → exactly one success, one `INSTALLMENT_ALREADY_PAID`

### Domain nuance
`LoanRepaymentScheduleInstallmentStatus.PARTIALLY_PAID` exists and `applyPayment` can set it — but **API path never allows partial posting**. Foreclosure `recomputePaymentAllocation` can transiently create partial states during multi-installment waterfall; not exposed on EMI API.

### Assessment
**D6: PASS** on partner and admin EMI posting paths. Spec-aligned.

### Spec deviation
`LoanApplicationOpsController` line 545: `@PreAuthorize("hasRole('SYSTEM_ADMIN')")` on `POST …/payments`. Spec FR-002 says **OPS_USER** may post. Frontend `canPostRepayment` includes OPS_USER → **403 at runtime** (see W5-A14-F13).

---

## 6. Foreclosure Quote Generation & Validity

### Implemented behaviour (matches spec)
- Quote = sum unpaid principal + unpaid interest across schedule  
- Supersedes prior `ACTIVE` quotes; monotonic version per account  
- Rejects zero/negative settlement (`LOAN_ALREADY_SETTLED`)  
- **No expiry window** — validity = `ACTIVE` status + settlement date = effective date at execute  
- Effective date does **not** change calculated amount (spec G-3 documented)

### Gaps vs production target (spec-acknowledged, still audit-relevant)
- No quote-request idempotency — retries create new versions  
- No audit event on quote request (only webhook)  
- Future/past effective dates allowed on quote request (no `@PastOrPresent`)

### Assessment
**Spec fidelity: HIGH.** Production readiness: **LOW** due to indefinite quote freshness and amount staleness (see §8).

---

## 7. Foreclosure Execution & Terminal Closure

### Happy path (spec-aligned)
`LoanForeclosureCommandService.executeForeclosureQuote`:

1. Validates account `DISBURSED`, quote `ACTIVE`, settlement date = effective date  
2. Creates `FORECLOSURE_SETTLEMENT` payment at **stored quote amount** (not caller amount)  
3. `recomputePaymentAllocation` — resets all installments, reapplies all `RECEIVED` payments in `payment_date, created_at` order  
4. Requires `allInstallmentsSettled`  
5. Marks quote `EXECUTED`, closes account `FORECLOSED` / reason `FORECLOSURE`, application `FORECLOSED`  
6. Audit `FORECLOSURE_EXECUTED` + webhook `LOAN_FORECLOSURE_COMPLETED`

### LSP path
- Mandatory HTTP idempotency via `lspApiIdempotencyService` — `Issue74LspForeclosureExecuteIntegrationTest` confirms replay returns cached response and **one** settlement payment row

### Internal admin path
- HTTP idempotency **optional** (`LoanApplicationOpsController` 587–588) — without key, retry after timeout relies on quote no longer `ACTIVE`

### Assessment
**Functional closure logic: PASS** for single-threaded happy path. **Terminal safety: FAIL** under concurrency (§8).

---

## 8. Concurrency, Idempotency & Race Analysis

### W1-F01 CONFIRMED — Foreclosure payment null idempotency key

```249:261:/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LoanForeclosureCommandService.java
        loanPaymentTransactionRepository.save(new LoanPaymentTransaction(
                loanAccount,
                null,
                normalizedActorUsername,
                quote.getSettlementAmount(),
                settlementDate,
                requiredReference,
                LoanPaymentChannel.FORECLOSURE_SETTLEMENT,
                LoanPaymentStatus.RECEIVED,
                resolvedNote == null ? "Foreclosure settlement for quote v" + quote.getVersion() : resolvedNote,
                CorrelationIdHolder.get(),
                null
        ));
```

- EMI payments require non-null UUID v4 idempotency (`LoanServicingSupportService.requireIdempotencyKey`)  
- DB unique constraint on `idempotency_key` (V92) allows **multiple NULL rows** — duplicate foreclosure settlement payments are possible  
- LSP HTTP idempotency prevents **same-key** replay duplicates but does not propagate key to payment row — payment layer cannot dedupe independently

### Race scenarios

| Scenario | Protection today | Risk |
|----------|------------------|------|
| LSP retry same idempotency key | LSP idempotency record | **Low** |
| Admin double-submit without key | Quote → `EXECUTED` only after commit | **Medium** — window before commit |
| Two concurrent admin executes (no keys) | None on quote row | **High** — duplicate settlement payments |
| Foreclosure + concurrent EMI post | EMI locks installment; foreclosure locks nothing | **High** — `recomputePaymentAllocation` resets all installments |
| Quote request during EMI payment | No lock | **Medium** — stale quote amount |
| Execute stale quote after EMI paid | No re-validation of quote amount vs live outstanding | **High** — overpayment accepted |

### Stale quote overpayment (detailed)
1. Quote generated: settlement = ₹100,000  
2. Borrower pays one EMI (₹10,000) — outstanding drops  
3. Execute quote still pays ₹100,000  
4. `recomputePaymentAllocation` settles all installments; **surplus remains `unallocated_amount` on foreclosure payment**  
5. `allInstallmentsSettled` checks installment outstanding only — **execution succeeds with silent surplus**

No ops alert, no partner signal, no suspense account (S13 deferred).

### Assessment
**Foreclosure races: FAIL bank-grade bar.** W1 finding remains open and is the highest-priority servicing defect.

---

## 9. DPD Bucketing & Delinquency Alerts

### Calculation (`LoanDelinquencySupport`)
- DPD = 0 if `outstanding_amount <= 0` OR `due_date >= today`  
- Else calendar days `due_date → today`  
- Loan bucket = worst installment DPD  
- Overdue amount = sum outstanding where DPD > 0

### SQL scheduler (`AlertRuleSetQueryRepository.findServicingDelinquencyRows`)
- Same predicates: `outstanding_amount > 0 AND due_date < :today`  
- PostgreSQL DPD: `(cast(:today as date) - inst.due_date)` — consistent with Java `ChronoUnit.DAYS.between`

### Alert rule (`DPD_BUCKET_TRANSITION`)
- Scans **`UNDER_REPAYMENT` only**  
- Alerts on **worsening** bucket ordinal vs `loan_delinquency_state`  
- Duplicate open alerts suppressed via `createAlertIfAbsent`  
- State refreshed when bucket or max DPD changes

### Correctness gaps

| Gap | Impact |
|-----|--------|
| `DISBURSED` loans excluded from scheduler | First EMI can be past-due before first payment advances status — **no DPD alert** |
| No cure/downgrade alert | Spec-acknowledged; bucket improvement silent |
| `dpdBucketTransitionCounter` increments even when alert suppressed | Metric inflation |
| No holiday/grace period | Calendar-day DPD only — spec G-8 |

### Assessment
**Read-path DPD: PASS.** **Alert completeness: MEDIUM** — blind spot for pre-first-payment delinquency.

---

## 10. Borrower Bank Details & Disbursement Interaction (S5)

### Spec / implementation alignment
`BorrowerBankDetailsService.assertBankDetailsUpdatable`:

1. **Blocks all updates** when borrower has account in `DISBURSEMENT_REQUESTED` or `DISBURSEMENT_PENDING_RECONCILIATION` → `BANK_DETAILS_LOCKED_DISBURSEMENT_IN_FLIGHT`  
2. **LSP path** requires pre-disbursal application (`INITIALIZED`, `AWAITING_APPROVAL`, `APPROVED_PENDING_DISBURSAL`, `DISBURSEMENT_RETRY`) → `BANK_DETAILS_UPDATE_NOT_ALLOWED` post-disbursal  
3. **Admin path** allows post-disbursal maintenance when not in-flight  
4. Audit row on every update; LSP webhook with masked account numbers  
5. Velocity alert on threshold

### Test evidence
`Issue62BorrowerBankDetailsIntegrationTest` — post-disbursal LSP blocked, in-flight LSP/admin blocked, admin post-disbursal allowed.

### Residual TOCTOU
Disbursement preflight validates bank details at request time; worker reads **current** borrower row at payout. In-flight guard prevents concurrent bank edits during `DISBURSEMENT_REQUESTED`. **Residual risk is low** for documented flows; no optimistic version on `borrower` row.

### Assessment
**S5 in-flight guard: PASS.** Admin post-disbursal edit policy is intentional (spec G-1) but needs operational governance.

---

## 11. Financial Dating & Business Calendar

| Surface | Dating rule | Implementation |
|---------|-------------|----------------|
| EMI `postedAt` | `@PastOrPresent` | `LoanApplicationOpsApiTypes`, `LspLoanApiController` |
| Foreclosure `settlementDate` | `@PastOrPresent` | Same |
| Foreclosure quote `effectiveDate` | **Unconstrained** | No past/future validation |
| DPD `today` | Business date | `BusinessCalendar.today()` = `LocalDate.now(clock)` — typically UTC |
| Schedule first due | Approval + 1 month | `approvalDate(loanAccount)` from `approved_at` UTC |
| Payment allocation order | `payment_date ASC, created_at ASC` | `recomputePaymentAllocation` — backdated EMI affects waterfall |
| Account closure timestamp | `Instant.now()` | Wall clock, not business date |

### Issues
- **Asymmetric dating:** payments cannot be future-dated; quotes can use future effective dates (execute blocked until that date matches)  
- **Timezone:** `approved_at` → `LocalDate` via UTC; payment `LocalDate` has no zone — India business-date alignment depends on server `Clock` configuration  
- **No value-date / accrual cut-off** for foreclosure interest — quote amount frozen at request instant

### Assessment
**Adequate for prototype.** Not adequate for regulated EOD/NPA dating without explicit IST business calendar and accrual rules.

---

## 12. Deferred S13 Receipt Ledger Impact

Per `/Users/siddhant/Desktop/lms/docs/deferred-implementation.md` (deferred 2026-07-15):

**Not implemented:**
- `payment_receipt` / `receipt_allocation` / `receipt_reversal` tables  
- Partial/multi-EMI posting, suspense, bounce/reversal  
- Immutable allocation history

**Current coupling:**
- EMI: direct installment mutation + `loan_payment_transaction` aggregate allocated/unallocated  
- Foreclosure: **full reset + recompute** across all payments — fragile, non-auditable allocation trail  
- D1 (LMS as SoR) is **product intent, not runtime fact**

**Foreclosure-specific S13 risk:** Recompute can mask overpayment as `unallocated_amount` on payment row with no ledger entry. Bounce/reversal would require reopening `FORECLOSED` loans — no path exists.

### Assessment
**Accepted deferral for UAT.** **Blocker** for production collections and bank reconciliation.

---

## 13. Test Coverage & Evidence Gaps

| Area | Covered | Missing |
|------|---------|---------|
| EMI concurrency (different keys) | `LoanRepaymentConcurrencyIntegrationTest` | Multi-replica same-key |
| LSP foreclosure idempotency | `Issue74LspForeclosureExecuteIntegrationTest` | — |
| Admin foreclosure without idempotency key | Controller tests | Concurrent double-execute |
| Foreclosure + EMI race | — | **No test** |
| Stale quote after EMI | — | **No test** |
| DPD bucket transitions | `AlertRuleEvaluationWorkerDpdBucketTransitionIntegrationTest` | DISBURSED overdue blind spot |
| Bank details S5 guards | `Issue62BorrowerBankDetailsIntegrationTest` | TOCTOU preflight→worker |
| Schedule D7 lock | `LoanRepaymentScheduleServiceTest` | — |
| S13 ledger | N/A (deferred) | — |

---

## 14. Findings Register

| ID | Sev | Area | Finding | Evidence |
|----|-----|------|---------|----------|
| **W5-A14-F01** | **Critical** | Foreclosure / W1 | Foreclosure settlement persists `loan_payment_transaction` with **`idempotency_key = null`**, bypassing payment-layer deduplication | `LoanForeclosureCommandService.java:249-261`; W1-PLATFORM-FOUNDATIONS W1-F01 |
| **W5-A14-F02** | **Critical** | Foreclosure races | No pessimistic lock on `loan_foreclosure_quote` or `loan_account` during execute; concurrent admin executes (no HTTP idempotency key) can create **duplicate settlement payments** | `executeForeclosureQuote` — no `findByIdForUpdate`; contrast `resolveTargetInstallmentForUpdate` |
| **W5-A14-F03** | **Critical** | Foreclosure vs EMI | Foreclosure `recomputePaymentAllocation` resets all installment paid fields without account-level lock; can interleave with concurrent EMI post | `LoanServicingSupportService.recomputePaymentAllocation:240-275` |
| **W5-A14-F04** | **High** | Foreclosure staleness | Active quote executes at **stored settlement amount** without re-checking live outstanding after intervening EMI — **overpayment + unallocated surplus** accepted | Execute path; `allInstallmentsSettled` ignores payment unallocated |
| **W5-A14-F05** | **High** | DPD | Scheduler scans **`UNDER_REPAYMENT` only** — `DISBURSED` loans past first due date get **no DPD alert** until first EMI posted | `AlertRuleSetQueryRepository:126`; spec EC-006 |
| **W5-A14-F06** | **High** | S13 deferred | No receipt ledger — foreclosure allocation via full recompute is not SoR-grade; no reversal/bounce/suspense path | `docs/deferred-implementation.md:43-54` |
| **W5-A14-F07** | **Medium** | Quote validity | No quote expiry; `effectiveDate` unconstrained — indefinite `ACTIVE` quotes with stale amounts | Spec G-1, G-3; no backend expiry |
| **W5-A14-F08** | **Medium** | Financial dating | Asymmetric rules: EMI/settlement `@PastOrPresent`; quote `effectiveDate` unrestricted; closure uses `Instant.now()` not business date | `LoanApplicationOpsApiTypes` |
| **W5-A14-F09** | **Medium** | Foreclosure surplus | Foreclosure overpayment leaves **`unallocated_amount > 0`** on payment row with no ops alert or partner signal | `recomputePaymentAllocation` + `allInstallmentsSettled` |
| **W5-A14-F10** | **Medium** | Admin idempotency | Internal foreclosure execute: HTTP idempotency **optional** — admin timeout retry without key races on quote status only | `LoanApplicationOpsController:587-588`; spec G-5 |
| **W5-A14-F11** | **Low** | DPD metrics | `lms.dpd.bucket_transition` counter increments on bucket worsening even when duplicate alert suppressed | `AlertRuleEvaluationWorker:244` before `created != null` check |
| **W5-A14-F12** | **Low** | Quote audit | Quote request writes no `loan_application_audit_event` (spec FR-021) — weaker D9 trail for quote generation | Spec vs `requestForeclosureQuote` |
| **W5-A14-F13** | **Low** | RBAC | Spec grants OPS_USER repayment posting; controller is **SYSTEM_ADMIN only** — UI/backend mismatch | `LoanApplicationOpsController:545` vs spec FR-002 |

### Positive controls (no finding ID — documented for balance)

- **D6 exact EMI:** enforced with lock + integration test  
- **D7 schedule freeze:** status + payment existence check  
- **LSP bank in-flight lock:** tested  
- **LSP foreclosure HTTP idempotency:** tested replay  
- **DPD math consistency:** Java ↔ SQL aligned  

---

## 15. Recommendations & Remediation Priority

### P0 — Before real-money / concurrent foreclosure

1. **W5-A14-F01/F02:** Propagate idempotency key (or deterministic settlement key) to foreclosure payment row; add `SELECT … FOR UPDATE` on quote (and ideally loan account) before execute; reject execute if quote version ≠ latest ACTIVE or live outstanding ≠ quote amount (within tolerance).  
2. **W5-A14-F03:** Serialize foreclosure execute against EMI posting — account-level advisory lock or `findByIdForUpdate` on `loan_account` for duration of execute transaction.  
3. **W5-A14-F04/F09:** At execute, recompute expected settlement from live schedule; reject or auto-supersede if mismatch; surface unallocated surplus as hard failure or suspense (pending S13).

### P1 — Before collections SoR / NPA operations

4. **W5-A14-F06:** Resume S13 per `docs/deferred-implementation.md` — decouple receipt from installment mutation; retrofit foreclosure to allocate via ledger.  
5. **W5-A14-F05:** Extend DPD scheduler to `DISBURSED` OR advance to `UNDER_REPAYMENT` on disbursement when first due ≤ today.  
6. **W5-A14-F10:** Make admin foreclosure idempotency **mandatory** (mirror LSP).

### P2 — Hardening / compliance

7. **W5-A14-F07/F08:** Quote expiry window; `@PastOrPresent` on effective date; IST business calendar for DPD and closure.  
8. **W5-A14-F13:** Align OPS_USER payment RBAC with spec or update spec + UI.  
9. Add integration tests for F02–F04 race scenarios.

---

**Audit conclusion:** Servicing implementation **faithfully documents prototype behaviour** in the six specs, with D6/D7 and S5 bank guards as standouts. **Bank-grade bar is not met** due to foreclosure payment/idempotency gaps (W1 confirmed), race surfaces, stale-quote financial integrity, DISBURSED DPD blind spot, and deferred S13 receipt ledger. Remediate P0 items before any live foreclosure or concurrent ops/LSP servicing traffic.

[REDACTED]