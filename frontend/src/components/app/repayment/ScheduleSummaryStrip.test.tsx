import { describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { axeBaseElement, renderWithProviders } from "@/test/utils";
import type { RepaymentInstallment } from "@/schemas/loan-account";
import { formatINR } from "@/lib/format";
import { ScheduleSummaryStrip } from "./ScheduleSummaryStrip";
import { computeScheduleTotals } from "./scheduleTotals";

function installment(
  overrides: Partial<RepaymentInstallment> &
    Pick<RepaymentInstallment, "dueDate" | "outstandingAmount">,
): RepaymentInstallment {
  return {
    id: "11111111-1111-4111-8111-aaaaaaaaaaaa",
    accountId: "22222222-2222-4222-8222-222222222222",
    scheduleId: "33333333-3333-4333-8333-333333333333",
    number: 1,
    principalDue: 8000,
    interestDue: 2000,
    installmentAmount: 10000,
    paidAmount: 0,
    dpd: 0,
    delinquencyBucket: "B0",
    status: "DUE",
    ...overrides,
  };
}

// Same three-row shape as scheduleTotals.test.ts's "separates remaining book
// from amount due on or before asOf" case, so the totals below are a known
// quantity: totalInstallmentAmount 30000, totalOutstanding 25000,
// outstandingAsOfToday 15000 (May + June rows, July excluded).
const ROWS: RepaymentInstallment[] = [
  installment({ dueDate: "2026-05-01", outstandingAmount: 5000, installmentAmount: 10000 }),
  installment({
    id: "11111111-1111-4111-8111-bbbbbbbbbbbb",
    number: 2,
    dueDate: "2026-06-01",
    outstandingAmount: 10000,
    installmentAmount: 10000,
  }),
  installment({
    id: "11111111-1111-4111-8111-cccccccccccc",
    number: 3,
    dueDate: "2026-07-01",
    outstandingAmount: 10000,
    installmentAmount: 10000,
  }),
];

describe("ScheduleSummaryStrip", () => {
  it("renders nothing for an empty schedule", () => {
    const { container } = renderWithProviders(<ScheduleSummaryStrip installments={[]} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("renders the three totals exactly as computeScheduleTotals derives them", () => {
    // Fake timers scoped to this test only: userEvent's internal delays don't
    // mix with a globally-faked clock (see the interaction tests below, which
    // stay on real timers since they never touch "today").
    vi.useFakeTimers();
    try {
      // Noon UTC keeps the *local* calendar date at 15 June in every timezone
      // this suite runs in. The day-boundary edge case itself (local vs. UTC
      // date) is already covered by scheduleTotals.test.ts; this only needs a
      // stable "today" so the component's default `asOf` is predictable.
      vi.setSystemTime(new Date("2026-06-15T12:00:00.000Z"));
      const totals = computeScheduleTotals(ROWS, "2026-06-15");

      renderWithProviders(<ScheduleSummaryStrip installments={ROWS} />);

      expect(
        screen.getByText(formatINR(totals.totalInstallmentAmount, { decimals: 2 })),
      ).toBeInTheDocument();
      expect(
        screen.getByText(formatINR(totals.totalOutstanding, { decimals: 2 })),
      ).toBeInTheDocument();
      expect(
        screen.getByText(formatINR(totals.outstandingAsOfToday, { decimals: 2 })),
      ).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it("gives each figure a MetricHint that names it in the trigger's accessible name", () => {
    renderWithProviders(<ScheduleSummaryStrip installments={ROWS} />);

    expect(
      screen.getByRole("button", { name: "How Total installment amount is calculated" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "How Total outstanding is calculated" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "How Outstanding as of today is calculated" }),
    ).toBeInTheDocument();
  });

  it("opens each hint on its own explanation, not a shared/generic one", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ScheduleSummaryStrip installments={ROWS} />);

    await user.click(
      screen.getByRole("button", { name: "How Total installment amount is calculated" }),
    );
    expect(
      await screen.findByText(/every scheduled installment across the full tenure/i),
    ).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "How Total outstanding is calculated" }));
    expect(
      await screen.findByText(/unpaid balance across the whole schedule/i),
    ).toBeInTheDocument();

    await user.click(
      screen.getByRole("button", { name: "How Outstanding as of today is calculated" }),
    );
    // Load-bearing for borrower-facing legibility: the copy must state the
    // due-on-or-before-today rule, not just gesture at "current" balance.
    expect(await screen.findByText(/due on or before today/i)).toBeInTheDocument();
    expect(screen.getByText(/future installments are excluded/i)).toBeInTheDocument();
  });

  it("has no axe violations with a hint open (portalled content lives on baseElement)", async () => {
    const user = userEvent.setup();
    const { baseElement } = renderWithProviders(<ScheduleSummaryStrip installments={ROWS} />);

    await user.click(
      screen.getByRole("button", { name: "How Outstanding as of today is calculated" }),
    );
    await screen.findByText(/due on or before today/i);

    // `region` is off because it is a page-level rule: content must sit inside
    // a landmark, and an isolated component render has no `<main>` to sit in.
    // The strip's real page context is covered by the page-level tests.
    expect(await axeBaseElement(baseElement)).toHaveNoViolations();
  });
});
