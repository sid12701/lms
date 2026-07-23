import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  addLspIpAllowlistEntry,
  getLspAllowlistEnforcement,
  listLspIpAllowlist,
  removeLspIpAllowlistEntry,
  updateLspAllowlistEnforcement,
} from "../api";
import type { LspIpAllowlistSurface } from "../types";

interface SaveAllowlistEntriesInput {
  ui: readonly string[];
  api: readonly string[];
}

interface RemoveAllowlistEntryInput {
  surface: LspIpAllowlistSurface;
  entryId: string;
}

interface UpdateAllowlistEnforcementInput {
  enforceUi?: boolean;
  enforceApi?: boolean;
}

function lspIpAllowlistQueryKey(lspId: string | null, surface: LspIpAllowlistSurface) {
  return ["lsps", lspId, "ip-allowlist", surface] as const;
}

function lspAllowlistEnforcementQueryKey(lspId: string | null) {
  return ["lsps", lspId, "allowlist-enforcement"] as const;
}

export function useLspIpAllowlistAdmin(lspId: string | null, enabled: boolean) {
  const active = enabled && Boolean(lspId);

  const ui = useQuery({
    queryKey: lspIpAllowlistQueryKey(lspId, "ui"),
    queryFn: () => {
      if (!lspId) throw new Error("lspId required");
      return listLspIpAllowlist(lspId, "ui");
    },
    enabled: active,
    staleTime: 0,
    refetchOnMount: "always",
  });

  const api = useQuery({
    queryKey: lspIpAllowlistQueryKey(lspId, "api"),
    queryFn: () => {
      if (!lspId) throw new Error("lspId required");
      return listLspIpAllowlist(lspId, "api");
    },
    enabled: active,
    staleTime: 0,
    refetchOnMount: "always",
  });

  const enforcement = useQuery({
    queryKey: lspAllowlistEnforcementQueryKey(lspId),
    queryFn: () => {
      if (!lspId) throw new Error("lspId required");
      return getLspAllowlistEnforcement(lspId);
    },
    enabled: active,
    staleTime: 0,
    refetchOnMount: "always",
  });

  const queryClient = useQueryClient();

  const invalidate = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ["lsps", lspId, "ip-allowlist"] }),
      queryClient.invalidateQueries({ queryKey: lspAllowlistEnforcementQueryKey(lspId) }),
    ]);
  };

  const saveEntries = useMutation<void, Error, SaveAllowlistEntriesInput>({
    mutationFn: async ({ ui: uiCidrs, api: apiCidrs }) => {
      if (!lspId) throw new Error("lspId required");
      await Promise.all([
        ...uiCidrs.map((cidr) => addLspIpAllowlistEntry(lspId, "ui", { cidr })),
        ...apiCidrs.map((cidr) => addLspIpAllowlistEntry(lspId, "api", { cidr })),
      ]);
    },
    onSuccess: invalidate,
  });

  const removeEntry = useMutation<void, Error, RemoveAllowlistEntryInput>({
    mutationFn: async ({ surface, entryId }) => {
      if (!lspId) throw new Error("lspId required");
      await removeLspIpAllowlistEntry(lspId, surface, entryId);
    },
    onSuccess: invalidate,
  });

  const updateEnforcement = useMutation<void, Error, UpdateAllowlistEnforcementInput>({
    mutationFn: async (patch) => {
      if (!lspId) throw new Error("lspId required");
      await updateLspAllowlistEnforcement(lspId, patch);
    },
    onSuccess: invalidate,
  });

  return { ui, api, enforcement, saveEntries, removeEntry, updateEnforcement };
}
