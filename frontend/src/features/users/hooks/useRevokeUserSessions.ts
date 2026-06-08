import { useMutation, type UseMutationResult } from "@tanstack/react-query";
import { toast } from "sonner";
import { revokeUserSessions } from "../api";
import type { RevokeUserSessionsInput, RevokeUserSessionsResponse } from "../types";

export interface RevokeUserSessionsVariables extends RevokeUserSessionsInput {
  id: string;
  username: string;
}

export function useRevokeUserSessions(): UseMutationResult<
  RevokeUserSessionsResponse,
  Error,
  RevokeUserSessionsVariables
> {
  return useMutation<RevokeUserSessionsResponse, Error, RevokeUserSessionsVariables>({
    mutationFn: ({ id, username: _username, ...rest }) => revokeUserSessions(id, rest),
    onSuccess: (result, variables) => {
      toast.success(
        `Revoked ${result.refreshTokensRevoked} active session(s) for ${variables.username}.`,
      );
    },
  });
}
