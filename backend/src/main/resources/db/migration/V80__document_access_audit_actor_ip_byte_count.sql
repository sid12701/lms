alter table loan_application_document_access_audit
    add column actor_ip varchar(64),
    add column byte_count bigint;
