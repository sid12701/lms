-- F-07: Move generated report bodies to object storage. The metadata row
-- now carries a storage_key pointing at R2; the inline report_content TEXT
-- column is left nullable and emptied for any rows that predate this
-- change. No backfill: existing pre-production reports are not migrated.

ALTER TABLE report_request
    ADD COLUMN storage_key VARCHAR(500);

UPDATE report_request
   SET report_content = NULL
 WHERE report_content IS NOT NULL;
