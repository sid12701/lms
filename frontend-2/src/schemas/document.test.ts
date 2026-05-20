import { describe, expect, it } from "vitest";
import {
  DOCUMENT_KIND_LABELS,
  Document,
  DocumentKind,
  DocumentStatus,
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
    verifiedAt: null,
    verifiedBy: null,
    rejectionReason: null,
  };
}

describe("DocumentKind", () => {
  it("accepts each canonical kind", () => {
    for (const k of [
      "PAN_CARD",
      "AADHAAR_CARD",
      "BANK_STATEMENT",
      "INCOME_PROOF",
      "LOAN_AGREEMENT",
      "KYC_PHOTO",
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

describe("DocumentStatus", () => {
  it("accepts the four lifecycle states", () => {
    for (const s of ["PENDING", "UPLOADED", "VERIFIED", "REJECTED"]) {
      expect(DocumentStatus.safeParse(s).success).toBe(true);
    }
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
      verifiedAt: null,
      verifiedBy: null,
      rejectionReason: null,
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
      verifiedAt: null,
      verifiedBy: null,
      rejectionReason: null,
    });
    expect(r.requiredForDisbursement).toBe(false);
  });

  it("accepts a verified document", () => {
    const r = Document.safeParse({
      ...validUploadedDocument(),
      status: "VERIFIED",
      verifiedAt: NOW,
      verifiedBy: UUID,
    });
    expect(r.success).toBe(true);
  });

  it("rejects a document with empty rejectionReason string (min 1)", () => {
    const r = Document.safeParse({
      ...validUploadedDocument(),
      status: "REJECTED",
      rejectionReason: "",
    });
    expect(r.success).toBe(false);
  });

  it("accepts a rejected document with a non-empty reason", () => {
    const r = Document.safeParse({
      ...validUploadedDocument(),
      status: "REJECTED",
      rejectionReason: "Image too blurry — please re-upload.",
    });
    expect(r.success).toBe(true);
  });

  it("rejects a rejectionReason longer than 500 chars", () => {
    const r = Document.safeParse({
      ...validUploadedDocument(),
      status: "REJECTED",
      rejectionReason: "x".repeat(501),
    });
    expect(r.success).toBe(false);
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
});
