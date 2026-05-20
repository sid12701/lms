import { describe, it, expect, vi, beforeEach } from "vitest";
import userEvent from "@testing-library/user-event";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { CopyableId } from "./CopyableId";

describe("CopyableId", () => {
  beforeEach(() => {
    Object.assign(navigator, {
      clipboard: { writeText: vi.fn().mockResolvedValue(undefined) },
    });
  });

  it("renders the value with the supplied aria-label", () => {
    const { getByRole } = renderWithProviders(<CopyableId value="loan-123" label="Loan ID" />);
    const btn = getByRole("button", { name: "Loan ID" });
    expect(btn).toBeInTheDocument();
    expect(btn.textContent).toContain("loan-123");
  });

  it("truncates the displayed text when `truncate` is set", () => {
    const { getByRole } = renderWithProviders(<CopyableId value="abcdefghijklmnop" truncate={6} />);
    expect(getByRole("button").textContent).toContain("abcdef…");
  });

  it("copies the full (non-truncated) value to clipboard", async () => {
    const { getByRole } = renderWithProviders(<CopyableId value="abcdefghijklmnop" truncate={6} />);
    await userEvent.click(getByRole("button"));
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith("abcdefghijklmnop");
  });

  it("forwards className", () => {
    const { container } = renderWithProviders(<CopyableId value="x" className="my-id" />);
    expect(container.querySelector('[data-slot="copyable-id"]')).toHaveClass("my-id");
  });

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(
      <CopyableId value="abc-123" label="Correlation ID" />,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
