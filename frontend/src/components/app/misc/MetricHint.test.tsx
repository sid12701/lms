import { describe, expect, it } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { axeBaseElement, renderWithProviders } from "@/test/utils";
import { MetricHint } from "./MetricHint";

describe("MetricHint", () => {
  it("names the control after the figure it explains", () => {
    renderWithProviders(
      <MetricHint label="Outstanding as of today">Due on or before today.</MetricHint>,
    );

    expect(
      screen.getByRole("button", { name: "How Outstanding as of today is calculated" }),
    ).toBeInTheDocument();
  });

  it("keeps the explanation out of the resting page and reveals it on activation", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <MetricHint label="Avg approval TAT">
        Mean hours from submission to approval decision.
      </MetricHint>,
    );

    expect(screen.queryByText(/mean hours from submission/i)).not.toBeInTheDocument();

    await user.click(screen.getByRole("button"));

    expect(await screen.findByText(/mean hours from submission/i)).toBeInTheDocument();
  });

  it("dismisses on Escape and returns focus to the trigger", async () => {
    const user = userEvent.setup();
    renderWithProviders(<MetricHint label="Total outstanding">Sum of unpaid balances.</MetricHint>);

    const trigger = screen.getByRole("button");
    await user.click(trigger);
    expect(await screen.findByText(/sum of unpaid balances/i)).toBeInTheDocument();

    await user.keyboard("{Escape}");

    expect(screen.queryByText(/sum of unpaid balances/i)).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it("has no axe violations while open", async () => {
    const user = userEvent.setup();
    // The panel renders through a portal, so it lives on `baseElement`,
    // not the render container.
    const { baseElement } = renderWithProviders(
      <MetricHint label="Days past due">
        Days since the oldest unpaid installment fell due.
      </MetricHint>,
    );
    await user.click(screen.getByRole("button"));
    await screen.findByText(/days since the oldest/i);

    expect(await axeBaseElement(baseElement)).toHaveNoViolations();
  });
});
