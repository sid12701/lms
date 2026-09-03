import { z } from "zod";

/**
 * Ops-escalation form schema (sibling to EscalateToAdminDialog, since
 * react-refresh/only-export-components forbids non-component exports from
 * `.tsx` modules). Both fields land on the emitted alert's audit row, so
 * neither may be blank.
 */
export const ESCALATION_TITLE_MAX = 255;
export const ESCALATION_MESSAGE_MAX = 1000;

export const EscalationFormSchema = z.object({
  title: z
    .string()
    .trim()
    .min(1, "Title is required.")
    .max(ESCALATION_TITLE_MAX, `Title must be ${ESCALATION_TITLE_MAX} characters or fewer.`),
  message: z
    .string()
    .trim()
    .min(1, "Message is required.")
    .max(ESCALATION_MESSAGE_MAX, `Message must be ${ESCALATION_MESSAGE_MAX} characters or fewer.`),
});

export type EscalationFormValues = z.infer<typeof EscalationFormSchema>;
