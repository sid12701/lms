import type { ReactNode } from "react";
import { TableSkeleton } from "./Skeletons/TableSkeleton";

export type ContentStatus = "loading" | "empty" | "error" | "ready" | "permission-denied";

export interface ContentStateProps {
  status: ContentStatus;
  /** Loading slot. Defaults to a `<TableSkeleton />`; pass any node to override. */
  loading?: ReactNode;
  /** Empty slot — typically an `<EmptyState />`. */
  empty?: ReactNode;
  /** Error slot — typically an `<ErrorState />`. */
  error?: ReactNode;
  /** Permission denied slot — typically a `<PermissionDeniedState />`. */
  permissionDenied?: ReactNode;
  /** Rendered when `status === "ready"`. */
  children: ReactNode;
}

/**
 * Status-router that swaps between loading / empty / error / permission /
 * ready content. Loading state advertises itself with `role="status"`
 * (default skeletons already do) so screen readers announce the wait.
 *
 *   status="ready" → renders children
 *   status="loading" → renders `loading` (defaults to <TableSkeleton />)
 *   status="empty" → renders `empty`
 *   status="error" → renders `error`
 *   status="permission-denied" → renders `permissionDenied`
 *
 * If a slot is undefined for a non-ready status, the component returns
 * `null` rather than falling back to children — callers should always
 * provide the slots they expect to surface.
 */
export function ContentState({
  status,
  loading,
  empty,
  error,
  permissionDenied,
  children,
}: ContentStateProps) {
  switch (status) {
    case "ready":
      return <>{children}</>;
    case "loading":
      return <>{loading ?? <TableSkeleton />}</>;
    case "empty":
      return <>{empty ?? null}</>;
    case "error":
      return <>{error ?? null}</>;
    case "permission-denied":
      return <>{permissionDenied ?? null}</>;
    default:
      return null;
  }
}
