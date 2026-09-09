import type { ReactElement, ReactNode } from "react";
import { TooltipProvider } from "@/components/ui/tooltip";
import { Toaster } from "sonner";
import { SessionProvider } from "@/features/auth/session-provider";
import { AuthScopedQueryProvider } from "@/app/auth-scoped-query-provider";
import { ThemeProvider } from "@/app/theme-context";
import { DensityProvider } from "@/app/density-context";

export function Providers({ children }: { children: ReactNode }): ReactElement {
  return (
    <SessionProvider>
      <ThemeProvider>
        <DensityProvider>
          <AuthScopedQueryProvider>
            <TooltipProvider delayDuration={150}>
              {children}
              <section
                aria-label="Notifications"
                className="pointer-events-none fixed inset-0 z-100"
              >
                <Toaster
                  position="top-right"
                  richColors
                  closeButton
                  className="pointer-events-auto"
                />
              </section>
            </TooltipProvider>
          </AuthScopedQueryProvider>
        </DensityProvider>
      </ThemeProvider>
    </SessionProvider>
  );
}
