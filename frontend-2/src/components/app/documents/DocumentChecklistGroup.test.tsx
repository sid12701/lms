import { describe, it, expect, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import type { Document } from "@/schemas/document";
import { DocumentChecklistGroup } from "./DocumentChecklistGroup";

let idCounter = 0;
function uid(): string {
  idCounter += 1;
  const hex = idCounter.toString(16).padStart(8, "0");
  return `${hex}-1111-4111-8111-111111111111`;
}

function makeDoc(overrides: Partial<Document> = {}): Document {
  return {
    id: uid(),
    applicationId: "22222222-2222-4222-8222-222222222222",
    kind: "PAN_CARD",
    status: "UPLOADED",
    requiredForDisbursement: false,
    fileName: "file.pdf",
    mimeType: "application/pdf",
    sizeBytes: 1024,
    uploadedAt: "2026-05-01T10:00:00.000Z",
    uploadedBy: "33333333-3333-4333-8333-333333333333",
    ...overrides,
  };
}

describe("DocumentChecklistGroup", () => {
  it("renders the Required and Optional section headings", () => {
    const { getByText } = renderWithProviders(<DocumentChecklistGroup docs={[]} />);
    expect(getByText(/Required for disbursement/i)).toBeInTheDocument();
    expect(getByText(/^Optional$/i)).toBeInTheDocument();
  });

  it("splits docs into required and optional lists", () => {
    const docs: Document[] = [
      makeDoc({ kind: "PAN_CARD", requiredForDisbursement: true }),
      makeDoc({ kind: "AADHAAR_CARD", requiredForDisbursement: true }),
      makeDoc({ kind: "BANK_STATEMENT", requiredForDisbursement: false }),
    ];
    const { container, getByText } = renderWithProviders(<DocumentChecklistGroup docs={docs} />);
    const requiredSection = container.querySelector('[data-slot="document-checklist-required"]');
    const optionalSection = container.querySelector('[data-slot="document-checklist-optional"]');
    expect(requiredSection?.textContent).toMatch(/PAN card/);
    expect(requiredSection?.textContent).toMatch(/Aadhaar card/);
    expect(optionalSection?.textContent).toMatch(/Bank statement/);
    expect(getByText("PAN card")).toBeInTheDocument();
  });

  it("renders an empty state for the required section when there are none", () => {
    const { getByText } = renderWithProviders(
      <DocumentChecklistGroup
        docs={[makeDoc({ kind: "OTHER", requiredForDisbursement: false })]}
      />,
    );
    expect(getByText(/No required documents/i)).toBeInTheDocument();
  });

  it("renders an empty state for the optional section when there are none", () => {
    const { getByText } = renderWithProviders(
      <DocumentChecklistGroup docs={[makeDoc({ requiredForDisbursement: true })]} />,
    );
    expect(getByText(/No optional documents/i)).toBeInTheDocument();
  });

  it("renders DocumentUploadRow for PENDING docs and DocumentChecklistRow for UPLOADED docs", () => {
    const docs: Document[] = [
      makeDoc({
        kind: "PAN_CARD",
        status: "PENDING",
        requiredForDisbursement: true,
        fileName: null,
        mimeType: null,
        sizeBytes: null,
        uploadedAt: null,
      }),
      makeDoc({ kind: "AADHAAR_CARD", status: "UPLOADED", requiredForDisbursement: true }),
    ];
    const { container } = renderWithProviders(<DocumentChecklistGroup docs={docs} />);
    expect(container.querySelectorAll('[data-slot="document-upload-row"]').length).toBe(1);
    expect(container.querySelectorAll('[data-slot="document-checklist-row"]').length).toBe(1);
  });

  it("wires onUpload through to PENDING rows and forwards the doc", async () => {
    const pendingDoc = makeDoc({
      kind: "PAN_CARD",
      status: "PENDING",
      requiredForDisbursement: true,
      fileName: null,
      mimeType: null,
      sizeBytes: null,
      uploadedAt: null,
    });
    const onUpload = vi.fn();
    const { getByRole } = renderWithProviders(
      <DocumentChecklistGroup docs={[pendingDoc]} onUpload={onUpload} />,
    );
    await userEvent.click(getByRole("button", { name: /Upload PAN card/i }));
    expect(onUpload).toHaveBeenCalledTimes(1);
    const [doc, args] = onUpload.mock.calls[0]!;
    expect(doc.id).toBe(pendingDoc.id);
    expect(typeof args.idempotencyKey).toBe("string");
    expect(args.idempotencyKey.length).toBeGreaterThan(0);
  }, 15_000);

  it("wires onView/onDownload through to uploaded rows and forwards the doc", async () => {
    const uploadedDoc = makeDoc({ kind: "BANK_STATEMENT", status: "UPLOADED" });
    const onView = vi.fn();
    const onDownload = vi.fn();
    const { getByRole } = renderWithProviders(
      <DocumentChecklistGroup docs={[uploadedDoc]} onView={onView} onDownload={onDownload} />,
    );
    await userEvent.click(getByRole("button", { name: /View Bank statement/i }));
    await userEvent.click(getByRole("button", { name: /Download Bank statement/i }));
    expect(onView).toHaveBeenCalledWith(expect.objectContaining({ id: uploadedDoc.id }));
    expect(onDownload).toHaveBeenCalledWith(expect.objectContaining({ id: uploadedDoc.id }));
  }, 15_000);

  it("does not surface any Verify or Reject affordances (Gap #18)", () => {
    const doc = makeDoc({ kind: "INCOME_PROOF", status: "UPLOADED" });
    const { queryByRole } = renderWithProviders(<DocumentChecklistGroup docs={[doc]} />);
    expect(queryByRole("button", { name: /Verify/i })).toBeNull();
    expect(queryByRole("button", { name: /Reject/i })).toBeNull();
  });

  it("propagates the compact prop to data-compact", () => {
    const { container } = renderWithProviders(<DocumentChecklistGroup docs={[]} compact />);
    const root = container.querySelector('[data-slot="document-checklist-group"]');
    expect(root?.getAttribute("data-compact")).toBe("true");
  });

  it("has no axe violations", async () => {
    const docs: Document[] = [
      makeDoc({
        kind: "PAN_CARD",
        status: "PENDING",
        requiredForDisbursement: true,
        fileName: null,
        mimeType: null,
        sizeBytes: null,
        uploadedAt: null,
      }),
      makeDoc({ kind: "BANK_STATEMENT", status: "UPLOADED" }),
    ];
    const { container } = renderWithProviders(<DocumentChecklistGroup docs={docs} />);
    expect(await axe(container)).toHaveNoViolations();
  });
});
