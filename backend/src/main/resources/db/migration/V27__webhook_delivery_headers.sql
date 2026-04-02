alter table webhook_event_delivery_attempt
    add column request_event_type varchar(64) not null default 'UNKNOWN';

alter table webhook_event_delivery_attempt
    add column request_delivery_id varchar(64) not null default 'UNKNOWN';

alter table webhook_event_delivery_attempt
    add column request_timestamp varchar(64) not null default '0';

alter table webhook_event_delivery_attempt
    add column request_signature varchar(255) not null default 'UNSIGNED';
