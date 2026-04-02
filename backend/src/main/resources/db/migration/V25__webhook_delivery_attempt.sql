alter table webhook_event_outbox
    add column attempt_count integer not null default 0;

alter table webhook_event_outbox
    add column last_attempt_at timestamptz;

alter table webhook_event_outbox
    add column next_attempt_at timestamptz;

alter table webhook_event_outbox
    add column delivered_at timestamptz;

alter table webhook_event_outbox
    add column last_error varchar(1000);

alter table webhook_event_outbox
    add column updated_at timestamptz not null default now();

create index idx_webhook_event_outbox_dispatch
    on webhook_event_outbox (status, next_attempt_at, created_at);

create table webhook_event_delivery_attempt (
    id uuid primary key,
    outbox_event_id uuid not null references webhook_event_outbox(id),
    attempt_number integer not null,
    request_url varchar(500) not null,
    response_status_code integer,
    response_body text,
    error_message text,
    status varchar(32) not null,
    created_at timestamptz not null default now()
);

create index idx_webhook_event_delivery_attempt_outbox_created_at
    on webhook_event_delivery_attempt (outbox_event_id, created_at desc);
