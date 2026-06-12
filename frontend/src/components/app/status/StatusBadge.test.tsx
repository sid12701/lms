import { describe, it, expect } from "vitest";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { STATUS_META } from "@/lib/lifecycle";
import { LOAN_APPLICATION_STATUSES } from "@/lib/loan-application-status";
import type { LoanAccountStatus } from "@/types";
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
    const { getByText, container } = renderWithProviders(
      <StatusBadge status="APPROVED_PENDING_DISBURSAL" />,
    );
    expect(getByText("Approved · pending disbursal")).toBeInTheDocument();
    const badge = container.querySelector('[data-slot="status-badge"]');
    expect(badge?.querySelector("svg")).toBeInTheDocument();
  });

  it("hides the icon when hideIcon is true", () => {
    const { container } = renderWithProviders(
      <StatusBadge status="APPROVED_PENDING_DISBURSAL" hideIcon />,
    );
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
    for (const s of LOAN_APPLICATION_STATUSES) {
      const meta = resolveStatusMeta(s);
      expect(meta.label.length).toBeGreaterThan(0);
      expect(meta.intent.length).toBeGreaterThan(0);
      expect(STATUS_META[s]).toBeDefined();
    }
  });

  it("maps every LoanAccountStatus enum value to a non-empty meta entry", () => {
    for (const s of ACCOUNT_STATUSES) {
      const meta = resolveStatusMeta(s);
      expect(meta.label.length).toBeGreaterThan(0);
      expect(meta.intent.length).toBeGreaterThan(0);
    }
  });

  it("renders correct intent token for canonical statuses", () => {
    const samples = [
      { status: "INITIALIZED" as const, intent: "neutral" },
      { status: "AWAITING_APPROVAL" as const, intent: "progress" },
      { status: "DISBURSED" as const, intent: "success" },
      { status: "DISBURSEMENT_RETRY" as const, intent: "warning" },
      { status: "REJECTED" as const, intent: "danger" },
      { status: "INVALID" as const, intent: "revoked" },
    ];
    for (const { status, intent } of samples) {
      const { container, unmount } = renderWithProviders(<StatusBadge status={status} />);
      const badge = container.querySelector('[data-slot="status-badge"]');
      expect(badge?.getAttribute("data-intent")).toBe(intent);
      unmount();
    }
  });

  it("renders unknown API statuses without folding", () => {
    const { getByText } = renderWithProviders(<StatusBadge status="UNKNOWN:KYC_PENDING" />);
    expect(getByText("Unknown (KYC_PENDING)")).toBeInTheDocument();
  });

  it("UNDER_REPAYMENT is success when on track (Gap #11)", () => {
    const meta = resolveStatusMeta("UNDER_REPAYMENT", {
      delinquency: { maxDaysPastDue: 0, overdueInstallmentCount: 0 },
    });
    expect(meta.intent).toBe("success");
    const { container } = renderWithProviders(
      <StatusBadge
        status="UNDER_REPAYMENT"
        delinquency={{ maxDaysPastDue: 0, overdueInstallmentCount: 0 }}
      />,
    );
    expect(container.querySelector('[data-intent="success"]')).toBeTruthy();
  });

  it("UNDER_REPAYMENT is danger when delinquent (Gap #11)", () => {
    const meta = resolveStatusMeta("UNDER_REPAYMENT", {
      delinquency: { maxDaysPastDue: 12, overdueInstallmentCount: 2 },
    });
    expect(meta.intent).toBe("danger");
    const { container } = renderWithProviders(
      <StatusBadge
        status="UNDER_REPAYMENT"
        delinquency={{ maxDaysPastDue: 12, overdueInstallmentCount: 2 }}
      />,
    );
    expect(container.querySelector('[data-intent="danger"]')).toBeTruthy();
  });

  it("UNDER_REPAYMENT without delinquency prop keeps STATUS_META intent", () => {
    const meta = resolveStatusMeta("UNDER_REPAYMENT");
    expect(meta.intent).toBe("success");
  });

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(
      <StatusBadge status="APPROVED_PENDING_DISBURSAL" />,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
