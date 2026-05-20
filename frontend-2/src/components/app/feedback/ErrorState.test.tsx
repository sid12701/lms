import { describe, it, expect, vi, beforeEach } from "vitest";
import userEvent from "@testing-library/user-event";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { ErrorState } from "./ErrorState";

describe("ErrorState", () => {
  beforeEach(() => {
    Object.assign(navigator, {
      clipboard: { writeText: vi.fn().mockResolvedValue(undefined) },
    });
  });

  it("renders the default title under role=alert", () => {
    const { getByRole, getByText } = renderWithProviders(<ErrorState />);
    expect(getByRole("alert")).toBeInTheDocument();
    expect(getByText("Something went wrong")).toBeInTheDocument();
  });

  it("calls retry.onClick when retry button is pressed", async () => {
    const onClick = vi.fn();
    const { getByRole } = renderWithProviders(
      <ErrorState retry={{ onClick }} description="Network error" />,
    );
    await userEvent.click(getByRole("button", { name: "Try again" }));
    expect(onClick).toHaveBeenCalledOnce();
  });

  it("copies the correlation ID to the clipboard", async () => {
    const { getByRole } = renderWithProviders(<ErrorState correlationId="abc-123" />);
    const copyBtn = getByRole("button", { name: /Copy correlation ID/i });
    await userEvent.click(copyBtn);
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith("abc-123");
  });

  it("forwards className", () => {
    const { container } = renderWithProviders(<ErrorState className="oops" />);
    expect(container.querySelector('[role="alert"]')).toHaveClass("oops");
  });

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(
      <ErrorState
        description="Something failed"
        correlationId="abc-123"
        retry={{ onClick: () => {} }}
      />,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
