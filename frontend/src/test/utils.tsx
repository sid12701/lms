import "vitest-axe/extend-expect";
import type { ReactElement } from "react";
import { render, type RenderOptions, type RenderResult } from "@testing-library/react";
import { axe } from "vitest-axe";
import type AxeCore from "axe-core";
import { TestProviders } from "./test-providers";

if (typeof globalThis.ResizeObserver === "undefined") {
  class ResizeObserverPolyfill {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  (globalThis as any).ResizeObserver = ResizeObserverPolyfill;
}
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

/** RTL `render` with our minimal global providers pre-applied. */
export function renderWithProviders(
  ui: ReactElement,
  options?: Omit<RenderOptions, "wrapper">,
): RenderResult {
  return render(ui, { wrapper: TestProviders, ...options });
}

/**
 * axe scan for `baseElement` (i.e. `document.body`) — required whenever a
 * test renders anything Radix portals out of RTL's `container` (Dialog,
 * AlertDialog, Popover, Select, DropdownMenu, HoverCard, Sheet, Toast,
 * Tooltip…). `axe(container)` never sees that content at all, since a portal
 * mounts outside `container` by design; scanning `baseElement` is the only
 * way an axe assertion can actually reach it.
 *
 * `region` — axe's page-level "all content must sit inside a landmark" rule
 * — is always disabled here. It's unsatisfiable by construction: an isolated
 * component render has no `<main>`/`<nav>` shell to sit in, so a real
 * `region` violation on the live page and a false one from test isolation
 * would look identical. Every other rule stays on.
 *
 * Use `axe(container)` instead when the render under test portals nothing —
 * it's the tighter, more correct assertion there and should stay that way.
 */
export function axeBaseElement(
  baseElement: Element,
  options?: AxeCore.RunOptions,
): Promise<AxeCore.AxeResults> {
  return axe(baseElement, {
    ...options,
    rules: { ...options?.rules, region: { enabled: false } },
  });
}
