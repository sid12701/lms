import { useQuery } from "@tanstack/react-query";
import { listLspAuditEvents } from "../api";

export function lspAuditEventsQueryKey(lspId: string | null) {
  return ["lsps", lspId, "audit-events"] as const;
}

export function useLspAuditEvents(lspId: string | null, enabled: boolean) {
  return useQuery({
    queryKey: lspAuditEventsQueryKey(lspId),
    queryFn: () => {
      if (!lspId) throw new Error("lspId required");
      return listLspAuditEvents(lspId);
    },
    enabled: enabled && Boolean(lspId),
    staleTime: 0,
    refetchOnMount: "always",
  });
}
