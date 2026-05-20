import { describe, it, expect, afterEach } from "vitest";
import { axe } from "vitest-axe";
import { render } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { TooltipProvider } from "@/components/ui/tooltip";
import { SessionProvider } from "@/features/auth/session-context";
import { MockScenarioProvider, ThemeProvider } from "@/app/providers";
import type { Session } from "@/mocks/api/auth";
import { USER_OPS_ADMIN } from "@/mocks/db/seed";
import { TopBar } from "./TopBar";

const session: Session = {
  user: {
    id: USER_OPS_ADMIN,
    username: "ops.admin",
    role: "SYSTEM_ADMIN",
    lspId: null,
    mustChangePassword: false,
  },
  accessToken: "mock.token",
  expiresAt: new Date(Date.now() + 3600_000).toISOString(),
};

function renderTopBar() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <SessionProvider skipBootstrap initialSession={session}>
          <MockScenarioProvider>
            <ThemeProvider>
              <TooltipProvider delayDuration={0}>
                <TopBar onOpenMobileNav={() => {}} />
              </TooltipProvider>
            </ThemeProvider>
          </MockScenarioProvider>
        </SessionProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("TopBar", () => {
  afterEach(() => {
    document.documentElement.classList.remove("dark");
    window.localStorage.removeItem("bhawana-lms-theme");
  });

  it("renders all top-level controls", () => {
    const { getAllByLabelText, getByLabelText } = renderTopBar();
    // Open command palette (two triggers — large screen button + sm icon).
    expect(getAllByLabelText("Open command palette").length).toBeGreaterThanOrEqual(1);
    // Open navigation (mobile-only — present in the DOM regardless of breakpoint).
    expect(getByLabelText("Open navigation")).toBeInTheDocument();
    // Notifications, Help, Theme toggle, Account menu — all icon-only triggers
    // must carry an accessible name.
    expect(getByLabelText("Notifications")).toBeInTheDocument();
    expect(getByLabelText("Help")).toBeInTheDocument();
    expect(getByLabelText("Switch to dark theme")).toBeInTheDocument();
    expect(getByLabelText(/Account menu for ops\.admin/)).toBeInTheDocument();
  });

  it("has no axe violations", async () => {
    const { container } = renderTopBar();
    expect(await axe(container)).toHaveNoViolations();
  });

  it("has no axe violations in dark mode", async () => {
    document.documentElement.classList.add("dark");
    window.localStorage.setItem("bhawana-lms-theme", "dark");
    const { container, getByLabelText } = renderTopBar();
    // Sanity — the toggle's accessible name flips to the inverse action.
    expect(getByLabelText("Switch to light theme")).toBeInTheDocument();
    expect(await axe(container)).toHaveNoViolations();
  });
});
