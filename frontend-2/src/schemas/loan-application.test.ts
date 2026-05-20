import { describe, expect, it } from "vitest";
import {
  LoanApplication,
  LoanDocument,
  LoanDocumentFileMeta,
  LoanDocumentStatus,
  LoanDocumentType,
  LoanStatus,
  SourceChannel,
} from "./loan-application";

const UUID = "550e8400-e29b-41d4-a716-446655440000";
const NOW = "2026-05-09T10:00:00.000Z";

function validApplication() {
  return {
    id: UUID,
    externalLoanId: "EXT-1",
    borrowerId: UUID,
    lspId: UUID,
    productId: UUID,
    requestedAmount: 50_000,
    tenureMonths: 12,
    status: "INITIATED" as const,
    sourceChannel: "UI" as const,
    assignedTo: null,
    createdAt: NOW,
    updatedAt: NOW,
    invalidatedAt: null,
    invalidReason: null,
  };
}

describe("LoanStatus enum", () => {
  it("contains every plan §5.5 + UI pages.md status", () => {
    for (const s of [
      "INITIATED",
      "KYC_PENDING",
      "DOCS_PENDING",
      "UNDER_REVIEW",
      "AWAITING_APPROVAL",
      "APPROVED",
      "APPROVED_PENDING_DISBURSAL",
      "REJECTED",
      "DISBURSEMENT_IN_PROGRESS",
      "DISBURSED",
      "UNDER_REPAYMENT",
      "PARTIALLY_PAID",
      "DELINQUENT",
      "FORECLOSURE_REQUESTED",
      "FORECLOSURE_APPROVED",
      "FORECLOSED",
      "FULLY_REPAID",
      "CLOSED",
      "CANCELLED",
      "INVALIDATED",
    ]) {
      expect(LoanStatus.safeParse(s).success).toBe(true);
    }
  });

  it("rejects unknown statuses", () => {
    expect(LoanStatus.safeParse("PENDING").success).toBe(false);
    expect(LoanStatus.safeParse("").success).toBe(false);
  });
});

describe("SourceChannel", () => {
  it("only allows UI / API / WEBHOOK", () => {
    expect(SourceChannel.safeParse("UI").success).toBe(true);
    expect(SourceChannel.safeParse("API").success).toBe(true);
    expect(SourceChannel.safeParse("WEBHOOK").success).toBe(true);
    expect(SourceChannel.safeParse("EMAIL").success).toBe(false);
    expect(SourceChannel.safeParse("SCHEDULER").success).toBe(false);
  });
});

describe("LoanApplication", () => {
  it("accepts a fully-valid application", () => {
    expect(LoanApplication.safeParse(validApplication()).success).toBe(true);
  });

  it("rejects requestedAmount === 0 (must be positive)", () => {
    expect(LoanApplication.safeParse({ ...validApplication(), requestedAmount: 0 }).success).toBe(
      false,
    );
  });

  it("rejects tenureMonths > 360", () => {
    expect(LoanApplication.safeParse({ ...validApplication(), tenureMonths: 361 }).success).toBe(
      false,
    );
  });

  it("accepts null externalLoanId", () => {
    expect(LoanApplication.safeParse({ ...validApplication(), externalLoanId: null }).success).toBe(
      true,
    );
  });
});

describe("LoanDocument enums + schema", () => {
  it("LoanDocumentType accepts canonical types", () => {
    for (const t of [
      "PAN",
      "AADHAAR",
      "ADDRESS_PROOF",
      "INCOME_PROOF",
      "BANK_STATEMENT",
      "PHOTOGRAPH",
      "LOAN_AGREEMENT",
      "OTHER",
    ]) {
      expect(LoanDocumentType.safeParse(t).success).toBe(true);
    }
    expect(LoanDocumentType.safeParse("ID_CARD").success).toBe(false);
  });

  it("LoanDocumentStatus accepts the lifecycle 4", () => {
    for (const s of ["PENDING", "UPLOADED", "VERIFIED", "REJECTED"]) {
      expect(LoanDocumentStatus.safeParse(s).success).toBe(true);
    }
    expect(LoanDocumentStatus.safeParse("EXPIRED").success).toBe(false);
  });

  it("LoanDocumentFileMeta caps size at 100 MB", () => {
    expect(
      LoanDocumentFileMeta.safeParse({
        storageKey: "k",
        mime: "application/pdf",
        size: 100 * 1024 * 1024,
        checksum: "abc",
      }).success,
    ).toBe(true);
    expect(
      LoanDocumentFileMeta.safeParse({
        storageKey: "k",
        mime: "application/pdf",
        size: 100 * 1024 * 1024 + 1,
        checksum: "abc",
      }).success,
    ).toBe(false);
  });

  it("BR-2/BR-3: LoanDocument exposes both gating booleans", () => {
    const r = LoanDocument.safeParse({
      id: UUID,
      applicationId: UUID,
      type: "PAN",
      displayName: "Borrower PAN",
      requiredForApproval: true,
      requiredForDisbursement: false,
      status: "PENDING",
      notes: null,
      fileMeta: null,
      uploadedAt: null,
      uploadedBy: null,
    });
    expect(r.success).toBe(true);
  });

  it("rejects document without applicationId", () => {
    const bad = {
      id: UUID,
      type: "PAN",
      displayName: "x",
      requiredForApproval: true,
      requiredForDisbursement: false,
      status: "PENDING",
      notes: null,
      fileMeta: null,
      uploadedAt: null,
      uploadedBy: null,
    };
    expect(LoanDocument.safeParse(bad).success).toBe(false);
  });
});
