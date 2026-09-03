import { describe, expect, it } from "vitest";
import { render, within } from "@testing-library/react";
import { DocumentChecklistGroup } from "./DocumentChecklistGroup";
import type { Document, DocumentStatus } from "@/schemas/document";

function doc(
  id: string,
  status: DocumentStatus,
  requiredForDisbursement: boolean,
  withFile: boolean,
): Document {
  return {
    id,
    applicationId: "app-1",
    kind: "PAN_CARD",
    status,
    requiredForDisbursement,
    fileName: withFile ? "pan.pdf" : null,
    mimeType: withFile ? "application/pdf" : null,
    sizeBytes: withFile ? 1024 : null,
    uploadedAt: withFile ? "2026-06-21T13:15:00.000Z" : null,
    uploadedBy: withFile ? "lsp.writer" : null,
  } as Document;
}

/**
 * Every (status × requiredForDisbursement) combination must land in the right
 * section and describe itself accurately. The section a row sits in is a claim
 * about whether it gates disbursement, so a row must never contradict it.
 */
describe("document checklist state matrix", () => {
  const sectionFor = (slot: string) =>
    document.querySelector(`[data-slot="document-checklist-${slot}"]`) as HTMLElement;

  it("uploaded + required → required section, Uploaded, still marked as a requirement", () => {
    render(<DocumentChecklistGroup docs={[doc("d", "UPLOADED", true, true)]} />);
    const req = sectionFor("required");
    expect(within(req).getByText("pan.pdf")).toBeInTheDocument();
    expect(within(req).getAllByText(/Required for disbursement/).length).toBeGreaterThan(0);
  });

  it("not uploaded + required → required section, Pending, marked as a requirement", () => {
    render(<DocumentChecklistGroup docs={[doc("d", "PENDING", true, false)]} />);
    const req = sectionFor("required");
    expect(within(req).getByText("Pending")).toBeInTheDocument();
    expect(within(req).getByText("No file uploaded yet.")).toBeInTheDocument();
  });

  it("uploaded + optional → optional section, Uploaded, no requirement badge", () => {
    render(<DocumentChecklistGroup docs={[doc("d", "UPLOADED", false, true)]} />);
    const opt = sectionFor("optional");
    expect(within(opt).getByText("pan.pdf")).toBeInTheDocument();
    expect(within(opt).queryByText(/Required for disbursement/)).not.toBeInTheDocument();
  });

  it("not uploaded + optional → optional section, no requirement badge", () => {
    render(<DocumentChecklistGroup docs={[doc("d", "PENDING", false, false)]} />);
    const opt = sectionFor("optional");
    expect(within(opt).queryByText(/Required for disbursement/)).not.toBeInTheDocument();
  });

  /**
   * The waiver case: the document type gates disbursement in general
   * (`requiredForDisbursement: true`) but has been marked not required for this
   * loan. It must not sit under a heading claiming it is required.
   */
  it("exempted document does not appear under the required heading", () => {
    render(<DocumentChecklistGroup docs={[doc("d", "NOT_REQUIRED", true, false)]} />);
    const req = sectionFor("required");
    // Not under the "Required for disbursement" heading…
    expect(within(req).queryByText("Not required")).not.toBeInTheDocument();
    // …but present, in the non-gating section, describing itself accurately.
    const opt = sectionFor("optional");
    expect(within(opt).getByText("Not required")).toBeInTheDocument();
    expect(
      within(opt).getByText("Not required for this loan — no document is expected."),
    ).toBeInTheDocument();
    // And it carries no "requirement" badge, because it is not one.
    expect(document.querySelector('[data-slot="required-for-disbursement-badge"]')).toBeNull();
  });
});
