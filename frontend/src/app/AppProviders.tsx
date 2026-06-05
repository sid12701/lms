import type { ReactElement, ReactNode } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { TooltipProvider } from "@/components/ui/tooltip";
import { Toaster } from "sonner";
import { SessionProvider } from "@/features/auth/session-provider";
import { defaultAppQueryClient } from "@/app/query-client";
import { ThemeProvider } from "@/app/theme-context";
import { DensityProvider } from "@/app/density-context";

interface ProvidersProps {
  children: ReactNode;
  /** Test seam — pass an isolated QueryClient. */
  queryClient?: QueryClient;
}

export function Providers({ children, queryClient }: ProvidersProps): ReactElement {
  const client = queryClient ?? defaultAppQueryClient;
  return (
    <QueryClientProvider client={client}>
      <ThemeProvider>
        <DensityProvider>
          <SessionProvider>
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
          </SessionProvider>
        </DensityProvider>
      </ThemeProvider>
    </QueryClientProvider>
  );
}
