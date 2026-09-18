# ADR 0009 — Foreclosure settlement boundary

- **Status:** Accepted (2026-09-18)
- **Source:** Consolidated audit `docs/audits/lms-consolidated-audit-and-fix-specs-2026-09-06.md` — C05 (stale quotes and excess settlement), H09 (foreclosure reallocates earlier receipts), shared contract B (financial settlement boundary)
- **Related:** ADR 0006 (migration discipline), `LoanForeclosureCommandService`, `LoanServicingSupportService`, migration V130

## Context

A foreclosure quote is a balance snapshot. Execution checked only that the quote was `ACTIVE` and
that the supplied settlement date equalled the quote's effective date. Neither proved the balance
was still the one quoted, and no lock serialized the command, so:

- a receipt arriving between quote and execution left the quote redeemable at its old, larger
  amount — the loan closed and the excess was booked as unallocated money;
- two executions of one quote had nothing stopping them but timing;
- execution reset every installment allocation and replayed the whole payment history across the
  schedule, so a receipt recorded against installment 3 could end up represented against
  installment 1.

## Decision

1. **Freshness is a fingerprint, not a counter.** Under the loan lock, execution recomputes
   outstanding principal, outstanding interest and settlement amount from the current schedule and
   compares them exactly to the quote's own stored snapshot. A mismatch is
   `FORECLOSURE_QUOTE_STALE` and the caller must request a new quote. No revision column is added:
   a quote's stored amounts already are its fingerprint.

2. **Legacy quotes are superseded, not evaluated.** A quote issued before V130 was never checked
   for freshness or date validity and has no execution fingerprint, so V130 supersedes every
   still-`ACTIVE` quote instead of inferring that its history can be trusted. The borrower
   requests a new quote under the rules below.

3. **Interim validity is same-day only (fail closed).** There is no expiry window yet. A quote can
   only be requested for the current business date (`BusinessCalendar`, Asia/Kolkata), and it can
   only be executed on that same date with `settlementDate == effectiveDate`; anything else is
   `FORECLOSURE_QUOTE_DATE_INVALID` or `SETTLEMENT_DATE_MISMATCH`. Backdated and future-dated
   quotes are therefore refused. This holds until the H08 pricing/validity policy (D8) defines a
   real validity window. Pricing is a separate concern, and this ADR does not change how a
   quote amount is computed.

4. **Settlement is exact; there is no excess route.** The recorded receipt is always the quoted
   amount, and it is only recorded once freshness has passed — so it settles the schedule exactly.
   There is no caller-supplied amount and no over-receipt, refund or excess machinery.

5. **One quote backs at most one settlement, and replay is decided by the settlement.** The
   settlement receipt carries `loan_payment_transaction.foreclosure_quote_id` under a partial
   unique index (V130), plus a fingerprint of the execution request (quote, settlement date,
   reference, channel). Executing an already-executed quote with the same request returns the
   original result with no new writes; any other request is `IDEMPOTENCY_CONFLICT`. Because this
   is read from the receipt rather than an HTTP idempotency cache, it holds for direct callers
   and after cache retention expires. Historical settlements are left `NULL` — a link inferred
   from amount and date coincidence would be invented evidence.

6. **Payment history is never replayed.** Foreclosure allocates only its own receipt across the
   installments that are still unpaid, in installment-number order. Earlier receipts keep their
   installment targeting and their rows are not rewritten. `amount = allocated + unallocated` and
   the principal/interest components are conserved.

7. **One lock order:** application → account → installments (installment-number order) → quote.
   The quote request takes the first three (it supersedes quotes under the account lock), and the
   execution takes all four. This is also what makes quote superseding atomic — concurrent
   requests cannot leave two `ACTIVE` quotes behind.

## Consequences

- A client that lets a quote go stale, or tries to use it on another day, gets a 422 and must
  re-quote. This is the intended cost; the alternative was settling loans for the wrong amount.
- Any quote that was `ACTIVE` at deploy time must be requested again.
- `recomputePaymentAllocation` is removed rather than left unused: a routine that rewrites
  financial history is not a tool to keep around for the next caller.
- Reconciliation, not migration, owns pre-V130 settlements: they are identifiable as
  `FORECLOSURE_SETTLEMENT` receipts with no quote link. Allocation damage left by the old replay
  is inventoried by `LoanPaymentTransactionRepository.findReceiptsExceedingTheirInstallmentPayment`
  (targeted receipts whose installment was paid less than they claim) and is never repaired
  automatically.
