import { describe, expect, it } from "vitest";
import {
  mapBackendPreviewRowToMisPreviewRow,
  type BackendPreviewRow,
} from "./api";

const BACKEND_ROW: BackendPreviewRow = {
  lspCode: "APEX",
  lspName: "Apex NBFC",
  applicationId: "00000000-0000-4000-8000-000000000101",
  externalLoanId: "EXT-101",
  borrowerFullName: "A•••a Devi",
  productCode: "PL-A",
  productName: "Personal Loan A",
  accountNumber: "BHAW-000101",
  principalAmount: 250_000,
  accountStatus: "UNDER_REPAYMENT",
  disbursalDate: "2026-01-15",
  delinquencyBucket: "DPD_1_30",
  overdueAmount: 5_000,
  closureReason: null,
  closedDate: null,
  applicationCreatedAt: "2026-01-01",
  loanYear: 2026,
  processingFeeAmount: 2_500,
  disbursalAmount: 247_500,
  interestRate: 14.5,
  tenureMonths: 12,
  borrowerId: "00000000-0000-4000-8000-000000000201",
  perEmiAmount: 22_500,
  installments: [
    {
      installmentNumber: 1,
      dueDate: "2026-02-15",
      installmentAmount: 22_500,
      paidAmount: 22_500,
      received: true,
    },
    {
      installmentNumber: 2,
      dueDate: "2026-03-15",
      installmentAmount: 22_500,
      paidAmount: 0,
      received: false,
    },
  ],
  loanStatusDisplay: "UNDER_REPAYMENT",
  foreclosedRepaidAmount: null,
  foreclosureDate: null,
  normalClosureDate: null,
  daysPastDue: 12,
  customerName: "A•••a Devi",
  address: "12 MG Road, Bengaluru",
  zipCode: "560001",
  borrowerState: "KA",
  ifscCode: "HDFC0001234",
  bankAccountNumber: "XXXX3210",
  gender: "FEMALE",
  aadharNumber: "XXXXXXXX1234",
  panNumber: "ABCDE1234F",
  profession: "SALARIED",
  income: 85_000,
};

describe("mapBackendPreviewRowToMisPreviewRow (Gap #10)", () => {
  it("maps every backend portfolio-MIS field including installments", () => {
    const row = mapBackendPreviewRowToMisPreviewRow(BACKEND_ROW);
    expect(row.loanId).toBe(BACKEND_ROW.applicationId);
    expect(row.lspName).toBe("Apex NBFC");
    expect(row.productName).toBe("Personal Loan A");
    expect(row.accountNumber).toBe("BHAW-000101");
    expect(row.borrowerId).toBe(BACKEND_ROW.borrowerId);
    expect(row.loanStatusDisplay).toBe("UNDER_REPAYMENT");
    expect(row.applicationCreatedAt).toBe("2026-01-01");
    expect(row.closureReason).toBeNull();
    expect(row.address).toBe("12 MG Road, Bengaluru");
    expect(row.delinquencyBucket).toBe("B1_30");
    expect(row.installments).toHaveLength(2);
    expect(row.installments?.[0]?.received).toBe(true);
    expect(row.aadhaar).toBe("XXXXXXXX1234");
    expect(row.bankAccount).toBe("XXXX3210");
  });

  it("maps backend DPD bucket names to frontend DelinquencyBucket ids", () => {
    expect(
      mapBackendPreviewRowToMisPreviewRow({
        ...BACKEND_ROW,
        delinquencyBucket: "CURRENT",
      }).delinquencyBucket,
    ).toBe("B0");
    expect(
      mapBackendPreviewRowToMisPreviewRow({
        ...BACKEND_ROW,
        delinquencyBucket: "DPD_90_PLUS",
      }).delinquencyBucket,
    ).toBe("B90_PLUS");
  });
});
