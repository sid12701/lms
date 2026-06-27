/**
 * Create-LSP form schema (sibling to LspCreateDialog).
 */
import { z } from "zod";

export const CreateLspFormSchema = z.object({
  code: z
    .string()
    .trim()
    .min(2, "LSP code must be at least 2 characters")
    .max(16, "LSP code can be at most 16 characters")
    .regex(
      /^[A-Z][A-Z0-9_-]*$/u,
      "LSP code must start with a letter and use uppercase letters, digits, hyphens, or underscores",
    ),
  name: z
    .string()
    .trim()
    .min(1, "Display name is required")
    .max(120, "Display name can be at most 120 characters"),
});

export type CreateLspFormValues = z.infer<typeof CreateLspFormSchema>;
