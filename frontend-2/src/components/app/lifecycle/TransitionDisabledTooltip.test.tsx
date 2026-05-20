import { describe, it, expect } from "vitest";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { Button } from "@/components/ui/button";
import {
  TransitionDisabledTooltip,
  resolveDisabledReason,
} from "./TransitionDisabledTooltip";
import type { LifecycleAction } from "./actions";

const APPROVE_ACTION: LifecycleAction = {
  id: "AWAITING_APPROVAL__APPROVED_PENDING_DISBURSAL",
  label: "Approve",
  toStatus: "APPROVED_PENDING_DISBURSAL",
  tone: "approve",
  requiresReason: false,
  permission: "LOAN_STATUS_UPDATE",
};

const DISBURSE_ACTION: LifecycleAction = {
  id: "APPROVED_PENDING_DISBURSAL__DISBURSEMENT_IN_PROGRESS",
  label: "Initiate disbursement",
  toStatus: "DISBURSEMENT_IN_PROGRESS",
  tone: "approve",
  requiresReason: false,
  permission: "DISBURSEMENT_TRIGGER",
};

describe("resolveDisabledReason()", () => {
  it("returns null when role is permitted and gates are clean", () => {
    const reason = resolveDisabledReason(
      APPROVE_ACTION,
      "AWAITING_APPROVAL",
      "SYSTEM_ADMIN",
      { docsComplete: true, scheduleValid: true },
    );
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
      "INITIATED", // no rule from INITIATED → APPROVED_PENDING_DISBURSAL
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

  it("disables the child when disabledReason is set", () => {
    const { getByRole } = renderWithProviders(
      <TransitionDisabledTooltip disabledReason="Insufficient permissions.">
        <Button type="button">Approve</Button>
      </TransitionDisabledTooltip>,
    );
    const btn = getByRole("button", { name: "Approve" });
    expect(btn).toBeDisabled();
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
});
