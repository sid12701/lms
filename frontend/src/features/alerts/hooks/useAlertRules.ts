import { useQuery } from "@tanstack/react-query";
import { listAlertRules } from "../api";
import type { AlertRuleRow } from "../types";

const ALERT_RULES_QUERY_KEY = ["alerts", "rules"] as const;

export function useAlertRules(enabled: boolean) {
  return useQuery<AlertRuleRow[]>({
    queryKey: ALERT_RULES_QUERY_KEY,
    queryFn: listAlertRules,
    enabled,
    staleTime: 60_000,
  });
}
