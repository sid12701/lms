import type { ReactNode } from "react";
import { TooltipProvider } from "@/components/ui/tooltip";

export function TestProviders({ children }: { children: ReactNode }) {
  return <TooltipProvider delayDuration={0}>{children}</TooltipProvider>;
}
