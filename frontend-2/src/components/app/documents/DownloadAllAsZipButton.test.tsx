import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import userEvent from "@testing-library/user-event";
import { axe } from "vitest-axe";
import { toast } from "sonner";
import { renderWithProviders } from "@/test/utils";
import type { LoanDocument } from "@/schemas/loan-application";
import { DownloadAllAsZipButton } from "./DownloadAllAsZipButton";

vi.mock("sonner", () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
    info: vi.fn(),
    warning: vi.fn(),
  },
}));

function makeDoc(overrides: Partial<LoanDocument> = {}): LoanDocument {
  return {
    id: "11111111-1111-4111-8111-111111111111",
    applicationId: "22222222-2222-4222-8222-222222222222",
    type: "PAN",
    displayName: "pan.pdf",
    requiredForApproval: true,
    requiredForDisbursement: false,
    status: "UPLOADED",
    notes: null,
    fileMeta: {
      storageKey: "s3://bucket/pan.pdf",
      mime: "application/pdf",
      size: 1024,
      checksum: "abc",
    },
    uploadedAt: "2026-05-01T10:00:00.000Z",
    uploadedBy: "33333333-3333-4333-8333-333333333333",
    ...overrides,
  };
}

function makeDocs(count: number): LoanDocument[] {
  return Array.from({ length: count }, (_, i) =>
    makeDoc({
      id: `${i + 1}1111111-1111-4111-8111-111111111111`.slice(0, 36),
      displayName: `doc-${i + 1}.pdf`,
    }),
  );
}

describe("DownloadAllAsZipButton", () => {
  beforeEach(() => {
    // Stub URL.createObjectURL / revokeObjectURL — jsdom does not implement
    // them and the component relies on them to return a blob URL.
    Object.defineProperty(URL, "createObjectURL", {
      configurable: true,
      value: vi.fn().mockReturnValue("blob:mock"),
    });
    Object.defineProperty(URL, "revokeObjectURL", {
      configurable: true,
      value: vi.fn(),
    });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("renders the document count in the button label", () => {
    const { getByRole } = renderWithProviders(
      <DownloadAllAsZipButton
        documents={makeDocs(3)}
        onBeforeAccess={() => {}}
        onZipReady={() => {}}
      />,
    );
    expect(getByRole("button", { name: /Download all \(3\)/ })).toBeInTheDocument();
  });

  it("is disabled when documents is empty", () => {
    const { getByRole } = renderWithProviders(
      <DownloadAllAsZipButton documents={[]} onBeforeAccess={() => {}} onZipReady={() => {}} />,
    );
    expect(getByRole("button", { name: /Download all \(0\)/ })).toBeDisabled();
  });

  it("is disabled when disabled prop is true", () => {
    const { getByRole } = renderWithProviders(
      <DownloadAllAsZipButton
        documents={makeDocs(2)}
        onBeforeAccess={() => {}}
        onZipReady={() => {}}
        disabled
      />,
    );
    expect(getByRole("button", { name: /Download all \(2\)/ })).toBeDisabled();
  });

  it("does nothing on click when documents is empty", async () => {
    const onBeforeAccess = vi.fn();
    const onZipReady = vi.fn();
    const { getByRole } = renderWithProviders(
      <DownloadAllAsZipButton
        documents={[]}
        onBeforeAccess={onBeforeAccess}
        onZipReady={onZipReady}
      />,
    );
    // Force-enable to test the early-return guard inside the click handler.
    const btn = getByRole("button");
    btn.removeAttribute("disabled");
    await userEvent.click(btn);
    expect(onBeforeAccess).not.toHaveBeenCalled();
    expect(onZipReady).not.toHaveBeenCalled();
  });

  it("calls onBeforeAccess once per document, each with a fresh idempotency key (BR-5)", async () => {
    const onBeforeAccess = vi.fn();
    const onZipReady = vi.fn();
    const docs = makeDocs(3);
    const { getByRole } = renderWithProviders(
      <DownloadAllAsZipButton
        documents={docs}
        onBeforeAccess={onBeforeAccess}
        onZipReady={onZipReady}
      />,
    );
    await userEvent.click(getByRole("button"));
    expect(onBeforeAccess).toHaveBeenCalledTimes(3);
    const keys = new Set(onBeforeAccess.mock.calls.map((c) => c[0].idempotencyKey));
    expect(keys.size).toBe(3); // every call has a unique key
    const ids = onBeforeAccess.mock.calls.map((c) => c[0].documentId);
    expect(ids).toEqual(docs.map((d) => d.id));
  });

  it("invokes onZipReady with a blob URL and a borrower-documents-*.zip filename", async () => {
    const onZipReady = vi.fn();
    const docs = makeDocs(2);
    const { getByRole } = renderWithProviders(
      <DownloadAllAsZipButton documents={docs} onBeforeAccess={() => {}} onZipReady={onZipReady} />,
    );
    await userEvent.click(getByRole("button"));
    expect(URL.createObjectURL).toHaveBeenCalledTimes(1);
    const blobArg = (URL.createObjectURL as unknown as ReturnType<typeof vi.fn>).mock.calls[0]![0];
    expect(blobArg).toBeInstanceOf(Blob);
    expect((blobArg as Blob).type).toBe("application/zip");
    expect(onZipReady).toHaveBeenCalledTimes(1);
    const [arg] = onZipReady.mock.calls[0]!;
    expect(arg.url).toBe("blob:mock");
    expect(arg.filename).toMatch(/^borrower-documents-.*\.zip$/);
  });

  it("shows a loading state and disables the button while iterating", async () => {
    let resolveAccess: (() => void) | undefined;
    const accessPromise = new Promise<void>((r) => {
      resolveAccess = r;
    });
    const onBeforeAccess = vi.fn().mockReturnValue(accessPromise);
    const onZipReady = vi.fn();
    const { getByRole, findByRole } = renderWithProviders(
      <DownloadAllAsZipButton
        documents={makeDocs(2)}
        onBeforeAccess={onBeforeAccess}
        onZipReady={onZipReady}
      />,
    );
    await userEvent.click(getByRole("button"));
    const preparing = await findByRole("button", { name: /Preparing…/ });
    expect(preparing).toBeDisabled();
    expect(preparing.getAttribute("aria-busy")).toBe("true");
    resolveAccess?.();
  });

  it("aborts the download and toasts an error if onBeforeAccess throws", async () => {
    const onBeforeAccess = vi.fn().mockRejectedValueOnce(new Error("audit write failed"));
    const onZipReady = vi.fn();
    const { getByRole } = renderWithProviders(
      <DownloadAllAsZipButton
        documents={makeDocs(2)}
        onBeforeAccess={onBeforeAccess}
        onZipReady={onZipReady}
      />,
    );
    await userEvent.click(getByRole("button"));
    expect(onZipReady).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalledWith(expect.stringContaining("audit write failed"));
  });

  it("handles a non-Error thrown value with a generic message", async () => {
    const onBeforeAccess = vi.fn().mockRejectedValueOnce("string-rejection");
    const onZipReady = vi.fn();
    const { getByRole } = renderWithProviders(
      <DownloadAllAsZipButton
        documents={makeDocs(1)}
        onBeforeAccess={onBeforeAccess}
        onZipReady={onZipReady}
      />,
    );
    await userEvent.click(getByRole("button"));
    expect(onZipReady).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalledWith(expect.stringContaining("Failed to prepare download"));
  });

  it("includes filename, mime, and size for each document in the synthesised blob", async () => {
    const onZipReady = vi.fn();
    const docs = [
      makeDoc({ id: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", displayName: "first.pdf" }),
      makeDoc({
        id: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
        displayName: "second.png",
        fileMeta: null,
      }),
    ];
    const { getByRole } = renderWithProviders(
      <DownloadAllAsZipButton documents={docs} onBeforeAccess={() => {}} onZipReady={onZipReady} />,
    );
    await userEvent.click(getByRole("button"));
    const blob = (URL.createObjectURL as unknown as ReturnType<typeof vi.fn>).mock
      .calls[0]![0] as Blob;
    const text = await blob.text();
    expect(text).toContain("first.pdf");
    expect(text).toContain("second.png");
    expect(text).toContain("application/pdf");
    // Missing fileMeta surfaces "—" placeholders.
    expect(text).toContain("mime=—");
  });

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(
      <DownloadAllAsZipButton
        documents={makeDocs(2)}
        onBeforeAccess={() => {}}
        onZipReady={() => {}}
      />,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
