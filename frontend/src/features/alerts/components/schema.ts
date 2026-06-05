/**
 * Acknowledge-alert form schema.
 *
 * Sibling-to-the-dialog Zod schema per the project convention
 * (react-refresh/only-export-components forbids non-component exports from
 * `.tsx` modules). The form is intentionally tiny: a single optional note
 * with a max length matching the wire-level `OperationalAlert.acknowledgmentNote`.
 */
import { z } from "zod";

export const AcknowledgeAlertSchema = z.object({
  note: z.string().max(500, "Note must be 500 characters or fewer.").optional(),
});

export type AcknowledgeAlertValues = z.infer<typeof AcknowledgeAlertSchema>;
