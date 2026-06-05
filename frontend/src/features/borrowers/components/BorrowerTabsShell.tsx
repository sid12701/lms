import { type ReactNode } from "react";
import { Tabs as TabsPrimitive } from "radix-ui";
import { cn } from "@/lib/utils";
import type { BorrowerDetailTab } from "../types";

const TAB_DEFS: ReadonlyArray<{ value: BorrowerDetailTab; label: string }> = [
  { value: "profile", label: "Profile" },
  { value: "loans", label: "Loans" },
];

export interface BorrowerTabsShellProps {
  activeTab: BorrowerDetailTab;
  onTabChange: (next: BorrowerDetailTab) => void;
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
 * Three-tab navigation shell for `/borrowers/:id`.
 *
 * Presentational — URL plumbing lives upstream in `detail-page.tsx` via a
 * `useSearchParams` helper. Mirrors the Phase 5 `DetailTabsShell` pattern:
 * only the active TabsContent is mounted so per-tab hook pause logic stays
 * honest.
 */
export function BorrowerTabsShell({
  activeTab,
  onTabChange,
  children,
  className,
}: BorrowerTabsShellProps) {
  return (
    <TabsPrimitive.Root
      data-slot="borrower-detail-tabs"
      value={activeTab}
      onValueChange={(value) => onTabChange(value as BorrowerDetailTab)}
      className={cn("flex flex-col gap-4", className)}
    >
      <TabsPrimitive.List
        aria-label="Borrower sections"
        className="border-border flex flex-wrap items-center gap-1 border-b"
      >
        {TAB_DEFS.map((tab) => (
          <TabsPrimitive.Trigger
            key={tab.value}
            value={tab.value}
            className={TAB_TRIGGER_CLASSES}
            data-testid={`borrower-tab-trigger-${tab.value}`}
          >
            {tab.label}
          </TabsPrimitive.Trigger>
        ))}
      </TabsPrimitive.List>

      <TabsPrimitive.Content
        value={activeTab}
        data-testid={`borrower-tab-panel-${activeTab}`}
        className="focus-visible:outline-none"
      >
        {children}
      </TabsPrimitive.Content>
    </TabsPrimitive.Root>
  );
}

export default BorrowerTabsShell;
