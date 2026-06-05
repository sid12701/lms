import { useId, useMemo } from "react";
import { X } from "lucide-react";
import { MultiSelectChip } from "@/components/app/data/MultiSelectChip";
import { FilterBarShell } from "@/components/app/data/FilterBarShell";
import { Button } from "@/components/ui/button";
import type { Role } from "@/types";
import {
  AUDIT_STREAM_KINDS,
  AUDIT_STREAM_LABEL,
  type AuditFilterValue,
  type AuditStreamKind,
  EMPTY_AUDIT_FILTER,
} from "./types";

/**
 * URL-state sync is intentionally deferred to Phase 7 (`useUrlFilters`);
 * this bar is a pure controlled component for now.
 */
export interface AuditFilterBarProps {
  value: AuditFilterValue;
  onChange: (next: AuditFilterValue) => void;
  /** Roles offered in the actor-role filter; defaults to all roles. */
  availableRoles?: readonly Role[];
  className?: string;
}

const DEFAULT_ROLES: readonly Role[] = [
  "SYSTEM_ADMIN",
  "OPS_USER",
  "PRODUCT_ADMIN",
  "LSP_UI_READ",
  "LSP_UI_WRITE",
  "LSP_API_CLIENT",
];

const ROLE_LABEL: Record<Role, string> = {
  SYSTEM_ADMIN: "System admin",
  OPS_USER: "Ops",
  PRODUCT_ADMIN: "Product admin",
  LSP_UI_READ: "LSP read",
  LSP_UI_WRITE: "LSP write",
  LSP_API_CLIENT: "LSP API",
};

function isFilterActive(value: AuditFilterValue): boolean {
  return (
    value.kinds.length > 0 ||
    value.roles.length > 0 ||
    value.fromDate !== null ||
    value.toDate !== null
  );
}

/**
 * Filter bar for the audit explorer. Controlled by parent state via
 * `value` + `onChange`. All filter writes go through `onChange` with the
 * full next snapshot — no partial events — so consumers can persist or
 * sync to URL state without diffing.
 *
 * Date inputs are native `<input type="date">` for v1; Phase 7 will swap
 * them for the shadcn `Calendar` primitive once the date-range component
 * lands.
 */
export function AuditFilterBar({
  value,
  onChange,
  availableRoles = DEFAULT_ROLES,
  className,
}: AuditFilterBarProps) {
  const fromId = useId();
  const toId = useId();

  const kindOptions = useMemo(
    () =>
      AUDIT_STREAM_KINDS.map((k) => ({
        value: k,
        label: AUDIT_STREAM_LABEL[k],
      })),
    [],
  );
  const roleOptions = useMemo(
    () => availableRoles.map((r) => ({ value: r, label: ROLE_LABEL[r] })),
    [availableRoles],
  );

  const setKinds = (kinds: AuditStreamKind[]) => onChange({ ...value, kinds });
  const setRoles = (roles: Role[]) => onChange({ ...value, roles });

  const handleFromChange = (next: string) => {
    onChange({ ...value, fromDate: next === "" ? null : next });
  };
  const handleToChange = (next: string) => {
    onChange({ ...value, toDate: next === "" ? null : next });
  };

  const active = isFilterActive(value);

  return (
    <FilterBarShell
      dataSlot="audit-filter-bar"
      ariaLabel="Audit filters"
      className={className}
      lead={
        <span className="text-foreground-muted px-1 text-xs font-medium tracking-wide uppercase">
          Filters
        </span>
      }
    >
      <MultiSelectChip<AuditStreamKind>
        label="Stream"
        options={kindOptions}
        selected={value.kinds}
        onToggle={setKinds}
      />
      <MultiSelectChip<Role>
        label="Role"
        options={roleOptions}
        selected={value.roles}
        onToggle={setRoles}
      />

      <div className="flex items-center gap-1.5">
        <label
          htmlFor={fromId}
          className="text-foreground-muted text-xs font-medium tracking-wide uppercase"
        >
          From
        </label>
        <input
          id={fromId}
          type="date"
          data-slot="audit-filter-from"
          value={value.fromDate ?? ""}
          onChange={(e) => handleFromChange(e.target.value)}
          className="border-border bg-surface text-foreground h-8 rounded-md border px-2 text-xs tabular-nums"
        />
      </div>
      <div className="flex items-center gap-1.5">
        <label
          htmlFor={toId}
          className="text-foreground-muted text-xs font-medium tracking-wide uppercase"
        >
          To
        </label>
        <input
          id={toId}
          type="date"
          data-slot="audit-filter-to"
          value={value.toDate ?? ""}
          onChange={(e) => handleToChange(e.target.value)}
          className="border-border bg-surface text-foreground h-8 rounded-md border px-2 text-xs tabular-nums"
        />
      </div>

      <div className="flex-1" />

      <Button
        type="button"
        variant="ghost"
        size="sm"
        onClick={() => onChange(EMPTY_AUDIT_FILTER)}
        disabled={!active}
        data-slot="audit-filter-clear"
        aria-label="Clear all audit filters"
        className="gap-1"
      >
        <X aria-hidden="true" className="size-3.5" />
        Clear
      </Button>
    </FilterBarShell>
  );
}
