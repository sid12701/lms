INSERT INTO alert_rule (id, code, name, description, enabled, audience, trigger_kind, config_json)
VALUES
    (
        '00000000-0000-4000-8000-000000000610',
        'OLDEST_TRANSACTION_AGE',
        'Oldest open transaction age',
        'A long-running database transaction anywhere on the cluster holds back the loan event feed snapshot for every LSP until it completes.',
        TRUE,
        'SYSTEM_ADMIN',
        'SCHEDULED',
        '{"ageSeconds":300}'
    )
ON CONFLICT (code) DO NOTHING;
