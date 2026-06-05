/**
 * Display labels for top-level route segments — used by `BreadcrumbBar` and
 * the route-change live region in `AppShell`.
 *
 * Lives in a sibling .ts file (rather than co-exported from
 * `BreadcrumbBar.tsx`) so the `react-refresh/only-export-components` lint
 * rule stays quiet — same pattern used for sibling `schema.ts` files across
 * the codebase.
 */
export const BREADCRUMB_LABELS: Record<string, string> = {
  home: "Home",
  "loan-applications": "Loan applications",
  borrowers: "Borrowers",
  alerts: "Alerts",
  reports: "Reports",
  audit: "Audit",
  lsps: "LSPs",
  products: "Products",
  users: "Users",
  "api-clients": "API clients",
  "my-loans": "My loans",
  dev: "Developer",
  components: "Components",
};

/** True if the segment looks like an opaque id (UUID-ish / 16+ hex chars). */
function isOpaqueId(segment: string): boolean {
  return /^[0-9a-f-]{16,}$/i.test(segment);
}

/** Title-case fallback for unknown segments. */
function humanize(segment: string): string {
  return segment.replace(/-/g, " ").replace(/\b\w/g, (c) => c.toUpperCase());
}

/**
 * Resolve a URL segment to a display label. Opaque ids resolve to "Detail";
 * known segments resolve via {@link BREADCRUMB_LABELS}; unknown segments
 * fall back to title-casing via `humanize()`.
 */
export function resolveBreadcrumbLabel(segment: string): string {
  if (isOpaqueId(segment)) return "Detail";
  return BREADCRUMB_LABELS[segment] ?? humanize(segment);
}
