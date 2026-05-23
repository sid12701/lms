/**
 * View-layer contract for the SYSTEM_ADMIN-only `/audit` page.
 *
 * The mock router returns an `AuditEventsResponse` ({@link AuditEventsResponse})
 * — a flattened, paginated stream join across all five audit tables defined
 * in `@/schemas/audit`. Each row is projected to a common discriminated
 * `AuditRow` shape so the table can render a single column layout that
 * adapts to the row's `stream`, and so the detail sheet can deep-link into
 * the subject (`/loan-applications/:id`, `/borrowers/:id`, `/products/:id`).
 *
 * Filters are URL-bound via `useUrlFilters(AuditEventsFilters)`. Empty
 * arrays / undefined scalars mean "no filter".
 */
import { z } from "zod";
import { Iso8601, IsoDate, Uuid } from "@/schemas/common";

// ─── Stream discriminator ────────────────────────────────────────────────────

export const AUDIT_STREAMS = [
  "APPLICATION",
  "INTAKE",
  "PII_REVEAL",
  "DOCUMENT_ACCESS",
  "PRODUCT",
] as const;

export const AuditStreamSchema = z.enum(AUDIT_STREAMS);
export type AuditStream = z.infer<typeof AuditStreamSchema>;

export const AUDIT_STREAM_LABEL: Record<AuditStream, string> = {
  APPLICATION: "Application",
  INTAKE: "Intake",
  PII_REVEAL: "PII reveal",
  DOCUMENT_ACCESS: "Document access",
  PRODUCT: "Product",
};

// ─── Subject discriminator (drives the deep-link button) ─────────────────────

export const AUDIT_SUBJECT_TYPES = [
  "LOAN_APPLICATION",
  "BORROWER",
  "LOAN_DOCUMENT",
  "LOAN_PRODUCT",
] as const;

export const AuditSubjectTypeSchema = z.enum(AUDIT_SUBJECT_TYPES);
export type AuditSubjectType = z.infer<typeof AuditSubjectTypeSchema>;

// ─── Row + response shapes ───────────────────────────────────────────────────

/**
 * Flattened audit row. Composed in the mock handler from the five distinct
 * stream tables — every consumer of the audit page reads through this shape.
 */
export interface AuditRow {
  id: string;
  stream: AuditStream;
  createdAt: string;
  actorId: string;
  /** Resolved from `db.users.get(actorId).username` (or "Unknown actor"). */
  actorName: string;
  /** When the row's stream schema carries one — null for INTAKE. */
  actorRole: string | null;
  correlationId: string;
  /** Discriminated subject for deep-link routing. */
  subjectType: AuditSubjectType | null;
  subjectId: string | null;
  /** Short human-readable headline e.g. "Approved", "PAN revealed". */
  headline: string;
  /** Original event payload — surfaced in the detail sheet as JSON. */
  raw?: unknown;
}

export const AuditRowSchema: z.ZodType<AuditRow> = z.object({
  id: z.string().min(1),
  stream: AuditStreamSchema,
  createdAt: Iso8601,
  actorId: z.string().min(1),
  actorName: z.string().min(1),
  actorRole: z.string().nullable(),
  correlationId: z.string().min(1),
  subjectType: AuditSubjectTypeSchema.nullable(),
  subjectId: z.string().nullable(),
  headline: z.string().min(1),
  raw: z.unknown(),
});

export interface AuditEventsResponse {
  items: readonly AuditRow[];
  total: number;
  page: number;
  pageSize: number;
}

export const AuditEventsResponseSchema: z.ZodType<AuditEventsResponse> = z.object({
  items: z.array(AuditRowSchema).readonly(),
  total: z.number().int().nonnegative(),
  page: z.number().int().nonnegative(),
  pageSize: z.number().int().positive(),
});

// ─── URL-bound filters ───────────────────────────────────────────────────────

/**
 * URL-bound filter shape for `/audit`. `streams` defaults to ALL when empty.
 * `q` is a free-text search over `headline` + `actorName`.
 */
export const AuditEventsFilters = z.object({
  streams: z.array(AuditStreamSchema).optional(),
  actorId: Uuid.optional(),
  correlationId: z.string().min(1).max(80).optional(),
  dateFrom: IsoDate.optional(),
  dateTo: IsoDate.optional(),
  q: z.string().trim().min(1).max(120).optional(),
  page: z.coerce.number().int().min(0).optional(),
  pageSize: z.coerce.number().int().min(5).max(200).optional(),
  /** Set by the table when a row is opened — surfaces the detail sheet. */
  eventId: z.string().min(1).max(80).optional(),
});

export type AuditEventsFilters = z.infer<typeof AuditEventsFilters>;
