create table admin_api_idempotency_record (
    id uuid primary key,
    operation_key varchar(64) not null,
    idempotency_key varchar(64) not null,
    request_fingerprint varchar(128) not null,
    response_status integer not null,
    response_body text not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create unique index uk_admin_api_idempotency_scope
    on admin_api_idempotency_record (operation_key, idempotency_key);

create index idx_admin_api_idempotency_created_at
    on admin_api_idempotency_record (created_at desc);

create index if not exists idx_lsp_api_idempotency_created_at
    on lsp_api_idempotency_record (created_at);
