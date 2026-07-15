import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, within } from "@testing-library/react";
import { DocumentsSection } from "./DocumentsSection";

const listLspSubmittedDocumentsMock = vi.fn();
const uploadLspDocumentMock = vi.fn();
const fetchLspDocumentRequirementsMock = vi.fn();

vi.mock("../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api")>();
  return {
    ...actual,
    listLspSubmittedDocuments: (...args: unknown[]) => listLspSubmittedDocumentsMock(...args),
    uploadLspDocument: (...args: unknown[]) => uploadLspDocumentMock(...args),
    fetchLspDocumentRequirements: (...args: unknown[]) => fetchLspDocumentRequirementsMock(...args),
  };
});

const requirements = [
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
];

beforeEach(() => {
  fetchLspDocumentRequirementsMock.mockReset().mockResolvedValue(requirements);
  listLspSubmittedDocumentsMock.mockReset().mockResolvedValue([]);
  uploadLspDocumentMock.mockReset();
});

describe("DocumentsSection", () => {
  it("renders all eight backend intake document slots including KFS", async () => {
    const { container } = render(<DocumentsSection applicationId="app-1" canUpload />);

    expect(await screen.findByText("Key Facts Statement")).toBeInTheDocument();
    expect(screen.getByText("Loan agreement")).toBeInTheDocument();
    expect(
      container.querySelectorAll(
        '[data-slot="lsp-document-row"]:not([data-document-type="OTHER"])',
      ),
    ).toHaveLength(8);
  });

  it("maps a submitted KFS row to the KFS slot instead of treating it as missing PAN", async () => {
    listLspSubmittedDocumentsMock.mockResolvedValue([
      {
        documentType: "KFS",
        status: "SUBMITTED",
        fileName: "kfs.pdf",
        contentType: "application/pdf",
        note: null,
        uploadedAt: "2026-06-21T13:15:00.000Z",
        uploadedByUsername: "lsp.writer",
      },
    ]);

    const { container } = render(
      <DocumentsSection applicationId="app-1" canUpload={false} readOnlyReason="terminal" />,
    );

    expect(await screen.findByText("Key Facts Statement")).toBeInTheDocument();
    const kfsRow = container.querySelector('[data-document-type="KFS"]');
    expect(kfsRow).not.toBeNull();
    expect(within(kfsRow as HTMLElement).getByText(/kfs\.pdf/)).toBeInTheDocument();
    expect(
      screen.getByText("Documents are read-only because this loan has reached a final status."),
    ).toBeInTheDocument();
  });

  it("re-maps submitted rows when document labels load after requirements", async () => {
    fetchLspDocumentRequirementsMock.mockImplementation(
      () =>
        new Promise((resolve) => {
          setTimeout(() => resolve(requirements), 25);
        }),
    );
    listLspSubmittedDocumentsMock.mockResolvedValue([
      {
        documentType: "KFS",
        status: "SUBMITTED",
        fileName: "kfs.pdf",
        contentType: "application/pdf",
        note: null,
        uploadedAt: "2026-06-21T13:15:00.000Z",
        uploadedByUsername: "lsp.writer",
      },
    ]);

    const { container } = render(<DocumentsSection applicationId="app-1" canUpload={false} />);

    expect(await screen.findByText("Key Facts Statement")).toBeInTheDocument();
    const kfsRow = container.querySelector('[data-document-type="KFS"]');
    expect(kfsRow).not.toBeNull();
    expect(within(kfsRow as HTMLElement).getByText(/kfs\.pdf/)).toBeInTheDocument();
  });
});
