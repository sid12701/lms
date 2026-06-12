# ADR 0004 — Processing fee is deducted at disbursement; borrower receives net cash, owes gross principal

- **Status:** Proposed (2026-06-08)
- **Drives:** #160 ([R-5] rescoped — processing fee deduction at disbursement + persisted fee + MIS parity)
- **Related:** #66 (Payments revamp — allocation rows, deferred), ADR 0001 (frontend integration model), ADR 0003 (LSP origination is API-only)

## Context

`LoanProduct.processingFeeRate` exists on every loan product (`NUMERIC(5,2)`, validated `[0.00, 100.00]`, interpreted as a percentage). The MIS report (`AdminReportingService.PortfolioMisRow.processingFeeAmount`) computes `principalAmount × processingFeeRate / 100` and surfaces it as the fee charged.

Code inspection (2026-06-08) confirms this is a fiction. `LoanDisbursementService` does not deduct, charge, or persist any processing fee. Borrowers receive the full requested principal; lenders never retain a fee at the disbursement event; no `processing_fee_amount` column exists on `disbursement` or `loan_account`. The MIS row's "processing fee" is a synthetic re-derivation that auditors are likely to read as money actually charged.

Audit ticket #160 was originally scoped as "add a parity test between the report row and the canonical fee calculator." Locking the synthetic figure with a parity test pins a number that doesn't correspond to any real cash flow. The right fix is to make the system actually charge the fee, then make the report read what was charged.

Three product-level cash-flow models exist and produce different code paths:

- **Model 1 — Net cash, gross principal.** Borrower applies for `P`. Receives `P − fee` cash. Owes principal `P`. Schedule, EMI, interest, foreclosure computed on `P`.
- **Model 2 — Net cash, net principal.** Borrower receives `P − fee` cash. Owes principal `P − fee`. Schedule, EMI, interest, foreclosure shrink with the fee.
- **Model 3 — Gross cash, fee billed separately.** Borrower receives `P` cash. Owes principal `P`. Fee is billed as a separate line item (added to the first EMI, or invoiced).

## Decision

Adopt **Model 1**.

1. `LoanFeeCalculator.computeProcessingFee(principal, processingFeeRate) → BigDecimal` (scale 2, HALF_UP) is the single source of truth for the fee amount.
2. `LoanDisbursementService` computes the fee at disbursement, **persists** it on `loan_account.processing_fee_amount`, and reduces the cash-to-borrower amount by the fee. The lender retains the fee.
3. The principal recorded against the loan stays the **requested** amount. Repayment schedule, EMI, interest accrual, and foreclosure quote are all computed on the requested principal and are **unchanged** by this ADR.
4. The MIS report (`AdminReportingService`) reads the persisted `processing_fee_amount` for newly-disbursed loans. For legacy loans (column is null), it falls back to `LoanFeeCalculator.computeProcessingFee(...)` using the current product config — preserving today's MIS output verbatim. **No backfill** of historical loans.
5. GST on the processing fee is **out of scope** for this ADR. The fee amount persisted and surfaced is pre-tax. A separate ticket and ADR are required if/when GST capture is added.

## Rationale

1. **Model 1 is the dominant retail-lending convention in the Indian market.** Borrowers expect to receive principal-minus-fee at disbursement and to repay against the loan size they applied for. Adopting it requires the smallest behavioural shift relative to borrower expectations.
2. **Model 1 leaves repayment math untouched.** Schedule generator, EMI, interest accrual, and foreclosure quote all read principal. Because the recorded principal does not change under Model 1, those code paths do not move. Model 2 would force changes to every one of them and dramatically widen the blast radius.
3. **Forward-only persistence avoids backfill risk.** Historical loans have a real disbursement event in the past; we cannot retroactively "charge" a fee that wasn't charged. Backfilling `processing_fee_amount` from `principal × rate / 100` would either record a fee the borrower never paid (false) or require a corresponding cash-flow correction (impossible after the fact). Falling back to the calculator at read time in MIS preserves today's report output for legacy rows with zero schema churn.
4. **A single calculator removes the silent-drift class of bugs.** Without `LoanFeeCalculator`, two divergent fee formulas can coexist (report-side and disbursement-side) and silently diverge. Routing both through one method makes drift impossible by construction and makes the units (percentage, scale, rounding) explicit in one place.
5. **GST is a tax-policy decision, not an architecture decision.** Bundling GST into this ADR couples a tax question to a cash-flow question. Keeping them separate lets product/finance decide the tax treatment independently without re-litigating Model 1.
6. **The "P2 / correctness / parity test" framing of #160 was wrong.** The audit doc treated this as a small reporting ticket. The real underlying problem is a missing feature (fee deduction). The rescope is a correction to the audit framing, not a scope creep.

## Consequences

### Borrower-facing
- Borrowers disbursed after this change receive `principal × (1 − rate/100)` in cash.
- Sanction letter / loan agreement copy must disclose the deduction. Implementation PR must verify and update; production rollout is blocked until the disclosure is correct.
- Repayment terms, EMI, foreclosure semantics are unchanged.

### LSP-facing
- LSP API disbursement response should surface `processingFeeAmount` and `netDisbursedAmount` so partners' downstream accounting matches what the borrower received. May require a contract version bump. Tracked as a follow-up under #160's implementation PR or as a separate ticket; not blocked by this ADR.
- `LOAN_DISBURSED` webhook payload should likewise carry fee and net cash. Same handling.

### System-facing
- `loan_account.processing_fee_amount NUMERIC(...)` nullable column added by Flyway migration. Newly-disbursed loans persist a value. Legacy rows stay null.
- `AdminReportingService` reads the persisted column when non-null, computes via the calculator when null. Today's MIS output for historical loans is unchanged.
- Disbursement reversal restores both principal and fee. The reversal path must zero the persisted `processing_fee_amount` (or otherwise reflect the reversal); pinned with a regression test.
- `LoanFeeCalculator` becomes the only place that converts (principal, rate) to fee. Inline math in `AdminReportingService` is deleted.

### Out of scope
- GST on the fee.
- Fee waivers, promotional zero-fee overrides, fee-at-disbursement-vs-fee-at-approval policy.
- Backfill of historical loans.
- Frontend changes beyond surfacing the new field if/when the disbursement-response DTO grows it.

## Trigger to re-open

This decision must be revisited (and superseded by a new ADR) if any of the following holds:

1. **Product wants Model 2 or Model 3.** Schedule, EMI, interest, foreclosure code paths all branch on the cash-flow model. Switching models silently — without an ADR — would corrupt every loan disbursed under the old model. The ADR must be superseded *before* code changes.
2. **GST capture is required.** A separate ADR commits to whether GST is computed on the fee, persisted alongside it, surfaced on the disbursement response, and reported separately in MIS.
3. **Backfill is required.** If audit or regulator requires that historical loans show a deducted fee, the design changes substantially (correction entries, historical cash-flow reconciliation) and warrants its own ADR.
4. **#66 (Payments revamp) lands and introduces per-installment allocation rows that already account for fees.** Per the audit doc's note on #66, MIS could then read fee parity directly from allocation rows; the persisted column may become redundant or reframe as a denormalisation.
