/* eslint-disable jsx-a11y/aria-role -- "role" here is the ActionBar prop, not the ARIA attribute */
import { describe, it, expect, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { ActionBar } from "./ActionBar";

describe("<ActionBar />", () => {
  it("renders permitted actions enabled (SYSTEM_ADMIN on AWAITING_APPROVAL)", () => {
    const { getByRole } = renderWithProviders(
      <ActionBar
        currentStatus="AWAITING_APPROVAL"
        role="SYSTEM_ADMIN"
        gates={{ docsComplete: true, scheduleValid: true }}
        onConfirm={() => {}}
      />,
    );
    // Approve button is the standard happy path; renders enabled.
    const approve = getByRole("button", { name: "Approve" });
    expect(approve).toBeEnabled();
    const reject = getByRole("button", { name: "Reject" });
    expect(reject).toBeEnabled();
  });

  it("disables actions blocked by docsComplete=false (BR-2/BR-3 gate)", () => {
    const { getByRole } = renderWithProviders(
      <ActionBar
        currentStatus="AWAITING_APPROVAL"
        role="SYSTEM_ADMIN"
        gates={{ docsComplete: false, scheduleValid: true }}
        onConfirm={() => {}}
      />,
    );
    const approve = getByRole("button", { name: "Approve" });
    expect(approve).toBeDisabled();
  });

  it("disables actions when role lacks the permission", () => {
    const { getByRole } = renderWithProviders(
      <ActionBar currentStatus="AWAITING_APPROVAL" role="LSP_UI_READ" onConfirm={() => {}} />,
    );
    const approve = getByRole("button", { name: "Approve" });
    expect(approve).toBeDisabled();
  });

  it("renders an empty-state message when no transitions leave the status", () => {
    const { getByText } = renderWithProviders(
      <ActionBar currentStatus="CLOSED" role="SYSTEM_ADMIN" onConfirm={() => {}} />,
    );
    expect(getByText(/No actions available/i)).toBeInTheDocument();
  });

  it("can hide target statuses that are handled by a dedicated workflow", () => {
    const { queryByRole, getByText } = renderWithProviders(
      <ActionBar
        currentStatus="UNDER_REPAYMENT"
        role="SYSTEM_ADMIN"
        gates={{ docsComplete: true, scheduleValid: true }}
        hiddenTargetStatuses={["FORECLOSED"]}
        onConfirm={() => {}}
      />,
    );
    expect(queryByRole("button", { name: "Settle foreclosure" })).not.toBeInTheDocument();
    expect(getByText(/No actions available/i)).toBeInTheDocument();
  });

  it("opens the confirm dialog when an enabled action is clicked", async () => {
    const { getByRole, findAllByRole } = renderWithProviders(
      <ActionBar
        currentStatus="AWAITING_APPROVAL"
        role="SYSTEM_ADMIN"
        gates={{ docsComplete: true, scheduleValid: true }}
        onConfirm={() => {}}
      />,
    );
    await userEvent.click(getByRole("button", { name: "Approve" }));
    // Heading + description with the same word — assert the heading exists.
    const headings = await findAllByRole("heading", { name: /Approve/i });
    expect(headings.length).toBeGreaterThan(0);
  });

  it("submits the full flow (click → pick reason code → fill → confirm)", async () => {
    const onConfirm = vi.fn().mockResolvedValue(undefined);
    const { getByRole, findByRole, findByLabelText, getAllByRole } = renderWithProviders(
      <ActionBar
        currentStatus="AWAITING_APPROVAL"
        role="SYSTEM_ADMIN"
        gates={{ docsComplete: true, scheduleValid: true }}
        onConfirm={onConfirm}
      />,
    );
    await userEvent.click(getByRole("button", { name: "Reject" }));
    // REJECTED requires a structured reason code (backend REASON_CODE_REQUIRED).
    await userEvent.click(await findByLabelText("Reason code"));
    await userEvent.click(await findByRole("option", { name: /Duplicate application/i }));
    const detailsInput = await findByLabelText(/Details/i);
    await userEvent.type(detailsInput, "Application withdrawn by borrower.");
    const submit = getAllByRole("button", { name: /Reject/i }).find(
      (b) => (b as HTMLButtonElement).type === "submit",
    )!;
    await userEvent.click(submit);

    expect(onConfirm).toHaveBeenCalledTimes(1);
    const [args] = onConfirm.mock.calls[0]!;
    expect(args.action.toStatus).toBe("REJECTED");
    expect(args.reason).toBe("Application withdrawn by borrower.");
    expect(args.reasonCode).toBe("DUPLICATE_APPLICATION");
    expect(typeof args.idempotencyKey).toBe("string");
  }, 15_000);

  it("has no axe violations in the default rendered state", async () => {
    const { container } = renderWithProviders(
      <ActionBar
        currentStatus="AWAITING_APPROVAL"
        role="SYSTEM_ADMIN"
        gates={{ docsComplete: true, scheduleValid: true }}
        onConfirm={() => {}}
      />,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
