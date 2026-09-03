/**
 * AuditPageFilterBar tests.
 *
 * The stream selector used to be a ten-button strip wearing tab ARIA —
 * `role="tablist"` / `role="tab"` / `aria-selected` with no tabpanel, no
 * `aria-controls` and no arrow-key traversal. Most of what is asserted here is
 * that the replacement genuinely behaves the way its role claims, because
 * "announces as a tab" was never the bug on its own; announcing as a tab and
 * then not behaving like one was.
 */
import { afterEach, describe, expect, it, vi } from "vitest";
import { axe } from "vitest-axe";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { axeBaseElement, renderWithProviders } from "@/test/utils";
import { AuditPageFilterBar } from "./AuditFilterBar";
import type { AuditEventsFilters } from "../types";

function renderBar(props?: {
  value?: AuditEventsFilters;
  onChange?: (next: AuditEventsFilters) => void;
  actorOptions?: readonly { value: string; label: string }[];
  ignoredFilterKeys?: readonly string[];
}) {
  return renderWithProviders(
    <AuditPageFilterBar
      value={props?.value ?? {}}
      onChange={props?.onChange ?? vi.fn()}
      actorOptions={props?.actorOptions}
      ignoredFilterKeys={props?.ignoredFilterKeys}
    />,
  );
}

describe("AuditPageFilterBar", () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it("presents stream as one control rather than a strip of stream buttons", () => {
    renderBar();

    expect(screen.getByLabelText("Stream filter")).toBeInTheDocument();
    // The nine stream names are no longer nine resting decision points.
    expect(screen.queryByRole("button", { name: "Intake" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Disbursement" })).not.toBeInTheDocument();
  });

  it("exposes no tab semantics anywhere in the bar", () => {
    renderBar({ value: { streams: ["APPLICATION"] } });

    expect(screen.queryByRole("tablist")).not.toBeInTheDocument();
    expect(screen.queryAllByRole("tab")).toHaveLength(0);
  });

  it("announces the stream control as a collapsed combobox that owns its list", async () => {
    const user = userEvent.setup();
    renderBar();

    const trigger = screen.getByLabelText("Stream filter");
    expect(trigger).toHaveAttribute("role", "combobox");
    expect(trigger).toHaveAttribute("aria-expanded", "false");

    await user.click(trigger);

    const listbox = await screen.findByRole("listbox");
    expect(trigger).toHaveAttribute("aria-expanded", "true");
    expect(trigger).toHaveAttribute("aria-controls", listbox.id);
    expect(screen.getByRole("option", { name: "All streams" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(screen.getByRole("option", { name: "Document access" })).toHaveAttribute(
      "aria-selected",
      "false",
    );
  }, 15000);

  it("opens and commits a stream from the keyboard alone", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderBar({ onChange });

    // The stream control is the first stop in the bar's tab order.
    await user.tab();
    expect(screen.getByLabelText("Stream filter")).toHaveFocus();

    await user.keyboard("{Enter}");
    await screen.findByRole("listbox");
    // Traversal the old strip promised via `role="tab"` and never implemented.
    await user.keyboard("{ArrowDown}{Enter}");

    await waitFor(() => {
      expect(onChange).toHaveBeenCalled();
    });
    expect(onChange.mock.calls.at(-1)?.[0]).toMatchObject({
      streams: ["APPLICATION"],
      page: 0,
    });
  }, 15000);

  it("returns focus to the stream trigger when the list is dismissed", async () => {
    const user = userEvent.setup();
    renderBar();

    await user.click(screen.getByLabelText("Stream filter"));
    await screen.findByRole("listbox");
    await user.keyboard("{Escape}");

    await waitFor(() => {
      expect(screen.getByLabelText("Stream filter")).toHaveFocus();
    });
  }, 15000);

  it("hydrates the stream control from the filter value and renders it as set", () => {
    renderBar({ value: { streams: ["DISBURSEMENT"] } });

    const trigger = screen.getByLabelText("Stream filter");
    expect(trigger).toHaveTextContent("Disbursement");
    expect(trigger).toHaveAttribute("data-filter-set", "true");
    expect(screen.getByLabelText("Actor filter")).not.toHaveAttribute("data-filter-set");
  });

  it("clears the stream filter when All streams is chosen", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderBar({ value: { streams: ["INTAKE"] }, onChange });

    await user.click(screen.getByLabelText("Stream filter"));
    await user.click(await screen.findByRole("option", { name: "All streams" }));

    await waitFor(() => {
      expect(onChange).toHaveBeenCalled();
    });
    expect(onChange.mock.calls.at(-1)?.[0]?.streams).toBeUndefined();
  }, 15000);

  it("names the applied stream in a chip and clears only that filter", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderBar({
      value: { streams: ["APPLICATION"], correlationId: "corr-deep" },
      onChange,
    });

    const chip = screen.getByRole("button", { name: "Remove Stream filter" }).closest("span");
    expect(chip).toHaveTextContent("Application");

    await user.click(screen.getByRole("button", { name: "Remove Stream filter" }));

    const next = onChange.mock.calls.at(-1)?.[0];
    expect(next?.streams).toBeUndefined();
    // Per-filter clear, not clear-all.
    expect(next?.correlationId).toBe("corr-deep");
  }, 15000);

  it("shows no applied-filter row when nothing is set", () => {
    const { container } = renderBar();
    expect(container.querySelector('[data-slot="audit-applied-filters"]')).toBeNull();
  });

  it("groups the date range under one named legend", () => {
    renderBar();
    const group = screen.getByRole("group", { name: "When" });
    expect(group).toContainElement(screen.getByLabelText("Audit from date"));
    expect(group).toContainElement(screen.getByLabelText("Audit to date"));
  });

  it("names a stream filter from the link that could not be applied", () => {
    // Regression guard for F-01: a deep link whose filter is dropped silently
    // turns a slice of the book into the whole book with no visible difference.
    renderBar({ ignoredFilterKeys: ["streams"] });

    const notice = screen.getByRole("status");
    expect(notice).toHaveTextContent(/could not be applied/i);
    expect(notice).toHaveTextContent("Stream");
  });

  it("does not render the removed free-text search input", () => {
    renderBar();
    expect(screen.queryByLabelText("Search audit log")).not.toBeInTheDocument();
  });

  it("publishes loanApplicationId through onChange", () => {
    vi.useFakeTimers();
    try {
      const onChange = vi.fn();
      renderBar({ onChange });

      fireEvent.change(screen.getByLabelText("Loan"), {
        target: { value: "11111111-1111-4111-8111-111111111111" },
      });
      vi.advanceTimersByTime(250);

      const lastCall = onChange.mock.calls.at(-1)?.[0];
      expect(lastCall?.loanApplicationId).toBe("11111111-1111-4111-8111-111111111111");
      expect(lastCall?.page).toBe(0);
    } finally {
      vi.useRealTimers();
    }
  });

  it("has no axe violations at rest", async () => {
    const { container } = renderBar({
      value: { streams: ["APPLICATION"] },
      ignoredFilterKeys: ["streams"],
    });
    expect(await axe(container)).toHaveNoViolations();
  }, 15000);

  it("has no axe violations with the stream list open", async () => {
    const user = userEvent.setup();
    // `container` misses the listbox — Radix portals it to the body.
    const { baseElement } = renderBar();

    await user.click(screen.getByLabelText("Stream filter"));
    await screen.findByRole("listbox");

    // `region` off (via the shared helper): it is a best-practice landmark
    // rule, and a bar rendered on its own has no landmarks to be inside.
    // Every rule that could catch the defect this control replaces — role
    // validity, required parent/children, accessible names — stays on.
    expect(await axeBaseElement(baseElement)).toHaveNoViolations();
  }, 20000);
});

describe("AuditPageFilterBar resting control count", () => {
  it("presents six leaf controls at rest, down from sixteen", () => {
    // The audit's *minimal choices* failure was a count: "All" + 9 streams as
    // buttons, plus actor, loan id, from, to, correlation and a permanently
    // mounted Clear. Stream is one control now and Clear only exists once
    // there is something to clear.
    const { container } = renderBar();

    const controls = container.querySelectorAll(
      '[data-slot="audit-stream-filter"], [data-slot="audit-actor-filter"], ' +
        '[data-slot="audit-loan-application-id"], [data-slot="audit-correlation-id"], ' +
        '[data-slot="audit-date-from"], [data-slot="audit-date-to"]',
    );
    expect(controls).toHaveLength(6);
    expect(screen.queryByRole("button", { name: /clear/i })).not.toBeInTheDocument();
  });
});
