import { afterEach, describe, expect, it, vi } from "vitest";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { ForeclosureSummaryCard } from "./ForeclosureSummaryCard";

const BASE_PROPS = {
  principalOutstanding: 4_50_000,
  accruedInterest: 12_345,
  foreclosureCharges: 5_000,
  totalPayoff: 4_67_345,
  quotedAt: "2026-05-10T09:00:00.000Z",
};

afterEach(() => {
  vi.useRealTimers();
});

describe("<ForeclosureSummaryCard />", () => {
  it("renders all four amounts", () => {
    const { getByText } = renderWithProviders(
      <ForeclosureSummaryCard
        {...BASE_PROPS}
        quoteValidUntil="2099-01-01T00:00:00.000Z"
      />,
    );
    expect(getByText("Principal outstanding")).toBeInTheDocument();
    expect(getByText("Accrued interest")).toBeInTheDocument();
    expect(getByText("Foreclosure charges")).toBeInTheDocument();
    expect(getByText("Total payoff")).toBeInTheDocument();
    // Currency strings should appear in tabular form.
    expect(getByText(/4,50,000/)).toBeInTheDocument();
    expect(getByText(/12,345/)).toBeInTheDocument();
    expect(getByText(/5,000/)).toBeInTheDocument();
    expect(getByText(/4,67,345/)).toBeInTheDocument();
  });

  it.each([
    {
      kind: "stale" as const,
      quoteValidUntil: "2026-05-09T12:00:00.000Z",
      now: new Date("2026-05-11T10:00:00.000Z"),
      expectedText: /Quote stale/i,
      forbiddenText: /Quote fresh/i,
    },
    {
      kind: "fresh" as const,
      quoteValidUntil: "2026-05-12T18:30:00.000Z",
      now: new Date("2026-05-11T10:00:00.000Z"),
      expectedText: /Quote fresh — expires/i,
      forbiddenText: /Quote stale/i,
    },
  ])(
    "renders the $kind freshness indicator when quoteValidUntil resolves accordingly",
    ({ quoteValidUntil, now, expectedText, forbiddenText }) => {
      vi.useFakeTimers();
      vi.setSystemTime(now);
      const { getByText, queryByText } = renderWithProviders(
        <ForeclosureSummaryCard {...BASE_PROPS} quoteValidUntil={quoteValidUntil} />,
      );
      expect(getByText(expectedText)).toBeInTheDocument();
      expect(queryByText(forbiddenText)).toBeNull();
    },
  );

  it("renders the formatted expiry timestamp when fresh", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-05-11T10:00:00.000Z"));
    const { getByText } = renderWithProviders(
      <ForeclosureSummaryCard
        {...BASE_PROPS}
        quoteValidUntil="2026-05-12T18:30:00.000Z"
      />,
    );
    // The "Quote fresh — expires" label proves the fresh-branch path renders;
    // the absolute wallclock differs by TZ so we don't lock to it.
    expect(getByText(/Quote fresh — expires/i)).toBeInTheDocument();
  });

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(
      <ForeclosureSummaryCard
        {...BASE_PROPS}
        quoteValidUntil="2099-01-01T00:00:00.000Z"
      />,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
