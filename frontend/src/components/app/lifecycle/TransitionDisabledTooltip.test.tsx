import { describe, it, expect, vi } from "vitest";
import { waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { axe } from "vitest-axe";
import { axeBaseElement, renderWithProviders } from "@/test/utils";
import { Button } from "@/components/ui/button";
import { TransitionDisabledTooltip, resolveDisabledReason } from "./TransitionDisabledTooltip";
import type { LifecycleAction } from "./actions";

const APPROVE_ACTION: LifecycleAction = {
  id: "AWAITING_APPROVAL__APPROVED_PENDING_DISBURSAL",
  label: "Approve",
  toStatus: "APPROVED_PENDING_DISBURSAL",
  tone: "approve",
  requiresReason: false,
  requiresReasonCode: false,
  permission: "LOAN_STATUS_UPDATE",
};

const DISBURSE_ACTION: LifecycleAction = {
  id: "APPROVED_PENDING_DISBURSAL__DISBURSED",
  label: "Initiate disbursement",
  toStatus: "DISBURSED",
  tone: "approve",
  requiresReason: false,
  requiresReasonCode: false,
  permission: "DISBURSEMENT_TRIGGER",
};

describe("resolveDisabledReason()", () => {
  it("returns null when role is permitted and gates are clean", () => {
    const reason = resolveDisabledReason(APPROVE_ACTION, "AWAITING_APPROVAL", "SYSTEM_ADMIN", {
      docsComplete: true,
      scheduleValid: true,
    });
    expect(reason).toBeNull();
  });

  it("returns 'Already in this status' when current === target", () => {
    const reason = resolveDisabledReason(
      APPROVE_ACTION,
      "APPROVED_PENDING_DISBURSAL",
      "SYSTEM_ADMIN",
    );
    expect(reason).toMatch(/already/i);
  });

  it("returns 'Insufficient permissions' when role lacks the permission", () => {
    const reason = resolveDisabledReason(
      DISBURSE_ACTION,
      "APPROVED_PENDING_DISBURSAL",
      "LSP_UI_READ",
    );
    expect(reason).toMatch(/permissions/i);
  });

  it("returns the docs gate reason when docsComplete=false", () => {
    const reason = resolveDisabledReason(
      DISBURSE_ACTION,
      "APPROVED_PENDING_DISBURSAL",
      "SYSTEM_ADMIN",
      { docsComplete: false, scheduleValid: true },
    );
    expect(reason).toMatch(/Documents incomplete/i);
  });

  it("returns the schedule gate reason when scheduleValid=false", () => {
    const reason = resolveDisabledReason(
      DISBURSE_ACTION,
      "APPROVED_PENDING_DISBURSAL",
      "SYSTEM_ADMIN",
      { docsComplete: true, scheduleValid: false },
    );
    expect(reason).toMatch(/schedule/i);
  });

  it("falls back to 'Transition not allowed' when no matching rule exists", () => {
    const reason = resolveDisabledReason(
      APPROVE_ACTION,
      "INITIALIZED", // no rule from INITIALIZED → APPROVED_PENDING_DISBURSAL
      "SYSTEM_ADMIN",
    );
    expect(reason).toMatch(/not allowed/i);
  });
});

describe("<TransitionDisabledTooltip />", () => {
  it("renders the child unmodified when disabledReason is null", () => {
    const { getByRole } = renderWithProviders(
      <TransitionDisabledTooltip disabledReason={null}>
        <Button type="button">Approve</Button>
      </TransitionDisabledTooltip>,
    );
    const btn = getByRole("button", { name: "Approve" });
    expect(btn).toBeEnabled();
  });

  it("marks the child aria-disabled but keeps it focusable", async () => {
    const user = userEvent.setup();
    const { getByRole } = renderWithProviders(
      <TransitionDisabledTooltip disabledReason="Insufficient permissions.">
        <Button type="button">Approve</Button>
      </TransitionDisabledTooltip>,
    );
    const btn = getByRole("button", { name: "Approve" });
    expect(btn).toHaveAttribute("aria-disabled", "true");
    // Native `disabled` would drop it out of the tab order, which is what hid
    // the reason from keyboard and screen-reader operators.
    expect(btn).not.toBeDisabled();
    await user.tab();
    expect(btn).toHaveFocus();
  });

  it("does not activate the action when clicked or keyed", async () => {
    const user = userEvent.setup();
    const onClick = vi.fn();
    const { getByRole } = renderWithProviders(
      <TransitionDisabledTooltip disabledReason="Documents incomplete.">
        <Button type="button" onClick={onClick}>
          Initiate disbursement
        </Button>
      </TransitionDisabledTooltip>,
    );
    const btn = getByRole("button", { name: "Initiate disbursement" });
    await user.click(btn);
    btn.focus();
    await user.keyboard("{Enter}");
    await user.keyboard(" ");
    expect(onClick).not.toHaveBeenCalled();
  });

  it("reveals the reason on keyboard focus and describes the control with it", async () => {
    const user = userEvent.setup();
    const { getByRole } = renderWithProviders(
      <TransitionDisabledTooltip disabledReason="Documents incomplete.">
        <Button type="button">Initiate disbursement</Button>
      </TransitionDisabledTooltip>,
    );
    const btn = getByRole("button", { name: "Initiate disbursement" });
    await user.tab();
    expect(btn).toHaveFocus();

    await waitFor(() => {
      expect(document.querySelector('[data-slot="tooltip-content"]')).not.toBeNull();
    });
    // Radix points the trigger at its visually-hidden copy of the content, so
    // the reason is announced rather than only painted.
    const describedBy = btn.getAttribute("aria-describedby");
    expect(describedBy).toBeTruthy();
    expect(document.getElementById(describedBy!)?.textContent).toMatch(/Documents incomplete/i);
  });

  it("has no axe violations in the disabled state", async () => {
    const { container } = renderWithProviders(
      <TransitionDisabledTooltip disabledReason="Documents incomplete (BR-3).">
        <Button type="button">Initiate disbursement</Button>
      </TransitionDisabledTooltip>,
    );
    expect(await axe(container)).toHaveNoViolations();
  });

  it("has no axe violations in the enabled state", async () => {
    const { container } = renderWithProviders(
      <TransitionDisabledTooltip disabledReason={null}>
        <Button type="button">Approve</Button>
      </TransitionDisabledTooltip>,
    );
    expect(await axe(container)).toHaveNoViolations();
  });

  it("has no axe violations with the reason tooltip open (portalled content lives on baseElement)", async () => {
    const user = userEvent.setup();
    const { baseElement } = renderWithProviders(
      <TransitionDisabledTooltip disabledReason="Documents incomplete (BR-3).">
        <Button type="button">Initiate disbursement</Button>
      </TransitionDisabledTooltip>,
    );
    // The blocked control is the trigger itself now: `aria-disabled` leaves
    // pointer events intact, so no wrapping span is needed to surface hover.
    await user.hover(document.querySelector("button")!);
    // Not `findByRole("tooltip")`: Radix renders the visible bubble *and* an
    // internal visually-hidden copy wired to the trigger's
    // `aria-describedby`, both carrying `role="tooltip"` — a unique-role
    // query throws "found multiple elements" here, so key off the content
    // slot instead.
    await waitFor(() => {
      expect(document.querySelector('[data-slot="tooltip-content"]')).not.toBeNull();
    });

    expect(await axeBaseElement(baseElement)).toHaveNoViolations();
  });
});
