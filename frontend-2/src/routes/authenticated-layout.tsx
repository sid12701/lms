import { Outlet } from "react-router-dom";
import { RequireAuth } from "@/routes/guards";
import { AppShell } from "@/components/app/shell/AppShell";

export function AuthenticatedLayout() {
  return (
    <RequireAuth>
      <AppShell>
        <Outlet />
      </AppShell>
    </RequireAuth>
  );
}
