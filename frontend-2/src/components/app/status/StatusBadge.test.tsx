import { describe, it, expect } from "vitest";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { STATUS_META } from "@/lib/lifecycle";
import type { LoanAccountStatus, LoanStatus } from "@/types";
import { StatusBadge } from "./StatusBadge";
import { resolveStatusMeta } from "./statusBadgeMeta";

const ACCOUNT_STATUSES: LoanAccountStatus[] = [
  "PENDING_DISBURSEMENT",
  "ACTIVE",
  "CLOSED",
  "FORECLOSED",
];

describe("StatusBadge", () => {
  it("renders the status label and an icon by default", () => {
    const { getByText, container } = renderWithProviders(<StatusBadge status="APPROVED" />);
    expect(getByText("Approved")).toBeInTheDocument();
    // icon is svg sibling of the label
    const badge = container.querySelector('[data-slot="status-badge"]');
    expect(badge?.querySelector("svg")).toBeInTheDocument();
  });

  it("hides the icon when hideIcon is true", () => {
    const { container } = renderWithProviders(<StatusBadge status="APPROVED" hideIcon />);
    const badge = container.querySelector('[data-slot="status-badge"]');
    expect(badge?.querySelector("svg")).toBeNull();
  });

  it("forwards data-status, data-intent, data-variant", () => {
    const { container } = renderWithProviders(<StatusBadge status="REJECTED" variant="default" />);
    const badge = container.querySelector('[data-slot="status-badge"]');
    expect(badge?.getAttribute("data-status")).toBe("REJECTED");
    expect(badge?.getAttribute("data-intent")).toBe("danger");
    expect(badge?.getAttribute("data-variant")).toBe("default");
  });

  it("maps every LoanStatus enum value to a non-empty meta entry", () => {
    const loanStatuses = Object.keys(STATUS_META) as LoanStatus[];
    expect(loanStatuses.length).toBeGreaterThan(0);
    for (const s of loanStatuses) {
      const meta = resolveStatusMeta(s);
      expect(meta.label.length).toBeGreaterThan(0);
      expect(meta.intent.length).toBeGreaterThan(0);
    }
  });

  it("maps every LoanAccountStatus enum value to a non-empty meta entry", () => {
    for (const s of ACCOUNT_STATUSES) {
      const meta = resolveStatusMeta(s);
      expect(meta.label.length).toBeGreaterThan(0);
      expect(meta.intent.length).toBeGreaterThan(0);
    }
  });

  it("renders correct intent token for each canonical intent", () => {
    const samples: Array<{ status: LoanStatus; intent: string }> = [
      { status: "INITIATED", intent: "neutral" },
      { status: "UNDER_REVIEW", intent: "progress" },
      { status: "DISBURSED", intent: "success" },
      { status: "DELINQUENT", intent: "warning" },
      { status: "REJECTED", intent: "danger" },
      { status: "INVALIDATED", intent: "revoked" },
    ];
    for (const { status, intent } of samples) {
      const { container, unmount } = renderWithProviders(<StatusBadge status={status} />);
      const badge = container.querySelector('[data-slot="status-badge"]');
      expect(badge?.getAttribute("data-intent")).toBe(intent);
      unmount();
    }
  });

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(<StatusBadge status="APPROVED" />);
    expect(await axe(container)).toHaveNoViolations();
  });
});
