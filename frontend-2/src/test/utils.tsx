/* eslint-disable react-refresh/only-export-components -- test-only helper module */
import "vitest-axe/extend-expect";
import type { ReactElement, ReactNode } from "react";
import { render, type RenderOptions, type RenderResult } from "@testing-library/react";
import { TooltipProvider } from "@/components/ui/tooltip";

// jsdom does not implement these browser APIs, but Radix primitives reach for
// them as soon as a Popover/Tooltip/Dialog mounts. Importing this helper
// guarantees the polyfills are installed before any rendering happens.
if (typeof globalThis.ResizeObserver === "undefined") {
  class ResizeObserverPolyfill {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  (globalThis as any).ResizeObserver = ResizeObserverPolyfill;
}
// Radix Dialog/Tooltip rely on these PointerEvent helpers, which jsdom omits.
if (typeof window !== "undefined") {
  if (!("hasPointerCapture" in Element.prototype)) {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (Element.prototype as any).hasPointerCapture = () => false;
  }
  if (!("releasePointerCapture" in Element.prototype)) {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (Element.prototype as any).releasePointerCapture = () => {};
  }
  if (!("setPointerCapture" in Element.prototype)) {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (Element.prototype as any).setPointerCapture = () => {};
  }
  if (!("scrollIntoView" in Element.prototype)) {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (Element.prototype as any).scrollIntoView = () => {};
  }
}

interface ProvidersProps {
  children: ReactNode;
}

/**
 * Wraps children in any global providers required by primitives under test.
 * Today this is just `TooltipProvider` — Tooltip-using components need it
 * even when the tooltip itself never opens in the test.
 */
function Providers({ children }: ProvidersProps) {
  return <TooltipProvider delayDuration={0}>{children}</TooltipProvider>;
}

/** RTL `render` with our minimal global providers pre-applied. */
export function renderWithProviders(
  ui: ReactElement,
  options?: Omit<RenderOptions, "wrapper">,
): RenderResult {
  return render(ui, { wrapper: Providers, ...options });
}
