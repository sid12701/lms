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
  "NEFT",
  "RTGS",
  "IMPS",
  "BANK_TRANSFER",
  "UPI",
  "CASH",
]);
export type RepaymentPostMode = z.infer<typeof RepaymentPostMode>;

export const REPAYMENT_POST_MODES = RepaymentPostMode.options;

export const REPAYMENT_POST_MODE_LABELS: Record<RepaymentPostMode, string> = {
  NEFT: "NEFT",
  RTGS: "RTGS",
  IMPS: "IMPS",
  BANK_TRANSFER: "Bank transfer",
  UPI: "UPI",
  CASH: "Cash",
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
    amount: z.literal(outstanding),
    postedAt: z
      .string()
      .min(1, "Posting date is required.")
      .refine((value) => !Number.isNaN(Date.parse(value)), {
        message: "Posting date is invalid.",
      }),
    mode: RepaymentPostMode,
    reference: z.string().max(128, "Reference must be 128 characters or fewer.").optional(),
  });
}

export type RepaymentPostSchema = ReturnType<typeof makeRepaymentPostSchema>;
export type RepaymentPostValues = z.infer<RepaymentPostSchema>;
