import { describe, expect, it } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/utils";
import { MisPreviewTable } from "./MisPreviewTable";
import type { MisPreviewResponseDto } from "../types";

const PREVIEW: MisPreviewResponseDto = {
  items: [
    {
      loanId: "00000000-0000-4000-8000-000000000101",
      externalLoanId: "EXT-1",
      borrowerName: "A•••a Devi",
      borrowerId: "00000000-0000-4000-8000-000000000201",
      lspCode: "APEX",
      lspName: "Apex NBFC",
      productCode: "PL-A",
      productName: "Personal Loan A",
      accountNumber: "BHAW-000101",
      amount: 250_000,
      status: "UNDER_REPAYMENT",
      loanStatusDisplay: "UNDER_REPAYMENT",
      disbursalDate: "2026-01-15",
      applicationCreatedAt: "2026-01-01",
      dpd: 12,
      delinquencyBucket: "B1_30",
      year: 2026,
      processingFee: 2_500,
      disbursalAmount: 247_500,
      interestPct: 14.5,
      tenureMonths: 12,
      emiAmount: 22_500,
      overdueAmount: 5_000,
      closureDate: null,
      closureReason: null,
      foreclosureDate: null,
      foreclosedAmount: null,
      address: "12 MG Road",
      pan: "ABCDE1234F",
      aadhaar: "XXXXXXXX1234",
      gender: "FEMALE",
      state: "KA",
      zip: "560001",
      ifsc: "HDFC0001234",
      bankAccount: "XXXX3210",
      profession: "SALARIED",
      income: 85_000,
      installments: [
        {
          installmentNumber: 1,
          dueDate: "2026-02-15",
          installmentAmount: 22_500,
          paidAmount: 22_500,
          received: true,
        },
      ],
    },
  ],
  total: 1,
  page: 0,
  pageSize: 25,
};

describe("MisPreviewTable (Gap #10)", () => {
  it("renders curated summary columns and export guidance", () => {
    renderWithProviders(
      <MisPreviewTable
        data={PREVIEW}
        isLoading={false}
        filters={{ page: 0, pageSize: 25 }}
        onFiltersChange={() => {}}
      />,
    );

    expect(screen.getByText("Apex NBFC")).toBeInTheDocument();
    expect(screen.getByText("Personal Loan A")).toBeInTheDocument();
    expect(screen.getByText(/Key portfolio columns only/i)).toBeInTheDocument();
    expect(screen.queryByText("EMI 1")).not.toBeInTheDocument();
    expect(document.querySelector('[data-pii="aadhaar"]')).toBeNull();
  });
});
