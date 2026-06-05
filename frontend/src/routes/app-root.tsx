import { Outlet } from "react-router-dom";

/**
 * Root layout route — providers live in {@link App} so session/theme/query context
 * wraps the router (including lazy auth pages) and survives Vite HMR cleanly.
 */
export function AppRoot() {
  return <Outlet />;
}
