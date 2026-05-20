create table lsp_api_idempotency_record (
    id uuid primary key,
    lsp_id uuid not null references lsp(id) on delete cascade,
    operation_key varchar(64) not null,
    idempotency_key varchar(64) not null,
    request_fingerprint varchar(128) not null,
    response_status integer not null,
    response_body text not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create unique index uk_lsp_api_idempotency_scope
    on lsp_api_idempotency_record (lsp_id, operation_key, idempotency_key);

create index idx_lsp_api_idempotency_created_at
    on lsp_api_idempotency_record (created_at desc);
