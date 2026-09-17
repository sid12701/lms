# H08 — Foreclosure settlement: decisions we need from the product owner

**Date:** 2026-09-18 · **For:** product owner sign-off · **Companion:** `docs/adr/0009-foreclosure-settlement-pricing-policy.md` (the same decisions with the code detail)

## Why you are being asked

A borrower who settles a loan early is currently quoted **all the interest for the whole original term**, as if the loan had run to the last month. The settlement date they pick makes no difference to the amount. That is what the code does today; nobody has ever approved it as the policy. We are not changing it until you decide.

Nothing below is a legal or RBI question, and we are not answering one. These are business choices about what we charge and who may settle early.

Where a **Recommendation** appears, it is engineering's read of what the rest of the system already does — **a suggestion, not a decision**. Overriding it costs nothing.

---

### 1. Should a borrower who settles early pay interest for the months the loan no longer runs?
Yes (today's behaviour), no (interest only up to the settlement day), or only on certain products? *This is the central question — everything else is detail.*

### 2. Is the interest rate fixed for the life of the loan?
Fixed at approval, or can it move afterwards? **Recommendation:** fixed — the loan already locks its product version, so a later rate edit cannot reprice an existing loan.

### 3. Who is allowed to settle early?
Any disbursed loan (today's rule), or only after a lock-in period / a minimum number of paid instalments / only on certain products / not while overdue?

### 4. Does the borrower pay interest for the settlement day itself?
Interest up to **and including** the day they settle, or up to the **day before**? *A one-day choice that changes every single settlement amount.*

### 5. If interest stops on the settlement date, how is a part-month charged?
Per whole month (as the EMI schedule already works), or per day? **Recommendation:** per whole month — the platform has no daily interest anywhere, and a daily rule would stop matching the schedule the borrower was given.

### 6. How should the final amount be rounded?
To the paisa, as everywhere else in the system, or to the nearest rupee — and if there is a tie, up or down? **Recommendation:** to the paisa, rounding halves up — the rule every other amount in the system uses.

### 7. Is there a charge for settling early?
None, a flat amount, a percentage of the outstanding balance, or a percentage that shrinks the longer the loan has run? *Today there is no such charge at all.*

### 8. If there is a charge, does GST apply to it?
Yes or no, at what rate, and should the borrower see the tax as a separate line? *We capture no tax anywhere today, so this is new either way.*

### 9. Can a settlement be dated in the past?
Only today's date, backdated within a few days, backdated freely with an audit trail, or dated in the future so the borrower can pay on a planned day? *Today any date is accepted, past or future, with no limit.*

### 10. If a settlement is backdated, which balance do we price it on?
The balance as it stood on that earlier date, or today's balance? *Only relevant if you allow backdating in question 9.*

### 11. Can ops waive the early-settlement charge for an individual borrower?
Yes (we record who waived it and why), or no. *Only relevant if there is a charge at all.*

### 12. Already decided by engineering — no answer needed, but you should know
The borrower must pay **exactly** the quoted amount. A short payment and an overpayment are both rejected and the loan stays open; there is no refund of an excess. If the business needs overpayment tolerance or a refund route, tell us and we will reopen it.

---

## What happens once you answer

We write the answers into ADR 0009 as an approved policy, then send you a short table of worked examples — real amounts for about a dozen settlement cases (before the first due date, on a due date, mid-month, a leap day, a backdated one, and so on). **Those examples need your signature too**: they become the tests that hold the calculator to your policy, and we are deliberately not filling them in ourselves.

Only after that do we change the formula. Loans already settled are never repriced.
