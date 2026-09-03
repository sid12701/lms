/**
 * Mutation hook — downloads a COMPLETED report request through the API layer.
 *
 * The API layer owns the short-lived Blob URL and revokes it after triggering
 * the native browser download, so callers never receive a disposable URL.
 */
import { useMutation, type UseMutationResult } from "@tanstack/react-query";
import { toast } from "sonner";
import { mapApiErrorMessage } from "@/lib/api/user-messages";
import { downloadRequest } from "../api";

export interface DownloadReportVariables {
  id: string;
}

export function useDownloadReportRequest(): UseMutationResult<
  void,
  Error,
  DownloadReportVariables
> {
  return useMutation<void, Error, DownloadReportVariables>({
    mutationFn: ({ id }) => downloadRequest(id),
    onError: (err) => {
      toast.error(mapApiErrorMessage(err, "Couldn't fetch the report download URL."));
    },
  });
}
