import { useEffect, useState } from "react";

/**
 * Subscribes to a CSS media query. SSR-safe: returns `defaultValue` until mounted.
 */
export function useMediaQuery(query: string, defaultValue = false): boolean {
  const [matches, setMatches] = useState(defaultValue);

  useEffect(() => {
    if (typeof window === "undefined" || typeof window.matchMedia !== "function") {
      return;
    }
    const media = window.matchMedia(query);
    const onChange = () => setMatches(media.matches);
    onChange();
    media.addEventListener("change", onChange);
    return () => media.removeEventListener("change", onChange);
  }, [query]);

  return matches;
}

/** Tailwind-aligned tiers: mobile <1024 (`lg`), compact 1024–1279, wide ≥1280. */
export type ViewportTier = "mobile" | "compact" | "wide";

const VIEWPORT_WIDE_QUERY = "(min-width: 1280px)";
const VIEWPORT_COMPACT_QUERY = "(min-width: 1024px) and (max-width: 1279px)";

/**
 * Single source of truth for shell layout tiers. Uses `matchMedia` listeners
 * (not `resize` / `innerWidth`). Defaults to `wide` until mounted so SSR and
 * the first paint match the desktop shell.
 */
export function useViewportTier(): ViewportTier {
  const isWide = useMediaQuery(VIEWPORT_WIDE_QUERY, true);
  const isCompact = useMediaQuery(VIEWPORT_COMPACT_QUERY, false);
  if (isWide) return "wide";
  if (isCompact) return "compact";
  return "mobile";
}

/** True when the viewport is below Tailwind `lg` (<1024px). */
export function useIsMobileViewport(): boolean {
  return useViewportTier() === "mobile";
}
