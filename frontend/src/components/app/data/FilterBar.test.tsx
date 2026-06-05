import { describe, it, expect, vi } from "vitest";
import { MemoryRouter, useLocation } from "react-router-dom";
import userEvent from "@testing-library/user-event";
import { axe } from "vitest-axe";
import { z } from "zod";
import { renderWithProviders } from "@/test/utils";
import { FilterBar } from "./FilterBar";
import { Button } from "@/components/ui/button";

const schema = z.object({
  status: z.enum(["A", "B"]).optional(),
  search: z.string().optional(),
});

function LocationSpy({ onChange }: { onChange: (search: string) => void }) {
  const loc = useLocation();
  onChange(loc.search);
  return null;
}

describe("FilterBar", () => {
  it("renders children with the current filter snapshot", () => {
    const { getByText } = renderWithProviders(
      <MemoryRouter initialEntries={["/?status=A"]}>
        <FilterBar schema={schema}>
          {({ filters }) => <span>active:{filters.status ?? "none"}</span>}
        </FilterBar>
      </MemoryRouter>,
    );
    expect(getByText(/active:A/)).toBeInTheDocument();
  });

  it("clears all filters and updates the URL on Clear click", async () => {
    const onClear = vi.fn();
    let lastSearch = "";
    const { getByRole } = renderWithProviders(
      <MemoryRouter initialEntries={["/?status=A&search=hello"]}>
        <FilterBar schema={schema} onClear={onClear}>
          {() => <span>filters</span>}
        </FilterBar>
        <LocationSpy onChange={(s) => (lastSearch = s)} />
      </MemoryRouter>,
    );
    const clear = getByRole("button", { name: /clear all filters/i });
    expect(clear).not.toBeDisabled();
    await userEvent.click(clear);
    expect(onClear).toHaveBeenCalledTimes(1);
    expect(lastSearch).toBe("");
  });

  it("disables Clear when no filters are active", () => {
    const { getByRole } = renderWithProviders(
      <MemoryRouter initialEntries={["/"]}>
        <FilterBar schema={schema}>{() => <Button type="button">x</Button>}</FilterBar>
      </MemoryRouter>,
    );
    expect(getByRole("button", { name: /clear all filters/i })).toBeDisabled();
  });

  it("setFilters writes the value back to the URL", async () => {
    let lastSearch = "";
    const { getByRole } = renderWithProviders(
      <MemoryRouter initialEntries={["/"]}>
        <FilterBar schema={schema}>
          {({ setFilters }) => (
            <Button type="button" onClick={() => setFilters({ status: "B" })}>
              set-b
            </Button>
          )}
        </FilterBar>
        <LocationSpy onChange={(s) => (lastSearch = s)} />
      </MemoryRouter>,
    );
    await userEvent.click(getByRole("button", { name: "set-b" }));
    expect(lastSearch).toContain("status=B");
  });

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(
      <MemoryRouter initialEntries={["/"]}>
        <FilterBar schema={schema} label="Filters">
          {() => <Button type="button">child</Button>}
        </FilterBar>
      </MemoryRouter>,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
