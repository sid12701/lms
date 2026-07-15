# Partner repayment schedule validation (Spec S20)

**Effective:** 2026-07-15  
**Endpoint:** `PUT /api/v1/lsp/loan-applications/{applicationId}/repayment-schedule` with `mode: LSP_PROVIDED`  
**Error:** `422` / `REPAYMENT_SCHEDULE_INVALID` with per-field `violations` and `violationType`

This is an **intentional contract tightening**. Schedules that previously passed with arbitrary interest or irregular calendars may now be rejected. Use `mode: GENERATED` when the platform schedule is acceptable.

## Accepted bounds (product defaults)

| Rule | Default | Config key |
|---|---|---|
| First due date min offset from approval (UTC date) | 1 day | `app.schedule.validation.first-due-min-days` |
| First due date max offset from approval | 60 days | `…first-due-max-days` |
| Monthly cadence drift from `firstDue + i months` | ±7 days | `…cadence-tolerance-days` |
| Final due max = approval + tenure months + grace | 75 days grace | `…horizon-grace-days` |
| Per-row interest vs `opening × (productRate/1200)` | max(₹10, 2% of expected) | `…interest-row-tolerance-abs` / `…-pct` |
| Total interest vs platform generator total | max(₹100, 1% of generated) | `…interest-total-tolerance-abs` / `…-pct` |

Principal-chain rules (count = tenure, opening/closing chain, Σ principal, EMI = P+I, final close = 0) are unchanged and remain primary when both fail.

## New `violationType` values

| Code | Meaning |
|---|---|
| `SCHEDULE_FIRST_DUE_OUT_OF_WINDOW` | First due date outside the approval window |
| `SCHEDULE_CADENCE_VIOLATION` | A later due date drifts more than tolerance from anchored monthly cadence |
| `SCHEDULE_HORIZON_EXCEEDED` | Final due date beyond tenure + grace |
| `SCHEDULE_INTEREST_ROW_MISMATCH` | Row interest not within tolerance of product monthly rate × opening |
| `SCHEDULE_INTEREST_TOTAL_MISMATCH` | Σ interest not within tolerance of the platform-generated schedule |

Messages for interest row/total violations name the expected value.

## Guidance for partners

1. Prefer due dates on a monthly grid from a first due within ~1 month of approval.
2. Derive interest from the **frozen product interest rate** on a reducing-balance basis (same formula as platform `GENERATED` schedules), or submit `mode: GENERATED`.
3. Do not ship flat/placeholder interest (e.g. ₹100 every row) on a positive-rate product.
