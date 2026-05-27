-- F-03: enforce referential integrity on webhook_event_outbox.loan_application_id.
-- No code path hard-deletes loan_application today, so the column is currently
-- clean. This FK is defence in depth: future cleanup scripts cannot silently
-- orphan outbox rows, and ON DELETE RESTRICT forces an explicit decision if a
-- delete path is ever introduced. Matches the ON DELETE RESTRICT used by the
-- other FKs on loan_application (V6).

-- Pre-flight: orphan rows must not exist. V58 backfilled loan_application_id
-- from aggregate linkage; any orphan here indicates a deeper data bug we must
-- surface, not paper over.
DO $$
DECLARE
    orphan_count BIGINT;
BEGIN
    SELECT COUNT(*) INTO orphan_count
    FROM webhook_event_outbox outbox
    WHERE outbox.loan_application_id IS NOT NULL
      AND NOT EXISTS (
          SELECT 1
          FROM loan_application app
          WHERE app.id = outbox.loan_application_id
      );

    IF orphan_count > 0 THEN
        RAISE EXCEPTION
            'webhook_event_outbox has % orphan loan_application_id row(s); '
            'investigate before adding FK (see F-03)', orphan_count;
    END IF;
END$$;

ALTER TABLE webhook_event_outbox
    ADD CONSTRAINT fk_webhook_event_outbox_loan_application
        FOREIGN KEY (loan_application_id)
        REFERENCES loan_application (id)
        ON DELETE RESTRICT;
