-- Drop the average-approval-TAT column from the portfolio KPI snapshot.
--
-- The metric was removed from the product: the home dashboard tile, the
-- `HomeDashboardSummary` DTO, the OpenAPI contract, the entity mapping and the
-- computation in `PortfolioKpiSnapshotComputationService` are all gone, so
-- nothing has read or written this column since. It is dropped here rather than
-- left dormant so the snapshot table does not carry a field the code no longer
-- explains.
--
-- This discards the historical TAT values. That is intended — the metric is not
-- coming back, and keeping unreadable numbers in a snapshot table invites a
-- future reader to trust them.
ALTER TABLE portfolio_kpi_snapshot
    DROP COLUMN IF EXISTS avg_approval_tat_hours;
