/**
 * Sibling Zod schemas for the product create/edit/mapping dialogs.
 *
 * Co-locating these schemas in a `.ts` (not `.tsx`) keeps
 * react-refresh/only-export-components happy across the dialogs that import
 * them.
 *
 * The product schema mirrors `LoanProduct`'s superRefine (principalMin â‰¤
 * principalMax, tenureMinMonths â‰¤ tenureMaxMonths) so RHF can surface the
 * exact same validation error as the backend.
 */
import { z } from "zod";

const numericPositive = z
  .number({ invalid_type_error: "Enter a number" })
  .positive("Must be greater than 0")
  .max(1e12, "Amount too large");

const numericNonNegative = z
  .number({ invalid_type_error: "Enter a number" })
  .nonnegative("Cannot be negative")
  .max(100, "Cannot exceed 100");

const tenureMonths = z
  .number({ invalid_type_error: "Enter a number" })
  .int("Whole months only")
  .min(1, "Minimum tenure is 1 month")
  .max(360, "Maximum tenure is 360 months");

export const CreateProductFormSchema = z
  .object({
    code: z
      .string()
      .trim()
      .min(2, "Code must be at least 2 characters")
      .max(24, "Code can be at most 24 characters")
      .regex(/^[A-Za-z0-9-]+$/, "Letters, digits, and hyphens only"),
    name: z
      .string()
      .trim()
      .min(1, "Name is required")
      .max(120, "Name can be at most 120 characters"),
    principalMin: numericPositive,
    principalMax: numericPositive,
    interestRatePct: numericNonNegative,
    processingFeePct: numericNonNegative,
    tenureMinMonths: tenureMonths,
    tenureMaxMonths: tenureMonths,
    lspIds: z.array(z.string().uuid()).default([]),
  })
  .superRefine((p, ctx) => {
    if (p.principalMax < p.principalMin) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["principalMax"],
        message: "Max principal must be greater than or equal to min principal.",
      });
    }
    if (p.tenureMaxMonths < p.tenureMinMonths) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["tenureMaxMonths"],
        message: "Max tenure must be greater than or equal to min tenure (months).",
      });
    }
  });

export type CreateProductFormValues = z.infer<typeof CreateProductFormSchema>;

export const EditProductFormSchema = z
  .object({
    name: z
      .string()
      .trim()
      .min(1, "Name is required")
      .max(120, "Name can be at most 120 characters"),
    status: z.enum(["ACTIVE", "INACTIVE"]),
    principalMin: numericPositive,
    principalMax: numericPositive,
    interestRatePct: numericNonNegative,
    processingFeePct: numericNonNegative,
    tenureMinMonths: tenureMonths,
    tenureMaxMonths: tenureMonths,
  })
  .superRefine((p, ctx) => {
    if (p.principalMax < p.principalMin) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["principalMax"],
        message: "Max principal must be greater than or equal to min principal.",
      });
    }
    if (p.tenureMaxMonths < p.tenureMinMonths) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["tenureMaxMonths"],
        message: "Max tenure must be greater than or equal to min tenure (months).",
      });
    }
  });

export type EditProductFormValues = z.infer<typeof EditProductFormSchema>;

export const ProductMappingFormSchema = z.object({
  lspIds: z.array(z.string().uuid()).default([]),
});

export type ProductMappingFormValues = z.infer<typeof ProductMappingFormSchema>;
