/**
 * The single filter surface for `/audit`.
 *
 * It used to be two: a sticky ten-button strip for the stream, and this bar for
 * everything else — sixteen controls at one decision point, which is the
 * *minimal choices* failure the UX audit recorded against this route. Stream is
 * a filter like the other five, so it is one now: the shared
 * `FilterBarSingleSelect`, the same control six other list surfaces use.
 *
 * That also ends an ARIA lie. The strip carried `role="tablist"` / `role="tab"`
 * / `aria-selected` with no tabpanel, no `aria-controls`, and no roving
 * tabindex, so a screen reader announced "tab, 3 of 10" for a control that
 * neither owned a panel nor traversed on arrow keys. The select inherits real
 * combobox/listbox semantics and real keyboard traversal from the primitive
 * rather than half-implementing them here.
 *
 * State is owned by the page (URL-bound); this file owns only which filters
 * exist and how they are laid out.
 */
import { useMemo } from "react";
import { useDebouncedControlledText } from "@/lib/hooks/use-debounced-controlled-text";
import { Input } from "@/components/ui/input";
import { DatePickerField } from "@/components/app/data/DatePickerField";
import {
  FilterAppliedChips,
  FilterBarFieldGroup,
  FilterBarShell,
  FilterBarSingleSelect,
  IgnoredFilterNotice,
  type AppliedFilter,
} from "@/components/app/data/FilterBarShell";
import { filterControlClass } from "@/components/app/data/filter-control";
import {
  AUDIT_STREAMS,
  AUDIT_STREAM_LABEL,
  type AuditEventsFilters,
  type AuditStream,
} from "../types";
import { auditFilterLabelFor } from "../url-filters";

const SEARCH_DEBOUNCE_MS = 200;

const STREAM_OPTIONS: readonly { value: AuditStream; label: string }[] = AUDIT_STREAMS.map((s) => ({
  value: s,
  label: AUDIT_STREAM_LABEL[s],
}));

export interface ActorOption {
  value: string;
  label: string;
}

export interface AuditPageFilterBarProps {
  value: AuditEventsFilters;
  onChange: (next: AuditEventsFilters) => void;
  /** Actors present in the loaded rows, rendered in the dropdown. Optional. */
  actorOptions?: ReadonlyArray<ActorOption>;
  /** Filter keys the URL asked for that could not be applied. */
  ignoredFilterKeys?: readonly string[];
  className?: string;
}

const EMPTY_ACTOR_OPTIONS: readonly ActorOption[] = [];
const EMPTY_IGNORED_FILTER_KEYS: readonly string[] = [];

export function AuditPageFilterBar({
  value,
  onChange,
  actorOptions = EMPTY_ACTOR_OPTIONS,
  ignoredFilterKeys = EMPTY_IGNORED_FILTER_KEYS,
  className,
}: AuditPageFilterBarProps) {
  const loanApplicationField = useDebouncedControlledText(
    value.loanApplicationId,
    (loanApplicationId) => onChange({ ...value, loanApplicationId, page: 0 }),
    SEARCH_DEBOUNCE_MS,
  );
  const correlationField = useDebouncedControlledText(
    value.correlationId,
    (correlationId) => onChange({ ...value, correlationId, page: 0 }),
    SEARCH_DEBOUNCE_MS,
  );

  const { clearPending: clearLoanPending, onChange: setLoanValue } = loanApplicationField;
  const { clearPending: clearCorrelationPending, onChange: setCorrelationValue } = correlationField;

  const clearAll = () => {
    clearLoanPending();
    clearCorrelationPending();
    setLoanValue("");
    setCorrelationValue("");
    onChange({
      ...value,
      streams: undefined,
      loanApplicationId: undefined,
      correlationId: undefined,
      actorId: undefined,
      dateFrom: undefined,
      dateTo: undefined,
      page: 0,
    });
  };

  /**
   * One chip per set filter, resolved to the label the operator sees. The page
   * is most often entered by deep link (`/audit?correlationId=…` from an
   * alert), so "which slice am I looking at" has to be answerable without
   * reading the address bar.
   */
  const appliedFilters = useMemo<AppliedFilter[]>(() => {
    const chips: AppliedFilter[] = [];
    const stream = value.streams?.[0];
    if (stream) {
      chips.push({
        key: "streams",
        label: "Stream",
        value: AUDIT_STREAM_LABEL[stream],
        onClear: () => onChange({ ...value, streams: undefined, page: 0 }),
      });
    }
    if (value.actorId) {
      chips.push({
        key: "actorId",
        label: "Actor",
        value: actorOptions.find((o) => o.value === value.actorId)?.label ?? value.actorId,
        onClear: () => onChange({ ...value, actorId: undefined, page: 0 }),
      });
    }
    if (value.loanApplicationId?.trim()) {
      chips.push({
        key: "loanApplicationId",
        label: "Loan",
        value: value.loanApplicationId,
        onClear: () => {
          clearLoanPending();
          setLoanValue("");
          onChange({ ...value, loanApplicationId: undefined, page: 0 });
        },
      });
    }
    if (value.correlationId?.trim()) {
      chips.push({
        key: "correlationId",
        label: "Correlation",
        value: value.correlationId,
        onClear: () => {
          clearCorrelationPending();
          setCorrelationValue("");
          onChange({ ...value, correlationId: undefined, page: 0 });
        },
      });
    }
    if (value.dateFrom) {
      chips.push({
        key: "dateFrom",
        label: "From",
        value: value.dateFrom,
        onClear: () => onChange({ ...value, dateFrom: undefined, page: 0 }),
      });
    }
    if (value.dateTo) {
      chips.push({
        key: "dateTo",
        label: "To",
        value: value.dateTo,
        onClear: () => onChange({ ...value, dateTo: undefined, page: 0 }),
      });
    }
    return chips;
  }, [
    value,
    onChange,
    actorOptions,
    clearLoanPending,
    setLoanValue,
    clearCorrelationPending,
    setCorrelationValue,
  ]);

  return (
    <FilterBarShell
      dataSlot="audit-page-filter-bar"
      ariaLabel="Audit log filters"
      className={className}
      notice={
        <IgnoredFilterNotice
          ignoredKeys={ignoredFilterKeys}
          labelFor={auditFilterLabelFor}
          dataSlot="audit-ignored-filters"
        />
      }
      appliedChips={
        // No `resultCount`: the list is cursor-paged and the response's `total`
        // is the length of the page in hand, not of the match set. A count that
        // reads "25 events" over an unknown remainder is worse than none.
        <FilterAppliedChips
          filters={appliedFilters}
          onClearAll={clearAll}
          dataSlot="audit-applied-filters"
        />
      }
    >
      <FilterBarSingleSelect
        value={value.streams?.[0]}
        onChange={(next) =>
          onChange({ ...value, streams: next ? [next as AuditStream] : undefined, page: 0 })
        }
        placeholder="All streams"
        ariaLabel="Stream filter"
        options={STREAM_OPTIONS}
        dataSlot="audit-stream-filter"
        className="w-44"
      />

      <FilterBarSingleSelect
        value={value.actorId}
        onChange={(next) => onChange({ ...value, actorId: next, page: 0 })}
        placeholder="All actors"
        ariaLabel="Actor filter"
        options={actorOptions}
        dataSlot="audit-actor-filter"
        className="w-44"
      />

      <div className="flex items-center gap-1.5">
        <span
          id="audit-loan-application-label"
          className="text-foreground-muted text-eyebrow uppercase"
        >
          Loan
        </span>
        <Input
          type="text"
          data-slot="audit-loan-application-id"
          aria-labelledby="audit-loan-application-label"
          value={loanApplicationField.value}
          onChange={(e) => loanApplicationField.onChange(e.target.value)}
          placeholder="loan application id"
          className={filterControlClass(loanApplicationField.value.trim() !== "", "w-56")}
        />
      </div>

      <div className="flex items-center gap-1.5">
        <span id="audit-correlation-label" className="text-foreground-muted text-eyebrow uppercase">
          Correlation
        </span>
        <Input
          type="text"
          data-slot="audit-correlation-id"
          aria-labelledby="audit-correlation-label"
          value={correlationField.value}
          onChange={(e) => correlationField.onChange(e.target.value)}
          placeholder="correlation id"
          className={filterControlClass(correlationField.value.trim() !== "", "w-48")}
        />
      </div>

      {/* Grouped rather than adjacent, as on `/loan-applications`: the pair
          wraps as one flex item at every width and the legend names once what
          two loose date boxes named twice. */}
      <FilterBarFieldGroup label="When">
        <DatePickerField
          dataSlot="audit-date-from"
          ariaLabel="Audit from date"
          placeholder="From"
          value={value.dateFrom}
          onChange={(next) => onChange({ ...value, dateFrom: next, page: 0 })}
          className="w-28"
        />
        <span aria-hidden="true" className="text-foreground-muted text-xs">
          –
        </span>
        <DatePickerField
          dataSlot="audit-date-to"
          ariaLabel="Audit to date"
          placeholder="To"
          value={value.dateTo}
          onChange={(next) => onChange({ ...value, dateTo: next, page: 0 })}
          className="w-28"
        />
      </FilterBarFieldGroup>
    </FilterBarShell>
  );
}
