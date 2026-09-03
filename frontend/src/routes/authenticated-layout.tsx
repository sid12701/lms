import { Outlet } from "react-router-dom";
import { RequireAuth } from "@/routes/guards";
import { AppShell } from "@/components/app/shell/AppShell";
import { PageMetaProvider } from "@/components/app/shell/page-meta-context";

export function AuthenticatedLayout() {
  return (
    <RequireAuth>
      <PageMetaProvider>
        <AppShell>
          <Outlet />
        </AppShell>
      </PageMetaProvider>
    </RequireAuth>
  );
}
