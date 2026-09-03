/**
 * Discriminated-union view model for the five distinct audit streams
 * (BRD §6.5, BR-7). Each kind wraps the matching Zod-inferred event type
 * 1:1 — so consumers can lift backend or test fixtures directly into a
 * timeline without re-shaping.
 *
 * Keeping the union narrow (kind discriminator + event payload) means the
 * timeline node can switch on `event.kind` and TypeScript will narrow
 * `event.event` to the correct row schema with no casts.
 */
import type {
  ApplicationAuditEvent,
  DocumentAccessEvent,
  IntakeAuditEvent,
  PiiRevealEvent,
  ProductAuditEvent,
  Role,
} from "@/types";

export type AuditStreamKind =
  | "APPLICATION"
  | "INTAKE"
  | "PII_REVEAL"
  | "DOCUMENT_ACCESS"
  | "PRODUCT";

export type AuditEvent =
  | { kind: "APPLICATION"; event: ApplicationAuditEvent }
  | { kind: "INTAKE"; event: IntakeAuditEvent }
  | { kind: "PII_REVEAL"; event: PiiRevealEvent }
  | { kind: "DOCUMENT_ACCESS"; event: DocumentAccessEvent }
  | { kind: "PRODUCT"; event: ProductAuditEvent };

/**
 * Shared metadata projected from every event regardless of stream — the
 * timeline header relies on these fields being present in every node.
 */
export interface AuditEventCommon {
  id: string;
  timestamp: string;
  actorId: string;
  /** IntakeAuditEvent has no actorRole; the node renders "—" for those rows. */
  actorRole: Role | null;
  correlationId: string;
}

export const AUDIT_STREAM_LABEL: Record<AuditStreamKind, string> = {
  APPLICATION: "Application",
  INTAKE: "Intake",
  PII_REVEAL: "PII reveal",
  DOCUMENT_ACCESS: "Document access",
  PRODUCT: "Product",
};

/** Return the timestamp ISO regardless of the event-specific column name. */
export function getAuditTimestamp(entry: AuditEvent): string {
  switch (entry.kind) {
    case "APPLICATION":
    case "INTAKE":
    case "PRODUCT":
      return entry.event.createdAt;
    case "PII_REVEAL":
      return entry.event.revealedAt;
    case "DOCUMENT_ACCESS":
      return entry.event.accessedAt;
  }
}

/** Project the columns the timeline header always needs. */
export function getAuditCommon(entry: AuditEvent): AuditEventCommon {
  switch (entry.kind) {
    case "APPLICATION":
      return {
        id: entry.event.id,
        timestamp: entry.event.createdAt,
        actorId: entry.event.actorId,
        actorRole: entry.event.actorRole,
        correlationId: entry.event.correlationId,
      };
    case "INTAKE":
      return {
        id: entry.event.id,
        timestamp: entry.event.createdAt,
        actorId: entry.event.actorId,
        // Intake events are recorded under the channel actor only; the
        // schema does not capture an explicit role, so we surface null.
        actorRole: null,
        correlationId: entry.event.correlationId,
      };
    case "PII_REVEAL":
      return {
        id: entry.event.id,
        timestamp: entry.event.revealedAt,
        actorId: entry.event.actorId,
        actorRole: entry.event.actorRole,
        correlationId: entry.event.correlationId,
      };
    case "DOCUMENT_ACCESS":
      return {
        id: entry.event.id,
        timestamp: entry.event.accessedAt,
        actorId: entry.event.actorId,
        actorRole: entry.event.actorRole,
        correlationId: entry.event.correlationId,
      };
    case "PRODUCT":
      return {
        id: entry.event.id,
        timestamp: entry.event.createdAt,
        actorId: entry.event.actorId,
        actorRole: entry.event.actorRole,
        correlationId: entry.event.correlationId,
      };
  }
}
