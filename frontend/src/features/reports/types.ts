/**
 * View-layer types for the `/reports` surface.
 *
 * MIS row/summary/filter shapes come from `@/schemas/report` (Gap #10 — aligned
 * with the live backend preview).
 */
import type {
  CreateReportRequestInput,
  MisFilters,
  MisPreviewFilters,
  MisPreviewInstallment,
  MisPreviewResponseDto,
  MisPreviewRow,
  MisSummary,
} from "@/schemas/report";
import type { ReportRequest, ReportStatus, ReportType } from "@/types";

export type {
  CreateReportRequestInput,
  MisFilters,
  MisPreviewFilters,
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

/** Response wrapper for `listRequests`. */
export interface ReportRequestsListResponse {
  items: ReportRequest[];
}
