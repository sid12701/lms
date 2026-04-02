create table loan_application_document_access_audit (
    id uuid primary key,
    loan_application_id uuid not null,
    action varchar(64) not null,
    actor_username varchar(255) not null,
    summary varchar(500) not null,
    document_types varchar(500) not null,
    correlation_id varchar(128),
    created_at timestamp with time zone not null,
    constraint fk_loan_document_access_audit_application
        foreign key (loan_application_id) references loan_application (id)
);

create index idx_loan_document_access_audit_application_created_at
    on loan_application_document_access_audit (loan_application_id, created_at desc);
