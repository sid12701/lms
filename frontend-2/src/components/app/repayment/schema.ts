/**
 * Repayment-posting form schema (BR-13).
 *
 * The schema is created via a factory so the BR-13 amount-equality refine can
 * close over the live `outstanding` amount without leaking that value into a
 * `.tsx` component (and tripping `react-refresh/only-export-components`).
 *
 * Mode enum is hardcoded per the agent brief — it deliberately exceeds the
 * narrower `PaymentChannel` enum in `src/schemas/payment.ts` because the UI
 * surface presents a richer set of channels than the mock-router currently
 * persists.
 */
import { z } from "zod";

export const RepaymentPostMode = z.enum([
  "BANK_TRANSFER",
  "UPI",
  "CASH",
  "CHEQUE",
  "RTGS",
  "NEFT",
]);
export type RepaymentPostMode = z.infer<typeof RepaymentPostMode>;

export const REPAYMENT_POST_MODES = RepaymentPostMode.options;

export const REPAYMENT_POST_MODE_LABELS: Record<RepaymentPostMode, string> = {
  BANK_TRANSFER: "Bank transfer",
  UPI: "UPI",
  CASH: "Cash",
  CHEQUE: "Cheque",
  RTGS: "RTGS",
  NEFT: "NEFT",
};

/**
 * Builds the Zod object used by `RepaymentPostDialog`. The amount field is
 * compared with `outstanding` to enforce BR-13 (full-installment-only).
 *
 * `outstanding` is captured by reference so the validation always reflects the
 * current row, even if the dialog is re-used across installments.
 */
export function makeRepaymentPostSchema(outstanding: number) {
  return z.object({
    amount: z
      .number({
        invalid_type_error: "Amount is required.",
        required_error: "Amount is required.",
      })
      .positive("Amount must be greater than zero.")
      .refine((value) => value === outstanding, {
        message:
          "Repayment must equal the outstanding amount of the next installment (BR-13).",
      }),
    postedAt: z
      .string()
      .min(1, "Posting date is required.")
      .refine((value) => !Number.isNaN(Date.parse(value)), {
        message: "Posting date is invalid.",
      }),
    mode: RepaymentPostMode,
  });
}

export type RepaymentPostSchema = ReturnType<typeof makeRepaymentPostSchema>;
export type RepaymentPostValues = z.infer<RepaymentPostSchema>;
