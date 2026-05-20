import { type ReactNode } from "react";
import { Tabs as TabsPrimitive } from "radix-ui";
import { cn } from "@/lib/utils";
import type { LoanApplicationDetailTab } from "../types";

const TAB_DEFS: ReadonlyArray<{ value: LoanApplicationDetailTab; label: string }> = [
  { value: "overview", label: "Overview" },
  { value: "schedule", label: "Schedule" },
  { value: "documents", label: "Documents" },
  { value: "repayments", label: "Repayments" },
  { value: "activity", label: "Activity" },
  { value: "webhooks", label: "Webhooks" },
];

export interface DetailTabsShellProps {
  activeTab: LoanApplicationDetailTab;
  onTabChange: (next: LoanApplicationDetailTab) => void;
  /** Active tab content; rendered inside the matching TabsContent slot. */
  children: ReactNode;
  className?: string;
}

const TAB_TRIGGER_CLASSES = cn(
  "text-foreground-muted hover:text-foreground data-[state=active]:text-foreground",
  "data-[state=active]:border-foreground border-b-2 border-transparent",
  "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
  "px-3 py-2 text-sm font-medium transition-colors",
);

/**
 * Six-tab navigation shell for `/loan-applications/:id`.
 *
 * The component is presentational — URL plumbing lives upstream in
 * `detail-page.tsx` via a `useSearchParams` helper. We render TabsList +
 * the children block (the active tab body) inside the matching
 * TabsContent slot; siblings are not mounted, keeping the per-tab hook
 * pause logic in `detail-page` honest.
 */
export function DetailTabsShell({
  activeTab,
  onTabChange,
  children,
  className,
}: DetailTabsShellProps) {
  return (
    <TabsPrimitive.Root
      data-slot="detail-tabs"
      value={activeTab}
      onValueChange={(value) => onTabChange(value as LoanApplicationDetailTab)}
      className={cn("flex flex-col gap-4", className)}
    >
      <TabsPrimitive.List
        aria-label="Loan application sections"
        className="border-border flex flex-wrap items-center gap-1 border-b"
      >
        {TAB_DEFS.map((tab) => (
          <TabsPrimitive.Trigger
            key={tab.value}
            value={tab.value}
            className={TAB_TRIGGER_CLASSES}
            data-testid={`tab-trigger-${tab.value}`}
          >
            {tab.label}
          </TabsPrimitive.Trigger>
        ))}
      </TabsPrimitive.List>

      <TabsPrimitive.Content
        value={activeTab}
        data-testid={`tab-panel-${activeTab}`}
        className="focus-visible:outline-none"
      >
        {children}
      </TabsPrimitive.Content>
    </TabsPrimitive.Root>
  );
}

export default DetailTabsShell;
