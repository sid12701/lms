import { z } from "zod";

/**
 * Initiate-disbursement form (BR-3 + BR-10 are evaluated upstream by the page;
 * this schema only carries an optional operator note for the audit log). Kept
 * out of the .tsx so fast-refresh doesn't whine about non-component exports.
 */
export const DisbursementInitiateSchema = z.object({
  note: z
    .string()
    .trim()
    .max(500, "Note must be 500 characters or fewer.")
    .optional()
    .default(""),
});
export type DisbursementInitiateValues = z.infer<typeof DisbursementInitiateSchema>;
