import { describe, it, expect, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import type { Document } from "@/schemas/document";
import { DocumentChecklistRow } from "./DocumentChecklistRow";
import { formatBytes } from "./document-format";

function makeDoc(overrides: Partial<Document> = {}): Document {
  return {
    id: "11111111-1111-4111-8111-111111111111",
    applicationId: "22222222-2222-4222-8222-222222222222",
    kind: "PAN_CARD",
    status: "UPLOADED",
    requiredForDisbursement: false,
    fileName: "pan.pdf",
    mimeType: "application/pdf",
    sizeBytes: 12345,
    uploadedAt: "2026-05-01T10:00:00.000Z",
    uploadedBy: "33333333-3333-4333-8333-333333333333",
    ...overrides,
  };
}

describe("formatBytes", () => {
  it.each([
    [null, "—"],
    [undefined, "—"],
    [-1, "—"],
    [Number.NaN, "—"],
    [512, "512 B"],
    [2048, "2.0 KB"],
    [2 * 1024 * 1024, "2.0 MB"],
  ])("formats %s as %s", (input, expected) => {
    expect(formatBytes(input as number | null | undefined)).toBe(expected);
  });
});

describe("DocumentChecklistRow (Gap #18 — view-only)", () => {
  it("renders the kind label, status pill, and file metadata", () => {
    const { getByText, container } = renderWithProviders(<DocumentChecklistRow doc={makeDoc()} />);
    expect(getByText("PAN card")).toBeInTheDocument();
    const pill = container.querySelector('[data-slot="document-status-pill"]');
    expect(pill?.textContent).toContain("Uploaded");
    expect(getByText("pan.pdf")).toBeInTheDocument();
    expect(getByText(/12\.1 KB|12 KB/)).toBeInTheDocument();
    expect(getByText("application/pdf")).toBeInTheDocument();
  });

  it("shows the 'Required for disbursement' badge when applicable", () => {
    const { getByText } = renderWithProviders(
      <DocumentChecklistRow doc={makeDoc({ requiredForDisbursement: true })} />,
    );
    expect(getByText(/Required for disbursement/i)).toBeInTheDocument();
  });

  it("falls back to the no-file message when fileName is null", () => {
    const { getByText, queryByText } = renderWithProviders(
      <DocumentChecklistRow doc={makeDoc({ fileName: null, mimeType: null, sizeBytes: null })} />,
    );
    expect(getByText(/No file uploaded yet/i)).toBeInTheDocument();
    expect(queryByText("application/pdf")).toBeNull();
  });

  it("calls onView and onDownload when their buttons are clicked", async () => {
    const onView = vi.fn();
    const onDownload = vi.fn();
    const { getByRole } = renderWithProviders(
      <DocumentChecklistRow doc={makeDoc()} onView={onView} onDownload={onDownload} />,
    );
    await userEvent.click(getByRole("button", { name: /View PAN card/i }));
    await userEvent.click(getByRole("button", { name: /Download PAN card/i }));
    expect(onView).toHaveBeenCalledOnce();
    expect(onDownload).toHaveBeenCalledOnce();
  });

  it("hides the Download button when there is no file", () => {
    const { queryByRole } = renderWithProviders(
      <DocumentChecklistRow doc={makeDoc({ fileName: null })} onDownload={() => {}} />,
    );
    expect(queryByRole("button", { name: /Download/i })).toBeNull();
  });

  it("does not surface any Verify or Reject affordances (Gap #18)", () => {
    const { queryByRole, container } = renderWithProviders(
      <DocumentChecklistRow doc={makeDoc()} />,
    );
    expect(queryByRole("button", { name: /Verify/i })).toBeNull();
    expect(queryByRole("button", { name: /Reject/i })).toBeNull();
    expect(container.querySelector('[data-slot="document-rejection-reason"]')).toBeNull();
  });

  it("applies compact mode via data-compact='true'", () => {
    const { container } = renderWithProviders(<DocumentChecklistRow doc={makeDoc()} compact />);
    const row = container.querySelector('[data-slot="document-checklist-row"]');
    expect(row?.getAttribute("data-compact")).toBe("true");
  });

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(
      <DocumentChecklistRow
        doc={makeDoc({ requiredForDisbursement: true })}
        onView={() => {}}
        onDownload={() => {}}
      />,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
