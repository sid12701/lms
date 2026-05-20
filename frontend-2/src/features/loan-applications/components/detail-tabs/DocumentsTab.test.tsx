/**
 * DocumentsTab tests — hook module is mocked.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { axe } from "vitest-axe";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/utils";
import type { LoanDocument } from "@/types";

const useDocumentsMock = vi.fn();

vi.mock("../../hooks/useLoanApplicationDocuments", () => ({
  useLoanApplicationDocuments: () => useDocumentsMock(),
  loanApplicationDocumentsQueryKey: (id: string) => ["loan-application", id, "documents"],
}));

import { DocumentsTab, adaptLoanDocumentToDocument } from "./DocumentsTab";

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

beforeEach(() => {
  useDocumentsMock.mockReset();
});

afterEach(() => {
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
    expect(adaptLoanDocumentToDocument(loanDoc({ type: "PHOTOGRAPH" })).kind).toBe(
      "KYC_PHOTO",
    );
  });

  it("falls back to OTHER for ADDRESS_PROOF", () => {
    expect(adaptLoanDocumentToDocument(loanDoc({ type: "ADDRESS_PROOF" })).kind).toBe(
      "OTHER",
    );
  });

  it("handles a null fileMeta gracefully", () => {
    const out = adaptLoanDocumentToDocument(
      loanDoc({ fileMeta: null, displayName: "Bank stmt" }),
    );
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

  it("is axe-clean when populated", async () => {
    useDocumentsMock.mockReturnValue({
      isPending: false,
      isError: false,
      data: {
        documents: [
          loanDoc({ id: "d-1", type: "PAN", requiredForDisbursement: true }),
        ],
      },
      refetch: vi.fn(),
    });
    const { container } = renderWithProviders(
      <DocumentsTab applicationId="app-1" canManage={false} />,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
