CREATE SEQUENCE loan_event_position_seq AS BIGINT;

CREATE TABLE loan_event (
    id UUID NOT NULL,
    lsp_id UUID NOT NULL REFERENCES lsp (id),
    event_type VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    loan_application_id UUID NOT NULL
        REFERENCES loan_application (id) DEFERRABLE INITIALLY DEFERRED,
    payload_json JSONB NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    correlation_id VARCHAR(128),
    transaction_id XID8 NOT NULL DEFAULT pg_current_xact_id(),
    position BIGINT NOT NULL DEFAULT nextval('loan_event_position_seq'),
    PRIMARY KEY (occurred_at, id)
) PARTITION BY RANGE (occurred_at);

DO $$
DECLARE
    month_offset INTEGER;
    partition_start TIMESTAMP WITH TIME ZONE;
    partition_end TIMESTAMP WITH TIME ZONE;
    partition_name TEXT;
BEGIN
    FOR month_offset IN -1..3 LOOP
        partition_start := date_trunc('month', CURRENT_TIMESTAMP) + make_interval(months => month_offset);
        partition_end := partition_start + INTERVAL '1 month';
        partition_name := 'loan_event_' || to_char(partition_start, 'YYYY_MM');
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF loan_event FOR VALUES FROM (%L) TO (%L)',
            partition_name,
            partition_start,
            partition_end
        );
    END LOOP;
END
$$;

CREATE INDEX idx_loan_event_feed_order
    ON loan_event (lsp_id, transaction_id, position);

CREATE INDEX idx_loan_event_application_time
    ON loan_event (loan_application_id, occurred_at DESC);

CREATE OR REPLACE FUNCTION reject_loan_event_mutation() RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'loan_event is append-only; updates and deletes are forbidden'
        USING ERRCODE = '55000';
END
$$;

CREATE TRIGGER loan_event_append_only
    BEFORE UPDATE OR DELETE ON loan_event
    FOR EACH ROW
    EXECUTE FUNCTION reject_loan_event_mutation();

GRANT SELECT, INSERT ON TABLE loan_event TO ${tenant_app_role};
GRANT USAGE, SELECT ON SEQUENCE loan_event_position_seq TO ${tenant_app_role};

ALTER TABLE loan_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE loan_event FORCE ROW LEVEL SECURITY;

CREATE POLICY loan_event_tenant_select_policy ON loan_event
    FOR SELECT
    TO ${tenant_app_role}
    USING (lsp_id = app_current_lsp_id());

CREATE POLICY loan_event_tenant_insert_policy ON loan_event
    FOR INSERT
    TO ${tenant_app_role}
    WITH CHECK (lsp_id = app_current_lsp_id());
