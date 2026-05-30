/**
 * DevTopBarTools tests — focus on the Phase 7 lifecycle-automation menu
 * items wiring to `@/mocks/lifecycle-automation`. The lifecycle module is
 * mocked so this suite does not depend on its real implementation. Sonner is
 * also mocked so we can assert the toast call sites without rendering the
 * actual Toaster.
 */
import { describe, it, expect, vi, beforeEach } from "vitest";
import { axe } from "vitest-axe";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactElement, ReactNode } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { TooltipProvider } from "@/components/ui/tooltip";
import { SessionProvider } from "@/features/auth/session-context";
import { MockScenarioProvider } from "@/app/providers";

vi.mock("@/mocks/lifecycle-automation", () => ({
  submitGeneratedSchedule: vi.fn(),
  completeForeclosureForApplication: vi.fn(),
  runFullLifecycle: vi.fn(),
}));

vi.mock("sonner", () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
    message: vi.fn(),
  },
}));

import {
  submitGeneratedSchedule,
  completeForeclosureForApplication,
  runFullLifecycle,
} from "@/mocks/lifecycle-automation";
import { toast } from "sonner";
import { DevTopBarTools } from "./DevTopBarTools";

function renderAt(path: string, routePattern: string): ReturnType<typeof render> {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  function Wrapper({ children }: { children: ReactNode }): ReactElement {
    return (
      <QueryClientProvider client={client}>
        <SessionProvider skipBootstrap initialSession={null}>
          <MockScenarioProvider>
            <TooltipProvider delayDuration={0}>{children}</TooltipProvider>
          </MockScenarioProvider>
        </SessionProvider>
      </QueryClientProvider>
    );
  }
  return render(
    <Wrapper>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path={routePattern} element={<DevTopBarTools />} />
          <Route path="*" element={<DevTopBarTools />} />
        </Routes>
      </MemoryRouter>
    </Wrapper>,
  );
}

beforeEach(() => {
  vi.mocked(submitGeneratedSchedule).mockReset();
  vi.mocked(completeForeclosureForApplication).mockReset();
  vi.mocked(runFullLifecycle).mockReset();
  vi.mocked(toast.success).mockReset();
  vi.mocked(toast.error).mockReset();
  vi.mocked(toast.message).mockReset();
});

describe("DevTopBarTools — Phase 7 lifecycle automation menu", () => {
  it("renders the three new menu items when a loan id is in the URL", async () => {
    const user = userEvent.setup();
    renderAt("/loan-applications/loan-123", "/loan-applications/:id");

    const trigger = await screen.findByRole("button", { name: /Developer tools/i });
    await user.click(trigger);

    expect(
      await screen.findByRole("menuitem", { name: /Simulate LSP schedule POST/i }),
    ).toBeInTheDocument();
    expect(
      await screen.findByRole("menuitem", { name: /Simulate LSP foreclosure POST/i }),
    ).toBeInTheDocument();
    expect(
      await screen.findByRole("menuitem", { name: /Run full lifecycle/i }),
    ).toBeInTheDocument();
  }, 15_000);

  it("disables the three items when there is no loan id in the route", async () => {
    const user = userEvent.setup();
    renderAt("/some-other-route", "/never-matches/:id");

    const trigger = await screen.findByRole("button", { name: /Developer tools/i });
    await user.click(trigger);

    const scheduleItem = await screen.findByRole("menuitem", {
      name: /Simulate LSP schedule POST/i,
    });
    const foreclosureItem = await screen.findByRole("menuitem", {
      name: /Simulate LSP foreclosure POST/i,
    });
    const lifecycleItem = await screen.findByRole("menuitem", {
      name: /Run full lifecycle/i,
    });

    for (const item of [scheduleItem, foreclosureItem, lifecycleItem]) {
      const disabled =
        item.getAttribute("aria-disabled") === "true" || item.hasAttribute("data-disabled");
      expect(disabled).toBe(true);
    }
  }, 15_000);

  it("invokes submitGeneratedSchedule with the URL id when the schedule item is clicked", async () => {
    vi.mocked(submitGeneratedSchedule).mockResolvedValue({
      step: "submit-schedule",
      ok: true,
      durationMs: 12,
    });

    const user = userEvent.setup();
    renderAt("/loan-applications/loan-abc", "/loan-applications/:id");

    const trigger = await screen.findByRole("button", { name: /Developer tools/i });
    await user.click(trigger);
    const item = await screen.findByRole("menuitem", { name: /Simulate LSP schedule POST/i });
    await user.click(item);

    expect(submitGeneratedSchedule).toHaveBeenCalledTimes(1);
    expect(submitGeneratedSchedule).toHaveBeenCalledWith("loan-abc");
    // Toast fires after the async result resolves.
    await vi.waitFor(() => {
      expect(vi.mocked(toast.success)).toHaveBeenCalled();
    });
  }, 15_000);

  it("invokes completeForeclosureForApplication when the foreclosure item is clicked", async () => {
    vi.mocked(completeForeclosureForApplication).mockResolvedValue({
      step: "foreclose",
      ok: true,
      durationMs: 20,
    });

    const user = userEvent.setup();
    renderAt("/loan-applications/loan-xyz", "/loan-applications/:id");

    const trigger = await screen.findByRole("button", { name: /Developer tools/i });
    await user.click(trigger);
    const item = await screen.findByRole("menuitem", { name: /Simulate LSP foreclosure POST/i });
    await user.click(item);

    expect(completeForeclosureForApplication).toHaveBeenCalledTimes(1);
    expect(completeForeclosureForApplication).toHaveBeenCalledWith("loan-xyz");
    await vi.waitFor(() => {
      expect(vi.mocked(toast.success)).toHaveBeenCalled();
    });
  }, 15_000);

  it("is axe-clean with the menu open (portaled — use baseElement)", async () => {
    const user = userEvent.setup();
    const { baseElement } = renderAt("/loan-applications/loan-axe", "/loan-applications/:id");

    const trigger = await screen.findByRole("button", { name: /Developer tools/i });
    await user.click(trigger);
    await screen.findByRole("menuitem", { name: /Simulate LSP schedule POST/i });

    // The dropdown trigger is rendered without a surrounding landmark in this
    // unit-test harness (in the real app it lives inside the TopBar / AppShell
    // landmarks). Disable the `region` rule for this isolated render.
    expect(await axe(baseElement, { rules: { region: { enabled: false } } })).toHaveNoViolations();
  }, 15_000);
});
