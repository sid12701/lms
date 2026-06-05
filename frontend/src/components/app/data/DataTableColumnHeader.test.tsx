import { describe, it, expect } from "vitest";
import userEvent from "@testing-library/user-event";
import { axe } from "vitest-axe";
import type { ColumnDef } from "@tanstack/react-table";
import { renderWithProviders } from "@/test/utils";
import { DensityProvider } from "@/app/providers";
import { DataTable } from "./DataTable";
import { DataTableColumnHeader } from "./DataTableColumnHeader";

interface Row {
  id: string;
  label: string;
}

const data: Row[] = [
  { id: "1", label: "first" },
  { id: "2", label: "second" },
];

describe("DataTableColumnHeader", () => {
  it("renders sortable header as a button inside a th with aria-sort=none initially", () => {
    const columns: ColumnDef<Row>[] = [
      {
        accessorKey: "label",
        header: ({ column }) => <DataTableColumnHeader column={column} title="Label" />,
      },
    ];
    const { getByRole, container } = renderWithProviders(
      <DensityProvider>
        <DataTable columns={columns} data={data} density="comfortable" ariaLabel="t" />
      </DensityProvider>,
    );
    expect(getByRole("button", { name: /sort/i })).toBeInTheDocument();
    const th = container.querySelector("th");
    expect(th?.getAttribute("aria-sort")).toBe("none");
  });

  it("toggles aria-sort on the th after click", async () => {
    const columns: ColumnDef<Row>[] = [
      {
        accessorKey: "label",
        header: ({ column }) => <DataTableColumnHeader column={column} title="Label" />,
      },
    ];
    const { getByRole, container } = renderWithProviders(
      <DensityProvider>
        <DataTable columns={columns} data={data} density="comfortable" ariaLabel="t" />
      </DensityProvider>,
    );
    const btn = getByRole("button", { name: /sort/i });
    await userEvent.click(btn);
    expect(container.querySelector("th")?.getAttribute("aria-sort")).toBe("ascending");
  });

  it("renders a plain label when sorting is disabled", () => {
    const columns: ColumnDef<Row>[] = [
      {
        accessorKey: "label",
        enableSorting: false,
        header: ({ column }) => <DataTableColumnHeader column={column} title="Label" />,
      },
    ];
    const { container, queryByRole } = renderWithProviders(
      <DensityProvider>
        <DataTable columns={columns} data={data} density="comfortable" ariaLabel="t" />
      </DensityProvider>,
    );
    expect(queryByRole("button", { name: /label/i })).toBeNull();
    expect(container.querySelector('[data-slot="column-header"]')?.textContent).toBe("Label");
  });

  it("has no axe violations", async () => {
    const columns: ColumnDef<Row>[] = [
      {
        accessorKey: "label",
        header: ({ column }) => <DataTableColumnHeader column={column} title="Label" />,
      },
    ];
    const { container } = renderWithProviders(
      <DensityProvider>
        <DataTable columns={columns} data={data} density="comfortable" ariaLabel="t" />
      </DensityProvider>,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
