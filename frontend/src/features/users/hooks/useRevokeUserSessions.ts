import { useMutation, useQueryClient, type UseMutationResult } from "@tanstack/react-query";
import { toast } from "sonner";
import { revokeUserSessions } from "../api";
import type { RevokeUserSessionsInput, RevokeUserSessionsResponse } from "../types";
import { USERS_LIST_QUERY_KEY } from "./useUsers";

export interface RevokeUserSessionsVariables extends RevokeUserSessionsInput {
  id: string;
  username: string;
}

export function useRevokeUserSessions(): UseMutationResult<
  RevokeUserSessionsResponse,
  Error,
  RevokeUserSessionsVariables
> {
  const queryClient = useQueryClient();
  return useMutation<RevokeUserSessionsResponse, Error, RevokeUserSessionsVariables>({
    mutationFn: ({ id, username: _username, ...rest }) => revokeUserSessions(id, rest),
    onSuccess: (result, variables) => {
      void queryClient.invalidateQueries({ queryKey: [...USERS_LIST_QUERY_KEY] });
      toast.success(
        `Revoked ${result.refreshTokensRevoked} active session(s) for ${variables.username}.`,
      );
    },
  });
}
