CREATE TABLE report_request (
    id UUID PRIMARY KEY,
    report_type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    lsp_id UUID REFERENCES lsp (id),
    disbursal_date_from DATE,
    disbursal_date_to DATE,
    requested_by_username VARCHAR(255) NOT NULL,
    file_name VARCHAR(255),
    media_type VARCHAR(128),
    report_content TEXT,
    error_message VARCHAR(1000),
    completed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_report_request_created_at ON report_request (created_at);
CREATE INDEX idx_report_request_status ON report_request (status);
CREATE INDEX idx_report_request_lsp ON report_request (lsp_id);
