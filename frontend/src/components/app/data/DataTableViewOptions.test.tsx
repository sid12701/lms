import { describe, it, expect } from "vitest";
import { useState } from "react";
import userEvent from "@testing-library/user-event";
import { axe } from "vitest-axe";
import type { ColumnDef, VisibilityState } from "@tanstack/react-table";
import { useReactTable, getCoreRowModel } from "@tanstack/react-table";
import { renderWithProviders } from "@/test/utils";
import { DataTableViewOptions } from "./DataTableViewOptions";

interface Row {
  id: string;
  name: string;
  amount: number;
}

const data: Row[] = [{ id: "x", name: "X", amount: 1 }];

const columns: ColumnDef<Row>[] = [
  { accessorKey: "name", meta: { label: "Name" }, enableHiding: true },
  { accessorKey: "amount", meta: { label: "Amount" }, enableHiding: true },
];

function Harness() {
  const [columnVisibility, setColumnVisibility] = useState<VisibilityState>({});
  const table = useReactTable({
    data,
    columns,
    state: { columnVisibility },
    onColumnVisibilityChange: setColumnVisibility,
    getCoreRowModel: getCoreRowModel(),
  });
  return <DataTableViewOptions table={table} />;
}

describe("DataTableViewOptions", () => {
  it("opens a popover and toggles a column off", async () => {
    const { getByRole, findByRole } = renderWithProviders(<Harness />);
    await userEvent.click(getByRole("button", { name: "Columns" }));
    const nameSwitch = await findByRole("switch", { name: /name/i });
    expect(nameSwitch.getAttribute("aria-checked")).toBe("true");
    await userEvent.click(nameSwitch);
    expect(nameSwitch.getAttribute("aria-checked")).toBe("false");
  });

  it("has no axe violations on the trigger surface", async () => {
    const { container } = renderWithProviders(<Harness />);
    expect(await axe(container)).toHaveNoViolations();
  });
});
