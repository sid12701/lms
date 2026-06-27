import { describe, it, expect, vi } from "vitest";
import { useState } from "react";
import userEvent from "@testing-library/user-event";
import { axe } from "vitest-axe";
import type { ColumnDef, SortingState } from "@tanstack/react-table";
import { renderWithProviders } from "@/test/utils";
import { DensityProvider } from "@/app/providers";
import { DataTable, type DataTableState, type DataTableStateChange } from "./DataTable";
import { DataTableColumnHeader } from "./DataTableColumnHeader";

interface Row {
  id: string;
  name: string;
  amount: number;
}

const rows: Row[] = [
  { id: "a", name: "Alpha", amount: 100 },
  { id: "b", name: "Beta", amount: 250 },
  { id: "c", name: "Gamma", amount: 75 },
];

const columns: ColumnDef<Row>[] = [
  {
    accessorKey: "name",
    header: ({ column }) => <DataTableColumnHeader column={column} title="Name" />,
    meta: { label: "Name" },
  },
  {
    accessorKey: "amount",
    header: ({ column }) => <DataTableColumnHeader column={column} title="Amount" numeric />,
    meta: { label: "Amount", numeric: true },
  },
];

function withDensity(node: React.ReactNode) {
  return <DensityProvider>{node}</DensityProvider>;
}

function ControlledHarness({
  onChange,
  initial,
}: {
  onChange?: (state: DataTableStateChange) => void;
  initial?: DataTableState;
}) {
  const [state, setState] = useState<DataTableState>(
    initial ?? { sorting: [], columnFilters: [], columnVisibility: {} },
  );
  return (
    <DataTable
      columns={columns}
      data={rows}
      density="comfortable"
      rowIdKey="id"
      ariaLabel="Test table"
      state={state}
      onStateChange={(next) => {
        setState((prev) => ({ ...prev, ...next }));
        onChange?.(next);
      }}
    />
  );
}

describe("DataTable", () => {
  it("renders header cells and one row per data item", () => {
    const { getByRole, getAllByRole } = renderWithProviders(
      withDensity(
        <DataTable columns={columns} data={rows} density="comfortable" ariaLabel="Test table" />,
      ),
    );
    expect(getByRole("table", { name: "Test table" })).toBeInTheDocument();
    // 1 header row + 3 data rows
    expect(getAllByRole("row")).toHaveLength(1 + rows.length);
  });

  it("shows the empty state when data is empty", () => {
    const { getByText } = renderWithProviders(
      withDensity(
        <DataTable columns={columns} data={[]} density="comfortable" empty="Nothing to show" />,
      ),
    );
    expect(getByText("Nothing to show")).toBeInTheDocument();
  });

  it("renders skeleton rows while loading", () => {
    const { container } = renderWithProviders(
      withDensity(
        <DataTable columns={columns} data={rows} density="comfortable" loading skeletonRows={4} />,
      ),
    );
    const skeletons = container.querySelectorAll('[data-slot="skeleton"]');
    // 4 rows * 2 visible columns = 8 skeleton elements
    expect(skeletons.length).toBe(8);
  });

  it("clicking the header toggles sorting state", async () => {
    const onChange = vi.fn<(s: DataTableStateChange) => void>();
    const { getAllByRole } = renderWithProviders(
      withDensity(<ControlledHarness onChange={onChange} />),
    );
    // First sortable column header button (Name)
    const headerBtn = getAllByRole("button", { name: /sort/i })[0]!;
    await userEvent.click(headerBtn);
    const sortCall = onChange.mock.calls.find((call) => call[0]?.sorting !== undefined)?.[0];
    expect(sortCall?.sorting).toBeDefined();
    expect((sortCall?.sorting as SortingState)[0]?.id).toBe("name");
  });

  it("fires getRowAction on Enter when row is interactive", async () => {
    const onAction = vi.fn();
    const { getAllByRole } = renderWithProviders(
      withDensity(
        <DataTable
          columns={columns}
          data={rows}
          density="comfortable"
          rowIdKey="id"
          ariaLabel="Test table"
          getRowAction={(row) => onAction(row.original.id)}
        />,
      ),
    );
    // first interactive row (rows are role="button")
    const firstRow = getAllByRole("button").find((el) => el.tagName === "TR");
    expect(firstRow).toBeDefined();
    firstRow!.focus();
    await userEvent.keyboard("{Enter}");
    expect(onAction).toHaveBeenCalledWith("a");
  });

  it("applies tabular numeric attribute on numeric cells", () => {
    const { container } = renderWithProviders(
      withDensity(
        <DataTable columns={columns} data={rows} density="comfortable" ariaLabel="Test table" />,
      ),
    );
    expect(container.querySelectorAll("td[data-tabular]").length).toBeGreaterThan(0);
  });

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(
      withDensity(
        <DataTable columns={columns} data={rows} density="comfortable" ariaLabel="Test table" />,
      ),
    );
    expect(await axe(container)).toHaveNoViolations();
  });

  it("renders mobile cards when the viewport is narrow", () => {
    Object.defineProperty(window, "matchMedia", {
      configurable: true,
      writable: true,
      value: (query: string) => ({
        matches: query.includes("max-width"),
        media: query,
        addEventListener: () => {},
        removeEventListener: () => {},
      }),
    });

    const mobileColumns: ColumnDef<Row>[] = [
      {
        accessorKey: "name",
        header: "Name",
        meta: { label: "Name", mobileCard: "primary" },
      },
      {
        accessorKey: "amount",
        header: "Amount",
        meta: { label: "Amount", numeric: true, mobileCard: "secondary" },
      },
    ];

    const { container } = renderWithProviders(
      withDensity(
        <DataTable
          columns={mobileColumns}
          data={rows}
          density="comfortable"
          ariaLabel="Mobile table"
        />,
      ),
    );

    expect(container.querySelector('[data-slot="data-table-mobile-cards"]')).not.toBeNull();
    expect(container.querySelector("table")).toBeNull();
    expect(container.textContent).toMatch(/Alpha/);
    expect(container.textContent).toMatch(/Amount/);
  });
});
