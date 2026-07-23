/**
 * View-layer contract for the SYSTEM_ADMIN-only `/audit` page.
 *
 * The backend returns an `AuditEventsResponse` ({@link AuditEventsResponse})
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
import { IsoDate, Uuid } from "@/schemas/common";

// ─── Stream discriminator ────────────────────────────────────────────────────

export const AUDIT_STREAMS = [
  "APPLICATION",
  "INTAKE",
  "PII_REVEAL",
  "DOCUMENT_ACCESS",
  "PRODUCT",
  "APP_USER",
  "API_CLIENT",
  "DISBURSEMENT",
  "REPORT_ACCESS",
] as const;

const AuditStreamSchema = z.enum(AUDIT_STREAMS);
export type AuditStream = z.infer<typeof AuditStreamSchema>;

export const AUDIT_STREAM_LABEL: Record<AuditStream, string> = {
  APPLICATION: "Application",
  INTAKE: "Intake",
  PII_REVEAL: "PII reveal",
  DOCUMENT_ACCESS: "Document access",
  PRODUCT: "Product",
  APP_USER: "App user",
  API_CLIENT: "API client",
  DISBURSEMENT: "Disbursement",
  REPORT_ACCESS: "Report access",
};

/** Tailwind classes for stream pills in {@link AuditTable}. */
export const AUDIT_STREAM_BADGE_TONE: Record<AuditStream, string> = {
  APPLICATION: "bg-info/15 text-info-foreground border-info/40",
  INTAKE: "bg-success/15 text-success-foreground border-success/40",
  PII_REVEAL: "bg-warning/15 text-warning-foreground border-warning/40",
  DOCUMENT_ACCESS: "bg-warning/15 text-warning-foreground border-warning/40",
  PRODUCT: "bg-muted text-foreground-muted border-border",
  APP_USER: "bg-primary/10 text-primary border-primary/30",
  API_CLIENT: "bg-secondary/15 text-secondary-foreground border-secondary/40",
  DISBURSEMENT: "bg-accent/15 text-accent-foreground border-accent/40",
  REPORT_ACCESS: "bg-chart-5/15 text-chart-5 border-chart-5/40",
};

// ─── Subject discriminator (drives the deep-link button) ─────────────────────

export type AuditSubjectType =
  | "LOAN_APPLICATION"
  | "BORROWER"
  | "LOAN_DOCUMENT"
  | "LOAN_PRODUCT"
  | "APP_USER"
  | "API_CLIENT";

// ─── Row + response shapes ───────────────────────────────────────────────────

/**
 * Flattened audit row. Composed in the backend from the five distinct
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

export interface AuditEventsResponse {
  items: readonly AuditRow[];
  total: number;
  page: number;
  pageSize: number;
  nextCursor?: string | null;
}

// ─── URL-bound filters ───────────────────────────────────────────────────────

/**
 * URL-bound filter shape for `/audit`. `streams` defaults to ALL when empty.
 * All filters are pushed down to the backend (#76).
 */
const AuditEventsFilters = z.object({
  streams: z.array(AuditStreamSchema).optional(),
  actorId: Uuid.optional(),
  loanApplicationId: Uuid.optional(),
  correlationId: z.string().min(1).max(80).optional(),
  dateFrom: IsoDate.optional(),
  dateTo: IsoDate.optional(),
  page: z.coerce.number().int().min(0).optional(),
  pageSize: z.coerce.number().int().min(5).max(200).optional(),
  cursor: z.string().min(1).max(512).optional(),
  /** Set by the table when a row is opened — surfaces the detail sheet. */
  eventId: z.string().min(1).max(80).optional(),
});

export type AuditEventsFilters = z.infer<typeof AuditEventsFilters>;
