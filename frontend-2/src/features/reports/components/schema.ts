/**
 * Create-report-request form schema.
 *
 * Sibling-to-the-dialog Zod schema per the project convention
 * (react-refresh/only-export-components forbids non-component exports from
 * `.tsx` modules). All fields are optional; the dialog normalises blanks
 * → null before posting.
 */
import { z } from "zod";

const UuidPattern =
  /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/u;
const IsoDatePattern = /^\d{4}-\d{2}-\d{2}$/u;

export const CreateReportRequestFormSchema = z
  .object({
    lspId: z
      .string()
      .trim()
      .max(80)
      .optional()
      .refine(
        (v) => !v || UuidPattern.test(v),
        "LSP id must be a valid UUID (or leave blank for all LSPs).",
      ),
    dateFrom: z
      .string()
      .trim()
      .optional()
      .refine(
        (v) => !v || IsoDatePattern.test(v),
        "Date must be in YYYY-MM-DD format.",
      ),
    dateTo: z
      .string()
      .trim()
      .optional()
      .refine(
        (v) => !v || IsoDatePattern.test(v),
        "Date must be in YYYY-MM-DD format.",
      ),
    notificationEmail: z
      .string()
      .trim()
      .max(254)
      .optional()
      .refine(
        (v) => !v || /^[^\s@]+@[^\s@]+\.[^\s@]+$/u.test(v),
        "Enter a valid email address (or leave blank).",
      ),
  })
  .refine(
    (data) => {
      if (!data.dateFrom || !data.dateTo) return true;
      return data.dateFrom <= data.dateTo;
    },
    {
      message: "From date must be on or before To date.",
      path: ["dateTo"],
    },
  );

export type CreateReportRequestFormValues = z.infer<
  typeof CreateReportRequestFormSchema
>;
