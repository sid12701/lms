import { describe, it, expect, vi } from "vitest";
import { act } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import type { Document } from "@/schemas/document";
import { DocumentUploadRow } from "./DocumentUploadRow";

function makeDoc(overrides: Partial<Document> = {}): Document {
  return {
    id: "11111111-1111-4111-8111-111111111111",
    applicationId: "22222222-2222-4222-8222-222222222222",
    kind: "PAN_CARD",
    status: "PENDING",
    requiredForDisbursement: false,
    fileName: null,
    mimeType: null,
    sizeBytes: null,
    uploadedAt: null,
    uploadedBy: null,
    ...overrides,
  };
}

describe("DocumentUploadRow", () => {
  it("renders the kind label, status pill, and the upload button", () => {
    const { getByText, getByRole } = renderWithProviders(
      <DocumentUploadRow doc={makeDoc()} onUpload={() => {}} />,
    );
    expect(getByText("PAN card")).toBeInTheDocument();
    expect(getByText("Pending")).toBeInTheDocument();
    expect(getByRole("button", { name: /Upload PAN card/i })).toBeInTheDocument();
    expect(getByText(/No file uploaded yet/i)).toBeInTheDocument();
  });

  it("shows the 'Required for disbursement' badge when applicable", () => {
    const { getByText, container } = renderWithProviders(
      <DocumentUploadRow
        doc={makeDoc({ requiredForDisbursement: true, kind: "AADHAAR_CARD" })}
        onUpload={() => {}}
      />,
    );
    expect(getByText(/Required for disbursement/i)).toBeInTheDocument();
    const row = container.querySelector('[data-slot="document-upload-row"]');
    expect(row?.getAttribute("data-required")).toBe("true");
  });

  it("does not render the upload button when onUpload is not provided", () => {
    const { queryByRole } = renderWithProviders(<DocumentUploadRow doc={makeDoc()} />);
    expect(queryByRole("button")).toBeNull();
  });

  it("calls onUpload with a fresh idempotency key when clicked", async () => {
    const onUpload = vi.fn();
    const { getByRole } = renderWithProviders(
      <DocumentUploadRow doc={makeDoc()} onUpload={onUpload} />,
    );
    await userEvent.click(getByRole("button", { name: /Upload PAN card/i }));
    expect(onUpload).toHaveBeenCalledTimes(1);
    const [args] = onUpload.mock.calls[0]!;
    expect(typeof args.idempotencyKey).toBe("string");
    expect(args.idempotencyKey.length).toBeGreaterThan(0);
  }, 15_000);

  it("shows a busy state while the upload promise is pending", async () => {
    let resolve: (() => void) | undefined;
    const onUpload = vi.fn(
      () =>
        new Promise<void>((r) => {
          resolve = r;
        }),
    );
    const { getByRole, findByRole } = renderWithProviders(
      <DocumentUploadRow doc={makeDoc()} onUpload={onUpload} />,
    );
    await userEvent.click(getByRole("button", { name: /Upload PAN card/i }));
    const busyBtn = await findByRole("button", { name: /Upload PAN card/i });
    expect(busyBtn).toBeDisabled();
    expect(busyBtn.getAttribute("aria-busy")).toBe("true");
    await act(async () => {
      resolve?.();
    });
    expect(busyBtn).not.toBeDisabled();
  }, 15_000);

  it("applies compact styling via data-compact='true' when compact", () => {
    const { container } = renderWithProviders(
      <DocumentUploadRow doc={makeDoc()} compact onUpload={() => {}} />,
    );
    const row = container.querySelector('[data-slot="document-upload-row"]');
    expect(row?.getAttribute("data-compact")).toBe("true");
  });

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(
      <DocumentUploadRow doc={makeDoc({ requiredForDisbursement: true })} onUpload={() => {}} />,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
