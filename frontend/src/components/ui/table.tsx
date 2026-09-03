import * as React from "react";

import { cn } from "@/lib/utils";

/**
 * True while the element can actually scroll in either axis.
 *
 * A bounded scroll container that holds no focusable content is unreachable by
 * keyboard — `/reports` and the repayments ledger both scrolled ~430px of rows
 * that a keyboard user could not move at all, because their tables are pure data
 * with no row action to tab to. Making the container focusable is the fix, but
 * only while it genuinely scrolls: an unconditional `tabIndex` would add a dead
 * tab stop to every short table in the app.
 */
function useScrollable(ref: React.RefObject<HTMLElement | null>): boolean {
  const [scrollable, setScrollable] = React.useState(false);

  React.useEffect(() => {
    const el = ref.current;
    if (!el || typeof ResizeObserver === "undefined") return;

    const measure = () =>
      setScrollable(el.scrollHeight > el.clientHeight + 1 || el.scrollWidth > el.clientWidth + 1);

    measure();
    // Observe the inner table too: adding rows grows the content without
    // resizing the container's own border box.
    const observer = new ResizeObserver(measure);
    observer.observe(el);
    const table = el.firstElementChild;
    if (table) observer.observe(table);
    return () => observer.disconnect();
  }, [ref]);

  return scrollable;
}

function Table({
  className,
  containerClassName,
  ...props
}: React.ComponentProps<"table"> & {
  /**
   * Classes for the scroll container that wraps the `<table>`.
   *
   * Needed because `position: sticky` resolves against the nearest scrolling
   * ancestor. A sticky `thead` inside this container can only pin if *this*
   * element is the scroller and has a bounded height — see `DataTable`.
   */
  containerClassName?: string;
}) {
  const containerRef = React.useRef<HTMLDivElement>(null);
  const scrollable = useScrollable(containerRef);

  return (
    <div
      ref={containerRef}
      data-slot="table-container"
      // Named via the table's own label so the region announces as something
      // more useful than "scrollable region".
      role={scrollable ? "region" : undefined}
      aria-label={scrollable ? props["aria-label"] : undefined}
      tabIndex={scrollable ? 0 : undefined}
      className={cn(
        "relative w-full overflow-x-auto",
        scrollable && "focus-visible:ring-ring rounded-container outline-none focus-visible:ring-2",
        containerClassName,
      )}
    >
      <table
        data-slot="table"
        className={cn("w-full caption-bottom text-xs", className)}
        {...props}
      />
    </div>
  );
}

function TableHeader({ className, ...props }: React.ComponentProps<"thead">) {
  return <thead data-slot="table-header" className={cn("[&_tr]:border-b", className)} {...props} />;
}

function TableBody({ className, ...props }: React.ComponentProps<"tbody">) {
  return (
    <tbody
      data-slot="table-body"
      className={cn("[&_tr:last-child]:border-0", className)}
      {...props}
    />
  );
}

function TableFooter({ className, ...props }: React.ComponentProps<"tfoot">) {
  return (
    <tfoot
      data-slot="table-footer"
      className={cn("bg-muted/50 border-t font-medium [&>tr]:last:border-b-0", className)}
      {...props}
    />
  );
}

function TableRow({ className, ...props }: React.ComponentProps<"tr">) {
  return (
    <tr
      data-slot="table-row"
      className={cn(
        "hover:bg-muted/50 has-aria-expanded:bg-muted/50 data-[state=selected]:bg-muted border-b transition-colors",
        className,
      )}
      {...props}
    />
  );
}

function TableHead({ className, ...props }: React.ComponentProps<"th">) {
  return (
    <th
      data-slot="table-head"
      className={cn(
        "text-foreground h-10 px-2 text-left align-middle font-medium whitespace-nowrap [&:has([role=checkbox])]:pr-0",
        className,
      )}
      {...props}
    />
  );
}

function TableCell({ className, ...props }: React.ComponentProps<"td">) {
  return (
    <td
      data-slot="table-cell"
      className={cn("p-2 align-middle whitespace-nowrap [&:has([role=checkbox])]:pr-0", className)}
      {...props}
    />
  );
}

function TableCaption({ className, ...props }: React.ComponentProps<"caption">) {
  return (
    <caption
      data-slot="table-caption"
      className={cn("text-muted-foreground mt-4 text-xs", className)}
      {...props}
    />
  );
}

export { Table, TableHeader, TableBody, TableFooter, TableHead, TableRow, TableCell, TableCaption };
