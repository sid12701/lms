import type { ReactNode } from "react";
import type { LucideIcon } from "lucide-react";
import { Plus, ShieldAlert } from "lucide-react";
import { PageHeader } from "@/components/app/layout/PageHeader";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { ErrorState } from "@/components/app/feedback/ErrorState";
import { Button } from "@/components/ui/button";
import { isUnauthorized } from "@/lib/admin-page-utils";

export interface AdminEntityListPagePrimaryAction {
  label: string;
  dataSlot: string;
  onClick: () => void;
}

export interface AdminEntityListPageProps {
  testId: string;
  /**
   * Optional and *undefaulted*.
   *
   * This used to default to `"Administration"`, which meant removing the
   * `eyebrow=` prop from the five admin pages changed nothing — they all fell
   * back to the default and kept rendering a kicker that only restated the
   * sidebar group the user had just clicked (audit S6, triple orientation
   * redundancy). A default here is invisible at the call site, so opting out of
   * it was impossible without editing this file.
   */
  eyebrow?: string;
  title: string;
  description: string;
  primaryAction?: AdminEntityListPagePrimaryAction;
  /** Replaces `primaryAction` when the header needs custom controls. */
  headerActions?: ReactNode;
  /** Shown above the list body (e.g. temp password or secret reveal). */
  banner?: ReactNode;
  /** Inserted after auth/error gates, before the filter bar (e.g. alert rules panel). */
  listPrefix?: ReactNode;
  list: {
    isError: boolean;
    isPending: boolean;
    error: unknown;
    refetch: () => void;
  };
  unauthorized: {
    title: string;
    description: string;
  };
  fetchError: {
    title: string;
    description: string;
  };
  isCatalogueEmpty: boolean;
  catalogueEmpty: {
    icon: LucideIcon;
    title: string;
    description: string;
  };
  filterBar: ReactNode;
  table: ReactNode;
  dialogs?: ReactNode;
}

/**
 * Shared chrome for admin entity list surfaces (`/users`, `/products`, `/lsps`,
 * `/api-clients`, `/alerts`). Owns page layout, header, permission/error gates,
 * filter + catalogue-empty + table slots.
 */
export function AdminEntityListPage({
  testId,
  eyebrow,
  title,
  description,
  primaryAction,
  headerActions,
  banner,
  listPrefix,
  list,
  unauthorized,
  fetchError,
  isCatalogueEmpty,
  catalogueEmpty,
  filterBar,
  table,
  dialogs,
}: AdminEntityListPageProps) {
  const headerSlot =
    headerActions ??
    (primaryAction ? (
      <Button type="button" onClick={primaryAction.onClick} data-slot={primaryAction.dataSlot}>
        <Plus aria-hidden="true" className="size-4" />
        {primaryAction.label}
      </Button>
    ) : undefined);

  return (
    <div data-testid={testId} className="flex flex-col gap-6 p-6" data-density="comfortable">
      <PageHeader eyebrow={eyebrow} title={title} description={description} actions={headerSlot} />

      {banner}

      {list.isError && isUnauthorized(list.error) ? (
        <EmptyState
          variant="no-permission"
          icon={ShieldAlert}
          title={unauthorized.title}
          description={unauthorized.description}
        />
      ) : list.isError ? (
        <ErrorState
          title={fetchError.title}
          description={fetchError.description}
          retry={{
            label: "Retry",
            onClick: () => {
              void list.refetch();
            },
          }}
        />
      ) : (
        <>
          {listPrefix}
          {filterBar}
          {isCatalogueEmpty ? (
            <EmptyState
              icon={catalogueEmpty.icon}
              title={catalogueEmpty.title}
              description={catalogueEmpty.description}
            />
          ) : (
            table
          )}
        </>
      )}

      {dialogs}
    </div>
  );
}
