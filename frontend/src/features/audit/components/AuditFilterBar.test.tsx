import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent } from "@testing-library/react";
import { renderWithProviders } from "@/test/utils";
import { AuditPageFilterBar } from "./AuditFilterBar";

describe("AuditPageFilterBar", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("does not render the removed free-text search input", () => {
    const onChange = vi.fn();
    const { queryByLabelText } = renderWithProviders(
      <AuditPageFilterBar value={{}} onChange={onChange} />,
    );

    expect(queryByLabelText("Search audit log")).not.toBeInTheDocument();
  });

  it("publishes loanApplicationId through onChange", () => {
    const onChange = vi.fn();
    const { getByLabelText } = renderWithProviders(
      <AuditPageFilterBar value={{}} onChange={onChange} />,
    );

    const input = getByLabelText("Loan");
    fireEvent.change(input, {
      target: { value: "11111111-1111-4111-8111-111111111111" },
    });
    vi.advanceTimersByTime(250);

    expect(onChange).toHaveBeenCalled();
    const lastCall = onChange.mock.calls.at(-1)?.[0];
    expect(lastCall?.loanApplicationId).toBe("11111111-1111-4111-8111-111111111111");
    expect(lastCall?.page).toBe(0);
  });
});
