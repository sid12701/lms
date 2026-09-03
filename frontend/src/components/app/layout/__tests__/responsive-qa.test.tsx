/**
 * Phase 10 responsive-QA smoke test.
 *
 * Mounts the AppShell at the 1024px (lg) breakpoint — the most likely
 * culprit for horizontal overflow because the right-rail collapses
 * here per locked decision D6 (rail kept at xl ≥ 1280, hidden below).
 *
 * jsdom has no layout engine, so `scrollWidth`/`offsetWidth` are always 0 —
 * there is no way to assert on *actual* overflow here. What we can catch is
 * the anti-pattern that causes it: this codebase styles exclusively with
 * Tailwind utility classes (no inline `style` widths), so a stray arbitrary
 * fixed width — `w-[1300px]` rather than a responsive/`max-w-*` class — on
 * any shell or table container is the concrete, greppable risk. The test
 * scans the rendered class list for that pattern at >= the lg viewport width.
 *
 * The page content is intentionally synthesised (representative
 * KPI strip + DataTable, not a real feature page) so this test stays
 * decoupled from any backend data layer that other agents own. The
 * structural invariant (no fixed-width overflow risk at lg) is independent
 * of which feature renders there.
 */
import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { render } from "@testing-library/react";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { TooltipProvider } from "@/components/ui/tooltip";
import { SessionProvider } from "@/features/auth/session-context";
import { ThemeProvider, DensityProvider } from "@/app/providers";
import { adminSession } from "@/test/session-fixtures";
import { AppShell } from "@/components/app/shell/AppShell";
import { PageHeader } from "@/components/app/layout/PageHeader";
import { KpiStrip } from "@/components/app/layout/KpiStrip";
import { DataTable } from "@/components/app/data/DataTable";
import type { ColumnDef } from "@tanstack/react-table";

const LG_VIEWPORT_PX = 1024;

interface SampleRow {
  id: string;
  application: string;
  borrower: string;
  amount: number;
  status: string;
}

const SAMPLE_ROWS: SampleRow[] = Array.from({ length: 12 }, (_, i) => ({
  id: `row-${i}`,
  application: `BC-AP-${String(20000 + i).padStart(6, "0")}`,
  borrower: `Sample Borrower ${i + 1}`,
  amount: 100_000 + i * 12_345,
  status: i % 2 === 0 ? "DISBURSED" : "AWAITING_APPROVAL",
}));

const COLUMNS: ColumnDef<SampleRow>[] = [
  { accessorKey: "application", header: "Application", meta: { label: "Application" } },
  { accessorKey: "borrower", header: "Borrower", meta: { label: "Borrower" } },
  { accessorKey: "amount", header: "Amount", meta: { label: "Amount", numeric: true } },
  { accessorKey: "status", header: "Status", meta: { label: "Status" } },
];

function SyntheticDashboard() {
  return (
    <div className="flex flex-col gap-6 p-6">
      <PageHeader
        eyebrow="Workspace"
        title="Dashboard"
        description="Responsive QA mount — KPI strip + table at 1024px."
      />
      <KpiStrip>
        <div className="border-border bg-surface rounded-md border p-4">
          <div className="text-foreground-muted text-xs uppercase">Open loans</div>
          <div className="text-foreground mt-1 text-2xl font-semibold">1,248</div>
        </div>
        <div className="border-border bg-surface rounded-md border p-4">
          <div className="text-foreground-muted text-xs uppercase">Disbursed</div>
          <div className="text-foreground mt-1 text-2xl font-semibold">₹45.2 Cr</div>
        </div>
        <div className="border-border bg-surface rounded-md border p-4">
          <div className="text-foreground-muted text-xs uppercase">DPD</div>
          <div className="text-foreground mt-1 text-2xl font-semibold">3.42%</div>
        </div>
        <div className="border-border bg-surface rounded-md border p-4">
          <div className="text-foreground-muted text-xs uppercase">Documents</div>
          <div className="text-foreground mt-1 text-2xl font-semibold">97.5%</div>
        </div>
      </KpiStrip>
      <DataTable
        columns={COLUMNS}
        data={SAMPLE_ROWS}
        density="comfortable"
        ariaLabel="Sample dashboard table"
      />
    </div>
  );
}

function createQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

describe("Responsive QA — AppShell at lg (1024px)", () => {
  let originalInnerWidth: number;
  let originalClientWidth: number;

  beforeAll(() => {
    originalInnerWidth = window.innerWidth;
    // jsdom defaults `documentElement.clientWidth` to 0 — pin it to the
    // viewport so the assertion's denominator is meaningful.
    const desc = Object.getOwnPropertyDescriptor(Element.prototype, "clientWidth");
    originalClientWidth = desc ? Number(desc.value ?? 0) : 0;
    Object.defineProperty(window, "innerWidth", {
      configurable: true,
      value: LG_VIEWPORT_PX,
    });
    Object.defineProperty(document.documentElement, "clientWidth", {
      configurable: true,
      get: () => LG_VIEWPORT_PX,
    });
  });

  afterAll(() => {
    Object.defineProperty(window, "innerWidth", {
      configurable: true,
      value: originalInnerWidth,
    });
    // Best-effort restore — if the descriptor wasn't there originally we
    // leave a getter on the prototype because cleaning it up cleanly is
    // unnecessary for downstream tests (jsdom's default is also 0).
    void originalClientWidth;
  });

  it("renders the shell + dashboard with no fixed-width class wider than the lg viewport", () => {
    const { container } = render(
      <QueryClientProvider client={createQueryClient()}>
        <MemoryRouter initialEntries={["/home"]}>
          <ThemeProvider>
            <DensityProvider>
              <SessionProvider skipBootstrap initialSession={adminSession}>
                <TooltipProvider delayDuration={0}>
                  <Routes>
                    <Route
                      path="/home"
                      element={
                        <AppShell>
                          <SyntheticDashboard />
                        </AppShell>
                      }
                    />
                  </Routes>
                </TooltipProvider>
              </SessionProvider>
            </DensityProvider>
          </ThemeProvider>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    // The shell mounts and renders the synthetic dashboard table.
    expect(container.querySelector('[data-slot="top-bar"]')).not.toBeNull();
    const tables = container.querySelectorAll("table");
    expect(tables.length).toBeGreaterThan(0);

    // The structural invariant: no descendant carries a fixed-width Tailwind
    // arbitrary-value class (`w-[Npx]`, optionally behind a breakpoint
    // variant like `lg:w-[Npx]`) at or above the lg viewport. Unlike
    // `min-w-*`/`max-w-*`, `w-[Npx]` pins an intrinsic width regardless of
    // container size, so one at >= 1024px on a shell/table container would
    // force horizontal overflow the moment it renders at this breakpoint.
    const root = container.firstElementChild as HTMLElement | null;
    expect(root).not.toBeNull();
    if (root) {
      const FIXED_WIDTH_CLASS = /(?:^|:)w-\[(\d+)px\]$/;
      const offendingClasses = Array.from(root.querySelectorAll<HTMLElement>("*")).flatMap((el) => {
        const classes = (el.getAttribute("class") ?? "").split(/\s+/).filter(Boolean);
        return classes.filter((token) => {
          const match = FIXED_WIDTH_CLASS.exec(token);
          return match !== undefined && match !== null && Number(match[1]) >= LG_VIEWPORT_PX;
        });
      });
      expect(offendingClasses).toHaveLength(0);
    }
  });
});
