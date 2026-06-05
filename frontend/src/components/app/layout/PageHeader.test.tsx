import { describe, it, expect } from "vitest";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { PageHeader } from "./PageHeader";

describe("PageHeader", () => {
  it("renders the title in an h1 inside a header landmark", () => {
    const { getByRole } = renderWithProviders(<PageHeader title="Dashboard" />);
    expect(getByRole("banner")).toBeInTheDocument();
    expect(getByRole("heading", { level: 1, name: "Dashboard" })).toBeInTheDocument();
  });

  it("renders eyebrow, description, and actions when provided", () => {
    const { getByText } = renderWithProviders(
      <PageHeader
        eyebrow="Loans"
        title="All applications"
        description="Manage every loan application across LSPs."
        actions={<button type="button">Export</button>}
      />,
    );
    expect(getByText("Loans")).toBeInTheDocument();
    expect(getByText(/Manage every loan/)).toBeInTheDocument();
    expect(getByText("Export")).toBeInTheDocument();
  });

  it("forwards className", () => {
    const { container } = renderWithProviders(<PageHeader title="x" className="test-class" />);
    expect(container.querySelector("header")).toHaveClass("test-class");
  });

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(
      <PageHeader eyebrow="Eye" title="Title" description="Desc" />,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
