import { describe, it, expect, afterEach } from "vitest";
import { axe } from "vitest-axe";
import { render } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { TooltipProvider } from "@/components/ui/tooltip";
import { SessionProvider } from "@/features/auth/session-context";
import { ThemeProvider } from "@/app/providers";
import { adminSession } from "@/test/session-fixtures";
import { TopBar } from "./TopBar";

function renderTopBar() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ThemeProvider>
          <SessionProvider skipBootstrap initialSession={adminSession}>
            <TooltipProvider delayDuration={0}>
              <TopBar />
            </TooltipProvider>
          </SessionProvider>
        </ThemeProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("TopBar", () => {
  afterEach(() => {
    document.documentElement.classList.remove("dark");
  });

  it("renders the command palette trigger and user menu", () => {
    const { getAllByLabelText, getByLabelText } = renderTopBar();
    expect(getAllByLabelText("Open command palette").length).toBeGreaterThan(0);
    expect(getByLabelText(`Account menu for ${adminSession.user.username}`)).toBeInTheDocument();
  });

  it("has no axe violations in light mode", async () => {
    const { container } = renderTopBar();
    expect(await axe(container)).toHaveNoViolations();
  });

  it("has no axe violations in dark mode", async () => {
    document.documentElement.classList.add("dark");
    const { container } = renderTopBar();
    expect(await axe(container)).toHaveNoViolations();
  });
});
