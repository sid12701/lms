import { describe, it, expect, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { axe } from "vitest-axe";
import { Search } from "lucide-react";
import { renderWithProviders } from "@/test/utils";
import { EmptyState } from "./EmptyState";

describe("EmptyState", () => {
  it("renders title and default no-data variant", () => {
    const { getByText, container } = renderWithProviders(<EmptyState title="Nothing to see" />);
    expect(getByText("Nothing to see")).toBeInTheDocument();
    expect(container.querySelector('[data-variant="no-data"]')).toBeInTheDocument();
  });

  it("renders distinct markup for the filtered-empty variant", () => {
    const { container } = renderWithProviders(
      <EmptyState variant="filtered-empty" title="No matches" icon={Search} />,
    );
    expect(container.querySelector('[data-variant="filtered-empty"]')).toBeInTheDocument();
  });

  it("renders an action button and fires its onClick", async () => {
    const onClick = vi.fn();
    const { getByRole } = renderWithProviders(
      <EmptyState title="Empty" action={{ label: "Create one", onClick, tone: "secondary" }} />,
    );
    const button = getByRole("button", { name: "Create one" });
    await userEvent.click(button);
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it("forwards className", () => {
    const { container } = renderWithProviders(<EmptyState title="x" className="my-empty" />);
    expect(container.querySelector('[data-slot="empty-state"]')).toHaveClass("my-empty");
  });

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(
      <EmptyState
        title="No applications"
        description="Try widening your filters."
        action={{ label: "Reset", onClick: () => {} }}
      />,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
