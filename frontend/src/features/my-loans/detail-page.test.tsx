import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { SessionProvider } from "@/features/auth/session-context";
import type { Session } from "@/features/auth/session-types";
import { MyLoanDetailPage } from "./detail-page";
import type { MyLoanDetail } from "./api";

const fetchMyLoanDetailMock = vi.fn();
const fetchInvalidReasonsMock = vi.fn();
const listLspSubmittedDocumentsMock = vi.fn();
const uploadLspDocumentMock = vi.fn();

vi.mock("./api", () => ({
  fetchMyLoanDetail: (...args: unknown[]) => fetchMyLoanDetailMock(...args),
  fetchInvalidReasons: (...args: unknown[]) => fetchInvalidReasonsMock(...args),
  listLspSubmittedDocuments: (...args: unknown[]) => listLspSubmittedDocumentsMock(...args),
  uploadLspDocument: (...args: unknown[]) => uploadLspDocumentMock(...args),
}));

function sessionFor(role: Session["user"]["role"]): Session {
  return {
    user: {
      id: "aaaaaaaa-1111-4aaa-8aaa-aaaaaaaaaaaa",
      username: role.toLowerCase(),
      role,
      lspId: "bbbbbbbb-2222-4bbb-8bbb-bbbbbbbbbbbb",
      mustChangePassword: false,
    },
    accessToken: "test.token",
    expiresAt: "2030-01-01T00:00:00.000Z",
  };
}

const detail: MyLoanDetail = {
  id: "cccccccc-3333-4ccc-8ccc-cccccccccccc",
  borrowerId: "dddddddd-4444-4ddd-8ddd-dddddddddddd",
  borrowerFullName: "Aarav Singh",
  borrowerPanMasked: "ABCDE1234F",
  borrowerAadhaarMasked: "XXXXXXXX1234",
  borrowerMobile: "9000000000",
  borrowerEmail: "aarav@example.test",
  borrowerDob: "1990-01-01",
  borrowerCity: "Mumbai",
  borrowerState: "Maharashtra",
  productId: "eeeeeeee-5555-4eee-8eee-eeeeeeeeeeee",
  productCode: "DOC-PROD",
  productName: "Doc Product",
  lspId: "bbbbbbbb-2222-4bbb-8bbb-bbbbbbbbbbbb",
  lspCode: "DOC-UP",
  lspName: "Doc Upload Test",
  externalLoanId: "SEED-10-RI05XN",
  requestedAmount: 255000,
  interestRate: 14.5,
  tenureMonths: 12,
  status: "UNDER_REPAYMENT",
  rawStatus: "UNDER_REPAYMENT",
  invalidReasonCode: null,
  invalidReasonText: null,
  invalidatedAt: null,
  createdAt: "2026-06-21T13:15:00.000Z",
  updatedAt: "2026-06-21T13:15:00.000Z",
  loanAccount: null,
};

function renderPage(role: Session["user"]["role"]) {
  return render(
    <MemoryRouter initialEntries={[`/my-loans/${detail.id}`]}>
      <SessionProvider skipBootstrap initialSession={sessionFor(role)}>
        <Routes>
          <Route path="/my-loans/:id" element={<MyLoanDetailPage />} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  fetchMyLoanDetailMock.mockReset().mockResolvedValue(detail);
  fetchInvalidReasonsMock.mockReset().mockResolvedValue([]);
  listLspSubmittedDocumentsMock.mockReset().mockResolvedValue([
    {
      documentType: "BANK_STATEMENT",
      status: "SUBMITTED",
      fileName: "bank_statement.pdf",
      contentType: "application/pdf",
      note: null,
      uploadedAt: "2026-06-21T13:15:00.000Z",
      uploadedByUsername: "lsp.writer",
    },
  ]);
  uploadLspDocumentMock.mockReset();
});

describe("MyLoanDetailPage role actions", () => {
  it("does not show mutation controls to LSP read-only users", async () => {
    renderPage("LSP_UI_READ");

    expect(await screen.findByRole("heading", { name: "Aarav Singh" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /mark invalid/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^upload$/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^replace$/i })).not.toBeInTheDocument();
    expect(screen.getByText("Documents are read-only for your account.")).toBeInTheDocument();
  });

  it("shows mutation controls to LSP write users", async () => {
    renderPage("LSP_UI_WRITE");

    expect(await screen.findByRole("heading", { name: "Aarav Singh" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /mark invalid/i })).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: /^upload$/i }).length).toBeGreaterThan(0);
    expect(screen.getByRole("button", { name: /^replace$/i })).toBeInTheDocument();
  });
});
