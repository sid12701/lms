import { describe, expect, it } from "vitest";
import {
  ClosureReason,
  DelinquencyBucket,
  InstallmentStatus,
  LoanAccount,
  LoanAccountStatus,
  RepaymentInstallment,
  RepaymentSchedule,
  ScheduleGenerator,
} from "./loan-account";

const UUID = "550e8400-e29b-41d4-a716-446655440000";
const NOW = "2026-05-09T10:00:00.000Z";

describe("loan-account enums", () => {
  it("LoanAccountStatus members", () => {
    for (const s of ["PENDING_DISBURSEMENT", "ACTIVE", "CLOSED", "FORECLOSED"]) {
      expect(LoanAccountStatus.safeParse(s).success).toBe(true);
    }
    expect(LoanAccountStatus.safeParse("OPEN").success).toBe(false);
  });

  it("ClosureReason members", () => {
    for (const r of ["FULLY_REPAID", "FORECLOSED", "CANCELLED"]) {
      expect(ClosureReason.safeParse(r).success).toBe(true);
    }
    expect(ClosureReason.safeParse("WRITTEN_OFF").success).toBe(false);
  });

  it("ScheduleGenerator only allows PLATFORM or LSP", () => {
    expect(ScheduleGenerator.safeParse("PLATFORM").success).toBe(true);
    expect(ScheduleGenerator.safeParse("LSP").success).toBe(true);
    expect(ScheduleGenerator.safeParse("EXTERNAL").success).toBe(false);
  });

  it("DelinquencyBucket members", () => {
    for (const b of ["B0", "B1_30", "B31_60", "B61_90", "B90_PLUS"]) {
      expect(DelinquencyBucket.safeParse(b).success).toBe(true);
    }
    expect(DelinquencyBucket.safeParse("B100").success).toBe(false);
  });

  it("InstallmentStatus members", () => {
    for (const s of ["DUE", "PARTIALLY_PAID", "PAID", "OVERDUE"]) {
      expect(InstallmentStatus.safeParse(s).success).toBe(true);
    }
    expect(InstallmentStatus.safeParse("WAIVED").success).toBe(false);
  });
});

describe("LoanAccount", () => {
  function base() {
    return {
      id: UUID,
      applicationId: UUID,
      accountNumber: "ACC-0001",
      accountStatus: "ACTIVE" as const,
      principal: 50_000,
      tenureMonths: 12,
      approvedAt: NOW,
      createdAt: NOW,
      closedAt: null,
      closureReason: null,
    };
  }

  it("accepts a valid active account", () => {
    expect(LoanAccount.safeParse(base()).success).toBe(true);
  });

  it("rejects accountNumber shorter than 4 chars", () => {
    expect(LoanAccount.safeParse({ ...base(), accountNumber: "ABC" }).success).toBe(false);
  });

  it("rejects principal === 0 (positive only)", () => {
    expect(LoanAccount.safeParse({ ...base(), principal: 0 }).success).toBe(false);
  });
});

describe("RepaymentSchedule", () => {
  it("BR-12: frozen flag is required and boolean", () => {
    expect(
      RepaymentSchedule.safeParse({
        id: UUID,
        accountId: UUID,
        generatedBy: "PLATFORM",
        frozen: false,
        createdAt: NOW,
      }).success,
    ).toBe(true);
  });

  it("rejects missing generatedBy", () => {
    expect(
      RepaymentSchedule.safeParse({
        id: UUID,
        accountId: UUID,
        frozen: true,
        createdAt: NOW,
      }).success,
    ).toBe(false);
  });
});

describe("RepaymentInstallment", () => {
  function inst() {
    return {
      id: UUID,
      accountId: UUID,
      scheduleId: UUID,
      number: 1,
      dueDate: "2026-06-09",
      principalDue: 4000,
      interestDue: 500,
      installmentAmount: 4500,
      paidAmount: 0,
      outstandingAmount: 4500,
      dpd: 0,
      delinquencyBucket: "B0" as const,
      status: "DUE" as const,
    };
  }

  it("accepts a valid installment row", () => {
    expect(RepaymentInstallment.safeParse(inst()).success).toBe(true);
  });

  it("rejects negative dpd", () => {
    expect(RepaymentInstallment.safeParse({ ...inst(), dpd: -1 }).success).toBe(false);
  });

  it("rejects number > 360", () => {
    expect(RepaymentInstallment.safeParse({ ...inst(), number: 361 }).success).toBe(false);
  });
});
