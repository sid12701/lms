/**
 * Test-only fixtures for audit components. Kept inside the audit folder
 * as a presentational test seam with stable, pre-canned UUIDs and ISO
 * timestamps so snapshot/assertion tests remain deterministic.
 */
import type { AuditEvent } from "./types";

const ID_APP = "00000000-0000-4000-8000-000000000001";
const ID_INTAKE = "00000000-0000-4000-8000-000000000002";
const ID_PII = "00000000-0000-4000-8000-000000000003";
const ID_DOC = "00000000-0000-4000-8000-000000000004";
const ID_PRODUCT = "00000000-0000-4000-8000-000000000005";

const ACTOR_ID = "11111111-1111-4000-8000-111111111111";
const APP_ID = "22222222-2222-4000-8000-222222222222";
const SUBJECT_ID = "33333333-3333-4000-8000-333333333333";
const PRODUCT_ID = "44444444-4444-4000-8000-444444444444";
const DOC_ID = "55555555-5555-4000-8000-555555555555";

const CORR_ID = "99999999-9999-4000-8000-999999999999";

export const FIXTURE_NOW = new Date("2026-05-10T12:00:00.000Z");

export const FIXTURE_APPLICATION = {
  kind: "APPLICATION",
  event: {
    id: ID_APP,
    applicationId: APP_ID,
    fromStatus: "UNDER_REVIEW",
    toStatus: "APPROVED",
    action: "approve",
    actorId: ACTOR_ID,
    actorRole: "OPS_USER",
    channel: "UI",
    correlationId: CORR_ID,
    reason: null,
    createdAt: "2026-05-10T11:55:00.000Z",
  },
} as const satisfies AuditEvent;

export const FIXTURE_INTAKE = {
  kind: "INTAKE",
  event: {
    id: ID_INTAKE,
    applicationId: APP_ID,
    snapshotJson: { externalLoanId: "EXT-001", requestedAmount: 250000 },
    actorId: ACTOR_ID,
    channel: "API",
    correlationId: CORR_ID,
    createdAt: "2026-05-10T10:00:00.000Z",
  },
} as const satisfies AuditEvent;

const LONG_REASON =
  "Verifying borrower identity ahead of disbursement; PAN cross-check required by ops standing order BCO-2026-04-18 and the operations runbook updated last quarter.";

export const FIXTURE_PII_LONG_REASON = {
  kind: "PII_REVEAL",
  event: {
    id: ID_PII,
    subjectType: "BORROWER",
    subjectId: SUBJECT_ID,
    fieldName: "PAN",
    actorId: ACTOR_ID,
    actorRole: "SYSTEM_ADMIN",
    reason: LONG_REASON,
    correlationId: CORR_ID,
    revealedAt: "2026-05-10T09:00:00.000Z",
  },
} as const satisfies AuditEvent;

export const FIXTURE_PII_SHORT_REASON = {
  kind: "PII_REVEAL",
  event: {
    id: "00000000-0000-4000-8000-00000000000a",
    subjectType: "BORROWER",
    subjectId: SUBJECT_ID,
    fieldName: "MOBILE",
    actorId: ACTOR_ID,
    actorRole: "OPS_USER",
    reason: "ok",
    correlationId: CORR_ID,
    revealedAt: "2026-05-10T08:30:00.000Z",
  },
} as const satisfies AuditEvent;

export const FIXTURE_DOCUMENT = {
  kind: "DOCUMENT_ACCESS",
  event: {
    id: ID_DOC,
    documentId: DOC_ID,
    applicationId: APP_ID,
    action: "DOWNLOADED",
    actorId: ACTOR_ID,
    actorRole: "OPS_USER",
    correlationId: CORR_ID,
    accessedAt: "2026-05-10T08:00:00.000Z",
  },
} as const satisfies AuditEvent;

export const FIXTURE_PRODUCT = {
  kind: "PRODUCT",
  event: {
    id: ID_PRODUCT,
    productId: PRODUCT_ID,
    action: "MAPPING_CHANGED",
    before: { lspId: "old" },
    after: { lspId: "new" },
    actorId: ACTOR_ID,
    actorRole: "PRODUCT_ADMIN",
    correlationId: CORR_ID,
    createdAt: "2026-05-10T07:00:00.000Z",
  },
} as const satisfies AuditEvent;

export const ALL_FIXTURES: AuditEvent[] = [
  FIXTURE_APPLICATION,
  FIXTURE_INTAKE,
  FIXTURE_PII_LONG_REASON,
  FIXTURE_PII_SHORT_REASON,
  FIXTURE_DOCUMENT,
  FIXTURE_PRODUCT,
];
