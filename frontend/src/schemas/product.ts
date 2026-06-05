/**
 * Loan products + LSP mapping.
 * Per blueprint §7 + UI pages.md "Loan Product Configuration".
 */
import { z } from "zod";
import { Iso8601, MoneyINRPositive, Uuid } from "./common";

export const ProductStatus = z.enum(["ACTIVE", "INACTIVE"]);
export type ProductStatus = z.infer<typeof ProductStatus>;

export const LoanProduct = z
  .object({
    id: Uuid,
    code: z.string().min(2).max(24),
    name: z.string().min(1).max(120),
    status: ProductStatus,
    principalMin: MoneyINRPositive,
    principalMax: MoneyINRPositive,
    /** Annual interest rate, percent (e.g. 14.5 = 14.5%). */
    interestRatePct: z.number().nonnegative().max(100),
    /** Processing fee, percent of principal. */
    processingFeePct: z.number().nonnegative().max(100),
    tenureMinMonths: z.number().int().min(1).max(360),
    tenureMaxMonths: z.number().int().min(1).max(360),
    createdAt: Iso8601,
  })
  .superRefine((p, ctx) => {
    if (p.principalMax < p.principalMin) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["principalMax"],
        message: "principalMax must be ≥ principalMin",
      });
    }
    if (p.tenureMaxMonths < p.tenureMinMonths) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["tenureMaxMonths"],
        message: "tenureMaxMonths must be ≥ tenureMinMonths",
      });
    }
  });
export type LoanProduct = z.infer<typeof LoanProduct>;

export const ProductLspMapping = z.object({
  productId: Uuid,
  lspIds: z.array(Uuid),
});
export type ProductLspMapping = z.infer<typeof ProductLspMapping>;
