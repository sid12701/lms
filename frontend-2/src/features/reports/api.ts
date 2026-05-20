/**
 * Reports feature API wrapper.
 *
 * Re-exports the typed mock handlers so hooks never touch `@/mocks/api/*`
 * directly. Importing this module is side-effectful via `@/mocks/api/reports`
 * — that module registers its routes on first import.
 */
import {
  createRequest as createRequestImpl,
  downloadRequest as downloadRequestImpl,
  listRequests as listRequestsImpl,
  misPreview as misPreviewImpl,
  misSummary as misSummaryImpl,
} from "@/mocks/api/reports";
import type {
  CreateReportRequestInput,
  MisFilters,
  MisPreviewFilters,
  MisPreviewResponseDto,
  MisSummary,
  ReportRequest,
  ReportRequestsListResponse,
} from "./types";

interface MutationOptions {
  idempotencyKey?: string;
}

export async function misSummary(
  filters: MisFilters = {},
): Promise<MisSummary> {
  return misSummaryImpl(filters);
}

export async function misPreview(
  filters: MisPreviewFilters = {},
): Promise<MisPreviewResponseDto> {
  return misPreviewImpl(filters);
}

export async function listRequests(): Promise<ReportRequestsListResponse> {
  return listRequestsImpl();
}

export async function createRequest(
  input: CreateReportRequestInput,
  opts: MutationOptions = {},
): Promise<ReportRequest> {
  return createRequestImpl(input, { idempotencyKey: opts.idempotencyKey });
}

export async function downloadRequest(id: string): Promise<{ url: string }> {
  return downloadRequestImpl(id);
}
