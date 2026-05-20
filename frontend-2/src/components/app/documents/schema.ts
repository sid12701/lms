import { z } from "zod";

/**
 * Form schema for the document-reject dialog. Lives next to the dialog
 * component (in a sibling file, not co-located) so React Refresh's
 * "only export components" rule stays clean.
 */
export const DocumentRejectSchema = z.object({
  rejectionReason: z
    .string()
    .trim()
    .min(1, "Rejection reason is required.")
    .max(500, "Rejection reason must be 500 characters or fewer."),
});
export type DocumentRejectValues = z.infer<typeof DocumentRejectSchema>;
