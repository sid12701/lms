import { describe, it, expect } from "vitest";
import { axe } from "vitest-axe";
import { render } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Routes, Route, useNavigate } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { TooltipProvider } from "@/components/ui/tooltip";
import { SessionProvider } from "@/features/auth/session-context";
import { ThemeProvider } from "@/app/providers";
import { adminSession } from "@/test/session-fixtures";
import { AppShell } from "./AppShell";

function createQueryClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
}

const adminSessionFixture = adminSession;

function renderShell() {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <MemoryRouter initialEntries={["/home"]}>
        <ThemeProvider>
          <SessionProvider skipBootstrap initialSession={adminSessionFixture}>
            <TooltipProvider delayDuration={0}>
              <Routes>
                <Route
                  path="/home"
                  element={
                    <AppShell>
                      <div data-testid="outlet">Outlet content</div>
                    </AppShell>
                  }
                />
              </Routes>
            </TooltipProvider>
          </SessionProvider>
        </ThemeProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("AppShell", () => {
  it("renders the sidebar, top bar, breadcrumb, and outlet", () => {
    const { getByLabelText, getByTestId, container } = renderShell();
    // Exactly one sidebar landmark — landmark-unique a11y rule depends on it.
    expect(getByLabelText("Primary navigation")).toBeInTheDocument();
    // TopBar slot is data-attributed.
    expect(document.querySelector('[data-slot="top-bar"]')).not.toBeNull();
    // Breadcrumb landmark (inside main).
    expect(container.querySelector('main nav[aria-label="Breadcrumb"]')).not.toBeNull();
    // Outlet.
    expect(getByTestId("outlet")).toBeInTheDocument();
  });

  it("renders the optional right-rail when supplied", () => {
    render(
      <QueryClientProvider client={createQueryClient()}>
        <MemoryRouter initialEntries={["/loan-applications/x"]}>
          <ThemeProvider>
            <SessionProvider skipBootstrap initialSession={adminSessionFixture}>
              <TooltipProvider delayDuration={0}>
                <Routes>
                  <Route
                    path="/loan-applications/:id"
                    element={
                      <AppShell rightRail={<div data-testid="rail">Rail</div>}>
                        <div>Body</div>
                      </AppShell>
                    }
                  />
                </Routes>
              </TooltipProvider>
            </SessionProvider>
          </ThemeProvider>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    expect(document.querySelector('[aria-label="Detail context"]')).not.toBeNull();
  });

  // ─── Item 2: skip-to-main link ─────────────────────────────────────────────
  describe("skip-to-main link", () => {
    it("exists in the DOM as the first focusable descendant of the shell", () => {
      const { container } = renderShell();
      const shell = container.querySelector('[data-slot="app-shell"]');
      const skipNav = shell?.querySelector('nav[aria-label="Skip navigation"]');
      const skip = container.querySelector('[data-slot="skip-to-main"]');
      expect(skipNav).not.toBeNull();
      expect(skip).not.toBeNull();
      // Skip nav is the first landmark in the shell so Tab from the top hits the link.
      expect(shell?.firstElementChild).toBe(skipNav);
      expect(skip?.getAttribute("href")).toBe("#main");
      expect(skip?.textContent).toBe("Skip to main content");
    });

    it("is sr-only by default and revealed on focus", async () => {
      const { container } = renderShell();
      const skip = container.querySelector<HTMLAnchorElement>('[data-slot="skip-to-main"]');
      expect(skip).not.toBeNull();
      expect(skip!.className).toContain("sr-only");
      expect(skip!.className).toContain("focus:not-sr-only");

      // First Tab should land on the skip link.
      await userEvent.tab();
      expect(document.activeElement).toBe(skip);
    });
  });

  // ─── Item 8: route-change live region ───────────────────────────────────────
  describe("route-change live region", () => {
    it("renders an aria-live polite announcer with the current page label", () => {
      const { container } = renderShell();
      const announcer = container.querySelector('main [data-slot="route-announcer"]');
      expect(announcer).not.toBeNull();
      expect(announcer?.getAttribute("aria-live")).toBe("polite");
      expect(announcer?.className).toContain("sr-only");
      expect(announcer?.textContent).toBe("Home page");
    });

    it("updates after programmatic navigation", async () => {
      function NavigateButton() {
        const navigate = useNavigate();
        return (
          <button type="button" data-testid="go-lsps" onClick={() => navigate("/lsps")}>
            Go to LSPs
          </button>
        );
      }

      const { container, getByTestId } = render(
        <QueryClientProvider client={createQueryClient()}>
          <MemoryRouter initialEntries={["/home"]}>
            <ThemeProvider>
              <SessionProvider skipBootstrap initialSession={adminSessionFixture}>
                <TooltipProvider delayDuration={0}>
                  <Routes>
                    <Route
                      path="/home"
                      element={
                        <AppShell>
                          <NavigateButton />
                        </AppShell>
                      }
                    />
                    <Route
                      path="/lsps"
                      element={
                        <AppShell>
                          <div>LSPs body</div>
                        </AppShell>
                      }
                    />
                  </Routes>
                </TooltipProvider>
              </SessionProvider>
            </ThemeProvider>
          </MemoryRouter>
        </QueryClientProvider>,
      );

      expect(container.querySelector('[data-slot="route-announcer"]')?.textContent).toBe(
        "Home page",
      );
      await userEvent.click(getByTestId("go-lsps"));
      expect(container.querySelector('[data-slot="route-announcer"]')?.textContent).toBe(
        "LSPs page",
      );
    });
  });

  it("has no axe violations", async () => {
    const { container } = renderShell();
    expect(await axe(container)).toHaveNoViolations();
  });
});
