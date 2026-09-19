-- H24: durable worker lease + notification outbox on report_request.
--
-- Report processing no longer holds one transaction across generation, object
-- storage and SMTP. A short claim transaction records who owns the row and when
-- its lease expires; generation/storage happen outside any transaction; the
-- terminal outcome is written by a second fenced transaction. Notifications are
-- a durable pending state retried by a separate sweep instead of an in-tx send.

ALTER TABLE report_request
    ADD COLUMN processing_owner VARCHAR(160),
    ADD COLUMN processing_expires_at TIMESTAMPTZ,
    ADD COLUMN processing_attempt INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN notification_attempts INTEGER NOT NULL DEFAULT 0;

-- Rows left PROCESSING by the pre-lease protocol are immediately reclaimable;
-- an already-expired lease lets the next claim pick them up.
UPDATE report_request
SET processing_expires_at = now()
WHERE status = 'PROCESSING'
  AND processing_expires_at IS NULL;

-- Reclaimable processing work: expired leases waiting for a new owner.
CREATE INDEX idx_report_request_processing_lease
    ON report_request (processing_expires_at)
    WHERE status = 'PROCESSING';

-- Notification outbox: terminal rows with a recipient whose send never landed.
CREATE INDEX idx_report_request_notification_pending
    ON report_request (created_at)
    WHERE status IN ('COMPLETED', 'FAILED')
      AND notification_email IS NOT NULL
      AND notification_sent_at IS NULL;
