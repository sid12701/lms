import { useId, useMemo, useRef } from "react";
import { Check, ChevronDown, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { cn } from "@/lib/utils";
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

interface MultiSelectChipProps<T extends string> {
  label: string;
  options: readonly { value: T; label: string }[];
  selected: T[];
  onToggle: (next: T[]) => void;
}

function MultiSelectChip<T extends string>({
  label,
  options,
  selected,
  onToggle,
}: MultiSelectChipProps<T>) {
  const buttonRef = useRef<HTMLButtonElement | null>(null);
  const summary =
    selected.length === 0
      ? label
      : selected.length === 1
        ? `${label}: ${options.find((o) => o.value === selected[0])?.label ?? selected[0]}`
        : `${label}: ${selected.length} selected`;

  const toggle = (val: T) => {
    if (selected.includes(val)) {
      onToggle(selected.filter((v) => v !== val));
    } else {
      onToggle([...selected, val]);
    }
  };

  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button
          ref={buttonRef}
          type="button"
          variant="outline"
          size="sm"
          data-slot="audit-filter-multiselect"
          aria-label={summary}
          className={cn(
            "gap-1",
            selected.length > 0 ? "border-ring text-foreground" : "text-foreground-muted",
          )}
        >
          <span>{summary}</span>
          <ChevronDown aria-hidden="true" className="size-3.5" />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-56 p-1">
        <ul role="listbox" aria-label={label} aria-multiselectable="true" className="flex flex-col">
          {options.map((opt) => {
            const checked = selected.includes(opt.value);
            return (
              <li key={opt.value}>
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  role="option"
                  aria-selected={checked}
                  onClick={() => toggle(opt.value)}
                  className="w-full justify-start gap-2"
                >
                  <span
                    aria-hidden="true"
                    className={cn(
                      "border-border inline-flex size-4 items-center justify-center rounded-sm border",
                      checked ? "bg-primary border-primary text-primary-foreground" : null,
                    )}
                  >
                    {checked ? <Check className="size-3" aria-hidden="true" /> : null}
                  </span>
                  <span>{opt.label}</span>
                </Button>
              </li>
            );
          })}
        </ul>
      </PopoverContent>
    </Popover>
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
    <div
      data-slot="audit-filter-bar"
      role="group"
      aria-label="Audit filters"
      className={cn(
        "border-border bg-surface flex flex-wrap items-center gap-2 rounded-md border p-2",
        className,
      )}
    >
      <span className="text-foreground-muted px-1 text-xs font-medium tracking-wide uppercase">
        Filters
      </span>

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
    </div>
  );
}
