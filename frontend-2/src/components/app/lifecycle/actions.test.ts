import { describe, it, expect } from "vitest";
import { TRANSITIONS } from "@/lib/lifecycle";
import { LIFECYCLE_ACTIONS, actionsFor, canTransition } from "./actions";

describe("LIFECYCLE_ACTIONS", () => {
  it("contains only rules with at least one allowed role (no SYSTEM-only)", () => {
    for (const action of LIFECYCLE_ACTIONS) {
      const [from, to] = action.id.split("__") as [string, string];
      const rule = TRANSITIONS.find((r) => r.from === from && r.to === to);
      expect(rule, `expected rule for ${action.id}`).toBeTruthy();
      expect(rule!.allowedRoles.length).toBeGreaterThan(0);
    }
  });

  it("re-exports canTransition", () => {
    expect(typeof canTransition).toBe("function");
  });

  it("uses unique ids", () => {
    const ids = new Set<string>();
    for (const a of LIFECYCLE_ACTIONS) {
      expect(ids.has(a.id)).toBe(false);
      ids.add(a.id);
    }
  });

  it("marks REJECTED / CANCELLED / INVALIDATED as destructive + reason-required", () => {
    const destructiveTargets = ["REJECTED", "CANCELLED", "INVALIDATED"] as const;
    for (const target of destructiveTargets) {
      const matches = LIFECYCLE_ACTIONS.filter((a) => a.toStatus === target);
      expect(matches.length).toBeGreaterThan(0);
      for (const m of matches) {
        expect(m.tone).toBe("destructive");
        expect(m.requiresReason).toBe(true);
      }
    }
  });

  it("marks the approve path (AWAITING_APPROVAL → APPROVED_PENDING_DISBURSAL) as approve tone", () => {
    // Two rules land on APPROVED_PENDING_DISBURSAL: the underwriter-approval
    // rule (intent=primary → approve) and the disbursement-failure rollback
    // (intent=destructive → destructive). Only the former is the "approve"
    // surface — exclude rules whose backing rule.intent is destructive.
    const approve = LIFECYCLE_ACTIONS.filter(
      (a) => a.toStatus === "APPROVED_PENDING_DISBURSAL" && a.tone !== "destructive",
    );
    expect(approve.length).toBeGreaterThan(0);
    for (const a of approve) expect(a.tone).toBe("approve");
  });

  it("flags every destructive action as requiresReason=true", () => {
    const destructive = LIFECYCLE_ACTIONS.filter((a) => a.tone === "destructive");
    for (const a of destructive) expect(a.requiresReason).toBe(true);
  });

  it("assigns a non-empty permission to every action", () => {
    for (const a of LIFECYCLE_ACTIONS) {
      expect(typeof a.permission).toBe("string");
      expect(a.permission.length).toBeGreaterThan(0);
    }
  });
});

describe("actionsFor()", () => {
  it("returns all UI-actionable rules from a given status", () => {
    const fromAwaiting = actionsFor("AWAITING_APPROVAL");
    const targets = fromAwaiting.map((a) => a.toStatus).sort();
    // From AWAITING_APPROVAL we expect APPROVED_PENDING_DISBURSAL, REJECTED, INVALIDATED.
    expect(targets).toContain("APPROVED_PENDING_DISBURSAL");
    expect(targets).toContain("REJECTED");
    expect(targets).toContain("INVALIDATED");
  });

  it("returns [] for terminal statuses", () => {
    expect(actionsFor("CLOSED")).toEqual([]);
    expect(actionsFor("FULLY_REPAID")).toEqual([]);
    expect(actionsFor("INVALIDATED")).toEqual([]);
  });

  it("never returns a rule with zero allowed roles (system-only)", () => {
    const fromDisbursed = actionsFor("DISBURSED");
    // DISBURSED → UNDER_REPAYMENT is a SYSTEM_ONLY rule (BR-14), so no
    // human-actionable transitions should leave DISBURSED.
    expect(fromDisbursed).toEqual([]);
  });

  it("preserves the rule label by default", () => {
    const fromInitiated = actionsFor("INITIATED");
    const submit = fromInitiated.find((a) => a.toStatus === "AWAITING_APPROVAL");
    expect(submit?.label).toBe("Submit for review");
  });
});
