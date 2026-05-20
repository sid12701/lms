import { describe, it, expect } from "vitest";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { PageSection } from "./PageSection";

describe("PageSection", () => {
  it("renders children inside a section", () => {
    const { getByText, container } = renderWithProviders(
      <PageSection>
        <p>Body</p>
      </PageSection>,
    );
    expect(container.querySelector("section")).toBeInTheDocument();
    expect(getByText("Body")).toBeInTheDocument();
  });

  it("renders header row when title/description/actions are provided", () => {
    const { getByRole, getByText } = renderWithProviders(
      <PageSection
        eyebrow="Loans"
        title="Pipeline"
        description="Live application pipeline"
        actions={<button type="button">Refresh</button>}
      >
        <p>Body</p>
      </PageSection>,
    );
    expect(getByRole("heading", { level: 2, name: "Pipeline" })).toBeInTheDocument();
    expect(getByText("Live application pipeline")).toBeInTheDocument();
    expect(getByText("Refresh")).toBeInTheDocument();
  });

  it("forwards className", () => {
    const { container } = renderWithProviders(
      <PageSection className="my-section">
        <span>x</span>
      </PageSection>,
    );
    expect(container.querySelector("section")).toHaveClass("my-section");
  });

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(
      <PageSection title="Section">
        <p>Body</p>
      </PageSection>,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
