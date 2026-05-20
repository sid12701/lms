import { describe, it, expect } from "vitest";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { RightRail } from "./RightRail";

describe("RightRail", () => {
  it("renders an aside with the expected sticky/hidden classes", () => {
    const { container } = renderWithProviders(
      <RightRail>
        <p>Status</p>
      </RightRail>,
    );
    const aside = container.querySelector("aside");
    expect(aside).toBeInTheDocument();
    expect(aside).toHaveClass("hidden", "xl:block", "sticky", "w-72");
  });

  it("forwards className", () => {
    const { container } = renderWithProviders(
      <RightRail className="extra">
        <span>x</span>
      </RightRail>,
    );
    expect(container.querySelector("aside")).toHaveClass("extra");
  });

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(
      <RightRail>
        <p>Status</p>
      </RightRail>,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
