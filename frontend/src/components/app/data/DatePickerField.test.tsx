import { describe, it, expect, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen } from "@testing-library/react";
import { axe } from "vitest-axe";
import { axeBaseElement, renderWithProviders } from "@/test/utils";
import { pickDateInField } from "@/test/date-picker";
import { formatPickerDate } from "@/lib/format";
import { DatePickerField } from "./DatePickerField";

describe("DatePickerField", () => {
  it("renders dd/MM/yyyy for a set value", () => {
    renderWithProviders(
      <DatePickerField value="2026-04-01" onChange={() => {}} ariaLabel="Test date" />,
    );
    expect(formatPickerDate("2026-04-01")).toBe("01/04/2026");
    expect(screen.getByText("01/04/2026")).toBeInTheDocument();
  });

  it("emits ISO yyyy-MM-dd when a day is picked", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithProviders(<DatePickerField onChange={onChange} ariaLabel="Disbursed from" />);

    await pickDateInField(user, "Disbursed from", "2026-06-15");

    expect(onChange).toHaveBeenLastCalledWith(expect.stringMatching(/^\d{4}-\d{2}-15$/));
  });

  it("clears the value when the clear control is used", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithProviders(
      <DatePickerField value="2026-05-01" onChange={onChange} ariaLabel="Disbursed to" />,
    );

    await user.click(screen.getByLabelText(/Clear Disbursed to/i));
    expect(onChange).toHaveBeenCalledWith(undefined);
  });

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(
      <DatePickerField value="2026-06-01" onChange={() => {}} ariaLabel="Audit from" />,
    );
    expect(await axe(container)).toHaveNoViolations();
  });

  it("has no axe violations with the calendar popover open (portalled content lives on baseElement)", async () => {
    const user = userEvent.setup();
    const { baseElement } = renderWithProviders(
      <DatePickerField value="2026-06-01" onChange={() => {}} ariaLabel="Audit from" />,
    );
    await user.click(screen.getByRole("button", { name: "Audit from" }));
    await screen.findByRole("grid");

    expect(await axeBaseElement(baseElement)).toHaveNoViolations();
  });
});
