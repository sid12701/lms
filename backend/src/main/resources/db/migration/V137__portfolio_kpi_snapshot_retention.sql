-- L04: explicit retention for derived portfolio KPI snapshots.
-- The snapshot worker writes one row per LSP plus one global row every run
-- (default cadence 15 min); without a purge the table grew without bound while
-- only the latest row per scope is ever read. PortfolioKpiSnapshotRetentionWorker
-- deletes rows older than app.portfolio-kpi.retention-days in bounded batches,
-- always preserving the newest row per scope. This index backs the purge's
-- computed_at predicate.
CREATE INDEX IF NOT EXISTS idx_portfolio_kpi_snapshot_computed
    ON portfolio_kpi_snapshot (computed_at);

COMMENT ON TABLE portfolio_kpi_snapshot IS
    'Derived portfolio KPI history (one row per LSP + one global per run). Retention: rows older than app.portfolio-kpi.retention-days are purged in batches, except the latest row per scope. Not an immutable financial record.';
