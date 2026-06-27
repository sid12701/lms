/**
 * DocumentPreviewModal tests — http-client.requestBlob and the URL object-URL
 * APIs are mocked so we can assert renderer selection, fallbacks, and cleanup.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/utils";

const requestBlobMock = vi.hoisted(() => vi.fn());

vi.mock("@/lib/api/http-client", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api/http-client")>();
  return { ...actual, requestBlob: requestBlobMock };
});

import { ApiError } from "@/lib/api/http-client";
import { DocumentPreviewModal } from "./DocumentPreviewModal";
import { isInlinePreviewable } from "./documentPreview";

function stubObjectUrl(): { created: string[]; revoked: string[] } {
  const created: string[] = [];
  const revoked: string[] = [];
  Object.defineProperty(URL, "createObjectURL", {
    value: vi.fn(() => {
      const url = `blob:doc-${created.length}`;
      created.push(url);
      return url;
    }),
    configurable: true,
  });
  Object.defineProperty(URL, "revokeObjectURL", {
    value: vi.fn((url: string) => revoked.push(url)),
    configurable: true,
  });
  return { created, revoked };
}

const INLINE_PDF_PATH =
  "/api/v1/internal/ops/loan-applications/app-1/kyc-documents/PAN_CARD/content?disposition=inline";

beforeEach(() => {
  requestBlobMock.mockReset();
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.clearAllMocks();
});

describe("isInlinePreviewable", () => {
  it("accepts pdf/jpeg/png and ignores charset params", () => {
    expect(isInlinePreviewable("application/pdf")).toBe(true);
    expect(isInlinePreviewable("image/jpeg")).toBe(true);
    expect(isInlinePreviewable("IMAGE/PNG")).toBe(true);
    expect(isInlinePreviewable("application/pdf; charset=binary")).toBe(true);
  });

  it("rejects scriptable / unknown / missing types", () => {
    expect(isInlinePreviewable("image/svg+xml")).toBe(false);
    expect(isInlinePreviewable("text/html")).toBe(false);
    expect(isInlinePreviewable(null)).toBe(false);
    expect(isInlinePreviewable(undefined)).toBe(false);
  });
});

describe("DocumentPreviewModal", () => {
  it("fetches the inline blob and renders a PDF in an iframe", async () => {
    stubObjectUrl();
    requestBlobMock.mockResolvedValue({
      blob: new Blob(["%PDF-1.4"], { type: "application/pdf" }),
      filename: "pan.pdf",
    });

    renderWithProviders(
      <DocumentPreviewModal
        open
        onOpenChange={vi.fn()}
        title="PAN card"
        mimeType="application/pdf"
        contentPath={INLINE_PDF_PATH}
      />,
    );

    expect(await screen.findByTitle(/Preview of PAN card/i)).toBeInTheDocument();
    expect(requestBlobMock).toHaveBeenCalledWith(INLINE_PDF_PATH);
  });

  it("renders an image preview for image mime types", async () => {
    stubObjectUrl();
    requestBlobMock.mockResolvedValue({
      blob: new Blob(["jpegbytes"], { type: "image/jpeg" }),
      filename: "selfie.jpg",
    });

    renderWithProviders(
      <DocumentPreviewModal
        open
        onOpenChange={vi.fn()}
        title="Selfie"
        mimeType="image/jpeg"
        contentPath="/x/content?disposition=inline"
      />,
    );

    const img = await screen.findByRole("img", { name: /Preview of Selfie/i });
    expect(img).toBeInTheDocument();
  });

  it("shows the unsupported fallback and does NOT fetch for non-previewable types", async () => {
    renderWithProviders(
      <DocumentPreviewModal
        open
        onOpenChange={vi.fn()}
        title="Notes"
        mimeType="text/plain"
        contentPath="/x/content?disposition=inline"
      />,
    );

    expect(await screen.findByText(/can't be previewed here/i)).toBeInTheDocument();
    expect(requestBlobMock).not.toHaveBeenCalled();
  });

  it("shows an error fallback with a friendly message when the fetch fails", async () => {
    stubObjectUrl();
    requestBlobMock.mockRejectedValue(
      new ApiError("boom", 503, "", "DOCUMENT_STORAGE_UNAVAILABLE"),
    );

    renderWithProviders(
      <DocumentPreviewModal
        open
        onOpenChange={vi.fn()}
        title="PAN card"
        mimeType="application/pdf"
        contentPath={INLINE_PDF_PATH}
      />,
    );

    expect(await screen.findByText(/Couldn't load the preview/i)).toBeInTheDocument();
    expect(screen.getByText(/temporarily unavailable/i)).toBeInTheDocument();
  });

  it("invokes onDownload when Download is clicked", async () => {
    stubObjectUrl();
    requestBlobMock.mockResolvedValue({
      blob: new Blob(["%PDF-1.4"], { type: "application/pdf" }),
      filename: "pan.pdf",
    });
    const onDownload = vi.fn();
    const user = userEvent.setup();

    renderWithProviders(
      <DocumentPreviewModal
        open
        onOpenChange={vi.fn()}
        title="PAN card"
        mimeType="application/pdf"
        contentPath={INLINE_PDF_PATH}
        onDownload={onDownload}
      />,
    );

    await screen.findByTitle(/Preview of PAN card/i);
    await user.click(screen.getByRole("button", { name: /download/i }));
    expect(onDownload).toHaveBeenCalledTimes(1);
  });

  it("revokes the object URL when unmounted", async () => {
    const { created, revoked } = stubObjectUrl();
    requestBlobMock.mockResolvedValue({
      blob: new Blob(["%PDF-1.4"], { type: "application/pdf" }),
      filename: "pan.pdf",
    });

    const { unmount } = renderWithProviders(
      <DocumentPreviewModal
        open
        onOpenChange={vi.fn()}
        title="PAN card"
        mimeType="application/pdf"
        contentPath={INLINE_PDF_PATH}
      />,
    );

    await screen.findByTitle(/Preview of PAN card/i);
    expect(created).toHaveLength(1);
    unmount();
    await waitFor(() => expect(revoked).toContain(created[0]));
  });
});
