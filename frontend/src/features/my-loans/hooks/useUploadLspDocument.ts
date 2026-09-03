/**
 * Mutation hook — uploads a single LSP document via `uploadLspDocument`.
 *
 * On success, the uploaded row is written straight into the
 * `useLspSubmittedDocuments` cache for this application — the server's own
 * response to the upload, not a fabricated client copy — and a background
 * `invalidateQueries` reconciles it with the full list.
 *
 * The direct cache write (rather than invalidate-only) matters for the same
 * reason `DocumentsSection`'s old effect had a silent "upload-only state"
 * fallback: if the initial submitted-documents GET never resolved, or is
 * failing outright, invalidating alone would just re-trigger the same
 * failure, and a document the user genuinely uploaded would still not
 * appear. Seeding the cache from the mutation's own response means the
 * upload is visible immediately and survives a failed reconcile — the
 * query only ever shows data the server actually returned, so the cache
 * stays the single source of truth instead of a parallel client copy.
 */
import { useMutation, useQueryClient, type UseMutationResult } from "@tanstack/react-query";
import { isUploadedBackendChecklistStatus } from "@/schemas/document";
import {
  uploadLspDocument,
  type LspDocumentType,
  type SubmittedLspDocument,
  type UploadedLspDocument,
} from "../api";
import { lspSubmittedDocumentsQueryKey } from "./useLspSubmittedDocuments";

export interface UploadLspDocumentVariables {
  documentType: LspDocumentType;
  file: File;
}

function toSubmittedDocumentRow(uploaded: UploadedLspDocument): SubmittedLspDocument {
  return {
    documentType: uploaded.documentType,
    status: isUploadedBackendChecklistStatus(uploaded.status) ? "SUBMITTED" : "PENDING",
    fileName: uploaded.fileName,
    contentType: uploaded.contentType,
    note: null,
    uploadedAt: uploaded.uploadedAt,
    uploadedByUsername: uploaded.uploadedByUsername,
  };
}

export function useUploadLspDocument(
  applicationId: string,
): UseMutationResult<UploadedLspDocument, Error, UploadLspDocumentVariables> {
  const queryClient = useQueryClient();
  const queryKey = lspSubmittedDocumentsQueryKey(applicationId);

  return useMutation({
    mutationFn: ({ documentType, file }: UploadLspDocumentVariables) =>
      uploadLspDocument({ applicationId, documentType, file }),
    onSuccess: (uploaded) => {
      queryClient.setQueryData<SubmittedLspDocument[]>(queryKey, (old) => {
        const next = (old ?? []).filter((row) => row.documentType !== uploaded.documentType);
        next.push(toSubmittedDocumentRow(uploaded));
        return next;
      });
      void queryClient.invalidateQueries({ queryKey });
    },
  });
}
