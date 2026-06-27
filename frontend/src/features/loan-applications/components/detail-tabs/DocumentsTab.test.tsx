/**
 * DocumentsTab tests — hook module is mocked.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { axe } from "vitest-axe";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/utils";
import type { LoanDocument } from "@/types";

const requestBlobMock = vi.hoisted(() => vi.fn());
const useDocumentsMock = vi.fn();

vi.mock("@/lib/api/http-client", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api/http-client")>();
  return {
    ...actual,
    requestBlob: requestBlobMock,
  };
});

vi.mock("../../hooks/useLoanApplicationDocuments", () => ({
  useLoanApplicationDocuments: () => useDocumentsMock(),
  loanApplicationDocumentsQueryKey: (id: string) => ["loan-application", id, "documents"],
}));

import { ApiError } from "@/lib/api/http-client";
import { DocumentsTab, adaptLoanDocumentToDocument } from "./DocumentsTab";
import { toast } from "sonner";

vi.mock("sonner", () => ({
  toast: {
    error: vi.fn(),
  },
}));

function loanDoc(overrides: Partial<LoanDocument> = {}): LoanDocument {
  return {
    id: "00000000-0000-4000-8000-000000000001",
    applicationId: "11111111-1111-4111-8111-111111111111",
    type: "PAN",
    displayName: "PAN card",
    requiredForApproval: true,
    requiredForDisbursement: true,
    status: "UPLOADED",
    notes: null,
    fileMeta: {
      storageKey: "pan.pdf",
      mime: "application/pdf",
      size: 4096,
      checksum: "sha256:abc",
    },
    uploadedAt: "2026-05-01T10:00:00.000Z",
    uploadedBy: "33333333-3333-4333-8333-333333333333",
    ...overrides,
  };
}

function stubDownloadApis(): { getClickedDownload: () => string } {
  let clickedDownload = "";
  Object.defineProperty(URL, "createObjectURL", {
    value: vi.fn(() => "blob:doc"),
    configurable: true,
  });
  Object.defineProperty(URL, "revokeObjectURL", {
    value: vi.fn(),
    configurable: true,
  });
  vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function clickAnchor(
    this: HTMLAnchorElement,
  ) {
    clickedDownload = this.download;
  });
  return { getClickedDownload: () => clickedDownload };
}

beforeEach(() => {
  requestBlobMock.mockReset();
  useDocumentsMock.mockReset();
  vi.mocked(toast.error).mockReset();
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.clearAllMocks();
});

describe("adaptLoanDocumentToDocument", () => {
  it("maps PAN → PAN_CARD and lifts fileMeta", () => {
    const out = adaptLoanDocumentToDocument(loanDoc({ type: "PAN" }));
    expect(out.kind).toBe("PAN_CARD");
    expect(out.fileName).toBe("pan.pdf");
    expect(out.mimeType).toBe("application/pdf");
    expect(out.sizeBytes).toBe(4096);
  });

  it("maps PHOTOGRAPH → KYC_PHOTO", () => {
    expect(adaptLoanDocumentToDocument(loanDoc({ type: "PHOTOGRAPH" })).kind).toBe("KYC_PHOTO");
  });

  it("maps ADDRESS_PROOF without collapsing it to OTHER", () => {
    expect(adaptLoanDocumentToDocument(loanDoc({ type: "ADDRESS_PROOF" })).kind).toBe(
      "ADDRESS_PROOF",
    );
  });

  it("maps KFS without collapsing it to OTHER", () => {
    expect(adaptLoanDocumentToDocument(loanDoc({ type: "KFS", displayName: "KFS" })).kind).toBe(
      "KFS",
    );
  });

  it("handles a null fileMeta gracefully", () => {
    const out = adaptLoanDocumentToDocument(loanDoc({ fileMeta: null, displayName: "Bank stmt" }));
    expect(out.fileName).toBe("Bank stmt");
    expect(out.mimeType).toBeNull();
    expect(out.sizeBytes).toBeNull();
  });
});

describe("DocumentsTab", () => {
  it("renders the skeleton while pending", () => {
    useDocumentsMock.mockReturnValue({
      isPending: true,
      isError: false,
      data: undefined,
      refetch: vi.fn(),
    });
    const { container } = renderWithProviders(
      <DocumentsTab applicationId="app-1" canManage={false} />,
    );
    expect(container.querySelector('[data-slot="table-skeleton"]')).not.toBeNull();
  });

  it("renders the empty state when no documents are attached", () => {
    useDocumentsMock.mockReturnValue({
      isPending: false,
      isError: false,
      data: { documents: [] },
      refetch: vi.fn(),
    });
    renderWithProviders(<DocumentsTab applicationId="app-1" canManage={false} />);
    expect(screen.getByText(/No documents attached/i)).toBeInTheDocument();
  });

  it("renders the Required + Optional sections when documents are present", () => {
    useDocumentsMock.mockReturnValue({
      isPending: false,
      isError: false,
      data: {
        documents: [
          loanDoc({ id: "d-1", type: "PAN", requiredForDisbursement: true }),
          loanDoc({ id: "d-2", type: "BANK_STATEMENT", requiredForDisbursement: false }),
        ],
      },
      refetch: vi.fn(),
    });
    renderWithProviders(<DocumentsTab applicationId="app-1" canManage />);
    // Heading + badge both contain this text — assert at least one is present.
    expect(screen.getAllByText(/Required for disbursement/i).length).toBeGreaterThan(0);
    expect(screen.getByText(/^Optional$/i)).toBeInTheDocument();
  });

  it("renders the error state with retry", async () => {
    const refetch = vi.fn();
    useDocumentsMock.mockReturnValue({
      isPending: false,
      isError: true,
      error: new Error("boom"),
      data: undefined,
      refetch,
    });
    const user = userEvent.setup();
    renderWithProviders(<DocumentsTab applicationId="app-1" canManage={false} />);
    expect(screen.getByText(/Couldn't load documents/i)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /try again/i }));
    expect(refetch).toHaveBeenCalledTimes(1);
  });

  it("downloads KFS with the backend enum and preserves the uploaded filename fallback", async () => {
    requestBlobMock.mockResolvedValue({
      blob: new Blob(["pdf"], { type: "application/pdf" }),
      filename: null,
    });
    const { getClickedDownload } = stubDownloadApis();
    useDocumentsMock.mockReturnValue({
      isPending: false,
      isError: false,
      data: {
        documents: [
          loanDoc({
            id: "00000000-0000-4000-8000-000000000009",
            type: "KFS",
            displayName: "KFS",
            fileMeta: {
              storageKey: "loan/app/kfs/uuid-kfs.pdf",
              fileName: "signed-kfs.pdf",
              mime: "application/pdf",
              size: 4096,
              checksum: "sha256:kfs",
            },
          }),
        ],
      },
      refetch: vi.fn(),
    });
    const user = userEvent.setup();

    renderWithProviders(<DocumentsTab applicationId="app-1" canManage={false} />);
    await user.click(screen.getByRole("button", { name: /Download KFS/i }));

    expect(requestBlobMock).toHaveBeenCalledWith(
      "/api/v1/internal/ops/loan-applications/app-1/kyc-documents/KFS/content",
    );
    expect(getClickedDownload()).toBe("signed-kfs.pdf");
    expect(URL.createObjectURL).toHaveBeenCalledTimes(1);
    expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:doc");
  });

  it("shows a retry-friendly toast when document storage is unavailable", async () => {
    requestBlobMock.mockRejectedValue(
      new ApiError(
        "Document storage is temporarily unavailable. Please retry.",
        503,
        "",
        "DOCUMENT_STORAGE_UNAVAILABLE",
      ),
    );
    useDocumentsMock.mockReturnValue({
      isPending: false,
      isError: false,
      data: {
        documents: [
          loanDoc({
            id: "00000000-0000-4000-8000-000000000011",
            type: "PAN",
            fileMeta: {
              storageKey: "loan/app/pan/uuid-pan.pdf",
              fileName: "uploaded-pan.pdf",
              mime: "application/pdf",
              size: 4096,
              checksum: "sha256:pan",
            },
          }),
        ],
      },
      refetch: vi.fn(),
    });
    const user = userEvent.setup();

    renderWithProviders(<DocumentsTab applicationId="app-1" canManage={false} />);
    await user.click(screen.getByRole("button", { name: /Download PAN card/i }));

    expect(toast.error).toHaveBeenCalledWith(
      "Document storage is temporarily unavailable. Please try again in a moment.",
    );
  });

  it("uses the server filename when Content-Disposition is readable", async () => {
    requestBlobMock.mockResolvedValue({
      blob: new Blob(["pdf"], { type: "application/pdf" }),
      filename: "server-pan.pdf",
    });
    const { getClickedDownload } = stubDownloadApis();
    useDocumentsMock.mockReturnValue({
      isPending: false,
      isError: false,
      data: {
        documents: [
          loanDoc({
            id: "00000000-0000-4000-8000-000000000010",
            type: "PAN",
            fileMeta: {
              storageKey: "loan/app/pan/uuid-pan.pdf",
              fileName: "uploaded-pan.pdf",
              mime: "application/pdf",
              size: 4096,
              checksum: "sha256:pan",
            },
          }),
        ],
      },
      refetch: vi.fn(),
    });
    const user = userEvent.setup();

    renderWithProviders(<DocumentsTab applicationId="app-1" canManage={false} />);
    await user.click(screen.getByRole("button", { name: /Download PAN card/i }));

    expect(requestBlobMock).toHaveBeenCalledWith(
      "/api/v1/internal/ops/loan-applications/app-1/kyc-documents/PAN_CARD/content",
    );
    expect(getClickedDownload()).toBe("server-pan.pdf");
  });

  it("opens the preview modal and requests the inline disposition when View is clicked", async () => {
    requestBlobMock.mockResolvedValue({
      blob: new Blob(["%PDF-1.4"], { type: "application/pdf" }),
      filename: null,
    });
    stubDownloadApis();
    useDocumentsMock.mockReturnValue({
      isPending: false,
      isError: false,
      data: {
        documents: [
          loanDoc({
            id: "00000000-0000-4000-8000-000000000012",
            type: "PAN",
            fileMeta: {
              storageKey: "loan/app/pan/uuid-pan.pdf",
              fileName: "uploaded-pan.pdf",
              mime: "application/pdf",
              size: 4096,
              checksum: "sha256:pan",
            },
          }),
        ],
      },
      refetch: vi.fn(),
    });
    const user = userEvent.setup();

    renderWithProviders(<DocumentsTab applicationId="app-1" canManage={false} />);
    await user.click(screen.getByRole("button", { name: /View PAN card/i }));

    await waitFor(() =>
      expect(requestBlobMock).toHaveBeenCalledWith(
        "/api/v1/internal/ops/loan-applications/app-1/kyc-documents/PAN_CARD/content?disposition=inline",
      ),
    );
    expect(await screen.findByTitle(/Preview of/i)).toBeInTheDocument();
  });

  it("is axe-clean when populated", async () => {
    useDocumentsMock.mockReturnValue({
      isPending: false,
      isError: false,
      data: {
        documents: [loanDoc({ id: "d-1", type: "PAN", requiredForDisbursement: true })],
      },
      refetch: vi.fn(),
    });
    const { container } = renderWithProviders(
      <DocumentsTab applicationId="app-1" canManage={false} />,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
