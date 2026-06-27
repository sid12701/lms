import { useId, useMemo } from "react";
import { X } from "lucide-react";
import { MultiSelectChip } from "@/components/app/data/MultiSelectChip";
import { DatePickerField } from "@/components/app/data/DatePickerField";
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
 * Date filters use the shared dd/MM/yyyy picker.
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
        <span
          id={fromId}
          className="text-foreground-muted text-xs font-medium tracking-wide uppercase"
        >
          From
        </span>
        <DatePickerField
          id={fromId}
          value={value.fromDate ?? undefined}
          onChange={(next) => handleFromChange(next ?? "")}
          ariaLabel="Audit from date"
          dataSlot="audit-filter-from"
          className="w-36"
        />
      </div>
      <div className="flex items-center gap-1.5">
        <span
          id={toId}
          className="text-foreground-muted text-xs font-medium tracking-wide uppercase"
        >
          To
        </span>
        <DatePickerField
          id={toId}
          value={value.toDate ?? undefined}
          onChange={(next) => handleToChange(next ?? "")}
          ariaLabel="Audit to date"
          dataSlot="audit-filter-to"
          className="w-36"
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
