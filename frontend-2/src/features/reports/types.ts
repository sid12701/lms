/**
 * View-layer types for the `/reports` surface.
 *
 * Re-exports the wire-format types from the shipped `@/mocks/api/reports`
 * module so the hooks + components don't reach across the mock boundary
 * directly. UI-only types (filter snapshot + dialog form shape are added
 * here too).
 */
import type {
  CreateReportRequestInput,
  MisFilters as MisFiltersDto,
  MisPreviewFilters as MisPreviewFiltersDto,
  MisPreviewResponseDto,
  MisPreviewRow,
  MisSummary,
} from "@/mocks/api/reports";
import type { ReportRequest, ReportStatus, ReportType } from "@/types";

export type {
  CreateReportRequestInput,
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
