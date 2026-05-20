alter table loan_application
    add column invalid_reason_code varchar(64);

alter table loan_application
    add column invalid_reason_text varchar(500);

alter table loan_application
    add column invalidated_by_username varchar(255);

alter table loan_application
    add column invalidated_at timestamptz;

create index idx_loan_application_invalidated_at
    on loan_application (invalidated_at desc);
