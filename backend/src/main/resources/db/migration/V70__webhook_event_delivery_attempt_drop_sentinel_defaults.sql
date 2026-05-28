-- F-16: drop sentinel defaults on webhook_event_delivery_attempt request_* columns.
-- V27 added these columns with placeholder defaults ('UNKNOWN' / '0' / 'UNSIGNED') so
-- existing rows could be backfilled. Every writer (WebhookOutboxService) now populates
-- all four fields unconditionally, so the defaults only serve to mask bugs: if a future
-- writer forgets a field, the row would silently get a sentinel value instead of
-- failing at insert. Dropping the defaults keeps the NOT NULL constraint and forces
-- omissions to surface loudly.

alter table webhook_event_delivery_attempt
    alter column request_event_type drop default;

alter table webhook_event_delivery_attempt
    alter column request_delivery_id drop default;

alter table webhook_event_delivery_attempt
    alter column request_timestamp drop default;

alter table webhook_event_delivery_attempt
    alter column request_signature drop default;
