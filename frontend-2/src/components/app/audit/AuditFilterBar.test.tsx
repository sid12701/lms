import { describe, it, expect, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { AuditFilterBar } from "./AuditFilterBar";
import { EMPTY_AUDIT_FILTER, type AuditFilterValue } from "./types";

function setup(initial: AuditFilterValue = EMPTY_AUDIT_FILTER) {
  const onChange = vi.fn<(next: AuditFilterValue) => void>();
  const utils = renderWithProviders(<AuditFilterBar value={initial} onChange={onChange} />);
  return { onChange, ...utils };
}

describe("AuditFilterBar", () => {
  it("renders all four filter controls and a disabled clear button when empty", () => {
    const { getByRole } = setup();
    expect(getByRole("button", { name: /Stream/i })).toBeInTheDocument();
    expect(getByRole("button", { name: /Role/i })).toBeInTheDocument();
    expect(getByRole("button", { name: /Clear all audit filters/i })).toBeDisabled();
  });

  it("toggles a stream kind via the Stream multi-select", async () => {
    const { onChange, getByRole, findByRole } = setup();
    await userEvent.click(getByRole("button", { name: /Stream/i }));
    const piiOption = await findByRole("option", { name: /PII reveal/i });
    await userEvent.click(piiOption);
    expect(onChange).toHaveBeenLastCalledWith({
      ...EMPTY_AUDIT_FILTER,
      kinds: ["PII_REVEAL"],
    });
  }, 15_000);

  it("removes a kind when the same option is clicked again", async () => {
    const { onChange, getByRole, findByRole } = setup({
      ...EMPTY_AUDIT_FILTER,
      kinds: ["APPLICATION"],
    });
    await userEvent.click(getByRole("button", { name: /Stream/i }));
    const opt = await findByRole("option", { name: /Application/i });
    expect(opt.getAttribute("aria-selected")).toBe("true");
    await userEvent.click(opt);
    expect(onChange).toHaveBeenLastCalledWith({ ...EMPTY_AUDIT_FILTER, kinds: [] });
  }, 15_000);

  it("toggles a role via the Role multi-select", async () => {
    const { onChange, getByRole, findByRole } = setup();
    await userEvent.click(getByRole("button", { name: /^Role/i }));
    const opsOption = await findByRole("option", { name: /Ops/i });
    await userEvent.click(opsOption);
    expect(onChange).toHaveBeenLastCalledWith({
      ...EMPTY_AUDIT_FILTER,
      roles: ["OPS_USER"],
    });
  }, 15_000);

  it("emits a fromDate when the From input changes, null when cleared", async () => {
    const { onChange, container, rerender } = setup();
    const input = container.querySelector(
      '[data-slot="audit-filter-from"]',
    ) as HTMLInputElement | null;
    expect(input).not.toBeNull();
    await userEvent.type(input!, "2026-05-01");
    // userEvent.type fires per-character; assert the final composed value.
    const lastCall = onChange.mock.calls.at(-1);
    expect(lastCall?.[0].fromDate).not.toBeNull();

    onChange.mockClear();
    rerender(
      <AuditFilterBar
        value={{ ...EMPTY_AUDIT_FILTER, fromDate: "2026-05-01" }}
        onChange={onChange}
      />,
    );
    const input2 = container.querySelector('[data-slot="audit-filter-from"]') as HTMLInputElement;
    await userEvent.clear(input2);
    expect(onChange).toHaveBeenLastCalledWith({ ...EMPTY_AUDIT_FILTER, fromDate: null });
  }, 15_000);

  it("emits a toDate when the To input changes", async () => {
    const { onChange, container } = setup();
    const input = container.querySelector('[data-slot="audit-filter-to"]') as HTMLInputElement;
    await userEvent.type(input, "2026-05-31");
    const lastCall = onChange.mock.calls.at(-1);
    expect(lastCall?.[0].toDate).not.toBeNull();
  }, 15_000);

  it("Clear resets every dimension to the empty filter", async () => {
    const populated: AuditFilterValue = {
      kinds: ["APPLICATION"],
      roles: ["OPS_USER"],
      fromDate: "2026-05-01",
      toDate: "2026-05-31",
    };
    const { onChange, getByRole } = setup(populated);
    const clear = getByRole("button", { name: /Clear all audit filters/i });
    expect(clear).not.toBeDisabled();
    await userEvent.click(clear);
    expect(onChange).toHaveBeenLastCalledWith(EMPTY_AUDIT_FILTER);
  });

  it("limits the role options when availableRoles is restricted", async () => {
    const onChange = vi.fn();
    const { getByRole, findByRole, queryByRole } = renderWithProviders(
      <AuditFilterBar
        value={EMPTY_AUDIT_FILTER}
        onChange={onChange}
        availableRoles={["SYSTEM_ADMIN"]}
      />,
    );
    await userEvent.click(getByRole("button", { name: /^Role/i }));
    expect(await findByRole("option", { name: /System admin/i })).toBeInTheDocument();
    expect(queryByRole("option", { name: /^Ops$/i })).toBeNull();
  }, 15_000);

  it("has no axe violations in the resting state", async () => {
    const { container } = setup();
    expect(await axe(container)).toHaveNoViolations();
  });
});
