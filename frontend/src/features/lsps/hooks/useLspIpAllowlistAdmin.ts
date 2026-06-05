import { useQuery, useQueryClient } from "@tanstack/react-query";
import { getLspAllowlistEnforcement, listLspIpAllowlist } from "../api";
import type { LspIpAllowlistSurface } from "../types";

export function lspIpAllowlistQueryKey(lspId: string | null, surface: LspIpAllowlistSurface) {
  return ["lsps", lspId, "ip-allowlist", surface] as const;
}

export function lspAllowlistEnforcementQueryKey(lspId: string | null) {
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

  return { ui, api, enforcement, invalidate };
}
