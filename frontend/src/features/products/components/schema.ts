/**
 * Sibling Zod schemas for the product create/edit/mapping dialogs.
 */
import { z } from "zod";

const principalAmount = z
  .number({ invalid_type_error: "Enter a number" })
  .positive("Principal amount must be greater than 0")
  .max(1e12, "Amount too large");

const ratePct = z
  .number({ invalid_type_error: "Enter a number" })
  .nonnegative("Rate cannot be negative")
  .max(100, "Rate cannot exceed 100");

const processingFeePct = z
  .number({ invalid_type_error: "Enter a number" })
  .nonnegative("Processing fee cannot be negative")
  .max(100, "Processing fee cannot exceed 100");

const minTenureMonths = z
  .number({ invalid_type_error: "Enter a number" })
  .int("Minimum tenure must be whole months")
  .min(1, "Minimum tenure must be at least 1 month")
  .max(360, "Minimum tenure cannot exceed 360 months");

const maxTenureMonths = z
  .number({ invalid_type_error: "Enter a number" })
  .int("Maximum tenure must be whole months")
  .min(1, "Maximum tenure must be at least 1 month")
  .max(360, "Maximum tenure cannot exceed 360 months");

export const CreateProductFormSchema = z
  .object({
    code: z
      .string()
      .trim()
      .min(2, "Product code must be at least 2 characters")
      .max(24, "Product code can be at most 24 characters")
      .regex(/^[A-Za-z0-9-]+$/, "Product code allows letters, digits, and hyphens only"),
    name: z
      .string()
      .trim()
      .min(1, "Display name is required")
      .max(120, "Display name can be at most 120 characters"),
    principalMin: principalAmount,
    principalMax: principalAmount,
    interestRatePct: ratePct,
    processingFeePct,
    tenureMinMonths: minTenureMonths,
    tenureMaxMonths: maxTenureMonths,
    lspIds: z.array(z.string().uuid()).default([]),
  })
  .superRefine((p, ctx) => {
    if (p.principalMax < p.principalMin) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["principalMax"],
        message: "Maximum principal must be greater than or equal to minimum principal.",
      });
    }
    if (p.tenureMaxMonths < p.tenureMinMonths) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["tenureMaxMonths"],
        message: "Maximum tenure must be greater than or equal to minimum tenure.",
      });
    }
  });

export type CreateProductFormValues = z.infer<typeof CreateProductFormSchema>;

export const EditProductFormSchema = z
  .object({
    name: z
      .string()
      .trim()
      .min(1, "Display name is required")
      .max(120, "Display name can be at most 120 characters"),
    status: z.enum(["ACTIVE", "INACTIVE"]),
    principalMin: principalAmount,
    principalMax: principalAmount,
    interestRatePct: ratePct,
    processingFeePct,
    tenureMinMonths: minTenureMonths,
    tenureMaxMonths: maxTenureMonths,
  })
  .superRefine((p, ctx) => {
    if (p.principalMax < p.principalMin) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["principalMax"],
        message: "Maximum principal must be greater than or equal to minimum principal.",
      });
    }
    if (p.tenureMaxMonths < p.tenureMinMonths) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["tenureMaxMonths"],
        message: "Maximum tenure must be greater than or equal to minimum tenure.",
      });
    }
  });

export type EditProductFormValues = z.infer<typeof EditProductFormSchema>;

export const ProductMappingFormSchema = z.object({
  lspIds: z.array(z.string().uuid()).default([]),
});

export type ProductMappingFormValues = z.infer<typeof ProductMappingFormSchema>;
