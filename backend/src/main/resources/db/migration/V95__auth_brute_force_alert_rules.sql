INSERT INTO alert_rule (id, code, name, description, enabled, audience, trigger_kind, config_json)
VALUES
    (
        '00000000-0000-4000-8000-000000000608',
        'AUTH_BRUTE_FORCE',
        'Auth brute-force lockout',
        'Locks an account after repeated failed password logins from the same username and IP within a short window.',
        TRUE,
        'SYSTEM_ADMIN',
        'SCHEDULED',
        '{"threshold":5,"windowMinutes":10}'
    ),
    (
        '00000000-0000-4000-8000-000000000609',
        'AUTH_BRUTE_FORCE_DISTRIBUTED',
        'Distributed auth brute-force',
        'Alerts when a username sees many failed logins from many distinct IPs over a longer window (no account lock).',
        TRUE,
        'SYSTEM_ADMIN',
        'SCHEDULED',
        '{"threshold":20,"distinctIpMin":5,"windowHours":24}'
    )
ON CONFLICT (code) DO NOTHING;
