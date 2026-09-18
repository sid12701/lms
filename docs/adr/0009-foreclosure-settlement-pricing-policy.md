# ADR 0009 — Foreclosure settlement pricing policy

- **Status:** **DRAFT / Proposed (2026-09-18) — not accepted, not implementable.** This ADR does not decide the formula. It enumerates the decisions the product owner must sign off, states what the code does today for each, and names the code-level consequence of each option. Nothing here may be read as an approved policy, and no formula change may land until this ADR is superseded by an Accepted revision carrying filled-in golden vectors.
- **Drives:** audit finding H08 (foreclosure pricing has no explicit accrued-interest policy) — `docs/audits/lms-consolidated-audit-and-fix-specs-2026-09-06.md`, `docs/audits/lms-agent-implementation-spec-2026-09-06.md`
- **Related:** C05 (foreclosure execution accepts stale quotes and excess settlement), H06 (repayment and settlement do not serialize changes to the whole loan), H09 (foreclosure reallocates earlier receipts away from their installment targets), ADR 0004 (processing fee deducted at disbursement), ADR 0003 (LSP origination is API-only)
- **Companion:** `docs/audits/h08-foreclosure-policy-questions-2026-09-18.md` — the same decisions in business language for the product owner.

## Context

A borrower may settle a loan early. Internal ops (`SYSTEM_ADMIN`) or an LSP requests a **foreclosure quote** for an **effective date**, and then executes that quote with a matching **settlement date** and a payment reference. The quote is persisted as `loan_foreclosure_quote` (principal, interest, settlement amount, version, status) and execution records one `loan_payment_transaction` on the `FORECLOSURE_SETTLEMENT` channel, re-allocates receipts across the schedule, and closes the loan to `FORECLOSED`.

### What the code does today

Read `backend/src/main/java/com/bhawana/lms/service/LoanForeclosureCommandService.java:85-97`. The quote is a **pure schedule summation**, not a pricing calculation:

- `outstandingPrincipal` = Σ over **all** installment rows of `max(principalDue − paidPrincipal, 0)`, each scaled to 2 dp.
- `outstandingInterest` = Σ over **all** installment rows of `max(interestDue − paidInterest, 0)`, each scaled to 2 dp.
- `settlementAmount` = principal + interest.

Consequences of that shape, all confirmed by reading the code:

1. **Every future installment's interest is in the quote.** A month-3 settlement on a 12-month loan is quoted the full remaining 10 installments of interest. The borrower pays the same total interest as if the loan ran to term.
2. **`effectiveDate` does not enter the arithmetic at all.** It is stored on the quote and re-checked for equality at execution (`LoanForeclosureCommandService.java:235`), and that is its only function. Two quotes with effective dates a year apart on an untouched loan are numerically identical.
3. **There is no charge, fee or tax component anywhere.** `loan_foreclosure_quote` has exactly three money columns (`outstanding_principal`, `outstanding_interest`, `settlement_amount`); `loan_payment_transaction` has one (`amount`, plus `allocated_amount` / `unallocated_amount`). No prepayment charge, no GST, no policy or formula version is stored.
4. **Eligibility is status-only.** Application must be `DISBURSED` or `UNDER_REPAYMENT` and the loan account `DISBURSED` (`LoanForeclosureCommandService.java:64-83`). No lock-in period, no minimum installments paid, no per-product or per-LSP switch, no delinquency gate.
5. **The effective date is unbounded.** `LoanForeclosureQuoteRequest` / `LspLoanForeclosureQuoteRequest` validate `@NotNull` only — an arbitrarily backdated or forward-dated effective date is accepted.
6. **Closure is gated on the schedule being fully covered by cash.** Execution records a receipt for the quoted amount, calls `recomputePaymentAllocation` (`LoanServicingSupportService.java:240-274`), then refuses to close unless `allInstallmentsSettled` (`:319`) — an `IllegalStateException` on the ops path, surfaced as the `SETTLEMENT_INCOMPLETE` violation type on the LSP path. Because allocation runs oldest-unpaid-first over `principalDue + interestDue` of every row, **any quote lower than the scheduled remaining total cannot execute at all.** This is the single largest implementation consequence in this document: removing future interest from the quote is not a one-formula change — it requires a decided mechanism for retiring the unaccrued interest on the remaining schedule rows (see D9).

### Whether today's behaviour is wrong

**Not established, deliberately.** The audits confirm the behaviour and explicitly decline to draw a contractual or regulatory conclusion from it; so does this ADR. Charging full-term interest on early settlement *may* be exactly what the loan agreement provides for, and the product owner may sign off today's behaviour unchanged. What is not acceptable is the current state: an amount of money computed by a summation nobody has approved, with a settlement date that looks meaningful and is not.

### Why this is an ADR and not a ticket

The pricing formula is downstream of eight independent product choices (rate basis, eligibility, inclusivity, day-count, rounding, charges, tax, backdating). Each one changes the calculator's signature, its stored inputs, or the schema. Picking them inside an implementation PR would bury a pricing policy in a diff and make later disputes unresolvable, because the quote does not record which rules produced it.

## Decisions required before any formula change

Each decision below is **open**. For each: what is needed, the options, what the code does today, and what the chosen option costs in code. Options are presented neutrally; where the audit or the existing codebase implies a default, it is marked **Engineering observation** and is a recommendation only, never a decision.

### D1 — Rate basis: fixed or floating

**Needed:** whether accrued interest to the settlement date is computed at the rate pinned on the loan, or at a rate that may have moved since disbursement.

**Options:**
- **(a) Fixed, pinned at approval.** One rate for the life of the loan.
- **(b) Floating.** Rate resets over the loan's life; accrual must be computed piecewise over rate periods.
- **(c) Fixed today, floating products later.** Policy declares products in scope as fixed and defers floating to a superseding ADR.

**Current code:** effectively (a), and pinning already exists — `LoanAccount` holds a `loan_product_version` FK and `LoanRepaymentScheduleService:584` reads `loanAccount.getLoanProductVersion().getInterestRate()`, so a later product-version edit cannot reprice an existing loan. `loan_product_version.interest_rate` is `NUMERIC(5,2)`, a single annual percentage. There is no rate-type discriminator and no rate-change history table.

**Code-level consequence:** (a) needs nothing new. (b) requires a rate-period history per loan account (new table + migration), piecewise accrual in the calculator, and pinning the applicable periods on the quote; it also reopens EMI regeneration, which is out of this ADR's scope. (c) requires a product-level rate-type flag only if a floating product can be configured before the superseding ADR lands.

### D2 — Borrower and product eligibility

**Needed:** which loans may be foreclosed at all, and by whom.

**Options (not mutually exclusive):**
- **(a) Any disbursed, non-closed loan** — today's rule.
- **(b) Lock-in period** — no foreclosure before *N* months from disbursement or before *N* installments are paid.
- **(c) Per-product switch** — foreclosure allowed only on products configured for it.
- **(d) Delinquency gate** — overdue loans excluded, or admitted only through a separate settlement path.
- **(e) Actor split** — LSP-initiated foreclosure restricted more tightly than ops-initiated.

**Current code:** (a) only. The status checks are the whole rule; `LoanProduct` / `LoanProductVersion` carry no foreclosure configuration whatsoever (their money fields are `min_principal`, `max_principal`, `interest_rate`, `processing_fee_rate`, `min_tenure_months`, `max_tenure_months`). Both the ops surface (`SYSTEM_ADMIN`) and the LSP surface reach the same `requestForeclosureQuote`.

**Code-level consequence:** (a) costs nothing. (b) needs a disbursement-date or paid-installment predicate plus a new rejection code and its ops-alert violation type. (c) needs product-version columns, a migration, product-admin UI, and a re-read on the pinned version rather than the live product. (d) needs a delinquency predicate shared with the DPD definitions (see H10/H11) so two surfaces cannot disagree about "overdue". (e) needs the rule applied at both entry points, since `requestForeclosureQuoteForLsp` delegates to the same method.

### D3 — Accrual start and end inclusivity

**Needed:** the exact half-open or closed interval over which interest accrues, relative to the settlement effective date.

**Options:**
- **(a) Accrue up to and including the settlement date** (borrower pays for the settlement day).
- **(b) Accrue up to but excluding the settlement date.**
- **(c) Accrue from the last paid due date; or from the last *due* date whether paid or not; or from disbursement for a pre-first-due-date settlement.**

**Current code:** no interval exists. Interest is per-installment-row and the settlement date is unused, so the question is currently unanswerable from the code. The schedule's own anchors are: first due date = approval date + 1 month, subsequent rows `plusMonths(n)` (`LoanRepaymentScheduleService:272`, `:291`).

**Code-level consequence:** whichever is chosen, the calculator takes `(lastAccrualAnchor, settlementDate)` explicitly and the chosen inclusivity must be asserted by a golden vector on both sides of a due date (§Golden vectors, rows 1–3). An off-by-one day here is a real money error on every settlement, and it is invisible without those vectors.

### D4 — Day-count convention

**Needed:** how a partial period converts into an interest amount.

**Options:** actual/365, actual/actual (ISDA), actual/360, 30/360, or **monthly-period-only** (no daily proration — interest accrues per whole installment period, so a mid-period settlement pays either the whole period's interest or none of it).

**Current code:** **there is no day-count convention in this codebase, and no daily interest accrual anywhere.** The only interest arithmetic is per installment period, on a reducing balance:

- `monthlyRate = annualRate / 1200`, at scale 10, `HALF_UP` (`LoanRepaymentScheduleService:584-586`).
- `interestDue = scale2(openingPrincipal × monthlyRate)` (`:588-590`).
- EMI = the standard annuity formula, `principal × r × (1+r)^n / ((1+r)^n − 1)`, divided at scale 2 `HALF_UP`, with the final installment's principal forced to the opening balance and its installment amount re-derived (`:653-663`, `:281-284`).
- The same `annual/1200` convention appears in `SyntheticPortfolioEmiCalculator:26`, and the partner-schedule validator (Spec S20) checks LSP-provided rows against it within tolerance (`validateInterestDiscipline`, `:520-572`).

A grep for 365, 360 and daily accrual across `backend/src/main/java` returns no interest usage: `ChronoUnit.DAYS` appears only in DPD bucketing (`LoanDelinquencySupport:22`, `HomeDashboardService:222`, `PortfolioKpiSnapshotComputationService:174`), retention and seeding.

**Engineering observation (recommendation, not a decision):** every existing money path treats a month as one indivisible period at `annual/12`. A *monthly-period* foreclosure convention therefore agrees with the schedule the borrower was given, needs no new primitive, and cannot disagree with the EMI rows. Any daily convention introduces the platform's first daily accrual and, with it, a permanent reconciliation question between the quote and the schedule the borrower signed.

**Code-level consequence:** a daily convention adds a day-count primitive (shared with any future penal-interest work, of which there is none today), makes leap-year and month-end behaviour material (rows 4 and 9 below), and means the quote's interest will not tie to any row in `loan_repayment_schedule_installment` — so the quote must store its own inputs to stay explainable.

### D5 — Rounding rule and scale

**Needed:** the scale and rounding mode for each component and for the total, and whether components round before or after summation.

**Options:** 2 dp `HALF_UP` per component then sum (today's shape); compute at high precision and round only the total; round the total to the nearest rupee; round the total *down* (in the borrower's favour) or *up*.

**Current code:** 2 dp `HALF_UP` everywhere, via one helper — `Money.scale(value) = value.setScale(2, RoundingMode.HALF_UP)` (`backend/src/main/java/com/bhawana/lms/common/money/Money.java:11-13`), with `LoanServicingSupportService.scaleCurrency` used on the foreclosure path. Rounding is applied **per installment row before summation** (`LoanForeclosureCommandService.java:88-96`), and again on the total. Every money column involved is `NUMERIC(19,2)`. The intermediate monthly rate is the one exception, held at scale 10.

**Engineering observation (recommendation, not a decision):** 2 dp `HALF_UP` is already the repo-wide invariant, including ADR 0004's fee calculator. Departing from it on this one path would make the foreclosure total the only amount in the system with different rounding.

**Code-level consequence:** rupee rounding or a directional rule changes the exact-match receipt comparison (`validateExactInstallmentAmount` and the C05 exactness work) and must be pinned by the rounding-boundary vectors (rows 10–11), since `HALF_UP` vs `HALF_EVEN` differs only on exact halves. `NUMERIC(19,2)` columns mean any scale above 2 requires a migration.

### D6 — Prepayment / foreclosure charge

**Needed:** whether a charge is levied for settling early, on what base, and whether it is configurable.

**Options:** none; a flat amount; a percentage of outstanding principal; a percentage of the *foregone* interest; tenure-dependent (higher inside a lock-in window, lower later); waivable by ops with a reason.

**Current code:** no charge exists — the quote is principal + interest, full stop. The only fee concept in the system is the disbursement-time processing fee (ADR 0004: `LoanFeeCalculator`, `loan_account.processing_fee_amount`, `loan_product_version.processing_fee_rate`), which is unrelated to settlement and is explicitly pre-tax.

**Code-level consequence:** any non-zero option adds a `permitted_charge_amount` column to `loan_foreclosure_quote` (migration), a charge component in the calculator's return value, a configuration home (product version, mirroring `processing_fee_rate`), new fields on the ops and LSP quote responses plus the OpenAPI snapshot and the generated frontend types, and a waiver path with its own audit action if (f) is chosen. A charge also changes what "exact match" means for the settlement receipt.

### D7 — Tax treatment

**Needed:** whether GST (or any tax) applies to the charge and/or the interest, at what rate, and how it is surfaced.

**Options:** no tax component; GST on the prepayment charge only; GST on charge and any other fee; tax computed and displayed separately from the charge; tax rate configurable versus hard-coded.

**Current code:** no tax anywhere. ADR 0004 §5 puts GST explicitly out of scope and requires a separate ticket and ADR before any tax capture — this ADR does not overturn that; it records that foreclosure would be the second path needing it.

**Code-level consequence:** a `tax_amount` column on the quote plus a tax-rate configuration home; the calculator returns a fourth component; MIS and the partner event payload (`LoanEventPayloads.foreclosureQuote` / `.foreclosure`) must distinguish tax from charge, or finance cannot reconcile. If tax is in scope, it is cheaper to decide it together with D6 than to add a column per decision.

### D8 — Backdated (and forward-dated) settlement

**Needed:** how far into the past or future an effective date may sit, and what happens to events between that date and today.

**Options:**
- **(a) Effective date must be today.**
- **(b) Backdating allowed within *N* days** (a settlement-window policy), with a bound.
- **(c) Backdating allowed to any date, with an approval or audit trail.**
- **(d) Forward-dating allowed to a future date** (the borrower is quoted for a date they intend to pay on).

**Current code:** unbounded in both directions and consequential in neither. `effectiveDate` is `@NotNull` only, and the only constraint at execution is `settlementDate.equals(quote.getEffectiveDate())` (`LoanForeclosureCommandService.java:235-244`). No clock is consulted. The C05 finding is exactly this: the quote has no valid-until policy and no balance-freshness check, so a receipt recorded after the quote does not invalidate it.

**Code-level consequence:** any bounded option needs a business clock (align with the M09 business-date work rather than calling `LocalDate.now()` in the service) plus a new rejection code and violation type. Backdating past a recorded receipt or a schedule change interacts directly with C05's revision fingerprint and with H09's receipt-targeting rule: if accrual depends on the effective date, a backdated quote must be priced from the balance **as of** that date, not from today's balance — which means either an as-of balance reconstruction or an explicit policy that backdated quotes price off the current balance and that discrepancy is accepted. Do not let an implementation PR pick between those two silently.

### D9 — May any future (unaccrued) interest ever be charged?

**Needed:** an explicit yes/no, and if yes, the contractual basis and the cap.

**Options:**
- **(a) Never.** Accrued interest to the settlement date only; unaccrued interest on remaining installments is not collected.
- **(b) Always, to term** — today's behaviour, made explicit as a policy.
- **(c) Only where the loan agreement for that product explicitly provides for it** — a per-product answer, which turns this into a configuration question and requires D2(c).

**Current code:** (b) in effect, by construction, without a recorded decision. The audit's acceptance criterion is that a chosen policy be demonstrated by a golden case either way, and that no future-interest behaviour be assumed by a test author.

**Code-level consequence (the largest in this ADR):** choosing (a) or (c) does not stop at the quote. Because execution requires `allInstallmentsSettled` after allocating the settlement receipt across `principalDue + interestDue` of every remaining row (`LoanForeclosureCommandService.java:262-266` → `LoanServicingSupportService.recomputePaymentAllocation`), a quote that excludes future interest **will fail to settle the schedule and be rejected** under today's code (`SETTLEMENT_INCOMPLETE` on the LSP path). A decided mechanism is needed for the unaccrued interest on remaining rows — for example writing it off with an explicit, audited adjustment and a closure check that accepts "settled or written off" — and that mechanism must not silently rewrite payment history (H09) or reprice historical settlements. Engineering must bring that mechanism back as an implementation decision once D9 is answered; it is not chosen here.

### D10 — Receipt that does not equal the quote — **settled constraint, recorded for sign-off visibility**

**Needed:** nothing. Engineering has decided.

**Decision (engineering, settled):** **exact match only.** A settlement receipt must equal the quoted settlement amount to the paisa; a short receipt and an excess receipt are both rejected, and the loan does not close. There is no over-receipt, no unallocated remainder on a `FORECLOSURE_SETTLEMENT` receipt, and no refund route in scope.

**Current code:** does not yet enforce it. Execution records a receipt for `quote.getSettlementAmount()` itself, so the ops/LSP caller cannot currently supply a differing amount through this path — but nothing checks freshness, so a quote can be executed after an intervening receipt has changed the balance, leaving an excess allocated as `unallocated_amount` while the loan closes. That is C05's fix, not this ADR's; exact-match-only is stated here so the product owner knows that "the borrower paid a little extra" has no accommodating behaviour to sign off.

**Consequence for the product owner:** if the business needs over-payment tolerance, a rounding-down courtesy, or a refund route, that is a **new decision** that reopens this constraint and C05 together. Silence means exact match.

### Also settled by engineering (not product decisions)

- **S1 — One pure calculator.** A single deterministic function returns `(principal, accruedInterest, permittedCharge, tax, total)` and is used for both preview and the execution check, so a preview and an execution can never disagree. Non-negotiable regardless of which options are chosen.
- **S2 — The quote records its own provenance.** Policy/formula version, the pinned loan terms used, and the effective date are stored on the quote, so any settlement can be re-explained years later without re-deriving it from today's configuration. Lands with C05's revision fingerprint.
- **S3 — No silent repricing of history.** Existing settled loans are never recomputed under a new policy. Active quotes are migrated or invalidated explicitly when the policy version changes.

## Golden example test vectors — template, to be filled after sign-off

The expected columns are **deliberately empty**. They are filled in only by the product owner's signed-off worked examples, after D1–D9 are answered, and become the assertion values for the calculator's tests. A test author must not compute them from whatever the code then does — that would pin the current behaviour as the policy, which is the defect H08 describes.

Fixture for every row unless the row says otherwise: principal ₹1,20,000.00, annual rate 12.00%, tenure 12 months, approval date 2026-01-15 (so first due date 2026-02-15), platform-generated schedule, no prior receipts.

| # | Scenario | Effective / settlement date | Prior receipts | Expected principal | Expected accrued interest | Expected charge | Expected tax | Expected total |
|---|---|---|---|---|---|---|---|---|
| 1 | Settlement **before the first due date** | 2026-02-01 | none | | | | | |
| 2 | Settlement **on a due date** | 2026-04-15 | installments 1–2 paid in full, on time | | | | | |
| 3 | Settlement **between installments** | 2026-04-28 | installments 1–2 paid in full, on time | | | | | |
| 4 | **Leap day** settlement | 2028-02-29 (approval 2027-01-15) | installment 1 paid | | | | | |
| 5 | **Prior targeted receipt out of order** — installment 3 paid before installment 1 (H09) | 2026-04-20 | installment 3 only | | | | | |
| 6 | **Prior partial receipt** on the current installment, if partials are ever supported | 2026-04-20 | installment 1 partially paid | | | | | |
| 7 | **Zero balance** — schedule already fully settled | 2027-02-15 | all 12 installments paid | n/a — quote must be refused (`LOAN_ALREADY_SETTLED`) | | | | |
| 8 | **Backdated** settlement, effective date before today (per D8) | 2026-03-01, requested 2026-04-20 | installment 1 paid 2026-02-15 | | | | | |
| 9 | **Month-end anchor** — approval 2026-01-31, so due dates land on short months | 2026-03-30 | installment 1 paid | | | | | |
| 10 | **Rounding boundary** — exact-half paisa in the interest component | fixture chosen to produce a `…5` half | none | | | | | |
| 11 | **Rounding boundary** — components round one way, the total another | fixture chosen to expose per-component vs total rounding | none | | | | | |
| 12 | **Future-interest demonstration** — the case that proves the D9 answer either way | 2026-04-20 (month 3 of 12) | installments 1–2 paid | | | | | |

Notes for whoever fills this in:

- Row 12 is mandatory under the audit's acceptance criteria: whichever way D9 goes, one vector must demonstrate it explicitly. An absent or assumed answer is not a passing fixture.
- Row 7's expected outcome is a refusal, not an amount — today's code already refuses a zero-balance quote (`LOAN_ALREADY_SETTLED`, `LoanForeclosureCommandService.java:98-103`).
- Row 6 must not be read as accepting partial installment payments: today the payment path requires an exact installment match (`LoanServicingSupportService.validateExactInstallmentAmount`). Fill it in only if partial receipts are in scope; otherwise mark it not-applicable and say so.
- Rows 4 and 9 are only discriminating if D4 selects a daily convention. Under a monthly-period convention they should equal their non-leap / non-month-end equivalents, and the vectors should say so rather than being dropped.
- Each filled row is asserted component-by-component **and** on the exact decimal total at the agreed scale, and re-asserted through both the preview and the execution path with the stored policy version compared.

## Consequences

### Of leaving this in DRAFT
- Foreclosure keeps charging full-term interest. That is the status quo, it is auditable, and it is not silently changed by this document.
- H08 stays blocked on the policy-dependent formula only. The C05 freshness and exactness controls, H06's loan lock and H09's receipt-targeting fix are **not** blocked by this ADR and should land independently.
- Ops and partner-facing copy must not describe the settlement amount as "interest to date". It is not.

### Of accepting it (once decisions are filled in)
- A new Accepted ADR supersedes this draft, carrying the answers to D1–D9 and the filled vector table. The formula PR cites that ADR's version and stores it on every quote (S2).
- Expect a Flyway migration on `loan_foreclosure_quote` for whichever of charge, tax and policy version are in scope, plus ops and LSP response fields, the OpenAPI snapshot, the generated frontend types, and the `FORECLOSURE_QUOTE_REQUESTED` / `LOAN_FORECLOSURE_COMPLETED` event payloads.
- If D9 is answered (a) or (c), the unaccrued-interest retirement mechanism is a prerequisite, not a follow-up — without it the settlement cannot execute at all.

### Out of scope for this ADR
- Any legal or regulatory conclusion about early-settlement pricing. None is asserted here, and none of the audit sources establishes one.
- Penal interest, late fees and bounce charges — none exist in the codebase today.
- GST mechanics beyond noting that D7 needs them (ADR 0004 §5 still governs).
- Quote freshness, staleness and excess-receipt enforcement (C05), the loan lock (H06) and receipt-targeting stability (H09).
- Repricing or re-examining historical settlements (S3).

## Trigger to re-open

1. The product owner answers D1–D9 — this draft is superseded by an Accepted ADR with the vectors filled in.
2. The product owner signs off today's behaviour unchanged — still a superseding Accepted ADR, because "full-term interest is the policy" must be recorded as a decision with its golden case (row 12), not left as an artefact of a summation.
3. A floating-rate product becomes configurable (reopens D1 and D4 together).
4. Over-receipt tolerance, courtesy rounding or a refund route is required (reopens D10 and C05).
5. Partial installment receipts become supported (reopens row 6 and the exact-match payment rule).
