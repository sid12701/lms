CREATE TABLE IF NOT EXISTS alert_rule (
    id UUID PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    audience VARCHAR(32) NOT NULL,
    trigger_kind VARCHAR(32) NOT NULL,
    config_json TEXT,
    last_evaluated_at TIMESTAMP WITH TIME ZONE
);

INSERT INTO alert_rule (id, code, name, description, enabled, audience, trigger_kind, config_json)
VALUES
    (
        '00000000-0000-4000-8000-000000000601',
        'STALE_INTAKE',
        'Stale intake',
        'INITIALIZED applications older than 24 hours with an incomplete required-document checklist.',
        TRUE,
        'OPS',
        'SCHEDULED',
        '{"staleHours":24}'
    ),
    (
        '00000000-0000-4000-8000-000000000602',
        'STUCK_DISBURSEMENT',
        'Stuck disbursement retry',
        'Applications in DISBURSEMENT_RETRY for more than 2 hours.',
        TRUE,
        'OPS',
        'SCHEDULED',
        '{"stuckHours":2}'
    ),
    (
        '00000000-0000-4000-8000-000000000603',
        'DPD_BUCKET_TRANSITION',
        'DPD bucket escalation',
        'UNDER_REPAYMENT loans whose delinquency bucket is past CURRENT (1–30, 31–60, 61–90, or 90+ DPD).',
        TRUE,
        'OPS',
        'SCHEDULED',
        '{}'
    ),
    (
        '00000000-0000-4000-8000-000000000604',
        'LSP_AUTO_REJECT_SPIKE',
        'LSP auto-reject rate spike',
        'LSP partners whose auto-reject rate exceeds the configured threshold over the rolling window.',
        TRUE,
        'SYSTEM_ADMIN',
        'SCHEDULED',
        '{"windowDays":7,"minSamples":10,"rejectRatePct":40}'
    ),
    (
        '00000000-0000-4000-8000-000000000605',
        'WEBHOOK_DEAD_LETTER',
        'Webhook dead letter',
        'Webhook outbox event exhausted retries and reached permanent failure.',
        TRUE,
        'SYSTEM_ADMIN',
        'EVENT',
        '{}'
    ),
    (
        '00000000-0000-4000-8000-000000000606',
        'BORROWER_ACTIVE_LOAN_DUPLICATE',
        'One open loan violation',
        'LSP intake blocked because the borrower already has an open loan (emitted at intake).',
        TRUE,
        'SYSTEM_ADMIN',
        'EVENT',
        '{}'
    ),
    (
        '00000000-0000-4000-8000-000000000607',
        'RATE_LIMIT_BREACH',
        'Rate limit breach',
        'LSP write or auth endpoint rate limit exceeded.',
        TRUE,
        'SYSTEM_ADMIN',
        'EVENT',
        '{}'
    )
ON CONFLICT (code) DO NOTHING;
