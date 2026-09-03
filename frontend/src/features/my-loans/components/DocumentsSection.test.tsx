import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { DocumentsSection, type DocumentsSectionProps } from "./DocumentsSection";

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

// Fresh, isolated QueryClient per render, retries off so a mocked rejection
// surfaces as an error immediately instead of racing RTL's polling window.
function renderSection(props: DocumentsSectionProps) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  }
  return render(<DocumentsSection {...props} />, { wrapper: Wrapper });
}

beforeEach(() => {
  fetchLspDocumentRequirementsMock.mockReset().mockResolvedValue(requirements);
  listLspSubmittedDocumentsMock.mockReset().mockResolvedValue([]);
  uploadLspDocumentMock.mockReset();
});

describe("DocumentsSection", () => {
  it("renders all eight backend intake document slots including KFS", async () => {
    const { container } = renderSection({ applicationId: "app-1", canUpload: true });

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

    const { container } = renderSection({
      applicationId: "app-1",
      canUpload: false,
      readOnlyReason: "terminal",
    });

    expect(await screen.findByText("Key Facts Statement")).toBeInTheDocument();
    const kfsRow = container.querySelector('[data-document-type="KFS"]');
    expect(kfsRow).not.toBeNull();
    expect(await within(kfsRow as HTMLElement).findByText(/kfs\.pdf/)).toBeInTheDocument();
    expect(
      screen.getByText("Documents are read-only because this loan has reached a final status."),
    ).toBeInTheDocument();
  });

  it("re-maps submitted rows when the backend taxonomy resolves after the submitted list, without re-fetching the submitted list", async () => {
    // Held open rather than timed out: the ordering under test (submitted list
    // first, requirements second) is the point, so it is driven explicitly
    // instead of racing a wall clock against RTL's polling window.
    let resolveRequirements!: (rows: typeof requirements) => void;
    fetchLspDocumentRequirementsMock.mockImplementation(
      () =>
        new Promise<typeof requirements>((resolve) => {
          resolveRequirements = resolve;
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

    const { container } = renderSection({ applicationId: "app-1", canUpload: false });

    // Requirements are still pending, so the slots come from the static
    // fallback taxonomy — and the submitted KFS is already mapped onto one.
    expect(await screen.findByText("Key Facts Statement")).toBeInTheDocument();
    const fallbackRow = container.querySelector('[data-document-type="KFS"]');
    expect(fallbackRow).not.toBeNull();
    expect(await within(fallbackRow as HTMLElement).findByText(/kfs\.pdf/)).toBeInTheDocument();

    // A distinct display name makes the late re-map observable: without it the
    // backend labels are identical to the fallback ones.
    resolveRequirements(
      requirements.map((row) =>
        row.code === "KFS" ? { ...row, displayName: "Key Facts Statement (KFS)" } : row,
      ),
    );

    expect(await screen.findByText("Key Facts Statement (KFS)")).toBeInTheDocument();
    const remappedRow = container.querySelector('[data-document-type="KFS"]');
    expect(await within(remappedRow as HTMLElement).findByText(/kfs\.pdf/)).toBeInTheDocument();

    // The defect this conversion fixes: the label resolving late used to be
    // wired as a dependency of the submitted-documents fetch, so it fired
    // twice. `docLabels` is derived now, not a fetch dependency — one call.
    expect(listLspSubmittedDocumentsMock).toHaveBeenCalledTimes(1);
  });

  it("falls back to the static taxonomy when the document-requirements fetch fails", async () => {
    fetchLspDocumentRequirementsMock.mockRejectedValue(new Error("network down"));

    const { container } = renderSection({ applicationId: "app-1", canUpload: true });

    expect(await screen.findByText("Key Facts Statement")).toBeInTheDocument();
    expect(
      container.querySelectorAll(
        '[data-slot="lsp-document-row"]:not([data-document-type="OTHER"])',
      ),
    ).toHaveLength(8);
  });

  it("keeps an uploaded document visible when the submitted-documents fetch fails (upload-only state)", async () => {
    listLspSubmittedDocumentsMock.mockRejectedValue(new Error("network down"));
    uploadLspDocumentMock.mockResolvedValue({
      id: "uploaded-1",
      documentType: "PAN",
      documentDisplayName: "PAN",
      status: "SUBMITTED",
      fileName: "pan.pdf",
      contentType: "application/pdf",
      fileSizeBytes: 1024,
      uploadedAt: "2026-08-06T10:00:00.000Z",
      uploadedByUsername: "lsp.writer",
    });

    const { container } = renderSection({ applicationId: "app-1", canUpload: true });
    await screen.findByText("PAN");

    const panRow = container.querySelector('[data-document-type="PAN"]') as HTMLElement;
    expect(panRow).not.toBeNull();
    expect(within(panRow).getByText("No file uploaded yet.")).toBeInTheDocument();

    const input = within(panRow).getByLabelText("Upload PAN");
    const file = new File(["dummy"], "pan.pdf", { type: "application/pdf" });
    await userEvent.upload(input, file);

    expect(uploadLspDocumentMock).toHaveBeenCalledWith(
      expect.objectContaining({ applicationId: "app-1", documentType: "PAN" }),
    );
    expect(await within(panRow).findByText(/pan\.pdf/)).toBeInTheDocument();
  });
});
