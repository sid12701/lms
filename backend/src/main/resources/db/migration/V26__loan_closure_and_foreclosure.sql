alter table loan_account
    add column closure_reason varchar(32);

alter table loan_account
    add column closed_at timestamptz;

alter table loan_account
    add column closed_by_username varchar(255);

create table loan_foreclosure_quote (
    id uuid primary key,
    loan_account_id uuid not null references loan_account(id),
    version integer not null,
    requested_by_username varchar(255) not null,
    executed_by_username varchar(255),
    effective_date date not null,
    outstanding_principal numeric(19,2) not null,
    outstanding_interest numeric(19,2) not null,
    settlement_amount numeric(19,2) not null,
    status varchar(32) not null,
    executed_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create unique index idx_loan_foreclosure_quote_account_version
    on loan_foreclosure_quote (loan_account_id, version);
