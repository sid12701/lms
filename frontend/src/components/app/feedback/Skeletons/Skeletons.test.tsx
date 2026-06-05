import { describe, it, expect } from "vitest";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { CardSkeleton, ChartSkeleton, FormSkeleton, KpiSkeleton, TableSkeleton } from "./index";

describe("Skeletons", () => {
  it("KpiSkeleton renders the requested count of placeholders", () => {
    const { container } = renderWithProviders(<KpiSkeleton count={3} />);
    const root = container.querySelector('[data-slot="kpi-skeleton"]');
    expect(root).toBeInTheDocument();
    expect(root!.children.length).toBe(3);
  });

  it("TableSkeleton renders rows × cols placeholders + a header", () => {
    const { container } = renderWithProviders(<TableSkeleton rows={2} cols={3} />);
    const rows = container.querySelectorAll('[data-slot="table-skeleton-row"]');
    const header = container.querySelector('[data-slot="table-skeleton-header"]');
    expect(rows.length).toBe(2);
    expect(header).toBeInTheDocument();
  });

  it("CardSkeleton honours `lines` prop", () => {
    const { container } = renderWithProviders(<CardSkeleton lines={4} />);
    expect(container.querySelector('[data-slot="card-skeleton"]')).toBeInTheDocument();
  });

  it("ChartSkeleton renders a fixed chart placeholder", () => {
    const { container } = renderWithProviders(<ChartSkeleton bars={5} />);
    const root = container.querySelector('[data-slot="chart-skeleton"]');
    expect(root).toBeInTheDocument();
    expect(root!.querySelectorAll('[data-slot="skeleton"]').length).toBe(7);
  });

  it("FormSkeleton honours `fields` prop", () => {
    const { container } = renderWithProviders(<FormSkeleton fields={2} />);
    expect(container.querySelector('[data-slot="form-skeleton"]')).toBeInTheDocument();
  });

  it("forwards className on each", () => {
    const { container } = renderWithProviders(
      <div>
        <KpiSkeleton className="kpi-x" />
        <TableSkeleton className="tbl-x" />
        <CardSkeleton className="card-x" />
        <ChartSkeleton className="chart-x" />
        <FormSkeleton className="form-x" />
      </div>,
    );
    expect(container.querySelector(".kpi-x")).toBeInTheDocument();
    expect(container.querySelector(".tbl-x")).toBeInTheDocument();
    expect(container.querySelector(".card-x")).toBeInTheDocument();
    expect(container.querySelector(".chart-x")).toBeInTheDocument();
    expect(container.querySelector(".form-x")).toBeInTheDocument();
  });

  it("flag pending state via data-pending", () => {
    const { container } = renderWithProviders(<KpiSkeleton />);
    expect(
      container.querySelector('[data-slot="kpi-skeleton"]')!.getAttribute("data-pending"),
    ).toBe("true");
  });

  it("has no axe violations across all skeletons", async () => {
    const { container } = renderWithProviders(
      <div>
        <KpiSkeleton />
        <TableSkeleton />
        <CardSkeleton />
        <ChartSkeleton />
        <FormSkeleton />
      </div>,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
