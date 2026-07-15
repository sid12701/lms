import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { SessionProvider } from "@/features/auth/session-context";
import type { Session } from "@/features/auth/session-types";
import { MyLoanDetailPage } from "./detail-page";
import { makeMyLoanDetail } from "./test-utils";

const fetchMyLoanDetailMock = vi.fn();
const fetchInvalidReasonsMock = vi.fn();
const listLspSubmittedDocumentsMock = vi.fn();
const uploadLspDocumentMock = vi.fn();
const fetchLspDocumentRequirementsMock = vi.fn();

vi.mock("./api", () => ({
  fetchMyLoanDetail: (...args: unknown[]) => fetchMyLoanDetailMock(...args),
  fetchInvalidReasons: (...args: unknown[]) => fetchInvalidReasonsMock(...args),
  listLspSubmittedDocuments: (...args: unknown[]) => listLspSubmittedDocumentsMock(...args),
  uploadLspDocument: (...args: unknown[]) => uploadLspDocumentMock(...args),
  fetchLspDocumentRequirements: (...args: unknown[]) => fetchLspDocumentRequirementsMock(...args),
  fetchMyLoanRepaymentSchedule: vi.fn().mockResolvedValue([]),
  fetchMyLoanPayments: vi.fn().mockResolvedValue([]),
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

const detail = makeMyLoanDetail();

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
  fetchLspDocumentRequirementsMock.mockReset().mockResolvedValue([
    {
      code: "PAN_CARD",
      displayName: "PAN",
      requiredForApproval: true,
      requiredForDisbursement: true,
    },
    {
      code: "AADHAAR_FILE",
      displayName: "Aadhaar",
      requiredForApproval: true,
      requiredForDisbursement: true,
    },
    {
      code: "ADDRESS_PROOF",
      displayName: "Address proof",
      requiredForApproval: true,
      requiredForDisbursement: true,
    },
    {
      code: "INCOME_PROOF",
      displayName: "Income proof",
      requiredForApproval: true,
      requiredForDisbursement: true,
    },
    {
      code: "BANK_STATEMENT",
      displayName: "Bank statement",
      requiredForApproval: true,
      requiredForDisbursement: true,
    },
    {
      code: "SELFIE_PHOTOGRAPH",
      displayName: "Photograph",
      requiredForApproval: true,
      requiredForDisbursement: true,
    },
    {
      code: "KFS",
      displayName: "Key Facts Statement",
      requiredForApproval: false,
      requiredForDisbursement: true,
    },
    {
      code: "LOAN_AGREEMENT",
      displayName: "Loan agreement",
      requiredForApproval: false,
      requiredForDisbursement: true,
    },
  ]);
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
    expect(screen.getByText("Documents are read-only for your access level.")).toBeInTheDocument();
  });

  it("shows mutation controls to LSP write users", async () => {
    renderPage("LSP_UI_WRITE");

    expect(await screen.findByRole("heading", { name: "Aarav Singh" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /mark invalid/i })).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: /^upload$/i }).length).toBeGreaterThan(0);
    expect(screen.getByRole("button", { name: /^replace$/i })).toBeInTheDocument();
  });
});
