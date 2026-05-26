import { describe, it, expect } from "vitest";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import type { DocumentStatus } from "@/schemas/document";
import { DocumentStatusPill, resolveDocumentStatusMeta } from "./DocumentStatusPill";

const CASES: Array<{
  status: DocumentStatus;
  label: string;
  tone: "warning" | "info";
}> = [
  { status: "PENDING", label: "Pending", tone: "warning" },
  { status: "UPLOADED", label: "Uploaded", tone: "info" },
];

describe("DocumentStatusPill", () => {
  it.each(CASES)(
    "renders the right label, tone, and icon for status=$status",
    ({ status, label, tone }) => {
      const { container, getByText } = renderWithProviders(
        <DocumentStatusPill status={status} />,
      );
      expect(getByText(label)).toBeInTheDocument();
      const pill = container.querySelector('[data-slot="document-status-pill"]');
      expect(pill).not.toBeNull();
      expect(pill?.getAttribute("data-status")).toBe(status);
      expect(pill?.getAttribute("data-tone")).toBe(tone);
      expect(pill?.querySelector("svg")).toBeInTheDocument();
    },
  );

  it("hides the icon when hideIcon is true", () => {
    const { container } = renderWithProviders(
      <DocumentStatusPill status="UPLOADED" hideIcon />,
    );
    const pill = container.querySelector('[data-slot="document-status-pill"]');
    expect(pill?.querySelector("svg")).toBeNull();
  });

  it("forwards extra props (className, custom data-*)", () => {
    const { container } = renderWithProviders(
      <DocumentStatusPill
        status="UPLOADED"
        className="extra-class"
        data-testid="my-pill"
      />,
    );
    const pill = container.querySelector('[data-slot="document-status-pill"]');
    expect(pill).not.toBeNull();
    expect(pill?.className).toMatch(/extra-class/);
    expect(pill?.getAttribute("data-testid")).toBe("my-pill");
  });

  it("resolveDocumentStatusMeta returns a populated entry for every enum value", () => {
    for (const { status, label, tone } of CASES) {
      const meta = resolveDocumentStatusMeta(status);
      expect(meta.label).toBe(label);
      expect(meta.tone).toBe(tone);
      expect(meta.icon).toBeDefined();
    }
  });

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(<DocumentStatusPill status="PENDING" />);
    expect(await axe(container)).toHaveNoViolations();
  });
});
