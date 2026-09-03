/**
 * Manage per-LSP IP allowlists split by surface (UI login vs API client tokens).
 */
import { useState } from "react";
import { Shield, X } from "lucide-react";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { ErrorState } from "@/components/app/feedback/ErrorState";
import { mapApiErrorMessage } from "@/lib/api/user-messages";
import { IpAllowListEditor } from "@/features/api-clients/components/IpAllowListEditor";
import { useLspIpAllowlistAdmin } from "../hooks/useLspIpAllowlistAdmin";
import type { LspIpAllowlistEntry, LspIpAllowlistSurface, LspRow } from "../types";

export interface LspIpAllowlistDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  lsp: LspRow | null;
}

function SurfaceSection({
  title,
  description,
  enforceId,
  enforced,
  onEnforcedChange,
  entries,
  onRemove,
  pending,
  onPendingChange,
  loading,
  canEnforce,
  emptyHint,
}: {
  title: string;
  description: string;
  enforceId: string;
  enforced: boolean;
  onEnforcedChange: (next: boolean) => void;
  entries: LspIpAllowlistEntry[];
  onRemove: (entryId: string) => void;
  pending: string[];
  onPendingChange: (next: string[]) => void;
  loading: boolean;
  canEnforce: boolean;
  emptyHint: string;
}) {
  return (
    <section className="border-border rounded-container flex flex-col gap-3 border p-3">
      <div className="flex flex-col gap-1">
        <h3 className="text-sm font-medium">{title}</h3>
        <p className="text-muted-foreground text-xs">{description}</p>
      </div>
      <div className="flex flex-col gap-1">
        <div className="flex items-center gap-2">
          <input
            id={enforceId}
            type="checkbox"
            className="h-4 w-4"
            checked={enforced}
            disabled={loading || !canEnforce}
            onChange={(event) => onEnforcedChange(event.target.checked)}
          />
          <Label htmlFor={enforceId} className="text-sm font-normal">
            Enforce allowlist (deny when IP does not match)
          </Label>
        </div>
        {!canEnforce && !loading ? (
          <p className="text-muted-foreground pl-6 text-xs">
            Add at least one CIDR entry before turning enforcement on.
          </p>
        ) : null}
      </div>
      {entries.length > 0 ? (
        <ul className="flex flex-col gap-1">
          {entries.map((entry) => (
            <li
              key={entry.id}
              className="bg-surface-muted flex items-center justify-between gap-2 rounded px-2 py-1 text-sm"
            >
              <span className="font-mono text-xs">{entry.cidr}</span>
              <Button
                type="button"
                variant="ghost"
                size="icon"
                className="h-7 w-7"
                disabled={loading}
                aria-label={`Remove ${entry.cidr}`}
                onClick={() => onRemove(entry.id)}
              >
                <X className="h-3.5 w-3.5" aria-hidden="true" />
              </Button>
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-muted-foreground text-xs">No saved CIDR entries yet.</p>
      )}
      <div className="flex flex-col gap-1">
        <Label className="text-xs">Add CIDR entries</Label>
        <IpAllowListEditor
          value={pending}
          onChange={onPendingChange}
          disabled={loading}
          emptyHint={emptyHint}
        />
      </div>
    </section>
  );
}

export function LspIpAllowlistDialog({ open, onOpenChange, lsp }: LspIpAllowlistDialogProps) {
  const lspId = lsp?.id ?? null;
  const { ui, api, enforcement, saveEntries, removeEntry, updateEnforcement } =
    useLspIpAllowlistAdmin(lspId, open);

  const [pendingUi, setPendingUi] = useState<string[]>([]);
  const [pendingApi, setPendingApi] = useState<string[]>([]);

  const queryLoading = ui.isPending || api.isPending || enforcement.isPending;
  const queryError = ui.error ?? api.error ?? enforcement.error;
  const loading =
    queryLoading || saveEntries.isPending || removeEntry.isPending || updateEnforcement.isPending;

  const uiEntries = ui.data ?? [];
  const apiEntries = api.data ?? [];
  const enforcementFlags = enforcement.data ?? { enforceUi: false, enforceApi: false };

  const resetMutationErrors = () => {
    saveEntries.reset();
    removeEntry.reset();
    updateEnforcement.reset();
  };

  const handleSavePending = async () => {
    if (!lspId) return;
    resetMutationErrors();
    try {
      await saveEntries.mutateAsync({ ui: pendingUi, api: pendingApi });
      setPendingUi([]);
      setPendingApi([]);
    } catch {
      // The mutation retains its error so the operator can retry without
      // losing either pending CIDR list.
    }
  };

  const handleEnforcementChange = async (patch: { enforceUi?: boolean; enforceApi?: boolean }) => {
    if (!lspId) return;
    resetMutationErrors();
    try {
      await updateEnforcement.mutateAsync(patch);
    } catch {
      // Surfaced below via updateEnforcement.error.
    }
  };

  const handleRemoveEntry = async (surface: LspIpAllowlistSurface, entryId: string) => {
    if (!lspId) return;
    resetMutationErrors();
    try {
      await removeEntry.mutateAsync({ surface, entryId });
    } catch {
      // Surfaced below via removeEntry.error.
    }
  };

  const mutationError = saveEntries.error ?? removeEntry.error ?? updateEnforcement.error;
  const mutationErrorMessage = saveEntries.error
    ? mapApiErrorMessage(saveEntries.error, "Failed to save allowlist entries.")
    : removeEntry.error
      ? mapApiErrorMessage(removeEntry.error, "Failed to remove entry.")
      : updateEnforcement.error
        ? mapApiErrorMessage(updateEnforcement.error, "Failed to update enforcement.")
        : null;
  const queryErrorMessage =
    queryError instanceof Error
      ? queryError.message
      : queryError
        ? "Failed to load IP allowlists."
        : null;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-lg">
        <DialogHeader>
          <div className="flex items-center gap-2">
            <Shield className="text-progress h-5 w-5" aria-hidden="true" />
            <DialogTitle>IP allowlists</DialogTitle>
          </div>
          <DialogDescription>
            {lsp
              ? `Office (UI) and datacenter (API) ranges for ${lsp.code}. When enforcement is on, login and API tokens are only issued from matching IPs.`
              : "Select an LSP to manage allowlists."}
          </DialogDescription>
        </DialogHeader>

        {queryError && !queryLoading ? (
          <ErrorState
            title="Couldn't load IP allowlists"
            description={queryErrorMessage ?? "Try again in a moment."}
            retry={{
              label: "Retry",
              onClick: () => {
                void Promise.all([ui.refetch(), api.refetch(), enforcement.refetch()]);
              },
            }}
          />
        ) : null}

        {mutationError ? (
          <p className="text-destructive text-sm" role="alert">
            {mutationErrorMessage}
          </p>
        ) : null}

        {!queryError || queryLoading ? (
          <div className="flex flex-col gap-4">
            <SurfaceSection
              title="UI (human login)"
              description="Applies to LSP portal users signing in with username and password."
              enforceId="enforce-ui"
              enforced={enforcementFlags.enforceUi}
              onEnforcedChange={(checked) => void handleEnforcementChange({ enforceUi: checked })}
              entries={uiEntries}
              onRemove={(id) => void handleRemoveEntry("ui", id)}
              pending={pendingUi}
              onPendingChange={setPendingUi}
              loading={loading}
              canEnforce={uiEntries.length > 0 || pendingUi.length > 0}
              emptyHint="No IP restrictions. Portal users can sign in from any source IP until you add an entry."
            />
            <SurfaceSection
              title="API (machine clients)"
              description="Applies to client-credentials tokens used by integrations."
              enforceId="enforce-api"
              enforced={enforcementFlags.enforceApi}
              onEnforcedChange={(checked) => void handleEnforcementChange({ enforceApi: checked })}
              entries={apiEntries}
              onRemove={(id) => void handleRemoveEntry("api", id)}
              pending={pendingApi}
              onPendingChange={setPendingApi}
              loading={loading}
              canEnforce={apiEntries.length > 0 || pendingApi.length > 0}
              emptyHint="No IP restrictions. API clients can obtain tokens from any source IP until you add an entry."
            />
          </div>
        ) : null}

        <DialogFooter className="flex-col gap-2 sm:flex-row">
          <Button
            type="button"
            disabled={loading || !lsp || (pendingUi.length === 0 && pendingApi.length === 0)}
            onClick={() => void handleSavePending()}
          >
            Save new entries
          </Button>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
            Close
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
