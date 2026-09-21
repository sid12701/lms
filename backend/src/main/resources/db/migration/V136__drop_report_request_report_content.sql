-- L03: drop the obsolete report_request.report_content column.
--
-- The column carried inline generated report bodies until V68 moved them to
-- object storage behind storage_key and emptied every pre-existing row. It has
-- no entity mapping (ReportRequest has never carried a field for it since the
-- storage cutover) and no remaining reader or writer in code or SQL — only the
-- V30 CREATE TABLE and the V68 cleanup touch it. Consumer inventory is
-- therefore complete: dropping the column cannot change any runtime behaviour.
--
-- Rollback: the data is already gone (V68 set every row to NULL); re-adding the
-- column restores the shape only, so this is intentionally one-way.

ALTER TABLE report_request
    DROP COLUMN report_content;
