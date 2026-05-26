/**
 * View-layer types for the `/reports` surface.
 *
 * MIS row/summary shapes come from `@/schemas/report` (Gap #10 — aligned
 * with the live backend preview). Request-queue types still mirror the mock
 * module until that surface is wired to the backend.
 */
import type {
  CreateReportRequestInput,
  MisPreviewInstallment,
  MisPreviewResponseDto,
  MisPreviewRow,
  MisSummary,
} from "@/schemas/report";
import type {
  MisFilters as MisFiltersDto,
  MisPreviewFilters as MisPreviewFiltersDto,
} from "@/mocks/api/reports";
import type { ReportRequest, ReportStatus, ReportType } from "@/types";

export type {
  CreateReportRequestInput,
  MisPreviewInstallment,
  MisPreviewRow,
  MisPreviewResponseDto,
  MisSummary,
  ReportRequest,
  ReportStatus,
  ReportType,
};

/** Filter snapshot held by the `/reports` page. */
export interface ReportsPageFilters {
  lspId?: string | null;
  dateFrom?: string | null;
  dateTo?: string | null;
}

export type MisFilters = MisFiltersDto;
export type MisPreviewFilters = MisPreviewFiltersDto;

/** Response wrapper for `listRequests`. */
export interface ReportRequestsListResponse {
  items: ReportRequest[];
}
