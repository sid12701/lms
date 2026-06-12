import { describe, expect, it } from "vitest";
import { BACKEND_ALLOWED_TRANSITIONS } from "@/lib/loan-application-status";
import { TRANSITIONS } from "./lifecycle";

describe("TRANSITIONS vs BACKEND_ALLOWED_TRANSITIONS", () => {
  it("every UI transition edge is allowed by the backend matrix", () => {
    for (const rule of TRANSITIONS) {
      const allowed = BACKEND_ALLOWED_TRANSITIONS[rule.from];
      expect(
        allowed,
        `backend matrix missing from-status ${rule.from}`,
      ).toBeDefined();
      expect(
        allowed.includes(rule.to),
        `UI offers ${rule.from} → ${rule.to} but backend disallows it`,
      ).toBe(true);
    }
  });

  it("exposes human-actionable ops transitions from AWAITING_APPROVAL", () => {
    const human = TRANSITIONS.filter(
      (r) => r.from === "AWAITING_APPROVAL" && r.allowedRoles.length > 0,
    );
    const targets = human.map((r) => r.to).sort();
    expect(targets).toEqual(["APPROVED_PENDING_DISBURSAL", "INVALID", "REJECTED"]);
  });
});
