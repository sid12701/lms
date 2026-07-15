import { ArrowDown, ArrowUp, ChevronsUpDown } from "lucide-react";
import type { Column } from "@tanstack/react-table";
import type { ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

export interface DataTableColumnHeaderProps<TData, TValue> {
  /** TanStack column instance. */
  column: Column<TData, TValue>;
  /** Visible label for the header cell. */
  title: ReactNode;
  /** Numeric columns get tabular alignment + right-align. */
  numeric?: boolean;
  className?: string;
}

/**
 * Header cell for sortable TanStack columns. ARIA-correct (`aria-sort`),
 * keyboard-accessible via the underlying shadcn `Button`. When the column
 * does not opt into sorting (`canSort()` is false) it renders a plain label
 * without the toggle affordance.
 */
export function DataTableColumnHeader<TData, TValue>({
  column,
  title,
  numeric = false,
  className,
}: DataTableColumnHeaderProps<TData, TValue>) {
  const canSort = column.getCanSort();
  const sorted = column.getIsSorted(); // false | "asc" | "desc"

  if (!canSort) {
    return (
      <span
        data-slot="column-header"
        className={cn(
          "text-foreground-muted text-xs font-medium tracking-wide uppercase",
          numeric && "block w-full text-right",
          className,
        )}
      >
        {title}
      </span>
    );
  }

  const SortIcon = sorted === "asc" ? ArrowUp : sorted === "desc" ? ArrowDown : ChevronsUpDown;
  // Supplementary sr-only text — never an aria-label, which would replace the
  // column name and leave screen-reader users with identical "Not sorted"
  // buttons across the header row.
  const sortHint =
    sorted === "asc"
      ? "Sorted ascending. Activate to sort descending."
      : sorted === "desc"
        ? "Sorted descending. Activate to clear sort."
        : "Not sorted. Activate to sort ascending.";

  return (
    <Button
      type="button"
      variant="ghost"
      size="xs"
      data-slot="column-header"
      data-sorted={sorted || undefined}
      onClick={() => column.toggleSorting(sorted === "asc")}
      className={cn(
        "text-foreground-muted hover:text-foreground -ml-2 h-7 gap-1.5 px-2 text-xs font-medium tracking-wide uppercase",
        numeric && "ml-0 w-full justify-end",
        className,
      )}
    >
      <span>{title}</span>
      <span className="sr-only">{sortHint}</span>
      <SortIcon
        aria-hidden="true"
        className={cn("size-3", sorted ? "text-foreground" : "text-foreground-subtle")}
      />
    </Button>
  );
}
