ALTER TABLE report_request
    ADD COLUMN notification_email VARCHAR(255),
    ADD COLUMN notification_sent_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN notification_error_message VARCHAR(1000);
