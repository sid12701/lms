/**
 * BR-11: LSP-provided repayment schedule reconciliation.
 *
 * The schema enforces (in order):
 *   1. count(installments) === approvedTenureMonths
 *   2. installment numbers are contiguous 1..N (no gaps, no dupes)
 *   3. due dates are strictly increasing
 *   4. for each row: principalDue + interestDue ≈ installmentAmount (±0.01)
 *   5. sum(principalDue) ≈ approvedPrincipal (±0.01)
 *
 * Tolerance is ±0.01 INR — this matches the BRD wording "amounts within
 * a paise" and is defensible against floating-point round-trip noise.
 */
import { z } from "zod";
import { IsoDate, MoneyINR, MoneyINRPositive, Uuid } from "./common";

/** Single row submitted by the LSP. */
export const LspProvidedInstallment = z.object({
  number: z.number().int().min(1).max(360),
  dueDate: IsoDate,
  principalDue: MoneyINR,
  interestDue: MoneyINR,
  installmentAmount: MoneyINR,
});
export type LspProvidedInstallment = z.infer<typeof LspProvidedInstallment>;

/** ±0.01 INR reconciliation tolerance. */
export const RECONCILE_TOLERANCE = 0.01;

/**
 * Tiny float-noise buffer added to the tolerance comparison so values that
 * sit *exactly* at ±0.01 (e.g. `10500 + 0.01`, which JS evaluates to
 * `10500.010000000000218`) still reconcile. Real LSP-supplied schedules hit
 * the same precision drift, so the schema must not reject them spuriously.
 */
const FLOAT_NOISE = 1e-6;

export const LspProvidedSchedule = z
  .object({
    accountId: Uuid,
    approvedPrincipal: MoneyINRPositive,
    approvedTenureMonths: z.number().int().min(1).max(360),
    installments: z.array(LspProvidedInstallment).min(1),
  })
  .superRefine((schedule, ctx) => {
    const { installments, approvedTenureMonths, approvedPrincipal } = schedule;

    // BR-11(1) — count
    if (installments.length !== approvedTenureMonths) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["installments"],
        message: `expected ${approvedTenureMonths} installments, got ${installments.length}`,
      });
    }

    // BR-11(2) — contiguous numbering 1..N
    let principalSum = 0;
    let prevDate: string | null = null;

    for (let i = 0; i < installments.length; i += 1) {
      const row = installments[i];
      // noUncheckedIndexedAccess: row may be undefined per TS, but length-bound iteration guarantees presence.
      if (!row) continue;

      if (row.number !== i + 1) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          path: ["installments", i, "number"],
          message: `expected installment #${i + 1}, got #${row.number}`,
        });
      }

      // BR-11(3) — strictly increasing due dates
      if (prevDate !== null && row.dueDate <= prevDate) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          path: ["installments", i, "dueDate"],
          message: `due date ${row.dueDate} must be strictly after ${prevDate}`,
        });
      }
      prevDate = row.dueDate;

      // BR-11(4) — row sum reconciliation
      const rowSum = row.principalDue + row.interestDue;
      if (Math.abs(rowSum - row.installmentAmount) > RECONCILE_TOLERANCE + FLOAT_NOISE) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          path: ["installments", i, "installmentAmount"],
          message: `principalDue + interestDue (${rowSum}) ≠ installmentAmount (${row.installmentAmount})`,
        });
      }

      principalSum += row.principalDue;
    }

    // BR-11(5) — total principal reconciliation
    if (Math.abs(principalSum - approvedPrincipal) > RECONCILE_TOLERANCE + FLOAT_NOISE) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["installments"],
        message: `sum(principalDue)=${principalSum} ≠ approvedPrincipal=${approvedPrincipal}`,
      });
    }
  });
export type LspProvidedSchedule = z.infer<typeof LspProvidedSchedule>;
