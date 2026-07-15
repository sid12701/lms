CREATE TABLE IF NOT EXISTS portfolio_kpi_snapshot (
    id UUID PRIMARY KEY,
    lsp_id UUID NULL REFERENCES lsp(id),
    computed_at TIMESTAMPTZ NOT NULL,
    total_disbursed NUMERIC(19, 2) NOT NULL,
    total_outstanding NUMERIC(19, 2) NOT NULL,
    total_overdue NUMERIC(19, 2) NOT NULL,
    status_counts JSONB NOT NULL,
    dpd_buckets JSONB NOT NULL,
    avg_approval_tat_hours NUMERIC(10, 2) NULL,
    CONSTRAINT chk_portfolio_kpi_status_counts_object
        CHECK (jsonb_typeof(status_counts) = 'object'),
    CONSTRAINT chk_portfolio_kpi_dpd_buckets_object
        CHECK (jsonb_typeof(dpd_buckets) = 'object')
);

CREATE INDEX IF NOT EXISTS idx_portfolio_kpi_snapshot_lsp_computed
    ON portfolio_kpi_snapshot (lsp_id, computed_at DESC);
