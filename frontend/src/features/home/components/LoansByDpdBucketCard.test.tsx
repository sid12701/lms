import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import type { DpdBucketSummary } from "../types";
import { LoansByDpdBucketCard } from "./LoansByDpdBucketCard";

const BUCKETS: DpdBucketSummary[] = [
  { bucket: "B0", count: 40 },
  { bucket: "B1_30", count: 100 },
  { bucket: "B31_60", count: 200 },
  { bucket: "B61_90", count: 50 },
  { bucket: "B90_PLUS", count: 10 },
];

function stubDimensions() {
  Object.defineProperty(HTMLElement.prototype, "offsetWidth", {
    configurable: true,
    get: () => 800,
  });
  Object.defineProperty(HTMLElement.prototype, "offsetHeight", {
    configurable: true,
    get: () => 280,
  });
}

function stubMotionPreference(matches: boolean) {
  Object.defineProperty(window, "matchMedia", {
    configurable: true,
    writable: true,
    value: vi.fn().mockReturnValue({
      matches,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }),
  });
}

beforeEach(() => {
  stubDimensions();
  stubMotionPreference(false);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("<LoansByDpdBucketCard />", () => {
  it("renders title, description, and aria summary", () => {
    const { getByText, container } = renderWithProviders(
      <LoansByDpdBucketCard buckets={BUCKETS} />,
    );
    expect(getByText("Loans by DPD bucket")).toBeInTheDocument();
    expect(
      getByText("Distribution of overdue loans across delinquency buckets."),
    ).toBeInTheDocument();
    const summary = container.querySelector("#loans-by-dpd-summary");
    expect(summary).not.toBeNull();
    expect(summary!.textContent).toMatch(/400 loans across 5 DPD buckets/);
  });

  it("renders the chart wrapper with aria-label and role=img", async () => {
    const { findByTestId } = renderWithProviders(<LoansByDpdBucketCard buckets={BUCKETS} />);
    const chart = await findByTestId("loans-by-dpd-chart");
    expect(chart.getAttribute("aria-label")).toBe("Loans by DPD bucket");
    expect(chart.getAttribute("role")).toBe("img");
  });

  it("renders an empty state when all bucket counts are zero", () => {
    const { getByText, queryByTestId } = renderWithProviders(
      <LoansByDpdBucketCard
        buckets={[
          { bucket: "B0", count: 0 },
          { bucket: "B1_30", count: 0 },
          { bucket: "B31_60", count: 0 },
          { bucket: "B61_90", count: 0 },
          { bucket: "B90_PLUS", count: 0 },
        ]}
      />,
    );
    expect(getByText("No loans in DPD buckets")).toBeInTheDocument();
    expect(queryByTestId("loans-by-dpd-chart")).toBeNull();
  });

  it("renders an empty state when buckets is empty", () => {
    const { getByText } = renderWithProviders(<LoansByDpdBucketCard buckets={[]} />);
    expect(getByText("No loans in DPD buckets")).toBeInTheDocument();
  });

  it("renders the shared chart skeleton while loading", () => {
    const { container, queryByTestId } = renderWithProviders(
      <LoansByDpdBucketCard buckets={[]} isLoading />,
    );
    expect(container.querySelector('[data-slot="chart-skeleton"]')).toBeInTheDocument();
    expect(queryByTestId("loans-by-dpd-chart")).toBeNull();
  });

  it("suppresses animation under prefers-reduced-motion", async () => {
    stubMotionPreference(true);
    const { findByTestId } = renderWithProviders(<LoansByDpdBucketCard buckets={BUCKETS} />);
    const chart = await findByTestId("loans-by-dpd-chart");
    expect(chart.getAttribute("data-reduced-motion")).toBe("true");
  });

  it("forwards className to the card", () => {
    const { container } = renderWithProviders(
      <LoansByDpdBucketCard buckets={BUCKETS} className="extra-class" />,
    );
    const card = container.querySelector('[data-slot="loans-by-dpd"]');
    expect(card).not.toBeNull();
    expect(card!.className).toContain("extra-class");
  });

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(<LoansByDpdBucketCard buckets={BUCKETS} />);
    expect(await axe(container)).toHaveNoViolations();
  });

  it("has no axe violations when empty", async () => {
    const { container } = renderWithProviders(<LoansByDpdBucketCard buckets={[]} />);
    expect(await axe(container)).toHaveNoViolations();
  });
});
