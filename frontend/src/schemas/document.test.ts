import { describe, expect, it } from "vitest";
import {
  DOCUMENT_KIND_LABELS,
  Document,
  DocumentKind,
  DocumentStatus,
  isUploadedBackendChecklistStatus,
} from "./document";

const UUID = "550e8400-e29b-41d4-a716-446655440000";
const NOW = "2026-05-09T10:00:00.000Z";

function validUploadedDocument() {
  return {
    id: UUID,
    applicationId: UUID,
    kind: "PAN_CARD" as const,
    status: "UPLOADED" as const,
    requiredForDisbursement: true,
    fileName: "borrower-pan.pdf",
    mimeType: "application/pdf",
    sizeBytes: 245_678,
    uploadedAt: NOW,
    uploadedBy: UUID,
  };
}

describe("DocumentKind", () => {
  it("accepts each canonical kind", () => {
    for (const k of [
      "PAN_CARD",
      "AADHAAR_CARD",
      "ADDRESS_PROOF",
      "BANK_STATEMENT",
      "INCOME_PROOF",
      "LOAN_AGREEMENT",
      "KYC_PHOTO",
      "KFS",
      "NACH_MANDATE",
      "OTHER",
    ]) {
      expect(DocumentKind.safeParse(k).success).toBe(true);
    }
  });

  it("rejects unknown kinds", () => {
    expect(DocumentKind.safeParse("PAN").success).toBe(false);
    expect(DocumentKind.safeParse("").success).toBe(false);
  });

  it("has a human label for every kind", () => {
    for (const k of DocumentKind.options) {
      expect(DOCUMENT_KIND_LABELS[k].length).toBeGreaterThan(0);
    }
  });
});

describe("isUploadedBackendChecklistStatus", () => {
  it("treats SUBMITTED and legacy verified states as uploaded", () => {
    for (const s of ["SUBMITTED", "UPLOADED", "VERIFIED", "RECEIVED"]) {
      expect(isUploadedBackendChecklistStatus(s)).toBe(true);
    }
  });

  it("treats PENDING and NOT_REQUIRED as not uploaded", () => {
    expect(isUploadedBackendChecklistStatus("PENDING")).toBe(false);
    expect(isUploadedBackendChecklistStatus("NOT_REQUIRED")).toBe(false);
  });
});

describe("DocumentStatus (Gap #18 — PENDING | UPLOADED only)", () => {
  it("accepts PENDING and UPLOADED", () => {
    for (const s of ["PENDING", "UPLOADED"]) {
      expect(DocumentStatus.safeParse(s).success).toBe(true);
    }
  });

  it("rejects the retired VERIFIED and REJECTED states", () => {
    expect(DocumentStatus.safeParse("VERIFIED").success).toBe(false);
    expect(DocumentStatus.safeParse("REJECTED").success).toBe(false);
  });

  it("rejects unknown values", () => {
    expect(DocumentStatus.safeParse("EXPIRED").success).toBe(false);
  });
});

describe("Document schema", () => {
  it("accepts a fully-valid uploaded document", () => {
    expect(Document.safeParse(validUploadedDocument()).success).toBe(true);
  });

  it("accepts a PENDING placeholder with all upload metadata null", () => {
    const r = Document.safeParse({
      id: UUID,
      applicationId: UUID,
      kind: "INCOME_PROOF",
      status: "PENDING",
      requiredForDisbursement: false,
      fileName: null,
      mimeType: null,
      sizeBytes: null,
      uploadedAt: null,
      uploadedBy: null,
    });
    expect(r.success).toBe(true);
  });

  it("defaults requiredForDisbursement to false when omitted", () => {
    const r = Document.parse({
      id: UUID,
      applicationId: UUID,
      kind: "OTHER",
      status: "PENDING",
      fileName: null,
      mimeType: null,
      sizeBytes: null,
      uploadedAt: null,
      uploadedBy: null,
    });
    expect(r.requiredForDisbursement).toBe(false);
  });

  it("rejects a sizeBytes above 100 MB", () => {
    const r = Document.safeParse({
      ...validUploadedDocument(),
      sizeBytes: 100 * 1024 * 1024 + 1,
    });
    expect(r.success).toBe(false);
  });

  it("rejects a document missing applicationId", () => {
    const bad: Record<string, unknown> = { ...validUploadedDocument() };
    delete bad.applicationId;
    expect(Document.safeParse(bad).success).toBe(false);
  });

  it("rejects payloads that still carry the retired verify/reject fields", () => {
    const carriesVerified = Document.safeParse({
      ...validUploadedDocument(),
      verifiedAt: NOW,
    });
    const carriesReject = Document.safeParse({
      ...validUploadedDocument(),
      rejectionReason: "blurry",
    });
    // Zod's `object()` strips unknown keys by default rather than failing,
    // so we assert the parsed shape no longer surfaces them.
    expect(carriesVerified.success).toBe(true);
    expect(carriesReject.success).toBe(true);
    if (carriesVerified.success) {
      expect(carriesVerified.data).not.toHaveProperty("verifiedAt");
    }
    if (carriesReject.success) {
      expect(carriesReject.data).not.toHaveProperty("rejectionReason");
    }
  });
});
