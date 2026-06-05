import { describe, it, expect } from "vitest";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { FormSection } from "./FormSection";

describe("FormSection", () => {
  it("renders as a fieldset/legend when title is provided", () => {
    const { container, getByText } = renderWithProviders(
      <FormSection title="Personal" description="Borrower details">
        <p>body</p>
      </FormSection>,
    );
    expect(container.querySelector("fieldset")).toBeInTheDocument();
    expect(container.querySelector("legend")?.textContent).toBe("Personal");
    expect(getByText("Borrower details")).toBeInTheDocument();
  });

  it("renders as a div without a title", () => {
    const { container } = renderWithProviders(
      <FormSection>
        <p>body</p>
      </FormSection>,
    );
    expect(container.querySelector("fieldset")).toBeNull();
    expect(container.querySelector('[data-slot="form-section"]')).toBeInTheDocument();
  });

  it("forwards className", () => {
    const { container } = renderWithProviders(
      <FormSection title="x" className="my-fs">
        <p>body</p>
      </FormSection>,
    );
    expect(container.querySelector("fieldset")).toHaveClass("my-fs");
  });

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(
      <FormSection title="Personal">
        <label htmlFor="n">Name</label>
        <input id="n" />
      </FormSection>,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
