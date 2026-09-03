import { describe, it, expect } from "vitest";
import { useState } from "react";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { axe } from "vitest-axe";
import {
  getCoreRowModel,
  getPaginationRowModel,
  useReactTable,
  type ColumnDef,
  type PaginationState,
} from "@tanstack/react-table";
import { axeBaseElement, renderWithProviders } from "@/test/utils";
import { DataTablePagination } from "./DataTablePagination";

interface Row {
  id: number;
}

const rows: Row[] = Array.from({ length: 30 }, (_, i) => ({ id: i + 1 }));
const columns: ColumnDef<Row>[] = [{ accessorKey: "id" }];

function Harness({ initialPageSize = 10 }: { initialPageSize?: number }) {
  const [pagination, setPagination] = useState<PaginationState>({
    pageIndex: 0,
    pageSize: initialPageSize,
  });
  const table = useReactTable({
    data: rows,
    columns,
    state: { pagination },
    onPaginationChange: setPagination,
    getCoreRowModel: getCoreRowModel(),
    getPaginationRowModel: getPaginationRowModel(),
  });
  return <DataTablePagination table={table} />;
}

describe("DataTablePagination", () => {
  it("shows the current page range and advances on next-page click", async () => {
    const { getByText, getByLabelText } = renderWithProviders(<Harness />);
    expect(getByText(/Showing 1–10 of 30/)).toBeInTheDocument();
    await userEvent.click(getByLabelText("Go to next page"));
    expect(getByText(/Showing 11–20 of 30/)).toBeInTheDocument();
  });

  it("disables prev/first on the first page", () => {
    const { getByLabelText } = renderWithProviders(<Harness />);
    expect(getByLabelText("Go to previous page")).toBeDisabled();
    expect(getByLabelText("Go to first page")).toBeDisabled();
  });

  it("jumps to the last page", async () => {
    const { getByText, getByLabelText } = renderWithProviders(<Harness />);
    await userEvent.click(getByLabelText("Go to last page"));
    expect(getByText(/Showing 21–30 of 30/)).toBeInTheDocument();
  });

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(<Harness />);
    expect(await axe(container)).toHaveNoViolations();
  });

  it("has no axe violations with the page-size listbox open (portalled content lives on baseElement)", async () => {
    const user = userEvent.setup();
    const { baseElement } = renderWithProviders(<Harness />);
    await user.click(screen.getByRole("combobox", { name: "Rows per page" }));
    await screen.findByRole("listbox");

    expect(await axeBaseElement(baseElement)).toHaveNoViolations();
  });

  it("shows Loading… in the range label when loading", () => {
    function LoadingHarness() {
      const table = useReactTable({
        data: [],
        columns,
        getCoreRowModel: getCoreRowModel(),
      });
      return <DataTablePagination table={table} totalRows={0} loading />;
    }
    const { getByText } = renderWithProviders(<LoadingHarness />);
    expect(getByText("Loading…")).toBeInTheDocument();
  });

  it("supports controlled manual pagination without a table instance", async () => {
    function ControlledHarness() {
      const [pagination, setPagination] = useState<PaginationState>({
        pageIndex: 0,
        pageSize: 10,
      });
      return (
        <DataTablePagination
          pageIndex={pagination.pageIndex}
          pageSize={pagination.pageSize}
          totalRows={30}
          onPaginationChange={setPagination}
        />
      );
    }
    const { getByText, getByLabelText } = renderWithProviders(<ControlledHarness />);
    expect(getByText(/Showing 1–10 of 30/)).toBeInTheDocument();
    await userEvent.click(getByLabelText("Go to next page"));
    expect(getByText(/Showing 11–20 of 30/)).toBeInTheDocument();
  });
});
