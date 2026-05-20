/**
 * Chip-style editor for an API client's IP allow-list (BR-8).
 *
 * Validates each new entry against the shared `IpCidr` regex before
 * appending. Invalid input is surfaced inline below the add field — the
 * controlled value remains unchanged until the operator types a valid
 * IPv4/CIDR. Existing entries can be removed via the per-chip "remove"
 * button.
 *
 * The component is intentionally controlled: parents track the canonical
 * list (typically in a RHF field) and re-render on every change. This
 * keeps Zod validation in lock-step with what the operator sees.
 */
import { useEffect, useRef, useState } from "react";
import { Plus, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { IpCidr } from "@/schemas/common";
import { cn } from "@/lib/utils";

export interface IpAllowListEditorProps {
  value: string[];
  onChange: (next: string[]) => void;
  /** Optional id wired to the surrounding form label for AT correctness. */
  id?: string;
  /** Disables every interactive control (chip remove + add input + add btn). */
  disabled?: boolean;
  className?: string;
}

export function IpAllowListEditor({
  value,
  onChange,
  id,
  disabled = false,
  className,
}: IpAllowListEditorProps) {
  const [adding, setAdding] = useState(false);
  const [draft, setDraft] = useState("");
  const [error, setError] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    if (!adding) return;
    const t = window.setTimeout(() => inputRef.current?.focus(), 0);
    return () => window.clearTimeout(t);
  }, [adding]);

  const handleRemove = (entry: string) => {
    onChange(value.filter((v) => v !== entry));
  };

  const tryAdd = () => {
    const trimmed = draft.trim();
    if (trimmed === "") {
      setError("Enter an IPv4 address or CIDR (e.g. 10.0.0.0/8).");
      return;
    }
    const parsed = IpCidr.safeParse(trimmed);
    if (!parsed.success) {
      setError("Invalid IPv4 / CIDR. Example: 10.0.0.0/8 or 203.0.113.7.");
      return;
    }
    if (value.includes(trimmed)) {
      setError("That entry is already in the allow-list.");
      return;
    }
    onChange([...value, trimmed]);
    setDraft("");
    setError(null);
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Enter") {
      e.preventDefault();
      tryAdd();
    }
    if (e.key === "Escape") {
      e.preventDefault();
      setDraft("");
      setError(null);
      setAdding(false);
    }
  };

  return (
    <div
      data-slot="ip-allow-list-editor"
      id={id}
      className={cn("flex flex-col gap-2", className)}
    >
      {value.length === 0 && !adding ? (
        <p className="text-foreground-muted text-xs">
          No IP restrictions. The client will be reachable from any source IP
          until you add an entry.
        </p>
      ) : null}

      {value.length > 0 ? (
        <ul
          aria-label="IP allow-list entries"
          className="flex flex-wrap gap-1.5"
        >
          {value.map((entry) => (
            <li
              key={entry}
              data-slot="ip-chip"
              className="border-border bg-surface-muted text-foreground inline-flex items-center gap-1 rounded-full border px-2.5 py-1 text-xs font-mono"
            >
              <span>{entry}</span>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                onClick={() => handleRemove(entry)}
                disabled={disabled}
                aria-label={`Remove ${entry} from allow-list`}
                data-slot="ip-chip-remove"
                className="h-5 w-5 p-0"
              >
                <X aria-hidden="true" className="size-3" />
              </Button>
            </li>
          ))}
        </ul>
      ) : null}

      {adding ? (
        <div className="flex flex-col gap-1">
          <div className="flex items-center gap-2">
            <Input
              ref={inputRef}
              value={draft}
              onChange={(e) => {
                setDraft(e.target.value);
                if (error) setError(null);
              }}
              onKeyDown={handleKeyDown}
              placeholder="10.0.0.0/8"
              aria-label="New CIDR entry"
              aria-invalid={error !== null}
              data-slot="ip-add-input"
              disabled={disabled}
              className="h-8 max-w-xs text-sm font-mono"
            />
            <Button
              type="button"
              variant="default"
              size="sm"
              onClick={tryAdd}
              disabled={disabled}
              data-slot="ip-add-confirm"
            >
              Add
            </Button>
            <Button
              type="button"
              variant="ghost"
              size="sm"
              onClick={() => {
                setDraft("");
                setError(null);
                setAdding(false);
              }}
              disabled={disabled}
              data-slot="ip-add-cancel"
            >
              Cancel
            </Button>
          </div>
          {error ? (
            <p
              role="alert"
              data-slot="ip-add-error"
              className="text-danger text-xs"
            >
              {error}
            </p>
          ) : null}
        </div>
      ) : (
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={() => setAdding(true)}
          disabled={disabled}
          data-slot="ip-add-open"
          className="self-start gap-1"
        >
          <Plus aria-hidden="true" className="size-3.5" />
          Add CIDR
        </Button>
      )}
    </div>
  );
}
