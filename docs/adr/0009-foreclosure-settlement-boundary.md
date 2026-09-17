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
   `FORECLOSURE_QUOTE_STALE` and the caller must request a new quote. No revision column is added
   and no quote is superseded by migration: a quote's stored amounts already are its fingerprint,
   which is what makes quotes written before this decision safe to evaluate rather than guess at.

2. **Expiry stays the date equality that already existed.** `settlementDate == effectiveDate` is
   the validity policy. Pricing (H08) is a separate concern and this ADR does not touch how a
   quote amount is computed.

3. **Settlement is exact; there is no excess route.** The recorded receipt is always the quoted
   amount, and it is only recorded once freshness has passed — so it settles the schedule exactly.
   There is no caller-supplied amount and no over-receipt, refund or excess machinery.

4. **One quote backs at most one settlement.** The settlement receipt carries
   `loan_payment_transaction.foreclosure_quote_id` under a partial unique index (V130). The service
   also refuses a non-`ACTIVE` quote; the index is the guarantee that survives any service-layer
   race. Historical settlements are left `NULL` — a link inferred from amount and date coincidence
   would be invented evidence.

5. **Payment history is never replayed.** Foreclosure allocates only its own receipt across the
   installments that are still unpaid, in installment-number order. Earlier receipts keep their
   installment targeting and their rows are not rewritten. `amount = allocated + unallocated` and
   the principal/interest components are conserved.

6. **One lock order:** application → account → quote → installments (installment-number order).
   Both the quote request and the execution take it, which is also what makes quote superseding
   atomic — concurrent requests cannot leave two `ACTIVE` quotes behind.

## Consequences

- A client that lets a quote go stale gets a 422 and must re-quote. This is the intended cost;
  the alternative was settling loans for the wrong amount.
- `recomputePaymentAllocation` is removed rather than left unused: a routine that rewrites
  financial history is not a tool to keep around for the next caller.
- Reconciliation, not migration, owns pre-V130 settlements: they are identifiable as
  `FORECLOSURE_SETTLEMENT` receipts with no quote link.
