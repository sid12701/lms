/**
 * LoanApplicationsFilterBar tests — verifies search debouncing, status
 * multi-select, single-select dropdowns, the applied-filter row, and the
 * URL round-trip behaviour driven by `useUrlFilters`.
 *
 * The clear-all assertions changed shape with Structure A. The bar used to
 * carry a permanently-mounted "Clear all filters" button that was merely
 * *disabled* when nothing was set; applied state now lives in a chip row that
 * does not exist until a filter is applied, so "nothing is set" is asserted by
 * the row's absence rather than by a disabled control.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { axe } from "vitest-axe";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Routes, Route, useSearchParams } from "react-router-dom";
import { useEffect, type ReactNode } from "react";
import { renderWithProviders } from "@/test/utils";
import { pickDateInField } from "@/test/date-picker";
import { LoanApplicationsFilterBar } from "./LoanApplicationsFilterBar";

function SearchSpy({ onChange }: { onChange: (search: string) => void }) {
  const [searchParams] = useSearchParams();
  const search = searchParams.toString();
  useEffect(() => {
    onChange(search);
  }, [onChange, search]);
  return null;
}

function renderBar(opts?: {
  initialPath?: string;
  lspOptions?: readonly { value: string; label: string }[];
  productOptions?: readonly { value: string; label: string }[];
  resultCount?: number;
  onSearch?: (q: string) => void;
}): { container: HTMLElement; latestSearch: () => string } {
  let latest = "";
  const onChange = (s: string) => {
    latest = s;
    opts?.onSearch?.(s);
  };

  const ui: ReactNode = (
    <MemoryRouter initialEntries={[opts?.initialPath ?? "/loan-applications"]}>
      <Routes>
        <Route
          path="/loan-applications"
          element={
            <>
              <LoanApplicationsFilterBar
                lspOptions={opts?.lspOptions}
                productOptions={opts?.productOptions}
                resultCount={opts?.resultCount}
              />
              <SearchSpy onChange={onChange} />
            </>
          }
        />
      </Routes>
    </MemoryRouter>
  );

  const { container } = renderWithProviders(ui);
  return { container, latestSearch: () => latest };
}

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
  vi.clearAllMocks();
});

describe("LoanApplicationsFilterBar", () => {
  it("renders loan id, LSP, status, product, and disbursal filters", () => {
    vi.useRealTimers();
    renderBar();
    expect(
      screen.getByRole("searchbox", { name: /Search loan applications/i }),
    ).toBeInTheDocument();
    expect(screen.getByLabelText(/LSP loan ID/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/Bhawana loan ID/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/Disbursed from/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/Disbursed to/i)).toBeInTheDocument();
    expect(screen.getByLabelText("Status filter")).toBeInTheDocument();
    expect(screen.getByLabelText("LSP filter")).toBeInTheDocument();
    expect(screen.getByLabelText("Product filter")).toBeInTheDocument();
  });

  it("shows no applied-filter row when nothing is set", () => {
    vi.useRealTimers();
    const { container } = renderBar();
    expect(container.querySelector('[data-slot="loan-applications-applied-filters"]')).toBeNull();
    expect(screen.queryByRole("button", { name: /Clear all/i })).not.toBeInTheDocument();
  });

  it("marks a set control as set, and leaves an unset one alone", async () => {
    vi.useRealTimers();
    renderBar({ initialPath: "/loan-applications?status=INITIALIZED" });
    // The core P0: a set control and an unset one used to render identical
    // colour, fill, border and weight.
    expect(screen.getByLabelText("Status filter")).toHaveAttribute("data-filter-set", "true");
    expect(screen.getByLabelText("LSP filter")).not.toHaveAttribute("data-filter-set");
  });

  it("names each applied filter in a chip and clears just that one", async () => {
    vi.useRealTimers();
    const user = userEvent.setup();
    // A real uuid: `lspId` is `z.string().uuid()`, so a placeholder id is
    // dropped at parse time and the filter would never register as set.
    const lspId = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
    const { latestSearch } = renderBar({
      initialPath: `/loan-applications?status=INITIALIZED&lspId=${lspId}`,
      lspOptions: [{ value: lspId, label: "Acme NBFC" }],
    });

    // Chips resolve ids to the label the operator picked, not the raw uuid.
    // Both the trigger and the chip name it, so scope to the applied row.
    const applied = screen.getByRole("button", { name: /Remove LSP filter/i }).closest("span");
    expect(applied).toHaveTextContent("Acme NBFC");

    await user.click(screen.getByRole("button", { name: /Remove LSP filter/i }));

    await waitFor(() => {
      const s = latestSearch();
      expect(s).not.toContain("lspId=");
      // The other filter survives — this is a per-filter clear, not clear-all.
      expect(s).toContain("status=INITIALIZED");
    });
  }, 15000);

  it("reports how many rows the current filter set matches", () => {
    vi.useRealTimers();
    renderBar({ initialPath: "/loan-applications?status=INITIALIZED", resultCount: 24 });
    expect(screen.getByText(/24 applications/)).toBeInTheDocument();
  });

  it("debounces search input into the URL after 200ms", async () => {
    // Use real timers + waitFor — fake timers + React 19's scheduler don't
    // tick `waitFor` cleanly when react-router's setSearchParams batches.
    vi.useRealTimers();
    const user = userEvent.setup();
    const { latestSearch } = renderBar();
    const input = screen.getByRole("searchbox", {
      name: /Search loan applications/i,
    });
    await user.type(input, "abc");
    await waitFor(
      () => {
        expect(latestSearch()).toContain("q=abc");
      },
      { timeout: 1500 },
    );
  }, 10_000);

  it("hydrates the search input from the URL", () => {
    vi.useRealTimers();
    renderBar({ initialPath: "/loan-applications?q=hello" });
    const input = screen.getByRole("searchbox", {
      name: /Search loan applications/i,
    }) as HTMLInputElement;
    expect(input.value).toBe("hello");
  });

  // Single-select on purpose: the ops list endpoint accepts one `status`
  // param (audit F5) — the dropdown mirrors that contract.
  it("opens the status dropdown and exposes options", async () => {
    vi.useRealTimers();
    const user = userEvent.setup();
    renderBar();
    const statusTrigger = screen.getByLabelText("Status filter");
    await user.click(statusTrigger);
    const opt = await screen.findByRole("option", { name: /Initialized/i });
    expect(opt).toBeInTheDocument();
  }, 15000);

  it("surfaces the applied-filter row once a filter is set", async () => {
    vi.useRealTimers();
    const { container } = renderBar({ initialPath: "/loan-applications?q=demo" });
    expect(
      container.querySelector('[data-slot="loan-applications-applied-filters"]'),
    ).not.toBeNull();
    expect(screen.getByRole("button", { name: /Clear all/i })).toBeEnabled();
  });

  it("clears all filters when Clear all is clicked", async () => {
    vi.useRealTimers();
    const user = userEvent.setup();
    const { latestSearch } = renderBar({
      initialPath: "/loan-applications?q=demo&status=INITIALIZED",
    });
    expect(latestSearch()).toContain("q=demo");
    const clear = screen.getByRole("button", { name: /Clear all/i });
    await user.click(clear);
    // After Clear, the bar may keep a `page=0` reset marker; what matters
    // is that the value-bearing filters are gone.
    await waitFor(() => {
      const s = latestSearch();
      expect(s).not.toContain("q=");
      expect(s).not.toContain("status=");
    });
  });

  it("emits a single-select value into the URL when an LSP is chosen", async () => {
    vi.useRealTimers();
    const user = userEvent.setup();
    const { latestSearch } = renderBar({
      lspOptions: [
        { value: "lsp-1", label: "Acme NBFC" },
        { value: "lsp-2", label: "Beta Capital" },
      ],
    });
    await user.click(screen.getByLabelText("LSP filter"));
    const option = await screen.findByRole("option", { name: /Acme NBFC/i });
    await user.click(option);
    await waitFor(() => {
      expect(latestSearch()).toContain("lspId=lsp-1");
    });
  }, 15000);

  it("emits separate loan ID and disbursal date filters into the URL", async () => {
    vi.useRealTimers();
    const user = userEvent.setup();
    const { latestSearch } = renderBar();

    await user.type(screen.getByLabelText(/LSP loan ID/i), "LSP-9001");
    await user.type(screen.getByLabelText(/Bhawana loan ID/i), "LMS-LN-9001");
    await pickDateInField(user, /Disbursed from/i, "2026-04-01");
    await pickDateInField(user, /Disbursed to/i, "2026-04-30");

    await waitFor(() => {
      const s = latestSearch();
      expect(s).toContain("lspLoanId=LSP-9001");
      expect(s).toContain("bhawLoanId=LMS-LN-9001");
      expect(s).toContain("disbursalDateFrom=2026-04-01");
      expect(s).toContain("disbursalDateTo=2026-04-30");
    });
  }, 15000);

  it("has no axe violations", async () => {
    vi.useRealTimers();
    const { container } = renderBar();
    expect(await axe(container)).toHaveNoViolations();
  }, 15000);
});
