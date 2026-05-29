/**
 * Mutation hook — fetches a download URL for a COMPLETED report request.
 *
 * The mock router returns a `data:text/csv;base64,…` URL; the consumer
 * calls `window.open(url, "_blank")` after the promise resolves.
 */
import { useMutation, type UseMutationResult } from "@tanstack/react-query";
import { toast } from "sonner";
import { downloadRequest } from "../api";

export interface DownloadReportVariables {
  id: string;
}

export function useDownloadReportRequest(): UseMutationResult<
  { url: string },
  Error,
  DownloadReportVariables
> {
  return useMutation<{ url: string }, Error, DownloadReportVariables>({
    mutationFn: ({ id }) => downloadRequest(id),
    onError: (err) => {
      toast.error(err.message || "Couldn't fetch the report download URL.");
    },
  });
}
