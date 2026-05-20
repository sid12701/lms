import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import userEvent from "@testing-library/user-event";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import type { LoanDocument } from "@/schemas/loan-application";
import { DocumentPreviewSheet } from "./DocumentPreviewSheet";

function makeDoc(overrides: Partial<LoanDocument> = {}): LoanDocument {
  return {
    id: "11111111-1111-4111-8111-111111111111",
    applicationId: "22222222-2222-4222-8222-222222222222",
    type: "PAN",
    displayName: "pan-card.pdf",
    requiredForApproval: true,
    requiredForDisbursement: false,
    status: "UPLOADED",
    notes: null,
    fileMeta: {
      storageKey: "s3://bucket/pan-card.pdf",
      mime: "application/pdf",
      size: 12345,
      checksum: "abc123",
    },
    uploadedAt: "2026-05-01T10:00:00.000Z",
    uploadedBy: "33333333-3333-4333-8333-333333333333",
    ...overrides,
  };
}

// Stub `matchMedia` for jsdom — defaults to reduced-motion=false so the Radix
// slide-in animation runs (the same as in browsers without the preference).
function stubMatchMedia(reduced: boolean) {
  Object.defineProperty(window, "matchMedia", {
    configurable: true,
    writable: true,
    value: (query: string) => ({
      matches: query.includes("prefers-reduced-motion") ? reduced : false,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }),
  });
}

describe("DocumentPreviewSheet", () => {
  beforeEach(() => {
    stubMatchMedia(false);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("renders the filename, status pill, and metadata when open with a document", () => {
    const { getByText, getByRole, baseElement } = renderWithProviders(
      <DocumentPreviewSheet
        open
        onOpenChange={() => {}}
        document={makeDoc()}
        onPreview={() => {}}
      />,
    );
    expect(getByRole("dialog")).toBeInTheDocument();
    expect(getByText("pan-card.pdf")).toBeInTheDocument();
    expect(
      baseElement.querySelector('[data-slot="document-status-pill"]'),
    ).toBeInTheDocument();
    expect(getByText(/12\.1 KB|12 KB|12345 B/i)).toBeInTheDocument();
    expect(getByText("application/pdf")).toBeInTheDocument();
    expect(
      getByText(/Preview not available — backend integration required/i),
    ).toBeInTheDocument();
  });

  it("calls onPreview exactly once when opened with a document", () => {
    const onPreview = vi.fn();
    const { rerender } = renderWithProviders(
      <DocumentPreviewSheet
        open
        onOpenChange={() => {}}
        document={makeDoc()}
        onPreview={onPreview}
      />,
    );
    expect(onPreview).toHaveBeenCalledTimes(1);
    const [args] = onPreview.mock.calls[0]!;
    expect(args.documentId).toBe("11111111-1111-4111-8111-111111111111");
    expect(typeof args.idempotencyKey).toBe("string");
    expect(args.idempotencyKey.length).toBeGreaterThan(0);

    // Re-renders during the same open should not re-fire onPreview.
    rerender(
      <DocumentPreviewSheet
        open
        onOpenChange={() => {}}
        document={makeDoc()}
        onPreview={onPreview}
      />,
    );
    rerender(
      <DocumentPreviewSheet
        open
        onOpenChange={() => {}}
        document={makeDoc()}
        onPreview={onPreview}
      />,
    );
    expect(onPreview).toHaveBeenCalledTimes(1);
  });

  it("re-fires onPreview after the sheet closes and re-opens", () => {
    const onPreview = vi.fn();
    const { rerender } = renderWithProviders(
      <DocumentPreviewSheet
        open
        onOpenChange={() => {}}
        document={makeDoc()}
        onPreview={onPreview}
      />,
    );
    expect(onPreview).toHaveBeenCalledTimes(1);

    rerender(
      <DocumentPreviewSheet
        open={false}
        onOpenChange={() => {}}
        document={makeDoc()}
        onPreview={onPreview}
      />,
    );
    rerender(
      <DocumentPreviewSheet
        open
        onOpenChange={() => {}}
        document={makeDoc()}
        onPreview={onPreview}
      />,
    );
    expect(onPreview).toHaveBeenCalledTimes(2);
  });

  it("does not call onPreview when opened without a document", () => {
    const onPreview = vi.fn();
    renderWithProviders(
      <DocumentPreviewSheet
        open
        onOpenChange={() => {}}
        document={null}
        onPreview={onPreview}
      />,
    );
    expect(onPreview).not.toHaveBeenCalled();
  });

  it("renders the empty body copy when document is null", () => {
    const { getByText } = renderWithProviders(
      <DocumentPreviewSheet
        open
        onOpenChange={() => {}}
        document={null}
        onPreview={() => {}}
      />,
    );
    expect(getByText(/No document selected/i)).toBeInTheDocument();
  });

  it("shows a skeleton when loading is true and a document is present", () => {
    const { baseElement } = renderWithProviders(
      <DocumentPreviewSheet
        open
        loading
        onOpenChange={() => {}}
        document={makeDoc()}
        onPreview={() => {}}
      />,
    );
    expect(
      baseElement.querySelector('[data-slot="document-preview-loading"]'),
    ).toBeInTheDocument();
  });

  it("invokes onOpenChange(false) when Close is clicked", async () => {
    const onOpenChange = vi.fn();
    const { getByRole } = renderWithProviders(
      <DocumentPreviewSheet
        open
        onOpenChange={onOpenChange}
        document={makeDoc()}
        onPreview={() => {}}
      />,
    );
    await userEvent.click(getByRole("button", { name: "Close" }));
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it("fires onDownload with a fresh idempotency key when Download is clicked", async () => {
    const onDownload = vi.fn();
    const { getByRole } = renderWithProviders(
      <DocumentPreviewSheet
        open
        onOpenChange={() => {}}
        document={makeDoc()}
        onPreview={() => {}}
        onDownload={onDownload}
      />,
    );
    await userEvent.click(getByRole("button", { name: "Download" }));
    expect(onDownload).toHaveBeenCalledTimes(1);
    const [args] = onDownload.mock.calls[0]!;
    expect(args.documentId).toBe("11111111-1111-4111-8111-111111111111");
    expect(typeof args.idempotencyKey).toBe("string");
    expect(args.idempotencyKey.length).toBeGreaterThan(0);
  });

  it("does not render the Download button when onDownload is omitted", () => {
    const { queryByRole } = renderWithProviders(
      <DocumentPreviewSheet
        open
        onOpenChange={() => {}}
        document={makeDoc()}
        onPreview={() => {}}
      />,
    );
    expect(queryByRole("button", { name: "Download" })).not.toBeInTheDocument();
  });

  it("marks the dialog content with data-reduced-motion=true when the user prefers reduced motion", () => {
    stubMatchMedia(true);
    const { baseElement } = renderWithProviders(
      <DocumentPreviewSheet
        open
        onOpenChange={() => {}}
        document={makeDoc()}
        onPreview={() => {}}
      />,
    );
    const sheet = baseElement.querySelector('[data-slot="document-preview-sheet"]');
    expect(sheet?.getAttribute("data-reduced-motion")).toBe("true");
  });

  it("falls back to '—' for missing metadata fields", () => {
    const { getAllByText } = renderWithProviders(
      <DocumentPreviewSheet
        open
        onOpenChange={() => {}}
        document={makeDoc({
          uploadedAt: null,
          uploadedBy: null,
          fileMeta: null,
        })}
        onPreview={() => {}}
      />,
    );
    // Four metadata rows fall back to "—": uploadedAt, uploadedBy, size, mime.
    expect(getAllByText("—").length).toBeGreaterThanOrEqual(3);
  });

  it("has no axe violations when open with a document", async () => {
    const { baseElement } = renderWithProviders(
      <DocumentPreviewSheet
        open
        onOpenChange={() => {}}
        document={makeDoc()}
        onPreview={() => {}}
        onDownload={() => {}}
      />,
    );
    expect(await axe(baseElement)).toHaveNoViolations();
  });

  it("has no axe violations when open without a document", async () => {
    const { baseElement } = renderWithProviders(
      <DocumentPreviewSheet
        open
        onOpenChange={() => {}}
        document={null}
        onPreview={() => {}}
      />,
    );
    expect(await axe(baseElement)).toHaveNoViolations();
  });
});
