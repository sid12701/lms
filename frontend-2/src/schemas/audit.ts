/**
 * Five distinct audit streams per BRD §6.5.
 *
 * BR-7 enforcement: every PII reveal AND every document access must
 * append exactly one row to the corresponding stream. Status transitions
 * append to ApplicationAuditEvent, intake snapshots append to
 * IntakeAuditEvent, product changes append to ProductAuditEvent.
 */
import { z } from "zod";
import { Iso8601, Uuid } from "./common";
import { Channel, Role } from "./role";
import { LoanStatus } from "./loan-application";

export const ApplicationAuditEvent = z.object({
  id: Uuid,
  applicationId: Uuid,
  fromStatus: LoanStatus.nullable(),
  toStatus: LoanStatus,
  /** Verb that produced the transition (e.g. "submit-for-review"). */
  action: z.string().min(1).max(80),
  actorId: Uuid,
  actorRole: Role,
  channel: Channel,
  correlationId: Uuid,
  reason: z.string().max(1000).nullable(),
  contextJson: z.record(z.unknown()).optional(),
  createdAt: Iso8601,
});
export type ApplicationAuditEvent = z.infer<typeof ApplicationAuditEvent>;

export const IntakeAuditEvent = z.object({
  id: Uuid,
  applicationId: Uuid,
  /** Full payload snapshot at intake. Stored as object for queryability. */
  snapshotJson: z.record(z.unknown()),
  actorId: Uuid,
  channel: Channel,
  correlationId: Uuid,
  createdAt: Iso8601,
});
export type IntakeAuditEvent = z.infer<typeof IntakeAuditEvent>;

export const PiiSubjectType = z.enum(["BORROWER"]);
export type PiiSubjectType = z.infer<typeof PiiSubjectType>;

export const PiiFieldName = z.enum(["PAN", "AADHAAR", "MOBILE", "ACCOUNT_NUMBER", "EMAIL"]);
export type PiiFieldName = z.infer<typeof PiiFieldName>;

export const PiiRevealEvent = z.object({
  id: Uuid,
  subjectType: PiiSubjectType,
  subjectId: Uuid,
  fieldName: PiiFieldName,
  actorId: Uuid,
  actorRole: Role,
  /** Reason is mandatory per BR-7 (no silent reads). */
  reason: z.string().min(1).max(500),
  correlationId: Uuid,
  revealedAt: Iso8601,
});
export type PiiRevealEvent = z.infer<typeof PiiRevealEvent>;

export const DocumentAccessAction = z.enum(["VIEWED", "DOWNLOADED", "VERIFIED", "REJECTED"]);
export type DocumentAccessAction = z.infer<typeof DocumentAccessAction>;

export const DocumentAccessEvent = z.object({
  id: Uuid,
  documentId: Uuid,
  applicationId: Uuid,
  action: DocumentAccessAction,
  actorId: Uuid,
  actorRole: Role,
  correlationId: Uuid,
  accessedAt: Iso8601,
});
export type DocumentAccessEvent = z.infer<typeof DocumentAccessEvent>;

export const ProductAuditAction = z.enum([
  "CREATED",
  "UPDATED",
  "MAPPING_CHANGED",
  "STATUS_CHANGED",
]);
export type ProductAuditAction = z.infer<typeof ProductAuditAction>;

export const ProductAuditEvent = z.object({
  id: Uuid,
  productId: Uuid,
  action: ProductAuditAction,
  before: z.record(z.unknown()).nullable(),
  after: z.record(z.unknown()).nullable(),
  actorId: Uuid,
  actorRole: Role,
  correlationId: Uuid,
  createdAt: Iso8601,
});
export type ProductAuditEvent = z.infer<typeof ProductAuditEvent>;
